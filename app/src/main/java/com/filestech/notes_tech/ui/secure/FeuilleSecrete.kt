package com.filestech.notes_tech.ui.secure

import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.ui.window.SecureFlagPolicy

/**
 * 🔴 **The window of a sheet where a secret is typed is protected from its first frame** — security
 * audit of 2026-09-26, F2.
 *
 * A `ModalBottomSheet` is a window of its own. Its default policy, `Inherit`, copies the parent
 * window's `FLAG_SECURE` **in its constructor**, during the composition that also runs
 * [SecureWindowGuard] — whose request reaches the activity's window only at a later recomposition.
 * With "Hide in recent apps" turned off, the vault code pad, the passphrase and the app lock PIN were
 * then typed in a window a screen recording could capture, although the guard of each sheet promises
 * the flag "even if the user turned the setting off". Measured in Material3 1.4.0's bytecode by the
 * audit's verifier (`ModalBottomSheetDialogWrapper.<init>` → `setSecurePolicy`).
 *
 * The guard stays: it protects the window behind the sheet, and the recent-apps thumbnail.
 */
val ProprietesDeFeuilleSecrete = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn)
