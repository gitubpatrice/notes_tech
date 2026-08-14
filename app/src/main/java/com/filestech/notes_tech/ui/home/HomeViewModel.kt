package com.filestech.notes_tech.ui.home

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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val notes: NotesRepository,
    private val folders: FoldersRepository,
    private val search: SearchRepository,
    private val settings: AppSettings,
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
