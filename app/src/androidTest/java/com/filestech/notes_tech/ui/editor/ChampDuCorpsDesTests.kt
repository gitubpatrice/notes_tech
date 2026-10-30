package com.filestech.notes_tech.ui.editor

import android.app.Activity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.descendants

/**
 * The note body's `EditText` (3.2.0, [ChampDuCorps]) in [activite]'s window, or `null` while there is
 * none. Compose's semantics do not reach into it: the screen tests read it here. Main thread.
 */
internal fun champDuCorpsDe(activite: Activity): ChampNatifDuCorps? =
    (activite.window.decorView as ViewGroup).descendants.filterIsInstance<ChampNatifDuCorps>().firstOrNull()

/** Types [texte] where the cursor is, as a keyboard does: through the field's input connection. Main thread. */
internal fun ChampNatifDuCorps.taper(texte: String) {
    checkNotNull(onCreateInputConnection(EditorInfo())).commitText(texte, 1)
}
