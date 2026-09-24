package com.filestech.notes_tech.ui.applock

import com.filestech.notes_tech.R
import com.filestech.notes_tech.security.applock.AppLockManager
import com.filestech.notes_tech.security.applock.BootCounter
import com.filestech.notes_tech.security.applock.FakeAppLockKeystore
import com.filestech.notes_tech.security.applock.FakeAppLockStore
import com.filestech.notes_tech.security.applock.FakeBiometricUnlockKey
import com.filestech.notes_tech.security.applock.FakePinVerifier
import com.filestech.notes_tech.security.applock.PinCheck
import com.filestech.notes_tech.security.applock.RelockDelay
import com.filestech.notes_tech.security.vault.MonotonicClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The settings' PIN sheet, step by step: which changes ask for the PIN, which do not, and that
 * nothing changes on a wrong one.
 *
 * Against a REAL [AppLockManager] on in-memory fakes: the rule "no lowering without a proof" lives in
 * the manager, and a test with a mocked manager would only check that the ViewModel calls it.
 *
 * ⚠️ `Main` is an unconfined dispatcher with its OWN scheduler: the countdown ticker loops on
 * `delay()` while a wait runs, and on the test's scheduler it would spin in virtual time for ever.
 */
class AppLockSettingsViewModelTest {

    private val store = FakeAppLockStore()
    private val verifier = FakePinVerifier()
    private val biometricKey = FakeBiometricUnlockKey()
    private var now = 1_000_000L

    private val appLock = AppLockManager(
        store = store,
        verifier = verifier,
        keystore = FakeAppLockKeystore(),
        biometricKey = biometricKey,
        clock = MonotonicClock { now },
        boots = BootCounter { 3 },
        io = Dispatchers.Unconfined,
    )

