package com.filestech.notes_tech.ui.home

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.repository.FoldersRepository
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.data.repository.SearchRepository
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.model.NoteSortMode
import com.filestech.notes_tech.domain.repository.VaultLockedException
import com.filestech.notes_tech.security.vault.FolderVaultService
import com.filestech.notes_tech.ui.common.userMessageFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Ce que l'écran d'accueil affiche. */
data class HomeUiState(
    val notes: List<Note> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val query: String = "",
    val sort: NoteSortMode = NoteSortMode.DEFAULT,
    val currentFolder: Folder? = null,
    val folderNamesById: Map<String, String> = emptyMap(),
    val vaultLostCount: Int = 0,
) {
    /** `true` quand la liste n'est pas restreinte à un dossier — le badge de dossier sert alors. */
    val showFolderBadge: Boolean get() = currentFolder == null || query.isNotEmpty()
}

/**
 * Ce que l'accueil doit faire savoir a l'ecran apres une action.
 *
 * Un canal d'evenements, pas un champ d'etat : ces trois-la se produisent une fois. Les mettre dans
 * l'etat les ferait rejouer a chaque rotation.
 */
sealed interface HomeEvent {
    data class NoteCreated(val note: Note, val inInbox: Boolean) : HomeEvent

    /** Le dossier vise est un coffre ferme : il faut le secret avant de pouvoir y ecrire. */
    data class VaultLocked(val folder: Folder) : HomeEvent

    /** [message] is chosen by `userMessageFor` — never the exception's own text. */
    data class CreationFailed(@StringRes val message: Int) : HomeEvent

    /**
     * A long-press [geste] on a note of the vault [folder], which is closed: the screen asks for the
     * secret, then runs the gesture again — accepted, it must not be dropped in silence.
     */
    data class GesteEnAttenteDuCoffre(val folder: Folder, val geste: GesteSurUneNote) : HomeEvent

    /** The note went to the trash; the message offers to undo it. */
    data class MovedToTrash(val noteId: String) : HomeEvent

    data object Restored : HomeEvent

    data object DeletedForever : HomeEvent

    /** [message] is chosen by `userMessageFor`, as for [CreationFailed]. */
    data class ActionFailed(@StringRes val message: Int) : HomeEvent
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val notes: NotesRepository,
    private val folders: FoldersRepository,
    private val search: SearchRepository,
    private val settings: AppSettings,
    private val vaults: FolderVaultService,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    /**
     * `null` = « toutes les notes ».
     *
     * Dans [SavedStateHandle] et non dans un simple champ : le filtre survit à la mort du processus
     * en arrière-plan. Sans ça, revenir dans l'application après quelques heures ramène sur
     * « toutes les notes » alors que l'écran affichait un dossier — et l'utilisateur croit ses
     * notes déplacées.
     */
    private val folderId = MutableStateFlow(savedState.get<String>(CLE_DOSSIER))
    private val query = MutableStateFlow(savedState.get<String>(CLE_RECHERCHE).orEmpty())
    private val vaultLostCount = MutableStateFlow(settings.vaultLostDrafts().size)

