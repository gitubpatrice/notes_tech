package com.filestech.notes_tech.security.vault

import com.filestech.notes_tech.core.crypto.SecretBytes
import com.filestech.notes_tech.core.crypto.wipe
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Les primitives cryptographiques des coffres, et rien de plus.
 *
 * Aucun état, aucune session, aucun accès à la base : ce qui est ici se rejoue intégralement sur la
 * JVM, sans appareil, contre les vecteurs de `app/src/test/resources/parite/coffre_*.tsv`. C'est
 * délibéré — la partie la plus irréparable du portage est aussi celle qui doit être la plus facile
 * à mettre en doute.
 *
 * ## Ce qui est prouvé, et par quoi
 *
 * Les vecteurs ont été produits en **exécutant** le vrai code Dart de la 2.0.3, puis recoupés
 * contre une troisième implantation indépendante (`argon2-cffi`, qui est le C de référence de la
 * RFC 9106, et `cryptography` Python, qui est OpenSSL) : 37 concordances, zéro divergence.
 * Procédure et limites dans `docs/09-VECTEURS-DE-PARITE.md`.
 *
 * ## ⚠️ Ce qui n'est PAS prouvé par ces vecteurs
 *
 * Qu'un utilisateur réel rouvre son coffre. Les vecteurs figent un **format**, pas une migration.
 * Le critère de sortie de la phase 4 reste d'ouvrir un coffre réellement créé par la version
 * Flutter — cf. `docs/00-PLAN.md`.
 */
internal object VaultCrypto {

