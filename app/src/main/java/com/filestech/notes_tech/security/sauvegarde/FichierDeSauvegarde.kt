package com.filestech.notes_tech.security.sauvegarde

import com.filestech.notes_tech.security.vault.VaultCrypto
import com.filestech.notes_tech.security.vault.VaultParams
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.io.CipherInputStream
import org.bouncycastle.crypto.io.CipherOutputStream
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * A `.ntbackup` file, version 1 (3.2.0, `docs/13-VERSION-3.2.md` "Format, version 1"):
 *
 * ```
 * 0   8   "NTBACKUP"          20  16  salt
 * 8   2   version = 1         36  12  GCM nonce
 * 10  1   KDF = 1, Argon2id   48  …   AES-256-GCM of the content, AAD = these 48 bytes, tag at the end
 * 11  4   t      15  4   m (KiB)      19  1   p
 * ```
 *
 * - **Streamed both ways** (Bouncy Castle's GCM): the content is never held whole, encrypted or not.
 * - 🔴 **Read as a stream, the content is NOT authenticated until its end.** The tag is checked when
 *   the stream closes — [ouvrir]'s stream throws then, an [IOException]. Hence the two passes of the
 *   restore (D-031): nothing read from it is believed, or written for good, before that.
 * - 🔴 **Exactly t = 3, m = 64 MiB, p = 1 before any derivation** (R-10): nothing is authenticated yet
 *   at that point, and a forged header must not make the phone allocate more, or compute longer, than a
 *   real backup does. Other parameters will come with another version of the format.
 * - **64 MiB at most, counted on what is really read** (R-9): a provider's announced size is only a
 *   first refusal.
 */
internal object FichierDeSauvegarde {

    const val TAILLE_DE_L_EN_TETE = 48

    /** The file, header and tag included. */
    const val TAILLE_MAX = 64L * 1024 * 1024

    const val VERSION = 1
    private const val KDF_ARGON2ID: Byte = 1
    private val MAGIQUE = "NTBACKUP".toByteArray(Charsets.US_ASCII)

    /** What a file is refused for before its tag is even reached. */
    enum class Refus { PAS_UNE_SAUVEGARDE, VERSION_INCONNUE, PARAMETRES_INCONNUS, TRONQUE, TROP_GROS }

    class SauvegardeRefuseeException(val refus: Refus) : IOException("sauvegarde refusee : $refus")

    /** The salt and nonce of one file — drawn anew for every file. */
    class EnTete(val sel: ByteArray, val nonce: ByteArray) {
        init {
            require(sel.size == VaultParams.SALT_BYTES && nonce.size == VaultParams.NONCE_BYTES)
        }

        fun octets(): ByteArray = ByteBuffer.allocate(TAILLE_DE_L_EN_TETE)
            .put(MAGIQUE)
            .putShort(VERSION.toShort())
            .put(KDF_ARGON2ID)
            .putInt(VaultParams.PASSPHRASE_ITERATIONS)
            .putInt(VaultParams.PASSPHRASE_MEMORY_KIB)
            .put(VaultParams.PARALLELISM.toByte())
            .put(sel)
            .put(nonce)
            .array()
    }

    fun nouvelEnTete(): EnTete = EnTete(VaultCrypto.newSalt(), VaultCrypto.newNonce())

    /** The key of a file: Argon2id with the vaults' passphrase parameters. To be wiped by the caller. */
    fun cle(secret: ByteArray, enTete: EnTete): ByteArray = VaultCrypto.derivePassphraseKey(secret, enTete.sel)

    /** Writes [enTete] to [sortie], and returns the stream the content goes into; closing it writes the tag. */
    fun ecrire(sortie: OutputStream, cle: ByteArray, enTete: EnTete): OutputStream {
        val octets = enTete.octets()
        sortie.write(octets)
        return CipherOutputStream(sortie, gcm(chiffrer = true, cle, enTete, octets))
    }

    /** Reads and checks the header of [entree] — strictly, nothing being authenticated yet. */
    fun lireLEnTete(entree: InputStream): Pair<EnTete, ByteArray> {
        // A loop, not `readNBytes`: that one exists from Android 13 only, and the app runs from 7.
        val octets = ByteArray(TAILLE_DE_L_EN_TETE)
        var lus = 0
        while (lus < octets.size) {
            val n = entree.read(octets, lus, octets.size - lus)
            if (n < 0) throw SauvegardeRefuseeException(Refus.TRONQUE)
            lus += n
        }
        val lecture = ByteBuffer.wrap(octets)
        val magique = ByteArray(MAGIQUE.size).also { lecture.get(it) }
        if (!magique.contentEquals(MAGIQUE)) throw SauvegardeRefuseeException(Refus.PAS_UNE_SAUVEGARDE)
        if (lecture.short.toInt() != VERSION) throw SauvegardeRefuseeException(Refus.VERSION_INCONNUE)
        val connus = lecture.get() == KDF_ARGON2ID &&
            lecture.int == VaultParams.PASSPHRASE_ITERATIONS &&
            lecture.int == VaultParams.PASSPHRASE_MEMORY_KIB &&
            lecture.get().toInt() == VaultParams.PARALLELISM
        if (!connus) throw SauvegardeRefuseeException(Refus.PARAMETRES_INCONNUS)
        val sel = ByteArray(VaultParams.SALT_BYTES).also { lecture.get(it) }
        val nonce = ByteArray(VaultParams.NONCE_BYTES).also { lecture.get(it) }
        return EnTete(sel, nonce) to octets
    }

    /**
     * The content of a file whose header [lireLEnTete] read from [entree] — the same stream, bounded by
     * the caller ([borne]). Reading it to its end checks the tag, and throws if it does not hold:
     * wrong secret and damaged file look the same.
     */
    fun ouvrir(entree: InputStream, cle: ByteArray, enTete: EnTete, octetsDeLEnTete: ByteArray): InputStream =
        CipherInputStream(entree, gcm(chiffrer = false, cle, enTete, octetsDeLEnTete))

    /** [entree], refused past [TAILLE_MAX] bytes really read. */
    fun borne(entree: InputStream): InputStream = object : FilterInputStream(entree) {
        private var lus = 0L

        override fun read(): Int = super.read().also { if (it >= 0) compter(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) compter(it) }

        override fun skip(n: Long): Long = super.skip(n).also { compter(it.toInt()) }

        private fun compter(n: Int) {
            lus += n
            if (lus > TAILLE_MAX) throw SauvegardeRefuseeException(Refus.TROP_GROS)
        }
    }

    private fun gcm(chiffrer: Boolean, cle: ByteArray, enTete: EnTete, aad: ByteArray) =
        GCMBlockCipher.newInstance(AESEngine.newInstance()).apply {
            init(chiffrer, AEADParameters(KeyParameter(cle), VaultParams.TAG_BITS, enTete.nonce, aad))
        }
}
