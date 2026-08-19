package com.filestech.notes_tech.ui.panic

import androidx.activity.compose.BackHandler
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
import com.filestech.notes_tech.ui.common.ActionDeDialogue
import com.filestech.notes_tech.ui.secure.SecureWindowGuard
import com.filestech.notes_tech.ui.theme.Formes
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
                // ⚠️⚠️ **Le deuxième item a été tu pendant trois jours, et c'est un écran de
                // CONSENTEMENT.** Il annonce la destruction du modèle de dictée. Il était masqué au
                // motif que « la dictée arrive en phase 7 » — annoncer la destruction de ce qui
                // n'existe pas est le même mensonge qu'une étape déclarée et jamais exécutée.
                //
                // La phase 7 est livrée depuis le 2026-08-16 et `PanicStep.VOICE_MODEL_WIPE`
                // s'exécute. Le motif a donc expiré ce jour-là, sans que rien ne le signale : la
                // séquence effaçait le seul fichier que l'utilisateur ait mis plusieurs minutes à
                // installer, et l'écran où il donne son accord n'en disait rien.
                //
                // *La règle vaut dans les deux sens* : ne pas annoncer ce qu'on ne fait pas, et
                // annoncer tout ce qu'on fait. Le portage n'en avait retenu que la première moitié,
                // et une garde écrite contre le mensonge par excès s'est retournée en mensonge par
                // omission. C'est la forme la plus discrète du défaut, parce qu'elle se lit comme
                // de la prudence.
                Puce(stringResource(R.string.panic_confirm_item_1))
                Puce(stringResource(R.string.panic_confirm_item_2))
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
            ActionDeDialogue(texte = stringResource(R.string.common_cancel), onClick = onDismiss)
        },
        confirmButton = {
            Button(
                onClick = onConfirmed,
                enabled = peutConfirmer,
                shape = Formes.bouton,
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

    // ⚠️⚠️ **Le garde de retour vit ici, dans le recouvrement, pas chez son hôte.**
    //
    // Le KDoc ci-dessus promettait « ni bouton retour, ni fermeture » et rien ne le tenait : un
    // appui sur Retour dépilait la route des réglages, le recouvrement disparaissait, et la
    // séquence continuait dans la portée applicative sans que plus personne ne la voie. L'
    // utilisateur retombait dans une application qui a l'air intacte, en croyant avoir annulé une
    // destruction irréversible et déjà commencée — et sans jamais lire si la clé est tombée.
    //
    // L'application publiée ferme ce chemin depuis toujours (`panic_complete_screen.dart:47`,
    // `canPop: false`), comme son écran de démarrage. Le portage avait transposé le garde sur
    // `SplashScreen` et sur l'éditeur, et l'avait perdu sur le seul écran où il protège autre chose
    // que du confort. Relevé par la relecture externe du 2026-08-15.
    //
    // Pendant la séquence, le geste est **avalé**. Une fois le rapport affiché, il fait ce que fait
    // le bouton : fermer. Ne rien faire à ce moment-là enfermerait l'utilisateur devant un écran à
    // bouton unique, sans raison — la destruction est terminée, il n'y a plus rien à protéger.
    BackHandler { if (!running && report != null) onClose() }

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

            // 🔴🔴 **`minimalGuarantee`, et non `isComplete`.**
            //
            // Ce sont deux questions différentes, et cet écran doit poser la seconde. `isComplete`
            // demande « tout s'est-il bien passé » ; `minimalGuarantee` demande « suis-je protégé »,
            // c'est-à-dire uniquement « la clé est-elle détruite ». Un nettoyage de cache qui échoue
            // ne retire rien à la protection : la base est déjà du bruit.
            //
            // L'écran lisait `isComplete`. Il en résultait les deux erreurs symétriques, aux deux
            // extrémités de ce qui compte :
            //  • une purge de cache ratée déclenchait « une partie de vos données peut avoir
            //    survécu » alors que la garantie cryptographique était acquise — une alarme fausse,
            //    adressée à quelqu'un sous contrainte ;
            //  • une clé NON détruite n'était pas distinguée : même écran, même phrase, un simple
            //    compteur d'étapes. C'est le seul cas où l'utilisateur doit comprendre qu'il ne
            //    doit PAS se séparer de l'appareil, et rien ne le lui disait.
            //
            // ⚠️ `PanicReportTest.sequenceInterrompue` affirmait déjà en commentaire que « l'écran
            // de fin s'appuie sur `minimalGuarantee` et non sur `isComplete` ». C'était vrai du
            // domaine, faux de l'écran : un test juste dont le commentaire décrivait une production
            // qui ne l'était pas. Relevé indépendamment par les deux relectures externes et par
            // l'audit de cohérence du 2026-08-15.
            val protege = report.minimalGuarantee

            // ⚠️ `liveRegion` : l'issue doit être **annoncée** au lecteur d'écran. Sans cela,
            // quelqu'un qui n'a pas les yeux sur l'appareil ne sait pas ce qui s'est passé — au
            // moment précis où cette information compte le plus. Le titre porte l'annonce, donc il
            // doit porter la vérité : « effacement terminé » sur une clé survivante serait
            // exactement le mensonge que tout le reste de cette séquence s'interdit.
            Text(
                text = stringResource(
                    if (protege) R.string.panic_complete_title else R.string.panic_key_survived_title,
                ),
                style = MaterialTheme.typography.headlineSmall,
                color = if (protege) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Spacer(Modifier.height(16.dp))

            when {
                // 🔴 Le seul message de l'application qu'on ne doit jamais adoucir : les notes
                // restent déchiffrables, et quelqu'un est peut-être sur le point de se séparer de
                // son appareil en croyant le contraire.
                !protege -> Text(
                    text = stringResource(R.string.panic_key_survived, report.failedSteps.size),
                    color = MaterialTheme.colorScheme.error,
                )

                report.isComplete -> {
                    Text(stringResource(R.string.panic_complete_body))
                    Spacer(Modifier.height(16.dp))
                    // ⚠️ La troisième puce — « modèle de dictée vocale : désinstallé » — a été
                    // rétablie le 2026-08-19, avec l'item 2 du dialogue et pour la même raison :
                    // `PanicStep.VOICE_MODEL_WIPE` s'exécute depuis le 2026-08-16. Le bilan
                    // omettait un effacement qui avait bien eu lieu.
                    Puce(stringResource(R.string.panic_complete_bullet_1))
                    Puce(stringResource(R.string.panic_complete_bullet_2))
                    Puce(stringResource(R.string.panic_complete_bullet_3))
                    Puce(stringResource(R.string.panic_complete_bullet_4))
                }

                // 🔴 **Le nettoyage qui a échoué n'a pas laissé la même chose selon l'étape.**
                //
                // `panic_incomplete` dit « des fichiers **illisibles** peuvent subsister », et c'est
                // vrai de tout sauf du clair : une archive d'export, un enregistrement de dictée, ou
                // une note restée dans le presse-papiers. Si c'est l'un de ceux-là qui a survécu, la
                // phrase rassurante décrit exactement l'inverse de la situation — des notes
                // lisibles, à quelqu'un qui vient de déclencher une destruction sous contrainte.
                //
                // Relevé CONFIRMÉ par une relecture externe (Gemini, 2026-08-15) ; l'autre relecture
                // avait conclu « rien trouvé » sur cet axe, parce qu'elle a regardé la logique des
                // branches et non le **texte** qu'elles affichent.
                //
                // ⚠️ La distinction n'est pas une quatrième issue globale : la garantie minimale
                // reste acquise — la base est du bruit. C'est la **nature du résidu** qui change, et
                // c'est elle que l'utilisateur doit connaître pour décider s'il peut se séparer de
                // l'appareil.
                report.clairPeutSubsister -> Text(
                    // ⚠️⚠️ **Sans compteur d'étapes, et c'est le point.** Ce résidu-là se MESURE à
                    // la fin de la séquence — il ne se déduit pas des étapes, et il vaut vrai avec
                    // ZÉRO étape en échec ; `PanicReportTest.clairRestantEstSignale` construit
                    // exactement cet état. La phrase affichait donc « 0 étape(s) de nettoyage ont
                    // échoué » juste avant d'avertir qu'il reste du clair, se contredisant dans sa
                    // propre phrase au seul moment où elle doit être crue.
                    //
                    // ⚠️ Le texte ne nomme plus les seules archives d'export : le prédicat couvre
                    // les trois sources de clair. Nommer la mauvaise envoyait quelqu'un fouiller
                    // des fichiers absents pendant qu'une note attendait dans le presse-papiers.
                    text = stringResource(R.string.panic_incomplete_plaintext),
                    color = MaterialTheme.colorScheme.error,
                )

                // La clé est tombée, donc l'essentiel est acquis ; seul un nettoyage a échoué. Pas
                // de rouge ici : l'alarme est réservée aux cas au-dessus, sans quoi elle ne veut
                // plus rien dire quand elle sert.
                else -> Text(stringResource(R.string.panic_incomplete, report.failedSteps.size))
            }

            // ⚠️ Le pied de page promet un prochain lancement « sur une base vierge ». Il n'est
            // affiché que si la clé est bien détruite — le promettre à côté d'un avertissement
            // disant que les notes restent lisibles ferait douter de celui des deux qui compte.
            if (protege) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.panic_complete_footer),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onClose, shape = Formes.bouton) { Text(stringResource(R.string.panic_complete_close)) }
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
