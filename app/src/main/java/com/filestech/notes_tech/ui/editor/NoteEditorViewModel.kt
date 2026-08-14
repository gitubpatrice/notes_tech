package com.filestech.notes_tech.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.repository.FoldersRepository
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.repository.VaultLockedException
import com.filestech.notes_tech.security.vault.FolderVaultService
import com.filestech.notes_tech.security.vault.VaultSessionClosedException
import com.filestech.notes_tech.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/** L'état de l'éditeur. Le contenu affiché est **en clair, en mémoire seulement**. */
data class EditorUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val title: String = "",
    val content: String = "",
    val note: Note? = null,
    val folder: Folder? = null,
    val lockedVault: Folder? = null,
    val saving: Boolean = false,
    val lostToVaultLock: Boolean = false,
) {
    val isVaultNote: Boolean get() = folder?.isVault == true
}

/**
 * L'éditeur d'une note.
 *
 * ## 🔴 Le clair ne vit qu'ici, et il ne redescend jamais tel quel
 *
 * Une note de coffre est déchiffrée **pour l'affichage** dans un objet éphémère. L'enregistrement
 * repasse par `NotesRepository.saveEdits`, qui rescelle. Persister l'objet déchiffré remettrait le
 * clair en base — la garde d'écriture le refuse, mais mieux vaut ne pas compter dessus.
 *
 * ## 🔴 Le coffre peut se refermer PENDANT qu'on écrit
 *
 * C'est le cas qui coûte des données : l'utilisateur tape, le délai d'inactivité tombe, la
 * sauvegarde différée part et se fait refuser. Sans rien de plus, le texte n'existe nulle part et
 * rien ne le dit. La note est donc inscrite dans `vault_lost_drafts`, que l'accueil relit pour
 * afficher sa bannière — le seul signalement d'une perte silencieuse.
 */
@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    private val notes: NotesRepository,
    private val folders: FoldersRepository,
    private val vaults: FolderVaultService,
    private val settings: AppSettings,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val noteId: String = checkNotNull(savedState[Destination.ARG_NOTE_ID]) {
        "l'editeur a ete ouvert sans identifiant de note"
    }

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var sauvegardeDifferee: Job? = null

    init {
        charger()
    }

    fun onTitleChange(value: String) {
        _state.value = _state.value.copy(title = value)
        programmerLaSauvegarde()
    }

    fun onContentChange(value: String) {
        _state.value = _state.value.copy(content = value)
        programmerLaSauvegarde()
    }

    fun setPinned(pinned: Boolean) = enArrierePlan { notes.setPinned(noteId, pinned) }

    fun setFavorite(favorite: Boolean) = enArrierePlan { notes.setFavorite(noteId, favorite) }

    fun moveToTrash() = enArrierePlan { notes.moveToTrash(noteId) }

    /** Relance le chargement après un déverrouillage réussi. */
    fun retryAfterUnlock() {
        _state.value = _state.value.copy(lockedVault = null, loading = true)
        charger()
    }

    /**
     * Enregistre **maintenant**, sans attendre le délai.
     *
     * ⚠️ Appelé quand l'écran se ferme. `NonCancellable` : la portée du ViewModel est annulée à
     * l'instant où l'écran disparaît, et une sauvegarde annulable partirait à la poubelle avec
     * elle. C'est exactement la leçon de la phase 4 — rattraper une annulation avec du code
     * annulable ne rattrape rien.
     */
    fun saveNow() {
        sauvegardeDifferee?.cancel()
        viewModelScope.launch { withContext(NonCancellable) { enregistrer() } }
    }

    private fun charger() {
        viewModelScope.launch {
            val note = notes.find(noteId)
            if (note == null) {
                _state.value = EditorUiState(loading = false, notFound = true)
                return@launch
            }
            val dossier = folders.find(note.folderId)
            if (!note.isLocked) {
                _state.value = EditorUiState(
                    loading = false,
                    title = note.title,
                    content = note.content,
                    note = note,
                    folder = dossier,
                )
                return@launch
            }
            // Note scellée : il faut la session du coffre pour l'afficher.
            val claire = try {
                vaults.decrypt(note)
            } catch (e: CancellationException) {
                throw e
            } catch (_: VaultSessionClosedException) {
                _state.value = EditorUiState(loading = false, note = note, folder = dossier, lockedVault = dossier)
                return@launch
            } catch (e: Exception) {
                Timber.e(e, "dechiffrement de la note $noteId")
                _state.value = EditorUiState(loading = false, note = note, folder = dossier, lockedVault = dossier)
                return@launch
            }
            _state.value = EditorUiState(
                loading = false,
                title = claire.title,
                content = claire.content,
                note = note,
                folder = dossier,
            )
        }
    }

    /**
     * Programme un enregistrement après un temps de calme.
     *
     * ⚠️ Le travail précédent est **annulé** avant d'en lancer un nouveau. Sans ça, une frappe
     * rapide empile une sauvegarde par caractère, et sur une note de coffre, chacune paie un
     * chiffrement complet.
     */
    private fun programmerLaSauvegarde() {
        sauvegardeDifferee?.cancel()
        sauvegardeDifferee = viewModelScope.launch {
            delay(DELAI_AUTO_SAVE_MILLIS)
            enregistrer()
        }
    }

    private suspend fun enregistrer() {
        val courant = _state.value
        val note = courant.note ?: return
        if (courant.lockedVault != null) return
        if (courant.title == note.title && courant.content == note.content && !note.isLocked) return

        _state.value = courant.copy(saving = true)
        try {
            notes.saveEdits(
                id = noteId,
                title = courant.title,
                content = courant.content,
                tags = note.tags,
            )
            _state.value = _state.value.copy(saving = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: VaultLockedException) {
            signalerLaPerte()
        } catch (_: VaultSessionClosedException) {
            signalerLaPerte()
        } catch (e: Exception) {
            Timber.e(e, "enregistrement de la note $noteId")
            _state.value = _state.value.copy(saving = false)
        }
    }

    /**
     * 🔴 Inscrit la note dans la liste des modifications perdues.
     *
     * Le coffre s'est refermé entre la frappe et l'écriture. Le texte tapé n'existe plus qu'à
     * l'écran, et il disparaîtra avec lui. C'est la seule trace qui permettra à l'accueil de le
     * dire — sans elle, la perte est parfaitement silencieuse.
     */
    private fun signalerLaPerte() {
        settings.addVaultLostDraft(noteId)
        _state.value = _state.value.copy(saving = false, lostToVaultLock = true, lockedVault = _state.value.folder)
    }

    private fun enArrierePlan(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "action d'editeur sur $noteId")
            }
        }
    }

    private companion object {
        /** `AppConstants.autoSaveDebounce` — le même demi-seconde que la version publiée. */
        const val DELAI_AUTO_SAVE_MILLIS = 500L
    }
}