    /**
     * Dérive la clé qui déballe la clé du coffre, à partir d'un secret mémorisé.
     *
     * ⚠️ **`withMemoryAsKB`, jamais `withMemoryPowOfTwo`.** Bouncy Castle propose les deux, et le
     * second interprète son argument comme un exposant : `withMemoryPowOfTwo(65536)` ne demanderait
     * pas 64 Mo, il demanderait `2^65536` blocs. L'erreur ne se verrait pas à la relecture — les
     * deux appels se ressemblent — et produirait des clés fausses, donc des coffres inouvrables.
     *
     * ⚠️ **Aucune normalisation Unicode.** Argon2id travaille sur les octets UTF-8 de la phrase
     * telle qu'elle a été saisie. Les vecteurs `accents_precomposes` et `accents_decomposes`
     * portent la même chaîne perçue, écrite en NFC puis en NFD, et leurs clés diffèrent : ajouter
     * une normalisation « pour aider l'utilisateur » fermerait tous les coffres créés avant.
     *
     * @param secret les octets UTF-8 de la passphrase ou du PIN. L'appelant en garde la propriété
     *   et doit l'effacer — cette fonction ne le fait pas, parce qu'elle ne sait pas si l'appelant
     *   en a encore besoin.
     */
    fun deriveKey(secret: ByteArray, salt: ByteArray, iterations: Int, memoryKib: Int): ByteArray {
        require(salt.isNotEmpty()) { "sel vide" }
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKib)
            .withParallelism(VaultParams.PARALLELISM)
            .withSalt(salt)
            .build()
        val generator = Argon2BytesGenerator().apply { init(parameters) }
        val out = ByteArray(VaultParams.DERIVED_KEY_BYTES)
        generator.generateBytes(secret, out)
        return out
    }

    /** [deriveKey] avec les paramètres du mode passphrase. */
    fun derivePassphraseKey(secret: ByteArray, salt: ByteArray): ByteArray =
        deriveKey(secret, salt, VaultParams.PASSPHRASE_ITERATIONS, VaultParams.PASSPHRASE_MEMORY_KIB)

    /** [deriveKey] avec les paramètres allégés du mode PIN. */
    fun derivePinKey(secret: ByteArray, salt: ByteArray): ByteArray =
        deriveKey(secret, salt, VaultParams.PIN_ITERATIONS, VaultParams.PIN_MEMORY_KIB)

    /**
     * Chiffre en AES-256-GCM et rend `chiffré ‖ étiquette(16)`.
     *
     * Le nonce **n'est pas** dans la sortie : côté coffre il vit dans la colonne `vault_iv`, côté
     * note il est préfixé par [NoteEnvelope]. C'est la convention de la version Flutter
     * (`_aesGcmEncrypt`, qui concatène `cipherText` puis `mac.bytes`), et la sortie de JCE est déjà
     * dans cet ordre — `Cipher.doFinal` place l'étiquette à la fin.
     *
     * ⚠️ **Réutiliser un couple (clé, nonce) casse GCM en entier** : deux messages chiffrés avec le
     * même couple laissent récupérer leur ou-exclusif, et surtout la clé d'authentification. Tout
     * appelant doit donc tirer un nonce neuf, jamais en dériver un d'un compteur ou d'un
     * identifiant.
     */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        withCipher(Cipher.ENCRYPT_MODE, key, nonce, aad) { it.doFinal(plaintext) }

    /**
     * Déchiffre `chiffré ‖ étiquette(16)`.
     *
     * @throws WrongSecretException si l'étiquette ne valide pas — **la seule** conclusion à en
     *   tirer est « ce n'est pas la bonne clé ». Voir la note ci-dessous sur les autres échecs.
     * @throws MalformedVaultDataException si l'enveloppe est trop courte pour contenir une
     *   étiquette. Ce n'est pas un mauvais secret : c'est une donnée abîmée, et la traiter comme
     *   une tentative ratée ferait s'auto-détruire un coffre PIN pour une corruption de base.
     */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        if (sealed.size < VaultParams.TAG_BYTES) {
            throw MalformedVaultDataException("enveloppe de ${sealed.size} octets, minimum ${VaultParams.TAG_BYTES}")
        }
        return try {
            withCipher(Cipher.DECRYPT_MODE, key, nonce, aad) { it.doFinal(sealed) }
        } catch (e: AEADBadTagException) {
            // ⚠️ Cette exception-ci, et elle seule, veut dire « mauvais secret ».
            //
            // La distinction n'est pas cosmétique : côté PIN, une tentative ratée incrémente un
            // compteur qui détruit le coffre au cinquième échec. Confondre « clé fausse » avec
            // « le fournisseur cryptographique a échoué » ferait effacer les notes d'un
            // utilisateur qui a saisi le bon code. La version Flutter a payé cette leçon en
            // v1.0.3 (F5) : elle traitait toute exception Keystore comme un échec légitime, et
            // a dû passer à une liste blanche.
            throw WrongSecretException(e)
        }
    }

    /**
     * L'attestation qu'une clé de coffre est bien celle attendue, sans avoir à déchiffrer une note.
     *
     * Comparée à `folders.vault_verifier` **en temps constant**. Une comparaison naïve fuirait, par
     * son temps d'exécution, le nombre d'octets de tête corrects — de quoi reconstruire la valeur
     * octet par octet au lieu de la deviner d'un coup.
     */
    fun verifierFor(folderKey: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        val keySpec = SecretKeySpec(folderKey, HMAC_SHA256)
        return try {
            mac.init(keySpec)
            mac.doFinal(VaultParams.VERIFIER_MESSAGE.toByteArray(Charsets.UTF_8))
        } finally {
            mac.reset()
        }
    }

    /** `true` si [folderKey] produit exactement [expectedVerifier]. Comparaison à temps constant. */
    fun matchesVerifier(folderKey: ByteArray, expectedVerifier: ByteArray): Boolean {
        val actual = verifierFor(folderKey)
        return try {
            SecretBytes.constantTimeEquals(actual, expectedVerifier)
        } finally {
            actual.wipe()
        }
    }

    /** Un nonce neuf. Voir l'avertissement de [seal] sur la réutilisation. */
    fun newNonce(): ByteArray = SecretBytes.randomBytes(VaultParams.NONCE_BYTES)

    /** Un sel neuf, à persister dans `folders.vault_salt`. */
    fun newSalt(): ByteArray = SecretBytes.randomBytes(VaultParams.SALT_BYTES)

    /** Une clé de coffre neuve. C'est elle que protègent toutes les couches au-dessus. */
    fun newFolderKey(): ByteArray = SecretBytes.randomBytes(VaultParams.FOLDER_KEY_BYTES)

    private inline fun <T> withCipher(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        block: (Cipher) -> T,
    ): T {
        require(nonce.size == VaultParams.NONCE_BYTES) {
            "nonce de ${nonce.size} octets, attendu ${VaultParams.NONCE_BYTES}"
        }
        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        val keySpec = SecretKeySpec(key, AES)
        cipher.init(mode, keySpec, GCMParameterSpec(VaultParams.TAG_BITS, nonce))
        // L'AAD lie l'enveloppe à son propriétaire : l'identifiant du dossier pour une clé de
        // coffre, celui de la note pour un contenu. Sans elle, quelqu'un capable d'écrire dans la
        // base recopierait le `vault_kek_wrapped` d'un coffre sur un autre pour en réutiliser la
        // passphrase, ou déplacerait le contenu d'une note dans une autre.
        cipher.updateAAD(aad)
        return block(cipher)
    }

    private const val AES = "AES"
    private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
    private const val HMAC_SHA256 = "HmacSHA256"
}
