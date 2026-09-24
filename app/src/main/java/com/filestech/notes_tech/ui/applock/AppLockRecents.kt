package com.filestech.notes_tech.ui.applock

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.filestech.notes_tech.ui.secure.SecureWindowGuard

/** How the app keeps its content out of the recent apps screen while a lock is configured (D-023). */
object AppLockRecents {

    /**
     * API 33+: `Activity.setRecentsScreenshotEnabled(false)` replaces the preview with a placeholder,
     * and leaves screenshots to the user's own setting. Below, `FLAG_SECURE` is the only tool — and it
     * blocks screenshots too.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    fun hidesWithoutSecureFlag(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

/**
 * Keeps the notes out of the recent apps screen while a lock is **configured** — not only while it is
 * locked. With a delay the app goes to the background unlocked, and that is when the system takes its
 * preview; the preview is also what Android shows for an instant when the app comes back, before the
 * lock screen is drawn.
 *
 * Below Android 13 this is a [SecureWindowGuard], so `SecureWindowController` stays the only author
 * of `FLAG_SECURE`: its counter composes this request with the user's setting and the other screens'.
 */
@Composable
fun RecentsGuard(lockConfigured: Boolean) {
    if (AppLockRecents.hidesWithoutSecureFlag()) {
        val activity = LocalActivity.current
        DisposableEffect(activity, lockConfigured) {
            activity?.setRecentsScreenshotEnabled(!lockConfigured)
            onDispose { }
        }
    } else {
        SecureWindowGuard(active = lockConfigured)
    }
}
