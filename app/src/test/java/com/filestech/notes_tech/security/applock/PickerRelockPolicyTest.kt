package com.filestech.notes_tech.security.applock

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * When opening a picker spares the app its re-lock — and, just as important, when it does not.
 *
 * Agenda Tech's twelve cases, kept as they are: the class is its copy. Every "does not" below is a way
 * the exemption could leak into an ordinary trip out of the app, which would turn "no PIN inside the
 * app" into "no PIN at all".
 */
class PickerRelockPolicyTest {

    private var clock = 1_000_000L
    private val policy = PickerRelockPolicy { clock }

    @Test
    @DisplayName("a picker that stops the app right after its launch spares the lock")
    fun picker_stop_is_spared() {
        policy.onExternalActivityLaunched()
        clock += 400
        assertThat(policy.sparesLockOnStop()).isTrue()
    }

    @Test
    @DisplayName("coming back from the picker in time does not lock")
    fun back_in_time() {
        policy.onExternalActivityLaunched()
        clock += 400
        policy.sparesLockOnStop()
        clock += 60_000
        assertThat(policy.locksOnReturn()).isFalse()
    }

    @Test
    @DisplayName("coming back from the picker too late locks")
    fun back_too_late() {
        policy.onExternalActivityLaunched()
        clock += 400
        policy.sparesLockOnStop()
        clock += PickerRelockPolicy.RETURN_GRACE_MS + 1
        assertThat(policy.locksOnReturn()).isTrue()
    }

    @Test
    @DisplayName("an ordinary stop with no picker is never spared")
    fun ordinary_stop() {
        assertThat(policy.sparesLockOnStop()).isFalse()
        assertThat(policy.locksOnReturn()).isFalse()
    }

    @Test
    @DisplayName("a stop long after the launch is not the picker's and is not spared")
    fun late_stop() {
        policy.onExternalActivityLaunched()
        clock += PickerRelockPolicy.LAUNCH_WINDOW_MS + 1
        assertThat(policy.sparesLockOnStop()).isFalse()
    }

    @Test
    @DisplayName("one launch spares one stop, not the next one")
    fun one_launch_one_stop() {
        policy.onExternalActivityLaunched()
        clock += 400
        assertThat(policy.sparesLockOnStop()).isTrue()
        clock += 1_000
        policy.locksOnReturn()
        clock += 1_000
        assertThat(policy.sparesLockOnStop()).isFalse()
    }

    @Test
    @DisplayName("the screen turning off while a picker is open ends the pass")
    fun screen_off_during_picker() {
        policy.onExternalActivityLaunched()
        clock += 400
        policy.sparesLockOnStop()
        assertThat(policy.isSparingLock).isTrue()
        clock += 20_000
        assertThat(policy.onScreenOff()).isTrue()
        // Consumed: coming back afterwards does not decide a second time, and nothing is spared.
        assertThat(policy.isSparingLock).isFalse()
        assertThat(policy.locksOnReturn()).isFalse()
    }

    @Test
    @DisplayName("the screen turning off with no picker open does nothing")
    fun screen_off_without_picker() {
        assertThat(policy.isSparingLock).isFalse()
        assertThat(policy.onScreenOff()).isFalse()
    }

    @Test
    @DisplayName("an intent while the picker is open locks, whatever the callback order")
    fun external_intent_locks() {
        // onNewIntent before onStart.
        policy.onExternalActivityLaunched()
        clock += 400
        policy.sparesLockOnStop()
        clock += 30_000
        assertThat(policy.onExternalIntent()).isTrue()
        assertThat(policy.locksOnReturn()).isFalse()

        // onStart first (return in time), then onNewIntent before onResume.
        policy.onExternalActivityLaunched()
        clock += 400
        policy.sparesLockOnStop()
        clock += 30_000
        assertThat(policy.locksOnReturn()).isFalse()
        assertThat(policy.onExternalIntent()).isTrue()
    }

    @Test
    @DisplayName("the picker handing back its result does not lock")
    fun picker_result_does_not_lock() {
        // No intent: onStart, then onResume. A later intent, once resumed, is an ordinary one.
        policy.onExternalActivityLaunched()
        clock += 400
        policy.sparesLockOnStop()
        clock += 30_000
        assertThat(policy.locksOnReturn()).isFalse()
        policy.onResumed()
        assertThat(policy.onExternalIntent()).isFalse()
    }

    @Test
    @DisplayName("an intent with no picker involved does not lock")
    fun intent_without_picker() {
        assertThat(policy.onExternalIntent()).isFalse()
    }

    @Test
    @DisplayName("a launch that only paused the app expires on resume")
    fun launch_expires_on_resume() {
        // A translucent permission dialog pauses without stopping; the pass must not survive it.
        policy.onExternalActivityLaunched()
        clock += 800
        policy.onResumed()
        clock += 200
        assertThat(policy.sparesLockOnStop()).isFalse()
    }
}
