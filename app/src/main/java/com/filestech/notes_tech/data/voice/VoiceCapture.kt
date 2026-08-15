package com.filestech.notes_tech.data.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.filestech.notes_tech.domain.voice.SttPermissionDeniedException
import com.filestech.notes_tech.domain.voice.SttRecordingFailedException
import com.filestech.notes_tech.domain.voice.WavPcm16
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La capture du micro, vers un fichier WAV que le moteur de transcription sait lire.
 *
 * ## 🔴 Ce fichier contient la voix de l'utilisateur, donc le contenu de sa note
 *
 * Il est de la **même nature qu'une archive d'export** : du clair sur le disque, dans un répertoire
 * privé, qui n'existe que le temps d'un geste. Toutes les règles écrites pour l'export s'appliquent
 * ici, et pour les mêmes raisons — cf. `NoteExporter` :
 *
 * 1. **une seule racine**, [repertoireDeCapture], pour qu'un seul geste suffise à tout effacer ;
 * 2. **purgée au démarrage** et par le **mode panique**, tous deux par le **même chemin** — deux
 *    définitions du répertoire, et la panique nettoierait un dossier que la capture n'utilise plus ;
 * 3. une capture **annulée ou ratée** est retirée immédiatement, et l'échec du retrait est **dit**.
 *
 * ⚠️⚠️ **Une différence avec l'export, et elle est en défaveur de la capture** : une archive
 * d'export est un geste que l'utilisateur a demandé, pour la partager. Un enregistrement, lui, est
 * un sous-produit — il n'a aucune valeur une fois transcrit, et le garder une seconde de plus que
 * nécessaire n'apporte rien à personne. D'où le retrait dès la transcription obtenue, à la charge
 * de l'appelant.
 *
 * ## Le format n'est pas négociable
 *
 * WAV PCM 16 bits, **mono**, **16 kHz** : c'est le seul format pour lequel `SpeechToText` promet un
 * comportement. Whisper rééchantillonne en interne, mais lui donner autre chose ajoute une
 * conversion silencieuse dont personne ne verrait la perte.
 */
