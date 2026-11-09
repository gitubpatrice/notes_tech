package com.filestech.notes_tech.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * The "Dictation" launcher shortcut (3.4.0, D-041): [demarrer] once the note is open — the microphone
 * as a tap on its button opens it: no model, the install screen; no permission, the request.
 *
 * Once: saved, so a rotation or the app lock giving the screen back does not start it again.
 */
@Composable
internal fun DicteeALOuverture(demandee: Boolean, noteChargee: Boolean, demarrer: () -> Unit) {
    var faite by rememberSaveable { mutableStateOf(false) }
    val lancer by rememberUpdatedState(demarrer)
    LaunchedEffect(noteChargee) {
        if (demandee && noteChargee && !faite) {
            faite = true
            lancer()
        }
    }
}
