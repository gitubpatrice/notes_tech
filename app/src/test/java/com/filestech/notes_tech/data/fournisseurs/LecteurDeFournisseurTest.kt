package com.filestech.notes_tech.data.fournisseurs

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * C-1 (3.5.0): what another app's provider serves is read on a thread of its own, one reading at a time,
 * for a delay at most — past it the stream is CLOSED, which is what frees a blocked read.
 */
@DisplayName("Lecture d'un fournisseur d'une autre appli")
class LecteurDeFournisseurTest {

    @Test
    fun `a reading gives what it read`(): Unit = runBlocking {
        val lecteur = LecteurDeFournisseur("test-lu")
        val lu = lecteur.lire(DELAI_LONG_MS, refus = "refus") { suivre ->
            suivre(ByteArrayInputStream("note".toByteArray())).use { it.readBytes().decodeToString() }
        }
        assertThat(lu).isEqualTo("note")
    }

    @Test
    fun `a second reading while one runs is refused, not queued`(): Unit = runBlocking {
        val lecteur = LecteurDeFournisseur("test-un-a-la-fois")
        val commencee = CountDownLatch(1)
        val liberee = CountDownLatch(1)
        // On another thread: this test's own thread blocks on the latch just below.
        val premiere = async(Dispatchers.Default) {
            lecteur.lire(DELAI_LONG_MS, refus = "refus") {
                commencee.countDown()
                liberee.await(DELAI_LONG_MS, TimeUnit.MILLISECONDS)
                "premiere"
            }
        }
        assertThat(commencee.await(DELAI_LONG_MS, TimeUnit.MILLISECONDS)).isTrue()

        assertThat(lecteur.lire(DELAI_LONG_MS, refus = "refus") { "seconde" }).isEqualTo("refus")

        liberee.countDown()
        assertThat(premiere.await()).isEqualTo("premiere")
        // And once it is over, the next one runs.
        assertThat(lecteur.lire(DELAI_LONG_MS, refus = "refus") { "suivante" }).isEqualTo("suivante")
    }

    /**
     * 🔴 The case the delay exists for: a provider that never writes. The reading gives up in time, and the
     * read blocked in it ENDS — the stream was closed under it — so the reader's thread is free again.
     */
    @Test
    fun `a stream that never answers is given up and closed under the blocked read`(): Unit = runBlocking {
        val lecteur = LecteurDeFournisseur("test-bloque")
        val readTermine = CountDownLatch(1)

        val lu = lecteur.lire(DELAI_COURT_MS, refus = "refus") { suivre ->
            try {
                suivre(FluxMuet()).read()
                "lu"
            } finally {
                readTermine.countDown()
            }
        }

        assertThat(lu).isEqualTo("refus")
        assertThat(readTermine.await(LIBERATION_MS, TimeUnit.MILLISECONDS)).isTrue()
        // The thread is free: the next reading runs and answers.
        assertThat(lecteur.lire(DELAI_LONG_MS, refus = "refus") { "suivante" }).isEqualTo("suivante")
    }

    @Test
    fun `whatever the provider throws ends the reading, not the app`(): Unit = runBlocking {
        val lecteur = LecteurDeFournisseur("test-erreurs")
        listOf(IOException("abime"), SecurityException("refuse"), IllegalStateException("bogue")).forEach { erreur ->
            assertThat(lecteur.lire(DELAI_LONG_MS, refus = "refus") { throw erreur }).isEqualTo("refus")
        }
    }

    @Test
    fun `at most one byte more than allowed is read`() {
        assertThat(lireAuPlus(ByteArrayInputStream(ByteArray(10)), maxOctets = 4)).hasLength(5)
        assertThat(lireAuPlus(ByteArrayInputStream(ByteArray(3)), maxOctets = 4)).hasLength(3)
    }

    /** The loop that spun a core: a stream answering 0 bytes for ever, without ending. */
    @Test
    fun `a stream that gives nothing without ending is refused`() {
        val rienSansFin = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        assertThat(lireAuPlus(rienSansFin, maxOctets = 4)).isNull()
    }

    /**
     * GPT-5.6 review and 3-axes audit of 3.5.0: the share screen gone (its scope cancelled) while a provider
     * blocks. Until then nothing was given up — the stream stayed open, the slot taken for good, and every
     * later share refused.
     */
    @Test
    fun `a cancelled caller gives the reading up, its stream closed and its slot free`(): Unit = runBlocking {
        val lecteur = LecteurDeFournisseur("test-annule")
        val flux = FluxMuet()
        val ouvert = CountDownLatch(1)
        val appelant = launch(Dispatchers.Default) {
            lecteur.lire(DELAI_LONG_MS, refus = "refus") { suivre ->
                suivre(flux).also { ouvert.countDown() }.read()
                "lu"
            }
        }
        assertThat(ouvert.await(DELAI_LONG_MS, TimeUnit.MILLISECONDS)).isTrue()

        appelant.cancelAndJoin()

        assertThat(flux.fermeLe.await(LIBERATION_MS, TimeUnit.MILLISECONDS)).isTrue()
        assertThat(lecteur.lire(DELAI_LONG_MS, refus = "refus") { "suivante" }).isEqualTo("suivante")
    }

