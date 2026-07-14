package ru.ui99.photopult.codec

/**
 * Decides which frames to feed the decoder when the receiver falls behind.
 *
 * Dropping arbitrary frames corrupts H.264 (P-frames depend on earlier ones), so once we're behind
 * we drop everything until the next keyframe and then resume — always converging on the freshest
 * decodable picture. Pure and single-threaded; the caller guards it.
 */
class KeyframeGate {

    private var droppingUntilKeyframe = false

    /** Signal that the decoder can't keep up (e.g. its input queue is full). */
    fun fallBehind() {
        droppingUntilKeyframe = true
    }

    val isDropping: Boolean get() = droppingUntilKeyframe

    /**
     * @return true if [frame] should be decoded; false if it must be dropped. Codec-config frames
     *   (SPS/PPS) are always passed through; a keyframe clears the dropping state.
     */
    fun accept(frame: StreamFraming.Frame): Boolean {
        if (frame.config) return true
        if (!droppingUntilKeyframe) return true
        if (frame.keyframe) {
            droppingUntilKeyframe = false
            return true
        }
        return false
    }
}
