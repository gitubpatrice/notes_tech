package com.filestech.notes_tech.security.vault

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The port's replay of notes_tech 2.0.9's `test/keystore_error_mapping_test.dart`: a PIN vault key
 * that cannot be generated on a phone WITHOUT a screen lock is named as such, and nothing else is.
 */
class KeyGenerationFailureTest {

    private val cause = IllegalStateException("vendor-specific text")

    @Test
    @DisplayName("API 28+, no screen lock: the missing lock is named, not taken for a transient failure")
    fun no_screen_lock() {
        val failure = classerLEchecDeGeneration(cause, sdkInt = 28, appareilSecurise = false)

        assertThat(failure).isInstanceOf(KeystoreDeviceNotSecureException::class.java)
        assertThat(failure.cause).isSameInstanceAs(cause)
    }

    @Test
    @DisplayName("API 28+, screen lock present: the failure stays transient")
    fun screen_lock_present() {
        assertThat(classerLEchecDeGeneration(cause, sdkInt = 34, appareilSecurise = true))
            .isInstanceOf(KeystoreUnavailableException::class.java)
    }

    @Test
    @DisplayName("below API 28 the unlocked-device requirement does not exist, so it cannot be the cause")
    fun before_api_28() {
        assertThat(classerLEchecDeGeneration(cause, sdkInt = 27, appareilSecurise = false))
            .isInstanceOf(KeystoreUnavailableException::class.java)
    }

    @Test
    @DisplayName("an unknown security state never tells the user to set a lock they may already have")
    fun unknown_security_state() {
        assertThat(classerLEchecDeGeneration(cause, sdkInt = 30, appareilSecurise = null))
            .isInstanceOf(KeystoreUnavailableException::class.java)
    }
}
