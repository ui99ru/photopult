package ru.ui99.photopult.ui.connect

import android.app.Application
import android.content.pm.PackageManager
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import ru.ui99.photopult.camera.CameraSession
import ru.ui99.photopult.codec.PreviewReceiver
import ru.ui99.photopult.net.nearby.ConnectionState
import ru.ui99.photopult.net.nearby.NearbyConnectionManager
import ru.ui99.photopult.net.protocol.RemoteCommand
import ru.ui99.photopult.net.protocol.Role
import ru.ui99.photopult.util.PairingStore
import ru.ui99.photopult.util.Permissions
import ru.ui99.photopult.util.PhotopultLog

/**
 * Activity-scoped holder for the Nearby connection. Kept across the connect/debug screens so the
 * link survives navigation, and torn down when the activity finishes.
 */
class NearbyViewModel(application: Application) : AndroidViewModel(application) {

    private val pairing = PairingStore(application)
    private val manager = NearbyConnectionManager(application, viewModelScope, pairing)
    val state = manager.state
    val peerState = manager.peerState
    val streamConfig = manager.streamConfig
    val linkQuality = manager.linkQuality

    private var started = false

    private var cameraSession: CameraSession? = null
    private var previewReceiver: PreviewReceiver? = null

    /** Remembered role from a previous pairing, if any (used to skip role selection on launch). */
    val rememberedRole: Role? get() = manager.rememberedRole
    fun hasRememberedPeer(): Boolean = manager.hasRememberedPeer()
    fun permissionsGranted(): Boolean = missingPermissions().isEmpty()

    /** Idempotent: starts advertising/discovery once for the chosen role. */
    fun start(role: Role) {
        if (started) return
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            PhotopultLog.e("cannot start Nearby, missing permissions: $missing")
            return
        }
        started = true
        manager.start(role)
    }

    fun connectTo(endpointId: String) = manager.connectTo(endpointId)

    fun requestState() = manager.requestState()

    fun confirm() = manager.confirm()

    fun reject() = manager.reject()

    fun retry() {
        started = true
        manager.retry()
    }

    /** Called when the user leaves the connection flow. */
    fun reset() {
        started = false
        manager.stop()
    }

    /** Forget the remembered pair and role, then tear down. */
    fun forgetPairing() {
        started = false
        manager.forgetPeer()
    }

    fun isConnected(): Boolean = state.value is ConnectionState.Connected

    // ---- Preview streaming (Stage 3) ----

    /** Camera role: start capturing and streaming to the connected remote. */
    fun startCameraSession(lifecycleOwner: LifecycleOwner, deviceRotationProvider: () -> Int) {
        if (cameraSession != null) return
        cameraSession = CameraSession(getApplication<Application>(), lifecycleOwner, manager, deviceRotationProvider)
            .also { it.start() }
    }

    /** Remote role: start receiving/decoding the preview. Feed it a surface + config. */
    fun startPreviewReceiver() {
        if (previewReceiver != null) return
        val receiver = PreviewReceiver(manager).also { it.start() }
        previewReceiver = receiver
        viewModelScope.launch {
            streamConfig.collect { config -> config?.let { receiver.setConfig(it) } }
        }
    }

    fun setPreviewSurface(surface: Surface?) = previewReceiver?.setSurface(surface)

    fun sendZoom(ratio: Float) = manager.sendCommand(RemoteCommand.Zoom(ratio))

    fun switchCamera() = manager.sendCommand(RemoteCommand.SwitchCamera)

    fun previewFramesRendered(): Long = previewReceiver?.framesRendered ?: 0
    fun previewFramesDropped(): Long = previewReceiver?.framesDropped ?: 0

    /** Camera-side adaptive stream metrics (debug). */
    fun cameraBitrate(): Int = cameraSession?.currentBitrate() ?: 0
    fun cameraResolution(): String? = cameraSession?.currentResolution()

    /** Stop preview sessions (leaving the connected screen / disconnect). */
    fun stopSessions() {
        cameraSession?.stop()
        cameraSession = null
        previewReceiver?.stop()
        previewReceiver = null
    }

    private fun missingPermissions(): List<String> {
        val context = getApplication<Application>()
        return Permissions.required().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onCleared() {
        stopSessions()
        manager.stop()
    }
}
