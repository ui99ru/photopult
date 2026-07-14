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

    @Serializable
    @SerialName("zoom")
    data class Zoom(val ratio: Float) : RemoteCommand

    @Serializable
    @SerialName("switchCamera")
    data object SwitchCamera : RemoteCommand

    /** Take a photo after [timerSec] seconds (0 = now). */
    @Serializable
    @SerialName("shutter")
    data class Shutter(val timerSec: Int = 0) : RemoteCommand

    @Serializable
    @SerialName("burstStart")
    data object BurstStart : RemoteCommand

    @Serializable
    @SerialName("burstStop")
    data object BurstStop : RemoteCommand
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

    /**
     * Describes the preview STREAM the camera is about to send (or has just changed): encoded
     * frame size, how many degrees the remote must rotate to show it upright, and whether it is
     * mirrored (front lens). Sent on the BYTES channel whenever the stream (re)starts.
     */
    @Serializable
    @SerialName("streamConfig")
    data class StreamConfig(
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val mirrored: Boolean,
        val fps: Int,
    ) : CameraEvent

    /** Link quality as bars (1..3), no numbers — shown as an indicator on the remote. */
    @Serializable
    @SerialName("linkQuality")
    data class LinkQuality(val level: Int) : CameraEvent

    /** Countdown tick shown on both screens; [secondsLeft] 0 = firing now. -1 = cancelled. */
    @Serializable
    @SerialName("countdown")
    data class Countdown(val secondsLeft: Int) : CameraEvent

    /** A capture finished (or failed). [photoId] correlates thumbnail + full file. */
    @Serializable
    @SerialName("captureDone")
    data class CaptureDone(val ok: Boolean, val photoId: String, val userMessage: String = "") : CameraEvent

    /** Small preview thumbnail (base64 JPEG) for instant display; full file follows over FILE. */
    @Serializable
    @SerialName("thumbnail")
    data class Thumbnail(val photoId: String, val jpegBase64: String) : CameraEvent

    /** Announces the FILE payload that carries the full-quality photo for [photoId]. */
    @Serializable
    @SerialName("photoIncoming")
    data class PhotoIncoming(val photoId: String, val payloadId: Long) : CameraEvent
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
