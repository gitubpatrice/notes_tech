package com.filestech.notes_tech.security.vault

/**
 * Le scellement lié à l'appareil, troisième couche des coffres à code.
 *
 * ## Pourquoi cette couche existe
 *
 * Quatre chiffres, c'est dix mille possibilités : rien, pour qui peut essayer hors de l'appareil.
 * Toute la sécurité du mode PIN tient donc à ce que la clé qui scelle ne sorte **jamais** du
 * matériel sécurisé. L'attaquant doit alors posséder le téléphone, passer par l'API, et il n'a que
 * cinq essais.
 *
 * Argon2id allégé, en dessous, ne fait que ralentir quelqu'un qui aurait déjà contourné le
 * compteur. Ce n'est pas lui qui protège.
 *
 * ## Pourquoi une interface
 *
 * Pour que le service de coffres soit testable sur la JVM. Le scellement réel n'est vérifiable que
 * sur appareil — cf. `AndroidVaultKeystoreTest` — mais tout ce qui l'entoure (compteur de
 * tentatives, effacement, reprise après interruption) mérite d'être exercé sans en dépendre.
 */
interface VaultKeystore {

    /**
     * Crée la clé d'alias [alias] si elle n'existe pas déjà.
     *
     * @return `true` si une clé a été créée, `false` si l'alias était déjà pris.
     * @throws KeystoreSoftwareOnlyException si la clé obtenue n'est pas retenue par du matériel
     *   sécurisé. Elle est alors supprimée : une clé logicielle donnerait l'illusion d'un coffre à
     *   code protégé alors qu'il serait attaquable hors de l'appareil.
     * @throws KeystoreUnavailableException si le magasin n'a pas pu être interrogé.
     */
    fun createKey(alias: String): Boolean

    /**
     * Scelle [plaintext] avec la clé [alias].
     *
     * Le nonce est produit par le Keystore lui-même — la clé est créée avec
     * `setRandomizedEncryptionRequired(true)`, ce qui interdit d'en fournir un. C'est la garantie
     * qu'aucun appelant ne pourra réutiliser un couple (clé, nonce), ce qui casserait GCM.
     */
    fun seal(alias: String, plaintext: ByteArray): SealedByKeystore

    /**
     * Ouvre ce que [seal] a produit.
     *
     * @throws KeystorePermanentlyInvalidatedException si le système a détruit la clé. Le coffre est
     *   alors légitimement irrécupérable.
     * @throws KeystoreKeyMissingException when [alias] holds no key — removing the screen lock
     *   deletes it, measured on API 34 — so that no PIN opens the vault. Neither counted nor
     *   wiped — see the exception.
     * @throws MalformedVaultDataException si l'étiquette ne valide pas. ⚠️ **Ce n'est pas un
     *   mauvais code** — le code de l'utilisateur n'intervient pas à cette couche. C'est une
     *   donnée abîmée, et la compter comme une tentative ratée ferait s'auto-détruire un coffre
     *   pour une corruption de base.
     * @throws KeystoreUnavailableException pour tout le reste, qui ne prouve rien et ne doit donc
     *   ni compter ni effacer.
     */
    fun open(alias: String, sealed: SealedByKeystore): ByteArray

    /** Supprime la clé. Idempotent : un alias absent n'est pas une erreur. */
    fun deleteKey(alias: String)

    /**
     * Supprime **toutes** les clés dont l'alias commence par [prefix].
     *
     * ## 🔴 Pourquoi le mode panique ne peut pas se contenter de [deleteKey]
     *
     * Effacer les clés une par une supposerait de savoir quels coffres existent — donc de lire la
     * base. Or la base est ce qu'on est en train de détruire, et elle a pu devenir illisible avant
     * cette étape, ou l'être depuis le début.
     *
     * Pire : un alias `vault_pin_*` peut survivre à la disparition du dossier qui l'a créé — une
     * suppression interrompue, une restauration partielle. Cette clé orpheline n'apparaît dans
     * aucune requête, et c'est précisément elle qui permettrait de déchiffrer un coffre à code
     * extrait d'une sauvegarde antérieure. Le magasin est la seule source qui les connaisse toutes.
     *
     * @return le nombre de clés effacées.
     * @throws KeystoreUnavailableException si le magasin n'a pas pu être énuméré. ⚠️ Un échec ici
     *   **ne doit pas** être avalé : il signifie que des clés de coffre survivent à la panique.
     */
    fun deleteKeysWithPrefix(prefix: String): Int

    /** `true` si l'alias existe. Sert au diagnostic et aux tests d'effacement. */
    fun hasKey(alias: String): Boolean
}

/**
 * Ce que le Keystore rend : un chiffré et le nonce qu'il a lui-même tiré.
 *
 * Les deux vont respectivement dans `folders.vault_pin_blob` et `folders.vault_pin_iv`.
 * Pas une `data class` : son `equals` comparerait les tableaux par référence.
 */
class SealedByKeystore(val ciphertext: ByteArray, val nonce: ByteArray)
