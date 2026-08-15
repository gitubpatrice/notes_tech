package com.filestech.notes_tech.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.repository.FoldersRepository
import com.filestech.notes_tech.data.repository.SearchRepository
import com.filestech.notes_tech.domain.model.Note
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<Note> = emptyList(),
    val folderNamesById: Map<String, String> = emptyMap(),
    /**
     * 🔴 **La recherche a échoué**, et il fallait bien que quelque chose puisse le dire.
     *
     * Le flux n'avait aucun filet : une erreur de la base remontait jusqu'à `stateIn`, qui la
     * relance depuis sa propre coroutine — donc **jusqu'au gestionnaire d'exceptions non
     * capturées**. L'écran ne se figeait pas, il emportait l'application.
     *
     * ⚠️ Ce n'est pas la syntaxe FTS5 qui menace : `FtsMatchExpression.from` la rend impossible,
     * et c'est pour ça que cette chaîne n'était lue nulle part alors que l'application publiée s'en
     * sert. Ce qui reste, c'est l'index abîmé, le disque plein, la base fermée sous les pieds.
     */
    val failed: Boolean = false,
)

/** Ce qu'une requête a donné : des résultats, ou un échec. Jamais les deux. */
private data class Issue(val resultats: List<Note> = emptyList(), val echec: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val search: SearchRepository,
    folders: FoldersRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val query = MutableStateFlow(savedState.get<String>(CLE_RECHERCHE).orEmpty())

    val state: StateFlow<SearchUiState> = combine(
        query,
        query.debounce(FREINAGE_MILLIS).distinctUntilChanged().flatMapLatest { texte ->
            // Une saisie vide ne déclenche aucune requête : l'écran affiche son état d'accueil.
            if (texte.isBlank()) {
                flowOf(Issue())
            } else {
                // 🔴 **Le `catch` est À L'INTÉRIEUR du `flatMapLatest`, et c'est tout l'enjeu.**
                //
                // `catch` est **terminal** : posé sur le flux extérieur, il émettait l'état d'échec
                // puis **terminait le flux d'état**. Le `combine` mourait avec lui, et plus aucune
                // frappe n'était jamais servie — la recherche restait figée sur son erreur jusqu'à
                // la destruction du ViewModel. Le filet censé protéger d'une panne passagère la
                // rendait définitive. Relevé CONFIRMÉ par une relecture externe (Gemini, 2026-08-15).
                //
                // Ici il ne couvre que le flux d'UNE requête : la suivante repart d'un flux neuf.
                search.observe(texte)
                    .map { Issue(resultats = it) }
                    .catch { erreur ->
                        Timber.e(erreur, "recherche plein texte")
                        emit(Issue(echec = true))
                    }
            }
        },
        folders.observeAll().map { liste -> liste.associate { it.id to it.name } },
    ) { texte, issue, noms ->
        // ⚠️ La requête reste affichée en cas d'échec : l'utilisateur doit voir ce qu'il a tapé pour
        // comprendre à quoi se rapporte le message. Les résultats, eux, sont vides — en garder
        // d'anciens à côté d'une erreur laisserait croire qu'ils correspondent à la saisie courante.
        SearchUiState(query = texte, results = issue.resultats, folderNamesById = noms, failed = issue.echec)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(ARRET_DIFFERE_MILLIS),
        initialValue = SearchUiState(query = query.value),
    )

    fun onQueryChange(value: String) {
        query.value = value
        savedState[CLE_RECHERCHE] = value
    }

    private companion object {
        const val FREINAGE_MILLIS = 250L
        const val ARRET_DIFFERE_MILLIS = 5_000L
        const val CLE_RECHERCHE = "search.query"
    }
}
