package com.filestech.notes_tech.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.repository.FoldersRepository
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.di.ApplicationScope
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.repository.VaultLockedException
import com.filestech.notes_tech.security.vault.FolderVaultService
import com.filestech.notes_tech.security.vault.VaultSessionClosedException
import com.filestech.notes_tech.ui.navigation.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val saveFailed: Boolean = false,
    /**
     * Le texte tel qu'il a été CHARGÉ, en clair.
     *
     * 🔴 Sans lui, aucune comparaison honnête n'est possible sur une note de coffre : `note.title`
     * y vaut la chaîne vide (le titre vit dans le chiffré), donc « le titre a-t-il changé ? »
     * répondait **toujours oui**. Ouvrir une note de coffre pour la LIRE, puis revenir, la
     * rechiffrait et repoussait sa date de modification — elle remontait en tête de liste sans que
     * personne n'y ait touché. Relevé par une relecture externe (Gemini, 2026-08-14).
     */
    val originalTitle: String = "",
    val originalContent: String = "",
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
    @ApplicationScope private val applicationScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val noteId: String = checkNotNull(savedState[Destination.ARG_NOTE_ID]) {
        "l'editeur a ete ouvert sans identifiant de note"
    }

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var sauvegardeDifferee: Job? = null

    /**
     * Serialise les ecritures de cette note.
     *
     * 🔴 `sauvegardeDifferee?.cancel()` n'arrete PAS une ecriture Room deja engagee. Sans ce
     * verrou, la sauvegarde differee peut se terminer APRES la finale et reecrire une version plus
     * ancienne — une perte silencieuse, exactement ce que la sauvegarde finale existe pour empecher.
     *
     * Releve en relisant les correctifs de relecture (GPT-5.2, 2026-08-14) : le correctif precedent
     * garantissait que la finale s'execute, pas qu'elle s'execute EN DERNIER.
     */
    private val ecriture = Mutex()

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
        // 🔴 **La portee du PROCESSUS, pas celle du ViewModel.**
        //
        // `viewModelScope` est annulé à l'instant où l'écran disparaît, c'est-à-dire exactement quand
        // cette sauvegarde est demandée. `withContext(NonCancellable)` ne protège qu'une coroutine
        // **déjà démarrée** : sur une portee annulée, `launch` crée une coroutine qui n'exécute
        // jamais son corps, et le texte tapé disparaît sans un mot.
        //
        // C'est la même leçon que la phase 4, un cran plus loin : ce n'est pas seulement l'annulation
        // qu'il faut rattraper, c'est la PORTEE qui doit survivre au geste qu'elle exécute. Relevé
        // par une relecture externe (Gemini, 2026-08-14).
        val instantane = _state.value
        applicationScope.launch {
            withContext(NonCancellable) { enregistrer(instantane) }
        }
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
                    originalTitle = note.title,
                    originalContent = note.content,
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
                originalTitle = claire.title,
                originalContent = claire.content,
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

    private suspend fun enregistrer(instantane: EditorUiState? = null) = ecriture.withLock {
        val courant = instantane ?: _state.value
        val note = courant.note ?: return
        if (courant.lockedVault != null) return
        // ⚠️ Comparer au texte CHARGÉ, pas à l'entité en base : pour une note scellée, l'entité ne
        // porte pas le clair. Et **pas** de `!note.isLocked` ici : cette condition faisait sauter la
        // garde pour les seules notes où elle comptait.
        if (courant.title == courant.originalTitle && courant.content == courant.originalContent) return

        // ⚠️ `_state.value` et non `courant` : l'instantane sert a savoir QUOI persister, jamais a
        // reecrire l'etat de l'ecran. Le recopier reinjecterait un titre et un contenu peut-etre
        // plus anciens que ce que l'utilisateur a sous les yeux.
        _state.value = _state.value.copy(saving = true, saveFailed = false)
        try {
            notes.saveEdits(
                id = noteId,
                title = courant.title,
                content = courant.content,
                tags = note.tags,
            )
            _state.value = _state.value.copy(
                saving = false,
                // Ce qui vient d'etre persiste devient la nouvelle reference de comparaison.
                originalTitle = courant.title,
                originalContent = courant.content,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: VaultLockedException) {
            signalerLaPerte()
        } catch (_: VaultSessionClosedException) {
            signalerLaPerte()
        } catch (e: Exception) {
            // 🔴 **Un échec d'enregistrement DOIT se voir.** Journaliser et rendre la main laissait
            // l'utilisateur taper dans le vide : l'écran se comportait normalement, et le texte
            // n'existait nulle part. Stockage plein, base verrouillée, erreur SQLCipher — toutes ces
            // causes produisaient une perte parfaitement silencieuse.
            //
            // C'est l'invariant « une perte de données se signale », et il était tenu pour le
            // verrouillage de coffre (bannière) mais pas pour le reste. Jumeau asymétrique. Relevé
            // par une relecture externe (GPT-5.2, 2026-08-14).
            Timber.e(e, "enregistrement de la note $noteId")
            _state.value = _state.value.copy(saving = false, saveFailed = true)
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
