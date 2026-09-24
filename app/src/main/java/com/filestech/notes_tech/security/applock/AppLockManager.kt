package com.filestech.notes_tech.security.applock

import com.filestech.notes_tech.security.vault.MonotonicClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the app's content may be shown. */
sealed interface AppLockState {

    /**
     * Each lock is a new [epoch]. The lock screen offers biometrics once per epoch — not once per
     * process, which would leave the prompt out after the first trip to the background, and not on
     * every recomposition, which would loop on a device whose prompt pauses the activity.
     */
    data class Locked(val epoch: Int) : AppLockState

    data object Unlocked : AppLockState
}

/**
 * Proof that the current PIN was typed, a moment ago. Every change that LOWERS protection takes one:
 * turning the lock off, changing the PIN, lengthening the delay, turning biometrics on.
 *
 * ## Why a proof object, and not "the settings screen asks for the PIN first"
 *
 * Because the rule then lives where it is enforced. With a screen-level rule, one new button that
 * forgets to ask is enough — the manager would happily lower the protection. Here the manager
 * refuses without a proof, and a proof exists only after [AppLockManager.attemptPin] accepted a PIN.
 *
 * Valid once, for [AppLockManager.PROOF_VALIDITY_MILLIS], and only within the lock epoch it was
 * issued in: a proof never survives the app locking.
 */
class PinProof internal constructor(internal val epoch: Int, internal val issuedAt: Long) {

    private val used = AtomicBoolean(false)

    internal val isUsed: Boolean get() = used.get()

    internal fun consume(): Boolean = used.compareAndSet(false, true)
}

/** What one PIN attempt did. */
sealed interface PinCheck {

    class Accepted(val proof: PinProof) : PinCheck

    /** Wrong PIN, counted. [waitMillis] is the wait it imposed on the next attempt (0: none). */
    data class Rejected(val waitMillis: Long) : PinCheck

    /** The wait was still running: no attempt was spent, nothing was checked. */
    data class Throttled(val remainingMillis: Long) : PinCheck

    /**
     * No PIN can be checked on this device any more: the verifier is unreadable, or its Keystore key
     * is gone. Nothing will change that — the lock screen offers only the erase path.
     */
    data object Unverifiable : PinCheck

    /** The Keystore did not answer. Nothing was counted: try again. */
    data object Unavailable : PinCheck

    /**
     * The attempt could not be written to the counter, so the PIN was NOT checked — nothing was
     * learned. Typically a full disk: the user frees some space and tries again.
     */
    data object NotRecorded : PinCheck
}

/** What one change of the lock's settings did. */
sealed interface LockChange {

    data object Saved : LockChange

    /** The disk refused the write. Nothing changed. */
    data object NotSaved : LockChange

    /** No valid [PinProof]: it is missing, used, stale, or older than the last lock. Ask again. */
    data object ProofRequired : LockChange

    /** The Keystore did not answer. Nothing changed. */
    data object Unavailable : LockChange
}

/**
 * The app lock, for the whole process: whether the content may be shown, the PIN attempts and their
 * throttle, and every change of the lock's settings.
 *
 * ## One mutex over every read-modify-write — the verification included
 *
 * Agenda Tech's audit S16: its throttle was checked, then the PIN verified, then the failure recorded,
 * each step under its own lock. Every tap landing in the ~100 ms of key derivation read a wait of
 * zero and got a free guess, so a burst of taps spent several guesses inside one window. Here
 * [attemptPin] holds the mutex from the throttle check to the recording of the outcome.
 *
 * ## The throttle, and the clock it runs on
 *
 * Five free attempts, then a wait that doubles up to an hour ([AppLockThrottle]). Never an erase.
 *
 * The deadline is on the monotonic clock: the user cannot move it, and it counts deep sleep — the
 * `delay()` of SMS Tech's lock counted neither. That clock restarts at every boot, so the deadline is
 * stored with the boot it belongs to ([BootCounter]): after a reboot the wait STARTS OVER. It is never
 * shortened — a wait already served before the reboot is served again, the price of not trusting a
 * clock the user can set. The counter itself survives everything but a correct PIN or a biometric
 * unlock, and is written with `commit`, so a force-stop right after a wrong PIN hands out nothing.
 *
 * ⚠️ **An attempt is COUNTED on disk before it is checked** (external review, GPT-5.6, 2026-09-24).
 * Counted after, a write refused by a full disk left the failure in memory only: a force-stop then
 * reloaded the old counter, and five fresh guesses came with every restart. Now a guess whose count
 * cannot be written is not checked at all ([PinCheck.NotRecorded]); a right PIN clears the count it
 * just wrote, and a Keystore that does not answer puts the previous count back.
 *
 * ## Main thread vs. mutex
 *
 * [resolveAtLaunch] and [lockIfConfigured] are called from `MainActivity`'s lifecycle and are
 * synchronous on purpose (a lock decided in a coroutine lands after the Recents snapshot — Agenda
 * F13). They only touch [state] and the epoch, both atomic. Everything that reads or writes the disk
 * is `suspend`, on [io], under the mutex.
 *
 * The one place the two meet is [epochGuard]: a change that lowers protection revalidates its proof
 * and writes under it, and a lock takes it too — so no lock can slip between "the proof is valid" and
 * "the change is written" (same review). It is held for one preference write, never for a key
 * derivation.
 */
