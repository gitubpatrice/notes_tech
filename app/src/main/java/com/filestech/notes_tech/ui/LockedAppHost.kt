package com.filestech.notes_tech.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController

/**
 * Shows the lock screen **instead of** the app while [locked], and gives the user back the exact
 * screen they left once it is lifted. Taken from Agenda Tech (`ui/LockedAppHost.kt`), D-023.
 *
 * ## Why the navigation and the saved state live here, above the lock
 *
 * Agenda Tech switched between its lock screen and a root that created its own `NavController`:
 * locking removed the whole navigation from composition, and unlocking rebuilt it on the first
 * screen — every screen's `ViewModel` and `rememberSaveable` state left behind (a restore waiting for
 * its password after a file picker, lost after the PIN; Galaxy S9, 2026-09-15). SMS Tech pushes its
 * lock as a navigation destination, which loses the screen the same way.
 *
 * Here the controller and a [rememberSaveableStateHolder] are created outside the lock switch. The
 * content still **leaves composition** while locked — nothing of it is drawn, reachable by touch or
 * read by accessibility services, and its dialogs and sheets, being part of it, are gone too — but a
 * note being edited comes back with its text, and the editor with its `ViewModel`.
 */
@Composable
fun LockedAppHost(
    locked: Boolean,
    lockScreen: @Composable () -> Unit,
    content: @Composable (NavHostController) -> Unit,
) {
    val navController = rememberNavController()
    val stateHolder = rememberSaveableStateHolder()
    if (locked) {
        lockScreen()
    } else {
        stateHolder.SaveableStateProvider(APP_CONTENT_KEY) { content(navController) }
    }
}

private const val APP_CONTENT_KEY = "app-content"
