package com.filestech.notes_tech.ui.scellee

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.filestech.notes_tech.R
import com.filestech.notes_tech.data.scellee.LectureScellee
import com.filestech.notes_tech.data.scellee.lireUneNoteScellee
import com.filestech.notes_tech.ui.common.avecLaLangueChoisie
import com.filestech.notes_tech.ui.common.ouvrirLAppli
import com.filestech.notes_tech.ui.share.RecevoirUnPartageActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * "Open with → Notes Tech" on a `.ntnote` file (D-044, 3.4.0). No screen: it reads the file — bounded —,
 * deposits it in memory ([NotesScelleesEnAttente]) and opens the app, which asks for the code once
 * unlocked ([NotesScelleesRecues]). Nothing is decrypted, nothing written here.
 *
 * 🔴 Exported, as "Open with" requires, so read as the share receiver reads (3.2.0):
 * - `content://` of ANOTHER app only ([RecevoirUnPartageActivity.estDUneAutreAppli]) — never this app's
 *   own providers, whose export cache holds plaintext;
 * - bounded in size ([com.filestech.notes_tech.data.scellee.FichierDeNoteScellee.TAILLE_MAX]) and in time;
 * - a file that is not a sealed note — the filter takes any `application/octet-stream` — is said so in
 *   one short message, and nothing else happens: the app does not even open.
 */
@AndroidEntryPoint
class OuvrirUneNoteScelleeActivity : ComponentActivity() {

    @Inject
    lateinit var enAttente: NotesScelleesEnAttente

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase.avecLaLangueChoisie())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated (rotation, process restored): the first instance did it.
        if (savedInstanceState != null) {
            finish()
            return
        }
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        if (uri == null || !RecevoirUnPartageActivity.estDUneAutreAppli(uri, packageName)) {
            finish()
            return
        }
        lifecycleScope.launch {
            val lecture = async { lireUneNoteScellee(contentResolver, uri) }
            when (val lue = withTimeoutOrNull(DELAI_MAX_MS) { lecture.await() }) {
                is LectureScellee.Lue -> {
                    enAttente.deposer(lue.octets)
                    ouvrirLAppli()
                }
                LectureScellee.PasUneNoteScellee -> dire(R.string.sealed_not_a_sealed_note)
                LectureScellee.TropGrosse -> dire(R.string.sealed_too_big)
                LectureScellee.Illisible, null -> {
                    lecture.cancel()
                    dire(R.string.sealed_error_unreadable)
                }
            }
            finish()
        }
    }

    private fun dire(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        /** Past this, the reading is dropped: a provider that never answers keeps no window open. */
        const val DELAI_MAX_MS = 20_000L
    }
}
