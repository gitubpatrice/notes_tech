package com.filestech.notes_tech.domain.importation

import com.filestech.notes_tech.domain.export.NoteArchive
import com.filestech.notes_tech.domain.model.EncryptedFormat
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The Settings' import (D-032): what becomes a note, in which folder, and where it stops. */
class LectureDUnImportTest {

    private val zone = ZoneId.of("Europe/Paris")

    private fun zip(vararg entrees: Pair<String, ByteArray>): ByteArray {
        val sortie = ByteArrayOutputStream()
        ZipOutputStream(sortie).use { zip ->
            entrees.forEach { (nom, octets) ->
                zip.putNextEntry(ZipEntry(nom))
                zip.write(octets)
                zip.closeEntry()
            }
        }
        return sortie.toByteArray()
    }

    private fun lire(vararg documents: Pair<String, ByteArray>): Import {
        val lecture = LectureDUnImport(zone)
        documents.forEach { (nom, octets) -> lecture.ajouter(nom, ByteArrayInputStream(octets)) }
        return lecture.resultat()
    }

    @Test
    fun `an export of Notes Tech comes back in its folders, its README left out`() {
        val maintenant = Instant.parse("2026-10-29T10:00:00Z")
        val perso = Folder(
            id = "f-perso",
            name = "Perso",
            parentId = null,
            color = null,
            icon = null,
            createdAt = maintenant,
            updatedAt = maintenant,
            vault = null,
        )
        val archive = ByteArrayOutputStream().also { sortie ->
            NoteArchive.write(
                output = sortie,
                notes = listOf(
                    note("1", "Courses", Folder.INBOX_ID, maintenant),
                    note("2", "Reiki", "f-perso", maintenant),
                ),
                foldersById = mapOf(perso.id to perso),
                exportedAt = maintenant,
                zone = zone,
            )
        }.toByteArray()

        val lu = lire("notes-tech-export.zip" to archive)

        assertThat(lu.notes.map { it.note.titre to it.dossier }).containsExactly("Courses" to null, "Reiki" to "Perso")
        assertThat(lu.notes.first().note.etiquettes).containsExactly("maison")
        assertThat(lu.ignores).isEqualTo(0)
        assertThat(lu.arrete).isFalse()
    }

    @Test
    fun `a file alone goes to the Inbox, what is not text is counted`() {
        val lu = lire(
            "Recette.md" to "# Tarte\n\nPâte".toByteArray(),
            "liste.TXT" to "pain".toByteArray(),
            "photo.jpg" to byteArrayOf(1, 2, 3),
            "faux.md" to byteArrayOf(0xC3.toByte(), 0x28),
        )

        assertThat(lu.notes.map { it.note.titre to it.dossier }).containsExactly("Recette" to null, "liste" to null)
        assertThat(lu.ignores).isEqualTo(2)
    }

    @Test
    fun `in a ZIP, hidden entries are not documents, other files are counted`() {
        val lu = lire(
            "coffre-obsidian.zip" to zip(
                "Projets/Idée.md" to "Une idée".toByteArray(),
                "Projets/.obsidian/app.json" to "{}".toByteArray(),
                ".DS_Store" to byteArrayOf(0),
                "__MACOSX/Projets/._Idée.md" to byteArrayOf(0),
                "Projets/schema.png" to byteArrayOf(1),
                "inbox/Note.md" to "x".toByteArray(),
            ),
        )

        assertThat(lu.notes.map { it.note.titre to it.dossier }).containsExactly("Idée" to "Projets", "Note" to null)
        assertThat(lu.ignores).isEqualTo(1)
    }

    /**
     * 🔴 The text kept until the one writing transaction is bounded: past it, the import stops and keeps
     * what it had (audit 3 axes of 3.2.0, P1 — 100 MiB of notes held at once could exceed the heap).
     */
    @Test
    fun `past the text it may keep, the import stops and keeps what it had`() {
        val lecture = LectureDUnImport(zone, texteRetenuMax = 10)
        for (nom in listOf("Un.md", "Deux.md", "Trois.md")) {
            lecture.ajouter(nom, ByteArrayInputStream("abcd".toByteArray()))
        }

        val lu = lecture.resultat()

        // 4 + 4 fit in 10; the third would make 12.
        assertThat(lu.notes.map { it.note.titre }).containsExactly("Un", "Deux").inOrder()
        assertThat(lu.arrete).isTrue()
    }

