package com.filestech.notes_tech.security.applock

import com.filestech.notes_tech.data.prefs.LegacyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** What the preference file holds for the app lock PIN. */
sealed interface PinRecord {

    /** No app lock. */
    data object Absent : PinRecord

    /**
     * A lock is set, but its verifier cannot be read. The app stays LOCKED and offers only the erase
     * path — see [StoredPin.decode] for why "unreadable" must never fall back to "absent".
     */
    data object Unreadable : PinRecord

    class Present(val pin: StoredPin) : PinRecord
}

/**
 * How long the app may stay in the background before it asks for the PIN again.
 *
 * The same four steps as SMS Tech, without its "next launch only": a lock that never comes back while
 * the process lives is not a lock the user can reason about.
 */
enum class RelockDelay(val seconds: Int) {
    IMMEDIATELY(0),
    SECONDS_15(15),
    MINUTE_1(60),
    MINUTES_5(300),
    ;

    val millis: Long get() = seconds * MILLIS_PER_SECOND

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L

        /**
         * An unknown stored value falls back to the STRICTEST delay. Falling back to any other would
         * let a damaged preference lengthen the time the app stays open behind the user's back.
         */
        fun fromSeconds(seconds: Long): RelockDelay =
            entries.firstOrNull { it.seconds.toLong() == seconds } ?: IMMEDIATELY
    }
}

/**
 * The failed-PIN counter as it survives the process.
 *
 * [waitUntilElapsed] is on the monotonic clock (`elapsedRealtime`), which cannot be moved by the user
 * but starts again from zero at every boot — hence [boot], which says which boot the deadline belongs
 * to. `null` when the device does not say: the deadline is then never trusted, see [AppLockManager].
 */
data class PersistedThrottle(val failures: Int, val waitUntilElapsed: Long, val boot: Int?) {
    companion object {
        val NONE = PersistedThrottle(failures = 0, waitUntilElapsed = 0L, boot = null)
    }
}

/**
 * The app lock's persistent state.
 *
 * An interface so that [AppLockManager] — where every decision is taken — is tested on the JVM against
 * an in-memory fake. [PreferencesAppLockStore] is tested on a device, against the real file.
 *
 * Every write is synchronous and reports whether the disk accepted it: see
 * [LegacyPreferences.commit] for why `apply()` is not good enough for security state.
 */
interface AppLockStore {

    /** A lock is set — readable or not. This is the question every "should the app lock?" asks. */
    fun configuredNow(): Boolean

    val configured: Flow<Boolean>

    fun pinRecord(): PinRecord

    fun biometricEnabledNow(): Boolean

    val biometricEnabled: Flow<Boolean>

    fun relockDelayNow(): RelockDelay

    val relockDelay: Flow<RelockDelay>

    /** Sets the lock with [pin]: counter cleared, biometrics off, delay back to immediate. */
    fun enable(pin: StoredPin): Boolean

    /** Replaces the PIN; biometrics and delay are left as they are. */
    fun replacePin(pin: StoredPin): Boolean

    /** Removes every `app_lock_*` preference, in one write. */
    fun disable(): Boolean

    fun setBiometricEnabled(enabled: Boolean): Boolean

    fun setRelockDelay(delay: RelockDelay): Boolean

    fun throttle(): PersistedThrottle

    fun saveThrottle(throttle: PersistedThrottle): Boolean
}

/**
 * [AppLockStore] in the Flutter preference file, next to the other settings.
 *
 * ## Why here and not in a file of its own
 *
 * Panic mode clears this file by WHITE list (`PanicService.PREFERENCES_CONSERVEES`): every key below
 * is erased with the rest, without panic mode having to know that the lock exists. A separate file
 * would be one more thing for it to remember — the kind of list that goes stale.
 *
 * ⚠️ The file is readable by anyone who can read the app's sandbox. What it holds is designed for
 * that: the PIN verifier is useless away from this device's Keystore (see [AppLockKeystore]), and
 * the counter can only be rewound, by someone with that access, on a device they already own.
 */
@Singleton
class PreferencesAppLockStore @Inject constructor(private val prefs: LegacyPreferences) : AppLockStore {

