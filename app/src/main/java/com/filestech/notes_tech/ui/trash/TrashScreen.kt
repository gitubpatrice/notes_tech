package com.filestech.notes_tech.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.ui.common.ActionDeDialogue
import com.filestech.notes_tech.ui.common.CorpsDeDialogue
import com.filestech.notes_tech.ui.common.EmptyState
import com.filestech.notes_tech.ui.common.HoteDeMessages
import com.filestech.notes_tech.ui.home.NoteCard
import com.filestech.notes_tech.ui.theme.Formes
import kotlinx.coroutines.launch

/**
 * La corbeille, branchée : ViewModel et messages.
 *
 * Séparée de [TrashScreen], qui reste **sans état** — même découpage que `HomeRoute` / `HomeScreen`,
 * et pour la même raison : le chargement et la corbeille vide ne s'atteignent pas en pilotant
 * l'application à la main, alors que ce sont eux qui portaient un défaut. Cf. `04-PIEGES.md` §75.
 */
@Composable
fun TrashRoute(onBack: () -> Unit) {
    val viewModel: TrashViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val messages = remember { SnackbarHostState() }

    MessagesDeCorbeille(viewModel, messages)

    TrashScreen(
        state = state,
        onBack = onBack,
        onRestore = viewModel::restore,
        onDeletePermanently = viewModel::deletePermanently,
        onEmptyTrash = viewModel::emptyTrash,
        snackbarHost = { HoteDeMessages(messages) },
    )
}

/**
 * La corbeille.
 *
 * ⚠️ **Une note de coffre y reste chiffrée.** La corbeille ne déchiffre rien : la carte affiche
 * « Note verrouillée », comme partout ailleurs. Restaurer la remet dans son dossier, toujours
 * scellée.
 *
 * ⚠️ Les cartes ne sont **pas cliquables** ici — `onClick = null`, cf. la note de [NoteCard]. Une
 * note en corbeille ne s'ouvre pas, et une carte qui s'annonce activable sans rien faire est un
 * défaut, pas une commodité.
 */
