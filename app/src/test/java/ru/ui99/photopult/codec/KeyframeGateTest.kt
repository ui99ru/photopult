package ru.ui99.photopult.codec

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeGateTest {

    private fun frame(keyframe: Boolean = false, config: Boolean = false) =
        StreamFraming.Frame(ptsUs = 0, keyframe = keyframe, config = config, data = ByteArray(0))

    @Test
    fun passesEverythingWhenNotBehind() {
        val gate = KeyframeGate()
        assertTrue(gate.accept(frame(keyframe = false)))
        assertTrue(gate.accept(frame(keyframe = true)))
    }

    @Test
    fun whenBehind_dropsUntilKeyframe() {
        val gate = KeyframeGate()
        gate.fallBehind()
        assertFalse(gate.accept(frame(keyframe = false)))
        assertFalse(gate.accept(frame(keyframe = false)))
        assertTrue(gate.accept(frame(keyframe = true)))
        // Resumed after the keyframe.
        assertTrue(gate.accept(frame(keyframe = false)))
    }

    @Test
    fun configFramesAlwaysPass() {
        val gate = KeyframeGate()
        gate.fallBehind()
        assertTrue(gate.accept(frame(config = true)))
        assertTrue(gate.isDropping) // config passing doesn't clear the drop state
    }
}
