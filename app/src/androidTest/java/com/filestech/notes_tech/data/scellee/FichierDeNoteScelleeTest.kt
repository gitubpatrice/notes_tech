package com.filestech.notes_tech.data.scellee

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.data.scellee.FichierDeNoteScellee.NoteScelleeRefuseeException
import com.filestech.notes_tech.data.scellee.FichierDeNoteScellee.Raison
import com.filestech.notes_tech.security.sauvegarde.FichierDeSauvegarde
import com.filestech.notes_tech.security.sauvegarde.SecretDeSauvegarde
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * D-044 (3.4.0) — **a sealed note opens with its code, and only with it**; anything else is refused, said
 * by its reason. On the phone: the JSON is Android's, the derivation the vaults' Argon2id.
 */
@RunWith(AndroidJUnit4::class)
class FichierDeNoteScelleeTest {

    private val note = NoteScellee(
        titre = "Code de l'immeuble",
        contenu = "B 4521 — « ne pas oublier » 🔑\nseconde ligne",
        couleur = 3,
        etiquettes = listOf("maison", "accès"),
        lectureUnique = true,
    )
    private val code = SecretDeSauvegarde.nouveauCode()

    @Test
    fun the_note_comes_back_whole_with_its_code_however_it_is_typed() {
        val fichier = FichierDeNoteScellee.sceller(note, code)

        // Typed lowercase, without its dashes, with spaces: the backup's tolerant reading.
        val tape = code.lowercase().replace("-", " ")
        assertThat(FichierDeNoteScellee.ouvrir(fichier, tape)).isEqualTo(note)
        assertThat(FichierDeNoteScellee.aLaMagique(fichier)).isTrue()
        // Nothing of the note in clear in the file.
        assertThat(String(fichier, Charsets.ISO_8859_1)).doesNotContain("4521")
    }

    @Test
    fun control_another_code_is_refused_as_a_wrong_code_or_damaged_file() {
        val fichier = FichierDeNoteScellee.sceller(note.copy(couleur = null, etiquettes = emptyList()), code)

        assertThat(refus { FichierDeNoteScellee.ouvrir(fichier, SecretDeSauvegarde.nouveauCode()) })
            .isEqualTo(Raison.CODE_OU_FICHIER)
    }

    @Test
    fun a_damaged_file_is_refused_like_a_wrong_code() {
        val fichier = FichierDeNoteScellee.sceller(note, code)
        fichier[fichier.size - 20] = (fichier[fichier.size - 20].toInt() xor 1).toByte()

        assertThat(refus { FichierDeNoteScellee.ouvrir(fichier, code) }).isEqualTo(Raison.CODE_OU_FICHIER)
    }

    /** Refused before any derivation: a text that is not a code. */
    @Test
    fun what_is_not_a_code_is_refused_before_anything_is_computed() {
        val fichier = FichierDeNoteScellee.sceller(note, code)

        assertThat(refus { FichierDeNoteScellee.ouvrir(fichier, "mot de passe") }).isEqualTo(Raison.CODE_MAL_FORME)
    }

    @Test
    fun a_backup_or_any_other_file_is_not_a_sealed_note() {
        val sauvegarde = ByteArrayOutputStream().also { sortie ->
            FichierDeSauvegarde.ecrire(sortie, ByteArray(32), FichierDeSauvegarde.nouvelEnTete()).close()
        }.toByteArray()
        val autre = "%PDF-1.7 un document quelconque".toByteArray()

        assertThat(FichierDeNoteScellee.aLaMagique(sauvegarde)).isFalse()
        assertThat(FichierDeNoteScellee.aLaMagique(autre)).isFalse()
        assertThat(refus { FichierDeNoteScellee.ouvrir(sauvegarde, code) }).isEqualTo(Raison.PAS_UNE_NOTE_SCELLEE)
        assertThat(refus { FichierDeNoteScellee.ouvrir(autre, code) }).isEqualTo(Raison.PAS_UNE_NOTE_SCELLEE)
    }

    /** A file made by hand: tags and title come in as the app would have written them (3-axes audit). */
    @Test
    fun a_hand_made_file_gives_clean_tags_and_a_one_line_title() {
        val fabriquee = note.copy(
            titre = " Fac\u202Eture\nsuite\t",
            etiquettes = listOf("a,b", " a ", "", "c\n", "b"),
        )

        val lue = FichierDeNoteScellee.ouvrir(FichierDeNoteScellee.sceller(fabriquee, code), code)

        assertThat(lue.titre).isEqualTo("Facture suite")
        assertThat(lue.etiquettes).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun a_file_past_the_bound_is_refused_unread() {
        val enorme = FichierDeNoteScellee.MAGIQUE + ByteArray(FichierDeNoteScellee.TAILLE_MAX.toInt())

        assertThat(refus { FichierDeNoteScellee.ouvrir(enorme, code) }).isEqualTo(Raison.TROP_GROSSE)
    }

    private fun refus(ouverture: () -> Unit): Raison =
        assertThrows(NoteScelleeRefuseeException::class.java) { ouverture() }.raison
}
