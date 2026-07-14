package ru.ui99.photopult.codec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFramingTest {

    @Test
    fun writeThenRead_roundTripsFrames() {
        val out = ByteArrayOutputStream()
        val a = byteArrayOf(1, 2, 3, 4, 5)
        val b = byteArrayOf(9, 8, 7)
        StreamFraming.writeFrame(out, a, 0, a.size, ptsUs = 1000, keyframe = true, config = true)
        StreamFraming.writeFrame(out, b, 0, b.size, ptsUs = 2000, keyframe = false, config = false)

        val input = ByteArrayInputStream(out.toByteArray())
        val f1 = StreamFraming.readFrame(input)!!
        val f2 = StreamFraming.readFrame(input)!!

        assertEquals(1000L, f1.ptsUs)
        assertTrue(f1.keyframe)
        assertTrue(f1.config)
        assertArrayEquals(a, f1.data)

        assertEquals(2000L, f2.ptsUs)
        assertTrue(!f2.keyframe)
        assertTrue(!f2.config)
        assertArrayEquals(b, f2.data)
    }

    @Test
    fun readFrame_returnsNullAtCleanEnd() {
        val input = ByteArrayInputStream(ByteArray(0))
        assertNull(StreamFraming.readFrame(input))
    }

    @Test
    fun writeFrame_honoursOffsetAndSize() {
        val out = ByteArrayOutputStream()
        val src = byteArrayOf(0, 0, 42, 43, 0)
        StreamFraming.writeFrame(out, src, 2, 2, ptsUs = 5, keyframe = false, config = false)
        val frame = StreamFraming.readFrame(ByteArrayInputStream(out.toByteArray()))!!
        assertArrayEquals(byteArrayOf(42, 43), frame.data)
    }
}
