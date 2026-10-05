package com.filestech.notes_tech.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Hands a web or mail address to the app that handles it, and says whether one did.
 *
 * `ACTION_VIEW` with no chooser and no `CATEGORY_BROWSABLE`: the intent notes_tech 2.0.9's
 * `url_launcher` sends (`LaunchMode.externalApplication`), so a `mailto:` reaches the mail app the
 * user already chose. The page opens in that app, never here — Notes Tech has no network access.
 *
 * ⚠️ Which addresses may get this far is decided **before**, by `MarkdownPreviewReader.webTarget`
 * (`http`, `https`, `mailto` only), where it is tested; this function does not decide it again.
 *
 * The address is bounded before it gets here (`MarkdownPreviewReader.MAX_LINK_LENGTH`): a huge one
 * would overflow the Binder transaction that carries the intent.
 *
 * @return `false` when no installed app takes the address, or the one that would refuses to be
 *   started by another app (`SecurityException`, an exported activity guarded by a permission) — the
 *   caller must say so: a tap that does nothing, silently, is a control the user will keep tapping.
 */
fun ouvrirUnLienExterne(context: Context, adresse: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, adresse.toUri()))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
