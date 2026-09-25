package com.filestech.notes_tech.ui.common

import androidx.annotation.StringRes
import com.filestech.notes_tech.R
import com.filestech.notes_tech.domain.repository.VaultLockedException
import com.filestech.notes_tech.security.clipboard.ClipboardException
import com.filestech.notes_tech.security.vault.KeystoreDeviceNotSecureException
import com.filestech.notes_tech.security.vault.KeystoreKeyMissingException
import com.filestech.notes_tech.security.vault.KeystoreSoftwareOnlyException
import com.filestech.notes_tech.security.vault.MalformedVaultDataException
import com.filestech.notes_tech.security.vault.VaultPinWipedException
import com.filestech.notes_tech.security.vault.VaultSessionClosedException
import com.filestech.notes_tech.security.vault.VaultValidationException
import com.filestech.notes_tech.security.vault.WrongPinException
import com.filestech.notes_tech.security.vault.WrongSecretException

/**
 * The text shown to the user for an error that reaches the screen — the port's `describeError`
 * (notes_tech 2.0.9, `lib/utils/error_localize.dart`).
 *
 * ## Never the exception's own text
 *
 * Until 2026-09-24 six screens displayed `exception.message` as it was. That text is developer
 * text: in this port it is unaccented internal French ("keystore momentanement indisponible",
 * "enregistrement prealable echoue"), shown as such to an English-speaking user, and an I/O
 * message can carry a path of the app's sandbox. The published app stopped doing the same thing in
 * 2.0.9, after a reviewer saw French where he expected English.
 *
 * A recognised error gets its own sentence; anything else gets [R.string.error_unexpected]. The
 * exception itself is still logged by the caller — the diagnosis moves to the log, where it
 * belongs, instead of disappearing.
 *
 * ⚠️ One table for the whole app, on purpose: six screens each deciding what to say about the same
 * exception is how a `KeystoreUnavailableException` ended up read as "retry later" in one place and
 * as raw text in another.
 */
@StringRes
fun userMessageFor(error: Throwable): Int = when (error) {
    is VaultLockedException, is VaultSessionClosedException -> R.string.error_vault_locked
    is WrongPinException -> R.string.error_vault_pin_wrong
    is WrongSecretException -> R.string.error_vault_passphrase_wrong
    is VaultPinWipedException -> when (error.reason) {
        VaultPinWipedException.Reason.TOO_MANY_ATTEMPTS -> R.string.error_vault_pin_wiped
        VaultPinWipedException.Reason.KEY_INVALIDATED -> R.string.vault_pin_wiped_key_invalidated
    }
    is KeystoreKeyMissingException -> R.string.vault_pin_key_missing
    is VaultValidationException -> refusalMessageFor(error.reason)
    is MalformedVaultDataException -> R.string.error_vault_encrypted_content_invalid
    is KeystoreSoftwareOnlyException -> R.string.error_vault_pin_hardware_unavailable
    is KeystoreDeviceNotSecureException -> R.string.error_vault_pin_needs_screen_lock
    is ClipboardException -> R.string.error_clipboard_secure_unavailable
    else -> R.string.error_unexpected
}

/**
 * A refused input, explained.
 *
 * ⚠️ Exhaustive `when`, no `else`: a new [VaultValidationException.Reason] must fail to compile
 * until someone decides what it tells the user. Moved here from `VaultSheets` so that the sheets
 * and [userMessageFor] cannot give two answers for the same reason.
 */
@StringRes
fun refusalMessageFor(reason: VaultValidationException.Reason): Int = when (reason) {
    VaultValidationException.Reason.PASSPHRASE_TOO_SHORT -> R.string.error_vault_passphrase_too_short
    VaultValidationException.Reason.PIN_LENGTH_OUT_OF_RANGE -> R.string.error_vault_pin_too_short
    VaultValidationException.Reason.PIN_NOT_DIGITS_ONLY -> R.string.error_vault_pin_not_digits
    VaultValidationException.Reason.ALREADY_A_VAULT -> R.string.error_vault_already_enabled
    VaultValidationException.Reason.NOT_A_VAULT -> R.string.error_vault_not_avault
    VaultValidationException.Reason.NOT_A_PIN_VAULT -> R.string.error_vault_not_pin_vault
    // No dedicated sentence exists for these two in either app: the folder vanished, or the sheet
    // offered a passphrase for a PIN vault. Neither is the user's doing, neither is actionable.
    VaultValidationException.Reason.FOLDER_NOT_FOUND,
    VaultValidationException.Reason.NOT_A_PASSPHRASE_VAULT,
    -> R.string.error_unexpected
}
