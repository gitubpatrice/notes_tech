package com.filestech.notes_tech.security.applock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app lock's two Keystore keys, on the device's real Keystore.
 *
 * ## ⚠️ Refuses to run over a configured lock
 *
 * Keystore aliases cannot be diverted like a preference file (04-PIEGES §72): these cases create and
 * delete THE keys of the debug install. Over a configured lock, deleting them would make its PIN
 * unverifiable for good. The setup therefore FAILS — loudly, not as an ignored case, which a run's
 * "OK" would hide (§45) — when a lock is configured on the device.
 */
@RunWith(AndroidJUnit4::class)
class AppLockKeysTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keystore = AndroidAppLockKeystore()
    private val biometricKey = AndroidBiometricUnlockKey()

    @Before
    fun setUp() {
        check(!PreferencesAppLockStore(LegacyPreferences(context)).configuredNow()) {
            "An app lock is configured on this device: this test would delete its keys. Turn it off first."
        }
        keystore.deleteKey()
        biometricKey.delete()
    }

    @After
    fun tearDown() {
        keystore.deleteKey()
        biometricKey.delete()
    }

    @Test
    fun the_pin_key_is_created_once_and_gives_a_stable_mac() {
        assertThat(keystore.hasKey()).isFalse()
        keystore.ensureKey()
        val first = keystore.mac(DATA)
        keystore.ensureKey()

        assertThat(keystore.hasKey()).isTrue()
        assertThat(keystore.mac(DATA)).isEqualTo(first)
        assertThat(keystore.mac(OTHER)).isNotEqualTo(first)
        assertThat(first).hasLength(AppLockParams.TAG_BYTES)
    }

    /** A deleted key is a missing key — never an answer that could be read as "wrong PIN". */
    @Test
    fun a_deleted_pin_key_is_reported_missing_and_deletion_is_idempotent() {
        keystore.ensureKey()
        keystore.deleteKey()
        keystore.deleteKey()

        assertThat(keystore.hasKey()).isFalse()
        assertThrows(AppLockKeyMissingException::class.java) { keystore.mac(DATA) }
    }

    /** The full verifier on the device: Argon2id, then the HMAC in the Keystore. */
    @Test
    fun the_verifier_accepts_its_pin_and_only_it_on_the_device() {
        val verifier = AppLockPinVerifier(keystore)
        val stored = verifier.create("2468")

        assertThat(verifier.matches("2468", stored)).isTrue()
        assertThat(verifier.matches("2469", stored)).isFalse()
    }

    /** No key is "biometrics turned off", never a key made in silence (the defect SMS Tech shipped). */
    @Test
    fun without_its_key_the_biometric_unlock_is_invalidated_not_recreated() {
        assertThat(biometricKey.prepare()).isEqualTo(BiometricPreparation.Invalidated)
        assertThat(biometricKey.prepare()).isEqualTo(BiometricPreparation.Invalidated)
    }

    /**
     * 🔴 The point of the key: a cipher the OS has not seen a biometric for does NOT run, so an unlock
     * cannot be forged by calling the success path by hand.
     *
     * ⚠️ Needs a device with a biometric enrolled — the key cannot be made otherwise. The creation is
     * TRIED, not asked about (SMS Tech: four cases counted green that never ran, their precondition a
     * question the device answered wrongly), and a device without one reports this case as an
     * assumption failure — `INSTRUMENTATION_STATUS_CODE: -4` — never as a pass.
     */
    @Test
    fun an_unauthenticated_cipher_never_verifies() {
        val created = runCatching { biometricKey.create() }
        assumeTrue("no biometric enrolled on this device: ${created.exceptionOrNull()}", created.isSuccess)
        val preparation = biometricKey.prepare()
        assertThat(preparation).isInstanceOf(BiometricPreparation.Ready::class.java)

        assertThat(biometricKey.verify((preparation as BiometricPreparation.Ready).cipher)).isFalse()
        assertThat(biometricKey.verify(null)).isFalse()
    }

    /** The other side of the same fact: without a biometric, no key — and no key left behind. */
    @Test
    fun a_failed_creation_leaves_no_key() {
        val created = runCatching { biometricKey.create() }
        assumeTrue("a biometric is enrolled on this device", created.isFailure)
        assertThat(biometricKey.prepare()).isEqualTo(BiometricPreparation.Invalidated)
    }

    private companion object {
        val DATA = "files-tech.notes_tech.app_lock.test".toByteArray()
        val OTHER = "files-tech.notes_tech.app_lock.other".toByteArray()
    }
}
