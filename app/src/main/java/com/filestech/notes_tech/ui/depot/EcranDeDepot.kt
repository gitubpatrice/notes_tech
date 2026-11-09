package com.filestech.notes_tech.ui.depot

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.ui.common.DialogueDestructif

/**
 * The blind drop's screen (D-042, 3.4.0): a title, a text, "Drop". Nothing of what the app holds — the
 * screen has no way to it ([DepotViewModel]).
 *
 * Closing with something typed asks first: nothing is kept anywhere once the screen goes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EcranDeDepot(
    etat: EtatDuDepot,
    onTitre: (String) -> Unit,
    onTexte: (String) -> Unit,
    onDeposer: () -> Unit,
    onFermer: () -> Unit,
) {
    var abandonDemande by remember { mutableStateOf(false) }
    val aDuTexte = etat.titre.isNotBlank() || etat.texte.isNotBlank()
    val fermer = { if (aDuTexte) abandonDemande = true else onFermer() }
    BackHandler(onBack = fermer)

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = fermer) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel))
                    }
                },
                title = { Text(stringResource(R.string.depot_title)) },
            )
        },
    ) { marges ->
        Column(
            modifier = Modifier.fillMaxSize().padding(marges).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.depot_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = etat.titre,
                onValueChange = onTitre,
                label = { Text(stringResource(R.string.note_editor_title)) },
                singleLine = true,
                enabled = !etat.enCours,
                modifier = Modifier.fillMaxWidth().testTag(TAG_TITRE),
            )
            OutlinedTextField(
                value = etat.texte,
                onValueChange = onTexte,
                label = { Text(stringResource(R.string.depot_text)) },
                enabled = !etat.enCours,
                modifier = Modifier.fillMaxWidth().weight(1f).focusRequester(focus).testTag(TAG_TEXTE),
            )
            if (etat.echec) {
                Text(
                    text = stringResource(R.string.depot_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Button(onClick = onDeposer, enabled = etat.peutDeposer, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.depot_action))
            }
        }
    }

    if (abandonDemande) {
        DialogueDestructif(
            titre = stringResource(R.string.depot_discard_title),
            corps = stringResource(R.string.depot_discard_body),
            libelleConfirmation = stringResource(R.string.depot_discard_confirm),
            onConfirmer = {
                abandonDemande = false
                onFermer()
            },
            onAnnuler = { abandonDemande = false },
        )
    }
}

internal const val TAG_TITRE = "depot_titre"
internal const val TAG_TEXTE = "depot_texte"
