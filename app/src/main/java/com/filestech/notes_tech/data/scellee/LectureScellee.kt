package com.filestech.notes_tech.data.scellee

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
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
 * Bounded: past [FichierDeNoteScellee.TAILLE_MAX] bytes really read, refused; and a file whose first
 * bytes are not the magic is refused there, unread beyond them — a video opened with the app costs eight
 * bytes. Nothing is decrypted here: the code is asked once the app is unlocked.
 */
suspend fun lireUneNoteScellee(resolver: ContentResolver, uri: Uri): LectureScellee = withContext(Dispatchers.IO) {
    try {
        resolver.openInputStream(uri)?.use(::lireBorne) ?: LectureScellee.Illisible
    } catch (e: IOException) {
        Timber.w(e, "note scellee illisible")
        LectureScellee.Illisible
    } catch (e: SecurityException) {
        Timber.w(e, "note scellee refusee par son fournisseur")
        LectureScellee.Illisible
    } catch (e: IllegalArgumentException) {
        Timber.w(e, "note scellee : adresse refusee")
        LectureScellee.Illisible
    }
}

internal fun lireBorne(entree: InputStream): LectureScellee {
    val sortie = ByteArrayOutputStream()
    val tampon = ByteArray(TAMPON)
    var total = 0L
    var magieVue = false
    while (true) {
        val lus = entree.read(tampon)
        if (lus < 0) break
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

private const val TAMPON = 64 * 1024
