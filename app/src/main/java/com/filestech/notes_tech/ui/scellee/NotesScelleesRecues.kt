package com.filestech.notes_tech.ui.scellee

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.data.scellee.FichierDeNoteScellee
import com.filestech.notes_tech.data.scellee.FichierDeNoteScellee.NoteScelleeRefuseeException
import com.filestech.notes_tech.data.scellee.FichierDeNoteScellee.Raison
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.NoteColor
import com.filestech.notes_tech.ui.secure.ProprietesDeDialogueSecret
import com.filestech.notes_tech.ui.secure.SecureWindowGuard
import com.filestech.notes_tech.ui.vault.ChampDePhraseSecrete
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * A sealed note received, opened once the app is unlocked (D-044, 3.4.0): the code is asked; the right
 * one makes it a note of the Inbox — never a vault —, its colour and tags kept, "after reading" if its
 * sender asked, and the editor opens on it.
 *
 * 🔴 Composed only inside the app lock's content, not while the panic runs — `PartagesRecus`'s place.
 * A secured window while the code is typed.
 */
@Composable
fun NotesScelleesRecues(onOuvrirLaNote: (String) -> Unit) {
    val viewModel: NotesScelleesViewModel = hiltViewModel()
    val deposee by viewModel.deposee.collectAsStateWithLifecycle()
    val etat by viewModel.etat.collectAsStateWithLifecycle()
    val ouvrir by rememberUpdatedState(onOuvrirLaNote)

    LaunchedEffect(viewModel) { viewModel.aOuvrir.collect { ouvrir(it) } }
    LaunchedEffect(deposee) { if (deposee != null) viewModel.prendre() }
    (etat as? NotesScelleesViewModel.Etat.Code)?.let { code ->
        DialogueDuCode(code, onOuvrir = viewModel::ouvrir, onAnnuler = viewModel::abandonner)
    }
}

@Composable
private fun DialogueDuCode(etat: NotesScelleesViewModel.Etat.Code, onOuvrir: (String) -> Unit, onAnnuler: () -> Unit) {
    // `remember`, never `rememberSaveable`: a code has nothing to do in a Bundle.
    var saisie by remember { mutableStateOf("") }
    SecureWindowGuard(active = true)
    AlertDialog(
        onDismissRequest = { if (!etat.enCours) onAnnuler() },
        properties = ProprietesDeDialogueSecret,
        title = { Text(stringResource(R.string.sealed_received_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.sealed_received_body))
                ChampDePhraseSecrete(
                    valeur = saisie,
                    onValeurChange = { saisie = it },
                    label = stringResource(R.string.sealed_code_field),
                    actionClavier = ImeAction.Done,
                    actif = !etat.enCours,
                )
                etat.erreur?.let { Text(stringResource(it.message()), color = MaterialTheme.colorScheme.error) }
                if (etat.enCours) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.sealed_opening))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onOuvrir(saisie) }, enabled = !etat.enCours && saisie.isNotBlank()) {
                Text(stringResource(R.string.sealed_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnuler, enabled = !etat.enCours) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@StringRes
internal fun Raison.message(): Int = when (this) {
    Raison.CODE_MAL_FORME -> R.string.sealed_error_malformed
    Raison.CODE_OU_FICHIER -> R.string.sealed_error_code
    Raison.VERSION_INCONNUE -> R.string.sealed_error_version
    Raison.TROP_GROSSE -> R.string.sealed_too_big
    Raison.PAS_UNE_NOTE_SCELLEE -> R.string.sealed_not_a_sealed_note
    Raison.ILLISIBLE -> R.string.sealed_error_unreadable
}

@HiltViewModel
class NotesScelleesViewModel @Inject constructor(
    private val enAttente: NotesScelleesEnAttente,
    private val notes: NotesRepository,
) : ViewModel() {

    sealed interface Etat {
        data object Aucune : Etat
        data class Code(val erreur: Raison? = null, val enCours: Boolean = false) : Etat
    }

    /** What "Open with" or the picker deposited, for [NotesScelleesRecues] to take. */
    val deposee = enAttente.contenu

    private var octets: ByteArray? = null
    private val _etat = MutableStateFlow<Etat>(Etat.Aucune)
    val etat: StateFlow<Etat> = _etat.asStateFlow()

    // A channel, not a SharedFlow: an editor to open is not lost when nothing collects yet.
    private val ouvertures = Channel<String>(Channel.BUFFERED)
    val aOuvrir: Flow<String> = ouvertures.receiveAsFlow()

    fun prendre() {
        // 🔴 Not while a file is being opened (3-axes audit of 3.4.0): replaced now, the bytes under
        // decryption would be zeroed, or the second file erased by the first one's end. It waits in
        // [enAttente], taken once the first is done.
        if (enCours()) return
        val pris = enAttente.prendre() ?: return
        octets?.fill(0)
        octets = pris
        _etat.value = Etat.Code()
    }

    fun ouvrir(saisie: String) {
        val fichier = octets ?: return
        if (enCours()) return
        _etat.value = Etat.Code(enCours = true)
        viewModelScope.launch {
            try {
                val note = withContext(Dispatchers.Default) { FichierDeNoteScellee.ouvrir(fichier, saisie) }
                // One INSERT: the note never exists without its "after reading" (external review, 3.4.0).
                val creee = notes.create(
                    folderId = Folder.INBOX_ID,
                    title = note.titre,
                    content = note.contenu,
                    tags = note.etiquettes,
                    color = NoteColor.fromId(note.couleur),
                    readOnce = note.lectureUnique,
                )
                abandonner()
                ouvertures.send(creee.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoteScelleeRefuseeException) {
                _etat.value = Etat.Code(erreur = e.raison)
            } catch (e: Exception) {
                Timber.w(e, "note scellee non ajoutee")
                _etat.value = Etat.Code(erreur = Raison.ILLISIBLE)
            }
            // A file deposited meanwhile — see [prendre].
            if (enAttente.contenu.value != null) prendre()
        }
    }

    private fun enCours() = (_etat.value as? Etat.Code)?.enCours == true

    fun abandonner() {
        octets?.fill(0)
        octets = null
        _etat.value = Etat.Aucune
    }

    override fun onCleared() {
        octets?.fill(0)
        super.onCleared()
    }
}
