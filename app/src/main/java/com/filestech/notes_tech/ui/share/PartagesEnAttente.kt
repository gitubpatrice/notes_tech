package com.filestech.notes_tech.ui.share

import android.os.SystemClock
import com.filestech.notes_tech.domain.importation.PartageRecu
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A share received and not yet turned into notes (3.2.0).
 *
 * 🔴 **In memory only, taken once, and short-lived.** [RecevoirUnPartageActivity] puts it here and
 * opens the app; [PartagesRecus], composed only once the app lock is passed, asks the user and takes
 * it. A share is therefore never written while the app is locked, and never through `MainActivity`'s
 * intent, whose extras stay dropped (audit of 2026-09-26, E1). A newer share replaces one not yet
 * taken; a process that dies in between loses it — the other app still has it.
 *
 * Past [DUREE_DE_VIE_MS] it is dropped (security review of 3.2.0, L3): shared while the app was
 * locked, it must not surface hours later, at the next unlock, as a note nobody asked for.
 */
@Singleton
class PartagesEnAttente @Inject constructor() {

    /** Monotonic: changing the phone's clock neither expires a share nor keeps one alive. */
    internal var horloge: () -> Long = { SystemClock.elapsedRealtime() }

    private val enAttente = MutableStateFlow<PartageRecu?>(null)
    private var deposeLe = 0L

    /** What is waiting, for [PartagesRecus] to show; whether it is still fresh is [prendre]'s call. */
    val partage: StateFlow<PartageRecu?> = enAttente.asStateFlow()

    @Synchronized
    fun deposer(recu: PartageRecu) {
        deposeLe = horloge()
        enAttente.value = recu
    }

    /** The share if it is still fresh, and nothing left behind: two callers cannot both take it. */
    @Synchronized
    fun prendre(): PartageRecu? {
        val recu = enAttente.value ?: return null
        enAttente.value = null
        return recu.takeIf { horloge() - deposeLe <= DUREE_DE_VIE_MS }
    }

    /** The user said no: the share goes, unwritten. */
    @Synchronized
    fun abandonner() {
        enAttente.value = null
    }

    companion object {
        const val DUREE_DE_VIE_MS = 2 * 60 * 1000L
    }
}
