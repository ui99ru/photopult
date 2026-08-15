package ru.ui99.photopult.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aspect-fit math for the preview: a buffer fitted into a view (after rotation) must preserve
 * proportions and never exceed the view bounds — that's the letterbox fix for the stretched preview.
 */
class PreviewFitTest {

    @Test
    fun portraitView_landscapeBufferRotated90_fillsExactly() {
        // 1280×720 rotated to portrait, into a 1080×1920 (9:16) screen → fills, no letterbox.
        val fit = previewFit(1080f, 1920f, 1280f, 720f, 90)
        assertEquals(1.5f, fit.scale, 1e-4f)
        assertEquals(1080f, fit.dispW, 1e-3f)
        assertEquals(1920f, fit.dispH, 1e-3f)
    }

    @Test
    fun tallerView_lettterboxesTopAndBottom() {
        // Same buffer into a 20:9 (1080×2400) screen → limited by width, letterbox vertically.
        val fit = previewFit(1080f, 2400f, 1280f, 720f, 90)
        assertEquals(1.5f, fit.scale, 1e-4f)
        assertEquals(1080f, fit.dispW, 1e-3f)
        assertEquals(1920f, fit.dispH, 1e-3f)
        assertTrue("must not exceed view height", fit.dispH <= 2400f)
    }

    @Test
    fun noRotation_keepsBufferAspect() {
        val fit = previewFit(1920f, 1080f, 1280f, 720f, 0)
        assertEquals(1.5f, fit.scale, 1e-4f)
        assertEquals(1920f, fit.dispW, 1e-3f)
        assertEquals(1080f, fit.dispH, 1e-3f)
    }

    @Test
    fun neverExceedsViewBounds() {
        for (rot in listOf(0, 90, 180, 270)) {
            val fit = previewFit(720f, 1600f, 1280f, 720f, rot)
            assertTrue("width within view (rot=$rot)", fit.dispW <= 720f + 1e-3f)
            assertTrue("height within view (rot=$rot)", fit.dispH <= 1600f + 1e-3f)
        }
    }

    @Test
    fun preservesAspectRatio() {
        val fit = previewFit(1000f, 1000f, 1280f, 720f, 0)
        // displayed aspect must equal buffer aspect (16:9)
        assertEquals(1280f / 720f, fit.dispW / fit.dispH, 1e-4f)
    }
}
