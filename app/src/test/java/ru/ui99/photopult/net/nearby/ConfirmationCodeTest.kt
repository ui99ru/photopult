package ru.ui99.photopult.net.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationCodeTest {

    @Test
    fun digits_takesLastFourDigits() {
        assertEquals("1234", ConfirmationCode.digits("token-91234"))
    }

    @Test
    fun digits_padsShortTokensToFour() {
        assertEquals("0012", ConfirmationCode.digits("12"))
    }

    @Test
    fun digits_fallsBackWhenNoDigits() {
        val result = ConfirmationCode.digits("no-digits-here")
        assertEquals(4, result.length)
        assertTrue(result.all { it.isDigit() })
    }

    @Test
    fun sameToken_yieldsSameCodeAndEmojis() {
        // Both phones derive the same token, so both must render identical confirmation.
        val token = "abc12345"
        assertEquals(ConfirmationCode.digits(token), ConfirmationCode.digits(token))
        assertEquals(ConfirmationCode.emojis(token), ConfirmationCode.emojis(token))
    }

    @Test
    fun emojis_areNonEmpty() {
        assertTrue(ConfirmationCode.emojis("1234").isNotEmpty())
    }
}
