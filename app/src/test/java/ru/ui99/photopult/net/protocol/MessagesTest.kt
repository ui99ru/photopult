package ru.ui99.photopult.net.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesTest {

    @Test
    fun getState_roundTripsOverWire() {
        val bytes = Wire.encode(RemoteCommand.GetState)
        assertEquals(RemoteCommand.GetState, Wire.decodeCommand(bytes))
    }

    @Test
    fun getState_usesTypeDiscriminator() {
        val json = Wire.encode(RemoteCommand.GetState).decodeToString()
        assertTrue("expected type discriminator, got $json", json.contains("\"type\":\"getState\""))
    }

    @Test
    fun state_roundTripsOverWire() {
        val original = CameraEvent.State(
            battery = 87,
            flash = "auto",
            lens = "front",
            zoomRatio = 2.5f,
            maxZoom = 8f,
            resolution = "4000x3000",
        )
        val decoded = Wire.decodeEvent(Wire.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun streamConfig_roundTripsOverWire() {
        val original = CameraEvent.StreamConfig(
            width = 1280,
            height = 720,
            rotationDegrees = 90,
            mirrored = false,
            fps = 30,
        )
        val decoded = Wire.decodeEvent(Wire.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun flashFocusExposure_roundTripOverWire() {
        listOf(
            RemoteCommand.SetFlash("auto"),
            RemoteCommand.Focus(0.3f, 0.7f),
            RemoteCommand.SetExposure(-2),
        ).forEach { command ->
            assertEquals(command, Wire.decodeCommand(Wire.encode(command)))
        }
    }

    @Test
    fun state_carriesExposureRange() {
        val original = CameraEvent.State(battery = 60, evIndex = -3, evMin = -12, evMax = 12)
        val decoded = Wire.decodeEvent(Wire.encode(original)) as CameraEvent.State
        assertEquals(-3, decoded.evIndex)
        assertEquals(-12, decoded.evMin)
        assertEquals(12, decoded.evMax)
    }

    @Test
    fun state_toleratesUnknownFields() {
        // A newer camera may add fields; an older remote must still parse the state it knows.
        val payload = """{"type":"state","battery":50,"futureField":true}""".encodeToByteArray()
        val decoded = Wire.decodeEvent(payload)
        assertTrue(decoded is CameraEvent.State)
        assertEquals(50, (decoded as CameraEvent.State).battery)
    }
}
