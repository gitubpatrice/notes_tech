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
     * Décode un fichier WAV complet en échantillons flottants, entre `-1` et `1`.
     *
     * C'est ce que le moteur de transcription attend, et c'est **la seule raison pour laquelle
     * cette fonction existe ici plutôt qu'en C**. Le greffon dont viennent les sources natives
     * embarquait `dr_wav.h`, un analyseur de format généraliste de 356 Ko, et lui passait un chemin
     * de fichier. Analyser un format en C, sur un fichier venu du disque, c'est une surface
     * d'attaque pour un besoin qu'on n'a pas : le seul WAV qu'on ait à lire est celui qu'on vient
     * soi-même d'écrire, dans un format qu'on fixe.
     *
     * ⚠️⚠️ **Ce décodeur est donc VOLONTAIREMENT étroit.** Il n'accepte que ce que [entete] produit,
     * et refuse tout le reste — pas de blocs `LIST`, pas de stéréo, pas de 8 ou 24 bits, pas de
     * flottant. Ce n'est pas une limitation à lever un jour : élargir ce décodeur reviendrait à
     * réécrire `dr_wav.h`, c'est-à-dire à réintroduire exactement ce qu'on a refusé.
     *
     * @throws IllegalArgumentException le contenu n'est pas un WAV 16 kHz mono 16 bits.
     */
    fun echantillons(octets: ByteArray): FloatArray {
        require(octets.size >= TAILLE_ENTETE) {
            "fichier trop court pour porter un en-tete : ${octets.size} octets"
        }
        require(ascii(octets, 0, 4) == "RIFF" && ascii(octets, 8, 4) == "WAVE") {
            "ce n'est pas un fichier WAV"
        }
        require(ascii(octets, 12, 4) == "fmt " && ascii(octets, 36, 4) == "data") {
            "disposition de blocs inattendue — ce WAV ne vient pas de cette application"
        }
        require(entier16(octets, 20) == 1) { "seul le PCM non compresse est accepte" }
        require(entier16(octets, 22) == CANAUX) { "seul le mono est accepte" }
        require(entier32(octets, 24) == FREQUENCE_HZ.toLong()) { "seul le $FREQUENCE_HZ Hz est accepte" }
        require(entier16(octets, 34) == BITS_PAR_ECHANTILLON) { "seul le 16 bits est accepte" }

        // ⚠️ La taille annoncée par l'en-tête est **plafonnée** par ce que le fichier porte vraiment.
        // Une capture interrompue avant la correction finale annonce zéro ; une capture tronquée par
        // un disque plein annonce plus qu'elle ne contient. Suivre l'annonce sans la confronter au
        // fichier, c'est lire au-delà du tableau dans le second cas — et jeter tout le son dans le
        // premier. Ni l'un ni l'autre n'est acceptable pour un fichier qu'on a écrit soi-même.
        //
        // 🔴 **En cas de désaccord, c'est le FICHIER qui a raison, pas son en-tête.** La version
        // précédente suivait l'annonce dès qu'elle tenait dans `1..disponibles`, donc **tronquait en
        // silence** un fichier dont l'en-tête annonçait moins qu'il ne portait — l'état exact que
        // laisse une réécriture d'en-tête interrompue au mauvais octet. Le son était sur le disque,
        // et seules quelques millisecondes en sortaient, sans la moindre erreur.
        //
        // Ce décodeur ne lit **que** des fichiers écrits par [entete] : il n'y a jamais de bloc à la
        // suite des données, donc aucun octet excédentaire légitime. `disponibles` est donc la
        // seule mesure fiable, et l'annonce ne sert plus qu'à constater l'accord.
        //
        // Relevé par une relecture externe (GPT-5.2, 2026-08-16).
        val annonces = entier32(octets, 40)
        val disponibles = (octets.size - TAILLE_ENTETE).toLong()
        val utiles = if (annonces == disponibles) annonces else disponibles

        val nombre = (utiles / 2).toInt()
        val sortie = FloatArray(nombre)
        for (i in 0 until nombre) {
            val position = TAILLE_ENTETE + i * 2
            val bas = octets[position].toInt() and 0xFF
            val haut = octets[position + 1].toInt()
            // ⚠️ Division par 32768 et non 32767 : c'est l'amplitude d'un entier 16 bits signé du
            // côté négatif, donc la seule qui garantisse que rien ne sorte de l'intervalle.
            sortie[i] = (((haut shl 8) or bas).toShort().toFloat() / 32768f)
        }
        return sortie
    }

    private fun ascii(octets: ByteArray, position: Int, longueur: Int): String =
        String(octets, position, longueur, Charsets.US_ASCII)

    private fun entier16(octets: ByteArray, position: Int): Int =
        (octets[position].toInt() and 0xFF) or ((octets[position + 1].toInt() and 0xFF) shl 8)

    private fun entier32(octets: ByteArray, position: Int): Long {
        var valeur = 0L
        for (i in 3 downTo 0) valeur = (valeur shl 8) or (octets[position + i].toLong() and 0xFF)
        return valeur
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
