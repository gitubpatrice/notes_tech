package com.filestech.notes_tech.domain.voice

import kotlin.math.sqrt

/**
 * Le format WAV PCM 16 bits mono, en fonctions **pures**.
 *
 * ## Pourquoi ce n'est pas dans la classe de capture
 *
 * Même raison que `NoteMarkdown` face à `NoteExporter` : un en-tête binaire se vérifie **octet par
 * octet**, et il ne le sera jamais s'il faut un micro pour l'atteindre. Ici il n'y a ni contexte
 * Android, ni matériel, ni fichier — donc un test JVM qui tourne à chaque gate.
 *
 * ⚠️ Le format n'est pas un choix esthétique : c'est le seul pour lequel [SpeechToText] promet un
 * comportement. 16 kHz, un canal, 16 bits signés.
 */
object WavPcm16 {

    const val FREQUENCE_HZ = 16_000
    const val CANAUX = 1
    const val BITS_PAR_ECHANTILLON = 16

    /** Un en-tête WAV canonique fait 44 octets — 12 pour `RIFF`, 24 pour `fmt `, 8 pour `data`. */
    const val TAILLE_ENTETE = 44

    /**
     * L'en-tête décrivant [donneesOctets] octets de données.
     *
     * ⚠️⚠️ **Tout est petit-boutiste, y compris sur une machine qui ne l'est pas.** Le format le
     * fixe ; suivre l'ordre de l'appareil produirait un fichier valide sur l'appareil de test et
     * illisible ailleurs — le genre de défaut qu'aucun essai local ne révèle.
     *
     * ⚠️ Appelé **deux fois** par une capture : une première avec `0`, avant de connaître la durée,
     * puis une seconde à la fin pour corriger. Un fichier interrompu entre les deux annonce donc
     * zéro donnée : un lecteur ordinaire n'en tirera rien. C'est voulu — mieux vaut un fichier
     * franchement invalide qu'un fichier qui se lit à moitié.
     *
     * ⚠️⚠️ **Ça ne veut pas dire que les octets ne sont plus là.** La première rédaction disait
     * « aucun lecteur ne le prendra pour un audio exploitable », ce qui laissait entendre une
     * protection du contenu : un outil qui balaie les octets bruts lira la voix quand même. La seule
     * garantie de confidentialité est l'**effacement** du fichier, pas la forme de son en-tête.
     * Relevé par une relecture externe (GPT-5.2, 2026-08-15).
     */
    fun entete(donneesOctets: Long): ByteArray {
        require(donneesOctets >= 0) { "taille de donnees negative : $donneesOctets" }
        val octetsParEchantillon = BITS_PAR_ECHANTILLON / 8
        val entete = ByteArray(TAILLE_ENTETE)

        fun ascii(position: Int, texte: String) {
            texte.forEachIndexed { i, c -> entete[position + i] = c.code.toByte() }
        }

        fun entier32(position: Int, valeur: Long) {
            for (i in 0 until 4) entete[position + i] = ((valeur shr (8 * i)) and 0xFF).toByte()
        }

        fun entier16(position: Int, valeur: Int) {
            for (i in 0 until 2) entete[position + i] = ((valeur shr (8 * i)) and 0xFF).toByte()
        }

        ascii(0, "RIFF")
        // La taille annoncée par RIFF exclut ses propres huit premiers octets.
        entier32(4, donneesOctets + TAILLE_ENTETE - 8)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        entier32(16, 16) // longueur du bloc fmt pour du PCM sans extension
        entier16(20, 1) // 1 = PCM non compressé
        entier16(22, CANAUX)
        entier32(24, FREQUENCE_HZ.toLong())
        entier32(28, (FREQUENCE_HZ * CANAUX * octetsParEchantillon).toLong()) // octets par seconde
        entier16(32, CANAUX * octetsParEchantillon) // alignement d'un bloc
        entier16(34, BITS_PAR_ECHANTILLON)
        ascii(36, "data")
        entier32(40, donneesOctets)
        return entete
    }

    /**
     * Le niveau sonore d'un bloc d'échantillons, entre `0` et `1`.
     *
     * Racine moyenne quadratique, rapportée au maximum d'un entier 16 bits signé.
     *
     * ⚠️ **Sert à l'affichage, pas à décider.** Une capture silencieuse doit se voir à l'écran,
     * sinon l'utilisateur parle dans un micro coupé et ne l'apprend qu'à la transcription vide. Ce
     * n'est en aucun cas une détection de parole.
     *
     * @param octets tampon brut du micro, petit-boutiste, deux octets par échantillon.
     * @param longueur nombre d'octets réellement lus — le tampon est presque toujours plus grand.
     */
    fun niveau(octets: ByteArray, longueur: Int): Float {
        val utiles = longueur.coerceIn(0, octets.size)
        // ⚠️ Un octet orphelin en fin de tampon est ignoré : la moitié d'un échantillon n'a pas de
        // valeur, et l'inclure ferait un pic ou un creux qui n'existe pas dans le son.
        val echantillons = utiles / 2
        if (echantillons == 0) return 0f

        var somme = 0.0
        for (i in 0 until echantillons) {
            val bas = octets[i * 2].toInt() and 0xFF
            val haut = octets[i * 2 + 1].toInt()
            val valeur = ((haut shl 8) or bas).toShort().toDouble()
            somme += valeur * valeur
        }
        return (sqrt(somme / echantillons) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }
}
