package com.filestech.notes_tech.ui.voice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.voice.SttModel
import com.filestech.notes_tech.domain.voice.SttModelCatalogue
import com.filestech.notes_tech.ui.common.ActionDeDialogue

/**
 * L'installation du modèle de dictée.
 *
 * ## 🔴 Cet écran demande à l'utilisateur un effort inhabituel, et doit l'assumer
 *
 * Il lui demande d'aller chercher lui-même un fichier de plusieurs dizaines de mégaoctets, sur un
 * site qu'il ne connaît pas, puis de le rapporter. Aucune application ordinaire ne fait ça — et la
 * raison en est la promesse même de celle-ci : **elle n'a aucune permission réseau**, donc elle ne
 * peut rien télécharger, ni pour lui rendre service ni à son insu.
 *
 * Trois conséquences sur l'écran :
 *
 * 1. **le nom exact du fichier est affiché**, et copiable. La source publie une trentaine de
 *    variantes dont les noms ne diffèrent que par un suffixe ; se tromper coûte un téléchargement
 *    entier pour finir sur une empreinte qui ne correspond pas ;
 * 2. **la progression est visible**. Copier et vérifier cinquante mégaoctets prend plusieurs
 *    secondes, et un écran figé après un geste long se relance ;
 * 3. **chaque échec dit quoi faire** — choisir un autre fichier, libérer de la place, retélécharger.
 *    Un « import échoué » unique laisserait l'utilisateur devant le même mur.
 *
 * ## ⚠️ Pourquoi il n'y a pas de bouton « télécharger »
 *
 * L'application publiée en a un : il passe le lien au navigateur du système. Le portage ne le fait
 * pas, et **copie le lien** à la place. La différence est mince techniquement — ouvrir un navigateur
 * ne demande aucune permission — mais elle porte sur ce que l'application *fait* : l'une remet une
 * adresse à un autre logiciel, l'autre la met à disposition. Pour une application dont l'argument
 * est de ne parler à personne, c'est la seconde qui se décrit sans réserve.
 *
 * ## ⚠️ Les huit chaînes orphelines, et pourquoi chacune l'est
 *
 * *Une orpheline n'est pas une question posée au produit : c'est une fonctionnalité qui manque* —
 * **sauf** quand le portage fait mieux. La règle a déjà servi, et elle a servi ici : sur neuf
 * orphelines relevées, une signalait un vrai manque (`voice_setup_install_ok`, la confirmation après
 * un import de plusieurs minutes) et a été câblée. Les huit autres sont délibérées :
 *
 * - `voice_setup_download`, `..._browser_open_failed`, `..._browser_open_error` : le portage n'ouvre
 *   pas de navigateur. Voir ci-dessus.
 * - `voice_setup_install_fail`, `voice_setup_checksum_mismatch_body` : elles portent `{message}` —
 *   le message **interne** de l'exception, non traduit. `VaultAttempt` a déjà dû cesser de faire ça.
 *   Remplacées par un message **par cause**.
 * - `voice_setup_import_in_progress` : le cas est **empêché** — les actions sont désarmées pendant
 *   un import — au lieu d'être signalé après coup.
 * - `voice_setup_path_unavailable` : la version publiée avait besoin d'un vrai chemin de fichier ;
 *   le portage lit une `Uri` directement, donc cet échec n'existe pas.
 * - `voice_setup_picker_dialog_title` : le sélecteur de documents Android n'accepte pas de titre.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSetupRoute(onBack: () -> Unit) {
    val viewModel: VoiceSetupViewModel = hiltViewModel()
    val etat by viewModel.etat.collectAsStateWithLifecycle()
    val contexte = LocalContext.current
    val snackbars = remember { SnackbarHostState() }

    // ⚠️⚠️ **Le chemin voisin, que le correctif de `modeleVise` avait oublié.** Le dialogue de
    // retrait disparaissait à la rotation, sans action et sans un mot. C'est le motif du *jumeau
    // asymétrique* : deux états du même écran, un seul rendu durable, et la relecture du fichier
    // corrigé ne regarde pas celui d'à côté. Relevé par une relecture externe (Gemini, 2026-08-16),
    // **sur le correctif** — c'est exactement ce qu'on lui demandait de chercher.
    //
    // ⚠️ L'**identifiant**, pas le `SttModel` : un objet de domaine ne se met pas dans un `Bundle`.
    // Même parade que les deux détours du déplacement, dans l'éditeur.
    var aRetirer by rememberSaveable { mutableStateOf<String?>(null) }

    // ⚠️⚠️ Le modèle visé par le sélecteur est retenu ICI, et en `rememberSaveable` : le sélecteur
    // de documents traverse une **autre application**, et l'activité peut être recréée pendant ce
    // détour — rotation, thème système, pression mémoire. Un `remember` simple ne survit à aucun des
    // trois : au retour, la variable valait `null`, l'URI reçue était ignorée, et **le geste se
    // perdait sans un mot**. Le commentaire disait déjà tout cela, et le code ne le tenait pas.
    // Relevé par les DEUX relectures (2026-08-16).
    var modeleVise by rememberSaveable { mutableStateOf<String?>(null) }

    val selecteur = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val modele = modeleVise?.let(SttModelCatalogue::parIdentifiant)
        if (uri != null && modele != null) viewModel.importer(modele, uri)
        modeleVise = null
    }

    val messageLienCopie = stringResource(R.string.voice_setup_link_copied)
    val modeleInstalle = etat.installeAvecSucces
    val messageInstalle = modeleInstalle?.let { stringResource(R.string.voice_setup_install_ok, it) }

    // ⚠️ Ces deux messages n'agissent sur rien — ils ne font qu'informer. Ils se consomment donc
    // **après** l'affichage : une composition détruite entre-temps les laisse en attente, et la
    // suivante les reprend. C'est l'inverse de l'insertion de texte dictée, qui, elle, doit être
    // consommée tout de suite. Cf. `DictationViewModel.message`.
    //
    // ⚠️⚠️ Et **un seul à la fois** : l'un est affiché, l'autre reste posé pour le tour suivant.
    LaunchedEffect(etat.lienCopie, modeleInstalle) {
        if (messageInstalle != null) {
            snackbars.showSnackbar(messageInstalle)
            viewModel.installationConsommee()
        } else if (etat.lienCopie) {
            snackbars.showSnackbar(messageLienCopie)
            viewModel.lienConsomme()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.voice_setup_app_bar_title)) },
            )
        },
    ) { marges ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(marges)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.voice_setup_offline_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            ModeDEmploi(
                source = SttModelCatalogue.SOURCE_PUBLIQUE,
                onCopierLeLien = {
                    copierDansLePressePapiers(contexte, SttModelCatalogue.SOURCE_PUBLIQUE)
                    viewModel.lienCopie()
                },
            )

            if (etat.verificationEnCours) VerificationEnCours()

            Text(
                text = stringResource(R.string.voice_setup_choose_model),
                style = MaterialTheme.typography.titleMedium,
            )

            etat.modeles.forEach { modele ->
                CarteDeModele(
                    modele = modele,
                    installe = etat.installe == modele.id,
                    // ⚠️ Un seul import à la fois : les autres cartes se désarment pendant qu'il
                    // tourne, plutôt que d'accepter un geste que le magasin ferait attendre en
                    // silence. Un bouton qui ne répond pas se réappuie.
                    actionsPossibles = !etat.importEnCours && !etat.verificationEnCours,
                    onImporter = {
                        modeleVise = modele.id
                        // ⚠️ `*/*` et non un type MIME précis : un `.bin` n'a pas de type déclaré,
                        // et plusieurs fournisseurs le servent en `application/octet-stream`
                        // quand ils ne le servent pas comme inconnu. Filtrer ici rendrait le
                        // fichier **invisible** dans le sélecteur, sans aucun message.
                        selecteur.launch(arrayOf("*/*"))
                    },
                    onRetirer = { aRetirer = modele.id },
                )
            }

            etat.progression?.let { ProgressionDImport(it, viewModel::annulerImport) }

            Spacer(Modifier.height(8.dp))
            PiedDePageDeSecurite()
        }
    }

    etat.erreur?.let { cause ->
        DialogueDErreur(cause = cause, onFermer = viewModel::oublierLErreur)
    }

    aRetirer?.let(SttModelCatalogue::parIdentifiant)?.let { modele ->
        DialogueDeRetrait(
            onConfirmer = {
                viewModel.desinstaller(modele)
                aRetirer = null
            },
            onAnnuler = { aRetirer = null },
        )
    }
}

@Composable
private fun ModeDEmploi(source: String, onCopierLeLien: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.voice_setup_how_to_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Etape(R.string.voice_setup_step_1_title, R.string.voice_setup_step_1_text)
            Etape(R.string.voice_setup_step_2_title, R.string.voice_setup_step_2_text)
            Etape(R.string.voice_setup_step_3_title, R.string.voice_setup_step_3_text)

            Text(text = source, style = MaterialTheme.typography.bodySmall)
            ActionDeDialogue(
                texte = stringResource(R.string.voice_setup_copy_link_tooltip),
                onClick = onCopierLeLien,
            )
        }
    }
}

@Composable
private fun Etape(titre: Int, texte: Int) {
    Column {
        Text(stringResource(titre), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(texte), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CarteDeModele(
    modele: SttModel,
    installe: Boolean,
    actionsPossibles: Boolean,
    onImporter: () -> Unit,
    onRetirer: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(modele.displayName, style = MaterialTheme.typography.titleMedium)
                val libelleDEtat = if (installe) {
                    R.string.voice_setup_model_installed
                } else {
                    R.string.voice_setup_model_not_installed
                }
                Text(
                    text = stringResource(libelleDEtat),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (installe) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            // ⚠️ `null` plutôt qu'un repli en dur : une entrée future sans description traduite
            // n'affichera **rien**, ce qui se remarque — là où un repli en français passerait
            // inaperçu jusqu'à ce qu'un anglophone le signale.
            descriptionDe(modele)?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall)
            }
            // 🔴 Le nom du fichier amont. Sans lui, l'utilisateur ne peut pas faire l'import.
            Text(
                text = stringResource(R.string.voice_setup_upstream_file, modele.fichierAmont),
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionDeDialogue(
                    texte = stringResource(R.string.voice_setup_select_file),
                    onClick = onImporter,
                    enabled = actionsPossibles,
                )
                if (installe) {
                    ActionDeDialogue(
                        texte = stringResource(R.string.voice_setup_remove),
                        onClick = onRetirer,
                        enabled = actionsPossibles,
                        couleur = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * La description traduite d'un modèle.
 *
 * 🔴 Elle était un champ du catalogue — donc du français en dur, servi tel quel à un utilisateur
 * anglophone. Le défaut ne s'est vu qu'**à l'écran**, sur l'appareil : à la relecture, un champ
 * `notes` rempli d'une phrase française à côté d'autres champs français ne détonne pas.
 */
@StringRes
private fun descriptionDe(modele: SttModel): Int? = when (modele.id) {
    SttModelCatalogue.whisperBaseQ5.id -> R.string.voice_model_base_notes
    SttModelCatalogue.whisperTinyQ5.id -> R.string.voice_model_tiny_notes
    else -> null
}

@Composable
private fun VerificationEnCours() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.height(20.dp))
        Text(stringResource(R.string.voice_setup_verifying), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProgressionDImport(pourcentage: Int, onAnnuler: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.voice_setup_copying_progress, pourcentage),
            style = MaterialTheme.typography.bodyMedium,
        )
        // ⚠️ La barre est masquée aux lecteurs d'écran : le texte au-dessus porte déjà le
        // pourcentage, et une barre qui l'annonce une seconde fois transforme un import en litanie.
        LinearProgressIndicator(
            progress = { pourcentage / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {},
        )
        ActionDeDialogue(texte = stringResource(R.string.common_cancel), onClick = onAnnuler)
    }
}

/**
 * Ce que l'application fait de l'audio et du modèle.
 *
 * ⚠️ L'intitulé de l'application publiée était « Promesse ». Le mot annonce un **engagement** là où
 * la phrase qu'il coiffe énonce un **fonctionnement** — et une application dont l'argument est la
 * sobriété n'a pas à surjouer un texte qui se suffit. Remplacé par un intitulé qui décrit, dans le
 * vocabulaire que la politique de confidentialité emploie déjà.
 */
@Composable
private fun PiedDePageDeSecurite() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.voice_setup_security_footer_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = stringResource(R.string.voice_setup_security_footer_body),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun DialogueDErreur(cause: ErreurImport, onFermer: () -> Unit) {
    // 🔴 Le `when` est **exhaustif sur l'énumération** : ajouter un cas d'échec sans lui donner de
    // texte fera échouer la compilation, au lieu d'afficher un dialogue vide à quelqu'un dont
    // l'import vient de rater.
    val corps = when (cause) {
        ErreurImport.FICHIER_INADAPTE -> R.string.voice_setup_error_source_invalid
        ErreurImport.PLACE_INSUFFISANTE -> R.string.voice_setup_error_storage_full
        ErreurImport.EMPREINTE -> R.string.voice_setup_error_checksum
        ErreurImport.TECHNIQUE -> R.string.voice_setup_error_import_failed
    }
    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(stringResource(R.string.voice_setup_import_error_title)) },
        text = { Text(stringResource(corps)) },
        confirmButton = {
            ActionDeDialogue(texte = stringResource(R.string.common_ok), onClick = onFermer)
        },
    )
}

@Composable
private fun DialogueDeRetrait(onConfirmer: () -> Unit, onAnnuler: () -> Unit) {
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(stringResource(R.string.voice_setup_remove_confirm_title)) },
        text = { Text(stringResource(R.string.voice_setup_remove_confirm_body)) },
        confirmButton = {
            ActionDeDialogue(
                texte = stringResource(R.string.voice_setup_remove),
                onClick = onConfirmer,
                couleur = MaterialTheme.colorScheme.error,
            )
        },
        dismissButton = {
            ActionDeDialogue(texte = stringResource(R.string.common_cancel), onClick = onAnnuler)
        },
    )
}

/**
 * ⚠️ **Le presse-papiers ORDINAIRE, et non `SensitiveClipboard`.**
 *
 * Celui-ci efface son contenu au bout de quelques secondes, ce qui est exactement ce qu'il faut pour
 * une note — et exactement ce qu'il ne faut pas ici : l'utilisateur copie cette adresse **pour aller
 * la coller dans un navigateur**, geste qui traverse deux applications. La voir disparaître en route
 * serait incompréhensible. Une adresse publique n'est pas un secret ; la traiter comme tel rendrait
 * la fonction inutilisable.
 */
private fun copierDansLePressePapiers(contexte: Context, texte: String) {
    val presse = contexte.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    presse?.setPrimaryClip(ClipData.newPlainText(texte, texte))
}
