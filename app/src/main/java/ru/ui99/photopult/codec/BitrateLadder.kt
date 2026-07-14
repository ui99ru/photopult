package ru.ui99.photopult.codec

/**
 * The adaptive quality ladder. Rung 0 is best; stepping down trades resolution/bitrate for a
 * stable, smooth stream (the brief: sacrifice sharpness, not frame rate). Link-quality bars are
 * derived from the current rung.
 */
object BitrateLadder {

    data class Rung(val width: Int, val height: Int, val bitrate: Int)

    val RUNGS: List<Rung> = listOf(
        Rung(1280, 720, 3_000_000),
        Rung(960, 540, 1_500_000),
        Rung(640, 360, 800_000),
    )

    val best: Rung get() = RUNGS.first()

    fun rung(index: Int): Rung = RUNGS[index.coerceIn(0, RUNGS.lastIndex)]

    /** 3 bars at the top rung down to 1 bar at the bottom. */
    fun linkQualityLevel(rungIndex: Int): Int =
        (RUNGS.size - rungIndex.coerceIn(0, RUNGS.lastIndex))
}
