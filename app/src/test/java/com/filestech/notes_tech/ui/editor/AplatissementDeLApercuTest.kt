package com.filestech.notes_tech.ui.editor

import com.filestech.notes_tech.domain.markdown.CellAlignment
import com.filestech.notes_tech.domain.markdown.ListItem
import com.filestech.notes_tech.domain.markdown.PreviewBlock
import com.filestech.notes_tech.domain.markdown.TextRun
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The preview's lazy items (`aplatir`): one per block, one per item of a top-level list, one per row
 * of a top-level table — the header included.
 */
@DisplayName("The preview's lazy items")
class AplatissementDeLApercuTest {

    private fun paragraphe(texte: String) = PreviewBlock.Paragraph(listOf(TextRun(texte)))

    @Test
    fun `a block is one item, a list one per item, a table one per row plus its header`() {
        val titre = PreviewBlock.Heading(1, listOf(TextRun("T")))
        val liste = PreviewBlock.ListBlock(
            ordered = false,
            start = 1,
            items = listOf(ListItem(null, listOf(paragraphe("a"))), ListItem(null, listOf(paragraphe("b")))),
        )
        val tableau = PreviewBlock.Table(
            alignments = listOf(CellAlignment.START),
            header = listOf(listOf(TextRun("h"))),
            rows = listOf(listOf(listOf(TextRun("1"))), listOf(listOf(TextRun("2"))), listOf(listOf(TextRun("3")))),
        )

        assertThat(aplatir(listOf(titre, liste, tableau, PreviewBlock.Rule))).containsExactly(
            ElementDApercu(titre, 0),
            ElementDApercu(liste, 0),
            ElementDApercu(liste, 1),
            ElementDApercu(tableau, 0),
            ElementDApercu(tableau, 1),
            ElementDApercu(tableau, 2),
            ElementDApercu(tableau, 3),
            ElementDApercu(PreviewBlock.Rule, 0),
        ).inOrder()
    }

    @Test
    fun `a table with a header only is one item`() {
        val tableau = PreviewBlock.Table(listOf(CellAlignment.START), listOf(listOf(TextRun("h"))), emptyList())
        assertThat(aplatir(listOf(tableau))).containsExactly(ElementDApercu(tableau, 0))
    }

    @Test
    fun `nothing to read is no item`() {
        assertThat(aplatir(emptyList())).isEmpty()
    }

    /**
     * 🔴 A note shown as written was ONE item holding all of it: a 5 MiB note froze the app on
     * opening (2026-10-29). Its pieces are items, numbered, and none exceeds the maximum.
     */
    @Test
    fun `a note shown as written is one item per piece, none longer than the maximum`() {
        val ligne = "x".repeat(60)
        val texte = (1..200).joinToString("\n") { "$it $ligne" }

        val elements = aplatir(listOf(PreviewBlock.AsWritten(texte)))

        assertThat(elements.size).isGreaterThan(1)
        assertThat(elements.map { it.partie }).isEqualTo(elements.indices.toList())
        elements.forEach { element ->
            val morceau = (element.bloc as PreviewBlock.AsWritten).text
            assertThat(morceau.length).isAtMost(MORCEAU_TEL_QUEL_MAX)
        }
        // Cut at line breaks: the pieces, joined by the breaks they dropped, are the note.
        assertThat(elements.joinToString("\n") { (it.bloc as PreviewBlock.AsWritten).text }).isEqualTo(texte)
    }

    @Test
    fun `a short note shown as written stays one item`() {
        val bloc = PreviewBlock.AsWritten("[a](")
        assertThat(aplatir(listOf(bloc))).containsExactly(ElementDApercu(bloc, 0))
    }

    /** One line longer than a piece — no break to cut at — is cut at the maximum, whole characters. */
    @Test
    fun `a line longer than a piece is cut at the maximum, never inside a surrogate pair`() {
        val ligne = "a".repeat(9) + "😀".repeat(10)

        val morceaux = morceauxTelQuel(ligne, max = 10)

        assertThat(morceaux.joinToString("")).isEqualTo(ligne)
        morceaux.forEach { morceau ->
            assertThat(morceau.length).isAtMost(10)
            assertThat(Character.isLowSurrogate(morceau.first())).isFalse()
            assertThat(Character.isHighSurrogate(morceau.last())).isFalse()
        }
    }

    /** A break in the first half of a piece would make pieces tiny: there, the cut is at the maximum. */
    @Test
    fun `a break early in a piece is not a place to cut`() {
        val texte = "ab\n" + "c".repeat(20)

        val morceaux = morceauxTelQuel(texte, max = 10)

        assertThat(morceaux.first()).hasLength(10)
        assertThat(morceaux.joinToString("")).isEqualTo(texte)
    }
}