    private val events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 4)
    val eventFlow = events.asSharedFlow()

    /**
     * Le texte de recherche **freiné**, et lui seul déclenche une requête.
     *
     * ⚠️ Le freinage porte sur la requête, pas sur l'affichage du champ : `query` alimente le texte
     * saisi immédiatement, `queryFreinee` alimente la base 250 ms plus tard. Les confondre donne
     * soit un champ qui répond en retard à la frappe, soit une requête par caractère.
     */
    private val queryFreinee = query.debounce(FREINAGE_MILLIS).distinctUntilChanged()

    val state: StateFlow<HomeUiState> = combine(
        listeDeNotes(),
        query,
        settings.sort,
        dossierCourant(),
        nomsDeDossiers(),
    ) { resultat, texte, tri, dossier, noms ->
        HomeUiState(
            notes = resultat.getOrDefault(emptyList()),
            loading = false,
            failed = resultat.isFailure,
            query = texte,
            sort = tri,
            currentFolder = dossier,
            folderNamesById = noms,
        )
    }.combine(vaultLostCount) { etat, perdues -> etat.copy(vaultLostCount = perdues) }
        .stateIn(
            scope = viewModelScope,
            // `WhileSubscribed(5 s)` et non `Eagerly` : sans abonné, garder ces flux actifs
            // maintiendrait des curseurs SQLCipher ouverts sur une base chiffrée pendant que
            // l'application est en arrière-plan. Les 5 secondes couvrent une rotation d'écran sans
            // relancer les requêtes.
            started = SharingStarted.WhileSubscribed(ARRET_DIFFERE_MILLIS),
            initialValue = HomeUiState(sort = settings.sortNow()),
        )

    fun onQueryChange(value: String) {
        query.value = value
        savedState[CLE_RECHERCHE] = value
    }

    fun onFolderSelected(id: String?) {
        folderId.value = id
        savedState[CLE_DOSSIER] = id
    }

    fun onSortSelected(mode: NoteSortMode) = settings.setSort(mode)

    /**
     * Crée une note dans le dossier affiché, ou dans la boîte de réception en vue « toutes ».
     *
     * ## 🔴 Le coffre verrouillé est refusé AVANT toute écriture
     *
     * `NotesRepository.create` scelle la note dans la même transaction que son insertion. Si le
     * coffre est fermé, le scellement lève et **la transaction est annulée** : aucune note en clair
     * ne subsiste dans un dossier protégé.
     *
     * C'est un écart avec l'application publiée, et il va dans le bon sens. Elle créait la note,
     * puis la chiffrait, puis la supprimait si le chiffrement échouait — une réparation qui devait
     * elle-même réussir pour que l'invariant tienne. La garde est ici structurelle : le chemin qui
     * établit l'invariant est le même que celui qui écrit.
     *
     * Le contrôle `isUnlocked` ci-dessous n'est donc PAS la protection, seulement la **courtoisie**
     * qui propose la saisie du secret au lieu d'un message d'erreur. La protection, c'est la
     * transaction.
     */
    fun createNote() {
        viewModelScope.launch {
            val cible = folderId.value ?: Folder.INBOX_ID
            val dossier = folders.find(cible)
            if (dossier?.isVault == true && !vaults.isUnlocked(cible)) {
                events.emit(HomeEvent.VaultLocked(dossier))
                return@launch
            }
            try {
                val creee = notes.create(folderId = cible)
                events.emit(HomeEvent.NoteCreated(creee, inInbox = folderId.value == null))
            } catch (e: CancellationException) {
                throw e
            } catch (_: VaultLockedException) {
                // Le coffre s'est refermé entre le contrôle et l'écriture — la garde échantillonnée
                // ne peut pas l'empêcher, seule la transaction le peut. Elle l'a fait.
                if (dossier != null) events.emit(HomeEvent.VaultLocked(dossier))
            } catch (e: Exception) {
                Timber.w(e, "creation de note")
                events.emit(HomeEvent.CreationFailed(userMessageFor(e)))
            }
        }
    }

    /**
     * Runs a long-press gesture on a note (3.1.0).
     *
     * ## 🔴 The vault is checked HERE, on a fresh read, and not on the card
     *
     * Putting a vault note in the trash, or erasing it, needs no key: the row goes, sealed blob and
     * all. So nothing structural stops it, and this check IS the protection. Without it, whoever gets
     * past the app lock could destroy a vault's notes without its secret, from the list — while the
     * editor, the only way to do it until now, needs the vault open to show the note at all.
     *
     * - **Fresh read**: the card's copy may be stale — the note moved into a vault since, or out.
     * - **At execution**: the sheet and the confirmation stay open as long as they like, and the
     *   automatic lock can fall in between. A check made when the sheet opened would be stale by the
     *   time the user confirms (same reasoning as the folder gestures in `HomeRoute`).
     * - **Vault folder, or a sealed note**: an empty note in a vault is not sealed yet, and a sealed
     *   note is protected wherever it sits.
     */
    fun executer(geste: GesteSurUneNote) {
        viewModelScope.launch {
     *
     * ⚠️ **Not atomic with the write, and accepted** (raised by both external reviews, 2026-10-10): an
     * automatic lock falling in the microseconds between this check and the DELETE/UPDATE lets the
     * gesture complete. That gesture was made while the vault WAS open, by whoever had opened it — the
     * editor's trash has always behaved so. What this check exists for, a gesture on a vault already
     * closed, is refused. Holding the session through the write would need a lease the sessions do not
     * have (`VaultSessions.whileUnlocking` marks an unlock in progress, it is not that).
            try {
                val actuelle = notes.find(geste.note.id) ?: return@launch
                val dossier = folders.find(actuelle.folderId)
                val protegee = actuelle.isLocked || dossier?.isVault == true
                if (protegee && !vaults.isUnlocked(actuelle.folderId)) {
                    if (dossier != null) events.emit(HomeEvent.GesteEnAttenteDuCoffre(dossier, geste))
                    return@launch
                }
                val evenement = when (geste) {
                    is GesteSurUneNote.MettreALaCorbeille ->
                        if (notes.moveToTrash(actuelle.id)) HomeEvent.MovedToTrash(actuelle.id) else null

                    is GesteSurUneNote.SupprimerDefinitivement ->
                        if (notes.deletePermanently(actuelle.id)) HomeEvent.DeletedForever else null
                }
                evenement?.let { events.emit(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "geste sur une note")
                events.emit(HomeEvent.ActionFailed(userMessageFor(e)))
            }
        }
    }

    /**
     * Undoes [executer]'s move to the trash. No vault check: a restored note comes back sealed, as it
     * left, and the trash screen restores vault notes the same way.
     */
    fun restaurer(noteId: String) {
        viewModelScope.launch {
            try {
                if (notes.restoreFromTrash(noteId)) events.emit(HomeEvent.Restored)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "annulation de la mise a la corbeille")
                events.emit(HomeEvent.ActionFailed(userMessageFor(e)))
            }
        }
    }

    fun dismissVaultLostBanner() {
        vaultLostCount.value = 0
        settings.clearVaultLostDrafts()
    }

    /** Purge les notes dont le séjour en corbeille a expiré. Sans effet visible si rien n'expire. */
    fun purgeExpiredTrash() {
        viewModelScope.launch {
            try {
                notes.purgeExpiredTrash()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Une purge ratée n'est pas une erreur à montrer : la corbeille se re-purgera au
                // démarrage suivant. La signaler ferait paniquer pour un rattrapage différé.
                Timber.w(e, "purge de la corbeille differee")
            }
        }
    }

    /**
     * Les notes à afficher, selon qu'une recherche est en cours ou non.
     *
     * ⚠️ **Le tri s'applique aussi en vue « toutes les notes ».** Il ne s'appliquait qu'à
     * l'intérieur d'un dossier dans la version Flutter, jusqu'à ce qu'une relecture externe le
     * relève : deux chemins censés se comporter pareil, et l'un ignorait le réglage. Le portage
     * part du comportement corrigé.
     *
     * ⚠️ **Une recherche ignore le filtre de dossier**, et c'est voulu : on cherche dans toutes ses
     * notes, pas dans celles du dossier qu'on regardait.
     */
    private fun listeDeNotes(): kotlinx.coroutines.flow.Flow<Result<List<Note>>> =
        combine(queryFreinee, folderId, settings.sort) { texte, dossier, tri -> Triple(texte, dossier, tri) }
            .flatMapLatest { (texte, dossier, tri) ->
                when {
                    texte.isNotBlank() -> search.observe(texte)
                    dossier == null -> notes.observeAllAlive(tri)
                    else -> notes.observeInFolder(dossier, tri)
                }
            }
            .map { Result.success(it) }
            .catch { erreur ->
                if (erreur is CancellationException) throw erreur
                Timber.e(erreur, "lecture de la liste de notes")
                emit(Result.failure(erreur))
            }

    private fun dossierCourant(): kotlinx.coroutines.flow.Flow<Folder?> =
        combine(folderId, folders.observeAll()) { id, tous ->
            // Le dossier est relu dans la liste observée, pas interrogé séparément : s'il est
            // supprimé ailleurs, il disparaît d'ici au même instant. Une lecture ponctuelle aurait
            // laissé un titre d'écran pointant un dossier qui n'existe plus.
            id?.let { cherche -> tous.firstOrNull { it.id == cherche } }
        }.distinctUntilChanged()

    private fun nomsDeDossiers(): kotlinx.coroutines.flow.Flow<Map<String, String>> =
        folders.observeAll().map { liste -> liste.associate { it.id to it.name } }.distinctUntilChanged()

    private companion object {
        /** `AppConstants.searchDebounce` — `core/constants.dart`. */
        const val FREINAGE_MILLIS = 250L
        const val ARRET_DIFFERE_MILLIS = 5_000L
        const val CLE_DOSSIER = "home.folderId"
        const val CLE_RECHERCHE = "home.query"
    }
}
