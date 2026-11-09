package com.filestech.notes_tech.data.repository

import androidx.room.withTransaction
import com.filestech.notes_tech.data.local.DatabaseProvider
import com.filestech.notes_tech.domain.model.NoteColor
import javax.inject.Inject

/**
 * The selection's gestures on many notes at once (3.3.0) — out of [NotesRepository], which holds the
 * one-note ones and was at the edge of what detekt lets a class hold.
 *
 * ## 🔴 ONE transaction per gesture: all the notes, or none (D-037)
 *
 * A batch that stopped halfway — a vault closing between two notes, a full disk — would leave part of
 * the selection changed and the rest not, and the message could only say "failed" about a gesture that
 * half happened. Rolled back, it says the truth: nothing changed.
 *
 * Each note goes through the SAME function as one note does — [NotesRepository.setArchived],
 * [NotesRepository.moveToTrash], [NotesRepository.relocateLockedNote]… — inside the batch's transaction
 * (Room nests them): no second copy of what they write, of their link indexing, nor of their guards.
 *
 * ⚠️ **No vault check here**, as for one note: flags, colour, trash and deletion need no key. The caller
 * (`HomeViewModel.executer`) refuses a protected note of a closed vault BEFORE calling. The move is the
 * exception: its transaction needs the keys, and fails without them.
 *
 * Each returns the ids of the notes it changed, in the order given — fewer than asked when some already
 * were so, or are gone. What a gesture announces, and what its "Undo" puts back, is that list and
 * nothing else (external review, 2026-11-09: a count made the undo act on every id asked).
 */
class NotesEnLot @Inject constructor(
    private val databases: DatabaseProvider,
    private val notes: NotesRepository,
    private val folders: FoldersRepository,
) {

    suspend fun setArchived(ids: Collection<String>, archived: Boolean): List<String> =
        pourChacune(ids) { notes.setArchived(it, archived) }

    suspend fun setPinned(ids: Collection<String>, pinned: Boolean): List<String> = pourChacune(ids) {
        notes.setPinned(it, pinned)
    }

    suspend fun setFavorite(ids: Collection<String>, favorite: Boolean): List<String> =
        pourChacune(ids) { notes.setFavorite(it, favorite) }

    suspend fun setColor(ids: Collection<String>, color: NoteColor?): List<String> = pourChacune(ids) {
        notes.setColor(it, color)
    }

    suspend fun moveToTrash(ids: Collection<String>): List<String> = pourChacune(ids) { notes.moveToTrash(it) }

    /** The "Undo" of [moveToTrash]. */
    suspend fun restoreFromTrash(ids: Collection<String>): List<String> =
        pourChacune(ids) { notes.restoreFromTrash(it) }

    suspend fun deletePermanently(ids: Collection<String>): List<String> = pourChacune(ids) {
        notes.deletePermanently(it)
    }

    /**
     * Moves the notes to [folderId], each by the path the editor takes for it (`NoteEditorViewModel
     * .deplacerVers`): a sealed note by [NotesRepository.relocateLockedNote], any other by
     * [NotesRepository.moveToFolder], which seals it if [folderId] is a vault. A note already there is
     * left alone.
     *
     * 🔴 **A sealed note leaves its vault only if [sortieDeCoffreConfirmee]** — the screen's question,
     * asked when the selection holds a sealed note and [folderId] is not a vault. The state is read
     * again HERE, in the transaction, not taken from the screen: a note sealed since the selection was
     * made would otherwise leave its vault without the question. As in the editor, the refusal is loud —
     * the batch rolls back, the user tries again and gets the question — rather than a protection
     * removed in silence.
     *
     * @throws IllegalStateException a sealed note would leave its vault without the confirmation.
     * @throws com.filestech.notes_tech.domain.repository.VaultLockedException [folderId] is a closed
     *   vault, or a sealed note's own vault is closed — the whole batch is rolled back.
     */
    suspend fun moveToFolder(
        ids: Collection<String>,
        folderId: String,
        sortieDeCoffreConfirmee: Boolean,
    ): List<String> = pourChacune(ids) { id ->
        val actuelle = notes.find(id)
        when {
            actuelle == null || actuelle.folderId == folderId -> false
            actuelle.isLocked -> {
                // Read in the transaction too: the destination's kind decides whether this is an exit.
                val versUnCoffre = folders.find(folderId)?.isVault == true
                check(versUnCoffre || sortieDeCoffreConfirmee) { "sortie de coffre sans confirmation : $id" }
                notes.relocateLockedNote(id, folderId)
            }
            else -> notes.moveToFolder(id, folderId)
        }
    }

    private suspend fun pourChacune(ids: Collection<String>, geste: suspend (String) -> Boolean): List<String> {
        val database = databases.get()
        return database.withTransaction { ids.distinct().filter { geste(it) } }
    }
}
