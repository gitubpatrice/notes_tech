package com.filestech.notes_tech.core.crypto

import java.security.SecureRandom

/**
 * Manipulation de matériel secret en mémoire.
 *
 * Portage de `SecretBytes` de `files_tech_core` (Dart), dont les conventions d'encodage sont
 * **contraignantes** : la KEK persistée par la version Flutter est encodée par `toHex`, et il faut
 * la relire à l'identique. Voir `docs/02-SCHEMA-HERITE.md`.
 *
 * Aucune fonction de ce fichier ne fait figurer une valeur secrète dans un message d'exception.
 * Une `FormatException` qui recrache le fragment fautif met la clé dans le journal de plantage.
 */
object SecretBytes {

    /**
     * Le générateur est partagé : `SecureRandom` est sûr vis-à-vis des accès concurrents et son
     * initialisation est coûteuse. En créer un par appel n'ajoute aucune entropie.
     */
    private val secureRandom = SecureRandom()

    private const val HEX_DIGITS = "0123456789abcdef"

    fun randomBytes(size: Int): ByteArray {
        require(size > 0) { "size doit être strictement positif" }
        return ByteArray(size).also(secureRandom::nextBytes)
    }

    /**
     * Remplit [bytes] de zéros.
     *
     * ⚠️ Ne remplace pas une gestion de durée de vie. La JVM peut avoir recopié le tableau lors
     * d'un déplacement du ramasse-miettes, et cette copie-là est hors de portée. Effacer réduit la
     * fenêtre d'exposition, ça ne l'annule pas — ne pas s'en servir pour justifier de garder un
     * secret en mémoire plus longtemps que nécessaire.
     */
    fun wipe(bytes: ByteArray) = bytes.fill(0)

    /** Encodage hexadécimal **minuscule**, deux caractères par octet. Réciproque de [fromHex]. */
    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_DIGITS[v ushr 4]
            out[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return String(out)
    }

    /**
     * Décode une chaîne hexadécimale. Insensible à la casse en lecture, contrairement à [toHex]
     * qui produit toujours des minuscules — la version Flutter valide déjà par `^[0-9a-fA-F]+$`.
     *
     * @throws IllegalArgumentException si la longueur est impaire ou si un caractère n'est pas
     *   hexadécimal. Le message ne contient **jamais** la valeur fautive.
     */
    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "chaîne hexadécimale de longueur impaire" }
        val out = ByteArray(hex.length / 2)
        try {
            for (i in out.indices) {
                out[i] =
                    decodeByte(Character.digit(hex[i * 2], HEX_RADIX), Character.digit(hex[i * 2 + 1], HEX_RADIX), i)
            }
        } catch (e: IllegalArgumentException) {
            // ⚠️ Le tableau est déjà partiellement rempli de vrais octets de la clé. Le laisser au
            // ramasse-miettes sans l'effacer serait une fuite évitable, sur le chemin d'erreur
            // précisément — celui qu'on regarde le moins.
            //
            // Relevé par une relecture externe (GPT-5.5, 2026-08-13).
            out.wipe()
            throw e
        }
        return out
    }

    /**
     * Décode l'hexadécimal **depuis des octets ASCII**, sans jamais construire de `String`.
     *
     * ## ⚠️ Pourquoi cette variante existe, alors que [fromHex] fait la même chose
     *
     * Une `String` Java est **immuable** : on ne peut pas l'effacer. Passer par elle pour décoder la
     * clé maître en laisse une copie lisible dans le tas jusqu'au prochain ramasse-miettes — c'est
     * exactement ce qu'un vidage mémoire ramasse.
     *
     * L'appelant qui détient déjà les caractères sous forme d'octets — c'est le cas après un
     * déchiffrement — doit donc utiliser cette version-ci. Il garde la maîtrise de l'effacement de
     * son tableau d'entrée, ce que [fromHex] ne lui offre pas.
     *
     * Relevé indépendamment par deux relectures externes (Gemini 3.1 Pro et GPT-5.5, 2026-08-13).
     */
    fun fromHexAscii(hex: ByteArray): ByteArray {
        require(hex.size % 2 == 0) { "séquence hexadécimale de longueur impaire" }
        val out = ByteArray(hex.size / 2)
        try {
            for (i in out.indices) {
                out[i] = decodeByte(hexDigit(hex[i * 2]), hexDigit(hex[i * 2 + 1]), i)
            }
        } catch (e: IllegalArgumentException) {
            out.wipe()
            throw e
        }
        return out
    }

    private fun decodeByte(hi: Int, lo: Int, index: Int): Byte {
        require(hi >= 0 && lo >= 0) { "caractère non hexadécimal en position ${index * 2}" }
        return ((hi shl 4) or lo).toByte()
    }

    /** `-1` si l'octet n'est pas un chiffre hexadécimal ASCII. Insensible à la casse, comme [fromHex]. */
    private fun hexDigit(byte: Byte): Int = when (val code = byte.toInt() and 0xFF) {
        in 0x30..0x39 -> code - 0x30 // '0'..'9'
        in 0x61..0x66 -> code - 0x61 + 10 // 'a'..'f'
        in 0x41..0x46 -> code - 0x41 + 10 // 'A'..'F'
        else -> -1
    }

    /**
     * Comparaison à temps constant.
     *
     * Le `||` court-circuité d'une comparaison naïve fuit la position du premier octet différent.
     * Ici la boucle parcourt toujours toute la longueur et accumule les écarts par `or`.
     *
     * La différence de **longueur**, elle, reste observable — c'est inhérent, et sans importance :
     * les valeurs comparées ici (vérificateurs de coffre, empreintes) ont une taille fixe et
     * publique.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private const val HEX_RADIX = 16
}

/** Raccourci d'usage : `key.wipe()` se lit mieux qu'un appel préfixé dans un `finally`. */
fun ByteArray.wipe() = SecretBytes.wipe(this)
