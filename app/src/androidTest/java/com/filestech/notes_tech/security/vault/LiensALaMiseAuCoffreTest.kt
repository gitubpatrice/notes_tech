package com.filestech.notes_tech.security.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.data.repository.FoldersRepository
import com.filestech.notes_tech.data.repository.LinksRepository
import com.filestech.notes_tech.data.repository.NotesRepository
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import javax.inject.Inject

/**
 * 🔴 **A folder turned into a vault takes its notes out of the links** — security audit of 2026-09-26,
 * K1 and K2 — through the bulk gesture itself, `encryptAllNotesInFolder`, not the repair done at the
 * opening, which would hide a gesture that forgot the rule.
 *
 * Before: the `[[…]]` titles written in a converted note stayed in clear in `note_links`, and a link
 * from an ordinary note still resolved to it — the chip telling that a vault holds a note of that
 * title (the rule of issue #10).
 *
 * ⚠️ It writes to the app's real database: two folders and their notes, with a random suffix, deleted
 * in [tearDown].
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LiensALaMiseAuCoffreTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var notes: NotesRepository

    @Inject
    lateinit var links: LinksRepository

    @Inject
    lateinit var folders: FoldersRepository

    @Inject
    lateinit var vaults: FolderVaultService

    private val suffixe = UUID.randomUUID().toString().take(8)
    private val dossiers = mutableListOf<String>()

    @Before
    fun setUp() {
        hilt.inject()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        dossiers.forEach { dossier ->
            vaults.lock(dossier)
            notes.listAllAlive().filter { it.folderId == dossier }.forEach { notes.deletePermanently(it.id) }
            folders.delete(dossier)
        }
    }

    @Test
    fun converting_a_folder_detaches_its_notes_from_every_link(): Unit = runBlocking {
        val ordinaire = folders.create("Plain $suffixe").id.also { dossiers += it }
        val futurCoffre = folders.create("Soon a vault $suffixe").id.also { dossiers += it }
        val titreCible = "Codes $suffixe"
        val cible = notes.create(folderId = futurCoffre, title = titreCible, content = "0000").id
        val bavarde = notes.create(
            folderId = futurCoffre,
            title = "Bank $suffixe",
            content = "See [[Safe $suffixe]].",
        ).id
        val source = notes.create(folderId = ordinaire, title = "Todo $suffixe", content = "Read [[$titreCible]].").id
        // Before: the ordinary note's link is resolved, the future vault note has a link of its own.
        assertThat(links.observeOutgoing(source).first().single().targetId).isEqualTo(cible)
        assertThat(links.observeOutgoing(bavarde).first()).isNotEmpty()

        vaults.createPassphraseVault(futurCoffre, PHRASE)
        val bilan = vaults.encryptAllNotesInFolder(futurCoffre)

        assertThat(bilan.isComplete).isTrue()
        assertThat(notes.find(cible)!!.isLocked).isTrue()
        // No link leaves a sealed note: its `[[…]]` titles are not left in clear.
        assertThat(links.observeOutgoing(bavarde).first()).isEmpty()
        // None resolves to it: the ordinary note's link is a dangling one again.
        val lien = links.observeOutgoing(source).first().single()
        assertThat(lien.targetId).isNull()
        assertThat(lien.targetTitle).isEqualTo(titreCible)
    }

    private companion object {
        const val PHRASE = "correct horse battery staple"
    }
}
