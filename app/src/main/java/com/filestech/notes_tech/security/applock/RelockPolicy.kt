package com.filestech.notes_tech.security.applock

/** What leaving the foreground means for the lock. */
enum class StopDecision {
    /** No lock configured, or the activity is only being recreated. */
    NONE,

    /** A picker the app opened caused this stop: no lock, but the screen and the clock are watched. */
    SPARED_FOR_PICKER,

    LOCK_NOW,

    /** A delay is set: the app locks if the user comes back after it. */
    LOCK_LATER,
}

/**
 * Every decision `MainActivity`'s lifecycle takes about the app lock — and none of its Android.
 *
 * ## Why this is process-scoped, not activity-scoped
 *
 * The instance lives in the [AppLockLifecycle] singleton, not in the activity. An activity destroyed
 * in the background ("don't keep activities", or memory pressure) comes back as a new instance: had
 * the pending delay or the picker's pass lived in the old one, the new one would have forgotten that
 * the app left the foreground, and opened unlocked. Agenda Tech keeps its picker policy in the
 * activity and has that hole.
 */
class RelockPolicy(private val now: () -> Long) {

    private val picker = PickerRelockPolicy(now)

    private var leftAt: Long? = null
    private var delayMillis = 0L

    val isSparingForPicker: Boolean get() = picker.isSparingLock

    fun onExternalActivityLaunched() = picker.onExternalActivityLaunched()

    /**
     * `onStop`, first half: whether this stop is the one a picker caused. Consumed on EVERY stop,
     * before the lock decision, so that a launch made while no lock was configured cannot leave a
     * pass for a later stop.
     */
    fun sparesStopForPicker(): Boolean = picker.sparesLockOnStop()

    /**
     * `onStop`, second half. [sparedForPicker] is the first half's answer, AFTER the caller checked the
     * screen — see [AppLockLifecycle.onStop] for why that order matters.
     */
    fun decideStop(configured: Boolean, sparedForPicker: Boolean, delay: RelockDelay): StopDecision {
        leftAt = null
        return when {
            !configured -> StopDecision.NONE
            sparedForPicker -> StopDecision.SPARED_FOR_PICKER
            delay == RelockDelay.IMMEDIATELY -> StopDecision.LOCK_NOW
            else -> {
                leftAt = now()
                delayMillis = delay.millis
                StopDecision.LOCK_LATER
            }
        }
    }

    /**
     * `onStart`: true when the app must lock before showing anything — back from a picker too late,
     * or back after the delay. Both are consumed.
     */
    fun onStart(): Boolean {
        val lateFromPicker = picker.locksOnReturn()
        val left = leftAt
        leftAt = null
        val delayElapsed = left != null && now() - left >= delayMillis
        return lateFromPicker || delayElapsed
    }

    /**
     * The screen turned off while a picker the app opened was on top: the pass ends there, and the
     * ordinary rule takes over from this moment — lock now, or once [delay] has passed. True when the
     * app must lock now.
     *
     * Agenda Tech locks at once, having no delay. Here a phone put down on a picker is treated like a
     * phone put down in the app, which also stops the activity: the user's delay applies to both.
     */
    fun onScreenOff(delay: RelockDelay): Boolean {
        if (!picker.onScreenOff()) return false
        if (delay == RelockDelay.IMMEDIATELY) return true
        leftAt = now()
        delayMillis = delay.millis
        return false
    }

    /** `onStop` found the screen already off: the pass is void, and the stop is an ordinary one. */
    fun cancelPickerPass() {
        picker.onScreenOff()
    }

    /** `onNewIntent`: true when the app must lock — see [PickerRelockPolicy.onExternalIntent]. */
    fun onNewIntent(): Boolean = picker.onExternalIntent()

    fun onResume() = picker.onResumed()
}
