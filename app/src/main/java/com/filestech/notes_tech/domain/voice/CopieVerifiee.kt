package com.filestech.notes_tech.domain.voice

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Ce qu'une copie vérifiée a produit.
 *
 * @param empreinteSha256 en hexadécimal **minuscule**, comme [SttModel.expectedSha256]. La casse est
 *   fixée ici plutôt que laissée à l'appelant : deux comparaisons de chaînes, l'une sensible à la
 *   casse et l'autre non, dans deux fichiers différents, c'est la façon dont un contrôle de sécurité
 *   devient un contrôle décoratif.
 *
 *   ⚠️⚠️ **Et les appelants comparent bel et bien au caractère près.** Ils ne le faisaient pas : ils
 *   passaient `ignoreCase = true`, ce qui annulait exactement l'effort décrit ci-dessus. Sans
 *   conséquence aujourd'hui — le catalogue est en minuscules, et un test le vérifie entrée par
 *   entrée — mais c'était une promesse que le code ne tenait pas, et la tolérance aurait couvert le
 *   jour où une seule des deux normalisations aurait bougé. Relevé par une relecture externe
 *   (Gemini, 2026-08-16).
 */
data class CopieVerifieeResultat(val empreinteSha256: String, val octets: Long)

/**
 * Copier un flux en calculant son empreinte **dans le même passage**.
 *
 * ## Pourquoi cette fonction est ici, et pas dans le magasin de modèles
 *
 * Même raison que [WavPcm16] face à `VoiceCapture` : ce qui se vérifie à la valeur doit pouvoir être
 * vérifié sans appareil. Une empreinte SHA-256 est exactement ça — une valeur connue pour une entrée
 * connue. Ici il n'y a ni contexte Android, ni fichier, ni `Uri` : deux flux, donc un test JVM qui
 * tourne à chaque gate.
 *
 * ## 🔴 Un seul passage, et ce n'est pas une optimisation
 *
 * Copier puis relire pour hacher, ce serait lire deux fois plusieurs dizaines de mégaoctets — mais
 * surtout, ce serait hacher **autre chose que ce qui a été écrit**. Entre les deux passages, la
 * source peut changer : elle appartient à une autre application, et un `content://` n'est pas un
 * instantané. Hacher au vol garantit que l'empreinte rendue décrit les octets réellement posés dans
 * le fichier de destination, ce qui est la seule propriété dont la vérification a besoin.
 *
 * ⚠️ La borne [octetsMax] n'est pas un garde-fou de confort. La source est un fournisseur de contenu
 * **tiers** : n'importe quelle application peut en exporter un, et rien n'oblige ce qu'il annonce à
 * correspondre à ce qu'il sert. Sans borne, un flux sans fin remplirait le stockage privé jusqu'à ce
 * que l'appareil ne réponde plus.
 */
object CopieVerifiee {

    /** 64 Ko : assez grand pour que l'appel système ne domine pas, assez petit pour rester en cache. */
    const val TAILLE_DU_BLOC = 64 * 1024

    /**
     * Au plus une notification de progression par tranche.
     *
     * ⚠️ Sans ce pas, un import de 57 Mo produirait près de mille appels — donc mille recompositions
     * si l'appelant est un écran. Le débit est bien plus utile à l'utilisateur qu'une précision au
     * bloc près.
     */
    const val PAS_DE_PROGRESSION = 512L * 1024

    /**
     * Nombre de lectures vides consécutives tolérées.
     *
     * ⚠️ Le contrat d'[InputStream.read] exclut ce cas pour un tampon non vide — il bloque jusqu'à
     * ce qu'un octet arrive, la fin du flux, ou une erreur. Le compteur est là parce que la source
     * est **écrite par quelqu'un d'autre** : un fournisseur de contenu tiers peut ne pas respecter
     * le contrat, et une boucle qui n'avance pas ne se termine jamais. Même parade que la boucle de
     * capture, pour la même raison, et pour le prix d'un entier.
     */
    private const val LECTURES_VIDES_MAX = 50

