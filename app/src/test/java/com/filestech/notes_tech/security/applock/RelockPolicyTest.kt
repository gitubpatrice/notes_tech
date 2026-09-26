package com.filestech.notes_tech.security.applock

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The picker's pass and the user's delay, together — what `AppLockLifecycle` carries out. */
class RelockPolicyTest {

    private var clock = 5_000_000L
    private val policy = RelockPolicy { clock }

    /** An ordinary trip out of the app: no picker was launched. */
    private fun stop(configured: Boolean = true, delay: RelockDelay = RelockDelay.IMMEDIATELY): StopDecision =
        policy.decideStop(configured, policy.sparesStopForPicker(), delay)

    @Test
    @DisplayName("no lock configured: leaving the app decides nothing")
    fun no_lock() {
        assertThat(stop(configured = false)).isEqualTo(StopDecision.NONE)
        assertThat(policy.onStart()).isFalse()
    }

    @Test
    @DisplayName("immediate delay: leaving the app locks at once")
    fun immediate() {
        assertThat(stop()).isEqualTo(StopDecision.LOCK_NOW)
    }

    @Test
    @DisplayName("a delay locks on return only once it has passed")
    fun delay_on_return() {
        assertThat(stop(delay = RelockDelay.MINUTE_1)).isEqualTo(StopDecision.LOCK_LATER)
        clock += 59_999
        assertThat(policy.onStart()).isFalse()

        assertThat(stop(delay = RelockDelay.MINUTE_1)).isEqualTo(StopDecision.LOCK_LATER)
        clock += 60_000
        assertThat(policy.onStart()).isTrue()
    }

    @Test
    @DisplayName("a return nobody touches does not push the deadline back (audit E2)")
    fun untouched_return_keeps_the_departure() {
        stop(delay = RelockDelay.MINUTE_1)
        clock += 50_000
        // Brought back by another app, before the deadline, and sent away again.
        assertThat(policy.onStart()).isFalse()
        stop(delay = RelockDelay.MINUTE_1)
        clock += 20_000

        assertThat(policy.onStart()).isTrue()
    }

    @Test
    @DisplayName("the control: a return the user's hand ends starts the delay over")
    fun touched_return_ends_the_departure() {
        stop(delay = RelockDelay.MINUTE_1)
        clock += 50_000
        assertThat(policy.onStart()).isFalse()
        policy.onUserInteraction()
        stop(delay = RelockDelay.MINUTE_1)
        clock += 20_000

        assertThat(policy.onStart()).isFalse()
    }

    @Test
    @DisplayName("a return consumes the delay: the next start does not lock on an old departure")
    fun delay_is_consumed() {
        stop(delay = RelockDelay.SECONDS_15)
        clock += 20_000
        assertThat(policy.onStart()).isTrue()
        clock += 20_000
        assertThat(policy.onStart()).isFalse()
    }

    @Test
    @DisplayName("a picker the app opened spares the lock, whatever the delay")
    fun picker_is_spared() {
        policy.onExternalActivityLaunched()
        clock += 500
        assertThat(stop()).isEqualTo(StopDecision.SPARED_FOR_PICKER)
        clock += 30_000
        assertThat(policy.onStart()).isFalse()
    }

    @Test
    @DisplayName("a screen already off when the picker stops the app voids the pass")
    fun screen_already_off() {
        policy.onExternalActivityLaunched()
        clock += 500
        assertThat(policy.sparesStopForPicker()).isTrue()
        policy.cancelPickerPass()

        assertThat(policy.decideStop(true, sparedForPicker = false, RelockDelay.IMMEDIATELY))
            .isEqualTo(StopDecision.LOCK_NOW)
        assertThat(policy.isSparingForPicker).isFalse()
    }

    @Test
    @DisplayName("the screen going off during the picker locks at once with no delay")
    fun screen_off_immediate() {
        policy.onExternalActivityLaunched()
        clock += 500
        stop()
        assertThat(policy.onScreenOff(RelockDelay.IMMEDIATELY)).isTrue()
    }

    /** A phone put down on a picker is treated like a phone put down in the app. */
    @Test
    @DisplayName("the screen going off during the picker starts the user's delay")
    fun screen_off_with_delay() {
        policy.onExternalActivityLaunched()
        clock += 500
        stop(delay = RelockDelay.MINUTE_1)
        clock += 10_000

        assertThat(policy.onScreenOff(RelockDelay.MINUTE_1)).isFalse()
        clock += 60_000
        assertThat(policy.onStart()).isTrue()
    }

    @Test
    @DisplayName("the screen going off during the picker, back within the delay: no lock")
    fun screen_off_back_within_delay() {
        policy.onExternalActivityLaunched()
        clock += 500
        stop(delay = RelockDelay.MINUTE_1)

        policy.onScreenOff(RelockDelay.MINUTE_1)
        clock += 30_000
        assertThat(policy.onStart()).isFalse()
    }

    @Test
    @DisplayName("a launch made while no lock was configured leaves no pass for a later stop")
    fun no_pass_left_by_an_unlocked_launch() {
        policy.onExternalActivityLaunched()
        clock += 500
        assertThat(stop(configured = false)).isEqualTo(StopDecision.NONE)
        clock += 500
        assertThat(stop()).isEqualTo(StopDecision.LOCK_NOW)
    }

    @Test
    @DisplayName("an intent while a picker is open locks")
    fun intent_during_picker() {
        policy.onExternalActivityLaunched()
        clock += 500
        stop()
        assertThat(policy.onNewIntent()).isTrue()
    }
}
