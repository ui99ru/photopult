package ru.ui99.photopult.camera

import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.view.Surface
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.ui99.photopult.codec.AdaptiveController
import ru.ui99.photopult.codec.BitrateLadder
import ru.ui99.photopult.codec.H264Encoder
import ru.ui99.photopult.codec.LocalPreview
import ru.ui99.photopult.codec.StreamFraming
import ru.ui99.photopult.net.nearby.NearbyConnectionManager
import ru.ui99.photopult.net.protocol.CameraEvent
import ru.ui99.photopult.net.protocol.RemoteCommand
import ru.ui99.photopult.net.transfer.TransferQueue
import ru.ui99.photopult.util.PhotoStorage
import ru.ui99.photopult.util.PhotopultLog

/**
 * Camera-role coordinator: CameraX → encoder → Nearby STREAM, the adaptive quality loop, and the
 * capture pipeline (shutter, timer, burst, save + background full-quality transfer to the remote).
 */
class CameraSession(
    private val context: Context,
    private val manager: NearbyConnectionManager,
    private val transferQueue: TransferQueue,
    private val onCountdown: (Int?) -> Unit = {},
    private val onSnapped: () -> Unit = {},
    private val onPhotoSaved: (Uri?, String) -> Unit = { _, _ -> },
) {
    private companion object {
        const val FPS = 30
        const val GOP_SECONDS = 1
        const val MONITOR_INTERVAL_MS = 1_000L
        const val BURST_INTERVAL_MS = 500L
    }

    private var currentRung = BitrateLadder.best
    private val controller = CameraController(context, currentRung.width, currentRung.height)
    private val surfaceExecutor = Executors.newSingleThreadExecutor()
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adaptive = AdaptiveController()
    private val blockedNanos = AtomicLong(0)

    private var encoder: H264Encoder? = null
    private val writeLock = Any()
    private var monitorJob: Job? = null
    private var countdownJob: Job? = null
    private var burstJob: Job? = null
    private var lastLevel = 3
    private val payloadToPhoto = HashMap<Long, String>()

    // On-device preview: decode the frames we're streaming and show them on the camera screen.
    private val localPreview = LocalPreview()
    private val _localConfig = MutableStateFlow<CameraEvent.StreamConfig?>(null)
    /** Orientation/size of the local preview, for aspect-fit on the camera screen. */
    val localConfig: StateFlow<CameraEvent.StreamConfig?> = _localConfig.asStateFlow()

    @Volatile private var streamOut: OutputStream? = null

    // Device-authoritative rotation, delivered by CameraX via the preview SurfaceRequest.
    @Volatile private var streamRotation = 0
    @Volatile private var haveRotation = false

    fun start() {
        PhotopultLog.i("CameraSession.start")
        // Keep the process (and CameraX session) alive through calls/backgrounding.
        CaptureForegroundService.start(context)
        manager.commandListener = { handleCommand(it) }
        manager.transferUpdateListener = { onTransferUpdate(it) }
        controller.onCameraReady = { onCameraReady() }
        startEncoder(currentRung)
        streamOut = manager.openOutgoingStream()
        controller.start(encoderSurfaceProvider())
        monitorJob = scope.launch { monitorLoop() }
        // A reconnect may have left transfers unfinished — re-offer them.
        transferQueue.requeueSending()
        drainQueue()
    }

    private fun startEncoder(rung: BitrateLadder.Rung) {
        localPreview.setResolution(rung.width, rung.height)
        val enc = H264Encoder(rung.width, rung.height, rung.bitrate, FPS, GOP_SECONDS) { data, pts, key, config ->
            // Show the same frames locally on the camera screen (fresh array per callback — safe to hold).
            localPreview.submit(StreamFraming.Frame(pts, key, config, data))
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

    /** Camera screen supplies the Surface for the on-device preview. */
    fun setLocalPreviewSurface(surface: Surface?) = localPreview.setSurface(surface)

    private fun encoderSurfaceProvider() = Preview.SurfaceProvider { request ->
        // CameraX tells us exactly how many degrees to rotate the frame to be upright (accounting
        // for the sensor and the current device orientation) — the source of truth for rotation.
        request.setTransformationInfoListener(ContextCompat.getMainExecutor(context)) { info ->
            val rot = ((info.rotationDegrees % 360) + 360) % 360
            streamRotation = rot
            haveRotation = true
            publishStreamConfig()
        }
        val surface = encoder?.inputSurface
        if (surface != null) {
            request.provideSurface(surface, surfaceExecutor) { /* surface released by camera */ }
        } else {
            request.willNotProvideSurface()
        }
    }

    /** Broadcast the current preview geometry to the remote + the local preview. */
    private fun publishStreamConfig() {
        if (!haveRotation) return
        val config = CameraEvent.StreamConfig(
            width = currentRung.width,
            height = currentRung.height,
            rotationDegrees = streamRotation,
            mirrored = PreviewOrientation.isMirrored(controller.isFront()),
            fps = FPS,
        )
        _localConfig.value = config
        manager.sendEvent(config)
        encoder?.requestKeyframe()
    }

    // ---- Adaptive quality ----

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
            encoder?.stop()
            startEncoder(target)
            controller.rebind()
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
        // Re-publish geometry (lens switch / rebind may change mirroring); rotation comes from the
        // CameraX transformation listener. sendState reports the rest.
        publishStreamConfig()
        sendState()
        encoder?.requestKeyframe()
    }

    // ---- Commands ----

    private fun handleCommand(command: RemoteCommand) {
        when (command) {
            RemoteCommand.GetState -> sendState()
            is RemoteCommand.Zoom -> {
                controller.setZoomRatio(command.ratio)
                sendState()
            }
            RemoteCommand.SwitchCamera -> controller.switchLens()
            is RemoteCommand.Shutter -> startCapture(command.timerSec)
            RemoteCommand.BurstStart -> startBurst()
            RemoteCommand.BurstStop -> stopBurst()
            is RemoteCommand.SetFlash -> {
                controller.setFlash(command.mode)
                sendState()
            }
            is RemoteCommand.Focus -> controller.focusAt(command.x, command.y)
            is RemoteCommand.SetExposure -> {
                controller.setExposureIndex(command.index)
                sendState()
            }
        }
    }

    // ---- Capture ----

    private fun startCapture(timerSec: Int) {
        if (timerSec <= 0) {
            capturePhoto()
            return
        }
        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (s in timerSec downTo 1) {
                manager.sendEvent(CameraEvent.Countdown(s))
                onCountdown(s)
                delay(1_000)
            }
            manager.sendEvent(CameraEvent.Countdown(0))
            onCountdown(0)
            capturePhoto()
            delay(400)
            onCountdown(null)
        }
    }

    private fun startBurst() {
        if (burstJob != null) return
        PhotopultLog.i("burst start")
        burstJob = scope.launch {
            while (isActive) {
                capturePhoto()
                delay(BURST_INTERVAL_MS)
            }
        }
    }

    private fun stopBurst() {
        PhotopultLog.i("burst stop")
        burstJob?.cancel()
        burstJob = null
    }

    private fun capturePhoto() {
        val imageCapture = controller.imageCapture()
        if (imageCapture == null) {
            manager.sendEvent(CameraEvent.CaptureDone(false, newPhotoId(), "Камера ещё не готова"))
            return
        }
        imageCapture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        onCaptured(image.readJpeg())
                    } catch (e: Exception) {
                        PhotopultLog.e("capture post-process failed", e)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    PhotopultLog.e("takePicture failed", exception)
                    manager.sendEvent(CameraEvent.CaptureDone(false, newPhotoId(), "Не удалось сделать снимок"))
                }
            },
        )
    }

    private fun onCaptured(bytes: ByteArray) {
        val photoId = newPhotoId()
        val displayName = "Photopult_$photoId.jpg"
        PhotopultLog.i("captured $photoId (${bytes.size} bytes)")
        val thumbnail = PhotoStorage.makeThumbnailBase64(bytes)
        // Camera keeps its full-quality copy.
        val savedUri = PhotoStorage.saveJpegToGallery(context, bytes, displayName)
        // Instant thumbnail + done event to the remote.
        manager.sendEvent(CameraEvent.Thumbnail(photoId, thumbnail))
        manager.sendEvent(CameraEvent.CaptureDone(true, photoId))
        onSnapped()
        onPhotoSaved(savedUri, thumbnail)
        // Queue the full file for background transfer (survives reconnect).
        val cacheFile = PhotoStorage.writeCacheFile(context, bytes, "$photoId.jpg")
        transferQueue.add(photoId, cacheFile.absolutePath)
        drainQueue()
    }

    private fun drainQueue() {
        while (true) {
            val item = transferQueue.nextPending() ?: break
            val payloadId = manager.sendFile(File(item.path))
            if (payloadId == null) break // not connected — reconnect will requeue and retry
            payloadToPhoto[payloadId] = item.photoId
            manager.sendEvent(CameraEvent.PhotoIncoming(item.photoId, payloadId))
        }
    }

    private fun onTransferUpdate(update: PayloadTransferUpdate) {
        val photoId = payloadToPhoto[update.payloadId] ?: return
        when (update.status) {
            PayloadTransferUpdate.Status.SUCCESS -> {
                PhotopultLog.i("photo $photoId delivered")
                transferQueue.markSent(photoId)
                payloadToPhoto.remove(update.payloadId)
            }
            PayloadTransferUpdate.Status.FAILURE,
            PayloadTransferUpdate.Status.CANCELED,
            -> {
                PhotopultLog.w("photo $photoId transfer failed — will retry on reconnect")
                payloadToPhoto.remove(update.payloadId)
            }
            else -> Unit // IN_PROGRESS
        }
    }

    private fun sendState() {
        val snap = controller.snapshot()
        manager.sendEvent(
            CameraEvent.State(
                battery = batteryPercent(),
                flash = snap.flashMode,
                lens = if (controller.isFront()) "front" else "back",
                zoomRatio = snap.zoomRatio,
                maxZoom = snap.maxZoomRatio,
                resolution = "${currentRung.width}x${currentRung.height}",
                evIndex = snap.evIndex,
                evMin = snap.evMin,
                evMax = snap.evMax,
            ),
        )
    }

    private fun batteryPercent(): Int = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: -1
    }.getOrDefault(-1)

    private fun newPhotoId(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    /** For the debug screen. */
    fun currentBitrate(): Int = currentRung.bitrate
    fun currentResolution(): String = "${currentRung.width}x${currentRung.height}"
    fun pendingTransfers(): Int = transferQueue.pendingCount()

    fun stop() {
        PhotopultLog.i("CameraSession.stop")
        manager.commandListener = null
        manager.transferUpdateListener = null
        countdownJob?.cancel()
        burstJob?.cancel()
        monitorJob?.cancel()
        scope.cancel()
        controller.stop()
        localPreview.stop()
        _localConfig.value = null
        encoder?.stop()
        encoder = null
        runCatching { streamOut?.close() }
        streamOut = null
        runCatching { surfaceExecutor.shutdown() }
        runCatching { captureExecutor.shutdown() }
        CaptureForegroundService.stop(context)
    }
}

private fun ImageProxy.readJpeg(): ByteArray {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}
