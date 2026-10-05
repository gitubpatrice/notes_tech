package com.filestech.notes_tech.security.applock

/**
 * Whether leaving the foreground for an activity **the app itself opened for a result** — the
 * document picker that imports the dictation model — spares the app its re-lock.
 *
 * Taken from Agenda Tech (`security/PickerRelockPolicy.kt`, v1.1.1 and its pre-tag audit), where the
 * defect was measured: a picker is a separate activity, so opening one stopped `MainActivity` and
 * locked it; the user typed their PIN in the middle of a flow they had just started, and the screen
 * waiting for the picker's result was gone after it. Patrice's rule: **as long as the user is inside
 * the app, no PIN again.**
 *
 * ## The bounds, and why each exists
 *
 * - [LAUNCH_WINDOW_MS]: only a stop that follows the launch within this window is spared. A launch
 *   that never stopped the activity (a picker that failed to open) must not leave a pass that a later
 *   press on Home would use. [onResumed] expires it as well.
 * - [onScreenOff]: the screen turning off while the picker is open locks at once — a phone put down.
 * - [onExternalIntent]: coming back through an intent rather than through the picker's result is
 *   entering the app from outside, and locks.
 * - [RETURN_GRACE_MS]: a user who comes back from the picker later than this is locked anyway.
 *
 * Known limit, accepted in Agenda Tech and here: someone who leaves the picker for Home and returns
 * **through Recents** with the screen still on gets the app unlocked within [RETURN_GRACE_MS]. Recents
 * delivers no intent, so nothing distinguishes that return from the picker handing back its result.
 *
 * Pure, clock injected, so every decision is tested on the JVM.
 */
class PickerRelockPolicy(private val now: () -> Long) {

    private var launchedAt: Long? = null
    private var stoppedAt: Long? = null

    /**
     * Set by [locksOnReturn] when the user came back in time, cleared by [onResumed]. `onStart` and
     * `onNewIntent` are not ordered by the platform for an activity brought back from the stopped
     * state, so an external intent must still be able to lock between the two.
     */
    private var returnedInTime = false

    /** The app is about to open an activity for a result. */
    fun onExternalActivityLaunched() {
        launchedAt = now()
        stoppedAt = null
        returnedInTime = false
    }

    /**
     * `onStop`: true when this stop is the one that launch caused, so the lock is spared.
     * Consumes the launch — a second stop is never spared by the same one.
     */
    fun sparesLockOnStop(): Boolean {
        val launched = launchedAt ?: return false
        launchedAt = null
        val stopped = now()
        if (stopped - launched > LAUNCH_WINDOW_MS) return false
        stoppedAt = stopped
        return true
    }

    /** `onStart`: true when the user is back from a spared stop too late, and the app must lock now. */
    fun locksOnReturn(): Boolean {
        val stopped = stoppedAt ?: return false
        stoppedAt = null
        if (now() - stopped > RETURN_GRACE_MS) return true
        returnedInTime = true
        return false
    }

    /** True while a spared stop is pending: the screen turning off is listened for only then. */
    val isSparingLock: Boolean
        get() = stoppedAt != null

    /** The screen turned off: true when a spared stop is pending, and the app must lock now. Consumes it. */
    fun onScreenOff(): Boolean {
        if (stoppedAt == null) return false
        stoppedAt = null
        return true
    }

    /**
     * `onNewIntent`: the app was brought back by an intent instead of by the picker handing back its
     * result. True when that happened while a spared stop was pending or before the return completed.
     */
    fun onExternalIntent(): Boolean {
        val pending = stoppedAt != null || returnedInTime
        stoppedAt = null
        returnedInTime = false
        return pending
    }

    /** `onResume`: a launch that did not stop the activity expires here, and the return is complete. */
    fun onResumed() {
        launchedAt = null
        returnedInTime = false
    }

    companion object {
        const val LAUNCH_WINDOW_MS = 3_000L
        const val RETURN_GRACE_MS = 3 * 60_000L
    }
}
