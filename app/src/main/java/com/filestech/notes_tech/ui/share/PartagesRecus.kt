package com.filestech.notes_tech.ui.share

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.local.entity.FolderEntity
import com.filestech.notes_tech.data.repository.NotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Turns a share waiting in [PartagesEnAttente] into notes of the Inbox, then opens the note — or, when
 * the share made several or could not all be read, says what happened.
 *
 * 🔴 **Composed beside the navigation, inside the app lock's content** (`MainActivity`): it exists only
 * while the app is unlocked, so a share is never written behind the lock screen. Never in a vault.
 */
@Composable
fun PartagesRecus(onOuvrirLaNote: (String) -> Unit) {
    val modele: PartagesRecusViewModel = hiltViewModel()
    val enAttente by modele.enAttente.collectAsState()
    val resultat by modele.resultat.collectAsState()

    LaunchedEffect(enAttente) {
        if (enAttente != null) modele.enregistrer()
    }

    when (val r = resultat) {
        null -> Unit
        is ResultatDUnPartage.UneNote -> LaunchedEffect(r) {
            onOuvrirLaNote(r.id)
            modele.vu()
        }
        is ResultatDUnPartage.Resume -> AlertDialog(
            onDismissRequest = modele::vu,
            title = { Text(stringResource(R.string.share_result_title)) },
            text = {
                Text(
                    buildList {
                        if (r.ajoutees >
                            0
                        ) {
                            add(pluralStringResource(R.plurals.share_result_added, r.ajoutees, r.ajoutees))
                        }
                        if (r.ignorees >
                            0
                        ) {
                            add(pluralStringResource(R.plurals.share_result_ignored, r.ignorees, r.ignorees))
                        }
                    }.joinToString("\n"),
                )
            },
            confirmButton = { TextButton(onClick = modele::vu) { Text(stringResource(R.string.common_ok)) } },
        )
        ResultatDUnPartage.Echec -> AlertDialog(
            onDismissRequest = modele::vu,
            title = { Text(stringResource(R.string.share_result_title)) },
            text = { Text(stringResource(R.string.share_result_failed)) },
            confirmButton = { TextButton(onClick = modele::vu) { Text(stringResource(R.string.common_ok)) } },
        )
    }
}

/** What a share became, for [PartagesRecus] to show once. */
sealed interface ResultatDUnPartage {
    data class UneNote(val id: String) : ResultatDUnPartage
    data class Resume(val ajoutees: Int, val ignorees: Int) : ResultatDUnPartage
    data object Echec : ResultatDUnPartage
}

@HiltViewModel
class PartagesRecusViewModel @Inject constructor(
    private val partages: PartagesEnAttente,
    private val notes: NotesRepository,
) : ViewModel() {

    val enAttente = partages.partage

    private val dernier = MutableStateFlow<ResultatDUnPartage?>(null)
    val resultat: StateFlow<ResultatDUnPartage?> = dernier.asStateFlow()

    fun enregistrer() {
        // Taken here, synchronously: a second call finds nothing, the share is written once.
        val recu = partages.prendre() ?: return
        viewModelScope.launch {
            dernier.value = try {
                val creees = if (recu.notes.isEmpty()) {
                    emptyList()
                } else {
                    notes.importer(
                        FolderEntity.INBOX_ID,
                        recu.notes,
                    )
                }
                if (creees.size == 1 && recu.ignores == 0) {
                    ResultatDUnPartage.UneNote(creees.single().id)
                } else {
                    ResultatDUnPartage.Resume(ajoutees = creees.size, ignorees = recu.ignores)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "partage non enregistre")
                ResultatDUnPartage.Echec
            }
        }
    }

    fun vu() {
        dernier.value = null
    }
}
