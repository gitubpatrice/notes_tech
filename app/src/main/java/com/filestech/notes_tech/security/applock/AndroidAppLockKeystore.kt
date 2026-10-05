package com.filestech.notes_tech.security.applock

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AppLockKeystore] backed by `AndroidKeyStore`: an HMAC-SHA256 key that never leaves the device.
 *
 * No user authentication, no unlocked-device requirement, no hardware requirement — see
 * [AppLockKeystore] for why each of the three would lock out the users who need the lock most.
 *
 * ⚠️ The price of the second one (security audit of 2026-09-26, K5): on a seized phone, code running
 * under the app's UID can use this key as an oracle — Argon2id precomputed elsewhere, one HMAC per
 * candidate — and find the app PIN. It opens no vault; it matters only if the PIN is reused as the
 * screen lock's. Written in D-023, not "fixed": Android would delete a bound key with the screen lock.
 */
@Singleton
class AndroidAppLockKeystore @Inject constructor() : AppLockKeystore {

    override fun ensureKey() {
        val store = keyStore()
        if (contains(store)) return
        try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generator.generateKey()
        } catch (e: Exception) {
            throw AppLockKeystoreUnavailableException(e)
        }
    }

    override fun mac(data: ByteArray): ByteArray {
        val store = keyStore()
        val key = try {
            store.getKey(ALIAS, null)
        } catch (e: Exception) {
            throw AppLockKeystoreUnavailableException(e)
        } ?: throw AppLockKeyMissingException()
        return try {
            val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
            mac.init(key as SecretKey)
            mac.doFinal(data)
        } catch (e: Exception) {
            throw AppLockKeystoreUnavailableException(e)
        }
    }

    override fun deleteKey() {
        val store = keyStore()
        try {
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
        } catch (e: Exception) {
            throw AppLockKeystoreUnavailableException(e)
        }
        // ⚠️ Re-read, like `AndroidVaultKeystore.deleteKeysWithPrefix`: a `deleteEntry` that returns
        // is a promise of the Keystore implementation, not an observation. The panic step that calls
        // this must not report a key as gone while it is still there.
        if (contains(keyStore())) {
            throw AppLockKeystoreUnavailableException(IllegalStateException("app lock key survived deletion"))
        }
    }

    override fun hasKey(): Boolean = contains(keyStore())

    /** Loaded on every call: a transient `load` failure must not stick for the process lifetime. */
    private fun keyStore(): KeyStore = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (e: Exception) {
        throw AppLockKeystoreUnavailableException(e)
    }

    private fun contains(store: KeyStore): Boolean = try {
        store.containsAlias(ALIAS)
    } catch (e: Exception) {
        throw AppLockKeystoreUnavailableException(e)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /**
         * ⚠️ Deliberately OUTSIDE the `vault_pin_` prefix: the panic step that sweeps PIN vault keys
         * must not be what deletes this one by accident, and this one must not be missed by a sweep
         * that only knows vaults. It has its own panic step.
         */
        const val ALIAS = "app_lock_hmac_v1"
    }
}
