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
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], HEX_RADIX)
            val lo = Character.digit(hex[i * 2 + 1], HEX_RADIX)
            require(hi >= 0 && lo >= 0) { "caractère non hexadécimal en position ${i * 2}" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
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
