package ru.ui99.photopult.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewOrientationTest {

    @Test
    fun mirrorOnlyForFront() {
        assertTrue(PreviewOrientation.isMirrored(true))
        assertFalse(PreviewOrientation.isMirrored(false))
    }
}
