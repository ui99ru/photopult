package ru.ui99.photopult.camera

import android.content.Context
import android.os.BatteryManager
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import java.io.OutputStream
import java.util.concurrent.Executors
import ru.ui99.photopult.codec.H264Encoder
import ru.ui99.photopult.codec.StreamFraming
import ru.ui99.photopult.net.nearby.NearbyConnectionManager
import ru.ui99.photopult.net.protocol.CameraEvent
import ru.ui99.photopult.net.protocol.RemoteCommand
import ru.ui99.photopult.util.PhotopultLog

/**
 * Camera-role coordinator: CameraX → encoder → Nearby STREAM, and remote commands → CameraX.
 * Started when a connection is up, stopped when it drops or the screen leaves.
 */
class CameraSession(
    private val context: Context,
    lifecycleOwner: LifecycleOwner,
    private val manager: NearbyConnectionManager,
    private val deviceRotationProvider: () -> Int,
) {
    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FPS = 30
        const val BITRATE = 3_000_000
        const val GOP_SECONDS = 1
    }

    private val controller = CameraController(context, lifecycleOwner, WIDTH, HEIGHT)
    private val surfaceExecutor = Executors.newSingleThreadExecutor()
    private var encoder: H264Encoder? = null
    private val writeLock = Any()

    @Volatile private var streamOut: OutputStream? = null

    fun start() {
        PhotopultLog.i("CameraSession.start")
        manager.commandListener = { handleCommand(it) }
        controller.onCameraReady = { onCameraReady() }

        val enc = H264Encoder(WIDTH, HEIGHT, BITRATE, FPS, GOP_SECONDS) { data, pts, key, config ->
            val out = streamOut ?: return@H264Encoder
            try {
                synchronized(writeLock) {
                    StreamFraming.writeFrame(out, data, 0, data.size, pts, key, config)
                }
            } catch (e: Exception) {
                PhotopultLog.w("stream write failed: ${e.message}")
            }
        }
        enc.start()
        encoder = enc
        streamOut = manager.openOutgoingStream()
        controller.start(encoderSurfaceProvider())
    }

    /** Change the preview bitrate (Stage 4 adaptive ladder). */
    fun setBitrate(bps: Int) = encoder?.setBitrate(bps)

    private fun encoderSurfaceProvider() = Preview.SurfaceProvider { request ->
        val surface = encoder?.inputSurface
        if (surface != null) {
            request.provideSurface(surface, surfaceExecutor) { /* surface released by camera */ }
        } else {
            request.willNotProvideSurface()
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
                width = WIDTH,
                height = HEIGHT,
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
                resolution = "${WIDTH}x$HEIGHT",
            ),
        )
    }

    private fun batteryPercent(): Int = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: -1
    }.getOrDefault(-1)

    fun stop() {
        PhotopultLog.i("CameraSession.stop")
        manager.commandListener = null
        controller.stop()
        encoder?.stop()
        encoder = null
        runCatching { streamOut?.close() }
        streamOut = null
        runCatching { surfaceExecutor.shutdown() }
    }
}
