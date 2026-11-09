package com.filestech.notes_tech.ui.secure

import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.ui.vault.ChampDePhraseSecrete
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D-040 (3.4.0) — **the keyboard is asked not to learn**, read where the keyboard reads it: an outer
 * interceptor receives the request that opens the keyboard and fills an `EditorInfo` from it, as in
 * [SaisieDeCoffreTest]. Each case has its control.
 */
@RunWith(AndroidJUnit4::class)
class ClavierSansApprentissageTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val drapeau = EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING

    @Test
    fun the_setting_on_asks_every_field_not_to_learn() {
        assertThat(optionsDuClavier(actif = true) { ChampOrdinaire() } and drapeau).isEqualTo(drapeau)
    }

    @Test
    fun the_control_the_setting_off_asks_nothing() {
        assertThat(optionsDuClavier(actif = false) { ChampOrdinaire() } and drapeau).isEqualTo(0)
    }

    /** A secret's field asks it whatever the setting says. */
    @Test
    fun a_secret_field_asks_it_even_with_the_setting_off() {
        val options = optionsDuClavier(actif = false) {
            var phrase by remember { mutableStateOf("") }
            ChampDePhraseSecrete(
                valeur = phrase,
                onValeurChange = { phrase = it },
                label = "phrase",
                actionClavier = ImeAction.Done,
                actif = true,
            )
        }

        assertThat(options and drapeau).isEqualTo(drapeau)
    }

    @Composable
    private fun ChampOrdinaire() {
        var valeur by remember { mutableStateOf(TextFieldValue("")) }
        TextField(value = valeur, onValueChange = { valeur = it })
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun optionsDuClavier(actif: Boolean, champ: @Composable () -> Unit): Int {
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
                ClavierSansApprentissage(actif = actif, contenu = champ)
            }
        }
        regle.onNode(hasSetTextAction()).performClick()
        regle.waitUntil(ATTENTE_MS) { options != null }
        return options!!
    }

    private companion object {
        const val ATTENTE_MS = 5_000L
    }
}
