package com.filestech.notes_tech.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.domain.model.Note
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class TrashUiState(val notes: List<Note> = emptyList())

@HiltViewModel
class TrashViewModel @Inject constructor(private val notes: NotesRepository) : ViewModel() {

    val state: StateFlow<TrashUiState> = notes.observeTrash()
        .map { TrashUiState(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ARRET_DIFFERE_MILLIS),
            initialValue = TrashUiState(),
        )

    fun restore(noteId: String) = enExecutant { notes.restoreFromTrash(noteId) }

    /**
     * ⚠️ **Définitif.** Pas de seconde corbeille, pas d'annulation : c'est le contrat annoncé à
     * l'écran, et l'utilisateur vient de le confirmer dans un dialogue qui le dit.
     */
    fun deletePermanently(noteId: String) = enExecutant { notes.deletePermanently(noteId) }

    private fun enExecutant(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // La liste est observée : un échec se voit tout de suite, la note reste là où elle
                // était. Un message par-dessus dirait la même chose deux fois.
                Timber.w(e, "action de corbeille")
            }
        }
    }

    companion object {
        /** `TRASH_RETENTION_MILLIS` de `NotesRepository`, en jours, pour l'affichage. */
        const val RETENTION_DAYS = 30
        private const val ARRET_DIFFERE_MILLIS = 5_000L
    }
}
