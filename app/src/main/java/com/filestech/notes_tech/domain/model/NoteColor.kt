package com.filestech.notes_tech.domain.model

/**
 * The background colour a note can carry in the lists (3.1.0).
 *
 * ## What is stored is [id], never a colour value
 *
 * The column `notes.color_id` holds this number. The colour itself is decided by the screen, per theme
 * (`ui/theme/CouleursDeNote.kt`): a light pastel under the light theme's dark text would be unreadable
 * under the dark theme's light text, so one stored ARGB could not be right in both. A number also lets
 * the palette be retuned without touching anyone's notes.
 *
 * ⚠️ **The ids are written in users' databases: they never change, and a removed colour leaves a hole.**
 * An id this version does not know — written by a later one — reads as no colour ([fromId]), so an
 * older version shows a neutral card rather than failing.
 *
 * There is no red: red is the border of a locked note, and a red note would look locked.
 */
enum class NoteColor(val id: Int) {
    YELLOW(1),
    ORANGE(2),
    GREEN(3),
    TEAL(4),
    BLUE(5),
    PURPLE(6),
    PINK(7),
    GRAY(8),
    ;

    companion object {
        /** The colour stored as [id], or `null` for none — and for an id this version does not know. */
        fun fromId(id: Int?): NoteColor? = id?.let { stocke -> entries.firstOrNull { it.id == stocke } }
    }
}
