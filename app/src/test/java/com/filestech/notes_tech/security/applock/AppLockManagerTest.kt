package com.filestech.notes_tech.security.applock

import com.filestech.notes_tech.security.vault.MonotonicClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Everything the app lock decides, against the in-memory fakes of `AppLockFakes.kt`. */
class AppLockManagerTest {

    private val log = mutableListOf<String>()
    private val store = FakeAppLockStore(log)
    private val verifier = FakePinVerifier()
    private val keystore = FakeAppLockKeystore(log)
    private var now = 10_000_000L
    private var boot: Int? = 7

    private fun manager(io: CoroutineDispatcher = Dispatchers.Unconfined) = AppLockManager(
        store = store,
        verifier = verifier,
        keystore = keystore,
        biometricKey = FakeBiometricUnlockKey(log),
        clock = MonotonicClock { now },
        boots = BootCounter { boot },
        io = io,
    )

    private fun configure(pin: String = PIN) {
        store.pin.value = verifier.create(pin).encode()
    }

    private suspend fun AppLockManager.fail(times: Int) = repeat(times) { attemptPin(WRONG) }

    // ── Launch ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a process with no lock opens; one with a lock stays locked")
    fun resolve_at_launch() {
        assertThat(manager().apply { resolveAtLaunch() }.state.value).isEqualTo(AppLockState.Unlocked)

        configure()
        assertThat(manager().apply { resolveAtLaunch() }.state.value).isInstanceOf(AppLockState.Locked::class.java)
    }

    @Test
    @DisplayName("a lock whose verifier cannot be read stays locked, and no PIN opens it")
    fun unreadable_lock_stays_locked() = runTest {
        store.pin.value = "damaged"
        val lock = manager().apply { resolveAtLaunch() }

        assertThat(lock.state.value).isInstanceOf(AppLockState.Locked::class.java)
        assertThat(lock.attemptPin(PIN)).isEqualTo(PinCheck.Unverifiable)
        assertThat(lock.state.value).isInstanceOf(AppLockState.Locked::class.java)
    }

    @Test
    @DisplayName("the launch decision is taken once: a recreated activity keeps the state it finds")
    fun resolve_is_once_per_process() = runTest {
        configure()
        val lock = manager().apply { resolveAtLaunch() }
        assertThat(lock.attemptPin(PIN)).isInstanceOf(PinCheck.Accepted::class.java)

        lock.resolveAtLaunch()

        assertThat(lock.state.value).isEqualTo(AppLockState.Unlocked)
    }

    // ── Attempts and waits ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the right PIN opens; a wrong one is refused and counted")
    fun right_and_wrong_pins() = runTest {
        configure()
        val lock = manager().apply { resolveAtLaunch() }

        assertThat(lock.attemptPin(WRONG)).isEqualTo(PinCheck.Rejected(0L))
        assertThat(store.persisted.failures).isEqualTo(1)
        assertThat(lock.state.value).isInstanceOf(AppLockState.Locked::class.java)

        assertThat(lock.attemptPin(PIN)).isInstanceOf(PinCheck.Accepted::class.java)
        assertThat(lock.state.value).isEqualTo(AppLockState.Unlocked)
        assertThat(store.persisted).isEqualTo(PersistedThrottle.NONE)
    }

    @Test
    @DisplayName("five free attempts, then a wait during which nothing is checked")
    fun wait_after_five_failures() = runTest {
        configure()
        val lock = manager().apply { resolveAtLaunch() }
        lock.fail(4)
        assertThat(lock.attemptPin(WRONG)).isEqualTo(PinCheck.Rejected(30_000L))

        val checksBefore = verifier.checks.get()
        now += 10_000L
        assertThat(lock.attemptPin(PIN)).isEqualTo(PinCheck.Throttled(20_000L))
        // Not even the RIGHT PIN is checked during the wait, and nothing was counted.
        assertThat(verifier.checks.get()).isEqualTo(checksBefore)
        assertThat(store.persisted.failures).isEqualTo(5)

        now += 20_000L
        assertThat(lock.attemptPin(WRONG)).isEqualTo(PinCheck.Rejected(60_000L))
    }

