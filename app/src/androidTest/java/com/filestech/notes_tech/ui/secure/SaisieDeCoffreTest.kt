package com.filestech.notes_tech.ui.secure

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 🔴 **The fields of a vault note: the keyboard learns nothing, the selection copies protected** —
 * security audit of 2026-09-26, F4 and F1 — on a real text field, the Material3 one of the editor.
 *
 * F4 is read where the keyboard reads it: an outer interceptor receives the request that opens the
 * keyboard, after [SaisieDeCoffre]'s own, and fills an `EditorInfo` from it. F1 goes through the
 * field's own Copy action, the one the selection toolbar calls. Each has its control: an inactive
 * wrapper — an ordinary note — changes nothing.
 */
@RunWith(AndroidJUnit4::class)
class SaisieDeCoffreTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val deposes = mutableListOf<String>()
    private val echecs = mutableListOf<String>()

    @Test
    fun a_vault_note_field_asks_the_keyboard_not_to_learn() {
        assertThat(optionsDuClavier(actif = true) and EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING)
            .isNotEqualTo(0)
    }

    @Test
    fun the_control_an_ordinary_note_field_does_not() {
        assertThat(optionsDuClavier(actif = false) and EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING)
            .isEqualTo(0)
    }

    @Test
    fun the_selection_copy_of_a_vault_note_goes_the_protected_way() {
        val natif = pressePapiersNatif()
        natif.setPrimaryClip(ClipData.newPlainText("before", "before"))

        copierParLaSelection(actif = true, texte = "PIN 4242")

        assertThat(deposes).containsExactly("PIN 4242")
        // The plain clip was never written.
        assertThat(natif.primaryClip?.getItemAt(0)?.text?.toString()).isEqualTo("before")
    }

    @Test
    fun the_control_the_selection_copy_of_an_ordinary_note_is_native() {
        copierParLaSelection(actif = false, texte = "Groceries")

        assertThat(deposes).isEmpty()
        assertThat(pressePapiersNatif().primaryClip?.getItemAt(0)?.text?.toString()).isEqualTo("Groceries")
    }

    /**
     * The protected clipboard refuses: the text is reported, never written plain. For a Cut, Compose
     * has already removed it from the field — the report is what lets the editor put it back.
     */
    @Test
    fun a_refused_protected_write_is_reported_and_never_falls_back_to_a_plain_clip() {
        val natif = pressePapiersNatif()
        natif.setPrimaryClip(ClipData.newPlainText("before", "before"))

        copierParLaSelection(
            actif = true,
            texte = "PIN 4242",
            action = SemanticsActions.CutText,
            deposer = { error("clipboard refused") },
        )

        assertThat(echecs).containsExactly("PIN 4242")
        assertThat(natif.primaryClip?.getItemAt(0)?.text?.toString()).isEqualTo("before")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalComposeUiApi::class)
    private fun optionsDuClavier(actif: Boolean): Int {
        var options: Int? = null
        regle.setContent {
            InterceptPlatformTextInput(
                interceptor = { requete, _ ->
                    val attributs = EditorInfo()
                    requete.createInputConnection(attributs)
                    options = attributs.imeOptions
                    awaitCancellation()
                },
            ) {
                SaisieDeCoffre(actif = actif, deposer = { deposes += it }, surEchec = { echecs += it }) {
                    var valeur by remember { mutableStateOf(TextFieldValue("")) }
                    TextField(value = valeur, onValueChange = { valeur = it }, modifier = Modifier.testTag(CHAMP))
                }
            }
        }
        regle.onNodeWithTag(CHAMP).performClick()
        regle.waitUntil(ATTENTE_MS) { options != null }
        return options!!
    }

    private fun copierParLaSelection(
        actif: Boolean,
        texte: String,
        action: SemanticsPropertyKey<AccessibilityAction<() -> Boolean>> = SemanticsActions.CopyText,
        deposer: suspend (String) -> Unit = { deposes += it },
    ) {
        regle.setContent {
            SaisieDeCoffre(actif = actif, deposer = deposer, surEchec = { echecs += it }) {
                var valeur by remember { mutableStateOf(TextFieldValue(texte)) }
                TextField(value = valeur, onValueChange = { valeur = it }, modifier = Modifier.testTag(CHAMP))
            }
        }
        regle.onNodeWithTag(CHAMP).performClick()
        regle.onNodeWithTag(CHAMP).performTextInputSelection(TextRange(0, texte.length))
        regle.onNodeWithTag(CHAMP).performSemanticsAction(action)
        regle.waitForIdle()
    }

    private fun pressePapiersNatif(): ClipboardManager =
        regle.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private companion object {
        const val CHAMP = "champ"
        const val ATTENTE_MS = 5_000L
    }
}
