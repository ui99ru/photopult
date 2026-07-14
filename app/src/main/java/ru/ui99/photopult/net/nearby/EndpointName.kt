package ru.ui99.photopult.net.nearby

/**
 * Packs the friendly device name together with a stable per-install id into the single string
 * Nearby advertises as the endpoint name. Lets the peer show a human name AND recognise a
 * remembered device across sessions (Nearby endpoint ids are ephemeral).
 *
 * Format: "<displayName><US><installId>", where <US> is the unit-separator control char (U+001F),
 * which won't appear in a device name. An endpoint from an older build without the separator
 * degrades gracefully: the whole string is the display name and also serves as the id.
 */
object EndpointName {
    private const val SEP = ""

    fun encode(displayName: String, installId: String): String = displayName + SEP + installId

    fun displayOf(raw: String): String = raw.substringBefore(SEP)

    fun idOf(raw: String): String =
        if (raw.contains(SEP)) raw.substringAfter(SEP) else raw
}
