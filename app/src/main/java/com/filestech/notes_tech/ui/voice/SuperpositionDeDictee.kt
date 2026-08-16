package com.filestech.notes_tech.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.ui.common.ActionDeDialogue

/**
 * Ce que l'utilisateur voit pendant qu'il dicte.
 *
 * ## 🔴 Le niveau sonore n'est pas une décoration
 *
 * C'est la seule chose qui distingue « le micro vous entend » de « le micro est coupé et vous
 * parlez pour rien ». Sans lui, l'utilisateur ne l'apprend qu'à la transcription vide — après avoir
 * dicté un paragraphe. `WavPcm16.niveau` le dit déjà : *ce n'est en aucun cas une détection de
 * parole*, seulement un témoin.
 *
 * ## ⚠️ Deux étapes, deux dialogues, et une seule action possible à la fois
 *
 * Pendant l'**enregistrement**, l'action est « Arrêter » — elle termine la capture et enchaîne sur
 * la transcription. Pendant la **transcription**, la seule action est « Annuler », qui abandonne :
 * il n'y a rien à arrêter en douceur, le calcul est déjà lancé. Proposer les deux au même moment
 * ferait choisir entre deux mots pour un même geste.
 *
 * ⚠️ Aucun `dismissButton` : ce dialogue ne se referme pas par un geste extérieur. Un appui à côté
 * pendant une dictée couperait l'enregistrement sans que rien ne le dise — et l'utilisateur, lui,
 * continuerait de parler.
 */
@Composable
fun SuperpositionDeDictee(etape: EtapeDeDictee, niveau: Float, onArreter: () -> Unit, onAbandonner: () -> Unit) {
    if (etape == EtapeDeDictee.INACTIVE) return

    val enregistre = etape == EtapeDeDictee.ENREGISTREMENT

    AlertDialog(
        // ⚠️ Vide, et non `onAbandonner` : voir la note de classe. La sortie passe par un bouton.
        onDismissRequest = {},
        title = {
            val titre = when (etape) {
                EtapeDeDictee.INITIALISATION -> R.string.voice_mic_initializing
                EtapeDeDictee.ENREGISTREMENT -> R.string.voice_recording_title
                // 🔴 Exhaustif sur l'énumération : une étape ajoutée sans titre fera échouer la
                // compilation, plutôt qu'afficher un dialogue muet pendant une dictée.
                EtapeDeDictee.TRANSCRIPTION, EtapeDeDictee.INACTIVE -> R.string.voice_transcribing
            }
            Text(stringResource(titre))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ⚠️ « Parlez » n'apparaît QU'UNE FOIS le micro ouvert : le dire pendant
                // l'initialisation ferait commencer l'utilisateur trop tôt, et son premier mot
                // n'arriverait jamais dans le fichier.
                //
                // ⚠️⚠️ Un `when` **exhaustif**, et non un `if/else` : l'initialisation tombait dans
                // la branche de la transcription par accident, pas par décision. Le texte affiché
                // se trouvait acceptable — « veuillez patienter » convient aux deux — mais c'était
                // une coïncidence, que le premier changement de libellé aurait défaite. Relevé par
                // une relecture externe (GPT-5.5, 2026-08-16).
                val consigne = when (etape) {
                    EtapeDeDictee.INITIALISATION -> R.string.voice_transcribing_hint
                    EtapeDeDictee.ENREGISTREMENT -> R.string.voice_recording_hint
                    EtapeDeDictee.TRANSCRIPTION, EtapeDeDictee.INACTIVE -> R.string.voice_transcribing_hint
                }
                Text(stringResource(consigne), style = MaterialTheme.typography.bodyMedium)
                if (enregistre) {
                    // ⚠️ Masqué aux lecteurs d'écran : un témoin qui change dix fois par seconde
                    // serait annoncé dix fois par seconde. Le texte au-dessus porte l'information
                    // utile, et un utilisateur non voyant a d'autres retours que celui-ci.
                    LinearProgressIndicator(
                        progress = { niveau },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clearAndSetSemantics {},
                    )
                }
            }
        },
        confirmButton = {
            // ⚠️ Pendant l'initialisation, « Arrêter » n'a rien à arrêter : le bouton propose donc
            // d'abandonner, comme pendant la transcription. Un libellé qui promet une action que le
            // code ne peut pas encore rendre est un jumeau asymétrique de plus.
            if (enregistre) {
                ActionDeDialogue(
                    texte = stringResource(R.string.voice_recording_stop),
                    onClick = onArreter,
                )
            } else {
                ActionDeDialogue(
                    texte = stringResource(R.string.common_cancel),
                    onClick = onAbandonner,
                )
            }
        },
    )
}