    /**
     * A folder's name from an archive loses its control and bidirectional formatting characters — an
     * override can make a name read as another in the folders' drawer — and is cut at its bound
     * (audit 3 axes of 3.2.0, P2).
     */
    @Test
    fun `a folder's name from an archive is cleaned and bounded`() {
        val lu = lire(
            "a.zip" to zip(
                "\u202Efdp.exe\u0007/Une.md" to "x".toByteArray(),
                "${"d".repeat(300)}/Deux.md" to "x".toByteArray(),
                "\u202E\u200F/Trois.md" to "x".toByteArray(),
            ),
        )

        assertThat(lu.notes.map { it.dossier })
            .containsExactly("fdp.exe", "d".repeat(LectureDUnImport.NOM_DE_DOSSIER_MAX), null)
            .inOrder()
        assertThat(LectureDUnImport.nomDeDossier("a".repeat(99) + "😀")).isEqualTo("a".repeat(99))
    }

    @Test
    fun `an entry too long is left out, and the next one is read`() {
        val lu = lire(
            "a.zip" to zip(
                "Grand.md" to ByteArray(LectureDUnImport.FICHIER_MAX + 1) { 'a'.code.toByte() },
                "Petit.md" to "ok".toByteArray(),
            ),
        )

        assertThat(lu.notes.map { it.note.titre }).containsExactly("Petit")
        assertThat(lu.ignores).isEqualTo(1)
    }

    /**
     * 🔴 A note brought in is at most [LongueurDUneNote.CARACTERES_MAX] characters (2026-10-30): read in
     * full — within the bytes allowed — but one character longer, a file is counted, not kept.
     */
    @Test
    fun `a file one character longer than a note is counted, one at the bound kept`() {
        val lu = lire(
            "Long.md" to "a".repeat(LongueurDUneNote.CARACTERES_MAX + 1).toByteArray(),
            "Juste.md" to "a".repeat(LongueurDUneNote.CARACTERES_MAX).toByteArray(),
        )

        assertThat(lu.notes.map { it.note.titre }).containsExactly("Juste")
        assertThat(lu.ignores).isEqualTo(1)
    }

    /** 🔴 A few hundred kilobytes that inflate past the bound: read once, counted, and the import stops. */
    @Test
    fun `an archive bomb stops the import on what it inflates, skipped entries included`() {
        val bombe = ByteArrayOutputStream().also { sortie ->
            ZipOutputStream(sortie).use { zip ->
                zip.putNextEntry(ZipEntry("Avant.md"))
                zip.write("gardée".toByteArray())
                zip.closeEntry()
                // Not a note: skipped, so its bytes would go uncounted if skipping were not reading.
                zip.putNextEntry(ZipEntry("zeros.bin"))
                val zeros = ByteArray(1024 * 1024)
                repeat((LectureDUnImport.LUS_MAX / zeros.size).toInt() + 1) { zip.write(zeros) }
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("Apres.md"))
                zip.write("jamais lue".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        assertThat(bombe.size).isLessThan(1024 * 1024)

        val lu = lire("bombe.zip" to bombe, "Suivant.md" to "après l'arrêt".toByteArray())

        assertThat(lu.arrete).isTrue()
        assertThat(lu.notes.map { it.note.titre }).containsExactly("Avant")
        assertThat(lu.ignores).isEqualTo(2)
    }

    @Test
    fun `past the number of files, the import stops on the ones it read`() {
        val entrees = (1..LectureDUnImport.FICHIERS_MAX + 1).map { "n$it.md" to "x".toByteArray() }.toTypedArray()

        val lu = lire("beaucoup.zip" to zip(*entrees))

        assertThat(lu.notes).hasSize(LectureDUnImport.FICHIERS_MAX)
        assertThat(lu.arrete).isTrue()
    }

    /** A download cut short: cut inside the second entry's data, not just its central directory. */
    @Test
    fun `a damaged archive keeps what was read before the damage`() {
        val hasard = java.util.Random(7)
        val long = ByteArray(20_000) { ('a' + hasard.nextInt(26)).code.toByte() }
        val entier = zip("Un.md" to "un".toByteArray(), "Deux.md" to long)
        val tronque = entier.copyOf(entier.size - 6_000)

        val lu = lire("abime.zip" to tronque)

        assertThat(lu.notes.map { it.note.titre }).containsExactly("Un")
        assertThat(lu.ignores).isEqualTo(1)
    }

    private fun note(id: String, titre: String, dossier: String, quand: Instant) = Note(
        id = id,
        title = titre,
        content = "Le texte de $titre.",
        folderId = dossier,
        tags = listOf("maison"),
        pinned = false,
        favorite = false,
        archived = false,
        trashedAt = null,
        createdAt = quand,
        updatedAt = quand,
        encrypted = null,
        encVersion = EncryptedFormat.CONTENT_ONLY,
    )
}
