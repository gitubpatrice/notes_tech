package com.filestech.notes_tech.ui.panic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.security.panic.PanicReport
import com.filestech.notes_tech.ui.secure.SecureWindowGuard
import java.util.Locale

/**
 * Le dialogue qui précède la destruction.
 *
 * ## 🔴 Il faut **taper** le mot, et c'est le garde-fou entier
 *
 * Ni appui long, ni glissement, ni double confirmation : l'utilisateur écrit littéralement le mot
 * affiché. Deux raisons, toutes deux dans la version publiée :
 *
 * - **Sous contrainte physique**, quelqu'un qui saisirait l'appareil pour déclencher la panique à la
 *   place de son propriétaire devrait connaître le mot exact.
 * - **Contre l'accident**, un appui distrait dans les réglages détruirait toutes les notes sans
 *   sauvegarde ni corbeille. Écrire un mot ne se fait pas par inadvertance.
 *
 * ## ⚠️ La garde sur le mot vide n'est pas défensive, elle est nécessaire
 *
 * Sans elle, une ressource manquante rendrait le mot-clé vide, la comparaison vraie sur un champ
 * **vide**, et le bouton de destruction irréversible s'activerait tout seul. Relevé par une
 * relecture externe sur la version publiée ; le portage naît avec la garde.
 *
 * La comparaison ignore la casse : la mise en majuscules automatique ne s'applique ni aux claviers
 * physiques ni à certaines méthodes de saisie, et quelqu'un sous stress tape sans majuscule. Le
 * geste reste délibéré — il a fallu écrire le mot.
 */
@Composable
fun PanicConfirmDialog(onDismiss: () -> Unit, onConfirmed: () -> Unit) {
    SecureWindowGuard()

    val motCle = stringResource(R.string.panic_confirm_keyword).trim()
    var saisi by remember { mutableStateOf("") }
    val peutConfirmer = motCle.isNotEmpty() &&
        saisi.trim().uppercase(Locale.ROOT) == motCle.uppercase(Locale.ROOT)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.panic_confirm_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.panic_confirm_destroy_intro))
                Spacer(Modifier.height(12.dp))
                // ⚠️ `panic_confirm_item_2` — « le modèle de dictée vocale » — n'est PAS affiché.
                // La dictée arrive en phase 7 ; annoncer la destruction de ce qui n'existe pas est
                // le même mensonge qu'une étape déclarée et jamais exécutée. Cf. docs/05-PARITE.md.
                Puce(stringResource(R.string.panic_confirm_item_1))
                Puce(stringResource(R.string.panic_confirm_item_3))
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.panic_confirm_irreversible),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.panic_confirm_type_prompt, motCle))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = saisi,
                    onValueChange = { saisi = it },
                    label = { Text(stringResource(R.string.panic_confirm_field_label)) },
                    placeholder = { Text(motCle) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
        confirmButton = {
            Button(
                onClick = onConfirmed,
                enabled = peutConfirmer,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.panic_confirm_yes))
            }
        },
    )
}

/**
 * L'écran qui couvre tout pendant et après la destruction.
 *
 * ⚠️ Il n'a **aucune sortie** tant que la séquence tourne : ni bouton retour, ni fermeture. Ce n'est
 * pas une commodité d'interface — quitter en cours de route ne rendrait pas les notes, la clé est
 * déjà détruite, et laisserait l'utilisateur croire qu'il a annulé quelque chose.
 */
@Composable
fun PanicOverlay(running: Boolean, report: PanicReport?, onClose: () -> Unit) {
    SecureWindowGuard()

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (running || report == null) {
                CircularProgressIndicator()
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.panic_progress),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.panic_progress_subtitle))
                return@Column
            }

            // ⚠️ `liveRegion` : la fin de la séquence doit être **annoncée** au lecteur d'écran.
            // Sans cela, quelqu'un qui n'a pas les yeux sur l'appareil ne sait pas si la
            // destruction est terminée — au moment précis où cette information compte le plus.
            Text(
                text = stringResource(R.string.panic_complete_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Spacer(Modifier.height(16.dp))

            if (report.isComplete) {
                Text(stringResource(R.string.panic_complete_body))
                Spacer(Modifier.height(16.dp))
                // ⚠️ `panic_complete_bullet_3` — « modèle de dictée vocale : désinstallé » — n'est
                // PAS affiché, pour la même raison que l'item 2 du dialogue.
                Puce(stringResource(R.string.panic_complete_bullet_1))
                Puce(stringResource(R.string.panic_complete_bullet_2))
                Puce(stringResource(R.string.panic_complete_bullet_4))
            } else {
                // 🔴 Le message d'échec est le seul de l'application qu'on ne doit jamais adoucir :
                // quelqu'un est peut-être sur le point de se séparer de son appareil.
                Text(
                    text = stringResource(R.string.panic_incomplete, report.failedSteps.size),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.panic_complete_footer),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onClose) { Text(stringResource(R.string.panic_complete_close)) }
        }
    }
}

@Composable
private fun Puce(texte: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        // La puce est décorative : la lire ferait entendre « point » avant chaque ligne.
        Text("•", modifier = Modifier.clearAndSetSemantics { })
        Spacer(Modifier.fillMaxWidth(0f))
        Text(text = texte, modifier = Modifier.padding(start = 8.dp))
    }
}
