package ru.ui99.photopult.net.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    @Test
    fun handshake_roundTripsThroughJson() {
        val original = Handshake(deviceName = "Pixel 8", role = Role.CAMERA)

        val decoded = Handshake.decode(original.encode())

        assertEquals(original, decoded)
        assertEquals(PROTOCOL_VERSION, decoded.protocolVersion)
    }

    @Test
    fun handshake_encodesDeclaredProtocolVersion() {
        val json = Handshake(deviceName = "Remote", role = Role.REMOTE).encode()

        assertTrue("expected protocolVersion in payload", json.contains("\"protocolVersion\":1"))
        assertTrue("expected role in payload", json.contains("REMOTE"))
    }

    @Test
    fun decode_toleratesUnknownFields() {
        // A newer peer may add fields; an older build must not choke on them.
        val payload = """{"protocolVersion":1,"deviceName":"X","role":"CAMERA","futureField":42}"""

        val decoded = Handshake.decode(payload)

        assertEquals("X", decoded.deviceName)
        assertEquals(Role.CAMERA, decoded.role)
    }
}
