package com.filestech.notes_tech.security.vault

/**
 * L'enveloppe binaire d'une note de coffre, telle qu'elle est écrite dans `notes.encrypted_content`.
 *
 * ```
 *   blob  = nonce(12) ‖ chiffré ‖ étiquette(16)
 *   clair = uint32 gros-boutiste longueur du titre ‖ titre UTF-8 ‖ contenu UTF-8   (format 2)
 *   clair = contenu UTF-8                                                          (format 1)
 * ```
 *
 * La colonne `notes.enc_v` dit lequel des deux formats le clair porte. En format 1, le titre est
 * resté en clair dans la colonne `title` ; en format 2, il a rejoint le blob et la colonne est
 * vidée. La migration de l'un vers l'autre exige la clé, donc une session ouverte : elle se fait à
 * l'ouverture du coffre, pas à la migration de schéma.
 *
 * Source : `folder_vault_service.dart`, `_packTitleAndContent` / `_unpackTitleAndContent` /
 * `encryptNote` / `decryptNote`.
 */
internal object NoteEnvelope {

    /** `notes.enc_v` — le blob ne porte que le contenu. */
    const val ENC_V_CONTENT_ONLY = 1

    /** `notes.enc_v` — le blob porte le titre **et** le contenu. */
    const val ENC_V_TITLE_AND_CONTENT = 2

    private const val TITLE_LENGTH_PREFIX_BYTES = 4

    /** Longueur minimale d'un blob valide : un nonce, une étiquette, et un chiffré éventuellement vide. */
    private val MIN_BLOB_SIZE = VaultParams.NONCE_BYTES + VaultParams.TAG_BYTES

    /** `nonce ‖ chiffré ‖ étiquette` — l'ordre que la version Flutter écrit depuis la v0.7. */
    fun composeBlob(nonce: ByteArray, sealed: ByteArray): ByteArray {
        require(nonce.size == VaultParams.NONCE_BYTES) {
            "nonce de ${nonce.size} octets, attendu ${VaultParams.NONCE_BYTES}"
        }
        return nonce + sealed
    }

    /** Le nonce de tête d'un blob. */
    fun nonceOf(blob: ByteArray): ByteArray {
        requireWellFormedBlob(blob)
        return blob.copyOfRange(0, VaultParams.NONCE_BYTES)
    }

    /** Ce qui suit le nonce : le chiffré et son étiquette, tels que [VaultCrypto.open] les attend. */
    fun sealedOf(blob: ByteArray): ByteArray {
        requireWellFormedBlob(blob)
        return blob.copyOfRange(VaultParams.NONCE_BYTES, blob.size)
    }

    /**
     * Le clair du format 2 : `uint32 gros-boutiste longueur du titre ‖ titre ‖ contenu`.
     *
     * Un préfixe de longueur binaire plutôt qu'un séparateur ou du JSON : rien à échapper, aucun
     * analyseur syntaxique dans le chemin cryptographique, et une frontière qui ne dépend d'aucun
     * caractère susceptible d'apparaître dans le titre comme dans le contenu.
     */
    fun packTitleAndContent(title: String, content: String): ByteArray {
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        val out = ByteArray(TITLE_LENGTH_PREFIX_BYTES + titleBytes.size + contentBytes.size)
        writeUInt32BigEndian(out, titleBytes.size)
        titleBytes.copyInto(out, TITLE_LENGTH_PREFIX_BYTES)
        contentBytes.copyInto(out, TITLE_LENGTH_PREFIX_BYTES + titleBytes.size)
        return out
    }

    /**
     * Réciproque de [packTitleAndContent].
     *
     * ⚠️ **La longueur est lue en `Long`, pas en `Int`, et ce n'est pas une précaution de style.**
     *
     * En Dart, `ByteData.getUint32` rend un entier 64 bits : une longueur annoncée à 4 000 000 000
     * y reste positive et le contrôle `4 + titleLen > plaintext.length` la rejette. En Kotlin, les
     * quatre mêmes octets lus dans un `Int` donnent un nombre **négatif** — le contrôle passe, et
     * le découpage part sur des bornes absurdes. Le clair d'une note est de la donnée déchiffrée,
     * donc authentifiée par GCM ; mais s'appuyer là-dessus pour se passer d'un contrôle correct,
     * c'est faire dépendre la solidité d'une couche de la vigilance d'une autre.
     *
     * @throws MalformedVaultDataException si l'enveloppe est incohérente. Lever vaut mieux que
     *   rendre un titre tronqué et un contenu décalé, qui seraient ensuite réécrits en base.
     */
    fun unpackTitleAndContent(plaintext: ByteArray): TitleAndContent {
        if (plaintext.size < TITLE_LENGTH_PREFIX_BYTES) {
            throw MalformedVaultDataException("clair de ${plaintext.size} octets, trop court pour un format 2")
        }
        val titleLength = readUInt32BigEndian(plaintext)
        val bodyStart = TITLE_LENGTH_PREFIX_BYTES + titleLength
        if (bodyStart > plaintext.size) {
            throw MalformedVaultDataException("longueur de titre annoncée hors des bornes du clair")
        }
        val start = bodyStart.toInt()
        return TitleAndContent(
            title = String(plaintext, TITLE_LENGTH_PREFIX_BYTES, start - TITLE_LENGTH_PREFIX_BYTES, Charsets.UTF_8),
            content = String(plaintext, start, plaintext.size - start, Charsets.UTF_8),
        )
    }

    /** Le résultat de [unpackTitleAndContent]. */
    data class TitleAndContent(val title: String, val content: String)

    private fun requireWellFormedBlob(blob: ByteArray) {
        if (blob.size < MIN_BLOB_SIZE) {
            throw MalformedVaultDataException("blob de ${blob.size} octets, minimum $MIN_BLOB_SIZE")
        }
    }

    private fun writeUInt32BigEndian(out: ByteArray, value: Int) {
        out[0] = (value ushr 24).toByte()
        out[1] = (value ushr 16).toByte()
        out[2] = (value ushr 8).toByte()
        out[3] = value.toByte()
    }

    private fun readUInt32BigEndian(source: ByteArray): Long = (source[0].toLong() and 0xFF shl 24) or
        (source[1].toLong() and 0xFF shl 16) or
        (source[2].toLong() and 0xFF shl 8) or
        (source[3].toLong() and 0xFF)
}
