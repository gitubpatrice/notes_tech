package com.filestech.notes_tech.ui.voice

import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.notes_tech.data.voice.SttModelStore
import com.filestech.notes_tech.data.voice.VoiceCapture
import com.filestech.notes_tech.domain.voice.SpeechToText
import com.filestech.notes_tech.domain.voice.SttModelCatalogue
import com.filestech.notes_tech.domain.voice.SttPermissionDeniedException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Où en est la dictée. L'écran n'affiche sa superposition que hors de [INACTIVE].
 *
 * 🔴 **[INITIALISATION] n'est pas un raffinement.** `AudioRecord` met un instant à s'ouvrir, et la
 * superposition disait « Parlez » avant que le micro n'enregistre : le premier mot était perdu, et
 * l'utilisateur ne pouvait l'apprendre qu'en relisant la transcription. La chaîne
 * `voice_mic_initializing` existait, traduite, et n'était utilisée nulle part — une orpheline qui
 * signalait une étape manquante, pas une chaîne en trop.
 */
enum class EtapeDeDictee { INACTIVE, INITIALISATION, ENREGISTREMENT, TRANSCRIPTION }

/**
 * Ce que la dictée a produit, ou pourquoi elle n'a rien produit.
 *
 * 🔴 **Un type, pas un message d'exception.** Même règle que pour l'import, et pour la même raison :
 * chaque cas appelle un geste différent — installer un modèle, ouvrir les réglages système,
 * réessayer, ou rien du tout.
 */
sealed interface IssueDeDictee {
    /** ⚠️ Un événement à consommer **une fois**, pas un état : le texte s'insère, puis s'oublie. */
    data class Texte(val contenu: String) : IssueDeDictee

    /** L'utilisateur n'a rien dit. Ce n'est **pas** un échec. */
    data object Silence : IssueDeDictee

    data object ModeleAbsent : IssueDeDictee

    /**
     * @param definitif « ne plus demander » a été coché. ⚠️ Seule l'interface peut le savoir —
     *   `shouldShowRequestPermissionRationale` demande une activité — d'où ce drapeau renseigné
     *   par l'écran, et non par la couche de données. Sans lui, l'utilisateur se verrait proposer
     *   une demande **qu'Android ignore en silence**.
     */
    data class PermissionRefusee(val definitif: Boolean) : IssueDeDictee

    data object CaptureImpossible : IssueDeDictee

    data object TranscriptionImpossible : IssueDeDictee
}

/**
 * La dictée, du bouton micro au texte inséré.
 *
 * ## 🔴 Le fichier audio est effacé dans un `finally`, toujours
 *
 * `VoiceCapture` le dit : l'enregistrement est un **sous-produit**, sans valeur une fois transcrit,
 * et son retrait est à la charge de l'appelant. Cet appelant, c'est cette classe. Le `finally`
 * couvre les trois sorties — texte obtenu, échec de transcription, annulation — parce qu'il n'y en a
 * aucune où laisser la voix de l'utilisateur sur le disque serait acceptable.
 *
 * ## ⚠️ Pourquoi une classe à part de l'éditeur
 *
 * `NoteEditorViewModel` porte déjà la note, ses liens, son coffre et son export. La dictée y
 * ajouterait un cycle de vie complet — permission, micro, moteur natif, fichier temporaire — dont
 * rien ne dépend du reste. Elle vit donc à côté, et ne rend à l'éditeur qu'une chaîne.
 */
