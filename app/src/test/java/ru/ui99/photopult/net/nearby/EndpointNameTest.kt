package ru.ui99.photopult.net.nearby

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointNameTest {

    @Test
    fun encode_thenParse_roundTrips() {
        val raw = EndpointName.encode("Redmi Note 13 Pro", "install-abc-123")
        assertEquals("Redmi Note 13 Pro", EndpointName.displayOf(raw))
        assertEquals("install-abc-123", EndpointName.idOf(raw))
    }

    @Test
    fun legacyNameWithoutSeparator_usesWholeStringForBoth() {
        // An endpoint from an older build has no separator.
        val raw = "Old Phone"
        assertEquals("Old Phone", EndpointName.displayOf(raw))
        assertEquals("Old Phone", EndpointName.idOf(raw))
    }

    @Test
    fun displayNameWithSpaces_isPreserved() {
        val raw = EndpointName.encode("POCO M6 Pro", "id-xyz")
        assertEquals("POCO M6 Pro", EndpointName.displayOf(raw))
    }
}
