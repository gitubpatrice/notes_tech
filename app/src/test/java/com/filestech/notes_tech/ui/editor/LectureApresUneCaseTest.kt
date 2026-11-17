package com.filestech.notes_tech.ui.editor

import com.filestech.notes_tech.domain.markdown.MarkdownPreviewReader
import com.filestech.notes_tech.domain.markdown.PreviewBlock
import com.filestech.notes_tech.domain.markdown.TaskBoxes
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * D-047 (3.5.0): a box turned over is patched into the reading instead of reading the note again — which
 * would flash the list and send it back to its top at every tick. The patch must be EXACTLY what a full
 * reading of the new text gives: that is the whole test.
 */
@DisplayName("Lecture après une case cochée")
class LectureApresUneCaseTest {

    private val notes = listOf(
        "- [ ] Lait\n- [x] Pain\n- [ ] Beurre",
        "Courses [[Magasin]]\r\n\r\n- [ ] Lait\r\n- [X] Pain\r\n",
        "> - [ ] cité\n> - [x] aussi\n\n1. [ ] premier\n2. [x] second",
        "- a\n  - [ ] imbriquée\n  - [x] faite\n- [ ] b **gras** [lien](https://example.org)",
        "# Titre\n\nTexte.\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n- [ ] après un tableau",
    )

    private fun lue(source: String) = LectureDeLApercu.Lue(aplatir(MarkdownPreviewReader.read(source)))

    private fun offsets(source: String): List<Int> {
        val offsets = mutableListOf<Int>()
        fun walk(blocks: List<PreviewBlock>) {
            blocks.forEach { block ->
                when (block) {
                    is PreviewBlock.ListBlock -> block.items.forEach { item ->
                        item.taskOffset?.let(offsets::add)
                        walk(item.blocks)
                    }
                    is PreviewBlock.Quote -> walk(block.blocks)
                    else -> Unit
                }
            }
        }
        walk(MarkdownPreviewReader.read(source))
        return offsets
    }

    @Test
    fun `every box of every note, turned over, patches into what a full reading gives`() {
        var cases = 0
        notes.forEach { note ->
            offsets(note).forEach { offset ->
                val apres = TaskBoxes.toggle(note, offset)!!
                assertThat(lectureApresUneCase(lue(note), note, apres)).isEqualTo(lue(apres))
                cases++
            }
        }
        assertThat(cases).isEqualTo(13)
    }

    @Test
    fun `the blocks a tick does not touch are kept, the very objects`() {
        val note = "Avant.\n\n- [ ] Lait\n- [x] Pain\n\nAprès."
        val avant = lue(note)
        val apres = lectureApresUneCase(avant, note, TaskBoxes.toggle(note, offsets(note).first())!!)!!

        assertThat(apres.elements.first().bloc).isSameInstanceAs(avant.elements.first().bloc)
        assertThat(apres.elements.last().bloc).isSameInstanceAs(avant.elements.last().bloc)
        assertThat(apres.elements[1].bloc).isNotSameInstanceAs(avant.elements[1].bloc)
    }

    /**
     * 3-axes audit of 3.5.0: an item too heavy to draw block by block is shown as written, its `[ ]` in the
     * text. It has no offset — no tap ticks it —, and its box changed by hand reads the note again: a patched
     * icon would sit next to a "- [ ] …" saying the opposite.
     */
    @Test
    fun `the box of an item shown as written cannot be tapped, and is read again`() {
        val sousTaches = (1..MarkdownPreviewReader.MAX_BLOCKS_PER_ITEM + 1).joinToString("") { "  - sous-tâche $it\n" }
        val lourd = "- [ ] tâche lourde\n$sousTaches"
        val avant = lue(lourd)
        val element = (avant.elements.first().bloc as PreviewBlock.ListBlock).items.first()
        assertThat(element.blocks.single()).isInstanceOf(PreviewBlock.Code::class.java)
        assertThat(element.taskOffset).isNull()

        assertThat(lectureApresUneCase(avant, lourd, TaskBoxes.toggle(lourd, lourd.indexOf("[ ]"))!!)).isNull()
    }

    /** Anything but one box turned over: read again. */
    @Test
    fun `any other change is read again`() {
        val note = "- [ ] Lait\n- [x] Pain"
        val avant = lue(note)
        // A letter typed: another length.
        assertThat(lectureApresUneCase(avant, note, "- [ ] Lait!\n- [x] Pain")).isNull()
        // One character changed, but not in a box.
        assertThat(lectureApresUneCase(avant, note, "- [ ] Lail\n- [x] Pain")).isNull()
        // In a box, but to something that is no mark: the box is gone.
        assertThat(lectureApresUneCase(avant, note, "- [y] Lait\n- [x] Pain")).isNull()
        // Two boxes at once.
        assertThat(lectureApresUneCase(avant, note, "- [x] Lait\n- [ ] Pain")).isNull()
        // Brackets the reading knows as no box: text inside a code span.
        val code = "`[ ]` pas une case"
        assertThat(lectureApresUneCase(lue(code), code, "`[x]` pas une case")).isNull()
    }
}
