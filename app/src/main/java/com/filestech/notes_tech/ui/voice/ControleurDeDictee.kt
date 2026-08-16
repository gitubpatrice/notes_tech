package com.filestech.notes_tech.ui.voice

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.ui.common.ActionDeDialogue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Tout ce que l'éditeur a besoin de savoir de la dictée, et rien de plus.
 *
 * ## Pourquoi ce détour plutôt qu'un branchement direct dans l'éditeur
 *
 * La dictée demande une permission, un lanceur d'activité, une lecture de `shouldShowRequest…`, une
 * superposition, un dialogue de refus et sept messages distincts. Posé tel quel dans
 * `NoteEditorRoute`, cela l'a fait franchir les seuils de longueur **et** de complexité que detekt
 * garde — et le gate a raison : cette fonction porte déjà la note, ses liens, son coffre, son
 * déplacement et son export.
 *
 * ⚠️ **La parade n'est pas de régénérer la baseline.** Ce qui est extrait ici forme un tout
 * cohérent qui ne dépend de rien de l'éditeur, sinon d'un endroit où poser le texte.
 */
@Stable
class ControleurDeDictee internal constructor(
    val etape: EtapeDeDictee,
    val niveau: Float,
    internal val refusDefinitif: Boolean,
    internal val onRefusVu: () -> Unit,
    internal val onReglagesInjoignables: () -> Unit,
    /** Demande la permission si nécessaire, puis démarre. */
    val demarrer: () -> Unit,
    /** Termine l'enregistrement et enchaîne sur la transcription. */
    val arreter: () -> Unit,
    /** Abandonne : l'annulation traverse jusqu'au moteur natif. */
    val abandonner: () -> Unit,
) {
    val actif: Boolean get() = etape != EtapeDeDictee.INACTIVE
}

/**
 * Câble la dictée, et rend de quoi la piloter.
 *
 * ⚠️ N'émet **rien**. L'affichage — superposition et dialogue de refus — est à [SurcoucheDeDictee],
 * que l'appelant place où il veut dans son arbre.
 *
 * @param onTexte reçoit la transcription, à insérer là où l'appelant le juge bon.
 */