@Singleton
class VoiceCapture @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val clock: Clock,
) {

    /** Ce que la capture est en train de faire. */
    enum class Etat { ARRETEE, EN_COURS }

    private val _etat = MutableStateFlow(Etat.ARRETEE)
    val etat: StateFlow<Etat> = _etat.asStateFlow()

    /**
     * Le niveau sonore courant, entre `0` et `1`.
     *
     * ⚠️ Sert **uniquement** à l'affichage : une capture silencieuse doit se voir à l'écran, sinon
     * l'utilisateur parle dans un micro coupé sans le savoir et découvre le problème à la
     * transcription vide. Ce n'est pas une détection de parole.
     */
    private val _niveau = MutableStateFlow(0f)
    val niveau: StateFlow<Float> = _niveau.asStateFlow()

    /**
     * Un seul enregistrement à la fois.
     *
     * ⚠️ Le micro est une ressource **exclusive** : deux `AudioRecord` concurrents donnent un échec
     * d'initialisation opaque sur certains appareils, et un fichier vide sur d'autres. Le verrou
     * rend le refus explicite plutôt que dépendant du constructeur.
     */
    private val verrou = Mutex()

    @Volatile
    private var arretDemande = false

    private var enregistreur: AudioRecord? = null

    /**
     * Enregistre jusqu'à ce que [arreter] soit appelé, et rend le fichier WAV produit.
     *
     * ⚠️ **Suspend jusqu'à la fin de la capture.** L'appelant lance cette fonction dans une portée
     * qu'il contrôle et appelle [arreter] depuis l'interface ; l'annulation de la portée efface le
     * fichier au passage.
     *
     * @throws SttPermissionDeniedException `RECORD_AUDIO` n'est pas accordée. ⚠️ Le caractère
     *   **définitif** du refus ne se lit pas ici — il demande l'activité — donc c'est l'interface
     *   qui décide de proposer les réglages système plutôt qu'une nouvelle demande.
     * @throws SttRecordingFailedException le micro n'a pas démarré, ou la lecture a échoué.
     *
     * ⚠️ **`@RequiresPermission` en plus du contrôle d'exécution**, et les deux servent. Le contrôle
     * refuse proprement à l'exécution ; l'annotation fait porter la contrainte **jusqu'à l'appelant**,
     * qui est le seul à pouvoir demander la permission. Sans elle, l'interface pourrait appeler cette
     * fonction sans jamais rien demander, et découvrir le refus à l'usage.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun enregistrer(): File = verrou.withLock {
        if (!permissionAccordee()) {
            throw SttPermissionDeniedException("permission RECORD_AUDIO non accordee")
        }
        withContext(Dispatchers.IO) { capturer() }
    }

    /** Demande l'arrêt de la capture en cours. Sans effet s'il n'y en a pas. */
    fun arreter() {
        arretDemande = true
    }

    fun permissionAccordee(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun capturer(): File {
        val tailleMin = AudioRecord.getMinBufferSize(FREQUENCE_HZ, CANAL, ENCODAGE)
        if (tailleMin <= 0) {
            throw SttRecordingFailedException("le materiel refuse 16 kHz mono 16 bits")
        }
        // ⚠️ Un tampon au minimum syndical déborde dès que le fil de lecture est préempté, et
        // Android jette alors les échantillons **en silence**. Le facteur est le prix d'une capture
        // sans trous.
        val tailleTampon = tailleMin * FACTEUR_DE_TAMPON

        val fichier = fichierNeuf()
        arretDemande = false

        val micro = try {
            ouvrirLeMicro(tailleTampon)
        } catch (e: Throwable) {
            // ⚠️ Le fichier a déjà été créé : il est vide, mais un fichier `.wav` de zéro octet dans
            // le répertoire des captures se lirait comme une capture ratée qu'on aurait oubliée.
            effacerOuSignaler(fichier)
            throw e
        }

        enregistreur = micro
        _etat.value = Etat.EN_COURS
        return try {
            ecrireLeWav(micro, fichier, tailleTampon)
            fichier
        } catch (e: Throwable) {
            // ⚠️ `Throwable` : une annulation doit effacer l'audio elle aussi, puis repartir.
            effacerOuSignaler(fichier)
            throw e
        } finally {
            _etat.value = Etat.ARRETEE
            _niveau.value = 0f
            enregistreur = null
            arreterEtLiberer(micro)
        }
    }

    /**
     * Ouvre le micro, ou dit précisément pourquoi il ne s'ouvre pas.
     *
     * ⚠️ `VOICE_RECOGNITION` et non `MIC` : la chaîne de traitement du second applique une réduction
     * de bruit et un gain automatique réglés pour l'oreille humaine, pas pour un modèle acoustique.
     * Le premier livre le signal tel quel, ce que Whisper attend.
     *
     * ⚠️ Les trois issues d'échec sont **distinctes** parce qu'elles appellent trois réponses :
     * proposer les réglages système, dire que le micro est occupé, ou constater que l'appareil ne
     * sait pas faire du 16 kHz mono. Un « échec du micro » unique les rendrait indiscernables.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun ouvrirLeMicro(tailleTampon: Int): AudioRecord {
        val micro = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                FREQUENCE_HZ,
                CANAL,
                ENCODAGE,
                tailleTampon,
            )
        } catch (e: SecurityException) {
            // La permission a pu être révoquée entre le contrôle et ici.
            throw SttPermissionDeniedException("permission RECORD_AUDIO retiree", cause = e)
        } catch (e: IllegalArgumentException) {
            throw SttRecordingFailedException("configuration micro refusee", cause = e)
        }
        if (micro.state != AudioRecord.STATE_INITIALIZED) {
            micro.release()
            throw SttRecordingFailedException("micro indisponible — deja pris par une autre application ?")
        }
        return micro
    }

    private fun ecrireLeWav(micro: AudioRecord, fichier: File, tailleTampon: Int) {
        val tampon = ByteArray(tailleTampon)
        var octetsEcrits = 0L

        RandomAccessFile(fichier, "rw").use { sortie ->
            // 🔴 **L'en-tête est écrit AVANT les données, avec des tailles provisoires**, puis
            // corrigé à la fin. Un WAV porte ses longueurs dans ses douze premiers octets ; on ne
            // les connaît qu'une fois la capture terminée. Écrire l'en-tête après supposerait de
            // garder tout l'audio en mémoire — c'est-à-dire la voix de l'utilisateur dans le tas,
            // pendant toute la durée de l'enregistrement.
            sortie.write(WavPcm16.entete(donneesOctets = 0))

            micro.startRecording()
            if (micro.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw SttRecordingFailedException("le micro n'a pas demarre")
            }

            // ⚠️ La borne de durée est dans la CONDITION, pas dans un `break` au milieu du corps :
            // les deux façons d'arrêter — le geste de l'utilisateur et le garde-fou — se lisent
            // alors au même endroit, et aucune ne peut être manquée en relisant le corps.
            while (!arretDemande && octetsEcrits < OCTETS_MAX) {
                val lus = micro.read(tampon, 0, tampon.size)
                if (lus < 0) throw SttRecordingFailedException("lecture micro en echec : code $lus")
                if (lus > 0) {
                    sortie.write(tampon, 0, lus)
                    octetsEcrits += lus
                    _niveau.value = WavPcm16.niveau(tampon, lus)
                }
            }

            // ⚠️ Les tailles corrigées **en dernier** : un fichier interrompu avant cette ligne porte
            // un en-tête annonçant zéro donnée. C'est voulu — il ne se lit pas comme un audio
            // valide, et personne ne le prendra pour une capture exploitable.
            sortie.seek(0)
            sortie.write(WavPcm16.entete(donneesOctets = octetsEcrits))
        }

        if (octetsEcrits == 0L) {
            throw SttRecordingFailedException("aucun echantillon capture")
        }
    }

    /**
     * ⚠️ `stop()` lève si le micro n'enregistrait pas, et `release()` doit avoir lieu **de toute
     * façon** : un `AudioRecord` non libéré garde le micro pour toute l'application.
     */
    private fun arreterEtLiberer(micro: AudioRecord) {
        try {
            if (micro.recordingState == AudioRecord.RECORDSTATE_RECORDING) micro.stop()
        } catch (e: IllegalStateException) {
            Timber.w(e, "capture : arret du micro")
        } finally {
            micro.release()
        }
    }

    private fun fichierNeuf(): File {
        val racine = repertoireDeCapture(context)
        racine.mkdirs()
        return File(racine, "capture-${clock.millis()}.wav")
    }

    /** Voir `NoteExporter.effacerOuSignaler` : même règle, même raison. */
    private fun effacerOuSignaler(fichier: File) {
        try {
            if (fichier.exists() && !fichier.delete()) {
                Timber.e("capture : l'audio %s n'a PAS pu etre efface", fichier.name)
            }
        } catch (e: SecurityException) {
            Timber.e(e, "capture : effacement de l'audio %s refuse", fichier.name)
        }
    }

    companion object {
        /** Le seul endroit où un enregistrement est écrit. Voir la note de classe. */
        fun repertoireDeCapture(context: Context): File = File(context.cacheDir, "captures")

        /**
         * Efface les enregistrements laissés par les sessions précédentes.
         *
         * ⚠️ À appeler au démarrage du processus, comme `NoteExporter.purgerLesArchives`. Un
         * enregistrement qui survit à la fermeture de l'application attend indéfiniment dans le
         * cache, et personne ne se rappelle qu'il est là.
         */
        fun purgerLesCaptures(context: Context) {
            repertoireDeCapture(context).deleteRecursively()
        }

        /** ⚠️ Une seule définition du format, dans le domaine : `WavPcm16`. */
        const val FREQUENCE_HZ = WavPcm16.FREQUENCE_HZ
        private const val CANAL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODAGE = AudioFormat.ENCODING_PCM_16BIT
        private const val FACTEUR_DE_TAMPON = 4

        /**
         * Deux minutes d'audio, soit environ 3,8 Mo.
         *
         * ⚠️ **Une borne, parce qu'un micro qui reste ouvert est un micro qui reste ouvert.** Un
         * appui manqué sur « arrêter », une application mise en arrière-plan sans que le geste
         * aboutisse, et la capture continuerait tant que le processus vit — en écrivant la voix de
         * l'utilisateur sur le disque. La transcription d'une dictée de note ne dure pas deux
         * minutes ; ce qui dépasse est presque sûrement un accident.
         */
        private const val OCTETS_MAX = FREQUENCE_HZ.toLong() * 2 * 120
    }
}
