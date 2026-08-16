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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.ui.common.ActionDeDialogue
import timber.log.Timber

/**
 * Tout ce que l'éditeur a besoin de savoir de la dictée, et rien de plus.
 *
 * ## Pourquoi ce détour plutôt qu'un branchement direct dans l'éditeur
 *
 * La dictée demande une permission, un lanceur d'activité, une lecture de `shouldShowRequest…`, une
 * superposition, un dialogue de refus et six messages distincts. Posé tel quel dans
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
 * que l'appelant place où il veut dans son arbre. Séparer les deux évite d'imposer à l'éditeur la
 * position de la superposition dans sa propre hiérarchie.
 *
 * @param onTexte reçoit la transcription, à insérer là où l'appelant le juge bon.
 */
@Composable
fun rememberControleurDeDictee(onTexte: (String) -> Unit, messages: SnackbarHostState): ControleurDeDictee {
    val dictee: DictationViewModel = hiltViewModel()
    val etape by dictee.etape.collectAsStateWithLifecycle()
    val niveau by dictee.niveau.collectAsStateWithLifecycle()
    val issue by dictee.issue.collectAsStateWithLifecycle()

    val activite = LocalActivity.current
    val contexte = LocalContext.current

    // ⚠️ Déclaré avant le lanceur : sa fermeture le capture.
    var refusDefinitif by remember { mutableStateOf(false) }

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
            //
            // ⚠️ Sans lui, l'utilisateur ayant coché « ne plus demander » se verrait proposer une
            // nouvelle demande **qu'Android ignore en silence** : un bouton qui ne fait rien.
            refusDefinitif = activite?.let {
                !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
            } ?: false
        }
    }

    val texteInsere = stringResource(R.string.voice_transcribed)
    val rienEntendu = stringResource(R.string.error_voice_transcribe_failed)
    val aucunModele = stringResource(R.string.error_voice_no_model_installed)
    val captureImpossible = stringResource(R.string.error_voice_start_capture_failed)
    val transcriptionImpossible = stringResource(R.string.error_voice_mic_capture_error)
    val microRefuse = stringResource(R.string.voice_permission_denied)

    LaunchedEffect(issue) {
        // ⚠️⚠️ **Afficher PUIS consommer.** Consommer d'abord annulerait l'effet en cours, la clé
        // changeant par son propre effet — piège déjà payé deux fois dans ce dépôt.
        when (val courante = issue) {
            null -> Unit
            is IssueDeDictee.Texte -> {
                onTexte(courante.contenu)
                messages.showSnackbar(texteInsere)
            }
            // ⚠️ Le silence n'est PAS un échec : l'utilisateur n'a rien dit. Même règle que le
            // `null` de `VoiceCapture.enregistrer`, et pour la même raison — un échec système et un
            // geste de l'utilisateur ne se classent pas ensemble.
            IssueDeDictee.Silence -> messages.showSnackbar(rienEntendu)
            IssueDeDictee.ModeleAbsent -> messages.showSnackbar(aucunModele)
            IssueDeDictee.CaptureImpossible -> messages.showSnackbar(captureImpossible)
            IssueDeDictee.TranscriptionImpossible -> messages.showSnackbar(transcriptionImpossible)
            is IssueDeDictee.PermissionRefusee -> messages.showSnackbar(microRefuse)
        }
        if (issue != null) dictee.issueConsommee()
    }

    return ControleurDeDictee(
        etape = etape,
        niveau = niveau,
        refusDefinitif = refusDefinitif,
        onRefusVu = { refusDefinitif = false },
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
                ouvrirLesReglagesDeLApplication(contexte)
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
 * Ouvre la fiche de l'application dans les réglages système.
 *
 * ⚠️ `ACTION_APPLICATION_DETAILS_SETTINGS` et non un écran de permissions : le second n'existe pas
 * sous le même nom sur toutes les surcouches, et un intent sans destinataire lève.
 *
 * ⚠️ L'échec est **avalé volontairement, et tracé** : c'est un raccourci de confort, et faire tomber
 * l'éditeur d'une note parce qu'un constructeur a retiré un écran de réglages serait disproportionné.
 */
private fun ouvrirLesReglagesDeLApplication(contexte: Context) {
    try {
        contexte.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", contexte.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "dictee : reglages systeme injoignables")
    }
}
