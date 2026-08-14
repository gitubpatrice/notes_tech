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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<Note> = emptyList(),
    val folderNamesById: Map<String, String> = emptyMap(),
)

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
            if (texte.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else search.observe(texte)
        },
        folders.observeAll().map { liste -> liste.associate { it.id to it.name } },
    ) { texte, resultats, noms ->
        SearchUiState(query = texte, results = resultats, folderNamesById = noms)
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
