package com.filestech.notes_tech.ui.home

import com.filestech.notes_tech.domain.model.NoteColor

/**
 * What the selection of a list can do to its notes (3.3.0) — one note or many: a long press selects,
 * and the one-note actions of 3.1.0's sheet became the selection's.
 *
 * A closed set, as [com.filestech.notes_tech.ui.folders.GesteDeDossier] is for folders: the screen that
 * waits for a vault's secret keeps the gesture itself, and an exhaustive `when` decides how each one
 * runs — a new gesture cannot be added without deciding it.
 *
 * ⚠️ [ids] only, never the cards' notes: whether a note may be touched is decided on a fresh read at
 * execution time (`HomeViewModel.executer`), never on what the list showed when the gesture was made.
 */
sealed interface GesteSurDesNotes {
    val ids: List<String>

    /**
     * Into the archive, or back out of it; the message offers to undo it — unless [annulation]: this IS
     * the undo of an archiving, said without an "Undo" of its own.
     */
    data class Archiver(override val ids: List<String>, val archiver: Boolean, val annulation: Boolean = false) :
        GesteSurDesNotes

    data class Epingler(override val ids: List<String>, val epingler: Boolean) : GesteSurDesNotes

    data class MettreEnFavori(override val ids: List<String>, val favori: Boolean) : GesteSurDesNotes

    /** [couleur] `null`: no colour. */
    data class Colorer(override val ids: List<String>, val couleur: NoteColor?) : GesteSurDesNotes

    /**
     * To [dossierId]. [sortieDeCoffreConfirmee]: the user answered the question that comes before a sealed
     * note leaves its vault — asked by the screen when the view model says it is due.
     */
    data class Deplacer(
        override val ids: List<String>,
        val dossierId: String,
        val sortieDeCoffreConfirmee: Boolean = false,
    ) : GesteSurDesNotes

    /** To the trash, for 30 days; the message offers to undo it. */
    data class MettreALaCorbeille(override val ids: List<String>) : GesteSurDesNotes

    /** Erased now, after a confirmation that says it cannot be undone. */
    data class SupprimerDefinitivement(override val ids: List<String>) : GesteSurDesNotes
}
