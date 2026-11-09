package com.filestech.notes_tech.ui.secure

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.core.view.inputmethod.EditorInfoCompat

/**
 * Asks the keyboard not to learn what is typed in the fields under it (D-040, 3.4.0).
 *
 * Compose 1.12 sets no `IME_FLAG_NO_PERSONALIZED_LEARNING` and offers no option for it: the request
 * that opens the keyboard is intercepted and the flag added — the mechanism [SaisieDeCoffre] brought
 * for vault notes in 3.2.0 (audit F4), shared here. The note's body is a native `EditText`, which no
 * interceptor reaches: it reads [LocalClavierSansApprentissage] and sets the flag itself.
 *
 * It is a REQUEST to the keyboard. Gboard and SwiftKey honour it; a keyboard may ignore it. The
 * suggestions stay: only the learning stops.
 *
 * ⚠️ **Always composed, acting only while [actif]** — for the reason [SaisieDeCoffre] gives: a wrapper
 * appearing or vanishing changes the structure of the composition, and every field under it would
 * lose its state. Turning the setting on or off reaches a keyboard already open only at its next
 * opening; the native body reopens its own at once.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ClavierSansApprentissage(actif: Boolean, contenu: @Composable () -> Unit) {
    val estActif by rememberUpdatedState(actif)
    CompositionLocalProvider(LocalClavierSansApprentissage provides actif) {
        InterceptPlatformTextInput(
            interceptor = { requete, suivant ->
                suivant.startInputMethod(if (estActif) sansApprentissage(requete) else requete)
            },
            content = contenu,
        )
    }
}

/** [requete], with `IME_FLAG_NO_PERSONALIZED_LEARNING` added to what it tells the keyboard. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun sansApprentissage(requete: PlatformTextInputMethodRequest): PlatformTextInputMethodRequest =
    PlatformTextInputMethodRequest { attributs: EditorInfo ->
        requete.createInputConnection(attributs).also {
            attributs.imeOptions = attributs.imeOptions or EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
    }

/** Whether the NATIVE text fields under [ClavierSansApprentissage] must ask it — see `ChampDuCorps`. */
internal val LocalClavierSansApprentissage = compositionLocalOf { false }
