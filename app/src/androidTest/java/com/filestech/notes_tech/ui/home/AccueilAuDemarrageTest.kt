package com.filestech.notes_tech.ui.home

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.IsolatedPreferencesContext
import com.filestech.notes_tech.data.local.DatabaseProvider
import com.filestech.notes_tech.data.local.LegacyDatabaseFixture
import com.filestech.notes_tech.data.local.LegacyDatabaseLocation
import com.filestech.notes_tech.data.local.NotesDatabaseFactory
import com.filestech.notes_tech.data.local.SqlCipherRawKey
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.prefs.HomeStartPreference
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import com.filestech.notes_tech.data.repository.DureesDeVie
import com.filestech.notes_tech.data.repository.FoldersRepository
import com.filestech.notes_tech.data.repository.NotesEnLot
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.data.repository.SearchRepository
import com.filestech.notes_tech.domain.model.Folder
import com.filestech.notes_tech.security.kek.KekRepository
import com.filestech.notes_tech.security.kek.WritableKekSource
import com.filestech.notes_tech.security.vault.FolderVaultService
import com.filestech.notes_tech.security.vault.KeystoreEnMemoire
import com.filestech.notes_tech.security.vault.MonotonicClock
import com.filestech.notes_tech.security.vault.VaultSessions
import com.filestech.notes_tech.security.vault.VaultWipeJournal
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * D-046 (3.5.0, issue #59): the home screen opens on the list the setting chooses — through the real
 * view model, on a real database: the setting is read at a NEW start, and the choice is written into the
 * saved state at once, so that a process death restores what was shown.
 *
 * The rule itself — which of saved state and setting wins — is `DossierAuDemarrageTest`, on the JVM. This
 * class checks that the view model applies it, and the control (the default) that it applies nothing
 * else.
 *
 * Also D-051 (3.5.0, issue #72), on the same real view model and database: the archive's own search.
 */
@RunWith(AndroidJUnit4::class)
class AccueilAuDemarrageTest {

    private lateinit var context: Context
    private lateinit var preferences: IsolatedPreferencesContext
    private lateinit var fichierDeBase: File
    private val kek = ByteArray(SqlCipherRawKey.KEY_SIZE_BYTES) { (it * 11 + 3).toByte() }
    private val horloge = HorlogeFigee(Instant.ofEpochMilli(1_760_000_000_000L))

    private lateinit var provider: DatabaseProvider
    private lateinit var dossiers: FoldersRepository
    private lateinit var notes: NotesRepository
    private lateinit var coffres: FolderVaultService
    private lateinit var reglages: AppSettings

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        preferences = IsolatedPreferencesContext(context, "_accueil_au_demarrage")
        System.loadLibrary("sqlcipher")
        fichierDeBase = File(context.cacheDir, "accueil-fixture/notes_tech.db")
        LegacyDatabaseFixture.create(fichierDeBase, SqlCipherRawKey.encode(kek))

        val source = object : WritableKekSource {
            override val name = "test"
            override fun load(): ByteArray = kek.copyOf()
            override fun store(kek: ByteArray) = Unit
            override fun replaceKeyAndStore(kek: ByteArray) = Unit
            override fun destroy() = Unit
        }
        provider = DatabaseProvider(
            context = context,
            factory = NotesDatabaseFactory(
                kekRepository = KekRepository(listOf(source), source, databaseExists = { true }),
                nowMillis = horloge::millis,
                databaseFile = { fichierDeBase },
            ),
            ioDispatcher = Dispatchers.IO,
        )
        reglages = AppSettings(LegacyPreferences(preferences))
        coffres = FolderVaultService(
            provider,
            KeystoreEnMemoire(),
            VaultSessions(HorlogeMonotone()),
            VaultWipeJournal(LegacyPreferences(preferences)),
            horloge,
        )
        dossiers = FoldersRepository(provider, horloge)
        notes = NotesRepository(provider, dossiers, coffres, coffres, horloge)
    }

    @After
    fun tearDown(): Unit = runBlocking {
        coffres.lockAll()
        provider.close()
        LegacyDatabaseLocation.SIDECAR_SUFFIXES.forEach { File(fichierDeBase.path + it).delete() }
        preferences.delete(FICHIER_DE_PREFERENCES)
    }

    @Test
    fun with_the_setting_on_the_Inbox_a_new_start_opens_on_it_and_saves_it(): Unit = runBlocking {
        reglages.setHomeStart(HomeStartPreference.INBOX)
        val sauvegarde = SavedStateHandle()

        val etat = etatCharge(accueil(sauvegarde)) { it.currentFolder != null }

        assertThat(etat.currentFolder?.id).isEqualTo(Folder.INBOX_ID)
        assertThat(sauvegarde.get<String>(HomeViewModel.CLE_DOSSIER)).isEqualTo(Folder.INBOX_ID)
    }

    /** "All notes" picked on purpose, then the process died: it comes back as it was, setting or not. */
    @Test
    fun a_restored_All_notes_stays_All_notes_with_the_setting_on_the_Inbox(): Unit = runBlocking {
        reglages.setHomeStart(HomeStartPreference.INBOX)
        val restauree = SavedStateHandle(mapOf(HomeViewModel.CLE_DOSSIER to null))

        val etat = etatCharge(accueil(restauree)) { true }

        assertThat(etat.currentFolder).isNull()
        assertThat(restauree.contains(HomeViewModel.CLE_DOSSIER)).isTrue()
    }

    /**
     * 3-axes audit of 3.5.0: the test above builds the restored state in memory. What a process death
     * really goes through is a Bundle — this proves a key holding `null` comes back out of it as a key,
     * which is what tells "All notes" chosen from "nothing to restore".
     */
    @Test
    fun a_saved_All_notes_survives_the_bundle_of_a_process_death() {
        val avant = SavedStateHandle()
        avant[HomeViewModel.CLE_DOSSIER] = null

        val apres = SavedStateHandle.createHandle(avant.savedStateProvider().saveState(), null)

        assertThat(apres.contains(HomeViewModel.CLE_DOSSIER)).isTrue()
        assertThat(apres.get<String>(HomeViewModel.CLE_DOSSIER)).isNull()
    }

    /** The control: with nothing set, a new start opens on All notes, as before 3.5.0. */
    @Test
    fun by_default_a_new_start_opens_on_All_notes(): Unit = runBlocking {
        val sauvegarde = SavedStateHandle()

        val etat = etatCharge(accueil(sauvegarde)) { true }

        assertThat(etat.currentFolder).isNull()
        assertThat(sauvegarde.contains(HomeViewModel.CLE_DOSSIER)).isTrue()
        assertThat(sauvegarde.get<String>(HomeViewModel.CLE_DOSSIER)).isNull()
    }

    // ── D-051 (3.5.0, issue #72): the archive's own search ──────────────────────────────────────────────

    /**
     * In the archive, the search looks through the archived notes alone; on the home screen, through the
     * others alone. Leaving the archive clears its search, rather than going on through the other notes.
     */
    @Test
    fun the_archive_searches_its_own_notes_and_leaving_it_clears_the_search(): Unit = runBlocking {
        val plain = LegacyDatabaseFixture.Fixtures.NOTE_PLAIN
        notes.setArchived(plain, true)
        // A second archived note WITHOUT the word: the whole archive is two notes, its search one — so a list
        // that ignored the search could not pass for it.
        val autreArchivee = notes.create(folderId = Folder.INBOX_ID, title = "Liste de courses", content = "")
        notes.setArchived(autreArchivee.id, true)
        // And a note that is NOT archived, holding the word: what a search outside the archive would find.
        val ordinaire = notes.create(folderId = Folder.INBOX_ID, title = "Réunion de rentrée", content = "")
        val accueil = accueil(SavedStateHandle())

        withContext(Dispatchers.Main) {
            accueil.onArchiveSelected()
            accueil.onQueryChange("reunion")
        }
        // Times out if the archive ignores the search (two notes) or searches outside it (the other note).
        etatCharge(accueil) { it.archive && it.notes.map { n -> n.id } == listOf(plain) }

        withContext(Dispatchers.Main) { accueil.onFolderSelected(null) }
        assertThat(etatCharge(accueil) { !it.archive }.query).isEmpty()

        withContext(Dispatchers.Main) { accueil.onQueryChange("reunion") }
        etatCharge(accueil) { !it.archive && it.notes.map { n -> n.id } == listOf(ordinaire.id) }
    }

    private suspend fun accueil(sauvegarde: SavedStateHandle): HomeViewModel = withContext(Dispatchers.Main) {
        HomeViewModel(
            notes = notes,
            enLot = NotesEnLot(provider, notes, dossiers),
            durees = DureesDeVie(provider, horloge),
            folders = dossiers,
            search = SearchRepository(provider),
            settings = reglages,
            vaults = coffres,
            savedState = sauvegarde,
        )
    }

    /** The first LOADED state matching [condition]: the list and the folder are read by then. */
    private suspend fun etatCharge(accueil: HomeViewModel, condition: (HomeUiState) -> Boolean): HomeUiState =
        withTimeout(TIMEOUT_MILLIS) {
            withContext(Dispatchers.Main) { accueil.state.first { !it.loading && condition(it) } }
        }

    private class HorlogeFigee(private val maintenant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = maintenant
    }

    private class HorlogeMonotone : MonotonicClock {
        override fun elapsedMillis(): Long = 0L
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L

        /** `LegacyPreferences.FLUTTER_PREFS_FILE`, private there — diverted by [IsolatedPreferencesContext]. */
        const val FICHIER_DE_PREFERENCES = "FlutterSharedPreferences"
    }
}
