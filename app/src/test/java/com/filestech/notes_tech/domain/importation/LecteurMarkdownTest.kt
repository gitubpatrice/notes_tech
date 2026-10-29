package com.filestech.notes_tech.domain.importation

import com.filestech.notes_tech.domain.export.NoteMarkdown
import com.filestech.notes_tech.domain.model.EncryptedFormat
import com.filestech.notes_tech.domain.model.Note
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The Markdown reader of 3.2.0, shared by the share sheet and the import. Its first duty: a note
 * exported by Notes Tech comes back as it left — through the export's real writer, not a copy of its
 * format.
 */
class LecteurMarkdownTest {

    private val zone = ZoneId.of("Europe/Paris")

    @Test
    fun `a note exported by Notes Tech comes back with its title, tags, dates, pin and favourite`() {
        val note = note(
            titre = "Courses \"samedi\" \\ ok\tet\u0007 fin",
            contenu = "# Liste\n\n- pain\n- [[Recettes]]\n",
            etiquettes = listOf("maison", "à faire"),
            epinglee = true,
            favorite = true,
        )
        val fichier = NoteMarkdown.render(note, folderLabel = "Perso", vaultMention = "from a vault", zone = zone)

        val lue = LecteurMarkdown.lire(fichier, "ignored.md", zone)

        assertThat(lue.titre).isEqualTo(note.title)
        assertThat(lue.contenu).isEqualTo("# Liste\n\n- pain\n- [[Recettes]]")
        assertThat(lue.etiquettes).containsExactly("maison", "à faire").inOrder()
        assertThat(lue.dossier).isEqualTo("Perso")
        assertThat(lue.creeeLe).isEqualTo(note.createdAt)
        assertThat(lue.modifieeLe).isEqualTo(note.updatedAt)
        assertThat(lue.epinglee).isTrue()
        assertThat(lue.favorite).isTrue()
    }

    @Test
    fun `a note without line break at the end, exported then read, is unchanged`() {
        val note = note(contenu = "une ligne")
        val lue = LecteurMarkdown.lire(NoteMarkdown.render(note, "Inbox", zone = zone), null, zone)
        assertThat(lue.contenu).isEqualTo("une ligne")
        assertThat(lue.etiquettes).isEmpty()
        assertThat(lue.epinglee).isFalse()
    }

    @Test
    fun `the common forms of other tools are read`() {
        val fichier = """
            ---
            title: Réunion de lundi
            tags:
              - travail
              - "projet, X"
            created: 2026-03-01
            updated: 2026-03-02T10:15:00+01:00
            aliases: [ignored]
            ---
            Corps
        """.trimIndent()

        val lue = LecteurMarkdown.lire(fichier, null, zone)

        assertThat(lue.titre).isEqualTo("Réunion de lundi")
        // "projet, X" would break the comma-joined storage: it becomes two tags.
        assertThat(lue.etiquettes).containsExactly("travail", "projet", "X").inOrder()
        assertThat(lue.creeeLe).isEqualTo(Instant.parse("2026-02-28T23:00:00Z"))
        assertThat(lue.modifieeLe).isEqualTo(Instant.parse("2026-03-02T09:15:00Z"))
        assertThat(lue.contenu).isEqualTo("Corps")
    }

    @Test
    fun `without frontmatter the title is the file name, and the text is the body`() {
        val lue = LecteurMarkdown.lire("Juste du texte\nsur deux lignes", "dossier/Idées du jour [unlocked].md", zone)
        assertThat(lue.titre).isEqualTo("Idées du jour")
        assertThat(lue.contenu).isEqualTo("Juste du texte\nsur deux lignes")
        assertThat(lue.creeeLe).isNull()
    }

    @Test
    fun `a frontmatter that never closes is not one`() {
        val texte = "---\ntitle: pas fermé\n\nla suite"
        val lue = LecteurMarkdown.lire(texte, "brouillon.md", zone)
        assertThat(lue.titre).isEqualTo("brouillon")
        assertThat(lue.contenu).isEqualTo(texte)
    }

    @Test
    fun `a Windows file — byte-order mark and CRLF — is read like any other`() {
        val texte = "\uFEFF---\r\ntitle: \"Win\"\r\n---\r\n\r\nligne 1\r\nligne 2\r\n"
        val lue = LecteurMarkdown.lire(texte, null, zone)
        assertThat(lue.titre).isEqualTo("Win")
        assertThat(lue.contenu).isEqualTo("ligne 1\nligne 2")
    }

    @Test
    fun `a title longer than the repository accepts is cut, never inside a surrogate pair`() {
        val titre = "a".repeat(LecteurMarkdown.TITRE_MAX - 1) + "😀" + "fin"
        val lue = LecteurMarkdown.lire("---\ntitle: \"$titre\"\n---\n", null, zone)
        assertThat(lue.titre).isEqualTo("a".repeat(LecteurMarkdown.TITRE_MAX - 1))
        assertThat(lue.titre.length).isAtMost(LecteurMarkdown.TITRE_MAX)
    }

    @Test
    fun `a shared text is titled by its subject, else its first non-blank line`() {
        assertThat(LecteurMarkdown.titreDUnTexte("Sujet", "corps")).isEqualTo("Sujet")
        assertThat(LecteurMarkdown.titreDUnTexte(null, "\n\n  Première vraie ligne \nsuite")).isEqualTo("Première vraie ligne")
        assertThat(LecteurMarkdown.titreDUnTexte(" ", "")).isEmpty()
    }

    private fun note(
        titre: String = "Titre",
        contenu: String = "",
        etiquettes: List<String> = emptyList(),
        epinglee: Boolean = false,
        favorite: Boolean = false,
    ) = Note(
        id = "00000000-0000-0000-0000-000000000001",
        title = titre,
        content = contenu,
        folderId = "inbox",
        tags = etiquettes,
        pinned = epinglee,
        favorite = favorite,
        archived = false,
        trashedAt = null,
        createdAt = Instant.parse("2026-08-14T13:07:23.123Z"),
        updatedAt = Instant.parse("2026-08-15T09:00:00.000Z"),
        encrypted = null,
        encVersion = EncryptedFormat.CONTENT_ONLY,
    )
}