    /**
     * Copie [source] dans [cible] et rend l'empreinte de ce qui a été écrit.
     *
     * ⚠️ **Ne ferme aucun des deux flux** : ils appartiennent à l'appelant, qui seul sait s'il doit
     * en plus forcer l'écriture sur le disque. Fermer ici retirerait cette possibilité.
     *
     * ⚠️⚠️ **L'annulation est vérifiée à chaque bloc.** [InputStream.read] est bloquant et ignore les
     * coroutines : sans ce contrôle, une portée annulée continuerait de copier jusqu'au bout, et
     * l'annulation ne se manifesterait qu'**après** le retour de la fonction — c'est-à-dire hors du
     * `try` de l'appelant qui efface le fichier partiel. C'est exactement le défaut trouvé dans la
     * boucle de capture le 2026-08-15 ; il ne sera pas réintroduit ici.
     *
     * @param octetsMax refus au-delà, **exclusivement d'après ce que l'appelant attend** — jamais
     *   d'après ce que la source annonce.
     * @param onProgress appelé avec le nombre d'octets déjà traités, au plus tous les
     *   [PAS_DE_PROGRESSION], puis une dernière fois à la fin. ⚠️ Appelé **sur le fil de la copie** :
     *   un appelant qui touche l'interface doit repasser sur le fil principal lui-même.
     * @throws SttModelSourceInvalidException le flux dépasse [octetsMax], ou n'avance plus.
     * @throws java.io.IOException la lecture ou l'écriture a échoué.
     */
    suspend fun copier(
        source: InputStream,
        cible: OutputStream,
        octetsMax: Long,
        onProgress: ((Long) -> Unit)? = null,
    ): CopieVerifieeResultat {
        require(octetsMax > 0) { "borne de copie invalide : $octetsMax" }

        val empreinte = MessageDigest.getInstance("SHA-256")
        val bloc = ByteArray(TAILLE_DU_BLOC)
        var total = 0L
        var dernierSignale = 0L
        var lecturesVides = 0

        while (true) {
            currentCoroutineContext().ensureActive()

            val lus = source.read(bloc)
            // ⚠️ La fin du flux est la **seule** sortie de boucle : tout le reste est soit une
            // exception, soit un tour de plus. Un unique point de sortie, c'est ce qui permet de
            // lire la condition d'arrêt sans parcourir le corps — et ce que detekt exige ici.
            if (lus < 0) break

            if (lus == 0) {
                lecturesVides++
                if (lecturesVides >= LECTURES_VIDES_MAX) {
                    throw SttModelSourceInvalidException("la source ne rend plus d'octets")
                }
            } else {
                lecturesVides = 0

                // 🔴 La borne est contrôlée **avant** d'écrire, pas après. Contrôlée après, le bloc
                // de trop serait déjà sur le disque au moment où l'exception part.
                if (total + lus > octetsMax) {
                    throw SttModelSourceInvalidException(
                        "la source depasse la taille attendue : plus de $octetsMax octets",
                    )
                }

                cible.write(bloc, 0, lus)
                empreinte.update(bloc, 0, lus)
                total += lus

                if (onProgress != null && total - dernierSignale >= PAS_DE_PROGRESSION) {
                    dernierSignale = total
                    onProgress(total)
                }
            }
        }

        // ⚠️ Un dernier appel inconditionnel : sans lui, une copie de 57,4 Mo signalerait 57,0 et une
        // barre de progression resterait figée juste avant la fin, là où l'utilisateur attend.
        onProgress?.invoke(total)
        return CopieVerifieeResultat(empreinteSha256 = enHexadecimal(empreinte.digest()), octets = total)
    }

    /**
     * L'empreinte d'un flux, sans le copier.
     *
     * Sert à re-vérifier un modèle **déjà installé** : le contrôle a lieu à chaque chargement, pas
     * seulement à l'import, parce qu'un fichier peut être remplacé entre les deux.
     */
    suspend fun empreinteDe(source: InputStream, octetsMax: Long): CopieVerifieeResultat =
        copier(source, FluxIgnorant, octetsMax)

    /** Le puits de la vérification sans copie. Aucune allocation, aucun octet conservé. */
    private object FluxIgnorant : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private fun enHexadecimal(octets: ByteArray): String {
        val chiffres = "0123456789abcdef"
        val sortie = StringBuilder(octets.size * 2)
        for (octet in octets) {
            val valeur = octet.toInt() and 0xFF
            sortie.append(chiffres[valeur ushr 4]).append(chiffres[valeur and 0x0F])
        }
        return sortie.toString()
    }
}
