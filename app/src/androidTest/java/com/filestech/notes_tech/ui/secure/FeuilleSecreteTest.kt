package com.filestech.notes_tech.ui.secure

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.IsolatedPreferencesContext
import com.filestech.notes_tech.controllerWithScreenshotsAllowed
import com.filestech.notes_tech.ui.applock.AppLockPinSheet
import com.filestech.notes_tech.ui.applock.PinPurpose
import com.filestech.notes_tech.ui.applock.PinSheetState
import com.filestech.notes_tech.ui.applock.PinStep
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.filestech.notes_tech.ui.vault.FeuilleDeCode
import com.filestech.notes_tech.ui.vault.FeuilleDePhraseSecrete
import com.filestech.notes_tech.ui.vault.VaultSheetState
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 🔴 **A sheet where a secret is typed carries `FLAG_SECURE` on its OWN window** — security audit of
 * 2026-09-26, F2 — read on that window, over an activity whose window does not carry it: the case of
 * "Hide in recent apps" turned off, where the sheet used to copy the parent's missing flag.
 *
 * The control is an ordinary sheet over the same activity: it is born without the flag, which proves
 * both that the activity's window does not lend it and that the reading sees the sheet's window.
 */
@RunWith(AndroidJUnit4::class)
class FeuilleSecreteTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val isole = IsolatedPreferencesContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private val fenetre = controllerWithScreenshotsAllowed(isole)

    @After
    fun tearDown() = isole.delete(PLUGIN_FILE)

    @Test
    fun the_vault_code_pad_is_protected_on_its_own_window() {
        poser {
            FeuilleDeCode(
                state = VaultSheetState(),
                nomDuDossier = "Bank",
                creating = false,
                chiffrementEnCours = { false },
                onQuitter = {},
                onValider = {},
                onFermerSurUneIssueFinale = {},
            )
        }
        assertThat(drapeauDeLaFeuille()).isTrue()
    }

    @Test
    fun the_vault_passphrase_sheet_is_protected_on_its_own_window() {
        poser {
            FeuilleDePhraseSecrete(
                state = VaultSheetState(),
                nomDuDossier = "Bank",
                creating = false,
                chiffrementEnCours = { false },
                onQuitter = {},
                onValider = {},
                onFermerSurUneIssueFinale = {},
            )
        }
        assertThat(drapeauDeLaFeuille()).isTrue()
    }

    @Test
    fun the_app_lock_pin_sheet_is_protected_on_its_own_window() {
        poser { AppLockPinSheet(sheet = PinSheetState(PinPurpose.ENABLE, PinStep.NEW), onPin = {}, onDismiss = {}) }
        assertThat(drapeauDeLaFeuille()).isTrue()
    }

    @Test
    fun the_control_an_ordinary_sheet_is_not() {
        poser { ModalBottomSheet(onDismissRequest = {}) { Text("Plain") } }
        assertThat(regle.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE).isEqualTo(0)
        assertThat(drapeauDeLaFeuille()).isFalse()
    }

    private fun poser(contenu: @Composable () -> Unit) {
        regle.setContent {
            CompositionLocalProvider(LocalSecureWindow provides fenetre) {
                NotesTechTheme { contenu() }
            }
        }
        regle.waitForIdle()
    }

    /** `FLAG_SECURE` on the window of the sheet itself — the dialog root, not the activity's. */
    private fun drapeauDeLaFeuille(): Boolean {
        var drapeaux = 0
        onView(isRoot()).inRoot(isDialog()).check { vue, _ ->
            drapeaux = (vue.rootView.layoutParams as WindowManager.LayoutParams).flags
        }
        return drapeaux and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    private companion object {
        const val PLUGIN_FILE = "FlutterSharedPreferences"
    }
}
