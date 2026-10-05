package com.filestech.notes_tech.core.text

/**
 * Word and character counts of a note, as the info panel shows them.
 *
 * ## Why not Dart's definition, which the published app planned to use
 *
 * `Note.wordCount` in notes_tech splits the trimmed content on `\s+` and counts the pieces. It was
 * written for an info panel that never shipped, so no user has ever seen its numbers — there is no
 * parity to keep, only a definition to choose.
 *
 * It miscounts ordinary French. French typography puts a (narrow) no-break space before `! ? : ;`
 * and inside « guillemets », so `« Bonjour ! »` splits into four pieces, three of which are
 * punctuation. A panel that reports 4 words for one word is wrong in a way the user sees at once.
 *
 * Two rules, therefore:
 *
 * - **Words**: pieces separated by whitespace — Dart's Unicode `\s`, so a no-break space does
 *   separate — and **containing at least one letter or digit**. `aujourd'hui` and `porte-clés` stay
 *   one word; a lone `!`, `—` or `«` is not a word.
 * - **Characters**: Unicode code points, **line breaks excluded**. Code points rather than UTF-16
 *   units, so an emoji counts once instead of twice; line breaks excluded, so ten short lines do
 *   not report nine invisible characters. Spaces do count, as in every word processor's
 *   "characters (with spaces)".
 */
object TextStatistics {

    fun words(text: String): Int = DartTextSemantics.WHITESPACE
        .split(DartTextSemantics.trim(text))
        .count { piece -> piece.any { it.isLetterOrDigit() } }

    fun characters(text: String): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (codePoint !in LINE_BREAKS) count++
            index += Character.charCount(codePoint)
        }
        return count
    }

    /** `\n`, `\r`, NEXT LINE, LINE SEPARATOR, PARAGRAPH SEPARATOR — what a text field shows as a new line. */
    private val LINE_BREAKS = setOf(0x0A, 0x0D, 0x85, 0x2028, 0x2029)
}
