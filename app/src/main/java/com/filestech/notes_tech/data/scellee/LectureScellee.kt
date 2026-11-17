package com.filestech.notes_tech.data.scellee

import android.content.ContentResolver
import android.net.Uri
import com.filestech.notes_tech.data.fournisseurs.LecteurDeFournisseur
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** What reading a file meant to be a sealed note gave (D-044, 3.4.0). */
sealed interface LectureScellee {
    class Lue(val octets: ByteArray) : LectureScellee
    data object PasUneNoteScellee : LectureScellee
    data object TropGrosse : LectureScellee
    data object Illisible : LectureScellee
}

/**
 * Reads [uri] as a sealed note — from "Open with" (another app's file) or the app's own picker.
 *
 * Bounded: past [FichierDeNoteScellee.TAILLE_MAX] bytes really read, refused; a file whose first bytes are
 * not the magic is refused there, unread beyond them — a video opened with the app costs eight bytes.
 * Nothing is decrypted here: the code is asked once the app is unlocked.
 *
 * 🔴 **Another app's provider is untrusted** (external review of 3.4.0): one reading at a time, on a thread
 * of its own, [delaiMs] at most with the stream CLOSED past it, whatever it throws caught — see
 * [LecteurDeFournisseur], where this mechanism moved in 3.5.0 to serve the share receiver too.
 */
suspend fun lireUneNoteScellee(resolver: ContentResolver, uri: Uri, delaiMs: Long = DELAI_MS): LectureScellee =
    LECTEUR.lire(delaiMs, refus = LectureScellee.Illisible) { suivre ->
        resolver.openInputStream(uri)?.let(suivre)?.use(::lireBorne) ?: LectureScellee.Illisible
    }

/** [entree] read whole, bounded — see [lireUneNoteScellee]. */
internal fun lireBorne(entree: InputStream): LectureScellee {
    val sortie = ByteArrayOutputStream()
    val tampon = ByteArray(TAMPON)
    var total = 0L
    var magieVue = false
    while (true) {
        val lus = entree.read(tampon)
        if (lus < 0) break
        // A stream that gives nothing without ending would loop here for ever: refused.
        if (lus == 0) return LectureScellee.Illisible
        total += lus
        if (total > FichierDeNoteScellee.TAILLE_MAX) return LectureScellee.TropGrosse
        sortie.write(tampon, 0, lus)
        if (!magieVue && sortie.size() >= FichierDeNoteScellee.MAGIQUE.size) {
            if (!FichierDeNoteScellee.aLaMagique(sortie.toByteArray())) return LectureScellee.PasUneNoteScellee
            magieVue = true
        }
    }
    return if (magieVue) LectureScellee.Lue(sortie.toByteArray()) else LectureScellee.PasUneNoteScellee
}

/** One thread, for the providers of other apps only — see [lireUneNoteScellee]. */
private val LECTEUR = LecteurDeFournisseur("notes-scellees")

private const val TAMPON = 64 * 1024

/** As the share receiver's: a provider that never answers keeps no window open longer. */
private const val DELAI_MS = 20_000L
