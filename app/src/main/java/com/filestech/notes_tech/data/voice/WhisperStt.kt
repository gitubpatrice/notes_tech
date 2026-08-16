package com.filestech.notes_tech.data.voice

import com.filestech.notes_tech.domain.voice.SpeechToText
import com.filestech.notes_tech.domain.voice.SttEngineUnavailableException
import com.filestech.notes_tech.domain.voice.SttModel
import com.filestech.notes_tech.domain.voice.SttModelChecksumMismatchException
import com.filestech.notes_tech.domain.voice.SttModelMissingException
import com.filestech.notes_tech.domain.voice.SttSegment
import com.filestech.notes_tech.domain.voice.SttTranscription
import com.filestech.notes_tech.domain.voice.SttTranscriptionFailedException
import com.filestech.notes_tech.domain.voice.WavPcm16
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La transcription, par whisper.cpp compilé dans l'application.
 *
 * ## 🔴 Le modèle est revérifié à CHAQUE chargement, pas seulement à l'import
 *
 * `SttModel.expectedSha256` le dit, et c'est ici que la promesse se tient. Un fichier peut changer
 * entre l'import et le chargement — corruption disque, remplacement, restauration d'une sauvegarde
 * — et ce qu'on s'apprête à faire de lui n'est pas anodin : le donner à une bibliothèque native qui
 * l'interprétera, dans le processus qui détient les clés des notes. Relire cinquante mégaoctets
 * coûte une seconde et demie sur l'appareil de test le plus modeste ; c'est le prix, et il est payé
 * à chaque ouverture, pas une fois pour toutes.
 *
 * ⚠️ Un modèle non conforme est **supprimé**, comme à l'import et pour la même raison : le garder
 * inviterait à réessayer avec exactement les mêmes octets.
 *
 * ## ⚠️ Ce qui traverse la frontière native, et ce qui n'y va pas
 *
 * Les échantillons — des flottants — et rien d'autre. Le fichier WAV est décodé **en Kotlin**, par
 * `WavPcm16`. Cf. la note de `notes_stt_jni.cpp` : un analyseur de format en C, sur un fichier venu
 * du disque, serait une surface d'attaque pour un besoin qu'on n'a pas.
 */
@Singleton
class WhisperStt @Inject constructor(private val modeles: SttModelStore) : SpeechToText {

    /**
     * Un seul appel natif à la fois.
     *
     * ⚠️ `whisper_full` est documenté **non réentrant pour un même contexte**. Le verrou n'est donc
     * pas une politesse de performance : deux transcriptions simultanées corrompraient l'état du
     * moteur, et le symptôme serait du texte faux plutôt qu'un plantage.
     */
    private val verrou = Mutex()

    private val natif = WhisperNatif()

    @Volatile
    private var poignee: Long = 0L

    @Volatile
    private var modeleCharge: SttModel? = null

    override val isInitialized: Boolean get() = poignee != 0L

    override val loadedModel: SttModel? get() = modeleCharge

    override suspend fun initialize(model: SttModel) = verrou.withLock {
        // Idempotence : le même modèle déjà chargé, il n'y a rien à faire. Cf. le contrat.
        if (poignee != 0L && modeleCharge == model) return@withLock

        // ⚠️ Rappelée avec un modèle **différent**, elle remplace — le contrat le dit. Le précédent
        // part d'abord : deux modèles chargés, ce sont deux fois plusieurs dizaines de mégaoctets
        // de mémoire native, sur des appareils qui n'en ont pas le double.
        libererSousVerrou()

        if (!WhisperNatif.disponible) {
            throw SttEngineUnavailableException("bibliotheque native de transcription indisponible")
        }

        val fichier = modeles.fichierDuModele(model)
        if (!fichier.isFile) {
            throw SttModelMissingException("le modele \"${model.id}\" n'est pas installe")
        }
        // 🔴 La revérification. Voir la note de classe.
        if (!modeles.estInstalle(model)) {
            modeles.desinstaller(model)
            throw SttModelChecksumMismatchException(
                "empreinte du modele \"${model.id}\" non conforme ; le fichier a ete supprime",
            )
        }

        val nouvelle = withContext(Dispatchers.Default) { natif.ouvrir(fichier.absolutePath) }
        if (nouvelle == 0L) {
            throw SttEngineUnavailableException("le modele \"${model.id}\" n'a pas pu etre charge")
        }
        poignee = nouvelle
        modeleCharge = model
    }

    override suspend fun transcribeFile(audioPath: String, language: String?): SttTranscription {
        // ⚠️ Lu **hors** du verrou : lire le fichier ne touche pas au moteur, et le faire sous
        // verrou ferait attendre une transcription en cours pour une lecture de disque.
        val echantillons = lireLesEchantillons(File(audioPath))

        return verrou.withLock {
            val courante = poignee
            if (courante == 0L) {
                // ⚠️ Ce cas est dans la hiérarchie scellée **exprès**. Une `IllegalStateException`
                // aurait traversé un `when` exhaustif chez l'appelant, pour l'échec le plus banal
                // de tous — le moteur pas encore chargé. Cf. le contrat.
                throw SttEngineUnavailableException("moteur non initialise")
            }
            transcrireSousVerrou(courante, echantillons, language)
        }
    }

