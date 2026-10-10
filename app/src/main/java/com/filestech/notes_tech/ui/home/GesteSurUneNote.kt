package com.filestech.notes_tech.ui.home

import com.filestech.notes_tech.domain.model.Note

/**
 * What the long press on a note in a list can do (3.1.0).
 *
 * A closed set, as [com.filestech.notes_tech.ui.folders.GesteDeDossier] is for folders: the screen
 * that waits for a vault's secret keeps the gesture itself, and an exhaustive `when` decides how each
 * one resumes after the unlock — a third gesture cannot be added without deciding it.
 *
 * ⚠️ [note] is the card's copy, used to NAME the note only. Whether it may be touched is decided on a
 * fresh read at execution time (`HomeViewModel.executer`), never on this snapshot.
 */
sealed interface GesteSurUneNote {
    val note: Note

    /** To the trash, for 30 days; the message that says so offers to undo it. */
    data class MettreALaCorbeille(override val note: Note) : GesteSurUneNote

    /** Erased now, after a confirmation that says it cannot be undone. */
    data class SupprimerDefinitivement(override val note: Note) : GesteSurUneNote
}
