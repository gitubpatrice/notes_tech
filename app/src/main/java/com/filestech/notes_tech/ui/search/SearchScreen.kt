package com.filestech.notes_tech.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.ui.common.EmptyState
import com.filestech.notes_tech.ui.common.rememberFolderDisplayName
import com.filestech.notes_tech.ui.home.NoteCard

/**
 * La recherche, branchée : ViewModel et rien d'autre.
 *
 * Séparée de [SearchScreen], qui reste **sans état** — troisième écran à passer à ce découpage, après
 * l'accueil et la corbeille, et pour la même raison : les états qui portaient les défauts sont ceux
 * qu'on n'atteint pas en pilotant l'application. Ici, la fenêtre entre une frappe et la réponse de
 * l'index. Cf. `04-PIEGES.md` §76.
 */
@Composable
fun SearchRoute(onBack: () -> Unit, onOpenNote: (Note) -> Unit) {
    val viewModel: SearchViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    SearchScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onBack = onBack,
        onOpenNote = onOpenNote,
    )
}

/**
 * L'écran de recherche plein texte.
 *
 * ## 🔴 Ce qui n'y apparaît jamais
 *
 * Les notes verrouillées. La garantie est tenue **deux fois dans la requête** — par les
 * déclencheurs hérités qui n'indexent que du vide pour une note chiffrée, et par un
 * `encrypted_content IS NULL` explicite — et **pas ici**. Ajouter un filtre d'affichage donnerait
 * l'impression que la garantie vit dans l'interface, et le prochain écran qui lit l'index
 * l'oublierait.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onOpenNote: (Note) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }

    // Same translation of a default inbox name as the home screen's badges.
    val nomDuDossier = rememberFolderDisplayName()

    // Le clavier s'ouvre à l'arrivée : un écran de recherche qu'il faut toucher pour commencer à
    // chercher fait perdre un geste à chaque usage.
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_close),
                        )
                    }
                },
                title = { Text(stringResource(R.string.search_title)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.search_clear),
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .focusRequester(focus),
            )

            when {
                state.query.isBlank() -> EmptyState(
                    icon = Icons.Outlined.TravelExplore,
                    title = stringResource(R.string.search_empty_title),
                    subtitle = stringResource(R.string.search_empty_subtitle_fts),
                )

                // 🔴🔴 **Cette branche manquait, et son absence faisait mentir la suivante.**
                //
                // Tant que la réponse en main ne répond pas à la saisie courante, on ne sait rien —
                // et l'écran annonçait « Aucun résultat. Essayez un autre mot-clé », c'est-à-dire
                // qu'il accusait la saisie de l'utilisateur pour une requête encore en vol. Le
                // publié rend un indicateur (`search_screen.dart:109`) : c'était une régression.
                //
                // ⚠️ **`&& results.isEmpty()` fait partie de la condition, ce n'est pas une garde
                // superflue.** Sans lui, chaque frappe remplacerait la liste par un indicateur pendant
                // 250 ms : un clignotement à chaque lettre, là où le publié laisse les résultats de la
                // requête précédente en place le temps du freinage. L'indicateur ne paraît donc que
                // lorsqu'il n'y a **rien** à montrer — première recherche, ou requête vidée de ses
                // résultats.
                state.searching && state.results.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                // ⚠️ **Avant « aucun résultat »**, et l'ordre est tout : une recherche qui a
                // échoué rend elle aussi une liste vide. Sans cette branche, un échec de la base
                // s'annonçait « Aucun résultat. Essayez un autre mot-clé » — un message qui accuse
                // la saisie de l'utilisateur pour une panne qui ne lui doit rien.
                state.failed -> EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(R.string.search_error_generic),
                )

                // Ici `searching` est faux : la réponse en main est bien celle de la saisie
                // courante, donc « aucun résultat » est une affirmation, plus une supposition.
                state.results.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.SearchOff,
                    title = stringResource(R.string.search_empty),
                    subtitle = stringResource(R.string.search_try_other),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.results, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onClick = { onOpenNote(note) },
                            folderName = state.folderNamesById[note.folderId]?.let { nomDuDossier(note.folderId, it) },
                        )
                    }
                }
            }
        }
    }
}