    @Test
    @DisplayName("the counter and its wait survive a process restart within the same boot")
    fun survives_a_restart() = runTest {
        configure()
        manager().fail(5)
        now += 12_000L

        val restarted = manager()

        assertThat(restarted.throttleRemainingMillis()).isEqualTo(18_000L)
        now += 18_000L
        // The sixth failure overall, not a fresh first one.
        assertThat(restarted.attemptPin(WRONG)).isEqualTo(PinCheck.Rejected(60_000L))
    }

    /**
     * `elapsedRealtime` restarts at zero on boot. Reusing the old deadline would read as a wait of
     * days (old uptime) or of nothing (a longer new uptime); the rule is one full wait from now.
     *
     * ⚠️ The new uptime is deliberately LONGER than the old deadline: that is the case where trusting
     * it shortens the wait to nothing. With a shorter new uptime the one-wait clamp alone would give
     * the same answer, and this test could not tell a missing boot check from a present one.
     */
    @Test
    @DisplayName("after a reboot the wait starts over — even when the new uptime passed the old deadline")
    fun reboot_restarts_the_wait() = runTest {
        configure()
        store.persisted = PersistedThrottle(failures = 6, waitUntilElapsed = 70_000L, boot = 7)
        boot = 8
        now = 3_600_000L

        val afterReboot = manager()

        assertThat(afterReboot.throttleRemainingMillis()).isEqualTo(60_000L)
        // Persisted with the new boot, so that one more restart in this boot does not restart it again.
        assertThat(store.persisted.boot).isEqualTo(8)
        now += 45_000L
        assertThat(manager().throttleRemainingMillis()).isEqualTo(15_000L)
    }

    @Test
    @DisplayName("a device that does not name its boots never has its deadline trusted")
    fun unknown_boot_restarts_the_wait() = runTest {
        boot = null
        configure()
        manager().fail(5)
        now += 29_000L

        assertThat(manager().throttleRemainingMillis()).isEqualTo(30_000L)
    }

    @Test
    @DisplayName("a stored deadline is never trusted beyond one full wait")
    fun stored_deadline_is_clamped() = runTest {
        configure()
        store.persisted = PersistedThrottle(failures = 5, waitUntilElapsed = now + 10 * 3_600_000L, boot = boot)

        assertThat(manager().throttleRemainingMillis()).isEqualTo(30_000L)
    }

    /**
     * Agenda Tech's S16, rebuilt so that it can tell the two designs apart. The discriminating state
     * is one failure short of the wait: had the check run outside the mutex, every attempt landing
     * during the ~20 ms of the first check would read "no wait" and get its own guess.
     */
    @Test
    @DisplayName("a burst of attempts cannot outrun the wait the first of them triggers")
    fun burst_cannot_outrun_the_wait() = runBlocking {
        configure()
        val lock = manager(io = Dispatchers.Default).apply { resolveAtLaunch() }
        lock.fail(4)
        verifier.duringCheck = { Thread.sleep(CHECK_MILLIS) }
        val checksBefore = verifier.checks.get()

        val outcomes = List(BURST) { async(Dispatchers.Default) { lock.attemptPin(WRONG) } }.awaitAll()

        assertThat(verifier.checks.get() - checksBefore).isEqualTo(1)
        assertThat(outcomes.count { it is PinCheck.Rejected }).isEqualTo(1)
        assertThat(outcomes.count { it is PinCheck.Throttled }).isEqualTo(BURST - 1)
        assertThat(store.persisted.failures).isEqualTo(5)
    }

    @Test
    @DisplayName("a keystore that does not answer counts nothing")
    fun keystore_unavailable_is_not_a_failure() = runTest {
        configure()
        val lock = manager()
        verifier.failure = AppLockKeystoreUnavailableException()

        assertThat(lock.attemptPin(WRONG)).isEqualTo(PinCheck.Unavailable)
        assertThat(store.persisted).isEqualTo(PersistedThrottle.NONE)
    }

