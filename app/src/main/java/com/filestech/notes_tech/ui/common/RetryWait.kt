package com.filestech.notes_tech.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import com.filestech.notes_tech.R

/**
 * "Too many attempts. Try again in …" — for a vault PIN or passphrase under throttling, and for the
 * app lock.
 *
 * ## Why a sentence of its own
 *
 * The vault sheets showed `common_error_with` + "12 s", i.e. "Error: 12 s". notes_tech 2.0.9
 * removed `common_error_with` (no exception text on screen) and shows "Vault locked." in that case,
 * which drops the one piece of information the user needs: how long to wait. This keeps it, in
 * words.
 *
 * Seconds under a minute, whole minutes above — "Try again in 3600 seconds" reads like a timer
 * nobody wants to watch. Always rounded UP: announcing 0 s while the throttle still refuses would
 * make the next tap look broken.
 */
@Composable
fun retryWaitMessage(remainingMillis: Long): String {
    val seconds = ((remainingMillis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceAtLeast(1L)
    return if (seconds < SECONDS_PER_MINUTE) {
        val n = seconds.toInt()
        pluralStringResource(R.plurals.common_retry_in_seconds, n, n)
    } else {
        val minutes = ((seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).toInt()
        pluralStringResource(R.plurals.common_retry_in_minutes, minutes, minutes)
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
