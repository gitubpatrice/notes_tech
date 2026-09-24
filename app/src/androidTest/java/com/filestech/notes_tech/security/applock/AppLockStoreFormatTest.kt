package com.filestech.notes_tech.security.applock

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.IsolatedPreferencesContext
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the app lock writes, read back from the RAW preference file — the format of the Flutter
 * plugin: `flutter.` prefix, a Dart `int` as a `Long`.
 *
 * ## ⚠️ On a diverted file, never the real one (04-PIEGES §72)
 *
 * The real `FlutterSharedPreferences` of the debug install may carry a lock someone set on the test
 * phone. A test that wrote there and "cleaned up behind itself" would remove it. The preferences
 * alone are diverted to a test file; a witness checks that the real file is left exactly as it was.
 */
@RunWith(AndroidJUnit4::class)
class AppLockStoreFormatTest {

    private val target: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val context = IsolatedPreferencesContext(target)
    private lateinit var raw: SharedPreferences
    private lateinit var store: PreferencesAppLockStore
    private lateinit var realBefore: Map<String, *>

    @Before
    fun setUp() {
        realBefore = target.getSharedPreferences(PLUGIN_FILE, Context.MODE_PRIVATE).all.toMap()
        raw = context.getSharedPreferences(PLUGIN_FILE, Context.MODE_PRIVATE)
        raw.edit().clear().commit()
        store = PreferencesAppLockStore(LegacyPreferences(context))
    }

    @After
    fun tearDown() = context.delete(PLUGIN_FILE)

    private fun storedPin() = StoredPin(
        ByteArray(AppLockParams.SALT_BYTES) {
            1
        },
        ByteArray(AppLockParams.TAG_BYTES) { 2 },
    )

    /** The witness of the isolation: without it, a diversion that failed would pass every other case. */
    @Test
    fun the_real_preference_file_is_never_touched() {
        store.enable(storedPin())
        store.setRelockDelay(RelockDelay.MINUTES_5)
        store.disable()

        val realAfter = target.getSharedPreferences(PLUGIN_FILE, Context.MODE_PRIVATE).all
        assertThat(realAfter).isEqualTo(realBefore)
        assertThat(raw.all).isEmpty()
    }

    @Test
    fun what_the_lock_writes_is_in_the_plugin_format() {
        assertThat(store.enable(storedPin())).isTrue()
        assertThat(store.setRelockDelay(RelockDelay.MINUTE_1)).isTrue()
        assertThat(store.setBiometricEnabled(true)).isTrue()
        assertThat(store.saveThrottle(PersistedThrottle(failures = 6, waitUntilElapsed = 123_456L, boot = 7))).isTrue()

        assertThat(raw.getString("flutter.app_lock_pin", null)).startsWith("v1:")
        assertThat(raw.all["flutter.app_lock_relock_delay_seconds"]).isEqualTo(60L)
        assertThat(raw.all["flutter.app_lock_biometric"]).isEqualTo(true)
        assertThat(raw.all["flutter.app_lock_failures"]).isEqualTo(6L)
        assertThat(raw.all["flutter.app_lock_wait_until_elapsed_ms"]).isEqualTo(123_456L)
        assertThat(raw.all["flutter.app_lock_wait_boot"]).isEqualTo(7L)

        assertThat(store.throttle()).isEqualTo(PersistedThrottle(6, 123_456L, 7))
        assertThat(store.relockDelayNow()).isEqualTo(RelockDelay.MINUTE_1)
        assertThat((store.pinRecord() as PinRecord.Present).pin.tag).isEqualTo(storedPin().tag)
    }

    /** By prefix: a key added to the lock later is removed too, and nothing else is. */
    @Test
    fun turning_the_lock_off_removes_every_lock_key_and_nothing_else() {
        store.enable(storedPin())
        store.setBiometricEnabled(true)
        raw.edit()
            .putString("flutter.theme_mode", "dark")
            .putBoolean("flutter.app_lock_added_later", true)
            .commit()

        assertThat(store.disable()).isTrue()

        assertThat(raw.all.keys.filter { it.startsWith("flutter.app_lock_") }).isEmpty()
        assertThat(raw.getString("flutter.theme_mode", null)).isEqualTo("dark")
        assertThat(store.configuredNow()).isFalse()
    }

    /**
     * Present but unreadable is LOCKED, never "no lock" — and never a crash on the launch path, even
     * with a value of the wrong type, which `getString` would turn into a `ClassCastException`.
     */
    @Test
    fun an_unreadable_verifier_keeps_the_lock_and_never_throws() {
        raw.edit().putString("flutter.app_lock_pin", "damaged").commit()
        assertThat(store.configuredNow()).isTrue()
        assertThat(store.pinRecord()).isEqualTo(PinRecord.Unreadable)

        raw.edit().putBoolean("flutter.app_lock_pin", true).commit()
        assertThat(store.configuredNow()).isTrue()
        assertThat(store.pinRecord()).isEqualTo(PinRecord.Unreadable)
    }

    /** A damaged delay falls back to the strictest one, never to a longer one. */
    @Test
    fun an_unknown_delay_falls_back_to_immediate() {
        raw.edit().putLong("flutter.app_lock_relock_delay_seconds", 42L).commit()
        assertThat(store.relockDelayNow()).isEqualTo(RelockDelay.IMMEDIATELY)

        raw.edit().putString("flutter.app_lock_relock_delay_seconds", "300").commit()
        assertThat(store.relockDelayNow()).isEqualTo(RelockDelay.IMMEDIATELY)
    }

    /** The negative control of the prefix: the same keys without it are not seen at all. */
    @Test
    fun the_same_keys_without_the_prefix_are_not_seen() {
        raw.edit()
            .putString("app_lock_pin", storedPin().encode())
            .putBoolean("app_lock_biometric", true)
            .commit()

        assertThat(store.configuredNow()).isFalse()
        assertThat(store.biometricEnabledNow()).isFalse()
    }

    private companion object {
        /** Written out, not read from `LegacyPreferences`: this test checks that class's format. */
        const val PLUGIN_FILE = "FlutterSharedPreferences"
    }
}