    /** A provider slow to OPEN: the stream it hands over after the delay is closed at once, never read. */
    @Test
    fun `a stream opened after the reading was given up is closed at once`(): Unit = runBlocking {
        val lecteur = LecteurDeFournisseur("test-tardif")
        val delaiPasse = CountDownLatch(1)
        val tardif = FluxMuet()
        val issue = CountDownLatch(1)
        var refuse = false

        val lu = lecteur.lire(DELAI_COURT_MS, refus = "refus") { suivre ->
            delaiPasse.await(DELAI_LONG_MS, TimeUnit.MILLISECONDS)
            try {
                suivre(tardif)
                "lu"
            } catch (e: IOException) {
                refuse = true
                throw e
            } finally {
                issue.countDown()
            }
        }
        assertThat(lu).isEqualTo("refus")
        delaiPasse.countDown()

        assertThat(issue.await(LIBERATION_MS, TimeUnit.MILLISECONDS)).isTrue()
        assertThat(refuse).isTrue()
        assertThat(tardif.fermeLe.count).isEqualTo(0)
    }

    /** A reading given up and finishing late must not free the slot of the reading running now. */
    @Test
    fun `a reading given up cannot free the slot of a newer one`(): Unit = runBlocking {
        val lecteur = LecteurDeFournisseur("test-jeton")
        val ancienneLibre = CountDownLatch(1)
        val ancienneFinie = CountDownLatch(1)
        assertThat(
            lecteur.lire(DELAI_COURT_MS, refus = "refus") {
                ancienneLibre.await(DELAI_LONG_MS, TimeUnit.MILLISECONDS)
                ancienneFinie.countDown()
                "ancienne"
            },
        ).isEqualTo("refus")
        val nouvelleCommencee = CountDownLatch(1)
        val nouvelleLibre = CountDownLatch(1)
        val nouvelle = async(Dispatchers.Default) {
            lecteur.lire(DELAI_LONG_MS, refus = "refus") {
                nouvelleCommencee.countDown()
                nouvelleLibre.await(DELAI_LONG_MS, TimeUnit.MILLISECONDS)
                "nouvelle"
            }
        }
        assertThat(nouvelleCommencee.await(DELAI_LONG_MS, TimeUnit.MILLISECONDS)).isTrue()

        ancienneLibre.countDown()
        assertThat(ancienneFinie.await(LIBERATION_MS, TimeUnit.MILLISECONDS)).isTrue()
        // The old reading is over; the newer one still holds the slot.
        assertThat(lecteur.lire(DELAI_LONG_MS, refus = "refus") { "troisieme" }).isEqualTo("refus")

        nouvelleLibre.countDown()
        assertThat(nouvelle.await()).isEqualTo("nouvelle")
    }

    /**
     * A provider stuck where no stream can be closed keeps its thread: the next reading runs on another one,
     * up to a bound — past it, readings are refused — and the bound frees itself when the stuck ones return.
     */
    @Test
    fun `a stuck thread is replaced, a bounded number of times`(): Unit = runBlocking {
        val lecteur = LecteurDeFournisseur("test-coince")
        val coinces = CountDownLatch(1)
        repeat(FILS_BLOQUES_MAX) {
            val lu = lecteur.lire(DELAI_COURT_MS, refus = "refus") {
                coinces.await()
                "coince"
            }
            assertThat(lu).isEqualTo("refus")
        }
        assertThat(lecteur.lire(DELAI_LONG_MS, refus = "refus") { "au-dela" }).isEqualTo("refus")

        coinces.countDown()
        withTimeout(LIBERATION_MS) {
            while (lecteur.lire(DELAI_LONG_MS, refus = "refus") { "suivante" } != "suivante") delay(SONDE_MS)
        }
    }

    /**
     * A provider that never answers: `read` blocks until the stream is closed, then fails — what closing
     * the descriptor of a real provider's pipe does. (`PipedInputStream` would not do: it does not look at
     * its own closing while it waits for a writer that never came.)
     */
    private class FluxMuet : InputStream() {
        val fermeLe = CountDownLatch(1)

        override fun read(): Int {
            fermeLe.await()
            throw IOException("ferme")
        }

        override fun close() = fermeLe.countDown()
    }

    private companion object {
        const val DELAI_LONG_MS = 5_000L
        const val DELAI_COURT_MS = 200L
        const val LIBERATION_MS = 3_000L
        const val SONDE_MS = 50L

        /** `LecteurDeFournisseur.FILS_BLOQUES_MAX`, private there. */
        const val FILS_BLOQUES_MAX = 4
    }
}
