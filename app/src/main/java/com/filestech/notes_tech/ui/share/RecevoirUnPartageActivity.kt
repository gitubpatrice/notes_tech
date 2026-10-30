package com.filestech.notes_tech.ui.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.filestech.notes_tech.domain.importation.FichierPartage
import com.filestech.notes_tech.domain.importation.LectureDUnPartage
import com.filestech.notes_tech.domain.importation.PartageRecu
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject

/**
 * "Share → Notes Tech" (3.2.0, `docs/13-VERSION-3.2.md` §4): the only component that reads what
 * another app sends. No screen; it reads, bounds, hands over to [PartagesEnAttente] and opens the app.
 *
 * 🔴 **`MainActivity` is not touched.** It keeps dropping every extra of its intent (audit of
 * 2026-09-26, E1): nothing an outside app sends reaches the navigation. What a share carries goes from
 * here to memory, and becomes notes only once the app lock is passed AND the user has confirmed
 * ([PartagesRecus]): any app can start this activity by name, without the user choosing Notes Tech.
 *
 * What is read, and what is refused:
 * - `ACTION_SEND` / `ACTION_SEND_MULTIPLE` only, any `text` type (the manifest's filters). ⚠️ Not
 *   written with its wildcard here: in Kotlin a slash-star opens a NESTED comment, and it turned the
 *   rest of this file into one (detekt saw every import unused).
 * - Files: **`content://` only**, never a provider of this app (a share must not make it read its own
 *   files — its export cache, for one), read one by one under [LectureDUnPartage]'s bounds: a size per
 *   file, a budget for the whole share, a number of files, and [DELAI_MAX_MS] in all.
 * - Extras come from any app: a malformed one is caught and counted, it does not crash the app.
 */
@AndroidEntryPoint
class RecevoirUnPartageActivity : ComponentActivity() {

    @Inject
    lateinit var enAttente: PartagesEnAttente

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated (rotation, process restored): the share was handled by the first instance.
        if (savedInstanceState != null) {
            finish()
            return
        }
        val recu = intent
        lifecycleScope.launch {
            // 🔴 Bounded in time too (security review of 3.2.0, M1): a provider that never answers
            // must not keep this window over the sending app. The read goes on in the background if
            // a read call blocks, its result dropped; the app opens without it.
            val lecture = async(Dispatchers.IO) { lire(recu) }
            val partage = withTimeoutOrNull(DELAI_MAX_MS) { lecture.await() }
            if (partage == null) lecture.cancel()
            if (partage != null) enAttente.deposer(partage)
            ouvrirLAppli()
            finish()
        }
    }

    private fun lire(intent: Intent): PartageRecu? {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null
        return try {
            val texte = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            val sujet = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            val flux = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
                )
                else -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            }
            if (flux.isEmpty()) {
                LectureDUnPartage.lireUnTexte(texte, sujet)
            } else {
                LectureDUnPartage.lireDesFichiers(flux.size) { index, maxOctets ->
                    lireUnFichier(flux[index], maxOctets)
                }
            }
        } catch (e: RuntimeException) {
            // BadParcelableException, ClassCastException…: what another app put in its intent.
            Timber.w(e, "partage illisible")
            PartageRecu(notes = emptyList(), ignores = 1)
        }
    }

    private fun lireUnFichier(uri: Uri, maxOctets: Int): FichierPartage {
        if (!estDUneAutreAppli(uri, packageName)) return FichierPartage(null, null)
        return FichierPartage(nomAffiche(uri), lireBorne(uri, maxOctets))
    }

    /** At most [maxOctets] + 1 bytes: one more than allowed is enough to know it is over. */
    private fun lireBorne(uri: Uri, maxOctets: Int): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { flux ->
            val sortie = ByteArrayOutputStream()
            val tampon = ByteArray(TAMPON)
            var lus = 0
            while (lus <= maxOctets) {
                val n = flux.read(tampon, 0, minOf(tampon.size, maxOctets + 1 - lus))
                if (n < 0) break
                sortie.write(tampon, 0, n)
                lus += n
            }
            sortie.toByteArray()
        }
    } catch (e: IOException) {
        Timber.w(e, "fichier partage illisible")
        null
    } catch (e: SecurityException) {
        Timber.w(e, "fichier partage refuse")
        null
    }

    private fun nomAffiche(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { curseur ->
            if (curseur.moveToFirst()) curseur.getString(0) else null
        }
    } catch (e: RuntimeException) {
        Timber.w(e, "nom du fichier partage illisible")
        null
    }

    /** The app as the launcher opens it: its task brought back if it exists, a new one otherwise. */
    private fun ouvrirLAppli() {
        val lancement = packageManager.getLaunchIntentForPackage(packageName) ?: return
        lancement.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(lancement)
    }

    internal companion object {
        /**
         * 🔴 **A `content://` of another app, never one of this app's own providers** — its export cache
         * holds plaintext. Compared on the HOST, and any user-info refused (security review of 3.2.0,
         * L1): `content://0@<this app>.exports/…` carries the authority `0@…`, which a `startsWith` on
         * it let through, while the content resolver strips the `0@` and reaches this app's provider.
         */
        fun estDUneAutreAppli(uri: Uri, paquet: String): Boolean = uri.scheme == ContentResolver.SCHEME_CONTENT &&
            uri.userInfo == null &&
            '@' !in uri.authority.orEmpty() &&
            !uri.host.orEmpty().startsWith(paquet, ignoreCase = true)

        private const val TAMPON = 64 * 1024

        /** Past this, a share is dropped: the app opens without it. */
        private const val DELAI_MAX_MS = 20_000L
    }
}
