package ru.ui99.photopult.camera

import android.content.Context
import android.os.BatteryManager
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.ui99.photopult.codec.AdaptiveController
import ru.ui99.photopult.codec.BitrateLadder
import ru.ui99.photopult.codec.H264Encoder
import ru.ui99.photopult.codec.StreamFraming
import ru.ui99.photopult.net.nearby.NearbyConnectionManager
import ru.ui99.photopult.net.protocol.CameraEvent
import ru.ui99.photopult.net.protocol.RemoteCommand
import ru.ui99.photopult.util.PhotopultLog

/**
 * Camera-role coordinator: CameraX → encoder → Nearby STREAM, remote commands → CameraX, and the
 * adaptive quality loop. Started when a connection is up, stopped when it drops or the screen
 * leaves.
 */
class CameraSession(
    private val context: Context,
    lifecycleOwner: LifecycleOwner,
    private val manager: NearbyConnectionManager,
    private val deviceRotationProvider: () -> Int,
) {
    private companion object {
        const val FPS = 30
        const val GOP_SECONDS = 1
        const val MONITOR_INTERVAL_MS = 1_000L
    }

    private var currentRung = BitrateLadder.best
    private val controller = CameraController(context, lifecycleOwner, currentRung.width, currentRung.height)
    private val surfaceExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adaptive = AdaptiveController()
    private val blockedNanos = AtomicLong(0)

    private var encoder: H264Encoder? = null
    private val writeLock = Any()
    private var monitorJob: Job? = null
    private var lastLevel = 3

    @Volatile private var streamOut: OutputStream? = null

    fun start() {
        PhotopultLog.i("CameraSession.start")
        manager.commandListener = { handleCommand(it) }
        controller.onCameraReady = { onCameraReady() }
        startEncoder(currentRung)
        streamOut = manager.openOutgoingStream()
        controller.start(encoderSurfaceProvider())
        monitorJob = scope.launch { monitorLoop() }
    }

    private fun startEncoder(rung: BitrateLadder.Rung) {
        val enc = H264Encoder(rung.width, rung.height, rung.bitrate, FPS, GOP_SECONDS) { data, pts, key, config ->
            val out = streamOut ?: return@H264Encoder
            val start = System.nanoTime()
            try {
                synchronized(writeLock) {
                    StreamFraming.writeFrame(out, data, 0, data.size, pts, key, config)
                }
            } catch (e: Exception) {
                PhotopultLog.w("stream write failed: ${e.message}")
            }
            blockedNanos.addAndGet(System.nanoTime() - start)
        }
        enc.start()
        encoder = enc
    }

    private fun encoderSurfaceProvider() = Preview.SurfaceProvider { request ->
        val surface = encoder?.inputSurface
        if (surface != null) {
            request.provideSurface(surface, surfaceExecutor) { /* surface released by camera */ }
        } else {
            request.willNotProvideSurface()
        }
    }

    private suspend fun monitorLoop() {
        while (true) {
            delay(MONITOR_INTERVAL_MS)
            val blocked = blockedNanos.getAndSet(0)
            val congestion = (blocked.toFloat() / (MONITOR_INTERVAL_MS * 1_000_000f)).coerceIn(0f, 1f)
            val newRung = adaptive.onSample(congestion)
            if (newRung != currentRungIndex()) applyRung(newRung)
            sendLinkQualityIfChanged()
        }
    }

    private fun currentRungIndex(): Int = BitrateLadder.RUNGS.indexOf(currentRung).coerceAtLeast(0)

    private fun applyRung(index: Int) {
        val target = BitrateLadder.rung(index)
        val sameResolution = target.width == currentRung.width && target.height == currentRung.height
        PhotopultLog.i("adaptive: rung ${currentRungIndex()} -> $index (${target.width}x${target.height} ${target.bitrate}bps)")
        currentRung = target
        if (sameResolution) {
            encoder?.setBitrate(target.bitrate)
        } else {
            // Resolution change needs a fresh encoder + a new camera surface + decoder reconfigure.
            encoder?.stop()
            startEncoder(target)
            controller.rebind() // camera re-requests → provides the new encoder surface, fires onCameraReady
        }
    }

    private fun sendLinkQualityIfChanged() {
        val level = BitrateLadder.linkQualityLevel(currentRungIndex())
        if (level != lastLevel) {
            lastLevel = level
            manager.sendEvent(CameraEvent.LinkQuality(level))
        }
    }

    private fun onCameraReady() {
        val snap = controller.snapshot()
        val rotation = PreviewOrientation.rotationForUpright(
            sensorRotation = snap.sensorRotationDegrees,
            deviceRotation = deviceRotationProvider(),
            front = controller.isFront(),
        )
        manager.sendEvent(
            CameraEvent.StreamConfig(
                width = currentRung.width,
                height = currentRung.height,
                rotationDegrees = rotation,
                mirrored = PreviewOrientation.isMirrored(controller.isFront()),
                fps = FPS,
            ),
        )
        sendState()
        encoder?.requestKeyframe()
    }

    private fun handleCommand(command: RemoteCommand) {
        when (command) {
            RemoteCommand.GetState -> sendState()
            is RemoteCommand.Zoom -> {
                controller.setZoomRatio(command.ratio)
                sendState()
            }
            RemoteCommand.SwitchCamera -> controller.switchLens() // re-binds → onCameraReady
        }
    }

    private fun sendState() {
        val snap = controller.snapshot()
        manager.sendEvent(
            CameraEvent.State(
                battery = batteryPercent(),
                flash = "off",
                lens = if (controller.isFront()) "front" else "back",
                zoomRatio = snap.zoomRatio,
                maxZoom = snap.maxZoomRatio,
                resolution = "${currentRung.width}x${currentRung.height}",
            ),
        )
    }

    private fun batteryPercent(): Int = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: -1
    }.getOrDefault(-1)

    /** For the debug screen. */
    fun currentBitrate(): Int = currentRung.bitrate
    fun currentResolution(): String = "${currentRung.width}x${currentRung.height}"

    fun stop() {
        PhotopultLog.i("CameraSession.stop")
        manager.commandListener = null
        monitorJob?.cancel()
        scope.cancel()
        controller.stop()
        encoder?.stop()
        encoder = null
        runCatching { streamOut?.close() }
        streamOut = null
        runCatching { surfaceExecutor.shutdown() }
    }
}
