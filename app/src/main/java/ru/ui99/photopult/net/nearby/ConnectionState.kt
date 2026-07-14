package ru.ui99.photopult.net.nearby

/** A camera discovered by the remote while searching. */
data class DiscoveredEndpoint(
    val endpointId: String,
    val name: String,
)

/**
 * The connection cycle as a single observable state. The UI renders purely from this; the manager
 * is the only writer.
 */
sealed interface ConnectionState {
    /** Nothing started yet. */
    data object Idle : ConnectionState

    /** Camera is advertising and waiting for a remote to appear. */
    data class Advertising(val localName: String) : ConnectionState

    /**
     * Remote is discovering; [endpoints] updates as cameras come and go. While a connection to
     * [connectingEndpointId] is in progress that card shows "connecting" and taps are ignored.
     */
    data class Discovering(
        val endpoints: List<DiscoveredEndpoint>,
        val connectingEndpointId: String? = null,
    ) : ConnectionState

    /** A connection was initiated; both sides show [code] + [emojis] to confirm the same pair. */
    data class Confirming(
        val endpointId: String,
        val peerName: String,
        val code: String,
        val emojis: String,
        val incoming: Boolean,
    ) : ConnectionState

    /** Connected to [peerName]. */
    data class Connected(
        val endpointId: String,
        val peerName: String,
    ) : ConnectionState

    /** Something went wrong; [userMessage] is human-readable. */
    data class Failed(val userMessage: String) : ConnectionState
}
