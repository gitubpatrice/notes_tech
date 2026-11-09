package com.filestech.notes_tech.ui.scellee

import com.filestech.notes_tech.ui.common.DepotEnMemoire
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A sealed note received — its file's bytes, still sealed — and not yet opened (D-044, 3.4.0). A
 * [DepotEnMemoire]: in memory, taken once, opened only once the app lock is passed ([NotesScelleesRecues]).
 * Two minutes to unlock the app; once taken, the code dialog keeps it as long as it is open.
 */
@Singleton
class NotesScelleesEnAttente @Inject constructor() : DepotEnMemoire<ByteArray>(DUREE_DE_VIE_MS) {

    companion object {
        const val DUREE_DE_VIE_MS = 2 * 60 * 1000L
    }
}
