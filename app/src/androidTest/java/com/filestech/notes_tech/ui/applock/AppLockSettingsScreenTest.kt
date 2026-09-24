package com.filestech.notes_tech.ui.applock

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.IsolatedPreferencesContext
import com.filestech.notes_tech.R
import com.filestech.notes_tech.controllerWithScreenshotsAllowed
import com.filestech.notes_tech.security.applock.BiometricAvailability
import com.filestech.notes_tech.security.applock.RelockDelay
import com.filestech.notes_tech.ui.actionnablesSansNom
import com.filestech.notes_tech.ui.actionsPerduesALaFusion
import com.filestech.notes_tech.ui.secure.LocalSecureWindow
import com.filestech.notes_tech.ui.settings.SettingsScreen
import com.filestech.notes_tech.ui.settings.SettingsUiState
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app lock's settings: which rows exist, which can be touched, what they say — and the PIN sheet
 * they open. Stateless halves only: the flows behind them are tested on the JVM
 * (`AppLockSettingsViewModelTest`), against the real manager.
 */
@RunWith(AndroidJUnit4::class)
class AppLockSettingsScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val isolated = IsolatedPreferencesContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private val secureWindow = controllerWithScreenshotsAllowed(isolated)

    @After
    fun tearDown() = isolated.delete(PLUGIN_FILE)

    private val section = mutableStateOf(AppLockSettingsState(false, false, RelockDelay.IMMEDIATELY))
    private val availability = mutableStateOf(BiometricAvailability.AVAILABLE)
    private val calls = mutableListOf<String>()

    private fun text(id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

    private fun showSection() {
        rule.setContent {
            CompositionLocalProvider(LocalSecureWindow provides secureWindow) {
                NotesTechTheme {
                    Column {
                        AppLockSectionContent(
                            state = section.value,
                            availability = availability.value,
                            onToggleLock = { calls += "toggle" },
                            onChangePin = { calls += "change" },
                            onToggleBiometric = { calls += "biometric" },
                            onOpenDelay = { calls += "delay" },
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun only_the_switch_shows_until_a_lock_is_set() {
        showSection()
        rule.onNodeWithText(text(R.string.app_lock_settings_toggle)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.app_lock_settings_change_pin)).assertDoesNotExist()
        rule.onNodeWithText(text(R.string.app_lock_settings_biometric)).assertDoesNotExist()

        rule.runOnIdle { section.value = section.value.copy(configured = true) }

        rule.onNodeWithText(text(R.string.app_lock_settings_change_pin)).performClick()
        rule.onNodeWithText(text(R.string.app_lock_settings_delay)).performClick()
        rule.onNodeWithText(text(R.string.app_lock_settings_toggle)).performClick()
        assertThat(calls).containsExactly("change", "delay", "toggle").inOrder()
    }

    /**
     * Biometrics can be turned ON only where a strong sensor is enrolled, and OFF always — a user must
     * never be stuck with a setting the device no longer honours.
     */
    @Test
    fun biometrics_cannot_be_turned_on_without_a_sensor_but_can_always_be_turned_off() {
        section.value =
            AppLockSettingsState(configured = true, biometricEnabled = false, relockDelay = RelockDelay.IMMEDIATELY)
        availability.value = BiometricAvailability.NOT_ENROLLED
        showSection()

        rule.onNode(hasText(text(R.string.app_lock_settings_biometric)), useUnmergedTree = false)
            .assertIsNotEnabled()
        rule.onNodeWithText(text(R.string.app_lock_settings_biometric_not_enrolled)).assertIsDisplayed()

        rule.runOnIdle { section.value = section.value.copy(biometricEnabled = true) }
        rule.onNode(hasText(text(R.string.app_lock_settings_biometric))).assertIsEnabled().performClick()
        assertThat(calls).containsExactly("biometric")

        rule.runOnIdle { availability.value = BiometricAvailability.UNAVAILABLE }
        rule.onNodeWithText(text(R.string.app_lock_settings_biometric_unavailable)).assertIsDisplayed()
    }

    @Test
    fun the_delay_row_says_the_delay() {
        section.value =
            AppLockSettingsState(configured = true, biometricEnabled = false, relockDelay = RelockDelay.MINUTE_1)
        showSection()

        rule.onNodeWithText(rule.activity.resources.getQuantityString(R.plurals.app_lock_delay_minutes, 1, 1))
            .assertIsDisplayed()
    }

    @Test
    fun the_section_has_no_actionable_without_a_name() {
        section.value =
            AppLockSettingsState(configured = true, biometricEnabled = true, relockDelay = RelockDelay.MINUTES_5)
        showSection()

        assertThat(rule.actionnablesSansNom()).isEmpty()
        assertThat(rule.actionsPerduesALaFusion()).isEmpty()
    }

    // ── The PIN sheet ────────────────────────────────────────────────────────────────────────────

    private val sheet = mutableStateOf(PinSheetState(PinPurpose.ENABLE, PinStep.NEW))
    private val entered = mutableListOf<String>()

    private fun showSheet() {
        rule.setContent {
            CompositionLocalProvider(LocalSecureWindow provides secureWindow) {
                NotesTechTheme {
                    AppLockPinSheet(sheet = sheet.value, onPin = { entered += it }, onDismiss = { calls += "dismiss" })
                }
            }
        }
        rule.waitForIdle()
    }

    private fun type(pin: String) = pin.forEach {
        rule.onNodeWithContentDescription(text(R.string.vault_pin_key_label, "$it")).performClick()
    }

    @Test
    fun choosing_a_pin_warns_what_forgetting_it_costs_and_hands_over_the_digits() {
        showSheet()
        rule.onNodeWithText(text(R.string.app_lock_pin_new_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.app_lock_pin_new_warning)).assertIsDisplayed()

        type("1357")
        rule.onNodeWithText(text(R.string.common_validate)).performClick()

        assertThat(entered).containsExactly("1357")
        assertThat(secureWindow.activeNow()).isTrue()
    }

    @Test
    fun the_current_pin_step_has_no_warning_and_a_wait_disables_the_pad() {
        sheet.value =
            PinSheetState(PinPurpose.DISABLE, PinStep.CURRENT, waitMillis = 20_000L, waitAnnouncedMillis = 30_000L)
        showSheet()

        rule.onNodeWithText(text(R.string.app_lock_pin_current_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.app_lock_pin_new_warning)).assertDoesNotExist()
        rule.onNodeWithContentDescription(text(R.string.vault_pin_key_label, "1")).assertIsNotEnabled()
    }

    // ── The screenshot switch, when the lock forces the flag ─────────────────────────────────────

    /**
     * Below Android 13 the lock forces FLAG_SECURE: the switch must say so and not pretend that
     * screenshots are allowed. Rendered with the flag given explicitly, so that this case runs on any
     * version.
     */
    @Test
    fun the_screenshot_switch_says_when_the_lock_forces_it() {
        rule.setContent {
            CompositionLocalProvider(LocalSecureWindow provides secureWindow) {
                NotesTechTheme {
                    SettingsScreen(
                        state = SettingsUiState(secureWindow = false),
                        paniqueEnCours = false,
                        onBack = {},
                        onTheme = {},
                        onLocale = {},
                        onSort = {},
                        onSecureWindow = { calls += "secure" },
                        onAutoLock = {},
                        onPanic = {},
                        onOpenAbout = {},
                        onOpenVoiceSetup = {},
                        fenetreImposeeParLeVerrou = true,
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText(text(R.string.app_lock_secure_window_forced)).assertIsDisplayed()
        val row = rule.onNode(hasText(text(R.string.settings_secure_window)))
        row.assertIsNotEnabled()
        assertThat(row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
            .isEqualTo(ToggleableState.On)
        assertThat(calls).isEmpty()
    }

    private companion object {
        const val PLUGIN_FILE = "FlutterSharedPreferences"
    }
}
