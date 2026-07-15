package ru.ui99.photopult.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewOrientationTest {

    @Test
    fun backCamera_portrait_appliesPortraitHalfTurn() {
        // Base sensor rotation 90° + 180° portrait correction = 270° (fixes the upside-down preview).
        assertEquals(270, PreviewOrientation.rotationForUpright(90, 0, front = false))
    }

    @Test
    fun backCamera_deviceLandscape_cancelsSensor_noPortraitFix() {
        // Landscape is already upright — no half-turn added; unchanged from the standard formula.
        assertEquals(0, PreviewOrientation.rotationForUpright(90, 90, front = false))
        assertEquals(180, PreviewOrientation.rotationForUpright(90, 270, front = false))
    }

    @Test
    fun reversePortrait_alsoGetsHalfTurn() {
        // device 180° -> base norm(90-180)=270, +180 = 90.
        assertEquals(90, PreviewOrientation.rotationForUpright(90, 180, front = false))
    }

    @Test
    fun frontCamera_addsRotations_withPortraitFix() {
        // Portrait: base norm(270+0)=270, +180 = 90.
        assertEquals(90, PreviewOrientation.rotationForUpright(270, 0, front = true))
        // Landscape: base norm(270+90)=0, +0 = 0.
        assertEquals(0, PreviewOrientation.rotationForUpright(270, 90, front = true))
    }

    @Test
    fun normalisesOutOfRangeAngles() {
        // 450 -> 90, base norm(90)=90, portrait +180 = 270.
        assertEquals(270, PreviewOrientation.rotationForUpright(450, 0, front = false))
        // -90 -> 270, base norm(270)=270, portrait +180 = 90.
        assertEquals(90, PreviewOrientation.rotationForUpright(-90, 0, front = false))
    }

    @Test
    fun mirrorOnlyForFront() {
        assertTrue(PreviewOrientation.isMirrored(true))
        assertFalse(PreviewOrientation.isMirrored(false))
    }
}
