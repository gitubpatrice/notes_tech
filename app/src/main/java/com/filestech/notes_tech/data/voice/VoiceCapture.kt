package com.filestech.notes_tech.data.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.filestech.notes_tech.domain.voice.SttException
import com.filestech.notes_tech.domain.voice.SttPermissionDeniedException
import com.filestech.notes_tech.domain.voice.SttRecordingFailedException
import com.filestech.notes_tech.domain.voice.WavPcm16
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference
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

    /**
     * Pourquoi la capture s'est arrêtée.
     *
     * 🔴🔴 **Sans cette distinction, la borne de durée trahit en silence.** Les deux fins
     * produisaient le **même** `File`, et la suite du traitement était identique : transcription,
     * insertion, message « Texte inséré. ». Un utilisateur qui dictait trois minutes en perdait une,
     * et **rien** dans l'application ne le lui disait (`04-PIEGES.md` §96).
     */
    enum class FinDeCapture {
        /** [arreter] a été appelé — le cas ordinaire. */
        GESTE,

        /** [DUREE_MAX_SECONDES] atteint : **la suite de la dictée n'a pas été enregistrée**. */
        BORNE_DE_DUREE,
    }

    /**
     * Ce qu'une capture a produit, **et pourquoi elle s'est terminée**.
     *
     * ⚠️ Un type de retour, et **pas** un drapeau lu à côté : un appelant ne peut pas oublier de
     * regarder ce qu'il reçoit, alors qu'il peut très bien ne jamais lire une propriété.
     */
    data class Capture(val fichier: File, val fin: FinDeCapture)

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
     * Les secondes déjà capturées.
     *
     * 🔴 **Dérivées des octets écrits, pas d'une horloge.** C'est le **même** compteur qui déclenche
     * [DUREE_MAX_SECONDES], si bien que ce que l'écran affiche et ce qui coupe la capture ne peuvent
     * pas diverger. Une horloge parallèle dériverait de l'audio réellement écrit — et c'est
     * précisément près de la borne que l'écart compterait.
     */
    private val _secondes = MutableStateFlow(0)
    val secondes: StateFlow<Int> = _secondes.asStateFlow()

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

    /** Une fois posé, plus aucune capture n'est ouverte. Voir [couperEtInterdire]. */
    @Volatile
    private var interdite = false

    private var enregistreur: AudioRecord? = null

    /**
     * Enregistre jusqu'à ce que [arreter] soit appelé **ou que [DUREE_MAX_SECONDES] soit atteint**, et
     * rend la [Capture] produite — ou `null`.
     *
     * ⚠️⚠️ **Les deux fins ne se valent pas et la [Capture] les distingue** : la seconde veut dire que
     * la suite de ce que l'utilisateur disait **n'a pas été enregistrée**. Cf. [FinDeCapture].
     *
     * ⚠️ **`null` n'est pas un échec** : c'est un arrêt demandé avant qu'un seul échantillon
     * n'arrive, c'est-à-dire un appui bref. Le signaler par une exception ferait afficher « échec de
     * l'enregistrement » à quelqu'un qui n'a simplement rien dit. Un échec système et un geste de
     * l'utilisateur ne se classent pas ensemble.
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
    suspend fun enregistrer(): Capture? {
        // 🔴 **Le drapeau se remet à zéro ICI, avant le verrou — pas dans [capturer].**
        //
        // Il y était. Séquence relevée par les DEUX relectures externes (2026-08-15) : l'utilisateur
        // lance une capture, l'appel attend le verrou parce que la précédente finit de s'écrire,
        // l'utilisateur appuie sur « arrêter » — et [capturer] remettait alors le drapeau à `false`
        // **après coup**. La demande d'arrêt était effacée avant que le micro ne s'ouvre, et
        // l'enregistrement partait pour deux minutes.
        //
        // Remis à zéro au moment où l'utilisateur demande d'enregistrer, le drapeau appartient au
        // bon geste : un arrêt **antérieur** est oublié, un arrêt **postérieur** est honoré. C'est
        // la seule lecture qui suit l'intention.
        arretDemande = false
        refuserSiInterdite()
        if (!permissionAccordee()) {
            throw SttPermissionDeniedException("permission RECORD_AUDIO non accordee")
        }
        // 🔴🔴 **Le fichier est retenu ICI, et effacé si le résultat ne parvient pas à l'appelant.**
        //
        // Défaut CONFIRMÉ, relevé par une relecture externe (GPT-5.5, 2026-08-16), et c'est le même
        // que celui du 08-15 déplacé d'un cran : la garde posée alors — un contrôle d'annulation à
        // chaque tour de boucle — couvre la durée de la capture, et **rien après**. Or `withContext`
        // vérifie l'annulation **au moment de rendre sa valeur** : la portée annulée pendant la
        // réécriture de l'en-tête faisait sortir une `CancellationException` à la place du fichier.
        // `capturer()` s'était terminé normalement, son `try/catch` n'avait donc rien effacé, et
        // l'appelant — qui devait transcrire puis supprimer — ne recevait jamais le nom du fichier.
        //
        // État final : un WAV de voix en clair dans le cache, que plus personne ne connaît. Une
        // fenêtre de quelques millisecondes, mais ouverte par le geste le plus banal qui soit —
        // quitter l'écran au moment où l'on relâche le bouton.
        //
        // ⚠️ `AtomicReference` et non une simple variable : l'écriture a lieu sur le fil d'E/S, la
        // lecture sur celui de l'appelant. La machinerie des coroutines établit probablement la
        // relation de précédence, mais « probablement » ne convient pas pour décider d'effacer un
        // fichier de voix.
        val produit = AtomicReference<Capture?>(null)
        try {
            return verrou.withLock { withContext(Dispatchers.IO) { capturer()?.also(produit::set) } }
        } catch (e: Throwable) {
            produit.get()?.fichier?.let(::effacerOuSignaler)
            throw traduire(e)
        }
    }

    /**
     * 🔴 **Rien ne sort d'ici hors de [SttException]** — sauf une annulation, qui n'est pas un échec.
     *
     * ⚠️ L'écriture du WAV passe par `RandomAccessFile` : cache plein, support retiré, permission
     * révoquée, et c'est une `IOException` brute qui traversait une fonction dont la documentation
     * ne promet que deux types. Un `when` exhaustif chez l'appelant l'aurait laissée filer — le
     * défaut que `SpeechToText.transcribeFile` a déjà eu à documenter. Relevé par une relecture
     * externe (GPT-5.5, 2026-08-16), en même temps que son jumeau dans l'import du modèle.
     */
    private fun traduire(e: Throwable): Throwable = when (e) {
        is CancellationException -> e
        is SttException -> e
        else -> SttRecordingFailedException("capture interrompue : ${e::class.java.simpleName}", cause = e)
    }

    /** Demande l'arrêt de la capture en cours. Sans effet s'il n'y en a pas. */
    fun arreter() {
        arretDemande = true
    }

    /**
     * Coupe la capture **et interdit les suivantes**. Réservé au mode panique.
     *
     * ## 🔴 Pourquoi [arreter] seul ne suffit pas
     *
     * [arretDemande] est remis à `false` par [enregistrer], au tout début et **avant le verrou** —
     * pour que le drapeau appartienne au geste en cours et non au précédent. Cette remise à zéro,
     * qui est juste dans l'usage ordinaire, ouvre une porte au mode panique : une capture qui a
     * franchi cette ligne et attend le verrou repartira dès que la précédente le libère, y compris
     * après le passage de l'étape qui devait couper le micro. Une nouvelle demande arrivée après
     * cette étape en ferait autant.
     *
     * L'interdiction est donc un **état**, pas un drapeau de geste : rien ne l'efface, et c'est
     * exact — le mode panique est sans retour, et le modèle de transcription part quelques étapes
     * plus loin de toute façon.
     *
     * ⚠️ C'est le motif « garde posée sur le geste et non sur l'accès », déjà payé dans ce dépôt.
     */
    fun couperEtInterdire() {
        interdite = true
        arreter()
    }

    /**
     * Attend que la capture soit effectivement terminée, au plus [millisecondesMax].
     *
     * ## ⚠️⚠️ Pourquoi l'attente est séparée de la demande
     *
     * [arreter] pose un drapeau et rend la main immédiatement ; la boucle ne le lit qu'en sortant de
     * `micro.read`, qui bloque le temps d'un tampon. Il s'écoule donc un court instant pendant lequel
     * la capture **écrit encore** — et c'est exactement l'instant où le mode panique voudrait
     * supprimer le répertoire.
     *
     * Les deux gestes sont séparés parce qu'ils vont à deux endroits différents de la séquence de
     * panique : le drapeau tout au début, pour que le micro cesse d'alimenter le disque sans faire
     * attendre le reste ; l'attente juste avant l'effacement, qui est le seul point où elle sert.
     * Les réunir obligerait à choisir entre retarder le presse-papiers et effacer sous une écriture
     * en cours.
     *
     * @return `true` si la capture est arrêtée, `false` si le délai a expiré. ⚠️ L'appelant efface
     *   **quand même** : un `false` dit qu'un fichier peut réapparaître après coup, pas qu'il ne faut
     *   rien tenter.
     */
    suspend fun attendreLArret(millisecondesMax: Long): Boolean {
        arreter()
        return withTimeoutOrNull(millisecondesMax) {
            // ⚠️ Un `StateFlow` émet sa valeur courante à la souscription : si la capture est déjà
            // arrêtée — le cas ordinaire — ceci rend la main sans attendre.
            etat.first { it == Etat.ARRETEE }
            true
        } ?: false
    }

    /**
     * ⚠️ Un échec, pas un `null`. Rendre `null` dirait « rien n'a été enregistré parce que vous
     * n'avez rien dit » ; c'est le refus d'un système qui a été mis à l'arrêt, et les deux ne se
     * classent pas ensemble — même règle que pour l'appui bref.
     */
    private fun refuserSiInterdite() {
        if (interdite) {
            throw SttRecordingFailedException("dictee coupee par le mode panique")
        }
    }

    fun permissionAccordee(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun capturer(): Capture? {
        // 🔴 **Contrôlée une SECONDE fois, ici, après le verrou.** Le contrôle de [enregistrer] a
        // lieu avant l'attente du verrou : une capture qui l'a franchi et patiente derrière une
        // autre ne l'a jamais revu. C'est ici, et ici seulement, que l'interdiction garantit
        // qu'aucun micro ne s'ouvre — les deux contrôles servent, et le premier ne fait qu'éviter
        // une attente inutile.
        refuserSiInterdite()

        val tailleMin = AudioRecord.getMinBufferSize(FREQUENCE_HZ, CANAL, ENCODAGE)
        if (tailleMin <= 0) {
            throw SttRecordingFailedException("le materiel refuse 16 kHz mono 16 bits")
        }
        // ⚠️ Un tampon au minimum syndical déborde dès que le fil de lecture est préempté, et
        // Android jette alors les échantillons **en silence**. Le facteur est le prix d'une capture
        // sans trous.
        val tailleTampon = tailleMin * FACTEUR_DE_TAMPON

        val fichier = fichierNeuf()

        // ⚠️ Aucun effacement ici : à ce stade [fichierNeuf] n'a produit qu'un **objet** `File`, et
        // rien n'existe encore sur le disque — c'est `RandomAccessFile` qui crée le fichier. Le
        // commentaire précédent affirmait le contraire et faisait effacer un fichier inexistant.
        // Relevé CONFIRMÉ par une relecture externe (GPT-5.2, 2026-08-15).
        val micro = ouvrirLeMicro(tailleTampon)

        enregistreur = micro
        _etat.value = Etat.EN_COURS
        return try {
            val octets = ecrireLeWav(micro, fichier, tailleTampon)
            if (octets > 0L) {
                Capture(fichier, finDeCapture(octets))
            } else {
                // Arrêt demandé avant le premier échantillon : rien à transcrire, et rien à garder.
                effacerOuSignaler(fichier)
                null
            }
        } catch (e: Throwable) {
            // ⚠️ `Throwable` : une annulation doit effacer l'audio elle aussi, puis repartir.
            effacerOuSignaler(fichier)
            throw e
        } finally {
            _etat.value = Etat.ARRETEE
            _niveau.value = 0f
            _secondes.value = 0
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

    private suspend fun ecrireLeWav(micro: AudioRecord, fichier: File, tailleTampon: Int): Long {
        val tampon = ByteArray(tailleTampon)
        var octetsEcrits = 0L
        var lecturesVides = 0

        RandomAccessFile(fichier, "rw").use { sortie ->
            // 🔴 **Tronquer avant d'écrire.** `RandomAccessFile` en mode « rw » n'efface pas ce qui
            // existe : si un fichier du même nom se trouvait là — deux captures dans la même
            // milliseconde, une horloge qui recule — les octets de la capture **précédente**
            // survivraient au-delà des nouvelles données. L'en-tête ne les annoncerait pas, aucun
            // lecteur ne les jouerait, et ils seraient pourtant physiquement là : de la voix.
            // Relevé CONFIRMÉ par une relecture externe (GPT-5.2, 2026-08-15).
            sortie.setLength(0)
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
                // 🔴🔴 **L'annulation est vérifiée ICI, à chaque tour.**
                //
                // `micro.read` est un appel bloquant : il ne connaît pas les coroutines et ne
                // s'interrompt pas. Sans ce contrôle, une portée annulée laissait la boucle tourner
                // jusqu'à la borne de deux minutes — micro ouvert, voix écrite sur le disque — puis
                // `withContext` levait l'annulation **après** le retour de la fonction, donc
                // **hors** du `try` qui efface. Le fichier survivait, et le commentaire de classe
                // promettait exactement le contraire.
                //
                // `ensureActive()` lève depuis l'intérieur du `try` : le fichier est effacé, le
                // micro relâché par le `finally`, et l'annulation repart telle quelle.
                // Relevé CONFIRMÉ par les DEUX relectures externes (2026-08-15).
                currentCoroutineContext().ensureActive()

                val lus = micro.read(tampon, 0, tampon.size)
                if (lus < 0) throw SttRecordingFailedException("lecture micro en echec : code $lus")

                // 🔴 **Le tampon d'une coupure de panique n'est PAS écrit.**
                //
                // `micro.read` bloque le temps d'un tampon et ignore tout ce qui se passe pendant ce
                // temps-là. Le tampon qu'il finit par rendre contient donc de la voix captée
                // **pendant** le déclenchement de la panique — et sans ce contrôle, elle partait sur
                // le disque avant que la condition de boucle ne soit seulement relue. Lever ici fait
                // effacer le fichier par le `catch` de [capturer], immédiatement, au lieu d'attendre
                // l'étape de purge quelques rangs plus loin. Relevé par une relecture externe
                // (GPT-5.5, 2026-08-16).
                //
                // ⚠️⚠️ **Sur `interdite`, jamais sur `arretDemande`.** Un arrêt ordinaire doit au
                // contraire écrire ce dernier tampon : c'est la fin de la phrase de l'utilisateur, et
                // la jeter couperait le dernier mot de chaque dictée. Deux arrêts, deux traitements.
                //
                // ⚠️ On ne va PAS jusqu'à arrêter l'`AudioRecord` depuis le fil de la panique, comme
                // la relecture le proposait aussi : `stop()` croisant le `release()` du `finally` est
                // un appel natif sur un objet en cours de libération, dans le seul chemin du code qui
                // n'a pas le droit de planter. Le gain se compte en fractions de seconde sur un
                // fichier de toute façon effacé ici même ; le risque, lui, est un plantage en pleine
                // panique.
                if (interdite) throw SttRecordingFailedException("dictee coupee par le mode panique")

                if (lus > 0) {
                    sortie.write(tampon, 0, lus)
                    octetsEcrits += lus
                    lecturesVides = 0
                    _niveau.value = WavPcm16.niveau(tampon, lus)
                    // ⚠️ Mis à jour **au même endroit** que le compteur d'octets, et dérivé de lui : voir
                    // [secondes]. Une seule source, donc pas d'écart possible avec la borne.
                    _secondes.value = (octetsEcrits / OCTETS_PAR_SECONDE).toInt()
                } else {
                    // ⚠️ Une lecture à zéro octet ne devrait pas arriver en mode bloquant, et
                    // certaines implémentations le font quand même. Sans compteur, la boucle
                    // tournerait **sans fin** : ni `arretDemande` ni la borne d'octets ne bougent,
                    // et le `finally` qui relâche le micro n'est jamais atteint. Signalé PROBABLE
                    // par une relecture externe (GPT-5.2) ; le coût du garde-fou est un entier.
                    lecturesVides++
                    if (lecturesVides >= LECTURES_VIDES_MAX) {
                        throw SttRecordingFailedException("le micro ne rend plus d'echantillons")
                    }
                }
            }

            // ⚠️ Les tailles corrigées **en dernier** : un fichier interrompu avant cette ligne porte
            // un en-tête annonçant zéro donnée. C'est voulu — il ne se lit pas comme un audio
            // valide, et personne ne le prendra pour une capture exploitable.
            sortie.seek(0)
            sortie.write(WavPcm16.entete(donneesOctets = octetsEcrits))
        }
        return octetsEcrits
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

    /**
     * Un fichier qui n'existe pas encore.
     *
     * ⚠️ **L'horodatage seul ne suffit pas.** Deux captures lancées dans la même milliseconde — ou
     * une horloge système qui recule — produisaient le même nom, donc l'écriture par-dessus une
     * capture précédente. Même parade que `NoteExporter.preparerRepertoire` : on cherche un nom
     * libre plutôt que de faire confiance à l'instant. La troncature de [ecrireLeWav] est la
     * seconde barrière ; les deux servent, et aucune ne remplace l'autre.
     */
    private fun fichierNeuf(): File {
        val racine = repertoireDeCapture(context)
        racine.mkdirs()
        val instant = clock.millis()
        var candidat = File(racine, "capture-$instant.wav")
        var suffixe = 1
        while (candidat.exists()) {
            candidat = File(racine, "capture-$instant-$suffixe.wav")
            suffixe++
        }
        return candidat
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
            val racine = repertoireDeCapture(context)
            // ⚠️ Le retour de `deleteRecursively` était jeté. Une purge qui échoue laissait des
            // enregistrements **en silence**, sur le seul chemin dont le rôle est de n'en laisser
            // aucun. Même règle que pour l'export : un contrôle qui ne regarde pas son résultat
            // n'en dit rien. Signalé par une relecture externe (GPT-5.2, 2026-08-15).
            if (racine.exists() && !racine.deleteRecursively()) {
                Timber.e("captures : le repertoire %s n'a PAS pu etre purge", racine.name)
            }
        }

        /** ⚠️ Une seule définition du format, dans le domaine : `WavPcm16`. */
        const val FREQUENCE_HZ = WavPcm16.FREQUENCE_HZ
        private const val CANAL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODAGE = AudioFormat.ENCODING_PCM_16BIT
        private const val FACTEUR_DE_TAMPON = 4

        /**
         * Nombre de lectures vides consécutives tolérées avant d'abandonner.
         *
         * ⚠️ La valeur importe peu ; ce qui compte est qu'elle soit **finie**. Voir la boucle.
         */
        private const val LECTURES_VIDES_MAX = 50

        /**
         * Pourquoi la boucle s'est arrêtée, lu sur **le compteur qui l'a fait sortir**.
         *
         * 🔴 **Extraite pour être mesurable.** Elle vivait en une ligne dans `capturer()`, une fonction
         * privée et suspendue qui exige un `AudioRecord` réel : la règle la plus facile à écrire de
         * travers du fichier était aussi la seule qu'aucun test ne pouvait atteindre. Même idiome que
         * `gesteDuMicro` et `GesteDeDossier`.
         *
         * ⚠️ **`>=` et non `==`.** Le dernier tampon lu peut faire dépasser la borne : `micro.read`
         * rend ce qu'il a, pas ce qu'on aurait voulu. Un `==` classerait une capture bornée comme un
         * arrêt au doigt, c'est-à-dire exactement le silence qu'on répare ici.
         *
         * ⚠️ **Un drapeau posé dans la boucle aurait un second endroit où se tromper** — celui qui le
         * pose et celui qui le lit. Le nombre d'octets écrits, lui, **est** la condition de sortie.
         */
        internal fun finDeCapture(octetsEcrits: Long): FinDeCapture =
            if (octetsEcrits >= OCTETS_MAX) FinDeCapture.BORNE_DE_DUREE else FinDeCapture.GESTE

        /** ⚠️ Une seule définition du débit : 16 kHz × 2 octets, mono. */
        private const val OCTETS_PAR_SECONDE = FREQUENCE_HZ.toLong() * 2

        /**
         * La borne de durée, **en secondes et publique**.
         *
         * 🔴 **L'écran doit pouvoir la NOMMER.** Elle s'appliquait en silence : rien ne distinguait
         * « la limite est atteinte » de « l'utilisateur a appuyé sur Arrêter », si bien qu'on dictait trois
         * minutes, qu'il en manquait une, et qu'aucun moyen ne permettait de l'apprendre
         * (`04-PIEGES.md` §96). Une borne qu'on ne peut pas afficher est une borne qui trahit.
         */
        const val DUREE_MAX_SECONDES = 120

        /**
         * Deux minutes d'audio, soit environ 3,8 Mo.
         *
         * ⚠️ **Une borne, parce qu'un micro qui reste ouvert est un micro qui reste ouvert.** Un
         * appui manqué sur « arrêter », une application mise en arrière-plan sans que le geste
         * aboutisse, et la capture continuerait tant que le processus vit — en écrivant la voix de
         * l'utilisateur sur le disque. La transcription d'une dictée de note ne dure pas deux
         * minutes ; ce qui dépasse est presque sûrement un accident.
         */
        private const val OCTETS_MAX = OCTETS_PAR_SECONDE * DUREE_MAX_SECONDES
    }
}
