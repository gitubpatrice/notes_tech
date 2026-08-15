package com.filestech.notes_tech.ui.trash

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.ui.common.EmptyState
import com.filestech.notes_tech.ui.home.NoteCard
import kotlinx.coroutines.launch

/**
 * La corbeille.
 *
 * ⚠️ **Une note de coffre y reste chiffrée.** La corbeille ne déchiffre rien : la carte affiche
 * « Note verrouillée », comme partout ailleurs. Restaurer la remet dans son dossier, toujours
 * scellée.
 */
@Composable
fun TrashRoute(onBack: () -> Unit) {
    val viewModel: TrashViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val messages = remember { SnackbarHostState() }
    var aSupprimer by remember { mutableStateOf<Note?>(null) }
    var vidangeADemander by remember { mutableStateOf(false) }

    MessagesDeCorbeille(viewModel, messages)

    Scaffold(
        snackbarHost = { SnackbarHost(messages) },
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
                    if (state.notes.isNotEmpty()) {
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
            // La rétention est annoncée en permanence, pas seulement quand la corbeille est vide :
            // c'est l'information dont on a besoin au moment où on hésite à restaurer.
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.trash_retention_notice, TrashViewModel.RETENTION_DAYS),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }

            if (state.notes.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.DeleteOutline,
                    title = stringResource(R.string.trash_empty_title),
                    subtitle = stringResource(R.string.trash_empty_subtitle),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.notes, key = { it.id }) { note ->
                        Column {
                            NoteCard(note = note, onClick = { })
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { viewModel.restore(note.id) }) {
                                    Icon(Icons.Outlined.RestoreFromTrash, contentDescription = null)
                                    Text(
                                        text = stringResource(R.string.common_restore),
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                                TextButton(onClick = { aSupprimer = note }) {
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

    aSupprimer?.let { note ->
        DialogueDestructif(
            titre = stringResource(R.string.trash_delete_forever_title),
            corps = stringResource(R.string.trash_delete_forever_body),
            libelleConfirmation = stringResource(R.string.trash_delete_forever),
            onConfirmer = {
                aSupprimer = null
                viewModel.deletePermanently(note.id)
            },
            onAnnuler = { aSupprimer = null },
        )
    }

    if (vidangeADemander) {
        DialogueDestructif(
            titre = stringResource(R.string.trash_empty_all),
            corps = stringResource(R.string.trash_empty_all_confirm),
            libelleConfirmation = stringResource(R.string.trash_empty_all),
            onConfirmer = {
                vidangeADemander = false
                viewModel.emptyTrash()
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
        text = { Text(corps) },
        confirmButton = {
            TextButton(onClick = onConfirmer) {
                Text(text = libelleConfirmation, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnuler) { Text(stringResource(R.string.common_cancel)) }
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
    val erreurGenerique = stringResource(R.string.common_error)

    LaunchedEffect(viewModel, ressources) {
        viewModel.eventFlow.collect { evenement ->
            val message = when (evenement) {
                is TrashEvent.Restored -> ressources.getString(R.string.trash_restored)
                is TrashEvent.DeletedForever -> ressources.getString(R.string.trash_deleted_forever)
                is TrashEvent.Emptied ->
                    ressources.getQuantityString(R.plurals.trash_emptied, evenement.count, evenement.count)

                is TrashEvent.Failed ->
                    ressources.getString(R.string.common_error_with, evenement.message ?: erreurGenerique)
            }
            portee.launch { messages.showSnackbar(message) }
        }
    }
}