@Singleton
class AppLockManager @Inject constructor(
    private val store: AppLockStore,
    private val verifier: PinVerifier,
    private val keystore: AppLockKeystore,
    private val biometricKey: BiometricUnlockKey,
    private val clock: MonotonicClock,
    private val boots: BootCounter,
    private val io: CoroutineDispatcher,
) {

    /**
     * Closed by default. [resolveAtLaunch] opens it when no lock is configured; nothing else opens it
     * without a PIN or a biometric.
     */
    private val _state = MutableStateFlow<AppLockState>(AppLockState.Locked(0))
    val state: StateFlow<AppLockState> = _state.asStateFlow()

    private val lockEpoch = AtomicInteger(0)
    private val resolved = AtomicBoolean(false)

    /** See the class KDoc: between a proof's last check and its write, no lock gets in. */
    private val epochGuard = Any()

    private val mutex = Mutex()

    // Guarded by [mutex].
    private var failures = 0
    private var waitUntil = 0L
    private var throttleRestored = false

    val configured: Flow<Boolean> get() = store.configured

    val biometricEnabled: Flow<Boolean> get() = store.biometricEnabled

    val relockDelay: Flow<RelockDelay> get() = store.relockDelay

    fun isConfigured(): Boolean = store.configuredNow()

    fun isBiometricEnabled(): Boolean = store.biometricEnabledNow()

    fun relockDelayNow(): RelockDelay = store.relockDelayNow()

    fun pinRecord(): PinRecord = store.pinRecord()

    /**
     * Once per process, before anything is shown: an app with no lock opens; an app with one — even
     * an unreadable one — stays locked. A recreated activity keeps the state it finds.
     */
    fun resolveAtLaunch() {
        if (!resolved.compareAndSet(false, true)) return
        if (!store.configuredNow()) _state.value = AppLockState.Unlocked
    }

    /**
     * Locks, if a lock is configured. A new epoch every time, even when already locked: see
     * [AppLockState.Locked]. Any [PinProof] issued before is void from here on.
     */
    fun lockIfConfigured() {
        if (!store.configuredNow()) return
        synchronized(epochGuard) { _state.value = AppLockState.Locked(lockEpoch.incrementAndGet()) }
    }

    /**
     * Spends ONE attempt: checks the wait, verifies, records — atomically (see the class KDoc).
     *
     * On success the app opens, unless it locked again while the PIN was being checked: someone who
     * typed the PIN and pressed Home at once must find the app locked when they come back.
     */
    suspend fun attemptPin(pin: String): PinCheck {
        // The lock the user was looking at, read BEFORE anything can block: read after the count's
        // `commit`, a Home press during that write gave the new epoch, and the right PIN reopened the
        // app in the background (external review, Gemini 3.1 Pro, 2026-09-24).
        val startEpoch = lockEpoch.get()
        return attemptPinIn(startEpoch, pin)
    }

    private suspend fun attemptPinIn(startEpoch: Int, pin: String): PinCheck = withContext(io) {
        mutex.withLock {
            restoreThrottleOnce()
            val remaining = remainingLocked()
            if (remaining > 0) return@withLock PinCheck.Throttled(remaining)

            // Absent counts as unverifiable too: nothing on disk can confirm this PIN, and "no lock"
            // is never a reason to accept one.
            val stored = (store.pinRecord() as? PinRecord.Present)?.pin
                ?: return@withLock PinCheck.Unverifiable

            // Counted BEFORE it is checked — see the class KDoc. No count on disk, no check.
            val previous = persistedNow()
            val counted = countedFailure()
            if (!store.saveThrottle(counted)) return@withLock PinCheck.NotRecorded

            val matches = try {
                verifier.matches(pin, stored)
            } catch (e: AppLockKeyMissingException) {
                Timber.w(e, "app lock key missing: no PIN can be checked")
                restoreCount(previous)
                return@withLock PinCheck.Unverifiable
            } catch (e: AppLockKeystoreUnavailableException) {
                Timber.w(e, "keystore unavailable: attempt not counted")
                restoreCount(previous)
                return@withLock PinCheck.Unavailable
            }
            if (!matches) {
                failures = counted.failures
                waitUntil = counted.waitUntilElapsed
                return@withLock PinCheck.Rejected(remainingLocked())
            }
            resetThrottleLocked()
            openIfStillIn(startEpoch)
            PinCheck.Accepted(PinProof(startEpoch, clock.elapsedMillis()))
        }
    }

    /**
     * A biometric prompt succeeded AND its cipher ran ([BiometricUnlockKey.verify]). Opens the app if
     * it is still in the lock [epoch] the prompt was shown for, and clears the PIN counter — the
     * owner has just authenticated.
     */
    suspend fun unlockWithBiometric(epoch: Int) = withContext(io) {
        mutex.withLock {
            if (_state.compareAndSet(AppLockState.Locked(epoch), AppLockState.Unlocked)) resetThrottleLocked()
        }
    }

    /** Remaining wait before the next attempt, 0 if none. Read-only: it drives the countdown. */
    suspend fun throttleRemainingMillis(): Long = withContext(io) {
        mutex.withLock {
            restoreThrottleOnce()
            remainingLocked()
        }
    }

    /** Sets the lock. Refused when one is already set: replacing a PIN takes a proof, see [changePin]. */
    suspend fun enable(newPin: String): LockChange = withContext(io) {
        mutex.withLock {
            if (store.configuredNow()) return@withLock LockChange.ProofRequired
            val stored = try {
                verifier.create(newPin)
            } catch (e: AppLockKeystoreUnavailableException) {
                Timber.w(e, "app lock not enabled: keystore unavailable")
                return@withLock LockChange.Unavailable
            }
            if (!store.enable(stored)) return@withLock LockChange.NotSaved
            clearThrottleInMemory()
            LockChange.Saved
        }
    }

    suspend fun changePin(proof: PinProof, newPin: String): LockChange = withContext(io) {
        mutex.withLock {
            if (!isValid(proof)) return@withLock LockChange.ProofRequired
            val stored = try {
                verifier.create(newPin)
            } catch (e: AppLockKeystoreUnavailableException) {
                Timber.w(e, "PIN not changed: keystore unavailable")
                return@withLock LockChange.Unavailable
            }
            // The key derivation above takes long enough for the app to lock meanwhile: the proof is
            // checked again, with no lock able to slip in before the write.
            commitWithProof(proof) { store.replacePin(stored) }
        }
    }

    /**
     * Removes the lock. The preferences go FIRST, the keys after: a key left behind by a failed
     * deletion is harmless — the next [enable] reuses or replaces it — whereas a verifier left without
     * its key would lock the user out of their notes for good.
     */
    suspend fun disable(proof: PinProof): LockChange = withContext(io) {
        mutex.withLock {
            val change = commitWithProof(proof) { store.disable() }
            if (change != LockChange.Saved) return@withLock change
            clearThrottleInMemory()
            forgetKeysQuietly(pinKey = true)
            _state.value = AppLockState.Unlocked
            LockChange.Saved
        }
    }

    /** Shortening needs nothing — it raises protection. Lengthening needs [proof]. */
    suspend fun setRelockDelay(delay: RelockDelay, proof: PinProof?): LockChange = withContext(io) {
        mutex.withLock {
            val lengthens = delay.seconds > store.relockDelayNow().seconds
            when {
                !lengthens -> if (store.setRelockDelay(delay)) LockChange.Saved else LockChange.NotSaved
                proof == null -> LockChange.ProofRequired
                else -> commitWithProof(proof) { store.setRelockDelay(delay) }
            }
        }
    }

    /**
     * Turning biometrics ON takes [proof], and a key that the caller has just made
     * ([BiometricUnlockKey.create]) and seen pass a real prompt ([BiometricUnlockKey.verify]).
     * Turning them OFF takes nothing, and deletes the key.
     */
    suspend fun setBiometricEnabled(enabled: Boolean, proof: PinProof?): LockChange = withContext(io) {
        mutex.withLock {
            when {
                !enabled -> if (store.setBiometricEnabled(false)) {
                    forgetKeysQuietly(pinKey = false)
                    LockChange.Saved
                } else {
                    LockChange.NotSaved
                }
                proof == null -> LockChange.ProofRequired
                else -> commitWithProof(proof) { store.setBiometricEnabled(true) }
            }
        }
    }

    /**
     * The biometric key is gone or dead ([BiometricPreparation.Invalidated]): biometrics off, key
     * deleted, and the caller says so. Never re-armed here — see [BiometricUnlockKey].
     */
    suspend fun onBiometricKeyLost() = withContext(io) {
        mutex.withLock {
            if (!store.setBiometricEnabled(false)) Timber.w("biometric flag could not be cleared")
            forgetKeysQuietly(pinKey = false)
        }
    }

    /**
     * Checks [proof] once more and applies [write] — both under [epochGuard], so that no lock can
     * land between the two. The proof is used up only if the write is on disk.
     */
    private inline fun commitWithProof(proof: PinProof, write: () -> Boolean): LockChange = synchronized(epochGuard) {
        when {
            !isValid(proof) -> LockChange.ProofRequired
            !write() -> LockChange.NotSaved
            else -> {
                proof.consume()
                LockChange.Saved
            }
        }
    }

    private fun isValid(proof: PinProof): Boolean {
        val age = clock.elapsedMillis() - proof.issuedAt
        return !proof.isUsed && proof.epoch == lockEpoch.get() && age in 0..PROOF_VALIDITY_MILLIS
    }

    private fun openIfStillIn(epoch: Int) {
        val current = _state.value
        if (current is AppLockState.Locked && current.epoch == epoch) {
            _state.compareAndSet(current, AppLockState.Unlocked)
        }
    }

    /** What the disk will hold if the attempt about to be checked turns out wrong. Caller holds [mutex]. */
    private fun countedFailure(): PersistedThrottle {
        val next = (failures + 1).coerceAtMost(MAX_COUNTED_FAILURES)
        val delay = AppLockThrottle.delayAfter(next)
        return PersistedThrottle(next, if (delay > 0) clock.elapsedMillis() + delay else 0L, boots.currentBoot())
    }

    /** The in-memory count as the disk should hold it. Caller holds [mutex]. */
    private fun persistedNow(): PersistedThrottle =
        if (failures == 0) PersistedThrottle.NONE else PersistedThrottle(failures, waitUntil, boots.currentBoot())

    /**
     * Puts back the count an attempt wrote but did not spend — the Keystore did not answer. If even
     * that write fails, the attempt stays counted: the safe side.
     */
    private fun restoreCount(previous: PersistedThrottle) {
        if (!store.saveThrottle(previous)) Timber.w("uncounted attempt left counted on disk")
    }

    /** Never negative, and never an overflow: [waitUntil] is kept within `[0, now + one wait]`. */
    private fun remainingLocked(): Long = (waitUntil - clock.elapsedMillis()).coerceAtLeast(0L)

    private fun resetThrottleLocked() {
        clearThrottleInMemory()
        if (!store.saveThrottle(PersistedThrottle.NONE)) Timber.w("PIN counter could not be cleared on disk")
    }

    private fun clearThrottleInMemory() {
        failures = 0
        waitUntil = 0L
        throttleRestored = true
    }

    /**
     * Pulls the persisted counter into memory on first use — lazily, so that building this singleton
     * (which Hilt may do on the main thread) never reads the disk.
     */
    private fun restoreThrottleOnce() {
        if (throttleRestored) return
        throttleRestored = true
        val persisted = store.throttle()
        failures = persisted.failures.coerceIn(0, MAX_COUNTED_FAILURES)
        val delay = AppLockThrottle.delayAfter(failures)
        if (delay == 0L) {
            waitUntil = 0L
            return
        }
        val now = clock.elapsedMillis()
        val boot = boots.currentBoot()
        val sameBoot = boot != null && persisted.boot == boot
        waitUntil = if (sameBoot && persisted.waitUntilElapsed >= 0L) {
            // Same boot: the monotonic deadline still means something. Never trusted beyond one full
            // wait, so that a damaged value cannot lock the owner out for longer than designed.
            persisted.waitUntilElapsed.coerceAtMost(now + delay)
        } else {
            // Another boot, one the device does not name — or a NEGATIVE deadline, which no write of
            // this class can produce: damaged. Taken as it was, `waitUntil - now` overflowed into a
            // wait of ages (external review, GPT-5.6, 2026-09-24). The wait starts over, never shorter.
            val restarted = now + delay
            if (!store.saveThrottle(PersistedThrottle(failures, restarted, boot))) {
                Timber.w("restarted PIN wait could not be persisted")
            }
            restarted
        }
    }

    /**
     * Best effort, after the preferences said "no lock" or "no biometrics": a surviving key is logged,
     * not reported. Panic mode, which must report, deletes them itself.
     */
    private fun forgetKeysQuietly(pinKey: Boolean) {
        try {
            biometricKey.delete()
        } catch (e: AppLockKeystoreUnavailableException) {
            Timber.w(e, "biometric unlock key not deleted")
        }
        if (!pinKey) return
        try {
            keystore.deleteKey()
        } catch (e: AppLockKeystoreUnavailableException) {
            Timber.w(e, "app lock key not deleted")
        }
    }

    companion object {
        /** Long enough for a biometric prompt after the PIN, short enough not to outlive the moment. */
        const val PROOF_VALIDITY_MILLIS = 2 * 60_000L

        /** The wait is at its cap long before this; counting further only risks an overflow. */
        const val MAX_COUNTED_FAILURES = 1_000
    }
}
