package com.filestech.notes_tech.data.fournisseurs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
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
import java.util.concurrent.atomic.AtomicReference

/**
 * Reading what ANOTHER app's content provider serves — a sealed note opened with the app (D-044, 3.4.0), a
 * share (3.2.0, hardened in 3.5.0, C-1). A provider is untrusted code (external review of 3.4.0): it may
 * never answer, answer forever, or throw anything.
 *
 * - **One reading at a time** per reader: another one is refused, not queued — a burst of "Open with" or
 *   of shares cannot pile up buffers. The slot is held by a token: a reading given up and finishing late
 *   cannot free the slot of a newer one.
 * - **On a thread of its own**: a provider that blocks holds that thread, never the shared IO pool.
 * - **A delay at most**: past it — or when the caller is cancelled, its screen gone — the reading is given
 *   up: every stream it opened is CLOSED (a coroutine cannot stop a blocked `read`, closing its descriptor
 *   does), and a stream it opens AFTER that is closed at once.
 * - **A stuck thread is replaced**: a provider blocking where no stream can be closed (opening the stream,
 *   a query) would otherwise keep every later reading waiting behind it. At most [FILS_BLOQUES_MAX] stuck
 *   at a time, then readings are refused: a hostile app sharing again and again must not make the app
 *   spawn threads without end.
 * - **Whatever the provider throws** ends the reading, not the app.
 *
 * Taken out of the sealed note's reader (3.4.0) when the share receiver needed the same (3.5.0): one
 * mechanism, two readers, each with its own thread and its own "one at a time". The cancellation, the late
 * stream, the stale token and the stuck thread were found by the 3.5.0 reviews (GPT-5.6, 3-axes audit).
 */
internal class LecteurDeFournisseur(private val nomDuFil: String) {

    /** The token of the reading running, `null` when none. */
    private val enCours = AtomicReference<Any?>(null)

    private var fil: ExecutorCoroutineDispatcher = nouveauFil()
    private var filsBloques = 0

    /**
     * Runs [lecture] on this reader's thread, for [delaiMs] at most. [lecture] passes every stream it opens
     * through the function it is given, so that giving up can close it.
     *
     * @return what [lecture] returns; [refus] when another reading is running, when the delay runs out, or
     *   when the provider threw — the reasons are logged, never shown: they come from another app.
     * @throws CancellationException when the caller is cancelled — after the reading is given up.
     */
    suspend fun <T> lire(delaiMs: Long, refus: T, lecture: (suivre: (InputStream) -> InputStream) -> T): T {
        val jeton = Any()
        if (!enCours.compareAndSet(null, jeton)) return refus
        val dispatcher = filDisponible() ?: return refus.also { enCours.compareAndSet(jeton, null) }
        val lectureEnCours = LectureEnCours()
        // Not a child of the caller: a reading stuck in a provider must not keep the caller waiting past the delay.
        val tache = CoroutineScope(SupervisorJob() + dispatcher).async {
            try {
                lecture(lectureEnCours::suivre)
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
                enCours.compareAndSet(jeton, null)
            }
        }
        val lu = try {
            withTimeoutOrNull(delaiMs) { tache.await() }
        } catch (e: CancellationException) {
            abandonner(lectureEnCours, tache, jeton)
            throw e
        }
        return lu ?: refus.also { abandonner(lectureEnCours, tache, jeton) }
    }

    /** Gives a reading up: its streams closed, its task cancelled, its slot freed, its thread replaced if stuck. */
    private fun abandonner(lecture: LectureEnCours, tache: Deferred<*>, jeton: Any) {
        lecture.abandonner()
        tache.cancel()
        // `isCompleted`, not `isActive`: a cancelled task is no longer "active" while its body still runs.
        // Not completed yet: its thread may be stuck in the provider, and the next reading would wait behind it.
        if (!tache.isCompleted) remplacerLeFil(tache)
        enCours.compareAndSet(jeton, null)
    }

    /** `null` while too many threads are stuck in providers: the reading is then refused. */
    @Synchronized
    private fun filDisponible(): ExecutorCoroutineDispatcher? = fil.takeIf { filsBloques < FILS_BLOQUES_MAX }

    /**
     * A new thread for the next readings; the old one is shut down, and ends as soon as the reading stuck on
     * it returns — if it ever does. Counted until then: [FILS_BLOQUES_MAX] at a time at most.
     */
    @Synchronized
    private fun remplacerLeFil(tache: Deferred<*>) {
        val ancien = fil
        fil = nouveauFil()
        ancien.close()
        filsBloques++
        Timber.w("fournisseur bloque : fil %s remplace (%d bloques)", nomDuFil, filsBloques)
        tache.invokeOnCompletion { liberer() }
    }

    @Synchronized
    private fun liberer() {
        filsBloques--
    }

    private fun nouveauFil(): ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { tache ->
        Thread(tache, nomDuFil).apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /**
     * The streams of one reading, and whether it was given up. No window between the two: a stream is
     * recorded THEN the flag read, the flag set THEN the streams closed — whichever comes second closes it.
     */
    private class LectureEnCours {
        private val ouverts = ConcurrentLinkedQueue<InputStream>()
        private val abandonnee = AtomicBoolean(false)

        fun suivre(flux: InputStream): InputStream {
            ouverts.add(flux)
            if (abandonnee.get()) {
                fermer(flux)
                throw IOException("lecture abandonnee")
            }
            return flux
        }

        fun abandonner() {
            abandonnee.set(true)
            ouverts.forEach(::fermer)
        }

        private fun fermer(flux: InputStream) {
            try {
                flux.close()
            } catch (e: IOException) {
                Timber.w(e, "flux d'un fournisseur non ferme")
            }
        }
    }

    private companion object {
        /** Threads stuck in providers at the same time, per reader; past it, readings are refused. */
        const val FILS_BLOQUES_MAX = 4
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
