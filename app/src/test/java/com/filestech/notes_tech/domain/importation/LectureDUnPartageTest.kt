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

    /** M2 of the 3.2.0 review: four full files spend the budget; the fifth is not even opened. */
    @Test
    fun `once the budget is spent, nothing more is read`() {
        val ouverts = mutableListOf<Int>()
        val bornes = mutableListOf<Int>()
        val recu = LectureDUnPartage.lireDesFichiers(6, zone) { index, maxOctets ->
            ouverts += index
            bornes += maxOctets
            // A reader that honours the bound: the file is as large as it is allowed to be.
            FichierPartage("plein$index.txt", ByteArray(maxOctets) { 'a'.code.toByte() })
        }
        // 16 MiB = three files of 5 MiB, then a fourth of the 1 MiB left.
        assertThat(bornes).containsExactly(
            LectureDUnPartage.FICHIER_MAX,
            LectureDUnPartage.FICHIER_MAX,
            LectureDUnPartage.FICHIER_MAX,
            LectureDUnPartage.BUDGET - 3 * LectureDUnPartage.FICHIER_MAX,
        ).inOrder()
        assertThat(ouverts).containsExactly(0, 1, 2, 3).inOrder()
        assertThat(recu.notes).hasSize(4)
        assertThat(recu.ignores).isEqualTo(2)
    }

    private fun lire(vararg fichiers: FichierPartage) =
        LectureDUnPartage.lireDesFichiers(fichiers.size, zone) { index, _ -> fichiers[index] }
}
