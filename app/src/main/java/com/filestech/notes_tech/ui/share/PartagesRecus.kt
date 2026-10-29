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
import com.filestech.notes_tech.domain.importation.PartageRecu
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * A share waiting in [PartagesEnAttente]: the user is asked, then it becomes notes of the Inbox, and
 * the note opens — or, when it made several or could not all be read, a summary says what happened.
 *
 * 🔴 **Composed beside the navigation, inside the app lock's content** (`MainActivity`), and not while
 * the panic mode runs: it exists only while the app is unlocked, so a share is never written behind
 * the lock screen. 🔴 **Nothing is written before the user says "Add"** (security review of 3.2.0, L3):
 * any app can start the share activity by name, without the user ever choosing Notes Tech in a share
 * sheet. Never in a vault.
 */
@Composable
fun PartagesRecus(onOuvrirLaNote: (String) -> Unit) {
    val modele: PartagesRecusViewModel = hiltViewModel()
    val enAttente by modele.enAttente.collectAsState()
    val resultat by modele.resultat.collectAsState()

    val attente = enAttente
    val fait = resultat
    when {
        fait != null -> Resultat(fait, onOuvrirLaNote, modele::vu)
        attente != null && attente.notes.isEmpty() -> LaunchedEffect(attente) { modele.enregistrer() }
        attente != null -> Confirmation(attente, onAjouter = modele::enregistrer, onAnnuler = modele::abandonner)
    }
}

@Composable
private fun Confirmation(recu: PartageRecu, onAjouter: () -> Unit, onAnnuler: () -> Unit) {
    val titreDeLaNote = recu.notes.singleOrNull()?.titre
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(stringResource(R.string.share_confirm_title)) },
        text = {
            Text(
                buildList {
                    add(
                        if (titreDeLaNote != null) {
                            stringResource(
                                R.string.share_confirm_one,
                                titreDeLaNote.ifBlank { stringResource(R.string.note_untitled) },
                            )
                        } else {
                            pluralStringResource(R.plurals.share_confirm_many, recu.notes.size, recu.notes.size)
                        },
                    )
                    if (recu.ignores > 0) {
                        add(pluralStringResource(R.plurals.share_result_ignored, recu.ignores, recu.ignores))
                    }
                }.joinToString("\n"),
            )
        },
        confirmButton = { TextButton(onClick = onAjouter) { Text(stringResource(R.string.share_confirm_add)) } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun Resultat(resultat: ResultatDUnPartage, onOuvrirLaNote: (String) -> Unit, onVu: () -> Unit) {
    when (resultat) {
        is ResultatDUnPartage.UneNote -> LaunchedEffect(resultat) {
            onOuvrirLaNote(resultat.id)
            onVu()
        }
        is ResultatDUnPartage.Resume -> AlertDialog(
            onDismissRequest = onVu,
            title = { Text(stringResource(R.string.share_result_title)) },
            text = {
                Text(
                    buildList {
                        if (resultat.ajoutees > 0) {
                            add(
                                pluralStringResource(
                                    R.plurals.share_result_added,
                                    resultat.ajoutees,
                                    resultat.ajoutees,
                                ),
                            )
                        }
                        if (resultat.ignorees > 0) {
                            add(
                                pluralStringResource(
                                    R.plurals.share_result_ignored,
                                    resultat.ignorees,
                                    resultat.ignorees,
                                ),
                            )
                        }
                    }.joinToString("\n"),
                )
            },
            confirmButton = { TextButton(onClick = onVu) { Text(stringResource(R.string.common_ok)) } },
        )
        ResultatDUnPartage.Echec -> AlertDialog(
            onDismissRequest = onVu,
            title = { Text(stringResource(R.string.share_result_title)) },
            text = { Text(stringResource(R.string.share_result_failed)) },
            confirmButton = { TextButton(onClick = onVu) { Text(stringResource(R.string.common_ok)) } },
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

    /** "Add": the share is taken here, synchronously — a second tap finds nothing, it is written once. */
    fun enregistrer() {
        // Expired (PartagesEnAttente.DUREE_DE_VIE_MS): taken and dropped, nothing to say.
        val recu = partages.prendre() ?: return
        viewModelScope.launch {
            dernier.value = try {
                val creees = if (recu.notes.isEmpty()) {
                    emptyList()
                } else {
                    notes.importer(FolderEntity.INBOX_ID, recu.notes)
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

    fun abandonner() = partages.abandonner()

    fun vu() {
        dernier.value = null
    }
}
