package ru.ui99.photopult.net.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Foundation of the JSON command/event protocol carried over the Nearby BYTES channel.
 *
 * Only the handshake is defined at this stage; the full command/event set (shutter, zoom, state,
 * countdown, …) lands in Stage 2. The [PROTOCOL_VERSION] major number lets an old peer show the
 * user a plain-language "update the app on the other phone" message instead of failing silently.
 */
const val PROTOCOL_VERSION: Int = 1

/** Shared JSON codec: tolerant of unknown fields so newer peers don't break older ones. */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

@Serializable
data class Handshake(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val deviceName: String,
    val role: Role,
) {
    fun encode(): String = ProtocolJson.encodeToString(serializer(), this)

    companion object {
        fun decode(json: String): Handshake = ProtocolJson.decodeFromString(serializer(), json)
    }
}

@Serializable
enum class Role {
    CAMERA,
    REMOTE,
}