    override suspend fun dispose() = verrou.withLock { libererSousVerrou() }

    // ---------------------------------------------------------------------------------------------

    private suspend fun transcrireSousVerrou(
        poigneeCourante: Long,
        echantillons: FloatArray,
        langue: String?,
    ): SttTranscription {
        val duree = (echantillons.size.toLong() * 1000) / WavPcm16.FREQUENCE_HZ

        val code = withContext(Dispatchers.Default) {
            // 🔴🔴 **L'annulation est transmise au moteur natif, pas seulement attendue.**
            //
            // `whisper_full` est un appel bloquant de plusieurs secondes qui ignore les coroutines.
            // Sans ce relais, une portée annulée — l'utilisateur quitte l'écran, le mode panique
            // démarre — laisserait tourner un calcul intensif sur un appareil que l'utilisateur
            // croit avoir libéré, et **tiendrait le fichier audio en clair** pendant tout ce temps.
            // C'est la troisième fois que ce motif se présente dans ce dépôt : `micro.read`,
            // `InputStream.read`, et maintenant ici.
            //
            // ⚠️ Le rappel est posé sur le `Job` courant, et **retiré au retour** : un `Job` de
            // portée longue accumulerait sinon un rappel par transcription.
            val abonnement = currentCoroutineContext().job.invokeOnCompletion {
                natif.demanderArret(poigneeCourante)
            }
            try {
                natif.transcrire(poigneeCourante, echantillons, langue.orEmpty(), filsDeCalcul())
            } finally {
                abonnement.dispose()
            }
        }

        // ⚠️ **Avant de lire le résultat.** Une transcription interrompue laisse le moteur dans un
        // état dont les segments ne veulent rien dire ; les rendre à l'appelant serait pire que de
        // ne rien rendre, puisqu'il les prendrait pour du texte dicté.
        currentCoroutineContext().ensureActive()

        if (code == WhisperNatif.CODE_ARRET) {
            // L'annulation a été honorée mais la portée est, elle, toujours vivante : le cas d'un
            // arrêt demandé par le mode panique. Ce n'est pas une panne, mais il n'y a rien à rendre.
            throw CancellationException("transcription interrompue")
        }
        if (code != 0) {
            throw SttTranscriptionFailedException("le moteur a echoue (code $code)")
        }

        val segments = (0 until natif.nombreDeSegments(poigneeCourante)).map { index ->
            SttSegment(
                text = natif.texteDuSegment(poigneeCourante, index),
                startMillis = natif.debutDuSegment(poigneeCourante, index),
                endMillis = natif.finDuSegment(poigneeCourante, index),
            )
        }

        return SttTranscription(
            // ⚠️ Rognée : whisper préfixe chaque segment d'une espace. Concaténés tels quels, ils
            // donnent un texte qui commence par un blanc — inséré tel quel dans la note.
            text = segments.joinToString("") { it.text }.trim(),
            segments = segments,
            detectedLanguage = natif.langueDetectee(poigneeCourante),
            durationMillis = duree,
        )
    }

    private suspend fun lireLesEchantillons(fichier: File): FloatArray = withContext(Dispatchers.IO) {
        try {
            WavPcm16.echantillons(fichier.readBytes())
        } catch (e: IOException) {
            throw SttTranscriptionFailedException("audio illisible", cause = e)
        } catch (e: IllegalArgumentException) {
            // Le décodeur n'accepte que ce que la capture produit. Un refus ici veut dire que le
            // fichier n'en vient pas — ou qu'il a été tronqué.
            throw SttTranscriptionFailedException("audio dans un format inattendu", cause = e)
        } catch (e: OutOfMemoryError) {
            // ⚠️ Une `Error`, donc invisible à un `catch (e: Exception)`. Deux minutes d'audio font
            // près de quatre mégaoctets d'octets **plus** sept de flottants ; sur un appareil déjà
            // à l'étroit, c'est un échec réaliste, et il doit se dire au lieu de tuer l'application.
            throw SttTranscriptionFailedException("memoire insuffisante pour lire l'audio", cause = e)
        }
    }

    /** ⚠️ Doit être appelée sous [verrou]. */
    private fun libererSousVerrou() {
        val courante = poignee
        poignee = 0L
        modeleCharge = null
        if (courante != 0L) natif.fermer(courante)
    }

    private companion object {
        /**
         * Combien de fils donner au moteur.
         *
         * ⚠️ **Jamais tous.** Whisper sature ce qu'on lui donne ; prendre tous les cœurs fait ramer
         * l'interface pendant la transcription et, sur un appareil modeste, déclenche la limitation
         * thermique — ce qui rend le calcul plus lent, pas plus rapide. La borne haute vaut aussi
         * pour les appareils à huit cœurs, où les quatre « petits » n'apportent rien à ce calcul.
         */
        fun filsDeCalcul(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
}
