package ru.ui99.photopult.camera

import android.content.Context
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import ru.ui99.photopult.util.PhotopultLog

/**
 * Owns the CameraX session on the camera phone: it feeds preview frames straight into the encoder's
 * input Surface (lowest-latency path) and keeps an [ImageCapture] use case for full-quality photos
 * (Stage 5). Zoom and lens switching are applied here and reported back through the state protocol.
 *
 * CameraX is bound to this controller's **own** lifecycle (not the Activity's), kept RESUMED for the
 * whole session. That's what lets the camera keep streaming when the phone's screen turns off /
 * goes to standby — paired with the foreground service (type `camera`) and a wake lock, the session
 * survives the display sleeping instead of tearing down with the Activity.
 */
class CameraController(
    private val context: Context,
    private val targetWidth: Int = 1280,
    private val targetHeight: Int = 720,
) : LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var encoderSurfaceProvider: Preview.SurfaceProvider? = null

    /**
     * Tracks the phone's physical orientation and feeds it to CameraX as the use cases' target
     * rotation. CameraX then reports (via the preview SurfaceRequest's TransformationInfo) exactly
     * how many degrees the frame must be rotated to be upright — the device-authoritative value we
     * send to the remote. This also keeps captured-photo EXIF orientation correct.
     */
    private var lastRotation = -1
    private val orientationListener by lazy {
        object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (rotation == lastRotation) return
                lastRotation = rotation
                preview?.targetRotation = rotation
                imageCapture?.targetRotation = rotation
                PhotopultLog.d("targetRotation -> $rotation")
            }
        }
    }

    /** Persisted across rebind (lens switch / resolution change) so the setting sticks. */
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    /** Callbacks so the session/state layer can react without this class depending on it. */
    var onCameraReady: ((CameraInfoSnapshot) -> Unit)? = null

    data class CameraInfoSnapshot(
        val lensFacing: Int,
        val maxZoomRatio: Float,
        val zoomRatio: Float,
        val sensorRotationDegrees: Int,
        val flashMode: String,
        val evIndex: Int,
        val evMin: Int,
        val evMax: Int,
    )

    fun start(surfaceProvider: Preview.SurfaceProvider) {
        encoderSurfaceProvider = surfaceProvider
        // Keep our lifecycle RESUMED so CameraX stays bound even when the screen turns off.
        ContextCompat.getMainExecutor(context).execute {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                provider = future.get()
                bind()
            }.onFailure { PhotopultLog.e("camera start failed", it) }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind() {
        val cameraProvider = provider ?: return
        val surfaceProvider = encoderSurfaceProvider ?: return

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(targetWidth, targetHeight),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()
        val previewUseCase = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
            .also { it.setSurfaceProvider(executor, surfaceProvider) }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
            .also { it.flashMode = flashMode }
        // Apply the current physical orientation so CameraX reports the right rotation from the start.
        if (lastRotation >= 0) {
            previewUseCase.targetRotation = lastRotation
            capture.targetRotation = lastRotation
        }
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        cameraProvider.unbindAll()
        val boundCamera = cameraProvider.bindToLifecycle(this, selector, previewUseCase, capture)
        camera = boundCamera
        imageCapture = capture
        preview = previewUseCase

        val info = boundCamera.cameraInfo
        PhotopultLog.i("camera bound lens=$lensFacing sensorRot=${info.sensorRotationDegrees}")
        onCameraReady?.invoke(snapshot())
    }

    fun setZoomRatio(ratio: Float) {
        val control = camera?.cameraControl ?: return
        val clamped = ratio.coerceIn(1f, maxZoom())
        control.setZoomRatio(clamped)
        PhotopultLog.d("setZoomRatio $clamped")
    }

    fun switchLens() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        PhotopultLog.i("switchLens -> $lensFacing")
        rebind()
    }

    /** Re-bind the use cases on the main thread (safe to call from any thread). */
    fun rebind() {
        ContextCompat.getMainExecutor(context).execute { bind() }
    }

    /** Set the flash mode used for the next capture. Accepts "off" / "on" / "auto". */
    fun setFlash(mode: String) {
        flashMode = when (mode) {
            "on" -> ImageCapture.FLASH_MODE_ON
            "auto" -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        PhotopultLog.d("setFlash $mode")
    }

    private fun flashName(): String = when (flashMode) {
        ImageCapture.FLASH_MODE_ON -> "on"
        ImageCapture.FLASH_MODE_AUTO -> "auto"
        else -> "off"
    }

    /**
     * Tap-to-focus at a point normalized to the encoder surface (0..1). CameraX's
     * [SurfaceOrientedMeteringPointFactory] maps it to sensor coordinates for us.
     */
    fun focusAt(x: Float, y: Float) {
        val control = camera?.cameraControl ?: return
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f)
            .createPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        runCatching { control.startFocusAndMetering(action) }
            .onFailure { PhotopultLog.w("focus failed: ${it.message}") }
        PhotopultLog.d("focusAt $x,$y")
    }

    /** Exposure compensation as a raw index; clamped to the sensor's supported range. */
    fun setExposureIndex(index: Int) {
        val control = camera?.cameraControl ?: return
        val range = exposureRange()
        val clamped = index.coerceIn(range.first, range.second)
        runCatching { control.setExposureCompensationIndex(clamped) }
            .onFailure { PhotopultLog.w("exposure failed: ${it.message}") }
        PhotopultLog.d("setExposureIndex $clamped")
    }

    private fun exposureRange(): Pair<Int, Int> {
        val state = camera?.cameraInfo?.exposureState ?: return 0 to 0
        if (!state.isExposureCompensationSupported) return 0 to 0
        val range = state.exposureCompensationRange
        return range.lower to range.upper
    }

    private fun currentExposureIndex(): Int =
        camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0

    fun imageCapture(): ImageCapture? = imageCapture

    fun isFront(): Boolean = lensFacing == CameraSelector.LENS_FACING_FRONT

    fun sensorRotationDegrees(): Int = camera?.cameraInfo?.sensorRotationDegrees ?: 0

    private fun maxZoom(): Float =
        camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f

    private fun currentZoom(): Float =
        camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    fun snapshot(): CameraInfoSnapshot {
        val range = exposureRange()
        return CameraInfoSnapshot(
            lensFacing = lensFacing,
            maxZoomRatio = maxZoom(),
            zoomRatio = currentZoom(),
            sensorRotationDegrees = sensorRotationDegrees(),
            flashMode = flashName(),
            evIndex = currentExposureIndex(),
            evMin = range.first,
            evMax = range.second,
        )
    }

    fun stop() {
        runCatching { orientationListener.disable() }
        runCatching { provider?.unbindAll() }
        runCatching { executor.shutdown() }
        camera = null
        imageCapture = null
        preview = null
        ContextCompat.getMainExecutor(context).execute {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        PhotopultLog.i("camera stopped")
    }
}
