package com.filestech.notes_tech.ui.raccourcis

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.filestech.notes_tech.ui.common.ouvrirLAppli
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Where a launcher shortcut lands (D-041, 3.4.0). No screen: it deposits what was asked in
 * [RaccourcisEnAttente], opens the app and goes.
 *
 * 🔴 **Not exported.** A shortcut is started by the system as this app, so it reaches a component no
 * other app can — the reason the request does not go through the exported `MainActivity`, whose intent
 * stays ignored. An unknown action deposits nothing: the app simply opens.
 */
@AndroidEntryPoint
class RaccourciActivity : ComponentActivity() {

    @Inject
    lateinit var enAttente: RaccourcisEnAttente

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated (rotation, process restored): the first instance did it.
        if (savedInstanceState != null) {
            finish()
            return
        }
        raccourciDe(intent?.action)?.let(enAttente::deposer)
        ouvrirLAppli()
        finish()
    }

    internal companion object {
        const val ACTION_NOUVELLE_NOTE = "com.filestech.notes_tech.action.NOUVELLE_NOTE"
        const val ACTION_DICTEE = "com.filestech.notes_tech.action.DICTEE"

        fun raccourciDe(action: String?): Raccourci? = when (action) {
            ACTION_NOUVELLE_NOTE -> Raccourci.NOUVELLE_NOTE
            ACTION_DICTEE -> Raccourci.DICTEE
            else -> null
        }
    }
}
