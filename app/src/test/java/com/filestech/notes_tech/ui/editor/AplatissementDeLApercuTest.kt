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
}
