package com.filestech.notes_tech.ui.secure

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.core.view.inputmethod.EditorInfoCompat
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * 🔴 **Around the text fields of a vault note** — security audit of 2026-09-26, F1 and F4.
 *
 * - **F1 — the selection toolbar's Copy and Cut** wrote a plain clip: no sensitive mark, no clearing,
 *   while the menu's Copy of the same screen goes through `SensitiveClipboard` (marked, cleared after
 *   60 s, purged by the panic). The fields' clipboard is replaced: what they put in it goes the
 *   menu's way. Paste is untouched.
 * - **F4 — the keyboard learned what was typed**: Compose 1.11 sets no
 *   `IME_FLAG_NO_PERSONALIZED_LEARNING`, and exposes no option for it. The request that opens the
 *   keyboard is intercepted and the flag added. It is a request to the keyboard, which a keyboard may
 *   ignore — the phrase field of the vault sheets, a password field, is the stronger case.
 *
 * ⚠️ **Always composed, and acting only while [actif]**: a wrapper added once the note is known to
 * be in a vault would change the structure of the composition, and the fields under it would lose
 * their state — scroll, focus, selection — at the very moment the note finishes loading.
 *
 * @param deposer writes a text with the protections of the menu's Copy.
 * @param surEchec called when [deposer] failed, with the text. ⚠️ **Compose's Cut has already removed
 *   that text from the field** — `TextFieldSelectionManager.cut` calls `cutWithResult()` BEFORE
 *   `setClipEntry` (bytecode of foundation 1.11.3, checked on 2026-09-26 after an external review).
 *   Letting the failure propagate would not bring the text back and would bring the app down;
 *   keeping it quiet lost the text. The owner of the field restores it, and says the copy failed.
 *   Never a fallback to a plain clip: that is the leak this class closes.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SaisieDeCoffre(
    actif: Boolean,
    deposer: suspend (String) -> Unit,
    surEchec: (String) -> Unit,
    contenu: @Composable () -> Unit,
) {
    val estActif by rememberUpdatedState(actif)
    val deposerCourant by rememberUpdatedState(deposer)
    val surEchecCourant by rememberUpdatedState(surEchec)
    val natif = LocalClipboard.current
    val pressePapiers = remember(natif) {
        PressePapiersDeCoffre(natif, { estActif }, { deposerCourant(it) }, { surEchecCourant(it) })
    }
    CompositionLocalProvider(LocalClipboard provides pressePapiers) {
        InterceptPlatformTextInput(
            interceptor = { requete, suivant ->
                val transmise = if (!estActif) {
                    requete
                } else {
                    PlatformTextInputMethodRequest { attributs: EditorInfo ->
                        requete.createInputConnection(attributs).also {
                            attributs.imeOptions =
                                attributs.imeOptions or EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING
                        }
                    }
                }
                suivant.startInputMethod(transmise)
            },
            content = contenu,
        )
    }
}

/** The clipboard of a vault note's fields: a text written goes through [deposer]; the rest is native. */
internal class PressePapiersDeCoffre(
    private val natif: Clipboard,
    private val actif: () -> Boolean,
    private val deposer: suspend (String) -> Unit,
    private val surEchec: (String) -> Unit,
) : Clipboard {

    override val nativeClipboard: NativeClipboard get() = natif.nativeClipboard

    override suspend fun getClipEntry(): ClipEntry? = natif.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val texte = clipEntry?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (!actif() || texte == null) return natif.setClipEntry(clipEntry)
        try {
            deposer(texte)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ⚠️ No fallback to the plain clip — see [SaisieDeCoffre]'s `surEchec`.
            Timber.w(e, "copie depuis une note de coffre refusee")
            surEchec(texte)
        }
    }
}
