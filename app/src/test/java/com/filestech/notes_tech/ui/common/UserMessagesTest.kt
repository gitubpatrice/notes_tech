package com.filestech.notes_tech.ui.common

import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.repository.VaultLockedException
import com.filestech.notes_tech.security.clipboard.ClipboardException
import com.filestech.notes_tech.security.vault.KeystoreDeviceNotSecureException
import com.filestech.notes_tech.security.vault.KeystoreSoftwareOnlyException
import com.filestech.notes_tech.security.vault.KeystoreUnavailableException
import com.filestech.notes_tech.security.vault.VaultSessionClosedException
import com.filestech.notes_tech.security.vault.VaultValidationException
import com.filestech.notes_tech.security.vault.WrongPinException
import com.filestech.notes_tech.security.vault.WrongSecretException
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * `userMessageFor`: a recognised error gets its own sentence, anything else the generic one — never
 * the exception's text, which is what six screens showed until 2026-09-24.
 */
class UserMessagesTest {

    @Test
    @DisplayName("the two new PIN vault creation failures get their own sentences")
    fun pin_vault_creation_failures() {
        val noScreenLock = userMessageFor(KeystoreDeviceNotSecureException())
        val softwareOnly = userMessageFor(KeystoreSoftwareOnlyException())

        assertThat(noScreenLock).isEqualTo(R.string.error_vault_pin_needs_screen_lock)
        assertThat(softwareOnly).isEqualTo(R.string.error_vault_pin_hardware_unavailable)
    }

    @Test
    @DisplayName("a locked vault reads the same through both of its exceptions")
    fun locked_vault() {
        assertThat(userMessageFor(VaultLockedException("n", "f"))).isEqualTo(R.string.error_vault_locked)
        assertThat(userMessageFor(VaultSessionClosedException("f"))).isEqualTo(R.string.error_vault_locked)
    }

    @Test
    @DisplayName("a wrong PIN and a wrong passphrase are told apart")
    fun wrong_secrets() {
        assertThat(userMessageFor(WrongPinException(attemptsRemaining = 3))).isEqualTo(R.string.error_vault_pin_wrong)
        assertThat(userMessageFor(WrongSecretException())).isEqualTo(R.string.error_vault_passphrase_wrong)
    }

    @Test
    @DisplayName("a refused input goes through the same table as the vault sheets")
    fun refused_input() {
        val refus = VaultValidationException(VaultValidationException.Reason.PIN_NOT_DIGITS_ONLY)

        assertThat(userMessageFor(refus)).isEqualTo(R.string.error_vault_pin_not_digits)
        assertThat(userMessageFor(refus)).isEqualTo(refusalMessageFor(refus.reason))
    }

    @Test
    @DisplayName("the secure clipboard refusal is named")
    fun clipboard() {
        val message = userMessageFor(ClipboardException("interne"))

        assertThat(message).isEqualTo(R.string.error_clipboard_secure_unavailable)
    }

    /**
     * The case that motivates the whole function: an exception with a perfectly readable message —
     * a path, internal French — must NOT reach the screen through it.
     */
    @Test
    @DisplayName("anything unrecognised gets the generic sentence, whatever its own text says")
    fun unrecognised() {
        val diskFull = IOException("/data/user/0/com.filestech.notes_tech/files/exports: ENOSPC")
        val internal = IllegalStateException("enregistrement prealable echoue")

        assertThat(userMessageFor(diskFull)).isEqualTo(R.string.error_unexpected)
        assertThat(userMessageFor(KeystoreUnavailableException())).isEqualTo(R.string.error_unexpected)
        assertThat(userMessageFor(internal)).isEqualTo(R.string.error_unexpected)
    }

    @Test
    @DisplayName("every refusal reason maps to a sentence, the two without one to the generic sentence")
    fun every_refusal_reason() {
        for (reason in VaultValidationException.Reason.entries) {
            assertThat(refusalMessageFor(reason)).isNotEqualTo(0)
        }
        val folderGone = refusalMessageFor(VaultValidationException.Reason.FOLDER_NOT_FOUND)

        assertThat(folderGone).isEqualTo(R.string.error_unexpected)
    }
}
