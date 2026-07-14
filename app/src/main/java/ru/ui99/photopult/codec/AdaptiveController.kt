package ru.ui99.photopult.codec

/**
 * Decides which ladder rung to use from a periodic congestion signal (0 = channel free, 1 = very
 * backed up). Uses streak-based hysteresis so we don't flap: we only step down after sustained
 * congestion and step back up after sustained headroom. Pure and single-threaded.
 */
class AdaptiveController(
    private val rungCount: Int = BitrateLadder.RUNGS.size,
    private val downThreshold: Float = 0.5f,
    private val upThreshold: Float = 0.15f,
    private val downStreak: Int = 2,
    private val upStreak: Int = 5,
) {
    var rung: Int = 0
        private set

    private var badStreak = 0
    private var goodStreak = 0

    /** Feed one congestion sample; returns the (possibly new) rung index. */
    fun onSample(congestion: Float): Int {
        when {
            congestion >= downThreshold -> { badStreak++; goodStreak = 0 }
            congestion <= upThreshold -> { goodStreak++; badStreak = 0 }
            else -> { badStreak = 0; goodStreak = 0 }
        }
        if (badStreak >= downStreak && rung < rungCount - 1) {
            rung++
            badStreak = 0
        } else if (goodStreak >= upStreak && rung > 0) {
            rung--
            goodStreak = 0
        }
        return rung
    }

    fun reset() {
        rung = 0
        badStreak = 0
        goodStreak = 0
    }
}
