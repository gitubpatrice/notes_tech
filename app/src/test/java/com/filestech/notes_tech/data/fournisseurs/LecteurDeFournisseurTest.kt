package com.filestech.notes_tech.data.fournisseurs

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
     * A provider that never answers: `read` blocks until the stream is closed, then fails — what closing
     * the descriptor of a real provider's pipe does. (`PipedInputStream` would not do: it does not look at
     * its own closing while it waits for a writer that never came.)
     */
    private class FluxMuet : InputStream() {
        private val ferme = CountDownLatch(1)

        override fun read(): Int {
            ferme.await()
            throw IOException("ferme")
        }

        override fun close() = ferme.countDown()
    }

    private companion object {
        const val DELAI_LONG_MS = 5_000L
        const val DELAI_COURT_MS = 200L
        const val LIBERATION_MS = 3_000L
    }
}