    private lateinit var viewModel: AppLockSettingsViewModel
    private lateinit var collector: Job

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = AppLockSettingsViewModel(appLock, biometricKey, Dispatchers.Unconfined)
        // `state` is shared WhileSubscribed: without a subscriber it would stay at its first value.
        collector = CoroutineScope(Dispatchers.Main).launch { viewModel.state.collect {} }
    }

    @AfterEach
    fun tearDown() {
        collector.cancel()
        Dispatchers.resetMain()
    }

    private val state get() = viewModel.state.value

    private fun configure(pin: String = PIN) {
        store.pin.value = verifier.create(pin).encode()
    }

    private fun type(vararg pins: String) = pins.forEach(viewModel::onPinEntered)

    @Test
    @DisplayName("setting the lock: choose, confirm — a mismatch starts over, and nothing is saved before")
    fun enable_flow() {
        viewModel.onToggleLock()
        assertThat(state.sheet).isEqualTo(PinSheetState(PinPurpose.ENABLE, PinStep.NEW))

        type("12")
        assertThat(state.sheet?.message).isEqualTo(PinSheetMessage.TOO_SHORT)

        type(PIN, "1357")
        assertThat(state.sheet?.step).isEqualTo(PinStep.NEW)
        assertThat(state.sheet?.message).isEqualTo(PinSheetMessage.MISMATCH)
        assertThat(store.configuredNow()).isFalse()

        type(PIN, PIN)
        assertThat(state.sheet).isNull()
        assertThat(state.notice).isEqualTo(R.string.app_lock_enabled)
        assertThat(store.configuredNow()).isTrue()
    }

    @Test
    @DisplayName("turning the lock off takes the right PIN; a wrong one changes nothing")
    fun disable_flow() {
        configure()
        viewModel.onToggleLock()
        assertThat(state.sheet).isEqualTo(PinSheetState(PinPurpose.DISABLE, PinStep.CURRENT))

        type(WRONG)
        assertThat(state.sheet?.message).isEqualTo(PinSheetMessage.WRONG_PIN)
        assertThat(store.configuredNow()).isTrue()

        type(PIN)
        assertThat(state.sheet).isNull()
        assertThat(state.notice).isEqualTo(R.string.app_lock_disabled)
        assertThat(store.configuredNow()).isFalse()
    }

    @Test
    @DisplayName("changing the PIN: current, then new, then confirmed — and only the new one opens")
    fun change_pin_flow() = runBlocking {
        configure()
        viewModel.onChangePin()
        type(PIN)
        assertThat(state.sheet?.step).isEqualTo(PinStep.NEW)

        type("1357", "1357")
        assertThat(state.notice).isEqualTo(R.string.app_lock_pin_changed)
        assertThat(appLock.attemptPin(PIN)).isInstanceOf(PinCheck.Rejected::class.java)
        assertThat(appLock.attemptPin("1357")).isInstanceOf(PinCheck.Accepted::class.java)
    }

    @Test
    @DisplayName("a shorter delay is set at once, a longer one only after the PIN")
    fun delay_flow() {
        configure()
        store.delay = RelockDelay.MINUTE_1

        viewModel.onDelayChosen(RelockDelay.SECONDS_15)
        assertThat(state.sheet).isNull()
        assertThat(store.delay).isEqualTo(RelockDelay.SECONDS_15)

        viewModel.onDelayChosen(RelockDelay.MINUTES_5)
        assertThat(state.sheet).isEqualTo(PinSheetState(PinPurpose.LENGTHEN_DELAY, PinStep.CURRENT))
        assertThat(store.delay).isEqualTo(RelockDelay.SECONDS_15)

        type(PIN)
        assertThat(state.sheet).isNull()
        assertThat(store.delay).isEqualTo(RelockDelay.MINUTES_5)
    }

    @Test
    @DisplayName("biometrics off needs nothing and deletes the key")
    fun biometric_off() {
        configure()
        store.biometric = true
        biometricKey.exists = true

        viewModel.onToggleBiometric()

        assertThat(state.sheet).isNull()
        assertThat(store.biometric).isFalse()
        assertThat(biometricKey.exists).isFalse()
    }

    /** The key is made AFTER the PIN, and the flag set only once a prompt's cipher really ran. */
    @Test
    @DisplayName("biometrics on: the PIN, then a key, then a prompt that works — in that order")
    fun biometric_on() = runBlocking {
        configure()
        viewModel.onToggleBiometric()
        assertThat(biometricKey.exists).isFalse()

        type(PIN)
        assertThat(state.enrollingBiometric).isTrue()
        assertThat(state.sheet).isNull()

        val cipher = viewModel.prepareEnrolment()
        assertThat(cipher).isNotNull()
        viewModel.completeEnrolment(BiometricOutcome.Succeeded(cipher))

        assertThat(store.biometric).isTrue()
        assertThat(state.enrollingBiometric).isFalse()
        assertThat(state.notice).isEqualTo(R.string.app_lock_biometric_enabled)
    }

    @Test
    @DisplayName("a dismissed enrolment prompt leaves neither a flag nor a key")
    fun biometric_cancelled() = runBlocking {
        configure()
        viewModel.onToggleBiometric()
        type(PIN)
        val cipher = viewModel.prepareEnrolment()

        viewModel.completeEnrolment(BiometricOutcome.Cancelled)

        assertThat(cipher).isNotNull()
        assertThat(store.biometric).isFalse()
        assertThat(biometricKey.exists).isFalse()
        assertThat(state.notice).isNull()
    }

    @Test
    @DisplayName("a device that refuses the key says so, and turns nothing on")
    fun biometric_key_refused() = runBlocking {
        configure()
        biometricKey.createFails = true
        viewModel.onToggleBiometric()
        type(PIN)

        assertThat(viewModel.prepareEnrolment()).isNull()
        assertThat(store.biometric).isFalse()
        assertThat(state.notice).isEqualTo(R.string.app_lock_biometric_setup_failed)
    }

    /** Otherwise the unlocked app would be an unthrottled way to find the PIN — and a vault's. */
    @Test
    @DisplayName("wrong PINs typed in the settings count on the lock screen's counter")
    fun same_counter_as_the_lock_screen() = runBlocking {
        configure()
        viewModel.onToggleLock()

        repeat(5) { type(WRONG) }

        assertThat(state.sheet?.waitMillis).isEqualTo(30_000L)
        assertThat(appLock.throttleRemainingMillis()).isEqualTo(30_000L)
    }

    @Test
    @DisplayName("closing the sheet forgets the proof: the next change asks for the PIN again")
    fun dismissing_forgets_the_proof() {
        configure()
        viewModel.onChangePin()
        type(PIN)
        assertThat(state.sheet?.step).isEqualTo(PinStep.NEW)

        viewModel.onSheetDismissed()
        viewModel.onChangePin()

        assertThat(state.sheet?.step).isEqualTo(PinStep.CURRENT)
    }

    private companion object {
        const val PIN = "2468"
        const val WRONG = "0000"
    }
}
