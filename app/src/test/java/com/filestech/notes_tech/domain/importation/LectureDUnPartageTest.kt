package com.filestech.notes_tech.domain.importation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId

/** "Share → Notes Tech": what becomes a note, what is counted, and the bounds. */
class LectureDUnPartageTest {

    private val zone = ZoneId.of("Europe/Paris")

    @Test
    fun `a shared text becomes one note titled by its subject, else its first line`() {
        assertThat(LectureDUnPartage.lireUnTexte("le corps", "Le sujet").notes.single().titre).isEqualTo("Le sujet")
        val sansSujet = LectureDUnPartage.lireUnTexte("Liste de courses\npain\nlait", null).notes.single()
        assertThat(sansSujet.titre).isEqualTo("Liste de courses")
        assertThat(sansSujet.contenu).isEqualTo("Liste de courses\npain\nlait")
    }

    @Test
    fun `an empty text, or one over the bound, is counted and never cut`() {
        assertThat(LectureDUnPartage.lireUnTexte("  ", null)).isEqualTo(PartageRecu(emptyList(), 1))
        val tropLong = "a".repeat(LectureDUnPartage.TEXTE_MAX + 1)
        assertThat(LectureDUnPartage.lireUnTexte(tropLong, null)).isEqualTo(PartageRecu(emptyList(), 1))
        val pile = "a".repeat(LectureDUnPartage.TEXTE_MAX)
        assertThat(LectureDUnPartage.lireUnTexte(pile, null).notes.single().contenu).hasLength(pile.length)
    }

    @Test
    fun `a file is read like an imported file`() {
        val recu =
            lire(FichierPartage("Recette.md", "---\ntitle: \"Tarte\"\ntags: [cuisine]\n---\n\nPâte".toByteArray()))
        val note = recu.notes.single()
        assertThat(note.titre).isEqualTo("Tarte")
        assertThat(note.etiquettes).containsExactly("cuisine")
        assertThat(note.contenu).isEqualTo("Pâte")
        assertThat(recu.ignores).isEqualTo(0)
    }

    @Test
    fun `a file that is not text, unreadable, or over the bound is counted, the others kept`() {
        val recu = lire(
            FichierPartage("photo.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())),
            FichierPartage("data.bin", "abc\u0000def".toByteArray()),
            FichierPartage("ailleurs.txt", null),
            FichierPartage("gros.txt", ByteArray(LectureDUnPartage.FICHIER_MAX + 1) { 'a'.code.toByte() }),
            FichierPartage("Idée.txt", "une idée".toByteArray()),
        )
        assertThat(recu.notes.map { it.titre }).containsExactly("Idée")
        assertThat(recu.ignores).isEqualTo(4)
    }

    @Test
    fun `at most FICHIERS_MAX files are read, the rest counted and never opened`() {
        val ouverts = mutableListOf<Int>()
        val nombre = LectureDUnPartage.FICHIERS_MAX + 30
        val recu = LectureDUnPartage.lireDesFichiers(nombre, zone) { index, _ ->
            ouverts += index
            FichierPartage("n$index.txt", "t$index".toByteArray())
        }
        assertThat(recu.notes).hasSize(LectureDUnPartage.FICHIERS_MAX)
        assertThat(recu.ignores).isEqualTo(30)
        assertThat(ouverts).hasSize(LectureDUnPartage.FICHIERS_MAX)
    }

    /** M2 of the 3.2.0 review: full files spend the budget; the ones after are not even opened. */
    @Test
    fun `once the budget is spent, nothing more is read`() {
        val pleins = LectureDUnPartage.BUDGET / LectureDUnPartage.FICHIER_MAX
        val reste = LectureDUnPartage.BUDGET - pleins * LectureDUnPartage.FICHIER_MAX
        val ouverts = mutableListOf<Int>()
        val bornes = mutableListOf<Int>()
        val recu = LectureDUnPartage.lireDesFichiers(pleins + 3, zone) { index, maxOctets ->
            ouverts += index
            bornes += maxOctets
            // A reader that honours the bound: the file is as large as it is allowed to be.
            FichierPartage("plein$index.txt", texteDe(maxOctets))
        }
        // Full files while they fit, then one of what is left, then nothing.
        assertThat(reste).isGreaterThan(0)
        assertThat(bornes).isEqualTo(List(pleins) { LectureDUnPartage.FICHIER_MAX } + reste)
        assertThat(ouverts).isEqualTo((0..pleins).toList())
        assertThat(recu.notes).hasSize(pleins + 1)
        assertThat(recu.ignores).isEqualTo(2)
    }

    /**
     * 🔴 A note brought in is at most [LongueurDUneNote.CARACTERES_MAX] characters (2026-10-30): a file
     * within the bytes read but one character longer is counted, not kept.
     */
    @Test
    fun `a file one character longer than a note is counted, one at the bound kept`() {
        val recu = lire(
            FichierPartage("long.txt", "a".repeat(LongueurDUneNote.CARACTERES_MAX + 1).toByteArray()),
            FichierPartage("juste.txt", "a".repeat(LongueurDUneNote.CARACTERES_MAX).toByteArray()),
        )
        assertThat(recu.notes.map { it.titre }).containsExactly("juste")
        assertThat(recu.ignores).isEqualTo(1)
    }

    /** [octets] bytes of valid UTF-8 in characters of 3 bytes each: as many bytes, a third as many characters. */
    private fun texteDe(octets: Int) = ("€".repeat(octets / 3) + "a".repeat(octets % 3)).toByteArray()

    private fun lire(vararg fichiers: FichierPartage) =
        LectureDUnPartage.lireDesFichiers(fichiers.size, zone) { index, _ -> fichiers[index] }
}
