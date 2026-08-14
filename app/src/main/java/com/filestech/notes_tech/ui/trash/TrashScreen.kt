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
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.ui.common.EmptyState
import com.filestech.notes_tech.ui.home.NoteCard

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
    var aSupprimer by remember { mutableStateOf<Note?>(null) }

    Scaffold(
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
        AlertDialog(
            onDismissRequest = { aSupprimer = null },
            title = { Text(stringResource(R.string.trash_delete_forever_title)) },
            text = { Text(stringResource(R.string.trash_delete_forever_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        aSupprimer = null
                        viewModel.deletePermanently(note.id)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.trash_delete_forever),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { aSupprimer = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
