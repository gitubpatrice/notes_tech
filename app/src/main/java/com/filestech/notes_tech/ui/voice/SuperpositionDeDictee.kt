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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
 * ## 🔴🔴 « Arrêter » et « Annuler » sont deux gestes, et les deux doivent être là
 *
 * Ils ne diffèrent pas par le mot mais par ce qu'ils font de la voix captée : **arrêter** transcrit
 * et insère, **annuler** jette. Pendant l'enregistrement, cette feuille ne proposait que le premier
 * — et son commentaire justifiait l'absence du second en les tenant pour « deux mots pour un même
 * geste ». C'était faux, et cela fermait la seule sortie qui n'écrit rien dans la note : un appui
 * involontaire sur le micro, une phrase dite à voix haute qu'on ne voulait pas garder, et il ne
 * restait qu'à laisser la dictée aller au bout puis effacer le texte inséré. `abandonner` existait,
 * traversait jusqu'au moteur natif, et **aucun bouton ne l'appelait** dans cet état.
 *
 * L'application publiée pose bien les deux côte à côte pendant l'enregistrement
 * (`voice_recording_overlay.dart`, `_RecordingBody`).
 *
 * ⚠️ Pendant l'**initialisation** et la **transcription**, il n'y a toujours qu'une action, et c'est
 * « Annuler » : il n'y a rien à arrêter en douceur, le micro n'est pas encore ouvert ou le calcul
 * est déjà lancé. Un libellé qui promet une action que le code ne peut pas rendre serait un jumeau
 * asymétrique de plus.
 *
 * ⚠️ **Une seule définition du bouton d'annulation**, posée tantôt dans l'emplacement de confirmation
 * tantôt dans celui de rejet. Deux définitions divergeraient au premier changement de libellé.
 *
 * ## ⚠️ Ce dialogue ne se referme pas par un geste extérieur
 *
 * `onDismissRequest` est vide : ni l'appui à côté ni le retour ne coupent la capture. Un appui à côté
 * pendant une dictée l'interromprait sans que rien ne le dise — et l'utilisateur, lui, continuerait
 * de parler. La sortie passe par un bouton, et depuis le correctif ci-dessus il y en a un dans
 * chaque état.
 *
 * ⚠️ **Écart assumé avec le publié**, qui lui annule sur le retour pendant l'enregistrement
 * (`PopScope`, `onPopInvokedWithResult`). Ici les deux gestes arrivent par le **même** rappel —
 * `onDismissRequest` ne dit pas lequel des deux l'a déclenché — et les séparer demanderait de
 * reprendre la fenêtre du dialogue. Le bouton d'annulation couvre le besoin sans cela.
 *
 * ## 🔴🔴 Sans région active, le changement d'étape n'est annoncé à personne
 *
 * Mesuré sur le S9 le 2026-08-18 : `régionsActives=[]` dans les **trois** états. Un `AlertDialog` est
 * annoncé **à son ouverture** ; le texte qui change ensuite dans ses emplacements ne l'est pas. Un
 * utilisateur non voyant entendait donc « Initialisation du micro… Veuillez patienter… » puis plus
 * rien — jamais le « Parlez » qui dit que le micro est ouvert, jamais le passage à la transcription.
 * *La dictée était inutilisable au lecteur d'écran, non pas faute de nom, mais faute de dire quand.*
 *
 * ⚠️ **Deux régions et non une** : le titre et la consigne vivent dans deux emplacements distincts
 * d'`AlertDialog`, et l'un sans l'autre ment. Le titre seul dirait « Dictée en cours » sans dire de
 * parler ; la consigne seule dirait « Veuillez patienter… » aussi bien pendant l'initialisation que
 * pendant la transcription, sans jamais nommer l'étape.
 *
 * ⚠️ **`Polite` et non `Assertive`** : les deux annonces se suivent au lieu de se couper. En
 * `Assertive`, la seconde interromprait la première et le titre serait perdu — l'inverse du but.
 * C'est le seul point où cette feuille diverge de la région du coffre (`04-PIEGES.md` §86), et pour
 * une raison mesurable : là-bas il y a **un** nœud à annoncer, ici il y en a deux.
 *
 * ⚠️ La consigne fusionne ses descendants, sans quoi la région porterait sur un conteneur **sans
 * texte propre** et n'annoncerait rien. Même construction qu'au §86.
 */
@Composable
fun SuperpositionDeDictee(etape: EtapeDeDictee, niveau: Float, onArreter: () -> Unit, onAbandonner: () -> Unit) {
    if (etape == EtapeDeDictee.INACTIVE) return

    val enregistre = etape == EtapeDeDictee.ENREGISTREMENT

    val annuler: @Composable () -> Unit = {
        ActionDeDialogue(texte = stringResource(R.string.common_cancel), onClick = onAbandonner)
    }

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
            Text(
                text = stringResource(titre),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
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
                    //
                    // ⚠️⚠️ Et c'est **ce masquage** qui rend la région active ci-dessus tenable : sans
                    // lui, la consigne fusionnerait un indicateur qui change en continu, et la région
                    // annoncerait à chaque échantillon.
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
            if (enregistre) {
                ActionDeDialogue(
                    texte = stringResource(R.string.voice_recording_stop),
                    onClick = onArreter,
                )
            } else {
                annuler()
            }
        },
        // 🔴 Pendant l'enregistrement **seulement** : ailleurs, « Annuler » est déjà la seule action,
        // et le poser ici en plus donnerait deux fois le même bouton.
        dismissButton = if (enregistre) annuler else null,
    )
}
