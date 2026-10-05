package com.filestech.notes_tech.ui.common

import com.filestech.notes_tech.domain.export.NoteArchive
import com.filestech.notes_tech.domain.export.NoteMarkdown
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.domain.model.Note
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipInputStream

/**
 * The inbox's default name, shown and exported in the app's language — the port's replay of
 * notes_tech 2.0.9's `test/inbox_default_name_test.dart`.
 *
 * Every Flutter install before 2.0.9 seeded the inbox as "Boîte de réception", whatever the phone's
 * language: an English-speaking user saw a French folder name in the app and in the export
 * (reported on fdroiddata!37885). The port seeded the same name until 2026-09-24.
 */
class InboxDefaultNameTest {

    private val epoch = Instant.EPOCH

    private fun folder(id: String, name: String) = Folder(
        id = id,
        name = name,
        parentId = null,
        color = null,
        icon = null,
        createdAt = epoch,
        updatedAt = epoch,
        vault = null,
    )

    private fun inbox(name: String) = folder(Folder.INBOX_ID, name)

    private val note = Note(
        id = "11111111-2222-3333-4444-555555555555",
        title = "Groceries",
        content = "Milk",
        folderId = Folder.INBOX_ID,
        tags = emptyList(),
        pinned = false,
        favorite = false,
        archived = false,
        trashedAt = null,
        createdAt = epoch,
        updatedAt = epoch,
        encrypted = null,
        encVersion = 0,
    )

    // ── Display ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a default inbox name follows the app's language, whichever default it is")
    fun default_name_follows_the_language() {
        for (seeded in listOf("Boîte de réception", Folder.INBOX_DEFAULT_NAME)) {
            assertThat(folderDisplayName(Folder.INBOX_ID, seeded, inboxLabel = "Inbox")).isEqualTo("Inbox")
            assertThat(folderDisplayName(Folder.INBOX_ID, seeded, inboxLabel = "Boîte de réception"))
                .isEqualTo("Boîte de réception")
        }
    }

    @Test
    @DisplayName("a name the user chose is kept, and only the inbox is concerned")
    fun chosen_names_are_kept() {
        val french = "Boîte de réception"
        assertThat(folderDisplayName(Folder.INBOX_ID, "Personal", inboxLabel = french)).isEqualTo("Personal")
        // A user folder that happens to carry a default name is NOT the inbox.
        assertThat(folderDisplayName("f1", "Boîte de réception", inboxLabel = "Inbox")).isEqualTo("Boîte de réception")
        assertThat(inbox("Personal").hasDefaultInboxName).isFalse()
        assertThat(folder("f1", "Inbox").hasDefaultInboxName).isFalse()
    }

    @Test
    @DisplayName("a new database seeds the name the rule recognises")
    fun the_seed_is_a_default_name() {
        assertThat(Folder.isDefaultInboxName(Folder.INBOX_ID, Folder.INBOX_DEFAULT_NAME)).isTrue()
    }

    // ── Export ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a default inbox exports under the technical directory name, a named one under its name")
    fun directory_names() {
        assertThat(zipDirectories(inbox("Boîte de réception"))).containsExactly("inbox")
        assertThat(zipDirectories(inbox(Folder.INBOX_DEFAULT_NAME))).containsExactly("inbox")
        assertThat(zipDirectories(inbox("Personal"))).containsExactly("Personal")
    }

    @Test
    @DisplayName("a default inbox gets the app-language label in the frontmatter, a named one its name")
    fun frontmatter_labels() {
        assertThat(frontmatterFolder(inbox("Boîte de réception"))).isEqualTo("folder: \"Inbox\"")
        assertThat(frontmatterFolder(inbox("Personal"))).isEqualTo("folder: \"Personal\"")
    }

    @Test
    @DisplayName("a note whose folder row is missing keeps its folder identifier as label")
    fun missing_folder_row() {
        assertThat(NoteMarkdown.folderLabel(null, "f9", inboxLabel = "Inbox")).isEqualTo("f9")
        assertThat(NoteMarkdown.folderLabel(null, Folder.INBOX_ID, inboxLabel = "Inbox")).isEqualTo("Inbox")
    }

    private fun archive(inboxFolder: Folder): Map<String, String> {
        val out = ByteArrayOutputStream()
        NoteArchive.write(
            output = out,
            notes = listOf(note),
            foldersById = mapOf(inboxFolder.id to inboxFolder),
            unlockedVaultFolderIds = emptySet(),
            inboxLabel = "Inbox",
            vaultMention = { "" },
            exportedAt = epoch,
            zone = ZoneOffset.UTC,
        )
        val entries = mutableMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun zipDirectories(inboxFolder: Folder): List<String> =
        archive(inboxFolder).keys.filter { it.endsWith(".md") && '/' in it }.map { it.substringBefore('/') }

    private fun frontmatterFolder(inboxFolder: Folder): String =
        archive(inboxFolder).entries.single { it.key.startsWith("${Folder.INBOX_ID}/") || '/' in it.key }
            .value.lines().single { it.startsWith("folder: ") }
}
