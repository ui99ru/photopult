package ru.ui99.photopult.net.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Messages exchanged over the Nearby BYTES channel (JSON, [ProtocolJson]). Two sealed families —
 * remote→camera commands and camera→remote events — keyed by a "type" discriminator so the set
 * grows without breaking older peers.
 *
 * Iteration (b) uses only getState/state; shutter, zoom, flash, countdown, etc. slot in here later.
 */

/** Remote → camera. */
@Serializable
sealed interface RemoteCommand {
    @Serializable
    @SerialName("getState")
    data object GetState : RemoteCommand
}

/** Camera → remote. */
@Serializable
sealed interface CameraEvent {
    /**
     * Current camera state. Only [battery] is live in iteration (b); the camera-specific fields are
     * placeholders until CameraX arrives in Stage 3, but the shape matches the brief so the wire
     * format is stable.
     */
    @Serializable
    @SerialName("state")
    data class State(
        val battery: Int,
        val flash: String = "off",
        val lens: String = "back",
        val zoomRatio: Float = 1f,
        val maxZoom: Float = 1f,
        val resolution: String = "",
    ) : CameraEvent
}

/** Encodes/decodes messages to/from the UTF-8 bytes carried by a Nearby BYTES payload. */
object Wire {
    fun encode(command: RemoteCommand): ByteArray =
        ProtocolJson.encodeToString(RemoteCommand.serializer(), command).encodeToByteArray()

    fun decodeCommand(bytes: ByteArray): RemoteCommand =
        ProtocolJson.decodeFromString(RemoteCommand.serializer(), bytes.decodeToString())

    fun encode(event: CameraEvent): ByteArray =
        ProtocolJson.encodeToString(CameraEvent.serializer(), event).encodeToByteArray()

    fun decodeEvent(bytes: ByteArray): CameraEvent =
        ProtocolJson.decodeFromString(CameraEvent.serializer(), bytes.decodeToString())
}
