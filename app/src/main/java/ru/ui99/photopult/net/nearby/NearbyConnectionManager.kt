package ru.ui99.photopult.net.nearby

import android.content.Context
import android.os.BatteryManager
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
import ru.ui99.photopult.net.protocol.CameraEvent
import ru.ui99.photopult.net.protocol.RemoteCommand
import ru.ui99.photopult.net.protocol.Role
import ru.ui99.photopult.net.protocol.Wire
import ru.ui99.photopult.util.DeviceName
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

    private val appContext: Context = context.applicationContext
    private val client: ConnectionsClient = Nearby.getConnectionsClient(appContext)
    private val localName: String = DeviceName.of(appContext)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Latest state reported by the camera (remote side only). Null until received. */
    private val _peerState = MutableStateFlow<CameraEvent.State?>(null)
    val peerState: StateFlow<CameraEvent.State?> = _peerState.asStateFlow()

    private var role: Role? = null
    private val discovered = linkedMapOf<String, DiscoveredEndpoint>()
    private var pending: ConnectionState.Confirming? = null
    private var connectedEndpointId: String? = null

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
        connectedEndpointId = null
        _peerState.value = null
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
                    connectedEndpointId = endpointId
                    _peerState.value = null
                    // Camera no longer needs to advertise once paired.
                    if (role == Role.CAMERA) client.stopAdvertising()
                    _state.value = ConnectionState.Connected(endpointId, peer)
                    // Remote asks the camera for its state as soon as the link is up.
                    if (role == Role.REMOTE) requestState()
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
            connectedEndpointId = null
            _peerState.value = null
            // Full auto-reconnect with state restore comes in iteration (d).
            restartDiscoveryOrAdvertising()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) {
                PhotopultLog.d("onPayloadReceived from $endpointId type=${payload.type} (ignored)")
                return
            }
            val bytes = payload.asBytes() ?: return
            when (role) {
                Role.CAMERA -> handleCommand(endpointId, bytes)
                Role.REMOTE -> handleEvent(endpointId, bytes)
                null -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Relevant for STREAM/FILE payloads in later stages; BYTES arrive whole.
        }
    }

    /** Remote → camera: ask for the current state. */
    fun requestState() {
        val id = connectedEndpointId ?: return
        PhotopultLog.i("send getState -> $id")
        send(id, Wire.encode(RemoteCommand.GetState))
    }

    private fun handleCommand(endpointId: String, bytes: ByteArray) {
        val command = runCatching { Wire.decodeCommand(bytes) }.getOrElse { e ->
            PhotopultLog.e("bad command from $endpointId", e); return
        }
        PhotopultLog.i("recv command $command from $endpointId")
        when (command) {
            RemoteCommand.GetState -> {
                val state = currentCameraState()
                PhotopultLog.i("send state -> $endpointId battery=${state.battery}")
                send(endpointId, Wire.encode(state))
            }
        }
    }

    private fun handleEvent(endpointId: String, bytes: ByteArray) {
        val event = runCatching { Wire.decodeEvent(bytes) }.getOrElse { e ->
            PhotopultLog.e("bad event from $endpointId", e); return
        }
        when (event) {
            is CameraEvent.State -> {
                PhotopultLog.i("recv state from $endpointId battery=${event.battery}")
                _peerState.value = event
            }
        }
    }

    private fun send(endpointId: String, bytes: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
            .addOnFailureListener { e -> PhotopultLog.e("sendPayload failed", e) }
    }

    /** Camera's current state. Only battery is live until CameraX lands in Stage 3. */
    private fun currentCameraState(): CameraEvent.State {
        val battery = runCatching {
            val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: -1
        }.getOrDefault(-1)
        return CameraEvent.State(
            battery = battery,
            flash = "off",
            lens = "back",
            zoomRatio = 1f,
            maxZoom = 1f,
            resolution = "—",
        )
    }
}
