package com.filestech.notes_tech.ui.depot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.domain.importation.LongueurDUneNote
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.security.panic.PanicJournal
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** What the blind drop shows: what is being typed, and nothing else (D-042). */
data class EtatDuDepot(
    val titre: String = "",
    val texte: String = "",
    val enCours: Boolean = false,
    val echec: Boolean = false,
    val depose: Boolean = false,
) {
    val peutDeposer: Boolean get() = (titre.isNotBlank() || texte.isNotBlank()) && !enCours && !depose
}

/**
 * The blind drop (D-042, 3.4.0): a note written without unlocking the app, and never read back.
 *
 * 🔴 **Writes, never reads.** Its one dependency on the notes is [NotesRepository.create]: no list, no
 * search, no count, no folder name. What it holds is what is being typed, in memory — here, NOT in the
 * saved state: a Bundle would keep the plaintext on disk with the activity's state, which the note's
 * body field never allows either (`ChampDuCorps`). A rotation keeps the text, a process death loses it.
 *
 * The note goes to the Inbox — never a vault: a vault's note is sealed under its key, and the drop runs
 * with no vault open, as the app lock is not passed.
 */
@HiltViewModel
class DepotViewModel @Inject constructor(private val notes: NotesRepository, private val panique: PanicJournal) :
    ViewModel() {

    private val _etat = MutableStateFlow(EtatDuDepot())
    val etat: StateFlow<EtatDuDepot> = _etat.asStateFlow()

    fun titre(valeur: String) {
        if (valeur.length <= NotesRepository.TITLE_MAX_LENGTH) _etat.update { it.copy(titre = valeur, echec = false) }
    }

    fun texte(valeur: String) {
        if (valeur.length <= LongueurDUneNote.CARACTERES_MAX) _etat.update { it.copy(texte = valeur, echec = false) }
    }

    fun deposer() {
        val courant = _etat.value
        if (!courant.peutDeposer) return
        // 🔴 A panic cut before its end is finished by the app before anything else (audit 2026-09-26,
        // P2): the drop must not write into the database it is destroying.
        if (panique.isPending()) {
            _etat.update { it.copy(echec = true) }
            return
        }
        _etat.update { it.copy(enCours = true, echec = false) }
        viewModelScope.launch {
            try {
                notes.create(folderId = Folder.INBOX_ID, title = courant.titre.trim(), content = courant.texte)
                // Gone from the screen at once: nothing typed stays to be read.
                _etat.value = EtatDuDepot(depose = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "depot refuse")
                _etat.update { it.copy(enCours = false, echec = true) }
            }
        }
    }
}
