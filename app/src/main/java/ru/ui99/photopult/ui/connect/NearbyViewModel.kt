package ru.ui99.photopult.ui.connect

import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import ru.ui99.photopult.net.nearby.ConnectionState
import ru.ui99.photopult.net.nearby.NearbyConnectionManager
import ru.ui99.photopult.net.protocol.Role
import ru.ui99.photopult.util.Permissions
import ru.ui99.photopult.util.PhotopultLog

/**
 * Activity-scoped holder for the Nearby connection. Kept across the connect/debug screens so the
 * link survives navigation, and torn down when the activity finishes.
 */
class NearbyViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = NearbyConnectionManager(application)
    val state = manager.state

    private var started = false

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

    fun isConnected(): Boolean = state.value is ConnectionState.Connected

    private fun missingPermissions(): List<String> {
        val context = getApplication<Application>()
        return Permissions.required().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onCleared() {
        manager.stop()
    }
}