@Composable
fun rememberControleurDeDictee(onTexte: (String) -> Unit, messages: SnackbarHostState): ControleurDeDictee {
    val dictee: DictationViewModel = hiltViewModel()
    val etape by dictee.etape.collectAsStateWithLifecycle()
    val niveau by dictee.niveau.collectAsStateWithLifecycle()
    val issue by dictee.issue.collectAsStateWithLifecycle()
    val message by dictee.message.collectAsStateWithLifecycle()

    val activite = LocalActivity.current
    val contexte = LocalContext.current

    // ⚠️ `rememberSaveable` : la demande de permission passe par une autre fenêtre, et l'activité
    // peut être recréée pendant ce détour. Voir la note du même sujet dans `VoiceSetupScreen`.
    var refusDefinitif by rememberSaveable { mutableStateOf(false) }

    // 🔴🔴 **Une portée qui SURVIT à l'annulation de l'effet.** Voir [annoncer].
    val portee = rememberCoroutineScope()

    val texteInsere = stringResource(R.string.voice_transcribed)
    val rienEntendu = stringResource(R.string.voice_nothing_heard)
    val aucunModele = stringResource(R.string.error_voice_no_model_installed)
    val captureImpossible = stringResource(R.string.error_voice_start_capture_failed)
    val transcriptionImpossible = stringResource(R.string.error_voice_transcribe_failed)
    val microRefuse = stringResource(R.string.voice_permission_needed)
    val reglagesInjoignables = stringResource(R.string.voice_system_settings_unavailable)

    val demande = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { accordee ->
        if (accordee) {
            dictee.demarrer()
        } else {
            // 🔴🔴 **C'est ICI, et nulle part ailleurs, que « ne plus demander » se lit.**
            //
            // `shouldShowRequestPermissionRationale` exige une activité ; une couche de données n'en
            // a pas, et lui en donner une ferait remonter l'interface dans le service.
            // `SttPermissionDeniedException` désigne explicitement l'écran comme responsable de ce
            // calcul — c'est ce contrat qui se tient ici.
            val definitif = activite?.let {
                !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
            } ?: false
            refusDefinitif = definitif
            // ⚠️⚠️ **Un refus SIMPLE n'affichait rien du tout.** Seul le cas définitif était traité :
            // le bouton micro revenait à l'écran, sans un mot, et l'utilisateur ne pouvait pas
            // savoir s'il avait raté son geste ou si l'application était en panne. Relevé par une
            // relecture externe (GPT-5.5, 2026-08-16).
            if (!definitif) messages.annoncer(portee, microRefuse)
        }
    }

    // 🔴 **L'insertion : consommée DÈS QU'ELLE EST FAITE.** Elle ne doit avoir lieu qu'une fois, et
    // la faire dépendre de la durée d'un message la rejouait à la première rotation.
    LaunchedEffect(issue) {
        val courante = issue ?: return@LaunchedEffect
        if (courante is IssueDeDictee.Texte) onTexte(courante.contenu)
        dictee.issueConsommee()
    }

    // 🔴 **Le message : consommé APRÈS son affichage.** Il ne doit pas avoir lieu zéro fois — et
    // c'est ce qui arrivait quand un seul flux portait les deux moitiés. Une composition détruite
    // avant que le message ne paraisse le laisse en attente, et celle-ci le reprend.
    LaunchedEffect(message) {
        val courant = message ?: return@LaunchedEffect
        messages.showSnackbar(
            when (courant) {
                is IssueDeDictee.Texte -> texteInsere
                // ⚠️ Le silence n'est PAS un échec : l'utilisateur n'a rien dit. Il affichait
                // pourtant « échec de la transcription » — une erreur technique pour un geste
                // ordinaire. Même règle que le `null` de `VoiceCapture.enregistrer`. Relevé par les
                // DEUX relectures externes (2026-08-16).
                IssueDeDictee.Silence -> rienEntendu
                IssueDeDictee.ModeleAbsent -> aucunModele
                IssueDeDictee.CaptureImpossible -> captureImpossible
                // ⚠️ Et non `error_voice_mic_capture_error`, qui orientait vers le micro alors que
                // l'enregistrement s'était bien passé et que c'est la transcription qui a échoué.
                IssueDeDictee.TranscriptionImpossible -> transcriptionImpossible
                is IssueDeDictee.PermissionRefusee -> microRefuse
            },
        )
        // ⚠️ Ici, et pas avant : si la composition meurt pendant l'affichage, cet appel n'a pas
        // lieu, et le message repart avec la composition suivante. C'est exactement ce qu'on veut —
        // et c'est possible **parce que** l'insertion, elle, a déjà été consommée ailleurs.
        dictee.messageAffiche()
    }

    return ControleurDeDictee(
        etape = etape,
        niveau = niveau,
        refusDefinitif = refusDefinitif,
        onRefusVu = { refusDefinitif = false },
        onReglagesInjoignables = { messages.annoncer(portee, reglagesInjoignables) },
        demarrer = {
            // 🔴 **Le modèle d'abord, la permission ensuite.** Demander le micro pour une dictée
            // qui ne peut pas aboutir, faute de modèle, c'est faire refuser durablement une
            // permission dont on n'avait pas encore l'usage.
            if (!dictee.modeleDisponible()) {
                dictee.signalerModeleAbsent()
            } else {
                // ⚠️ Le contrôle préalable évite de rouvrir une boîte système à chaque dictée : une
                // permission déjà accordée ne se redemande pas.
                val accordee = ContextCompat.checkSelfPermission(contexte, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (accordee) dictee.demarrer() else demande.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        arreter = dictee::arreter,
        abandonner = dictee::abandonner,
    )
}

/**
 * Affiche un message **sans retenir l'appelant**.
 *
 * ## 🔴🔴 Pourquoi ce détour, et ce qu'il a coûté
 *
 * `showSnackbar` **suspend** jusqu'à ce que le message disparaisse — plusieurs secondes. Appelé
 * directement depuis un `LaunchedEffect`, il retardait donc d'autant la consommation de l'événement
 * qui l'avait déclenché. Une rotation d'écran pendant ce délai annulait l'effet **avant** la
 * consommation, la nouvelle composition relisait la même issue, et **le texte dicté s'insérait une
 * seconde fois dans la note**.
 *
 * L'ordre correct n'est donc ni « consommer puis afficher » — l'effet s'annule et le message ne
 * paraît jamais — ni « afficher puis consommer ». C'est **consommer, puis afficher depuis une
 * portée qui ne dépend pas de l'effet**. Relevé par les DEUX relectures externes (2026-08-16), et
 * la règle « `showSnackbar` suspend le collecteur » était déjà écrite ailleurs dans ce dépôt.
 */
private fun SnackbarHostState.annoncer(portee: CoroutineScope, message: String) {
    // ⚠️ Le message en cours est **congédié** d'abord. `showSnackbar` fait la file : trois appuis
    // rapides sur un micro refusé enchaînaient trois fois le même message pendant une dizaine de
    // secondes. Avant le passage à une portée détachée, l'annulation de l'effet coupait la file
    // toute seule — le correctif a donc introduit l'empilement en fermant l'autre défaut. Relevé
    // par une relecture externe (Gemini, 2026-08-16).
    currentSnackbarData?.dismiss()
    portee.launch { showSnackbar(message) }
}

/** Ce que la dictée affiche : la superposition, et le dialogue d'un refus définitif. */
@Composable
fun SurcoucheDeDictee(controleur: ControleurDeDictee) {
    val contexte = LocalContext.current

    SuperpositionDeDictee(
        etape = controleur.etape,
        niveau = controleur.niveau,
        onArreter = controleur.arreter,
        onAbandonner = controleur.abandonner,
    )

    if (controleur.refusDefinitif) {
        DialogueDeMicroRefuse(
            onOuvrirLesReglages = {
                controleur.onRefusVu()
                if (!ouvrirLesReglagesDeLApplication(contexte)) controleur.onReglagesInjoignables()
            },
            onFermer = controleur.onRefusVu,
        )
    }
}

/**
 * Le refus **définitif** du micro, et la seule chose qui reste à proposer.
 *
 * 🔴 Redemander la permission dans ce cas est **ignoré en silence par Android** : l'utilisateur
 * verrait un bouton qui ne fait rien.
 *
 * ⚠️ Deux actions, donc deux emplacements — la limite d'un `AlertDialog`, déjà éprouvée par le
 * dialogue de suppression de dossier, où une troisième écrasait la hauteur des boutons.
 */
@Composable
private fun DialogueDeMicroRefuse(onOuvrirLesReglages: () -> Unit, onFermer: () -> Unit) {
    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(stringResource(R.string.voice_permission_denied)) },
        confirmButton = {
            ActionDeDialogue(
                texte = stringResource(R.string.voice_open_system_settings),
                onClick = onOuvrirLesReglages,
            )
        },
        dismissButton = {
            ActionDeDialogue(texte = stringResource(R.string.common_cancel), onClick = onFermer)
        },
    )
}

/**
 * Ouvre la fiche de l'application dans les réglages système. Rend `false` si c'est impossible.
 *
 * ⚠️ `ACTION_APPLICATION_DETAILS_SETTINGS` et non un écran de permissions : le second n'existe pas
 * sous le même nom sur toutes les surcouches, et un intent sans destinataire lève.
 *
 * ⚠️⚠️ **L'échec est RENDU, pas seulement tracé.** Il était avalé : sur un appareil dont le
 * constructeur ne résout pas cet intent, le dialogue se fermait et il ne se passait rien — un bouton
 * « ouvrir les réglages » devenu un bouton « annuler ». L'utilisateur, lui, restait sans micro et
 * sans consigne. Relevé par une relecture externe (GPT-5.5, 2026-08-16).
 */
private fun ouvrirLesReglagesDeLApplication(contexte: Context): Boolean = try {
    contexte.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", contexte.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (e: ActivityNotFoundException) {
    Timber.w(e, "dictee : reglages systeme injoignables")
    false
}