@HiltViewModel
class DictationViewModel @Inject constructor(
    private val capture: VoiceCapture,
    private val moteur: SpeechToText,
    private val magasin: SttModelStore,
) : ViewModel() {

    private val _etape = MutableStateFlow(EtapeDeDictee.INACTIVE)
    val etape: StateFlow<EtapeDeDictee> = _etape.asStateFlow()

    private val _issue = MutableStateFlow<IssueDeDictee?>(null)

    /**
     * Ce qui doit agir sur la note — **exactement une fois**.
     *
     * 🔴 Consommé **dès l'insertion faite**, sans attendre le moindre affichage : une insertion qui
     * dépend de la durée d'un message se rejoue au premier changement de configuration, et le texte
     * dicté entre deux fois dans la note.
     */
    val issue: StateFlow<IssueDeDictee?> = _issue.asStateFlow()

    private val _message = MutableStateFlow<IssueDeDictee?>(null)

    /**
     * Ce qui doit être **dit** à l'utilisateur — et qui, lui, doit survivre.
     *
     * ## ⚠️⚠️ Pourquoi deux flux pour un seul événement
     *
     * Les deux moitiés n'ont pas la même exigence, et les confondre casse forcément l'une des deux :
     *
     * - l'**insertion** doit avoir lieu une fois, et pas deux ;
     * - le **message** doit avoir lieu une fois, et pas zéro.
     *
     * Tant qu'ils partageaient un seul flux, il fallait choisir. Consommer tard rejouait
     * l'insertion ; consommer tôt perdait le message — il suffisait qu'un autre message occupe la
     * file et qu'une rotation détruise la composition avant que celui-ci ne paraisse. L'utilisateur
     * appuyait alors sur le micro, rien ne se passait, et **rien ne lui disait pourquoi**. Relevé
     * par une relecture externe (GPT-5.5, 2026-08-16), sur le correctif de la veille au soir.
     *
     * ⚠️ Celui-ci se consomme donc **après** l'affichage, par [messageAffiche] : une composition
     * détruite entre-temps le laisse en attente, et la suivante le reprend.
     */
    val message: StateFlow<IssueDeDictee?> = _message.asStateFlow()

    /** Le niveau sonore, pour que l'écran montre qu'on l'entend. Voir [VoiceCapture.niveau]. */
    val niveau: StateFlow<Float> = capture.niveau

    private var travail: Job? = null

    /**
     * Lance une dictée. Sans effet si une autre est en cours.
     *
     * ⚠️ **La permission est supposée accordée.** L'écran la demande avant d'appeler — il est le
     * seul à pouvoir le faire — et `VoiceCapture` refuse de toute façon si elle a été retirée entre
     * les deux.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun demarrer() {
        if (travail?.isActive == true) return
        travail = viewModelScope.launch {
            val modele = SttModelCatalogue.tous.firstOrNull { magasin.estPresent(it) }
            if (modele == null) {
                emettre(IssueDeDictee.ModeleAbsent)
                return@launch
            }

            _etape.value = EtapeDeDictee.INITIALISATION
            var audio: File? = null
            // ⚠️ Le passage à « parlez » est commandé par la CAPTURE, pas par l'horloge : c'est
            // elle qui sait quand le micro enregistre vraiment. Un délai fixe se tromperait sur
            // les appareils lents, c'est-à-dire précisément ceux qu'il faudrait couvrir.
            val suiviDuMicro = launch {
                capture.etat.first { it == VoiceCapture.Etat.EN_COURS }
                _etape.value = EtapeDeDictee.ENREGISTREMENT
            }
            try {
                audio = capturerPuisArreterLeSuivi(suiviDuMicro)
                if (audio == null) {
                    // ⚠️ `null` = appui bref, pas panne — et rien à transcrire. ⚠️⚠️ Mais **c'est
                    // signalé**, par un message NEUTRE : l'absence de tout retour après un appui sur
                    // « Arrêter » se lit comme une panne. Ce commentaire disait « rien à signaler »
                    // alors que le correctif de la veille avait justement ajouté ce message —
                    // relevé par une relecture externe (Gemini, 2026-08-16), sur le correctif.
                    emettre(IssueDeDictee.Silence)
                    return@launch
                }

                _etape.value = EtapeDeDictee.TRANSCRIPTION
                // ⚠️ `initialize` est idempotente et revérifie l'empreinte du modèle : l'appeler à
                // chaque dictée coûte une relecture, et c'est le prix de la promesse « vérifié avant
                // chaque chargement ». Le moteur, lui, ne se recharge pas.
                moteur.initialize(modele)
                val resultat = moteur.transcribeFile(audio.absolutePath)

                emettre(if (resultat.isEmpty) IssueDeDictee.Silence else IssueDeDictee.Texte(resultat.text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SttPermissionDeniedException) {
                // ⚠️ La cause est **tracée, pas perdue**. `SttErrors` le dit : le message suffit à
                // l'écran, il ne suffit pas à un diagnostic — une `SecurityException` du micro
                // perdue en route, c'est un rapport d'incident où il ne reste que « refusée ».
                Timber.w(e, "dictee : permission micro refusee")
                // ⚠️ `definitif = false` : la couche de données ne peut pas le savoir. C'est l'écran
                // qui recalcule, et lui seul.
                emettre(IssueDeDictee.PermissionRefusee(definitif = false))
            } catch (e: Throwable) {
                Timber.w(e, "dictee : echec")
                // ⚠️⚠️ **INITIALISATION compte comme de la capture.** L'ajout de cette étape a
                // cassé ce classement une première fois : une panne d'ouverture du micro — le cas
                // le plus fréquent, micro déjà pris par une autre application — se serait annoncée
                // comme un échec de TRANSCRIPTION, c'est-à-dire en accusant le modèle. Un correctif
                // est du code neuf, et il vise mal ce qu'il ne regarde pas.
                emettre(
                    when (_etape.value) {
                        EtapeDeDictee.INITIALISATION, EtapeDeDictee.ENREGISTREMENT ->
                            IssueDeDictee.CaptureImpossible
                        EtapeDeDictee.TRANSCRIPTION, EtapeDeDictee.INACTIVE ->
                            IssueDeDictee.TranscriptionImpossible
                    },
                )
            } finally {
                // 🔴 Toujours, sur les trois sorties. Voir la note de classe.
                audio?.let(::effacerOuSignaler)
                _etape.value = EtapeDeDictee.INACTIVE
            }
        }
    }

    /**
     * ⚠️ **L'annotation est portée jusqu'ici, elle n'est pas éteinte.** `VoiceCapture.enregistrer`
     * est marquée `@RequiresPermission`, et lint la remonte à chaque appelant — c'est précisément
     * son rôle : faire porter la contrainte jusqu'à celui qui peut demander la permission,
     * c'est-à-dire l'écran. La chaîne va donc de la capture à `ControleurDeDictee`, où le contrôle
     * a réellement lieu. Une suppression ici aurait coupé le fil au milieu.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun capturerPuisArreterLeSuivi(suivi: Job): File? = try {
        capture.enregistrer()
    } finally {
        suivi.cancel()
    }

    /**
     * Un modèle est-il installé ?
     *
     * 🔴 **À interroger AVANT de demander la permission du micro.** Sans ce contrôle, l'application
     * ouvrait la boîte de dialogue système — une permission sensible, que l'utilisateur accorde ou
     * refuse durablement — pour une action qui ne pouvait de toute façon pas aboutir. Demander
     * quelque chose dont on n'a pas l'usage est la meilleure façon de se le voir refuser une fois
     * pour toutes. Relevé **sur l'appareil**, en enchaînant les écrans.
     *
     * ⚠️ Contrôle bon marché — deux appels système — et volontairement pas la relecture d'empreinte :
     * il répond à « faut-il ouvrir le micro ? », pas à « ce binaire est-il celui qu'on croit ? ».
     * La seconde question se pose au chargement, et `WhisperStt` s'en charge.
     */
    fun modeleDisponible(): Boolean = SttModelCatalogue.tous.any { magasin.estPresent(it) }

    /**
     * Pose les deux moitiés de l'événement : celle qui agit, et celle qui parle.
     *
     * ⚠️ Une seule fonction, pour qu'elles ne puissent pas diverger. Deux affectations séparées, et
     * un cas d'échec ajouté plus tard n'en poserait qu'une — le geste sans son accusé, ou l'inverse.
     */
    private fun emettre(issue: IssueDeDictee) {
        _issue.value = issue
        _message.value = issue
    }

    /** Termine l'enregistrement et lance la transcription. */
    fun arreter() = capture.arreter()

    /**
     * Abandonne tout.
     *
     * ⚠️ L'annulation traverse jusqu'au moteur natif : `WhisperStt` pose un rappel d'interruption
     * sur le travail courant. Sans cela, un abandon laisserait plusieurs secondes de calcul sur un
     * appareil que l'utilisateur croit avoir libéré — et le fichier audio ouvert pendant ce temps.
     */
    fun abandonner() {
        capture.arreter()
        travail?.cancel()
    }

    /** L'insertion est faite. ⚠️ N'efface **pas** le message : voir [message]. */
    fun issueConsommee() = _issue.update { null }

    /** Le message a été **affiché**. À appeler après `showSnackbar`, jamais avant. */
    fun messageAffiche() = _message.update { null }

    private fun effacerOuSignaler(fichier: File) {
        try {
            if (fichier.exists() && !fichier.delete()) {
                Timber.e("dictee : l'audio %s n'a PAS pu etre efface", fichier.name)
            }
        } catch (e: SecurityException) {
            Timber.e(e, "dictee : effacement de l'audio %s refuse", fichier.name)
        }
    }
}
