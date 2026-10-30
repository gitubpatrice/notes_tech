package com.filestech.notes_tech.security.vault

import com.filestech.notes_tech.core.crypto.SecretBytes

/**
 * Le Keystore, en mémoire.
 *
 * Le vrai est exercé par `AndroidVaultKeystoreTest`. Ici il faut pouvoir **provoquer** ses
 * pannes — indisponibilité passagère, invalidation définitive — puisque c'est la distinction
 * entre les deux qui décide de détruire ou non les notes de quelqu'un.
 */
internal class KeystoreEnMemoire : VaultKeystore {
    private val cles = mutableMapOf<String, ByteArray>()
    var panneTransitoire = false
    var invalidationPermanente = false

    /** Only `seal` fails — after the key was created — keeping the array it was handed. */
    var scellementRefuse = false
    var clairRecuAuScellement: ByteArray? = null

    override fun createKey(alias: String): Boolean {
        garde()
        if (cles.containsKey(alias)) return false
        cles[alias] = SecretBytes.randomBytes(VaultParams.FOLDER_KEY_BYTES)
        return true
    }

    override fun seal(alias: String, plaintext: ByteArray): SealedByKeystore {
        garde()
        if (scellementRefuse) {
            clairRecuAuScellement = plaintext
            throw KeystoreUnavailableException()
        }
        val cle = cles[alias] ?: throw KeystoreUnavailableException()
        val nonce = VaultCrypto.newNonce()
        return SealedByKeystore(VaultCrypto.seal(cle, nonce, plaintext, ByteArray(0)), nonce)
    }

    /** The last array `open` gave back — the inner seal, which the service must wipe. */
    var clairRenduALOuverture: ByteArray? = null

    override fun open(alias: String, sealed: SealedByKeystore): ByteArray {
        garde()
        // As the real one since 2026-09-25: an absent key is named, never "unavailable".
        val cle = cles[alias] ?: throw KeystoreKeyMissingException()
        return VaultCrypto.open(cle, sealed.nonce, sealed.ciphertext, ByteArray(0))
            .also { clairRenduALOuverture = it }
    }

    /** `deleteKey` returns without deleting — a Keystore that refused in silence (audit P3). */
    var suppressionIgnoree = false

    override fun deleteKey(alias: String) {
        if (!suppressionIgnoree) cles.remove(alias)
    }

    override fun deleteKeysWithPrefix(prefix: String): Int {
        val vises = cles.keys.filter { it.startsWith(prefix) }
        vises.forEach(cles::remove)
        return vises.size
    }

    override fun hasKey(alias: String): Boolean = cles.containsKey(alias)

    private fun garde() {
        if (invalidationPermanente) throw KeystorePermanentlyInvalidatedException()
        if (panneTransitoire) throw KeystoreUnavailableException()
    }
}
