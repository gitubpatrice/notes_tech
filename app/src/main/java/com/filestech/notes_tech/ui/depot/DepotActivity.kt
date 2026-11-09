package com.filestech.notes_tech.ui.depot

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.ui.common.avecLaLangueChoisie
import com.filestech.notes_tech.ui.secure.ClavierSansApprentissage
import com.filestech.notes_tech.ui.secure.sansActionsDeTexteDesAutresApps
import com.filestech.notes_tech.ui.secure.sansClassificationDeTexte
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.filestech.notes_tech.ui.theme.estSombre
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The blind drop (D-042, 3.4.0): write a note without unlocking Notes Tech, and read nothing.
 *
 * Opened by the quick settings tile ([TuileDeDepot]) — which asks the PHONE to be unlocked first — and by
 * the launcher shortcut "Quick drop". Only the app lock is stepped over, and only to write.
 *
 * 🔴 What this activity must hold to, every one of them because it can be the first of its process (the
 * tile starts it in a fresh one, where `MainActivity` never ran):
 * - **Not exported**: the tile and the shortcut start it as this app; no other app can.
 * - **Protected window, always**: no screenshot, no preview in the recent apps, whatever the setting.
 * - **The selection toolbar keeps editing entries only**: other apps' text actions and the classifier
 *   are cut here too ([sansClassificationDeTexte], [sansActionsDeTexteDesAutresApps]) — "Translate" sent
 *   the selected text to another app in 3.1.1.
 * - **The keyboard's learning** follows the setting of D-040, as everywhere.
 * - **The text never reaches the saved state** — see [DepotViewModel].
 */
@AndroidEntryPoint
class DepotActivity : ComponentActivity() {

    @Inject
    lateinit var settings: AppSettings

    private val viewModel: DepotViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.avecLaLangueChoisie())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        sansClassificationDeTexte()
        sansActionsDeTexteDesAutresApps()
        enableEdgeToEdge()
        setContent {
            val theme by settings.theme.collectAsStateWithLifecycle(initialValue = settings.themeNow())
            val sansApprentissage by settings.keyboardNoLearning.collectAsStateWithLifecycle(
                initialValue = settings.keyboardNoLearningNow(),
            )
            val etat by viewModel.etat.collectAsStateWithLifecycle()
            LaunchedEffect(etat.depose) {
                if (etat.depose) {
                    Toast.makeText(this@DepotActivity, R.string.depot_done, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            NotesTechTheme(darkTheme = estSombre(theme)) {
                ClavierSansApprentissage(actif = sansApprentissage) {
                    EcranDeDepot(
                        etat = etat,
                        onTitre = viewModel::titre,
                        onTexte = viewModel::texte,
                        onDeposer = viewModel::deposer,
                        onFermer = ::finish,
                    )
                }
            }
        }
    }
}
