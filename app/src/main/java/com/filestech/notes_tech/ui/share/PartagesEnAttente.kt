package com.filestech.notes_tech.ui.share

import com.filestech.notes_tech.domain.importation.PartageRecu
import com.filestech.notes_tech.ui.common.DepotEnMemoire
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A share received and not yet turned into notes (3.2.0) — a [DepotEnMemoire] since 3.4.0, which
 * says why it lives in memory only, is taken once and expires.
 *
 * [RecevoirUnPartageActivity] puts it here and opens the app; [PartagesRecus], composed only once the
 * app lock is passed, asks the user and takes it. The other app still has what it shared: a share
 * lost to a dying process costs a second share.
 */
@Singleton
class PartagesEnAttente @Inject constructor() : DepotEnMemoire<PartageRecu>(DUREE_DE_VIE_MS) {

    /** What is waiting, for [PartagesRecus] to show. */
    val partage: StateFlow<PartageRecu?> get() = contenu

    companion object {
        const val DUREE_DE_VIE_MS = 2 * 60 * 1000L
    }
}