    @Test
    @DisplayName("a missing key makes the PIN unverifiable, and counts nothing")
    fun missing_key_is_unverifiable() = runTest {
        configure()
        val lock = manager()
        verifier.failure = AppLockKeyMissingException()

        assertThat(lock.attemptPin(PIN)).isEqualTo(PinCheck.Unverifiable)
        assertThat(store.persisted).isEqualTo(PersistedThrottle.NONE)
    }

    // ── External review, GPT-5.6, 2026-09-24 ──────────────────────────────────────────────────────

    /**
     * Counted after the check, a failure the disk refused lived in memory only: a force-stop then
     * reloaded the old count, and five fresh guesses came with every restart. No count, no check —
     * not even of the right PIN.
     */
    @Test
    @DisplayName("an attempt whose count cannot be written is not checked at all")
    fun unrecorded_attempt_is_not_checked() = runTest {
        configure()
        val lock = manager().apply { resolveAtLaunch() }
        store.refuseWrites = true
        val checksBefore = verifier.checks.get()

        assertThat(lock.attemptPin(PIN)).isEqualTo(PinCheck.NotRecorded)

        assertThat(verifier.checks.get()).isEqualTo(checksBefore)
        assertThat(lock.state.value).isInstanceOf(AppLockState.Locked::class.java)
    }

    /** The witness of the order: while the PIN is being checked, its failure is already on disk. */
    @Test
    @DisplayName("an attempt is on disk before it is checked, and a wrong one keeps that count")
    fun counted_before_checked() = runTest {
        configure()
        val lock = manager()
        var onDiskDuringCheck = -1
        verifier.duringCheck = { onDiskDuringCheck = store.persisted.failures }

        lock.attemptPin(WRONG)

        assertThat(onDiskDuringCheck).isEqualTo(1)
        assertThat(store.persisted.failures).isEqualTo(1)
    }

    /** Found by the second review (Gemini): the epoch was read after the count's blocking write. */
    @Test
    @DisplayName("a lock during the count's write keeps the app locked, right PIN or not")
    fun lock_during_the_count_write_wins() = runTest {
        configure()
        val lock = manager().apply { resolveAtLaunch() }
        store.duringSave = { lock.lockIfConfigured() }

        assertThat(lock.attemptPin(PIN)).isInstanceOf(PinCheck.Accepted::class.java)

        assertThat(lock.state.value).isInstanceOf(AppLockState.Locked::class.java)
    }

    /** `waitUntil - now` on `Long.MIN_VALUE` overflowed into a wait of ages. */
    @Test
    @DisplayName("a damaged negative deadline restarts the wait instead of locking out for ever")
    fun damaged_negative_deadline() = runTest {
        configure()
        store.persisted = PersistedThrottle(failures = 5, waitUntilElapsed = Long.MIN_VALUE, boot = boot)

        assertThat(manager().throttleRemainingMillis()).isEqualTo(30_000L)
    }

    /**
     * The new PIN's key derivation takes long enough for the app to lock meanwhile. The proof is
     * checked again before the write, with no lock able to slip in between.
     */
    @Test
    @DisplayName("a lock during the new PIN's derivation voids the change")
    fun lock_during_derivation_voids_the_change() = runTest {
        configure()
        val lock = manager().apply { resolveAtLaunch() }
        val proof = (lock.attemptPin(PIN) as PinCheck.Accepted).proof
        verifier.duringCreate = { lock.lockIfConfigured() }

        assertThat(lock.changePin(proof, "1357")).isEqualTo(LockChange.ProofRequired)

        verifier.duringCreate = {}
        assertThat(lock.attemptPin(PIN)).isInstanceOf(PinCheck.Accepted::class.java)
    }