@Composable
fun TrashScreen(
    state: TrashUiState,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    onDeletePermanently: (String) -> Unit,
    onEmptyTrash: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
) {
    // ⚠️ **`rememberSaveable`, et l'IDENTIFIANT plutôt que la note.** Un `remember` simple perd le
    // dialogue à la rotation : l'utilisateur lit « cette suppression est définitive », tourne son
    // téléphone, et la question a disparu sans réponse. La perte est du bon côté — rien n'est
    // détruit — mais elle abandonne en silence un geste engagé, ce que ce dépôt refuse ailleurs.
    //
    // `Note` n'est ni `Parcelable` ni `Saveable`, et n'a pas à le devenir : le dialogue n'a besoin que
    // de l'identifiant, son corps de texte ne porte aucun argument. Relevé par une relecture externe
    // (Gemini, 2026-08-17).
    var idASupprimer by rememberSaveable { mutableStateOf<String?>(null) }
    var vidangeADemander by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = snackbarHost,
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
                title = { Text(stringResource(R.string.trash_title)) },
                actions = {
                    // Rien à vider quand il n'y a rien : le bouton disparaît au lieu de proposer un
                    // geste sans effet, comme l'application publiée (`trash_screen.dart:147`).
                    //
                    // ⚠️ `!state.loading` en plus : sans lui le bouton **apparaissait après coup**,
                    // puisque la liste est vide avant la première réponse de la base. Une action
                    // destructrice qui surgit sous le doigt vaut mieux cachée le temps de savoir.
                    if (!state.loading && state.notes.isNotEmpty()) {
                        IconButton(onClick = { vidangeADemander = true }) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteSweep,
                                contentDescription = stringResource(R.string.trash_empty_all),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // La rétention est annoncée en permanence.
            //
            // ⚠️ **Écart assumé avec l'application publiée**, qui range cet avis à l'intérieur de la
            // branche « liste non vide » (`trash_screen.dart:238`) et ne le montre donc pas sur une
            // corbeille vide. Le garder visible dit « rien n'attend d'être détruit dans trente
            // jours », qui est justement ce qu'on vient vérifier. Écart d'affichage, sans effet sur
            // les données.
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.trash_retention_notice, TrashViewModel.RETENTION_DAYS),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }

            when {
                // 🔴 **Ce cas manquait, et il mentait.** Avant la première réponse de la base, la
                // liste est vide et l'écran annonçait « La corbeille est vide » — à quelqu'un dont
                // elle ne l'est pas. `trash_screen.dart` sépare les deux depuis toujours : son
                // `items` vaut `null` tant que la lecture n'a pas rendu.
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                state.notes.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.DeleteOutline,
                    title = stringResource(R.string.trash_empty_title),
                    subtitle = stringResource(R.string.trash_empty_subtitle),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.notes, key = { it.id }) { note ->
                        Column {
                            NoteCard(note = note, onClick = null)
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { onRestore(note.id) }, shape = Formes.bouton) {
                                    Icon(Icons.Outlined.RestoreFromTrash, contentDescription = null)
                                    Text(
                                        text = stringResource(R.string.common_restore),
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                                TextButton(onClick = { idASupprimer = note.id }, shape = Formes.bouton) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteForever,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                    Text(
                                        text = stringResource(R.string.trash_delete_forever),
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    idASupprimer?.let { id ->
        DialogueDestructif(
            titre = stringResource(R.string.trash_delete_forever_title),
            corps = stringResource(R.string.trash_delete_forever_body),
            libelleConfirmation = stringResource(R.string.trash_delete_forever),
            onConfirmer = {
                idASupprimer = null
                onDeletePermanently(id)
            },
            onAnnuler = { idASupprimer = null },
        )
    }

    if (vidangeADemander) {
        DialogueDestructif(
            titre = stringResource(R.string.trash_empty_all),
            corps = stringResource(R.string.trash_empty_all_confirm),
            libelleConfirmation = stringResource(R.string.trash_empty_all),
            onConfirmer = {
                vidangeADemander = false
                onEmptyTrash()
            },
            onAnnuler = { vidangeADemander = false },
        )
    }
}

/**
 * Dialogue de confirmation d'un geste irréversible.
 *
 * ⚠️ **La confirmation n'est PAS le bouton d'action principal.** L'application publiée avait mis la
 * suppression définitive en `FilledButton` face à un « Annuler » discret ; son propre commentaire
 * dit pourquoi c'était faux — *« le geste irréversible était celui qu'on tape par réflexe »*
 * (`trash_screen.dart:121`). Les deux boutons sont donc du même poids, seule la couleur d'erreur
 * distingue celui qui détruit.
 */
@Composable
private fun DialogueDestructif(
    titre: String,
    corps: String,
    libelleConfirmation: String,
    onConfirmer: () -> Unit,
    onAnnuler: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(titre) },
        text = { CorpsDeDialogue(corps) },
        confirmButton = {
            ActionDeDialogue(
                texte = libelleConfirmation,
                onClick = onConfirmer,
                couleur = MaterialTheme.colorScheme.error,
            )
        },
        dismissButton = {
            ActionDeDialogue(texte = stringResource(R.string.common_cancel), onClick = onAnnuler)
        },
    )
}

/**
 * Annonce ce que la corbeille vient de faire.
 *
 * ⚠️ **`portee.launch` autour de `showSnackbar`** : la fonction suspend jusqu'à la fermeture du
 * message, et l'appeler dans le `collect` bloquerait la réception des suivants — un enchaînement de
 * restaurations n'en annoncerait qu'une.
 *
 * Même parti pris que `MessagesDeDossier` dans `HomeRoute` : aucun porteur à consommer, chaque
 * valeur est affichée à sa réception.
 */
@Composable
private fun MessagesDeCorbeille(viewModel: TrashViewModel, messages: SnackbarHostState) {
    val portee = rememberCoroutineScope()
    val ressources = LocalResources.current
    LaunchedEffect(viewModel, ressources) {
        viewModel.eventFlow.collect { evenement ->
            val message = when (evenement) {
                is TrashEvent.Restored -> ressources.getString(R.string.trash_restored)
                is TrashEvent.DeletedForever -> ressources.getString(R.string.trash_deleted_forever)
                is TrashEvent.Emptied ->
                    ressources.getQuantityString(R.plurals.trash_emptied, evenement.count, evenement.count)

                // The sentence alone: `common_error_with` ("Error: …") left with notes_tech 2.0.9,
                // and what it wrapped was the exception's own text.
                is TrashEvent.Failed -> ressources.getString(evenement.message)
            }
            portee.launch { messages.showSnackbar(message) }
        }
    }
}
