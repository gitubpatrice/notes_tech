package com.filestech.notes_tech.ui.raccourcis

import com.filestech.notes_tech.ui.common.DepotEnMemoire
import javax.inject.Inject
import javax.inject.Singleton

/** What a launcher shortcut asks the unlocked app to do (D-041, 3.4.0). */
enum class Raccourci {
    /** A blank note, opened to be written. */
    NOUVELLE_NOTE,

    /** A blank note, with the microphone opened as a tap on its button would. */
    DICTEE,
}

/**
 * A shortcut tapped and not yet carried out — a [DepotEnMemoire], which says why: in memory, taken
 * once, carried out only once the app lock is passed ([RaccourcisRecus]), never through
 * `MainActivity`'s intent. Any app can start `MainActivity`; none can make it create a note or open
 * the microphone, since [RaccourciActivity], which deposits here, is not exported.
 */
@Singleton
class RaccourcisEnAttente @Inject constructor() : DepotEnMemoire<Raccourci>(DUREE_DE_VIE_MS) {

    companion object {
        /** Long enough to unlock the app; past it, a shortcut tapped and forgotten does nothing. */
        const val DUREE_DE_VIE_MS = 2 * 60 * 1000L
    }
}
