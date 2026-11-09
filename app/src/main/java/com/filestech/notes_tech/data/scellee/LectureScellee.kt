package com.filestech.notes_tech.data.scellee

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
 * 🔴 **Another app's provider is untrusted** (external review of 3.4.0): it may never answer, or hand back
 * files of 4 MiB as fast as it is asked.
 * - **One reading at a time**, in the whole process: another one is refused, not queued — a burst of
 *   "Open with" cannot pile up 4 MiB buffers.
 * - **On a thread of its own**: a provider that blocks holds that thread, never the shared IO pool.
 * - **[delaiMs] at most**: past it the stream is CLOSED — a coroutine cannot stop a blocked `read`, closing
 *   its descriptor does —, and the reading is given up.
 */
suspend fun lireUneNoteScellee(resolver: ContentResolver, uri: Uri, delaiMs: Long = DELAI_MS): LectureScellee {
    if (!lectureEnCours.compareAndSet(false, true)) return LectureScellee.Illisible
    val flux = AtomicReference<InputStream?>()
    // Not a child of the caller: a reading stuck in a provider must not keep the caller waiting past the delay.
    val lecture = CoroutineScope(SupervisorJob() + FIL_DES_FOURNISSEURS).async {
        try {
            resolver.openInputStream(uri)?.also(flux::set)?.use(::lireBorne) ?: LectureScellee.Illisible
        } catch (e: IOException) {
            Timber.w(e, "note scellee illisible")
            LectureScellee.Illisible
        } catch (e: SecurityException) {
            Timber.w(e, "note scellee refusee par son fournisseur")
            LectureScellee.Illisible
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // A provider is another app's code: whatever it throws ends the reading, not the app.
            Timber.w(e, "note scellee : fournisseur en erreur")
            LectureScellee.Illisible
        } finally {
            lectureEnCours.set(false)
        }
    }
    return withTimeoutOrNull(delaiMs) { lecture.await() } ?: run {
        try {
            flux.get()?.close()
        } catch (e: IOException) {
            Timber.w(e, "flux d'une note scellee non ferme")
        }
        lecture.cancel()
        lectureEnCours.set(false)
        LectureScellee.Illisible
    }
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

private val lectureEnCours = AtomicBoolean(false)

/** One thread, for the providers of other apps only — see [lireUneNoteScellee]. */
private val FIL_DES_FOURNISSEURS = Executors.newSingleThreadExecutor { tache ->
    Thread(tache, "notes-scellees").apply { isDaemon = true }
}.asCoroutineDispatcher()

private const val TAMPON = 64 * 1024

/** As the share receiver's: a provider that never answers keeps no window open longer. */
private const val DELAI_MS = 20_000L