    // ── Epochs: a lock during a check wins ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a lock that happens while the PIN is checked keeps the app locked, and voids the proof")
    fun lock_during_check_wins() = runTest {
        configure()
        val lock = manager().apply { resolveAtLaunch() }
        verifier.duringCheck = { lock.lockIfConfigured() } // Home pressed while the PIN is checked

        val check = lock.attemptPin(PIN)

        assertThat(check).isInstanceOf(PinCheck.Accepted::class.java)
        assertThat(lock.state.value).isEqualTo(AppLockState.Locked(1))
        assertThat(lock.disable((check as PinCheck.Accepted).proof)).isEqualTo(LockChange.ProofRequired)
    }

    @Test
    @DisplayName("a biometric unlock opens only the lock it was shown for, and clears the PIN counter")
    fun biometric_unlock_is_bound_to_its_epoch() = runTest {
        configure()
        val lock = manager().apply { resolveAtLaunch() }
        lock.fail(5)
        lock.lockIfConfigured()

        lock.unlockWithBiometric(epoch = 0)
        assertThat(lock.state.value).isEqualTo(AppLockState.Locked(1))

        lock.unlockWithBiometric(epoch = 1)
        assertThat(lock.state.value).isEqualTo(AppLockState.Unlocked)
        assertThat(lock.throttleRemainingMillis()).isEqualTo(0L)
        assertThat(store.persisted).isEqualTo(PersistedThrottle.NONE)
    }

    @Test
    @DisplayName("locking without a configured lock does nothing")
    fun lock_without_configuration() {
        val lock = manager().apply { resolveAtLaunch() }
        lock.lockIfConfigured()
        assertThat(lock.state.value).isEqualTo(AppLockState.Unlocked)
    }

    // ── Changes: nothing lowers protection without a proof ───────────────────────────────────────

    @Test
    @DisplayName("turning the lock off takes a proof, used once, and fails after the proof expires")
    fun disable_needs_a_fresh_single_use_proof() = runTest {
        configure()
        val lock = manager().apply { resolveAtLaunch() }
        val proof = (lock.attemptPin(PIN) as PinCheck.Accepted).proof

        now += AppLockManager.PROOF_VALIDITY_MILLIS + 1
        assertThat(lock.disable(proof)).isEqualTo(LockChange.ProofRequired)
        assertThat(store.configuredNow()).isTrue()

        val fresh = (lock.attemptPin(PIN) as PinCheck.Accepted).proof
        assertThat(lock.disable(fresh)).isEqualTo(LockChange.Saved)
        assertThat(store.configuredNow()).isFalse()

        configure()
        assertThat(lock.disable(fresh)).isEqualTo(LockChange.ProofRequired)
    }

    @Test
    @DisplayName("a proof does not survive the app locking")
    fun proof_dies_with_a_lock() = runTest {
        configure()
        val lock = manager().apply { resolveAtLaunch() }
        val proof = (lock.attemptPin(PIN) as PinCheck.Accepted).proof

        lock.lockIfConfigured()

        assertThat(lock.changePin(proof, "1357")).isEqualTo(LockChange.ProofRequired)
    }

    /** A second `enable` would be a PIN change with no proof. */
    @Test
    @DisplayName("setting a lock over an existing one is refused")
    fun enable_over_an_existing_lock() = runTest {
        configure()
        assertThat(manager().enable("1357")).isEqualTo(LockChange.ProofRequired)
        assertThat(manager().attemptPin(PIN)).isInstanceOf(PinCheck.Accepted::class.java)
    }

    @Test
    @DisplayName("after a PIN change the old PIN is refused and the new one opens")
    fun change_pin() = runTest {
        configure()
        val lock = manager().apply { resolveAtLaunch() }
        val proof = (lock.attemptPin(PIN) as PinCheck.Accepted).proof

        assertThat(lock.changePin(proof, "1357")).isEqualTo(LockChange.Saved)

        lock.lockIfConfigured()
        assertThat(lock.attemptPin(PIN)).isEqualTo(PinCheck.Rejected(0L))
        assertThat(lock.attemptPin("1357")).isInstanceOf(PinCheck.Accepted::class.java)
    }

