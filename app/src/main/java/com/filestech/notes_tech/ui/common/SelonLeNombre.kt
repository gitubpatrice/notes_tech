package com.filestech.notes_tech.ui.common

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * One note's sentence for one note, the plural one for several (3.3.0).
 *
 * ## Why not the plural alone
 *
 * What the selection says about ONE note is what the app already said about it before 3.3.0 — "Note
 * moved to trash", "Delete permanently?" — in the five languages, reviewed: the plural's `one` would be
 * a second sentence for the same thing, worded differently ("1 note moved to the trash"), on the same
 * screen as the first. The plural serves from two notes up; its `one` exists for Android only (French
 * puts 0 and 1 there, and lint wants every quantity), with the number written, never reached here.
 */
@Composable
internal fun selonLeNombre(nombre: Int, @StringRes une: Int, @PluralsRes plusieurs: Int): String =
    if (nombre == 1) stringResource(une) else pluralStringResource(plusieurs, nombre, nombre)

/** [selonLeNombre] outside composition — for a message posted from an event. */
internal fun Resources.selonLeNombre(nombre: Int, @StringRes une: Int, @PluralsRes plusieurs: Int): String =
    if (nombre == 1) getString(une) else getQuantityString(plusieurs, nombre, nombre)
