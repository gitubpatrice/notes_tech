package com.filestech.notes_tech.domain.importation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId

/** "Share → Notes Tech": what becomes a note, what is counted, and the bounds. */
class LectureDUnPartageTest {

    private val zone = ZoneId.of("Europe/Paris")

    @Test
    fun `a shared text becomes one note titled by its subject`() {
        val recu = LectureDUnPartage.lire("le corps", "Le sujet", emptyList(), zone)
        assertThat(recu.ignores).isEqualTo(0)
        assertThat(recu.notes.single().titre).isEqualTo("Le sujet")
        assertThat(recu.notes.single().contenu).isEqualTo("le corps")
    }

    @Test
    fun `a text without subject is titled by its first line`() {
        val recu = LectureDUnPartage.lire("Liste de courses\npain\nlait", null, emptyList(), zone)
        assertThat(recu.notes.single().titre).isEqualTo("Liste de courses")
    }

    @Test
    fun `an empty text, or one over the bound, is counted and never cut`() {
        assertThat(LectureDUnPartage.lire("  ", null, emptyList(), zone)).isEqualTo(PartageRecu(emptyList(), 1))
        val tropLong = "a".repeat(LectureDUnPartage.TEXTE_MAX + 1)
        assertThat(LectureDUnPartage.lire(tropLong, null, emptyList(), zone)).isEqualTo(PartageRecu(emptyList(), 1))
        val pile = "a".repeat(LectureDUnPartage.TEXTE_MAX)
        assertThat(LectureDUnPartage.lire(pile, null, emptyList(), zone).notes.single().contenu).hasLength(pile.length)
    }

    @Test
    fun `files win over the text that comes with them, and are read like imported files`() {
        val fichier = FichierPartage("Recette.md", "---\ntitle: \"Tarte\"\ntags: [cuisine]\n---\n\nPâte".toByteArray())
        val recu = LectureDUnPartage.lire("Regarde ce fichier", "Partagé", listOf(fichier), zone)
        val note = recu.notes.single()
        assertThat(note.titre).isEqualTo("Tarte")
        assertThat(note.etiquettes).containsExactly("cuisine")
        assertThat(note.contenu).isEqualTo("Pâte")
        assertThat(recu.ignores).isEqualTo(0)
    }

    @Test
    fun `a file that is not text, unreadable, or over the bound is counted, the others kept`() {
        val image = FichierPartage("photo.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        val binaire = FichierPartage("data.bin", "abc\u0000def".toByteArray())
        val illisible = FichierPartage("ailleurs.txt", null)
        val tropGros = FichierPartage("gros.txt", ByteArray(LectureDUnPartage.FICHIER_MAX + 1) { 'a'.code.toByte() })
        val bon = FichierPartage("Idée.txt", "une idée".toByteArray())

        val recu = LectureDUnPartage.lire(null, null, listOf(image, binaire, illisible, tropGros, bon), zone)

        assertThat(recu.notes.map { it.titre }).containsExactly("Idée")
        assertThat(recu.ignores).isEqualTo(4)
    }

    @Test
    fun `at most FICHIERS_MAX files are read, the rest counted`() {
        val fichiers = (1..LectureDUnPartage.FICHIERS_MAX + 3).map { FichierPartage("n$it.txt", "t$it".toByteArray()) }
        val recu = LectureDUnPartage.lire(null, null, fichiers, zone)
        assertThat(recu.notes).hasSize(LectureDUnPartage.FICHIERS_MAX)
        assertThat(recu.ignores).isEqualTo(3)
    }
}
