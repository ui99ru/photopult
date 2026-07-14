package ru.ui99.photopult.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewOrientationTest {

    @Test
    fun backCamera_portrait_rotates90() {
        assertEquals(90, PreviewOrientation.rotationForUpright(90, 0, front = false))
    }

    @Test
    fun backCamera_deviceLandscape_cancelsSensor() {
        assertEquals(0, PreviewOrientation.rotationForUpright(90, 90, front = false))
    }

    @Test
    fun frontCamera_addsRotations() {
        assertEquals(270, PreviewOrientation.rotationForUpright(270, 0, front = true))
        assertEquals(0, PreviewOrientation.rotationForUpright(270, 90, front = true))
    }

    @Test
    fun normalisesOutOfRangeAngles() {
        assertEquals(90, PreviewOrientation.rotationForUpright(450, 0, front = false))
        assertEquals(270, PreviewOrientation.rotationForUpright(-90, 0, front = false))
    }

    @Test
    fun mirrorOnlyForFront() {
        assertTrue(PreviewOrientation.isMirrored(true))
        assertFalse(PreviewOrientation.isMirrored(false))
    }
}
