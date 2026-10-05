package com.filestech.notes_tech.security.applock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// The app lock's device-bound pieces, in memory — shared by the manager's tests and the settings
// ViewModel's. Every write is recorded in `log`, so that an ORDER can be asserted, not only a result.

/** The store. A "process restart" is a new manager on the same instance. */
internal class FakeAppLockStore(private val log: MutableList<String> = mutableListOf()) : AppLockStore {

    /** The encoded PIN as the preference would hold it — or anything else, to simulate damage. */
    val pin = MutableStateFlow<String?>(null)
    private val biometricFlow = MutableStateFlow(false)
    private val delayFlow = MutableStateFlow(RelockDelay.IMMEDIATELY)
    var persisted = PersistedThrottle.NONE
    var refuseWrites = false

    var biometric: Boolean
        get() = biometricFlow.value
        set(value) {
            biometricFlow.value = value
        }

    var delay: RelockDelay
        get() = delayFlow.value
        set(value) {
            delayFlow.value = value
        }

    override fun configuredNow() = pin.value != null
    override val configured = pin.map { it != null }
    override fun pinRecord(): PinRecord {
        val encoded = pin.value ?: return PinRecord.Absent
        return StoredPin.decode(encoded)?.let(PinRecord::Present) ?: PinRecord.Unreadable
    }
    override fun biometricEnabledNow() = biometric
    override val biometricEnabled: StateFlow<Boolean> = biometricFlow
    override fun relockDelayNow() = delay
    override val relockDelay: StateFlow<RelockDelay> = delayFlow

    override fun enable(pin: StoredPin) = write("store.enable") {
        this.pin.value = pin.encode()
        biometric = false
        delay = RelockDelay.IMMEDIATELY
        persisted = PersistedThrottle.NONE
    }
    override fun replacePin(pin: StoredPin) = write("store.replacePin") { this.pin.value = pin.encode() }
    override fun disable() = write("store.disable") {
        pin.value = null
        biometric = false
        delay = RelockDelay.IMMEDIATELY
        persisted = PersistedThrottle.NONE
    }
    override fun setBiometricEnabled(enabled: Boolean) = write("store.biometric=$enabled") { biometric = enabled }
    override fun setRelockDelay(delay: RelockDelay) = write("store.delay=$delay") { this.delay = delay }
    override fun throttle() = persisted
    var duringSave: () -> Unit = {}
    override fun saveThrottle(throttle: PersistedThrottle) = write("store.throttle") {
        duringSave()
        persisted = throttle
    }

    private fun write(what: String, change: () -> Unit): Boolean {
        if (refuseWrites) return false
        change()
        log += what
        return true
    }
}

/**
 * Fast and deterministic: the tag is SHA-256 of the PIN. The real Argon2id + HMAC verifier has its own
 * tests (`AppLockPinTest`); here dozens of attempts must not each cost a key derivation.
 */
internal class FakePinVerifier : PinVerifier {
    val checks = AtomicInteger()
    var failure: Exception? = null
    var duringCheck: () -> Unit = {}
    var duringCreate: () -> Unit = {}

    override fun create(pin: String): StoredPin {
        require(AppLockParams.isValidPin(pin))
        failure?.let { throw it }
        duringCreate()
        return StoredPin(ByteArray(AppLockParams.SALT_BYTES), digest(pin))
    }

    override fun matches(pin: String, stored: StoredPin): Boolean {
        checks.incrementAndGet()
        failure?.let { throw it }
        duringCheck()
        return MessageDigest.isEqual(digest(pin), stored.tag)
    }

    private fun digest(pin: String) = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
}

internal class FakeAppLockKeystore(private val log: MutableList<String> = mutableListOf()) : AppLockKeystore {
    var deleteFails = false
    override fun ensureKey() = Unit
    override fun mac(data: ByteArray) = data
    override fun deleteKey() {
        log += "keystore.delete"
        if (deleteFails) throw AppLockKeystoreUnavailableException()
    }
    override fun hasKey() = true
}

/** A biometric key whose prompt "succeeds" when handed a cipher — the JVM has no TEE to ask. */
internal class FakeBiometricUnlockKey(private val log: MutableList<String> = mutableListOf()) : BiometricUnlockKey {
    var exists = false
    var createFails = false

    override fun create() {
        if (createFails) error("no biometric enrolled")
        exists = true
        log += "biometric.create"
    }

    override fun prepare(): BiometricPreparation = if (exists) {
        BiometricPreparation.Ready(
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(KEY_BYTES), "AES"))
            },
        )
    } else {
        BiometricPreparation.Invalidated
    }

    override fun verify(cipher: Cipher?) = cipher != null && exists

    override fun delete() {
        exists = false
        log += "biometric.delete"
    }

    private companion object {
        const val KEY_BYTES = 16
    }
}
