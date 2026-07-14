package ru.ui99.photopult.camera

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import ru.ui99.photopult.util.PhotopultLog

/**
 * Owns the CameraX session on the camera phone: it feeds preview frames straight into the encoder's
 * input Surface (lowest-latency path) and keeps an [ImageCapture] use case for full-quality photos
 * (Stage 5). Zoom and lens switching are applied here and reported back through the state protocol.
 *
 * The operator frames the shot via the remote's live preview, so there is no separate on-device
 * live PreviewView here (a deliberate simplification — see the PR's accepted decisions); the camera
 * screen shows status and the "Снято!" flash instead, and can dim to save battery.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var encoderSurfaceProvider: Preview.SurfaceProvider? = null

    /** Callbacks so the session/state layer can react without this class depending on it. */
    var onCameraReady: ((CameraInfoSnapshot) -> Unit)? = null

    data class CameraInfoSnapshot(
        val lensFacing: Int,
        val maxZoomRatio: Float,
        val zoomRatio: Float,
        val sensorRotationDegrees: Int,
    )

    fun start(surfaceProvider: Preview.SurfaceProvider) {
        encoderSurfaceProvider = surfaceProvider
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

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(executor, surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        cameraProvider.unbindAll()
        val boundCamera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
        camera = boundCamera
        imageCapture = capture

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
        bind()
    }

    fun imageCapture(): ImageCapture? = imageCapture

    fun isFront(): Boolean = lensFacing == CameraSelector.LENS_FACING_FRONT

    fun sensorRotationDegrees(): Int = camera?.cameraInfo?.sensorRotationDegrees ?: 0

    private fun maxZoom(): Float =
        camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f

    private fun currentZoom(): Float =
        camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    fun snapshot(): CameraInfoSnapshot = CameraInfoSnapshot(
        lensFacing = lensFacing,
        maxZoomRatio = maxZoom(),
        zoomRatio = currentZoom(),
        sensorRotationDegrees = sensorRotationDegrees(),
    )

    fun stop() {
        runCatching { provider?.unbindAll() }
        runCatching { executor.shutdown() }
        camera = null
        imageCapture = null
        PhotopultLog.i("camera stopped")
    }
}
