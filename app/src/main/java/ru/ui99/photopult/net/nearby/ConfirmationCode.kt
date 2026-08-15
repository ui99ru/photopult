package ru.ui99.photopult.net.nearby

/**
 * Turns the Nearby authentication token into a friendly confirmation shown on both phones.
 *
 * Nearby derives the same token on both ends, so both screens render the same digits and the same
 * emoji row. The user just checks they match and taps "yes" — no jargon, per the brief.
 */
object ConfirmationCode {

    // Small, visually distinct palette; index chosen deterministically from the token.
    private val EMOJI = listOf(
        "🐱", "🐶", "🦊", "🐼", "🐧", "🦉", "🐢", "🐬",
        "🌸", "🍀", "⭐", "🔥", "🍎", "🎈", "🚀", "🎸",
    )

    /** Up to four digits from the token; falls back to a hash if the token has none. */
    fun digits(token: String): String {
        val onlyDigits = token.filter { it.isDigit() }
        val source = onlyDigits.ifEmpty { positiveHash(token).toString() }
        return source.takeLast(4).padStart(4, '0')
    }

    /** A short, stable emoji row derived from the confirmation digits. */
    fun emojis(token: String): String {
        val code = digits(token)
        val builder = StringBuilder()
        for (i in 0 until 3) {
            val chunk = code.getOrElse(i) { '0' }.digitToIntOrNull() ?: 0
            val next = code.getOrElse(i + 1) { '0' }.digitToIntOrNull() ?: 0
            builder.append(EMOJI[(chunk * 3 + next) % EMOJI.size])
        }
        return builder.toString()
    }

    private fun positiveHash(token: String): Int = token.hashCode() and 0x7fffffff
}
