package com.filestech.notes_tech.ui.home

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.repository.FoldersRepository
import com.filestech.notes_tech.data.repository.NotesEnLot
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
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
    /**
     * The vaults open right now: a vault note's colour shows only then (`couleurVisible`, 3.1.0). Set by
     * `HomeRoute` from [HomeViewModel.coffresOuverts], never cached with the rest of this state.
     */
    val coffresOuverts: Set<String> = emptySet(),
    /** The archive is shown (3.3.0): the archived notes of every folder, and nothing else. */
    val archive: Boolean = false,
    /**
     * The selected notes (3.3.0), among those [notes] shows — never one the list no longer holds: an
     * action of the selection acts on what the user sees selected, nothing else. Empty: no selection.
     */
    val selection: Set<String> = emptySet(),
) {
    /** `true` quand la liste n'est pas restreinte à un dossier — le badge de dossier sert alors. */
    val showFolderBadge: Boolean get() = currentFolder == null || query.isNotEmpty()

    val enSelection: Boolean get() = selection.isNotEmpty()

    /** The selected notes, in the list's order. */
    val notesSelectionnees: List<Note> get() = notes.filter { it.id in selection }
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
     * A [geste] that needs the vault [folder], which is closed — a selected note of it, or the
     * destination of a move: the screen asks for the secret, then runs the gesture again — accepted, it
     * must not be dropped in silence.
     */
    data class GesteEnAttenteDuCoffre(val folder: Folder, val geste: GesteSurDesNotes) : HomeEvent

    /**
     * [notesScellees] sealed notes of the move [geste] would leave their vault (3.3.0): the screen asks
     * the editor's question, then runs [geste] with the answer.
     */
    data class SortieDeCoffreAConfirmer(val geste: GesteSurDesNotes.Deplacer, val notesScellees: Int) : HomeEvent

    /**
     * [ids] went into the archive ([archivees]) or out of it. [annulable]: the message offers to undo
     * it — not when it IS the undo, which would make an endless chain of "Undo".
     */
    data class Archivees(val ids: List<String>, val archivees: Boolean, val annulable: Boolean = true) : HomeEvent

    /** [ids] went to the trash; the message offers to undo it. */
    data class MisesALaCorbeille(val ids: List<String>) : HomeEvent

    /** The undo of [MisesALaCorbeille]: [nombre] notes back where they were. */
    data class Restaurees(val nombre: Int) : HomeEvent

    data class Deplacees(val nombre: Int) : HomeEvent

    data class Supprimees(val nombre: Int) : HomeEvent

    /** [message] is chosen by `userMessageFor`, as for [CreationFailed]. */
    data class ActionFailed(@StringRes val message: Int) : HomeEvent
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val notes: NotesRepository,
    private val enLot: NotesEnLot,
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

    /** The archive is shown (3.3.0) — `folderId` is then `null`. Saved for the same reason as it. */
    private val archive = MutableStateFlow(savedState.get<Boolean>(CLE_ARCHIVE) ?: false)
    private val query = MutableStateFlow(savedState.get<String>(CLE_RECHERCHE).orEmpty())
    private val vaultLostCount = MutableStateFlow(settings.vaultLostDrafts().size)

    /**
     * The selection as the user made it (3.3.0); [state] shows it cut down to the notes on screen. Saved:
     * a rotation, or the process dying in the background, does not lose a selection being made.
     */
    private val selection = MutableStateFlow(savedState.get<ArrayList<String>>(CLE_SELECTION).orEmpty().toSet())

    private val events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 4)
    val eventFlow = events.asSharedFlow()

    /** The note creation running, if any — see [createNote]. */
    private var creation: Job? = null

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
        .combine(archive) { etat, enArchive -> etat.copy(archive = enArchive) }
        .combine(selection) { etat, choisies ->
            // Cut down to what the list holds: a selected note gone from it — trashed elsewhere, moved
            // out of the folder shown — is not acted on by a gesture the user cannot see it in.
            val presentes = etat.notes.mapTo(HashSet()) { it.id }
            etat.copy(selection = choisies.filterTo(LinkedHashSet()) { it in presentes })
        }
        .stateIn(
            scope = viewModelScope,
            // `WhileSubscribed(5 s)` et non `Eagerly` : sans abonné, garder ces flux actifs
            // maintiendrait des curseurs SQLCipher ouverts sur une base chiffrée pendant que
            // l'application est en arrière-plan. Les 5 secondes couvrent une rotation d'écran sans
            // relancer les requêtes.
            started = SharingStarted.WhileSubscribed(ARRET_DIFFERE_MILLIS),
            initialValue = HomeUiState(sort = settings.sortNow()),
        )

    /**
     * The vaults open right now, straight from the sessions (3.1.0). NOT through [state]: `stateIn`
     * keeps its last value while nobody collects it, so a vault that locked meanwhile would still read
     * as open on the first frame back on the home screen — and a sealed note would flash its colour
     * (both external reviews, 2026-10-10). The sessions' flow is hot and always current.
     */
    val coffresOuverts: StateFlow<Set<String>> = vaults.unlockedFolderIds

    fun onQueryChange(value: String) {
        query.value = value
        savedState[CLE_RECHERCHE] = value
    }

    /** A folder, or `null` for "All notes" — which leaves the archive, and the selection made in another list. */
    fun onFolderSelected(id: String?) {
        folderId.value = id
        savedState[CLE_DOSSIER] = id
        montrerLArchive(false)
        viderLaSelection()
    }

    /**
     * The drawer's "Archive" (3.3.0). The search is cleared: the archive has no search field, and a
     * search kept from before would still filter a list that cannot show it (the search leaves
     * archived notes out, as notes_tech 2.0.9's does — D-036).
     */
    fun onArchiveSelected() {
        folderId.value = null
        savedState[CLE_DOSSIER] = null
        onQueryChange("")
        montrerLArchive(true)
        viderLaSelection()
    }

    fun onSortSelected(mode: NoteSortMode) = settings.setSort(mode)

    // ── La sélection (3.3.0) ─────────────────────────────────────────────────

    /**
     * Selects [id], or deselects it. From the selection the user SEES — the one [state] shows — not the
     * one kept: an id the list no longer holds is dropped here rather than kept for a later surprise.
     */
    fun basculerLaSelection(id: String) {
        val visible = state.value.selection
        ecrireLaSelection(if (id in visible) visible - id else visible + id)
    }

    /** Selects [ids] on top of the selection shown — "Select all", and a note whose vault was just opened. */
    fun ajouterALaSelection(ids: Collection<String>) = ecrireLaSelection(state.value.selection + ids)

    fun viderLaSelection() = ecrireLaSelection(emptySet())

    private fun ecrireLaSelection(ids: Set<String>) {
        selection.value = ids
        savedState[CLE_SELECTION] = ArrayList(ids)
    }

    private fun montrerLArchive(oui: Boolean) {
        archive.value = oui
        savedState[CLE_ARCHIVE] = oui
    }

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
     *
     * 🔴 **One creation at a time** (3.2.1): two quick taps on « Nouvelle note » created two notes,
     * the second one left empty in the list (measured, `DoubleToucherTest`). The tap that comes once
     * the editor is opening is dropped by the screen (`HomeRoute`, `dropUnlessResumed`); the one that
     * comes while the creation still runs is dropped here, as `ImportViewModel` and `ExportViewModel`
     * drop theirs. ⚠️ No test reaches this second case: on the emulator the creation is over before
     * any second tap. It is kept for slow phones, where the database write takes longer.
     */
    fun createNote() {
        if (creation?.isActive == true) return
        creation = viewModelScope.launch {
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
     * Runs a gesture of the selection (3.3.0; one note at a time in 3.1.0).
     *
     * ## 🔴 The vaults are checked HERE, on a fresh read, and not on the cards
     *
     * Putting a vault note in the trash, erasing it, archiving it, need no key: the row goes, or one
     * flag changes, sealed blob and all. So nothing structural stops it, and this check IS the
     * protection. Without it, whoever gets past the app lock could destroy a vault's notes without its
     * secret, from the list — while the editor, the only way to do it until 3.1.0, needs the vault open
     * to show the note at all.
     *
     * - **Fresh read**: the cards' copies may be stale — a note moved into a vault since, or out.
     * - **At execution**: the menus and confirmations stay open as long as they like, and the
     *   automatic lock can fall in between. A check made when the selection was made would be stale by
     *   the time the user confirms (same reasoning as the folder gestures in `HomeRoute`).
     * - **Vault folder, or a sealed note**: an empty note in a vault is not sealed yet, and a sealed
     *   note is protected wherever it sits.
     * - **Any note refused refuses the gesture**: nothing runs until the vault is open — then all of it
     *   does, in one transaction. Running the part that may run would leave the user with a selection
     *   half done and a message about the other half.
     *
     * Then, for a move: a closed vault as destination asks for ITS secret, and sealed notes about to
     * leave their vault ask the editor's question — both before anything is written.
     *
     * ⚠️ **Not atomic with the write, and accepted** (raised by both external reviews, 2026-10-10): an
     * automatic lock falling in the microseconds between this check and the write lets the gesture
     * complete. That gesture was made while the vault WAS open, by whoever had opened it — the editor's
     * trash has always behaved so. What this check exists for, a gesture on a vault already closed, is
     * refused. Holding the session through the write would need a lease the sessions do not have.
     */
    fun executer(geste: GesteSurDesNotes) {
        viewModelScope.launch {
            try {
                // Gone or in the trash since the selection: nothing to do to it.
                val actuelles = notes.findMany(geste.ids).filter { !it.isTrashed }
                if (actuelles.isEmpty()) {
                    viderLaSelection()
                    return@launch
                }
                val attente = attenteAvant(geste, actuelles)
                if (attente != null) {
                    events.emit(attente)
                    return@launch
                }
                val evenement = appliquer(geste, actuelles)
                viderLaSelection()
                evenement?.let { events.emit(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "geste sur des notes")
                events.emit(HomeEvent.ActionFailed(userMessageFor(e)))
            }
        }
    }

    /** What must come before [geste] runs — a vault's secret, the exit question — or `null`: it may run. */
    private suspend fun attenteAvant(geste: GesteSurDesNotes, actuelles: List<Note>): HomeEvent? {
        val coffreDUneNote = coffreFermeDe(actuelles)
        return when {
            coffreDUneNote != null -> HomeEvent.GesteEnAttenteDuCoffre(coffreDUneNote, geste)
            geste is GesteSurDesNotes.Deplacer -> attenteAvantLeDeplacement(geste, actuelles)
            else -> null
        }
    }

    /** A closed vault as destination asks for ITS secret; sealed notes leaving their vault, the question. */
    private suspend fun attenteAvantLeDeplacement(geste: GesteSurDesNotes.Deplacer, actuelles: List<Note>): HomeEvent? {
        // A destination gone since the sheet: the repository would refuse it, said as a failure.
        val destination = folders.find(geste.dossierId) ?: error("dossier de destination introuvable")
        val sorties = actuelles.count { it.isLocked && it.folderId != destination.id }
        return when {
            destination.isVault &&
                !vaults.isUnlocked(
                    destination.id,
                ) -> HomeEvent.GesteEnAttenteDuCoffre(destination, geste)
            destination.isVault || geste.sortieDeCoffreConfirmee || sorties == 0 -> null
            else -> HomeEvent.SortieDeCoffreAConfirmer(geste, sorties)
        }
    }

    /** The first closed vault holding a protected note of [actuelles], or `null`: every one may be touched. */
    private suspend fun coffreFermeDe(actuelles: List<Note>): Folder? {
        for ((dossierId, siennes) in actuelles.groupBy { it.folderId }) {
            val dossier = folders.find(dossierId)
            val protegee = dossier?.isVault == true || siennes.any { it.isLocked }
            if (protegee && !vaults.isUnlocked(dossierId)) {
                // A sealed note always has its folder (cascade): no folder here is a broken base, said so.
                return dossier ?: throw VaultLockedException(noteId = siennes.first().id, folderId = dossierId)
            }
        }
        return null
    }

    /** Writes [geste] on [actuelles], in one transaction, and says what to announce — `null`: nothing. */
    private suspend fun appliquer(geste: GesteSurDesNotes, actuelles: List<Note>): HomeEvent? {
        val ids = actuelles.map { it.id }
        return when (geste) {
            is GesteSurDesNotes.Archiver -> {
                // Only those that change: the "Undo" must put back exactly what this gesture moved.
                val aChanger = actuelles.filter { it.archived != geste.archiver }.map { it.id }
                enLot.setArchived(aChanger, geste.archiver)
                if (aChanger.isEmpty()) null else HomeEvent.Archivees(aChanger, geste.archiver)
            }

            // Said by the cards themselves — the pin, the star, the colour — as in the editor's menu.
            is GesteSurDesNotes.Epingler -> null.also { enLot.setPinned(ids, geste.epingler) }
            is GesteSurDesNotes.MettreEnFavori -> null.also { enLot.setFavorite(ids, geste.favori) }
            is GesteSurDesNotes.Colorer -> null.also { enLot.setColor(ids, geste.couleur) }

            is GesteSurDesNotes.Deplacer -> enLot.moveToFolder(ids, geste.dossierId, geste.sortieDeCoffreConfirmee)
                .takeIf { it > 0 }?.let { HomeEvent.Deplacees(it) }

            is GesteSurDesNotes.MettreALaCorbeille ->
                if (enLot.moveToTrash(ids) > 0) HomeEvent.MisesALaCorbeille(ids) else null

            is GesteSurDesNotes.SupprimerDefinitivement ->
                enLot.deletePermanently(ids).takeIf { it > 0 }?.let { HomeEvent.Supprimees(it) }
        }
    }

    /**
     * Undoes [executer]'s move to the trash. No vault check: a restored note comes back sealed, as it
     * left, and the trash screen restores vault notes the same way.
     */
    fun restaurer(ids: List<String>) = enAnnulant { HomeEvent.Restaurees(enLot.restoreFromTrash(ids)) }

    /**
     * Undoes an archiving, or an unarchiving: [ids] go back where they were. No vault check, for the
     * reason given on [restaurer]: the gesture being undone was checked, and this one only puts back the
     * flag it changed. Said without an "Undo" of its own.
     */
    fun annulerLArchivage(ids: List<String>, archivees: Boolean) = enAnnulant {
        enLot.setArchived(ids, !archivees)
        HomeEvent.Archivees(ids, archivees = !archivees, annulable = false)
    }

    private fun enAnnulant(bloc: suspend () -> HomeEvent) {
        viewModelScope.launch {
            try {
                events.emit(bloc())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "annulation d'un geste sur des notes")
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
     * notes, pas dans celles du dossier qu'on regardait. Et elle laisse les archives de côté, comme la
     * recherche de la 2.0.9 (D-036) : l'archive, elle, ignore la recherche — l'écran n'en a pas le champ.
     */
    private fun listeDeNotes(): Flow<Result<List<Note>>> =
        combine(queryFreinee, folderId, archive, settings.sort) { texte, dossier, enArchive, tri ->
            Vue(texte, dossier, enArchive, tri)
        }
            .flatMapLatest { vue ->
                when {
                    vue.archive -> notes.observeOutOfTrash(vue.tri, archived = true)
                    vue.texte.isNotBlank() -> search.observe(vue.texte)
                    vue.dossier == null -> notes.observeOutOfTrash(vue.tri, archived = false)
                    else -> notes.observeInFolder(vue.dossier, vue.tri)
                }
            }
            .map { Result.success(it) }
            .catch { erreur ->
                if (erreur is CancellationException) throw erreur
                Timber.e(erreur, "lecture de la liste de notes")
                emit(Result.failure(erreur))
            }

    private data class Vue(val texte: String, val dossier: String?, val archive: Boolean, val tri: NoteSortMode)

    private fun dossierCourant(): Flow<Folder?> = combine(folderId, folders.observeAll()) { id, tous ->
        // Le dossier est relu dans la liste observée, pas interrogé séparément : s'il est
        // supprimé ailleurs, il disparaît d'ici au même instant. Une lecture ponctuelle aurait
        // laissé un titre d'écran pointant un dossier qui n'existe plus.
        id?.let { cherche -> tous.firstOrNull { it.id == cherche } }
    }.distinctUntilChanged()

    private fun nomsDeDossiers(): Flow<Map<String, String>> =
        folders.observeAll().map { liste -> liste.associate { it.id to it.name } }.distinctUntilChanged()

    private companion object {
        /** `AppConstants.searchDebounce` — `core/constants.dart`. */
        const val FREINAGE_MILLIS = 250L
        const val ARRET_DIFFERE_MILLIS = 5_000L
        const val CLE_DOSSIER = "home.folderId"
        const val CLE_ARCHIVE = "home.archive"
        const val CLE_RECHERCHE = "home.query"
        const val CLE_SELECTION = "home.selection"
    }
}
