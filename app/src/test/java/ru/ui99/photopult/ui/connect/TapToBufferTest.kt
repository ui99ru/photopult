package ru.ui99.photopult.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the display→buffer focus mapping. The remote shows the encoder buffer rotated by
 * [mapTapToBuffer]'s rotation and optionally mirrored; a tap must land on the matching sensor point.
 */
class TapToBufferTest {

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: Pair<Float, Float>) {
        assertEquals(expectedX, actual.first, 1e-4f)
        assertEquals(expectedY, actual.second, 1e-4f)
    }

    @Test
    fun rotation0_isIdentity() {
        assertPoint(0.25f, 0.75f, mapTapToBuffer(0.25f, 0.75f, 0, false))
    }

    @Test
    fun center_isInvariantUnderEveryRotation() {
        for (r in listOf(0, 90, 180, 270)) {
            assertPoint(0.5f, 0.5f, mapTapToBuffer(0.5f, 0.5f, r, false))
        }
    }

    @Test
    fun rotation90_mapsDisplayedTopToBufferLeft() {
        // The buffer is rotated 90° clockwise to display upright, so a tap at the top-center of the
        // displayed frame corresponds to the left-center of the buffer.
        assertPoint(0f, 0.5f, mapTapToBuffer(0.5f, 0f, 90, false))
    }

    @Test
    fun rotation180_flipsBothAxes() {
        assertPoint(0.75f, 0.25f, mapTapToBuffer(0.25f, 0.75f, 180, false))
    }

    @Test
    fun mirror_flipsHorizontallyOnly() {
        assertPoint(0.75f, 0.75f, mapTapToBuffer(0.25f, 0.75f, 0, true))
    }

    @Test
    fun resultsStayInUnitRange() {
        val p = mapTapToBuffer(1f, 1f, 270, true)
        assertEquals(true, p.first in 0f..1f)
        assertEquals(true, p.second in 0f..1f)
    }
}
