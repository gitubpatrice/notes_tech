package com.filestech.notes_tech.ui.editor

import com.filestech.notes_tech.core.text.TextStatistics
import java.time.Instant

/**
 * What the info panel shows about a note.
 *
 * ## ⚠️ Built from the EDITOR state, never from a database row
 *
 * A vault note's row carries an empty title and an empty content — the text lives in the sealed
 * blob. Counting from the row would report 0 words for a note full of text, and only for the notes
 * the user protects most. The counts are therefore taken from [EditorUiState.content], the text on
 * screen: for a vault note, the decrypted text the user is reading; for any note, what is typed
 * right now, including the last half-second the debounced save has not written yet.
 *
 * The dates come from [EditorUiState.note], which [NoteEditorViewModel] refreshes after every save:
 * "last modified" is the last time the text reached the disk, not the last key press.
 */
data class NoteInfo(
    val folderName: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val words: Int,
    val characters: Int,
)

/** `null` while nothing is loaded, or when the note could not be opened: there is nothing true to show. */
fun noteInfoOf(state: EditorUiState, folderName: String): NoteInfo? {
    val note = state.note ?: return null
    if (state.loading || state.loadError != null || state.lockedVault != null) return null
    val text = state.content.text
    return NoteInfo(
        folderName = folderName,
        createdAt = note.createdAt,
        updatedAt = note.updatedAt,
        words = TextStatistics.words(text),
        characters = TextStatistics.characters(text),
    )
}
