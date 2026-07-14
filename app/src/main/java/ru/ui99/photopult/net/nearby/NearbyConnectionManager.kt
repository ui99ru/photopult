package ru.ui99.photopult.net.nearby

import android.content.Context
import android.os.BatteryManager
import com.google.android.gms.common.api.ApiException
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.ui99.photopult.net.protocol.CameraEvent
import ru.ui99.photopult.net.protocol.RemoteCommand
import ru.ui99.photopult.net.protocol.Role
import ru.ui99.photopult.net.protocol.Wire
import ru.ui99.photopult.util.DeviceName
import ru.ui99.photopult.util.PairingStore
import ru.ui99.photopult.util.PhotopultLog

/**
 * Wraps Nearby Connections for iteration (a): the camera advertises, the remote discovers and
 * connects, and both confirm a matching code before the link is accepted.
 *
 * Every meaningful callback and error (with its status code) is logged under the `Photopult` tag,
 * so a two-phone test is fully traceable via `adb logcat -s Photopult` and the debug screen.
 * Payload exchange (getState/state) and auto-reconnect land in later iterations.
 */
class NearbyConnectionManager(
    context: Context,
    private val scope: CoroutineScope,
    private val pairing: PairingStore,
) {

    private companion object {
        // Sequential retry backoffs for transient radio errors (RADIO_ERROR / ENDPOINT_IO_ERROR).
        val RETRY_BACKOFFS_MS = longArrayOf(1_500, 3_000, 6_000)

        // If a request neither initiates nor resolves in this long, treat it as a failed attempt.
        const val CONNECT_TIMEOUT_MS = 15_000L

        // Let the Bluetooth/Wi-Fi stack settle after teardown before searching again (MIUI needs it).
        const val TEARDOWN_PAUSE_MS = 1_500L

        // Small settle after stopping discovery before requesting a connection.
        const val PRE_REQUEST_SETTLE_MS = 300L
    }

    private val appContext: Context = context.applicationContext
    private val client: ConnectionsClient = Nearby.getConnectionsClient(appContext)

    // Friendly name shown in the UI; the advertised name also carries our stable install id so a
    // remembered peer can recognise us across sessions.
    private val localDisplayName: String = DeviceName.of(appContext)
    private val localAdvertiseName: String = EndpointName.encode(localDisplayName, pairing.installId())

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Latest state reported by the camera (remote side only). Null until received. */
    private val _peerState = MutableStateFlow<CameraEvent.State?>(null)
    val peerState: StateFlow<CameraEvent.State?> = _peerState.asStateFlow()

    private var role: Role? = null
    private val discovered = linkedMapOf<String, DiscoveredEndpoint>()
    private var pending: ConnectionState.Confirming? = null
    private var connectedEndpointId: String? = null

    // Connection state machine (remote side). While non-null, no new requestConnection is allowed.
    private var connectingEndpointId: String? = null
    private var retryCount = 0
    private var initiated = false
    private var connectJob: Job? = null
    private var watchdogJob: Job? = null

    // Install id of the peer currently being negotiated (parsed from its endpoint name).
    private var pendingPeerId: String? = null

    fun start(role: Role) {
        this.role = role
        PhotopultLog.i("start(role=$role, name=$localDisplayName, knownPeer=${pairing.peerId != null})")
        when (role) {
            Role.CAMERA -> startAdvertising()
            Role.REMOTE -> startDiscovery()
        }
    }

    private fun startAdvertising() {
        _state.value = ConnectionState.Advertising(localDisplayName)
        val options = AdvertisingOptions.Builder().setStrategy(NearbyConfig.STRATEGY).build()
        PhotopultLog.i("startAdvertising as \"$localDisplayName\" service=${NearbyConfig.SERVICE_ID}")
        client.startAdvertising(localAdvertiseName, NearbyConfig.SERVICE_ID, lifecycleCallback, options)
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
        // Guard: one connection attempt at a time. Repeated taps (or any auto-logic) are ignored
        // until this attempt resolves — this is what prevented the earlier storm of parallel
        // requestConnection calls that provoked RADIO_ERROR / ENDPOINT_IO_ERROR.
        if (connectingEndpointId != null) {
            PhotopultLog.w("connectTo($endpointId) ignored — already connecting to $connectingEndpointId")
            return
        }
        val target = discovered[endpointId]
        if (target == null) {
            PhotopultLog.w("connectTo($endpointId) ignored — endpoint no longer known")
            return
        }
        connectingEndpointId = endpointId
        retryCount = 0
        showConnecting(endpointId)
        connectJob?.cancel()
        connectJob = scope.launch {
            // Stop discovering while we negotiate; let the radio settle briefly first.
            client.stopDiscovery()
            delay(PRE_REQUEST_SETTLE_MS)
            requestConnectionOnce(endpointId)
        }
    }

    private fun requestConnectionOnce(endpointId: String) {
        if (connectingEndpointId != endpointId) return
        initiated = false
        PhotopultLog.i("requestConnection to $endpointId (attempt ${retryCount + 1})")
        armWatchdog(endpointId)
        client.requestConnection(localAdvertiseName, endpointId, lifecycleCallback)
            .addOnSuccessListener { PhotopultLog.i("requestConnection sent") }
            .addOnFailureListener { e ->
                val code = (e as? ApiException)?.statusCode
                PhotopultLog.e("requestConnection failed code=$code", e)
                cancelWatchdog()
                onAttemptFailed(endpointId, transient = isTransient(code), reason = "requestConnection $code")
            }
    }

    /** Decide whether to retry (transient radio error / timeout) or give up. Sequential only. */
    private fun onAttemptFailed(endpointId: String, transient: Boolean, reason: String) {
        if (connectingEndpointId != endpointId) return
        cancelWatchdog()
        if (transient && retryCount < RETRY_BACKOFFS_MS.size) {
            val backoff = RETRY_BACKOFFS_MS[retryCount]
            retryCount++
            PhotopultLog.w("attempt failed ($reason) — retry #$retryCount in ${backoff}ms")
            showConnecting(endpointId)
            connectJob?.cancel()
            connectJob = scope.launch {
                delay(backoff)
                requestConnectionOnce(endpointId)
            }
        } else {
            PhotopultLog.e("connect giving up after ${retryCount + 1} attempt(s): $reason")
            clearConnecting()
            _state.value = ConnectionState.Failed("Не удалось подключиться к камере")
        }
    }

    private fun armWatchdog(endpointId: String) {
        cancelWatchdog()
        watchdogJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            // Only fire if we're still waiting and negotiation never even initiated.
            if (connectingEndpointId == endpointId && !initiated) {
                PhotopultLog.w("connect watchdog timeout for $endpointId")
                runCatching { client.disconnectFromEndpoint(endpointId) }
                onAttemptFailed(endpointId, transient = true, reason = "timeout")
            }
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun clearConnecting() {
        connectingEndpointId = null
        pendingPeerId = null
        retryCount = 0
        initiated = false
        connectJob?.cancel()
        connectJob = null
        cancelWatchdog()
    }

    private fun showConnecting(endpointId: String) {
        val endpoints = (_state.value as? ConnectionState.Discovering)?.endpoints
            ?: discovered.values.toList()
        _state.value = ConnectionState.Discovering(endpoints, connectingEndpointId = endpointId)
    }

    private fun isTransient(code: Int?): Boolean =
        code == ConnectionsStatusCodes.STATUS_RADIO_ERROR ||
            code == ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR

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
        clearConnecting()
        restartDiscoveryOrAdvertising()
    }

    /** Tear everything down (leaving the flow). */
    fun stop() {
        PhotopultLog.i("stop() — stopping advertising/discovery, disconnecting")
        clearConnecting()
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
        clearConnecting()
        role?.let { start(it) }
    }

    /** Forget the remembered pair (and role) and tear down the session. */
    fun forgetPeer() {
        PhotopultLog.i("forgetPeer()")
        pairing.forget()
        stop()
    }

    val rememberedRole: Role? get() = pairing.rememberedRole
    fun hasRememberedPeer(): Boolean = pairing.peerId != null

    /**
     * Restart advertising/discovery after a teardown, but only after a short pause so the radio
     * stack releases first — starting immediately after a disconnect is what let a freshly found
     * endpoint be hit with a requestConnection while the radio was still unstable.
     */
    private fun restartDiscoveryOrAdvertising() {
        val currentRole = role ?: return
        connectJob?.cancel()
        connectJob = scope.launch {
            delay(TEARDOWN_PAUSE_MS)
            when (currentRole) {
                Role.CAMERA -> startAdvertising()
                Role.REMOTE -> startDiscovery()
            }
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val display = EndpointName.displayOf(info.endpointName)
            val peerId = EndpointName.idOf(info.endpointName)
            PhotopultLog.i("onEndpointFound $endpointId name=$display")
            discovered[endpointId] = DiscoveredEndpoint(endpointId, display, peerId)
            emitDiscovered()
            // Remembered pair → connect automatically, no tap. The connect guard prevents storms.
            if (peerId == pairing.peerId && connectingEndpointId == null) {
                PhotopultLog.i("known peer $display found — auto-connecting")
                connectTo(endpointId)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            PhotopultLog.i("onEndpointLost $endpointId")
            discovered.remove(endpointId)
            emitDiscovered()
        }
    }

    private fun emitDiscovered() {
        // Only overwrite the state while we're still in the discovery phase, and preserve any
        // in-progress "connecting" marker.
        if (_state.value is ConnectionState.Discovering) {
            _state.value = ConnectionState.Discovering(
                endpoints = discovered.values.toList(),
                connectingEndpointId = connectingEndpointId,
            )
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val token = info.authenticationDigits
            val display = EndpointName.displayOf(info.endpointName)
            val peerId = EndpointName.idOf(info.endpointName)
            val confirming = ConnectionState.Confirming(
                endpointId = endpointId,
                peerName = display,
                code = ConfirmationCode.digits(token),
                emojis = ConfirmationCode.emojis(token),
                incoming = info.isIncomingConnection,
            )
            // Negotiation reached the other side — stop the pre-initiation watchdog.
            initiated = true
            cancelWatchdog()
            pending = confirming
            pendingPeerId = peerId

            if (peerId == pairing.peerId) {
                // Remembered pair — accept without asking for the code (both sides do this).
                PhotopultLog.i("onConnectionInitiated $endpointId peer=$display — known, auto-accepting")
                client.acceptConnection(endpointId, payloadCallback)
                    .addOnFailureListener { e -> PhotopultLog.e("auto acceptConnection failed", e) }
            } else {
                PhotopultLog.i(
                    "onConnectionInitiated $endpointId peer=$display " +
                        "incoming=${info.isIncomingConnection} code=${confirming.code} — awaiting user",
                )
                _state.value = confirming
            }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            cancelWatchdog()
            val code = resolution.status.statusCode
            when (code) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val peer = pending?.peerName ?: endpointId
                    PhotopultLog.i("onConnectionResult $endpointId OK — connected to $peer")
                    // Remember this pair (and our role) so next launch reconnects with zero taps.
                    pendingPeerId?.let { id ->
                        pairing.rememberPeer(id, peer)
                        role?.let { pairing.rememberedRole = it }
                        PhotopultLog.i("remembered peer $peer (role=$role)")
                    }
                    pending = null
                    pendingPeerId = null
                    clearConnecting()
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
                    clearConnecting()
                    restartDiscoveryOrAdvertising()
                }
                ConnectionsStatusCodes.STATUS_RADIO_ERROR,
                ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR,
                -> {
                    val name = ConnectionsStatusCodes.getStatusCodeString(code)
                    PhotopultLog.w("onConnectionResult $endpointId transient: $code ($name)")
                    pending = null
                    onAttemptFailed(endpointId, transient = true, reason = "result $code")
                }
                else -> {
                    val name = ConnectionsStatusCodes.getStatusCodeString(code)
                    PhotopultLog.e("onConnectionResult $endpointId failed: $code ($name)")
                    pending = null
                    onAttemptFailed(endpointId, transient = false, reason = "result $code")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            PhotopultLog.w("onDisconnected $endpointId — returning to search")
            pending = null
            connectedEndpointId = null
            _peerState.value = null
            clearConnecting()
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