    @Test
    @DisplayName("a shorter delay needs nothing, a longer one needs a proof")
    fun delay_rules() = runTest {
        configure()
        store.delay = RelockDelay.MINUTE_1
        val lock = manager().apply { resolveAtLaunch() }

        assertThat(lock.setRelockDelay(RelockDelay.SECONDS_15, proof = null)).isEqualTo(LockChange.Saved)
        assertThat(lock.setRelockDelay(RelockDelay.MINUTES_5, proof = null)).isEqualTo(LockChange.ProofRequired)
        assertThat(store.delay).isEqualTo(RelockDelay.SECONDS_15)

        val proof = (lock.attemptPin(PIN) as PinCheck.Accepted).proof
        assertThat(lock.setRelockDelay(RelockDelay.MINUTES_5, proof)).isEqualTo(LockChange.Saved)
        assertThat(store.delay).isEqualTo(RelockDelay.MINUTES_5)
    }

    @Test
    @DisplayName("biometrics: on takes a proof, off takes nothing and deletes the key")
    fun biometric_rules() = runTest {
        configure()
        val lock = manager().apply { resolveAtLaunch() }

        assertThat(lock.setBiometricEnabled(true, proof = null)).isEqualTo(LockChange.ProofRequired)
        val proof = (lock.attemptPin(PIN) as PinCheck.Accepted).proof
        assertThat(lock.setBiometricEnabled(true, proof)).isEqualTo(LockChange.Saved)
        assertThat(store.biometric).isTrue()

        log.clear()
        assertThat(lock.setBiometricEnabled(false, proof = null)).isEqualTo(LockChange.Saved)
        assertThat(store.biometric).isFalse()
        assertThat(log).containsExactly("store.biometric=false", "biometric.delete").inOrder()
    }

    @Test
    @DisplayName("a lost biometric key turns biometrics off")
    fun lost_biometric_key() = runTest {
        configure()
        store.biometric = true

        manager().onBiometricKeyLost()

        assertThat(store.biometric).isFalse()
        assertThat(log).contains("biometric.delete")
    }

    /**
     * The order is the design: a key left behind is harmless, a verifier left without its key would
     * lock the owner out of their notes for good.
     */
    @Test
    @DisplayName("turning the lock off removes the preferences BEFORE the keys, and survives a stuck key")
    fun disable_order_and_stuck_key() = runTest {
        configure()
        keystore.deleteFails = true
        val lock = manager().apply { resolveAtLaunch() }
        val proof = (lock.attemptPin(PIN) as PinCheck.Accepted).proof
        log.clear()

        assertThat(lock.disable(proof)).isEqualTo(LockChange.Saved)

        assertThat(log).containsExactly("store.disable", "biometric.delete", "keystore.delete").inOrder()
        assertThat(store.configuredNow()).isFalse()
        assertThat(lock.state.value).isEqualTo(AppLockState.Unlocked)
    }

    @Test
    @DisplayName("a disk that refuses the write changes nothing, and says so")
    fun refused_write() = runTest {
        store.refuseWrites = true

        assertThat(manager().enable(PIN)).isEqualTo(LockChange.NotSaved)
        assertThat(store.configuredNow()).isFalse()
    }

    @Test
    @DisplayName("a new lock starts with a clean counter, whatever the process still remembered")
    fun new_lock_clean_counter() = runTest {
        configure()
        val lock = manager().apply { resolveAtLaunch() }
        lock.fail(3)
        // The preferences cleared behind the manager's back (panic mode clears them by white list).
        store.pin.value = null
        store.persisted = PersistedThrottle.NONE

        assertThat(lock.enable("1357")).isEqualTo(LockChange.Saved)
        lock.fail(4)

        // Had the three old failures survived in memory, this would be the eighth attempt, throttled.
        assertThat(lock.attemptPin(WRONG)).isEqualTo(PinCheck.Rejected(30_000L))
    }

    private companion object {
        const val PIN = "2468"
        const val WRONG = "0000"
        const val BURST = 10
        const val CHECK_MILLIS = 20L
    }
}
