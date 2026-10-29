package com.filestech.notes_tech.ui.share

import com.filestech.notes_tech.domain.importation.PartageRecu
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A share received and not yet turned into notes (3.2.0).
 *
 * 🔴 **In memory only, and taken once.** [RecevoirUnPartageActivity] puts it here and opens the app;
 * [PartagesRecus], composed only once the app lock is passed, takes it. A share is therefore never
 * written while the app is locked, and never through `MainActivity`'s intent, whose extras stay
 * dropped (audit of 2026-09-26, E1). A newer share replaces one not yet taken; a process that dies in
 * between loses it — the other app still has it.
 */
@Singleton
class PartagesEnAttente @Inject constructor() {

    private val enAttente = MutableStateFlow<PartageRecu?>(null)

    val partage: StateFlow<PartageRecu?> = enAttente.asStateFlow()

    fun deposer(recu: PartageRecu) {
        enAttente.value = recu
    }

    /** The share, and nothing left behind: two callers cannot both take it. */
    fun prendre(): PartageRecu? = enAttente.getAndUpdate { null }
}
