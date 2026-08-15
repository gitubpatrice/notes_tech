package com.filestech.notes_tech.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.domain.model.Note
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class TrashUiState(val notes: List<Note> = emptyList())

/**
 * Ce que la corbeille a à dire.
 *
 * Les réussites parlent ici alors qu'elles se voient déjà — la note quitte la liste. C'est
 * délibéré : **un lecteur d'écran ne voit pas une carte disparaître.** Le même motif a produit
 * aujourd'hui un défaut d'accessibilité sur l'épingle de l'éditeur, où l'icône changeait sans que
 * rien ne l'annonce.
 */
sealed interface TrashEvent {
    /** Une note est repartie dans son dossier. */
    data object Restored : TrashEvent

    /** Une note a été détruite. */
    data object DeletedForever : TrashEvent

    /** La corbeille a été vidée. [count] est mesuré, pas supposé. */
    data class Emptied(val count: Int) : TrashEvent

    /** Le geste a échoué et **rien n'a changé** : la transaction n'a pas été appliquée. */
    data class Failed(val message: String?) : TrashEvent
}

@HiltViewModel
class TrashViewModel @Inject constructor(private val notes: NotesRepository) : ViewModel() {

    private val events = MutableSharedFlow<TrashEvent>(extraBufferCapacity = 4)
    val eventFlow: SharedFlow<TrashEvent> = events.asSharedFlow()

    val state: StateFlow<TrashUiState> = notes.observeTrash()
        .map { TrashUiState(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ARRET_DIFFERE_MILLIS),
            initialValue = TrashUiState(),
        )

    fun restore(noteId: String) = enExecutant {
        if (notes.restoreFromTrash(noteId)) TrashEvent.Restored else null
    }

    /**
     * ⚠️ **Définitif.** Pas de seconde corbeille, pas d'annulation : c'est le contrat annoncé à
     * l'écran, et l'utilisateur vient de le confirmer dans un dialogue qui le dit.
     */
    fun deletePermanently(noteId: String) = enExecutant {
        if (notes.deletePermanently(noteId)) TrashEvent.DeletedForever else null
    }

    /**
     * Vide la corbeille.
     *
     * ⚠️ **N'annonce rien quand elle était déjà vide** — `count` à zéro ne remonte aucun message.
     * L'écran masque de toute façon l'action dans ce cas ; la garde est ici parce qu'une corbeille
     * peut se vider entre l'affichage du bouton et la confirmation du dialogue.
     */
    fun emptyTrash() = enExecutant {
        val supprimees = notes.emptyTrash()
        if (supprimees > 0) TrashEvent.Emptied(supprimees) else null
    }

    /**
     * Exécute [block] et publie ce qu'il retourne, `null` valant « rien à dire ».
     *
     * ⚠️ **`tryEmit` et non `emit`** : cette portée ne doit pas rester suspendue à attendre un
     * collecteur. L'écran peut être parti — c'est même le cas normal d'une rotation — et le message
     * serait alors sans destinataire.
     */
    private fun enExecutant(block: suspend () -> TrashEvent?) {
        viewModelScope.launch {
            val evenement = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "action de corbeille")
                TrashEvent.Failed(e.message)
            }
            if (evenement != null) events.tryEmit(evenement)
        }
    }

    companion object {
        /** `TRASH_RETENTION_MILLIS` de `NotesRepository`, en jours, pour l'affichage. */
        const val RETENTION_DAYS = 30
        private const val ARRET_DIFFERE_MILLIS = 5_000L
    }
}
