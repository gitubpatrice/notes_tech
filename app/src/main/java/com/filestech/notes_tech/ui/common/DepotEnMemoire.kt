package com.filestech.notes_tech.ui.common

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Something handed to the UNLOCKED app by an activity that runs without it — a share received (3.2.0),
 * a launcher shortcut (3.4.0).
 *
 * 🔴 **In memory only, taken once, and short-lived.** The handing activity puts it here and opens the
 * app ([ouvrirLAppli]); a composable that exists only once the app lock is passed takes it. Nothing is
 * therefore done while the app is locked, and nothing goes through `MainActivity`'s intent, whose
 * extras stay dropped (audit of 2026-09-26, E1). A newer deposit replaces one not yet taken; a process
 * that dies in between loses it.
 *
 * Past [dureeDeVieMs] it is dropped (security review of 3.2.0, L3): handed over while the app was
 * locked, it must not act hours later, at the next unlock, when nobody asked for it any more.
 */
open class DepotEnMemoire<T : Any>(private val dureeDeVieMs: Long) {

    /** Monotonic: changing the phone's clock neither expires a deposit nor keeps one alive. */
    internal var horloge: () -> Long = { SystemClock.elapsedRealtime() }

    private val enAttente = MutableStateFlow<T?>(null)
    private var deposeLe = 0L

    /** What is waiting; whether it is still fresh is [prendre]'s call. */
    val contenu: StateFlow<T?> = enAttente.asStateFlow()

    @Synchronized
    fun deposer(valeur: T) {
        deposeLe = horloge()
        enAttente.value = valeur
    }

    /** The deposit if it is still fresh, and nothing left behind: two callers cannot both take it. */
    @Synchronized
    fun prendre(): T? {
        val valeur = enAttente.value ?: return null
        enAttente.value = null
        return valeur.takeIf { horloge() - deposeLe <= dureeDeVieMs }
    }

    /** The user said no: it goes, undone. */
    @Synchronized
    fun abandonner() {
        enAttente.value = null
    }
}

/** The app as the launcher opens it: its task brought back if it exists, a new one otherwise. */
internal fun Activity.ouvrirLAppli() {
    val lancement = packageManager.getLaunchIntentForPackage(packageName) ?: return
    lancement.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    startActivity(lancement)
}
