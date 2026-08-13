package com.filestech.notes_tech.data.local

import com.filestech.notes_tech.core.crypto.SecretBytes
import com.filestech.notes_tech.core.crypto.wipe

/**
 * Compose le matériel de clé attendu par SQLCipher pour ouvrir la base héritée.
 *
 * ## Le fait décisif du portage
 *
 * La version Flutter compose `x'<64 hex>'` et le passe en `String`
 * (`notes_tech/lib/data/db/database.dart:463`). SQLCipher reconnaît ce motif **dans le matériel de
 * clé lui-même**, quelle que soit la voie d'arrivée (`PRAGMA key` ou `sqlite3_key`) : longueur
 * `taille_clé * 2 + 3`, préfixe `x'`, corps hexadécimal. Il l'utilise alors comme **clé brute** et
 * ne dérive rien.
 *
 * ⚠️ Passer les 32 octets bruts de la KEK à la place produirait une base illisible : SQLCipher les
 * traiterait comme une passphrase et les ferait passer par PBKDF2-HMAC-SHA512, 256 000 itérations
 * en compatibilité 4. La clé obtenue n'aurait aucun rapport avec celle du fichier.
 *
 * C'est la raison d'être de ce fichier : centraliser le seul endroit du code où ce format est
 * composé, pour qu'aucun appelant n'ait à s'en souvenir. Cf. `docs/01-DECISIONS.md` D-004.
 */
object SqlCipherRawKey {

    /** SQLCipher attend une clé de 256 bits en mode clé brute. */
    const val KEY_SIZE_BYTES = 32

    /** `x'` + 64 caractères hexadécimaux + `'`. C'est cette longueur exacte que SQLCipher teste. */
    const val ENCODED_SIZE_BYTES = KEY_SIZE_BYTES * 2 + 3

    /**
     * Encode [kek] au format clé brute, en octets ASCII prêts pour `SupportOpenHelperFactory`.
     *
     * Le tableau retourné **est** du matériel secret : il porte la clé en clair, simplement
     * ré-encodée. L'appelant doit l'effacer après usage, exactement comme la KEK.
     *
     * [kek] n'est pas modifié — son effacement reste la responsabilité de son propriétaire.
     */
    fun encode(kek: ByteArray): ByteArray {
        require(kek.size == KEY_SIZE_BYTES) {
            // Pas de valeur dans le message : la taille seule suffit au diagnostic.
            "KEK de taille invalide : ${kek.size} octets, attendu $KEY_SIZE_BYTES"
        }
        // ⚠️ Limite connue et assumée : les `String` intermédiaires portent la clé en clair et sont
        // immuables — la JVM en garde le contenu jusqu'au ramasse-miettes, hors de portée d'un
        // effacement. La version Flutter a exactement la même limite, et la fenêtre d'exposition
        // est celle d'une ouverture de base au démarrage. Ne pas ajouter de faux effacement ici :
        // il donnerait l'illusion d'une garantie qui n'existe pas.
        val hex = SecretBytes.toHex(kek)
        // `US_ASCII` et non UTF-8 : le résultat est identique pour ce jeu de caractères, mais
        // l'ASCII dit ce qui est vrai — SQLCipher compare des octets bruts, et toute interprétation
        // multi-octets serait un défaut.
        return "x'$hex'".toByteArray(Charsets.US_ASCII).also {
            check(it.size == ENCODED_SIZE_BYTES) {
                "encodage de taille ${it.size}, attendu $ENCODED_SIZE_BYTES"
            }
        }
    }

    /**
     * Décode une KEK depuis sa forme hexadécimale persistée (64 caractères minuscules).
     *
     * Utilisé par les couches d'acquisition de la KEK, qui la lisent telle que la version Flutter
     * l'a écrite (`notes_tech/lib/services/security/vault_service.dart:104`).
     *
     * @throws IllegalArgumentException si la longueur ou l'encodage ne conviennent pas. Le message
     *   ne contient jamais la valeur.
     */
    fun decodeHexKek(hex: String): ByteArray {
        require(hex.length == KEY_SIZE_BYTES * 2) {
            "KEK persistée : longueur ${hex.length}, attendu ${KEY_SIZE_BYTES * 2}"
        }
        val bytes = SecretBytes.fromHex(hex)
        // `fromHex` garantit déjà la taille par construction ; la vérification reste pour que
        // toute évolution de `fromHex` casse ici plutôt que dans SQLCipher.
        if (bytes.size != KEY_SIZE_BYTES) {
            bytes.wipe()
            throw IllegalArgumentException("KEK persistée : ${bytes.size} octets décodés")
        }
        return bytes
    }
}
