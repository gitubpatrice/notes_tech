package com.filestech.notes_tech.security.applock

import com.filestech.notes_tech.core.crypto.SecretBytes
import com.filestech.notes_tech.core.crypto.wipe
import com.filestech.notes_tech.security.vault.VaultCrypto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the disk keeps of the app lock PIN: a salt and a tag. Never the PIN, and nothing that can be
 * checked away from this device — see [AppLockKeystore].
 *
 * Not a `data class`: its `equals` would compare the arrays by reference.
 */
class StoredPin(salt: ByteArray, tag: ByteArray) {

    private val saltBytes = salt.copyOf()
    private val tagBytes = tag.copyOf()

    val salt: ByteArray get() = saltBytes.copyOf()
    val tag: ByteArray get() = tagBytes.copyOf()

    /** `v1:<salt hex>:<tag hex>` — versioned, so that a future format is told apart, not misread. */
    fun encode(): String = "$VERSION:${SecretBytes.toHex(saltBytes)}:${SecretBytes.toHex(tagBytes)}"

    override fun toString(): String = "StoredPin(${saltBytes.size} + ${tagBytes.size} bytes)"

    companion object {
        private const val VERSION = "v1"

        /**
         * `null` when [encoded] is not a well-formed v1 value.
         *
         * ⚠️ A `null` here must NOT be read as "no lock": the caller keeps the app locked and offers
         * only the erase path. Treating an unreadable verifier as an absent one would turn a
         * corrupted — or tampered — preference into a way around the lock.
         */
        fun decode(encoded: String): StoredPin? {
            val parts = encoded.split(':')
            if (parts.size != 3 || parts[0] != VERSION) return null
            return runCatching {
                val salt = SecretBytes.fromHex(parts[1])
                val tag = SecretBytes.fromHex(parts[2])
                val wellFormed = salt.size == AppLockParams.SALT_BYTES && tag.size == AppLockParams.TAG_BYTES
                if (wellFormed) StoredPin(salt, tag) else null
            }.getOrNull()
        }
    }
}

/** The rules of the app lock, and nothing else. */
object AppLockParams {

    /** Same bounds as a PIN vault (`VaultParams.PIN_MIN_LENGTH`..`PIN_MAX_LENGTH`) — one notion of "PIN" in the app. */
    const val PIN_MIN_LENGTH = 4
    const val PIN_MAX_LENGTH = 6

    /**
     * Argon2id cost: the PIN vault's lightened parameters (32 MiB, t = 2). They are not what
     * protects a short PIN — the Keystore key is — they slow down whoever gets to use that key.
     */
    const val ARGON2_ITERATIONS = 2
    const val ARGON2_MEMORY_KIB = 32 * 1024

    const val SALT_BYTES = 16

    /** HMAC-SHA256 output. */
    const val TAG_BYTES = 32

    /** Domain separation for the MAC input. Immutable once shipped: changing it locks every user out. */
    const val MAC_LABEL = "files-tech.notes_tech.app_lock.v1"

    /** Digits only, within bounds. A PIN is typed on a numeric pad and nothing else is accepted. */
    fun isValidPin(pin: String): Boolean = pin.length in PIN_MIN_LENGTH..PIN_MAX_LENGTH && pin.all { it in '0'..'9' }
}

/**
 * Turns a PIN into a [StoredPin], and checks a PIN against one.
 *
 * An interface for one reason: [AppLockManager]'s throttle is tested on the JVM with dozens of
 * attempts, and each real check costs an Argon2id run. [AppLockPinVerifier] has its own tests.
 */
interface PinVerifier {

    /**
     * @throws IllegalArgumentException [pin] is not 4 to 6 digits.
     * @throws AppLockKeystoreUnavailableException the key could not be created or used.
     */
    fun create(pin: String): StoredPin

    /**
     * `true` if [pin] is the one [stored] was made from.
     *
     * @throws AppLockKeyMissingException / [AppLockKeystoreUnavailableException] — neither is a wrong
     *   PIN, and neither may be counted as one.
     */
    fun matches(pin: String, stored: StoredPin): Boolean
}

/**
 * `tag = HMAC_deviceKey(label ‖ Argon2id(pin, salt))`. The derivation runs on the caller's thread and
 * costs a few hundred milliseconds on a 2018 phone: call it off the main thread.
 */
@Singleton
class AppLockPinVerifier @Inject constructor(private val keystore: AppLockKeystore) : PinVerifier {

    override fun create(pin: String): StoredPin {
        require(AppLockParams.isValidPin(pin)) { "invalid app lock PIN format" }
        keystore.ensureKey()
        val salt = SecretBytes.randomBytes(AppLockParams.SALT_BYTES)
        val tag = tagFor(pin, salt)
        return try {
            StoredPin(salt, tag)
        } finally {
            tag.wipe()
        }
    }

    /** Constant-time comparison of the tags. */
    override fun matches(pin: String, stored: StoredPin): Boolean {
        // A malformed candidate cannot be the PIN, and is not worth an Argon2id run.
        if (!AppLockParams.isValidPin(pin)) return false
        val tag = tagFor(pin, stored.salt)
        return try {
            SecretBytes.constantTimeEquals(tag, stored.tag)
        } finally {
            tag.wipe()
        }
    }

    private fun tagFor(pin: String, salt: ByteArray): ByteArray {
        val secret = pin.toByteArray(Charsets.UTF_8)
        val derived = try {
            VaultCrypto.deriveKey(secret, salt, AppLockParams.ARGON2_ITERATIONS, AppLockParams.ARGON2_MEMORY_KIB)
        } finally {
            secret.wipe()
        }
        val label = AppLockParams.MAC_LABEL.toByteArray(Charsets.UTF_8)
        val input = label + derived
        return try {
            keystore.mac(input)
        } finally {
            derived.wipe()
            input.wipe()
        }
    }
}

/**
 * How long to wait after a wrong PIN.
 *
 * Five free attempts — typos happen — then 30 s, doubling at each further failure, capped at one
 * hour. Never an erase: a child playing with the phone must not be able to destroy the notes by
 * typing badly. The vault PINs self-destruct because they guard the notes themselves; this lock only
 * guards the screen, and the notes behind it are already encrypted.
 */
object AppLockThrottle {

    const val FREE_ATTEMPTS = 5
    const val FIRST_DELAY_MILLIS = 30_000L
    const val MAX_DELAY_MILLIS = 60 * 60_000L

    /** The wait imposed after the [failures]-th consecutive failure. */
    fun delayAfter(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0L
        val doublings = (failures - FREE_ATTEMPTS).coerceAtMost(MAX_DOUBLINGS)
        return (FIRST_DELAY_MILLIS shl doublings).coerceAtMost(MAX_DELAY_MILLIS)
    }

    /** 30 s × 2^7 = 64 min > 1 h: beyond that the cap applies anyway, and the shift cannot overflow. */
    private const val MAX_DOUBLINGS = 7
}
