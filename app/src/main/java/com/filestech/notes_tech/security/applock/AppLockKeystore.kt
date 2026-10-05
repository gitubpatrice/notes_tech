package com.filestech.notes_tech.security.applock

/**
 * The device-bound key that makes the app lock PIN impossible to guess away from the phone.
 *
 * ## Why a Keystore key for a lock that only gates the interface
 *
 * The app lock does not encrypt anything: the notes are already encrypted at rest, and the database
 * opens at start-up without any user secret. So the PIN protects nothing cryptographically — but
 * the PIN VALUE itself is worth protecting. People reuse PINs: the one chosen here is very likely
 * the one of a PIN vault, of the phone, of a bank card.
 *
 * Stored as a plain salted hash, four to six digits fall to an offline search in seconds — whoever
 * copies the preference file would learn the PIN, and with it the vault's. The vault PIN design
 * rests on exactly the opposite assumption: that the short code can only be tried ON the device,
 * five times. The app lock must not become the side door to it.
 *
 * Hence an HMAC under a non-exportable Android Keystore key: the verifier stored on disk is useless
 * without this device's Keystore.
 *
 * ## ⚠️ Deliberately NOT the vault key recipe
 *
 * `AndroidVaultKeystore` refuses software-only keys and requires an unlocked device
 * (`setUnlockedDeviceRequired`). Both are right for a vault and both would be wrong here: the first
 * forbids the app lock on emulators and on phones without secure hardware, the second forbids it on
 * phones WITHOUT a screen lock — precisely the people who need an app lock most. A software-backed
 * key degrades to "Argon2id only", which is still better than no lock; it is not a reason to refuse.
 */
interface AppLockKeystore {

    /** Creates the key if it does not exist yet. Idempotent. */
    fun ensureKey()

    /**
     * HMAC-SHA256 of [data] under the device key.
     *
     * @throws AppLockKeyMissingException the key does not exist. A deliberate state, not a glitch:
     *   the verifier on disk can no longer be checked by anyone.
     * @throws AppLockKeystoreUnavailableException the Keystore could not be queried. Says nothing
     *   about the PIN — must never count as a failed attempt.
     */
    fun mac(data: ByteArray): ByteArray

    /** Deletes the key. Idempotent: an absent key is not an error. */
    fun deleteKey()

    fun hasKey(): Boolean
}

/** The key is gone: no PIN can be verified any more. */
class AppLockKeyMissingException : Exception("app lock key missing")

/** The Keystore did not answer. Retry later; nothing can be concluded about the PIN. */
class AppLockKeystoreUnavailableException(cause: Throwable? = null) :
    Exception("app lock keystore unavailable", cause)
