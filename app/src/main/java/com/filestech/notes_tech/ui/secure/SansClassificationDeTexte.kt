package com.filestech.notes_tech.ui.secure

import android.app.Activity
import android.os.Build
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier

/**
 * 🔴 **No text classifier in this app** — 3.1.1, measured on 2026-10-29.
 *
 * Android's text classifier adds its own actions to the selection toolbar of every field. On a phone
 * with Google's apps, a long press on a word of a note offered **"Translate"**, first in the toolbar,
 * with Google's icon; one tap handed the selected word to the Google app, which searched it online
 * (measured on the emulator, 3.1.1 release build, ordinary note). The text of a vault note left the
 * app the same way, outside the protected clipboard (audit of 2026-09-26, F1). It is the family the
 * same audit closed for every note by removing the `PROCESS_TEXT` queries (manifest, note of cell 5),
 * which accepted losing a translator in that toolbar: this one came through another door.
 *
 * ## Why here, once
 *
 * Compose foundation 1.12 asks the classifier of the **screen's** `Context` — `LocalContext` — through
 * `TextClassificationManager.createTextClassificationSession` (read in the bytecode). The manager is
 * the activity's: a dialog or a bottom sheet wraps the activity's context and gets the same one. Set
 * here, [TextClassifier.NO_OP] covers every field of every window — a filter on each field would
 * leave out the next field anyone writes.
 *
 * What is lost: smart selection (a long press selecting a whole address or number) and the
 * classifier's actions (call, open a map, translate). Cut, Copy, Paste and Select all remain.
 *
 * Before 8.0 there is no `TextClassificationManager`; Compose classifies only from 9.0.
 */
internal fun Activity.sansClassificationDeTexte() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    getSystemService(TextClassificationManager::class.java)?.setTextClassifier(TextClassifier.NO_OP)
}
