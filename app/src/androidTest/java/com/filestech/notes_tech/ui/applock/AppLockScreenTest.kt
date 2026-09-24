package com.filestech.notes_tech.ui.applock

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.IsolatedPreferencesContext
import com.filestech.notes_tech.R
import com.filestech.notes_tech.controllerWithScreenshotsAllowed
import com.filestech.notes_tech.ui.actionnablesSansNom
import com.filestech.notes_tech.ui.actionsPerduesALaFusion
import com.filestech.notes_tech.ui.secure.LocalSecureWindow
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The lock screen, in every state it can show — without a real lock on the device.
 *
 * Driven through [AppLockScreen], the stateless half: a wrong PIN, a wait, an unverifiable lock and
 * the path to panic mode must all be REACHED here, since none of them can be produced on demand on a
 * phone. The panic mode itself never runs: `onErase` is a counter.
 */
@RunWith(AndroidJUnit4::class)
class AppLockScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** The user's setting says "screenshots allowed", so that only the screen's own request counts. */
    private val isolated = IsolatedPreferencesContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private val secureWindow = controllerWithScreenshotsAllowed(isolated)

    @After
    fun tearDown() = isolated.delete(PLUGIN_FILE)

    private val state = mutableStateOf(AppLockUiState())
    private val biometric = mutableStateOf(false)
    private val submitted = mutableListOf<String>()
    private var biometricAsked = 0
    private var erased = 0

    private fun text(id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

    private fun key(digit: Char) = rule.onNodeWithContentDescription(text(R.string.vault_pin_key_label, "$digit"))

    /**
     * Scrolled into view first: on a small screen or with large text the lock screen scrolls, and a
     * click on a node below the fold lands nowhere — the S9 run of 2026-09-24 "tapped" the biometric
     * button that way and nothing happened.
     */
    private fun SemanticsNodeInteraction.tap() = performScrollTo().performClick()

    private fun type(pin: String) = pin.forEach { key(it).tap() }

    private fun show() {
        rule.setContent {
            CompositionLocalProvider(LocalSecureWindow provides secureWindow) {
                NotesTechTheme {
                    AppLockScreen(
                        state = state.value,
                        biometricAvailable = biometric.value,
                        onSubmit = { submitted += it },
                        onPinChanged = {},
                        onBiometric = { biometricAsked++ },
                        onErase = { erased++ },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun unlock_needs_4_to_6_digits_and_hands_over_exactly_what_was_typed() {
        show()
        val unlock = rule.onNodeWithText(text(R.string.app_lock_unlock))

        type("246")
        unlock.assertIsNotEnabled()
        type("8")
        unlock.assertIsEnabled()
        unlock.tap()

        assertThat(submitted).containsExactly("2468")
    }

    /**
     * Typing over a wrong PIN must not append to it: that would spend a second attempt on a PIN
     * nobody typed (the vault sheets' lesson of 2026-08-14).
     */
    @Test
    fun the_typed_pin_is_cleared_after_every_attempt() {
        show()
        type("2468")
        rule.onNodeWithContentDescription(text(R.string.vault_pin_digits_announce, 4, 6)).assertIsDisplayed()

        rule.runOnIdle { state.value = state.value.copy(attempts = 1, message = LockMessage.WRONG_PIN) }

        rule.onNodeWithContentDescription(text(R.string.vault_pin_digits_announce, 0, 6)).assertIsDisplayed()
    }

    @Test
    fun a_wrong_pin_is_announced_assertively() {
        state.value = AppLockUiState(message = LockMessage.WRONG_PIN)
        show()

        val slot = rule.onNodeWithTag(LOCK_MESSAGE_TAG).fetchSemanticsNode().config
        assertThat(
            slot.getOrNull(SemanticsProperties.ContentDescription),
        ).containsExactly(text(R.string.app_lock_wrong_pin))
        assertThat(slot.getOrNull(SemanticsProperties.LiveRegion)).isEqualTo(LiveRegionMode.Assertive)
    }

    /**
     * During a wait nothing can be typed, and what a screen reader hears is the wait as it STARTED —
     * the ticking countdown on screen is not re-announced every second.
     */
    @Test
    fun during_a_wait_the_pad_is_off_and_the_announcement_does_not_tick() {
        state.value = AppLockUiState(waitMillis = 21_000L, waitAnnouncedMillis = 30_000L)
        show()

        key('1').assertIsNotEnabled()
        rule.onNodeWithText(text(R.string.app_lock_unlock)).assertIsNotEnabled()
        val announced = rule.onNodeWithTag(LOCK_MESSAGE_TAG).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription)
        val thirtySeconds = rule.activity.resources.getQuantityString(R.plurals.common_retry_in_seconds, 30, 30)
        assertThat(announced).containsExactly(thirtySeconds)
    }

    @Test
    fun an_unverifiable_lock_offers_only_the_erase_path_and_it_asks_for_the_word() {
        state.value = AppLockUiState(verifiable = false)
        show()

        rule.onNodeWithText(text(R.string.app_lock_unverifiable)).assertIsDisplayed()
        key('1').assertDoesNotExist()
        rule.onNodeWithText(text(R.string.app_lock_unlock)).assertDoesNotExist()

        rule.onNodeWithText(text(R.string.app_lock_forgot_erase)).tap()

        rule.onNodeWithText(text(R.string.panic_confirm_title)).assertIsDisplayed()
        assertThat(erased).isEqualTo(0)
    }

    /**
     * Forgetting the PIN is no reason to make destroying every note easier than from the settings:
     * the panic mode's own confirmation, word typed, stands between the tap and the erase.
     */
    @Test
    fun forgot_pin_leads_to_panic_mode_and_nothing_is_erased_without_the_word() {
        show()
        rule.onNodeWithText(text(R.string.app_lock_forgot_pin)).tap()
        rule.onNodeWithText(text(R.string.app_lock_forgot_body)).assertIsDisplayed()

        rule.onNodeWithText(text(R.string.app_lock_forgot_erase)).performClick()
        val confirm = rule.onNodeWithText(text(R.string.panic_confirm_yes))
        confirm.assertIsNotEnabled()
        assertThat(erased).isEqualTo(0)

        rule.onNodeWithText(
            text(R.string.panic_confirm_field_label),
        ).performTextInput(text(R.string.panic_confirm_keyword))
        confirm.assertIsEnabled()
        confirm.performClick()

        assertThat(erased).isEqualTo(1)
    }

    /**
     * A PIN that can no longer be checked does not close the biometric door, and the text must not
     * say that erasing is the only way (external review, GPT-5.6, 2026-09-24).
     */
    @Test
    fun an_unverifiable_pin_still_offers_biometrics_when_usable() {
        state.value = AppLockUiState(verifiable = false)
        biometric.value = true
        show()

        rule.onNodeWithText(text(R.string.app_lock_unverifiable_biometric)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.app_lock_unverifiable)).assertDoesNotExist()
        rule.onNodeWithText(text(R.string.app_lock_use_biometric)).tap()

        assertThat(biometricAsked).isEqualTo(1)
    }

    @Test
    fun the_biometric_button_exists_only_when_usable() {
        show()
        rule.onNodeWithText(text(R.string.app_lock_use_biometric)).assertDoesNotExist()

        rule.runOnIdle { biometric.value = true }
        rule.onNodeWithText(text(R.string.app_lock_use_biometric)).tap()

        assertThat(biometricAsked).isEqualTo(1)
    }

    /**
     * The whole screen, "forgot your PIN?" included, fits without scrolling at the default text
     * size — worst case, with the biometric button. On the S9 (360 x 740 dp) the first layout needed
     * about 800 dp: the way out for someone who forgot the PIN was below the fold.
     */
    @Test
    fun the_whole_screen_fits_without_scrolling() {
        biometric.value = true
        show()

        val screen = rule.onNodeWithTag(LOCK_SCREEN_TAG).fetchSemanticsNode().boundsInRoot
        val lowest = rule.onNodeWithText(text(R.string.app_lock_forgot_pin)).fetchSemanticsNode().boundsInRoot
        assertThat(lowest.bottom).isAtMost(screen.bottom)
        rule.onNodeWithText(text(R.string.app_lock_use_biometric)).assertIsDisplayed()
    }

    /** The keypad's pressed keys are the PIN: the screen forces FLAG_SECURE whatever the setting. */
    @Test
    fun the_screen_forces_the_secure_flag_while_it_is_shown() {
        assertThat(secureWindow.activeNow()).isFalse()
        show()
        assertThat(secureWindow.activeNow()).isTrue()
    }

    @Test
    fun no_actionable_without_a_name_in_either_state() {
        show()
        assertThat(rule.actionnablesSansNom()).isEmpty()
        assertThat(rule.actionsPerduesALaFusion()).isEmpty()

        rule.runOnIdle { state.value = AppLockUiState(verifiable = false) }
        rule.waitForIdle()
        assertThat(rule.actionnablesSansNom()).isEmpty()
        assertThat(rule.actionsPerduesALaFusion()).isEmpty()
    }

    private companion object {
        const val PLUGIN_FILE = "FlutterSharedPreferences"
    }
}
