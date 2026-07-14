package ru.ui99.photopult.net.nearby

import android.content.Context
import android.os.Build
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.ui99.photopult.net.protocol.Role
import ru.ui99.photopult.util.PhotopultLog

/**
 * Wraps Nearby Connections for iteration (a): the camera advertises, the remote discovers and
 * connects, and both confirm a matching code before the link is accepted.
 *
 * Every meaningful callback and error (with its status code) is logged under the `Photopult` tag,
 * so a two-phone test is fully traceable via `adb logcat -s Photopult` and the debug screen.
 * Payload exchange (getState/state) and auto-reconnect land in later iterations.
 */
class NearbyConnectionManager(context: Context) {

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    private val localName: String = (Build.MODEL ?: "Phone").take(40)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var role: Role? = null
    private val discovered = linkedMapOf<String, DiscoveredEndpoint>()
    private var pending: ConnectionState.Confirming? = null

    fun start(role: Role) {
        this.role = role
        PhotopultLog.i("start(role=$role, localName=$localName)")
        when (role) {
            Role.CAMERA -> startAdvertising()
            Role.REMOTE -> startDiscovery()
        }
    }

    private fun startAdvertising() {
        _state.value = ConnectionState.Advertising(localName)
        val options = AdvertisingOptions.Builder().setStrategy(NearbyConfig.STRATEGY).build()
        PhotopultLog.i("startAdvertising as \"$localName\" service=${NearbyConfig.SERVICE_ID}")
        client.startAdvertising(localName, NearbyConfig.SERVICE_ID, lifecycleCallback, options)
            .addOnSuccessListener { PhotopultLog.i("advertising started") }
            .addOnFailureListener { e ->
                PhotopultLog.e("startAdvertising failed", e)
                _state.value = ConnectionState.Failed("Не удалось стать видимым для пульта")
            }
    }

    private fun startDiscovery() {
        discovered.clear()
        _state.value = ConnectionState.Discovering(emptyList())
        val options = DiscoveryOptions.Builder().setStrategy(NearbyConfig.STRATEGY).build()
        PhotopultLog.i("startDiscovery service=${NearbyConfig.SERVICE_ID}")
        client.startDiscovery(NearbyConfig.SERVICE_ID, discoveryCallback, options)
            .addOnSuccessListener { PhotopultLog.i("discovery started") }
            .addOnFailureListener { e ->
                PhotopultLog.e("startDiscovery failed", e)
                _state.value = ConnectionState.Failed("Не удалось начать поиск камер")
            }
    }

    /** Remote taps a discovered camera. */
    fun connectTo(endpointId: String) {
        val target = discovered[endpointId]
        PhotopultLog.i("requestConnection to $endpointId (${target?.name})")
        // Stop discovering while we negotiate this connection.
        client.stopDiscovery()
        client.requestConnection(localName, endpointId, lifecycleCallback)
            .addOnSuccessListener { PhotopultLog.i("requestConnection sent") }
            .addOnFailureListener { e ->
                PhotopultLog.e("requestConnection failed", e)
                _state.value = ConnectionState.Failed("Не удалось подключиться к камере")
            }
    }

    /** Both sides press "yes, it's the one". */
    fun confirm() {
        val p = pending ?: return
        PhotopultLog.i("acceptConnection ${p.endpointId}")
        client.acceptConnection(p.endpointId, payloadCallback)
            .addOnFailureListener { e -> PhotopultLog.e("acceptConnection failed", e) }
    }

    fun reject() {
        val p = pending ?: return
        PhotopultLog.i("rejectConnection ${p.endpointId}")
        client.rejectConnection(p.endpointId)
            .addOnFailureListener { e -> PhotopultLog.e("rejectConnection failed", e) }
        pending = null
        restartDiscoveryOrAdvertising()
    }

    /** Tear everything down (leaving the flow). */
    fun stop() {
        PhotopultLog.i("stop() — stopping advertising/discovery, disconnecting")
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        discovered.clear()
        pending = null
        _state.value = ConnectionState.Idle
    }

    /** Retry after a failure. */
    fun retry() {
        role?.let { start(it) }
    }

    private fun restartDiscoveryOrAdvertising() {
        when (role) {
            Role.CAMERA -> startAdvertising()
            Role.REMOTE -> startDiscovery()
            null -> Unit
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            PhotopultLog.i("onEndpointFound $endpointId name=${info.endpointName}")
            discovered[endpointId] = DiscoveredEndpoint(endpointId, info.endpointName)
            emitDiscovered()
        }

        override fun onEndpointLost(endpointId: String) {
            PhotopultLog.i("onEndpointLost $endpointId")
            discovered.remove(endpointId)
            emitDiscovered()
        }
    }

    private fun emitDiscovered() {
        // Only overwrite the state while we're still in the discovery phase.
        if (_state.value is ConnectionState.Discovering) {
            _state.value = ConnectionState.Discovering(discovered.values.toList())
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val token = info.authenticationDigits
            val confirming = ConnectionState.Confirming(
                endpointId = endpointId,
                peerName = info.endpointName,
                code = ConfirmationCode.digits(token),
                emojis = ConfirmationCode.emojis(token),
                incoming = info.isIncomingConnection,
            )
            PhotopultLog.i(
                "onConnectionInitiated $endpointId peer=${info.endpointName} " +
                    "incoming=${info.isIncomingConnection} code=${confirming.code}",
            )
            pending = confirming
            _state.value = confirming
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val code = resolution.status.statusCode
            when (code) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val peer = pending?.peerName ?: endpointId
                    PhotopultLog.i("onConnectionResult $endpointId OK — connected to $peer")
                    pending = null
                    // Camera no longer needs to advertise once paired.
                    if (role == Role.CAMERA) client.stopAdvertising()
                    _state.value = ConnectionState.Connected(endpointId, peer)
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    PhotopultLog.w("onConnectionResult $endpointId REJECTED")
                    pending = null
                    restartDiscoveryOrAdvertising()
                }
                else -> {
                    val name = ConnectionsStatusCodes.getStatusCodeString(code)
                    PhotopultLog.e("onConnectionResult $endpointId failed: $code ($name)")
                    pending = null
                    _state.value = ConnectionState.Failed("Соединение не установилось. Попробуйте снова")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            PhotopultLog.w("onDisconnected $endpointId — returning to search")
            pending = null
            // Full auto-reconnect with state restore comes in iteration (d).
            restartDiscoveryOrAdvertising()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            // getState/state exchange arrives in iteration (b); log for now.
            PhotopultLog.d("onPayloadReceived from $endpointId type=${payload.type}")
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // No-op until iteration (b).
        }
    }
}
