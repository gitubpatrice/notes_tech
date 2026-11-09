package com.filestech.notes_tech.ui.raccourcis

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.filestech.notes_tech.R
import com.filestech.notes_tech.ui.depot.DepotActivity
import timber.log.Timber

/**
 * The launcher shortcuts — a long press on the app's icon (D-041, 3.4.0).
 *
 * **Published by the code, not declared in XML**: a static shortcut names its package in its intent,
 * and Notes Tech is built under more than one application id (the release, the development builds). The
 * code builds the intent for the package it runs as. Published from `MainActivity`'s `onCreate`, with its
 * context: the labels follow the language chosen in the app, and an app in the foreground is never
 * rate-limited by the shortcut service.
 *
 * Each shortcut targets an activity of this app that is NOT exported — see [RaccourciActivity].
 */
internal fun Context.publierLesRaccourcis() {
    val raccourcis = listOf(
        raccourci(
            id = ID_NOUVELLE_NOTE,
            court = R.string.shortcut_new_note,
            long = R.string.shortcut_new_note_long,
            icone = R.drawable.ic_raccourci_note,
            intention = Intent(this, RaccourciActivity::class.java).setAction(RaccourciActivity.ACTION_NOUVELLE_NOTE),
        ),
        raccourci(
            id = ID_DICTEE,
            court = R.string.shortcut_dictation,
            long = R.string.shortcut_dictation_long,
            icone = R.drawable.ic_raccourci_dictee,
            intention = Intent(this, RaccourciActivity::class.java).setAction(RaccourciActivity.ACTION_DICTEE),
        ),
        // The blind drop (D-042) opens its own activity directly: nothing to carry out after an unlock.
        raccourci(
            id = ID_DEPOT,
            court = R.string.depot_title,
            long = R.string.depot_title,
            icone = R.drawable.ic_raccourci_depot,
            intention = Intent(this, DepotActivity::class.java).setAction(ACTION_DEPOT),
        ),
    )
    try {
        if (!ShortcutManagerCompat.setDynamicShortcuts(this, raccourcis)) Timber.w("raccourcis refuses (limite)")
    } catch (e: IllegalStateException) {
        // The user is locked out of the profile (direct boot), or the service refused: no shortcut, no harm.
        Timber.w(e, "raccourcis non publies")
    } catch (e: IllegalArgumentException) {
        Timber.w(e, "raccourcis non publies")
    }
}

private fun Context.raccourci(
    id: String,
    @StringRes court: Int,
    @StringRes long: Int,
    @DrawableRes icone: Int,
    intention: Intent,
): ShortcutInfoCompat = ShortcutInfoCompat.Builder(this, id)
    .setShortLabel(getString(court))
    .setLongLabel(getString(long))
    .setIcon(IconCompat.createWithResource(this, icone))
    .setIntent(intention)
    .build()

private const val ID_NOUVELLE_NOTE = "nouvelle_note"
private const val ID_DICTEE = "dictee"
private const val ID_DEPOT = "depot"

/** A shortcut's intent must carry an action; the drop's activity reads none. */
private const val ACTION_DEPOT = "com.filestech.notes_tech.action.DEPOT"
