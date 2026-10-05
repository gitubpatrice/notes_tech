package com.filestech.notes_tech.ui.trash

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.ui.common.userMessageFor
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

/**
 * ⚠️ **[loading] vaut `true` avant la première émission, et c'est ce qui manquait.**
 *
 * `stateIn` rend obligatoirement une valeur initiale, donc une liste **vide** avant que la base ait
 * répondu. Sans ce drapeau, l'écran traduisait cette absence de réponse en « La corbeille est
 * vide » : un message faux, affiché à qui a des notes en corbeille, et *annoncé* comme tel par un
 * lecteur d'écran. `trash_screen.dart` distingue les deux cas depuis toujours — son `items` est
 * `null` tant que la lecture n'a pas rendu, et il montre alors un indicateur d'activité.
 *
 * C'est le même triplet que [com.filestech.notes_tech.ui.home.HomeUiState], moins l'échec : la
 * corbeille lit un flux Room sans repli, un échec de lecture s'y manifeste par une exception à
 * l'ouverture de la base, pas par un état d'écran.
 */
data class TrashUiState(val notes: List<Note> = emptyList(), val loading: Boolean = true)

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
    /** [message] is chosen by `userMessageFor` — never the exception's own text. */
    data class Failed(@StringRes val message: Int) : TrashEvent
}

@HiltViewModel
class TrashViewModel @Inject constructor(private val notes: NotesRepository) : ViewModel() {

    private val events = MutableSharedFlow<TrashEvent>(extraBufferCapacity = 4)
    val eventFlow: SharedFlow<TrashEvent> = events.asSharedFlow()

    val state: StateFlow<TrashUiState> = notes.observeTrash()
        .map { TrashUiState(it, loading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ARRET_DIFFERE_MILLIS),
            initialValue = TrashUiState(),
        )

    /**
     * ⚠️ **Un `false` ne dit rien, et c'est vérifié plutôt que supposé.**
     *
     * L'invariant du dépôt veut qu'un geste sans effet se signale. Ici le `false` de
     * [NotesRepository.restoreFromTrash] ne peut avoir qu'une cause : la ligne n'est plus dans la
     * corbeille. Or la carte n'est à l'écran que parce qu'elle y est, la purge des trente jours ne
     * tourne qu'à l'entrée de l'écran d'accueil, et l'application est seule sur sa base. Il ne reste
     * donc que le **double appui** : le premier restaure et annonce, le second retombe ici pendant
     * la fraction de seconde qui précède le retrait de la carte.
     *
     * Annoncer un échec à ce moment-là afficherait « impossible de restaurer » **par-dessus** le
     * message de la restauration qui vient de réussir. Le silence n'est pas un oubli : c'est le seul
     * comportement qui ne ment pas. Point soulevé par une relecture externe (GPT-5.2, 2026-08-15),
     * écarté après analyse d'atteignabilité.
     */
    fun restore(noteId: String) = enExecutant {
        if (notes.restoreFromTrash(noteId)) TrashEvent.Restored else null
    }

    /**
     * ⚠️ **Définitif.** Pas de seconde corbeille, pas d'annulation : c'est le contrat annoncé à
     * l'écran, et l'utilisateur vient de le confirmer dans un dialogue qui le dit.
     *
     * Son `false` muet a la même justification que celui de [restore], au double appui près : ici il
     * faut deux confirmations, ce qui rend le cas encore moins atteignable.
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
                TrashEvent.Failed(userMessageFor(e))
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
