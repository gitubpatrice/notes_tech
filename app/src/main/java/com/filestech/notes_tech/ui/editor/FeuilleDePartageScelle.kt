package com.filestech.notes_tech.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.scellee.EnvoiDeNoteScellee
import com.filestech.notes_tech.data.scellee.NoteScellee
import com.filestech.notes_tech.security.clipboard.SensitiveClipboard
import com.filestech.notes_tech.ui.common.CodeAffiche
import com.filestech.notes_tech.ui.common.partagerUnFichier
import com.filestech.notes_tech.ui.secure.SecureWindowGuard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * "Share sealed…" of the editor's ⋮ menu (D-044, 3.4.0): the note, as it is on screen, sealed into a
 * file that only its code opens; the code shown once, the file shared by any app.
 *
 * 🔴 A secured window while open — the code is on it —, and the code forgotten when the sheet closes:
 * it lives in [PartageScelleViewModel] only.
 *
 * @param note the note to seal, with or without "read once" for its recipient.
 */
@Composable
internal fun FeuilleDePartageScelle(note: (lectureUnique: Boolean) -> NoteScellee, onFermer: () -> Unit) {
    val viewModel: PartageScelleViewModel = hiltViewModel()
    val etat by viewModel.etat.collectAsStateWithLifecycle()
    var lectureUnique by rememberSaveable { mutableStateOf(false) }
    val contexte = LocalContext.current
    val titreDuSelecteur = stringResource(R.string.common_share)
    val fermer = {
        viewModel.oublier()
        onFermer()
    }
    SecureWindowGuard(active = true)

    AlertDialog(
        // Not while sealing: the work would go on for a sheet already closed.
        onDismissRequest = { if (etat !is PartageScelleViewModel.Etat.EnCours) fermer() },
        title = { Text(stringResource(R.string.sealed_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val courant = etat) {
                    PartageScelleViewModel.Etat.Choix, PartageScelleViewModel.Etat.Echec -> {
                        Text(stringResource(R.string.sealed_explanation))
                        LectureUniqueChoisie(lectureUnique) { lectureUnique = it }
                        if (courant == PartageScelleViewModel.Etat.Echec) {
                            Text(stringResource(R.string.sealed_failed), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    PartageScelleViewModel.Etat.EnCours -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.sealed_sealing))
                    }
                    is PartageScelleViewModel.Etat.Pret -> {
                        Text(stringResource(R.string.sealed_code_label))
                        CodeAffiche(courant.envoi.code)
                        Text(
                            stringResource(R.string.sealed_code_once),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = viewModel::copierLeCode) {
                            Text(stringResource(R.string.sealed_copy_code))
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (val courant = etat) {
                PartageScelleViewModel.Etat.Choix, PartageScelleViewModel.Etat.Echec ->
                    TextButton(onClick = { viewModel.sceller(note(lectureUnique)) }) {
                        Text(stringResource(R.string.sealed_create))
                    }
                is PartageScelleViewModel.Etat.Pret -> TextButton(
                    onClick = {
                        partagerUnFichier(
                            contexte,
                            courant.envoi.uri,
                            TYPE_DU_FICHIER,
                            courant.envoi.nomDuFichier,
                            titreDuSelecteur,
                        )
                    },
                ) { Text(stringResource(R.string.sealed_share_file)) }
                PartageScelleViewModel.Etat.EnCours -> Unit
            }
        },
        dismissButton = {
            if (etat !is PartageScelleViewModel.Etat.EnCours) {
                TextButton(onClick = fermer) { Text(stringResource(R.string.common_close)) }
            }
        },
    )
}

@Composable
private fun LectureUniqueChoisie(choisie: Boolean, onChoisir: (Boolean) -> Unit) {
    // One node, toggled by the row — the settings' idiom.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(value = choisie, role = Role.Switch, onValueChange = onChoisir),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(stringResource(R.string.sealed_read_once), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.sealed_read_once_detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = choisie, onCheckedChange = null)
    }
}

/** A `.ntnote` has no type of its own: what the messaging apps take for any file. */
private const val TYPE_DU_FICHIER = "application/octet-stream"

@HiltViewModel
class PartageScelleViewModel @Inject constructor(
    private val envoi: EnvoiDeNoteScellee,
    private val pressePapiers: SensitiveClipboard,
) : ViewModel() {

    sealed interface Etat {
        data object Choix : Etat
        data object EnCours : Etat
        class Pret(val envoi: EnvoiDeNoteScellee.Envoi) : Etat
        data object Echec : Etat
    }

    private val _etat = MutableStateFlow<Etat>(Etat.Choix)
    val etat: StateFlow<Etat> = _etat.asStateFlow()

    fun sceller(note: NoteScellee) {
        if (_etat.value is Etat.EnCours) return
        _etat.value = Etat.EnCours
        viewModelScope.launch {
            _etat.value = try {
                Etat.Pret(envoi.preparer(note))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "note scellee non preparee")
                Etat.Echec
            }
        }
    }

    /** Through the sensitive clipboard: marked, cleared after a minute, purged by the panic. */
    fun copierLeCode() {
        val pret = _etat.value as? Etat.Pret ?: return
        viewModelScope.launch { pressePapiers.copier(pret.envoi.code) }
    }

    /** The sheet closes: the code goes with it. */
    fun oublier() {
        _etat.value = Etat.Choix
    }
}
