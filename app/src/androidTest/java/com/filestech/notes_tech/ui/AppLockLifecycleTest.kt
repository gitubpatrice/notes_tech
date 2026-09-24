package com.filestech.notes_tech.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import com.filestech.notes_tech.security.applock.AndroidAppLockKeystore
import com.filestech.notes_tech.security.applock.AndroidBiometricUnlockKey
import com.filestech.notes_tech.security.applock.AppLockManager
import com.filestech.notes_tech.security.applock.AppLockStore
import com.filestech.notes_tech.security.applock.LockChange
import com.filestech.notes_tech.security.applock.PinCheck
import com.filestech.notes_tech.security.applock.RelockDelay
import com.filestech.notes_tech.ui.applock.LOCK_SCREEN_TAG
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * **The app lock in the REAL activity**, through its real lifecycle: what `MainActivity` and
 * `AppLockLifecycle` decide, which no JVM test and no stateless screen can show.
 *
 * - a configured lock opens on the lock screen, and the PIN opens the app;
 * - leaving the app locks it, and unlocking returns to the screen that was left — the NavController
 *   kept above the lock, measured in the app and not only in `LockedAppHostTest`;
 * - a configuration change (the language change recreates the activity) does not lock;
 * - a delay spares a quick return.
 *
 * ## ⚠️ It writes the debug install's REAL preferences and Keystore keys (04-PIEGES §72)
 *
 * The activity reads them through Hilt; they cannot be diverted here. So the setup REFUSES to run
 * over a configured lock — failing, not skipping — and the teardown removes exactly what it set: the
 * lock (preferences first, keys after, like `AppLockManager.disable`) and the "presentation seen"
 * flag if it was not there. If the process dies mid-case, the debug install is left locked with the
 * PIN [PIN]: written here so that whoever finds it can get in.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppLockLifecycleTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject
    lateinit var appLock: AppLockManager

    @Inject
    lateinit var store: AppLockStore

    @Inject
    lateinit var prefs: LegacyPreferences

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private var presentationAlreadySeen = true

    @Before
    fun setUp() {
        hilt.inject()
        check(!store.configuredNow()) {
            "An app lock is configured on this device: this test would replace it. Turn it off first."
        }
        val settings = AppSettings(prefs)
        presentationAlreadySeen = !settings.shouldShowSplash()
        // The first-launch presentation comes before everything, the lock screen included.
        settings.markSplashShown()
        runBlocking { assertThat(appLock.enable(PIN)).isEqualTo(LockChange.Saved) }
    }

    @After
    fun tearDown() {
        scenario?.close()
        store.disable()
        AndroidBiometricUnlockKey().delete()
        AndroidAppLockKeystore().deleteKey()
        if (!presentationAlreadySeen) prefs.remove(SPLASH_SHOWN_KEY)
    }

    private fun text(id: Int): String = context.getString(id)

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun waitForLockScreen() = compose.waitUntilAtLeastOneExists(hasTestTag(LOCK_SCREEN_TAG), TIMEOUT_MILLIS)

    private fun waitForHome() =
        compose.waitUntilAtLeastOneExists(hasContentDescription(text(R.string.common_more_options)), TIMEOUT_MILLIS)

    private fun unlock() {
        PIN.forEach { digit ->
            compose.onNodeWithContentDescription(context.getString(R.string.vault_pin_key_label, "$digit"))
                .performScrollTo()
                .performClick()
        }
        compose.onNodeWithText(text(R.string.app_lock_unlock)).performScrollTo().performClick()
        // A key derivation on the device — up to a second on the S9.
        compose.waitUntilDoesNotExist(hasTestTag(LOCK_SCREEN_TAG), TIMEOUT_MILLIS)
    }

    @Test
    fun a_configured_lock_opens_on_the_lock_screen_and_the_pin_opens_the_app() {
        launch()
        waitForLockScreen()
        compose.onNodeWithContentDescription(text(R.string.common_more_options)).assertDoesNotExist()

        unlock()

        waitForHome()
    }

    @Test
    fun leaving_the_app_locks_it_and_unlocking_returns_to_the_same_screen() {
        launch()
        waitForLockScreen()
        unlock()
        waitForHome()
        compose.onNodeWithContentDescription(text(R.string.common_more_options)).performClick()
        compose.onNodeWithText(text(R.string.settings_title)).performClick()
        compose.waitUntilAtLeastOneExists(hasText(text(R.string.app_lock_settings_title)), TIMEOUT_MILLIS)

        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)

        waitForLockScreen()
        compose.onNodeWithText(text(R.string.app_lock_settings_title)).assertDoesNotExist()
        unlock()
        compose.waitUntilAtLeastOneExists(hasText(text(R.string.app_lock_settings_title)), TIMEOUT_MILLIS)
    }

    /** The language change recreates the activity on purpose: the PIN must not be asked there. */
    @Test
    fun a_configuration_change_does_not_lock() {
        launch()
        waitForLockScreen()
        unlock()
        waitForHome()

        scenario!!.recreate()

        waitForHome()
        compose.onNodeWithTag(LOCK_SCREEN_TAG).assertDoesNotExist()
    }

    @Test
    fun a_delay_spares_a_quick_return_but_not_an_ordinary_lock() {
        runBlocking {
            val proof = (appLock.attemptPin(PIN) as PinCheck.Accepted).proof
            assertThat(appLock.setRelockDelay(RelockDelay.MINUTE_1, proof)).isEqualTo(LockChange.Saved)
        }
        // The right PIN typed above opened the app, before any activity: close it again, so that the
        // launch below meets a lock like any other.
        appLock.lockIfConfigured()
        launch()
        waitForLockScreen()
        unlock()
        waitForHome()

        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)

        waitForHome()
        compose.onNodeWithTag(LOCK_SCREEN_TAG).assertDoesNotExist()
    }

    private companion object {
        const val PIN = "2468"
        const val TIMEOUT_MILLIS = 15_000L

        /** `AppSettings.KEY_SPLASH_SHOWN`, written out: the test restores exactly what it set. */
        const val SPLASH_SHOWN_KEY = "splash_shown_v1"
    }
}
