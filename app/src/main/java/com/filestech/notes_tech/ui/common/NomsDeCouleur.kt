package com.filestech.notes_tech.ui.common

import androidx.annotation.StringRes
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.NoteColor

/**
 * The name of a note's colour, as TalkBack says it on a card and in the colour sheet (3.1.0).
 *
 * An exhaustive `when`: a new colour cannot be added without its name — a swatch announced as nothing
 * is a button without a name.
 */
@StringRes
internal fun NoteColor.nom(): Int = when (this) {
    NoteColor.YELLOW -> R.string.note_color_yellow
    NoteColor.ORANGE -> R.string.note_color_orange
    NoteColor.GREEN -> R.string.note_color_green
    NoteColor.TEAL -> R.string.note_color_teal
    NoteColor.BLUE -> R.string.note_color_blue
    NoteColor.PURPLE -> R.string.note_color_purple
    NoteColor.PINK -> R.string.note_color_pink
    NoteColor.GRAY -> R.string.note_color_gray
}