    override fun configuredNow(): Boolean = prefs.contains(KEY_PIN)

    override val configured: Flow<Boolean> = observing { configuredNow() }

    override fun pinRecord(): PinRecord {
        if (!prefs.contains(KEY_PIN)) return PinRecord.Absent
        val encoded = prefs.stringOrNull(KEY_PIN) ?: return PinRecord.Unreadable
        return StoredPin.decode(encoded)?.let(PinRecord::Present) ?: PinRecord.Unreadable
    }

    override fun biometricEnabledNow(): Boolean = prefs.booleanOrNull(KEY_BIOMETRIC) == true

    override val biometricEnabled: Flow<Boolean> = observing { biometricEnabledNow() }

    override fun relockDelayNow(): RelockDelay =
        RelockDelay.fromSeconds(prefs.long(KEY_RELOCK_DELAY, RelockDelay.IMMEDIATELY.seconds.toLong()))

    override val relockDelay: Flow<RelockDelay> = observing { relockDelayNow() }

    override fun enable(pin: StoredPin): Boolean = prefs.commit {
        // Leftovers of an earlier lock go first: a new PIN starts with a clean counter, and biometrics
        // must be turned on again — with this PIN — rather than inherited.
        prefs.keysStartingWith(PREFIX).forEach { remove(it) }
        putString(KEY_PIN, pin.encode())
    }

    override fun replacePin(pin: StoredPin): Boolean = prefs.commit { putString(KEY_PIN, pin.encode()) }

    override fun disable(): Boolean = prefs.commit {
        // By prefix, not by list: a key added to the lock later is removed without anyone having to
        // remember it here.
        prefs.keysStartingWith(PREFIX).forEach { remove(it) }
    }

    override fun setBiometricEnabled(enabled: Boolean): Boolean = prefs.commit {
        if (enabled) putBoolean(KEY_BIOMETRIC, true) else remove(KEY_BIOMETRIC)
    }

    override fun setRelockDelay(delay: RelockDelay): Boolean = prefs.commit {
        putLong(KEY_RELOCK_DELAY, delay.seconds.toLong())
    }

    override fun throttle(): PersistedThrottle {
        val failures = prefs.long(KEY_FAILURES, 0L)
        if (failures <= 0L) return PersistedThrottle.NONE
        val boot = prefs.long(KEY_WAIT_BOOT, NO_BOOT)
        return PersistedThrottle(
            failures = failures.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            waitUntilElapsed = prefs.long(KEY_WAIT_UNTIL, 0L),
            boot = if (boot == NO_BOOT) null else boot.toInt(),
        )
    }

    override fun saveThrottle(throttle: PersistedThrottle): Boolean = prefs.commit {
        if (throttle.failures == 0) {
            remove(KEY_FAILURES)
            remove(KEY_WAIT_UNTIL)
            remove(KEY_WAIT_BOOT)
        } else {
            putLong(KEY_FAILURES, throttle.failures.toLong())
            putLong(KEY_WAIT_UNTIL, throttle.waitUntilElapsed)
            val boot = throttle.boot
            if (boot == null) remove(KEY_WAIT_BOOT) else putLong(KEY_WAIT_BOOT, boot.toLong())
        }
    }

    private fun <T> observing(read: () -> T): Flow<T> = prefs.changes().map { read() }.distinctUntilChanged()

    private companion object {
        /** Every key of the lock starts with this — [disable] and [enable] rely on it. */
        const val PREFIX = "app_lock_"

        const val KEY_PIN = "app_lock_pin"
        const val KEY_BIOMETRIC = "app_lock_biometric"
        const val KEY_RELOCK_DELAY = "app_lock_relock_delay_seconds"
        const val KEY_FAILURES = "app_lock_failures"
        const val KEY_WAIT_UNTIL = "app_lock_wait_until_elapsed_ms"
        const val KEY_WAIT_BOOT = "app_lock_wait_boot"

        /** `BOOT_COUNT` starts at 1, so -1 cannot be a real boot. */
        const val NO_BOOT = -1L
    }
}
