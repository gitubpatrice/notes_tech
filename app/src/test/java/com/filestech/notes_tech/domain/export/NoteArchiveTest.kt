package com.filestech.notes_tech.domain.export

import com.filestech.notes_tech.domain.model.EncryptedBody
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipInputStream

/**
 * Ce que l'archive doit contenir — et surtout, ce qu'elle ne doit **pas** perdre en route.
 *
 * Les défauts visés ici ont tous la même forme : l'archive est valide, l'export s'annonce réussi, et
 * la perte se produit chez le destinataire, à la décompression. Personne ne la voit du côté qui
 * l'a causée.
 */
class NoteArchiveTest {

    private val instant = Instant.ofEpochMilli(1_700_000_000_000L)
    private val paris = ZoneId.of("Europe/Paris")

    private var compteur = 0

    private fun note(title: String, folderId: String = "d1", locked: Boolean = false): Note {
        compteur++
        return Note(
            id = "0000000$compteur-2222-3333-4444-555555555555",
            title = title,
            content = "corps de $title",
            folderId = folderId,
            tags = emptyList(),
            pinned = false,
            favorite = false,
            archived = false,
            trashedAt = null,
            createdAt = instant,
            updatedAt = instant,
            encrypted = if (locked) EncryptedBody(ByteArray(32)) else null,
            encVersion = if (locked) 2 else 0,
        )
    }

    private fun dossier(id: String, name: String) = Folder(
        id = id,
        name = name,
        parentId = null,
        color = null,
        icon = null,
        createdAt = instant,
        updatedAt = instant,
        vault = null,
    )

    private fun archive(
        notes: List<Note>,
        dossiers: List<Folder> = listOf(dossier("d1", "Dossier")),
        ouverts: Set<String> = emptySet(),
    ): Pair<NoteArchive.Summary, Map<String, String>> {
        val sortie = ByteArrayOutputStream()
        val bilan = NoteArchive.write(
            output = sortie,
            notes = notes,
            foldersById = dossiers.associateBy { it.id },
            unlockedVaultFolderIds = ouverts,
            inboxLabel = "Inbox",
            vaultMention = { "Note du coffre : $it" },
            exportedAt = instant,
            zone = paris,
        )
        val entrees = mutableMapOf<String, String>()
        ZipInputStream(sortie.toByteArray().inputStream()).use { zip ->
            var entree = zip.nextEntry
            while (entree != null) {
                entrees[entree.name] = zip.readBytes().toString(Charsets.UTF_8)
                entree = zip.nextEntry
            }
        }
        return bilan to entrees
    }

    @Test
    @DisplayName("chaque note est un fichier dans le sous-dossier de son dossier")
    fun arborescence() {
        val (bilan, entrees) = archive(listOf(note("Une note")))

        assertThat(entrees.keys).containsExactly("Dossier/Une note.md", "README.md")
        assertThat(bilan.exported).isEqualTo(1)
        assertThat(entrees.getValue("Dossier/Une note.md")).contains("folder: \"Dossier\"")
    }

    /**
     * 🔴 L'extraction sur FAT32 comme sous Windows **ne distingue pas la casse**. Sans la clé de
     * comptage en minuscules, ces deux entrées seraient distinctes dans l'archive et une seule
     * survivrait à la décompression — sans un mot.
     */
    @Test
    @DisplayName("deux titres qui ne diffèrent que par la casse ne s'écrasent pas chez le destinataire")
    fun collisionDeCasse() {
        val (_, entrees) = archive(listOf(note("Reiki"), note("reiki")))

        assertThat(entrees.keys).containsExactly("Dossier/Reiki.md", "Dossier/reiki-2.md", "README.md")
    }

    /**
     * 🔴 Le cas qu'on n'imagine pas et qu'un utilisateur qui numérote ses titres rencontre au
     * premier export : le suffixe de désambiguïsation est **lui-même déjà pris**.
     */
    @Test
    @DisplayName("un titre qui porte déjà le suffixe de désambiguïsation ne provoque pas de doublon")
    fun suffixeDejaPris() {
        val (_, entrees) = archive(listOf(note("Note"), note("Note-2"), note("Note")))

        assertThat(entrees.keys)
            .containsExactly("Dossier/Note.md", "Dossier/Note-2.md", "Dossier/Note-3.md", "README.md")
    }

    /**
     * ⚠️ Une note scellée n'est pas exportée, et le compteur le dit. Écrire son blob dans un `.md`
     * produirait un fichier illisible que l'utilisateur croirait être sa note.
     */
    @Test
    @DisplayName("une note de coffre fermé est omise ET comptée")
    fun coffreFerme() {
        val (bilan, entrees) = archive(listOf(note("Visible"), note("Scellée", locked = true)))

        assertThat(entrees.keys).containsExactly("Dossier/Visible.md", "README.md")
        assertThat(bilan.exported).isEqualTo(1)
        assertThat(bilan.skippedLocked).isEqualTo(1)
    }

    @Test
    @DisplayName("une note venue d'un coffre ouvert porte la mention dans son nom et son frontmatter")
    fun coffreOuvert() {
        val (bilan, entrees) = archive(
            notes = listOf(note("Secret")),
            dossiers = listOf(dossier("d1", "Perso")),
            ouverts = setOf("d1"),
        )

        assertThat(entrees.keys).contains("Perso/Secret [unlocked].md")
        assertThat(entrees.getValue("Perso/Secret [unlocked].md")).contains("# Note du coffre : Perso")
        assertThat(bilan.skippedLocked).isEqualTo(0)
    }

    /**
     * ⚠️ Le compte du fichier d'accueil est celui des notes **écrites**, pas celui des notes
     * fournies. Un README annonçant les notes omises transformerait un export partiel en export
     * qui se croit complet.
     */
    @Test
    @DisplayName("le fichier d'accueil compte ce qui est dans l'archive, pas ce qu'on lui a donné")
    fun accueilCompteLeReel() {
        val (_, entrees) = archive(
            listOf(note("A"), note("Scellée", locked = true), note("B")),
        )

        // English since 2026-09-24, word for word notes_tech 2.0.9's README.
        assertThat(entrees.getValue("README.md")).contains("- Notes: 2")
        assertThat(entrees.getValue("README.md")).contains("- Exported: 2023-11-14T23:13:20.000")
    }

    @Test
    @DisplayName("une note dont le dossier a disparu retombe dans un dossier neutre")
    fun dossierIntrouvable() {
        val (_, entrees) = archive(notes = listOf(note("Orpheline", folderId = "inconnu")), dossiers = emptyList())

        assertThat(entrees.keys).contains("inconnu/Orpheline.md")
    }

    @Test
    @DisplayName("la boîte de réception garde son nom technique comme dossier, son libellé dans le texte")
    fun boiteDeReception() {
        val (_, entrees) = archive(
            notes = listOf(note("Brouillon", folderId = Folder.INBOX_ID)),
            dossiers = emptyList(),
        )

        assertThat(entrees.keys).contains("inbox/Brouillon.md")
        assertThat(entrees.getValue("inbox/Brouillon.md")).contains("folder: \"Inbox\"")
    }
}
