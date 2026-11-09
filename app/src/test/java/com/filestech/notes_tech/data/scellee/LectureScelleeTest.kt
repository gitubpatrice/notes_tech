package com.filestech.notes_tech.data.scellee

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * D-044 (3.4.0) — reading a file "opened with" Notes Tech: bounded, and a file that is no sealed note is
 * dropped at its first bytes — the filter takes any `application/octet-stream`, a video included.
 */
@DisplayName("Lecture d'une note scellée")
class LectureScelleeTest {

    @Test
    fun `a sealed note is read whole`() {
        val fichier = FichierDeNoteScellee.MAGIQUE + ByteArray(100_000) { it.toByte() }

        val lue = lireBorne(ByteArrayInputStream(fichier))

        assertThat(lue).isInstanceOf(LectureScellee.Lue::class.java)
        assertThat((lue as LectureScellee.Lue).octets).isEqualTo(fichier)
    }

    @Test
    fun `another file is dropped at its first bytes, not read to its end`() {
        val flux = FluxCompte("%PDF-1.7".toByteArray() + ByteArray(10_000_000))

        assertThat(lireBorne(flux)).isEqualTo(LectureScellee.PasUneNoteScellee)
        // One buffer at most: the rest of a large file is never read.
        assertThat(flux.lus).isAtMost(64 * 1024)
    }

    @Test
    fun `a file past the bound is refused`() {
        val fichier = FichierDeNoteScellee.MAGIQUE + ByteArray(FichierDeNoteScellee.TAILLE_MAX.toInt())

        assertThat(lireBorne(ByteArrayInputStream(fichier))).isEqualTo(LectureScellee.TropGrosse)
    }

    @Test
    fun `an empty or too short file is no sealed note`() {
        assertThat(lireBorne(ByteArrayInputStream(ByteArray(0)))).isEqualTo(LectureScellee.PasUneNoteScellee)
        assertThat(lireBorne(ByteArrayInputStream("NTSE".toByteArray()))).isEqualTo(LectureScellee.PasUneNoteScellee)
    }

    /** Counts what is really read. */
    private class FluxCompte(octets: ByteArray) : InputStream() {
        private val entree = ByteArrayInputStream(octets)
        var lus = 0
            private set

        override fun read(): Int = entree.read().also { if (it >= 0) lus++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = entree.read(b, off, len)
            if (n > 0) lus += n
            return n
        }
    }
}
