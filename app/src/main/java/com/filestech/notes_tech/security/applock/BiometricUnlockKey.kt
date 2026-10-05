package com.filestech.notes_tech.security.applock

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/** What [BiometricUnlockKey.prepare] could produce. */
sealed interface BiometricPreparation {

    /** Hand [cipher] to `BiometricPrompt` inside a `CryptoObject`. */
    class Ready(val cipher: Cipher) : BiometricPreparation

    /**
     * The key is gone, or dead: the biometrics of the device changed since it was made, or it was
     * never made. Biometric unlock must be turned off and the user told why — never re-armed in
     * silence (see [BiometricUnlockKey]).
     */
    data object Invalidated : BiometricPreparation

    /** The Keystore could not produce a cipher right now. Nothing is concluded; the PIN still works. */
    data object Unavailable : BiometricPreparation
}

/**
 * The key that makes a biometric unlock a proof, not a callback.
 *
 * ## Why a key at all
 *
 * `onAuthenticationSucceeded` is a statement made by the app's own process. Handing `BiometricPrompt`
 * a `CryptoObject` moves the proof into the secure hardware: the key requires a Class 3
 * authentication for EVERY use, so [verify] can only complete an operation if the TEE saw one. The
 * unlock is gated on that operation, not on the callback (Agenda Tech, audit F3).
 *
 * ## ⚠️ Never re-created in silence — the defect SMS Tech shipped
 *
 * The key is invalidated when a fingerprint or a face is ENROLLED on the device. That is the point:
 * someone who can add their finger to the phone must not inherit the unlock. A key re-created on the
 * next attempt would hand it back to them. So a missing or dead key means "biometrics off, use the
 * PIN", and only the settings — after the PIN — make a new one ([create]).
 */
interface BiometricUnlockKey {

    /**
     * Makes a fresh key, replacing any previous one.
     *
     * @throws Exception the Keystore refused — typically no biometric enrolled, or no secure lock
     *   screen, which a Class 3 key requires. The caller reports it; nothing was changed.
     */
    fun create()

    fun prepare(): BiometricPreparation

    /** Runs the operation `BiometricPrompt` authorised. `true` only if it actually ran. */
    fun verify(cipher: Cipher?): Boolean

    /**
     * Deletes the key. Idempotent.
     *
     * @throws AppLockKeystoreUnavailableException the key could not be deleted, or survived — panic
     *   mode must not report it gone.
     */
    fun delete()
}

@Singleton
class AndroidBiometricUnlockKey @Inject constructor() : BiometricUnlockKey {

    override fun create() {
        delete()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                // API 30+: the tier is pinned by the OS as well, so a device credential cannot stand
                // in for the biometric. Below 30, a per-use key (no validity window, the default) can
                // only be unlocked by a biometric through a CryptoObject anyway.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .build()
        generator.init(spec)
        generator.generateKey()
    }

    override fun prepare(): BiometricPreparation = try {
        val key = keyStore().getKey(ALIAS, null) as? SecretKey
        if (key == null) {
            BiometricPreparation.Invalidated
        } else {
            BiometricPreparation.Ready(Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) })
        }
    } catch (e: KeyPermanentlyInvalidatedException) {
        Timber.w(e, "biometric unlock key invalidated by an enrolment")
        BiometricPreparation.Invalidated
    } catch (e: Exception) {
        // Deliberately broad: the Keystore goes through an IPC, and some OEM builds surface failures
        // as unchecked ProviderException or KeyStoreException. A Keystore that misbehaves degrades to
        // "no biometric unlock this time", never to a crash on the lock screen.
        Timber.w(e, "biometric unlock key could not be prepared")
        BiometricPreparation.Unavailable
    }

    override fun verify(cipher: Cipher?): Boolean {
        if (cipher == null) return false
        return try {
            cipher.doFinal(PROOF)
            true
        } catch (e: Exception) {
            // Includes a key invalidated while the prompt was on screen: refuse this unlock.
            Timber.w(e, "the authenticated cipher did not run: unlock refused")
            false
        }
    }

    override fun delete() {
        try {
            val store = keyStore()
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
            // Re-read: a `deleteEntry` that returns is a promise, not an observation.
            if (keyStore().containsAlias(ALIAS)) error("biometric unlock key survived deletion")
        } catch (e: Exception) {
            throw AppLockKeystoreUnavailableException(e)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256

        /** Outside the `vault_pin_` prefix, like the PIN key: panic mode deletes it by its own step. */
        const val ALIAS = "app_lock_biometric_v1"

        /** Arbitrary plaintext: only whether the operation is permitted carries meaning. */
        val PROOF = "notes-tech-app-lock".toByteArray()
    }
}
