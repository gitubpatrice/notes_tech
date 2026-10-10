package com.filestech.notes_tech.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.model.NoteSortMode
import com.filestech.notes_tech.ui.common.EmptyState
import com.filestech.notes_tech.ui.common.displayName
import com.filestech.notes_tech.ui.common.libelleDeTri
import com.filestech.notes_tech.ui.common.rememberFolderDisplayName
import com.filestech.notes_tech.ui.theme.Formes

/**
 * L'écran d'accueil : la liste des notes, filtrée par dossier ou par recherche.
 *
 * Portage de `ui/screens/home_screen.dart`. Le composable est **sans état** — tout vient de
 * [HomeUiState] et repart par les fonctions passées en argument. C'est ce qui rend la liste
 * testable sans base, sans Hilt et sans appareil.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onQueryChange: (String) -> Unit,
    onSortSelected: (NoteSortMode) -> Unit,
    onOpenNote: (Note) -> Unit,
    /** The long press on a card: the note's actions (3.1.0). */
    onLongPressNote: (Note) -> Unit,
    onNewNote: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onDismissVaultLostBanner: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOuvert by remember { mutableStateOf(false) }
    var triOuvert by remember { mutableStateOf(false) }

    // Lu une fois : le bouton flottant s'en sert DEUX fois — comme nom accessible et comme libellé
    // visible. Deux appels à `stringResource` diraient la même chose, mais laisseraient croire que
    // les deux valeurs peuvent différer, alors que c'est justement ce qu'il ne faut pas.
    val nomDeLaNouvelleNote = stringResource(R.string.home_new_note)

    // The badge names come from a raw `id → stored name` map: a default inbox name is translated
    // here, at display time, in the language the user chose (notes_tech 2.0.9 parity).
    val nomDuDossier = rememberFolderDisplayName()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = Icons.Outlined.Sort,
                            contentDescription = stringResource(R.string.home_folders),
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Signature Files Tech : le damier n'accompagne QUE le nom de
                        // l'application. Quand un filtre est actif, le titre devient le nom du
                        // dossier ; y laisser le logo reviendrait à dire que ce dossier EST
                        // l'application. Même règle que la version Flutter publiée.
                        //
                        // ⚠️ `contentDescription = null` : l'image est **décorative**. Le texte
                        // juste à côté porte déjà le nom, et un lecteur d'écran qui annoncerait
                        // « logo Notes Tech, Notes Tech » le dirait deux fois.
                        if (state.currentFolder == null) {
                            Image(
                                painter = painterResource(R.drawable.logo_damier),
                                contentDescription = null,
                                modifier = Modifier
                                    // 22 dp et non 26 : la valeur de la version Flutter paraissait
                                    // trop grande une fois rendue par Compose, jugee sur appareil
                                    // le 2026-08-20. Ecart assume, et deliberement mesure a l'oeil :
                                    // aucun test ne dit si un logo est trop gros.
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            // Le titre porte le nom du dossier quand un filtre est actif — c'est
                            // le seul endroit où l'utilisateur voit ce qu'il regarde.
                            text = state.currentFolder?.displayName() ?: stringResource(R.string.app_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // ⚠️ `fill = false` : le texte prend la place qu'il lui faut sans
                            // pousser le logo hors de la barre sur un nom de dossier long.
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .semantics { heading() },
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { triOuvert = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Sort,
                            contentDescription = stringResource(R.string.home_sort_mode),
                        )
                    }
                    MenuDeTri(
                        ouvert = triOuvert,
                        actif = state.sort,
                        onDismiss = { triOuvert = false },
                        onSelect = {
                            triOuvert = false
                            onSortSelected(it)
                        },
                    )
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            imageVector = Icons.Outlined.TravelExplore,
                            contentDescription = stringResource(R.string.search_title),
                        )
                    }
                    IconButton(onClick = { menuOuvert = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            // 🔴 **Ce bouton ouvre un MENU, il ne va pas aux réglages.** Il portait
                            // `settings_title`, c'est-à-dire le nom d'**une** de ses deux entrées :
                            // un lecteur d'écran annonçait « Réglages, bouton », et l'activer
                            // donnait un menu. L'application publiée y met le `moreButtonTooltip`
                            // de la plateforme (`home_screen.dart:361`) ; Compose n'expose pas
                            // d'équivalent public, d'où cette chaîne. Cf. `04-PIEGES.md` §73.
                            contentDescription = stringResource(R.string.common_more_options),
                        )
                    }
                    DropdownMenu(expanded = menuOuvert, onDismissRequest = { menuOuvert = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_title)) },
                            leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                            onClick = {
                                menuOuvert = false
                                onOpenSettings()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.about_title)) },
                            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                            onClick = {
                                menuOuvert = false
                                onOpenAbout()
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // 🔴🔴 **Le nom accessible est posé sur le BOUTON, pas sur son texte ni sur son icône.**
            //
            // `ExtendedFloatingActionButton` de material3 1.4.0 enveloppe son emplacement `text`
            // dans un `clearAndSetSemantics` : le libellé est **dessiné** (261 × 60 px mesurés) et
            // **absent de l'arbre de sémantique fusionné**, celui que lit un lecteur d'écran.
            // Mesuré sur le S9 le 2026-08-17 : arbre fusionné **0 nœud** portant « Nouvelle note »,
            // arbre non fusionné **1**, sous un nœud `ClearAndSetSemantics = true`. Le bouton
            // s'annonçait donc « bouton », sans nom — là où l'application publiée porte un `label`
            // **et** un `tooltip` (`home_screen.dart:391-396`). C'est une régression de parité.
            //
            // ⚠️ Nommer l'**icône** marchait aussi, et c'était la première correction. Deux
            // relectures externes ont convergé pour la refuser : le libellé appartient à l'action,
            // pas au pictogramme. Une icône nommée devient une source de libellé **de plus** — donc
            // une annonce en double le jour où material3 cesse d'effacer le slot `text`, et un arrêt
            // de focus parasite si son `mergeDescendants` change. Ici la propriété est sur la racine
            // du composant : elle survit aux deux, et au mode réduit où le texte n'est plus composé.
            //
            // Cf. `AccueilTest`, qui exige **exactement un** « Nouvelle note » sur ce nœud — c'est
            // l'assertion qui verra la double annonce si elle apparaît. Et `04-PIEGES.md` §71.
            ExtendedFloatingActionButton(
                onClick = onNewNote,
                shape = Formes.bouton,
                modifier = Modifier.semantics { contentDescription = nomDeLaNouvelleNote },
                icon = { Icon(Icons.Outlined.EditNote, contentDescription = null) },
                text = { Text(nomDeLaNouvelleNote) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.vaultLostCount > 0) {
                BanniereBrouillonsPerdus(state.vaultLostCount, onDismissVaultLostBanner)
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.home_search_hint)) },
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
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
            )

            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                state.failed -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        text = stringResource(R.string.home_load_error),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(24.dp),
                    )
                }

                state.notes.isEmpty() -> ListeVide(state, onNewNote)

                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp,
                        top = 4.dp,
                        end = 12.dp,
                        // Assez de marge basse pour que la dernière note ne finisse pas sous le
                        // bouton flottant, où elle serait invisible et intouchable.
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // `key = { it.id }` stabilise l'identité des éléments entre recompositions.
                    // Sans clé, un changement de filtre réassocie les états par position, et les
                    // cartes se réutilisent pour la mauvaise note.
                    items(state.notes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onClick = { onOpenNote(note) },
                            onLongClick = { onLongPressNote(note) },
                            folderName = if (state.showFolderBadge) {
                                state.folderNamesById[note.folderId]?.let { nomDuDossier(note.folderId, it) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListeVide(state: HomeUiState, onNewNote: () -> Unit) {
    val enRecherche = state.query.isNotEmpty()
    EmptyState(
        icon = if (enRecherche) Icons.Outlined.SearchOff else Icons.Outlined.NoteAlt,
        title = when {
            enRecherche -> stringResource(R.string.search_empty)
            state.currentFolder != null -> stringResource(R.string.home_no_notes_in)
            else -> stringResource(R.string.home_no_notes)
        },
        subtitle = if (enRecherche) {
            stringResource(R.string.search_try_other)
        } else {
            stringResource(R.string.home_start_writing)
        },
        action = if (enRecherche) {
            null
        } else {
            {
                // Le bouton flottant existe déjà, mais il reste peu visible au premier lancement
                // et sur tablette. Un appel à l'action dans l'état vide est ce qui fait créer la
                // première note.
                TextButton(onClick = onNewNote, shape = Formes.bouton) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.home_new_note),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
    )
}

/**
 * La bannière des modifications perdues parce qu'un coffre s'est verrouillé pendant une sauvegarde.
 *
 * ⚠️ C'est le seul signalement d'une **perte de données silencieuse** : l'utilisateur a tapé,
 * l'écran s'est comporté normalement, et le texte n'existe nulle part. Sans cette bannière, il ne
 * l'apprendrait qu'en rouvrant la note.
 */
@Composable
private fun BanniereBrouillonsPerdus(count: Int, onDismiss: () -> Unit) {
    val couleurs = MaterialTheme.colorScheme
    Surface(color = couleurs.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = couleurs.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = pluralStringResource(R.plurals.home_vault_lost_banner, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = couleurs.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss, shape = Formes.bouton) {
                Text(text = stringResource(R.string.common_ok), color = couleurs.onErrorContainer)
            }
        }
    }
}

@Composable
private fun MenuDeTri(ouvert: Boolean, actif: NoteSortMode, onDismiss: () -> Unit, onSelect: (NoteSortMode) -> Unit) {
    DropdownMenu(expanded = ouvert, onDismissRequest = onDismiss) {
        for (mode in NoteSortMode.entries) {
            DropdownMenuItem(
                text = { Text(stringResource(libelleDeTri(mode))) },
                leadingIcon = {
                    RadioButton(selected = mode == actif, onClick = null)
                },
                onClick = { onSelect(mode) },
            )
        }
    }
}

/**
 * Le libellé d'un mode de tri.
 *
 * ⚠️ `when` **exhaustif** et non une table : ajouter un mode sans lui donner de libellé doit échouer
 * à la compilation. Une table aurait rendu un menu avec une entrée vide.
 *
 * ## ✅ Six modes, six libellés DISTINCTS — corrigé des deux côtés
 *
 * L'application publiée fait correspondre `createdDesc` au libellé de `updatedDesc` et `createdAsc`
 * à celui de `updatedAsc` : son menu affiche « Plus récent d'abord » **deux fois** et « Plus ancien
 * d'abord » deux fois, sur six lignes. Rien n'y distingue un tri par date de modification d'un tri
 * par date de création, et la position du bouton radio est le seul indice de ce qu'on a choisi.
 *
 * Ce portage l'avait **reproduit**, la parité étant le critère de sortie de la phase 8. Patrice a
 * tranché le 2026-08-14 : corriger. Les deux clés existantes ont changé de **valeur** plutôt que
 * d'être doublées par deux nouvelles — « Plus récent d'abord » à côté de « Créée — plus récente
 * d'abord » aurait laissé deviner que la première parle de modification.
 *
 * ⚠️ Corrigé **aussi dans `notes_tech`** (commit `24bc67e`), sans quoi les deux versions
 * divergeraient à la comparaison de la phase 8. Cf. `docs/05-PARITE.md`.
 */
