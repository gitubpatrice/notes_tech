package com.filestech.notes_tech.domain.markdown

/**
 * Ticking a GFM task box from the reading (D-047, 3.5.0): `[ ]` ↔ `[x]`, three characters for three.
 *
 * ⚠️ **Nothing is written unless a box is there.** The offset comes from a reading of the note
 * ([ListItem.taskOffset]); the text may have changed since — another tap, a save, a vault relocked and
 * reopened. The three characters are checked first, and anything else leaves the note as it is: the
 * reading is never trusted to point at the right place.
 *
 * Same length, so no other offset of the note moves: the matches of "Find in note", the links, the
 * position of the reading stay right.
 */
object TaskBoxes {

    /** What a box holds between its brackets: ` ` open, `x` or `X` done. */
    const val MARKS = " xX"

    /**
     * [text] with the box whose `[` is at [offset] turned over — an open box ticked, a ticked one (`x` or
     * `X`) opened —, or `null` when the characters there are not a box.
     */
    fun toggle(text: String, offset: Int): String? {
        if (offset < 0 || offset + 2 >= text.length) return null
        val mark = text[offset + 1]
        if (text[offset] != '[' || text[offset + 2] != ']' || mark !in MARKS) return null
        val turned = if (mark == ' ') 'x' else ' '
        return buildString(text.length) {
            append(text, 0, offset + 1)
            append(turned)
            append(text, offset + 2, text.length)
        }
    }

    /** The mark [character] stands for, between a box's brackets; `null` for anything else. */
    fun markOf(character: Char): TaskMark? = when (character) {
        ' ' -> TaskMark.OPEN
        'x', 'X' -> TaskMark.DONE
        else -> null
    }
}
