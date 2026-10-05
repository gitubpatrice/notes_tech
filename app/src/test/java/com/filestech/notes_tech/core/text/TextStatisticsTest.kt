package com.filestech.notes_tech.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

// ⚠️ Every invisible character is built from its code point, never written as a literal: a test
// whose subject is the difference between U+0020, U+00A0 and U+202F cannot rely on characters that
// an editor, a copy-paste or a tool may silently turn into plain spaces. Even `\u` escapes were
// turned into raw characters by the tooling on 2026-09-24 — hence `Char(0x…)`.
private val NBSP = Char(0x00A0)
private val NNBSP = Char(0x202F)
private val ZWSP = Char(0x200B)
private val LS = Char(0x2028)
private val PS = Char(0x2029)
private val NEL = Char(0x0085)

/** The counts of the info panel. */
class TextStatisticsTest {

    @Test
    @DisplayName("an empty or blank note has no word, and only its spaces as characters")
    fun empty_and_blank() {
        assertThat(TextStatistics.words("")).isEqualTo(0)
        assertThat(TextStatistics.characters("")).isEqualTo(0)
        assertThat(TextStatistics.words("   \n\t ")).isEqualTo(0)
        // Three spaces, a tab and a space are characters; the line break is not.
        assertThat(TextStatistics.characters("   \n\t ")).isEqualTo(5)
    }

    @Test
    @DisplayName("words are separated by any whitespace, line breaks included")
    fun plain_words() {
        assertThat(TextStatistics.words("one two\nthree\tfour")).isEqualTo(4)
        assertThat(TextStatistics.words("  leading and trailing  ")).isEqualTo(3)
    }

    /**
     * The case that justifies not reusing Dart's definition: French punctuation is preceded by a
     * narrow no-break space (U+202F) and guillemets are padded with no-break spaces (U+00A0).
     */
    @Test
    @DisplayName("french punctuation behind a no-break space is not a word")
    fun french_typography() {
        val phrase = "«${NBSP}Bonjour$NNBSP!$NBSP» dit-il$NNBSP; c'est aujourd'hui."

        // Bonjour, dit-il, c'est, aujourd'hui. — Dart's split would have said 8.
        assertThat(TextStatistics.words(phrase)).isEqualTo(4)
    }

    @Test
    @DisplayName("a no-break space separates two words, as in Dart; a zero-width space does not")
    fun no_break_space_separates() {
        assertThat(TextStatistics.words("deux${NBSP}mots")).isEqualTo(2)
        assertThat(TextStatistics.words("deux${NNBSP}mots")).isEqualTo(2)
        // The control: U+200B is not whitespace for Dart either, so the word stays whole.
        assertThat(TextStatistics.words("deux${ZWSP}mots")).isEqualTo(1)
    }

    @Test
    @DisplayName("a number is a word, a lone dash is not")
    fun digits_and_dashes() {
        assertThat(TextStatistics.words("page 42 — fin")).isEqualTo(3)
    }

    @Test
    @DisplayName("an emoji counts as one character, not two UTF-16 units")
    fun code_points() {
        val emoji = String(Character.toChars(0x1F600))
        assertThat(emoji.length).isEqualTo(2)
        assertThat(TextStatistics.characters(emoji)).isEqualTo(1)
        assertThat(TextStatistics.characters("a${emoji}b")).isEqualTo(3)
    }

    @Test
    @DisplayName("every kind of line break is excluded from the character count, spaces are not")
    fun line_breaks_excluded() {
        // a b c d e f — \n, the \r\n pair, U+2028, U+2029 and U+0085 are not characters.
        assertThat(TextStatistics.characters("a\nb\r\nc${LS}d${PS}e${NEL}f")).isEqualTo(6)
        // The control: ordinary and no-break spaces ARE characters.
        assertThat(TextStatistics.characters("a b${NBSP}c")).isEqualTo(5)
    }

    @Test
    @DisplayName("markdown syntax counts: the panel measures what was typed")
    fun markdown_is_counted_as_typed() {
        // The counts describe the text in the editor, syntax included — the same text the export writes.
        assertThat(TextStatistics.words("# Title\n- item [[Link]]")).isEqualTo(3)
        assertThat(TextStatistics.characters("**b**")).isEqualTo(5)
    }
}
