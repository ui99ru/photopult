package ru.ui99.photopult.codec

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveControllerTest {

    @Test
    fun sustainedCongestion_stepsDown() {
        val c = AdaptiveController(rungCount = 3, downStreak = 2)
        assertEquals(0, c.onSample(0.9f)) // 1 bad sample, not yet
        assertEquals(1, c.onSample(0.9f)) // 2 bad → step down
        assertEquals(2, c.onSample(0.9f))
        assertEquals(2, c.onSample(0.9f)) // clamped at bottom
    }

    @Test
    fun sustainedHeadroom_stepsBackUp() {
        val c = AdaptiveController(rungCount = 3, downStreak = 2, upStreak = 3)
        repeat(2) { c.onSample(0.9f) } // down to rung 1
        assertEquals(1, c.rung)
        assertEquals(1, c.onSample(0.0f))
        assertEquals(1, c.onSample(0.0f))
        assertEquals(0, c.onSample(0.0f)) // 3 good → step up
    }

    @Test
    fun neutralSamples_resetStreaks_noFlapping() {
        val c = AdaptiveController(rungCount = 3, downStreak = 2)
        c.onSample(0.9f) // 1 bad
        c.onSample(0.3f) // neutral resets
        assertEquals(0, c.onSample(0.9f)) // only 1 bad again — still rung 0
    }

    @Test
    fun linkQualityBars_mapFromRung() {
        assertEquals(3, BitrateLadder.linkQualityLevel(0))
        assertEquals(2, BitrateLadder.linkQualityLevel(1))
        assertEquals(1, BitrateLadder.linkQualityLevel(2))
    }
}
