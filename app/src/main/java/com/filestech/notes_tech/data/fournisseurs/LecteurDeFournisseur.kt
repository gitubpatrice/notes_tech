package com.filestech.notes_tech.data.fournisseurs

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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reading what ANOTHER app's content provider serves — a sealed note opened with the app (D-044, 3.4.0), a
 * share (3.2.0, hardened in 3.5.0, C-1). A provider is untrusted code (external review of 3.4.0): it may
 * never answer, answer forever, or throw anything.
 *
 * - **One reading at a time** per reader: another one is refused, not queued — a burst of "Open with" or
 *   of shares cannot pile up buffers.
 * - **On a thread of its own**: a provider that blocks holds that thread, never the shared IO pool.
 * - **A delay at most**: past it every stream the reading opened is CLOSED — a coroutine cannot stop a
 *   blocked `read`, closing its descriptor does —, and the reading is given up.
 * - **Whatever the provider throws** ends the reading, not the app.
 *
 * Taken out of the sealed note's reader (3.4.0) when the share receiver needed the same (3.5.0): one
 * mechanism, two readers, each with its own thread and its own "one at a time".
 */
internal class LecteurDeFournisseur(nomDuFil: String) {

    private val enCours = AtomicBoolean(false)

    private val fil = Executors.newSingleThreadExecutor { tache ->
        Thread(tache, nomDuFil).apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /**
     * Runs [lecture] on this reader's thread, for [delaiMs] at most. [lecture] passes every stream it opens
     * through the function it is given, so that the delay can close it.
     *
     * @return what [lecture] returns; [refus] when another reading is running, when the delay runs out, or
     *   when the provider threw — the reasons are logged, never shown: they come from another app.
     */
    suspend fun <T> lire(delaiMs: Long, refus: T, lecture: (suivre: (InputStream) -> InputStream) -> T): T {
        if (!enCours.compareAndSet(false, true)) return refus
        val ouverts = ConcurrentLinkedQueue<InputStream>()
        val suivre = { flux: InputStream -> flux.also(ouverts::add) }
        // Not a child of the caller: a reading stuck in a provider must not keep the caller waiting past the delay.
        val tache = CoroutineScope(SupervisorJob() + fil).async {
            try {
                lecture(suivre)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Timber.w(e, "fournisseur illisible")
                refus
            } catch (e: SecurityException) {
                Timber.w(e, "fournisseur : lecture refusee")
                refus
            } catch (e: RuntimeException) {
                Timber.w(e, "fournisseur en erreur")
                refus
            } finally {
                enCours.set(false)
            }
        }
        return withTimeoutOrNull(delaiMs) { tache.await() } ?: run {
            ouverts.forEach { flux ->
                try {
                    flux.close()
                } catch (e: IOException) {
                    Timber.w(e, "flux d'un fournisseur non ferme")
                }
            }
            tache.cancel()
            enCours.set(false)
            refus
        }
    }
}

/**
 * At most [maxOctets] + 1 bytes of [flux], a stream another app serves: one more than allowed is enough to
 * know it is over. The share receiver's reading of each file (3.2.0).
 *
 * 🔴 `null` for a stream that gives NOTHING without ending (C-1, 3.5.0): `read` answering 0 again and again
 * left the count where it was, and the loop spun a core for good on a provider built for it. The sealed
 * note's reader refused that case from the start (`lireBorne` there).
 */
internal fun lireAuPlus(flux: InputStream, maxOctets: Int): ByteArray? {
    val sortie = ByteArrayOutputStream()
    val tampon = ByteArray(TAMPON)
    var lus = 0
    while (lus <= maxOctets) {
        val n = flux.read(tampon, 0, minOf(tampon.size, maxOctets + 1 - lus))
        if (n < 0) break
        if (n == 0) return null
        sortie.write(tampon, 0, n)
        lus += n
    }
    return sortie.toByteArray()
}

private const val TAMPON = 64 * 1024
