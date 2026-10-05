package com.filestech.notes_tech.ui.common

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Told when the app is about to open another activity for a result, so that the app lock does not
 * lock behind the user's back. `MainActivity` provides the real one (`AppLockLifecycle`).
 */
fun interface ExternalActivityGuard {
    fun onExternalActivityLaunched()
}

/**
 * The default does nothing, which means **the lock still happens**: a screen composed outside
 * `MainActivity` (a preview, a test) fails closed, never open.
 */
val LocalExternalActivityGuard = staticCompositionLocalOf { ExternalActivityGuard { } }

/** Same `launch` as the platform launcher, with the guard told first — unless [sparesLock] is false. */
class AppResultLauncher<I> internal constructor(
    private val launcher: ManagedActivityResultLauncher<I, *>,
    private val guard: ExternalActivityGuard,
    private val sparesLock: Boolean,
) {
    fun launch(input: I) {
        if (sparesLock) guard.onExternalActivityLaunched()
        launcher.launch(input)
    }
}

/**
 * Whether opening [this] contract spares the app its re-lock.
 *
 * **Not a permission request.** The system permission dialog is translucent: it pauses the activity
 * without stopping it, so it never triggers the lock and needs no pass. Giving it one opened a hole
 * found in external review of Agenda Tech (Gemini Pro, 2026-09-15): request a permission, press Home
 * within the launch window, and the stop that followed was spared — the app reopened unlocked.
 *
 * Decided by type rather than by a flag at each call site, so that a permission launcher added later
 * cannot forget it. On a device whose permission UI does stop the activity, the app simply locks: the
 * safe side.
 */
internal fun ActivityResultContract<*, *>.sparesRelock(): Boolean =
    this !is ActivityResultContracts.RequestPermission &&
        this !is ActivityResultContracts.RequestMultiplePermissions

/**
 * **The only way this app opens an activity for a result.** A drop-in for
 * `rememberLauncherForActivityResult`, which nothing else may call — enforced by
 * `AppResultLauncherIsTheOnlySeamTest`.
 *
 * One seam rather than a line at each call site: the defect Agenda Tech met across eight launchers
 * was the one written later with the platform API, which silently locked the user out of its own
 * flow again.
 */
@Composable
fun <I, O> rememberAppResultLauncher(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): AppResultLauncher<I> {
    val launcher = rememberLauncherForActivityResult(contract, onResult)
    val guard = LocalExternalActivityGuard.current
    return remember(launcher, guard) { AppResultLauncher(launcher, guard, contract.sparesRelock()) }
}
