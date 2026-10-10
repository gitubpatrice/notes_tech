package com.filestech.notes_tech.ui.home

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.IsolatedPreferencesContext
import com.filestech.notes_tech.core.crypto.SecretBytes
import com.filestech.notes_tech.data.local.DatabaseProvider
import com.filestech.notes_tech.data.local.LegacyDatabaseFixture
import com.filestech.notes_tech.data.local.LegacyDatabaseLocation
import com.filestech.notes_tech.data.local.NotesDatabaseFactory
import com.filestech.notes_tech.data.local.SqlCipherRawKey
import com.filestech.notes_tech.data.prefs.AppSettings
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import com.filestech.notes_tech.data.repository.FoldersRepository
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.data.repository.SearchRepository
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.security.kek.KekRepository
import com.filestech.notes_tech.security.kek.WritableKekSource
import com.filestech.notes_tech.security.vault.FolderVaultService
import com.filestech.notes_tech.security.vault.KeystoreUnavailableException
import com.filestech.notes_tech.security.vault.MonotonicClock
import com.filestech.notes_tech.security.vault.SealedByKeystore
import com.filestech.notes_tech.security.vault.VaultCrypto
import com.filestech.notes_tech.security.vault.VaultKeystore
import com.filestech.notes_tech.security.vault.VaultParams
import com.filestech.notes_tech.security.vault.VaultSessions
import com.filestech.notes_tech.security.vault.VaultWipeJournal
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
 * The long press on a note (3.1.0), against a real SQLCipher database and a real passphrase vault.
 *
 * ## 🔴 What this file guards
 *
 * Trashing or erasing a vault note needs no key — the row goes, sealed blob and all — so nothing
 * structural stops it: `HomeViewModel.executer` checking the vault IS the protection. Without it,
 * whoever gets past the app lock could destroy a closed vault's notes from the list, without its
 * secret. Each refusal below is paired with the same gesture succeeding once the vault is open: a
 * refusal alone would also pass if the gesture never worked at all.
 *
 * ⚠️ The RESUME after the unlock — the refused gesture running by itself once the secret is given — is
 * the screen's, not the view model's: it is checked in `GestesDeNoteEnCoursTest`, on the state holder
 * that keeps the gesture waiting.
 */
@RunWith(AndroidJUnit4::class)
class GestesSurUneNoteTest {

    private lateinit var context: Context
    private lateinit var preferences: IsolatedPreferencesContext
    private lateinit var fichierDeBase: File
    private val kek = ByteArray(SqlCipherRawKey.KEY_SIZE_BYTES) { (it * 7 + 5).toByte() }
    private val horloge = HorlogeFigee(Instant.ofEpochMilli(1_760_000_000_000L))

    private lateinit var provider: DatabaseProvider
    private lateinit var dossiers: FoldersRepository
    private lateinit var notes: NotesRepository
    private lateinit var coffres: FolderVaultService
    private lateinit var accueil: HomeViewModel

    @Before
    fun setUp(): Unit = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        preferences = IsolatedPreferencesContext(context, "_gestes_sur_une_note")
        System.loadLibrary("sqlcipher")
        fichierDeBase = File(context.cacheDir, "gestes-fixture/notes_tech.db")
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
        val journal = VaultWipeJournal(LegacyPreferences(preferences))
        coffres = FolderVaultService(provider, KeystoreEnMemoire(), VaultSessions(HorlogeMonotone()), journal, horloge)
        dossiers = FoldersRepository(provider, horloge)
        notes = NotesRepository(provider, dossiers, coffres, coffres, horloge)
        accueil = withContext(Dispatchers.Main) {
            HomeViewModel(
                notes = notes,
                folders = dossiers,
                search = SearchRepository(provider),
                settings = AppSettings(LegacyPreferences(preferences)),
                vaults = coffres,
                savedState = SavedStateHandle(),
            )
        }
    }

    @After
    fun tearDown(): Unit = runBlocking {
        coffres.lockAll()
        provider.close()
        LegacyDatabaseLocation.SIDECAR_SUFFIXES.forEach { File(fichierDeBase.path + it).delete() }
        preferences.delete(FICHIER_DE_PREFERENCES)
    }

    @Test
    fun trashing_a_note_of_a_closed_vault_is_refused_and_allowed_once_it_is_open(): Unit = runBlocking {
        val (coffre, note) = noteDeCoffreFermee()
        val geste = GesteSurUneNote.MettreALaCorbeille(note)

        val refus = evenementDe { accueil.executer(geste) }
        assertThat(refus).isEqualTo(HomeEvent.GesteEnAttenteDuCoffre(dossiers.find(coffre)!!, geste))
        assertThat(notes.find(note.id)!!.trashedAt).isNull()

        coffres.unlockWithPassphrase(coffre, PHRASE)
        assertThat(evenementDe { accueil.executer(geste) }).isEqualTo(HomeEvent.MovedToTrash(note.id))
        assertThat(notes.find(note.id)!!.trashedAt).isNotNull()
    }

    @Test
    fun erasing_a_note_of_a_closed_vault_is_refused_and_allowed_once_it_is_open(): Unit = runBlocking {
        val (coffre, note) = noteDeCoffreFermee()
        val geste = GesteSurUneNote.SupprimerDefinitivement(note)

        assertThat(evenementDe { accueil.executer(geste) }).isInstanceOf(HomeEvent.GesteEnAttenteDuCoffre::class.java)
        assertThat(notes.find(note.id)).isNotNull()

        coffres.unlockWithPassphrase(coffre, PHRASE)
        assertThat(evenementDe { accueil.executer(geste) }).isEqualTo(HomeEvent.DeletedForever)
        assertThat(notes.find(note.id)).isNull()
    }

    /**
     * The card's copy decides nothing: a note shown in clear, moved into a vault that then closed, is
     * refused — the check reads the note again at execution.
     */
    @Test
    fun the_check_reads_the_note_again_instead_of_trusting_the_card(): Unit = runBlocking {
        val ordinaire = notes.create(folderId = dossiers.create("Ordinaire").id, title = "Avant", content = "texte")
        val coffre = dossiers.create("Coffre").id.also { coffres.createPassphraseVault(it, PHRASE) }
        notes.moveToFolder(ordinaire.id, coffre)
        coffres.lock(coffre)

        val refus = evenementDe { accueil.executer(GesteSurUneNote.MettreALaCorbeille(ordinaire)) }

        assertThat(refus).isInstanceOf(HomeEvent.GesteEnAttenteDuCoffre::class.java)
        assertThat(notes.find(ordinaire.id)!!.trashedAt).isNull()
    }

    @Test
    fun an_ordinary_note_goes_to_the_trash_and_undo_brings_it_back(): Unit = runBlocking {
        val note = notes.create(folderId = dossiers.create("Ordinaire").id, title = "Liste", content = "pain")

        assertThat(evenementDe { accueil.executer(GesteSurUneNote.MettreALaCorbeille(note)) })
            .isEqualTo(HomeEvent.MovedToTrash(note.id))
        assertThat(notes.find(note.id)!!.trashedAt).isNotNull()

        assertThat(evenementDe { accueil.restaurer(note.id) }).isEqualTo(HomeEvent.Restored)
        assertThat(notes.find(note.id)!!.trashedAt).isNull()
    }

    /** Undo works on a vault note while its vault is CLOSED: the note comes back sealed, as it left. */
    @Test
    fun undo_restores_a_vault_note_without_its_secret_and_it_stays_sealed(): Unit = runBlocking {
        val (coffre, note) = noteDeCoffreFermee()
        coffres.unlockWithPassphrase(coffre, PHRASE)
        evenementDe { accueil.executer(GesteSurUneNote.MettreALaCorbeille(note)) }
        coffres.lock(coffre)

        assertThat(evenementDe { accueil.restaurer(note.id) }).isEqualTo(HomeEvent.Restored)
        val revenue = notes.find(note.id)!!
        assertThat(revenue.trashedAt).isNull()
        assertThat(revenue.isLocked).isTrue()
    }

    private suspend fun noteDeCoffreFermee(): Pair<String, Note> {
        val coffre = dossiers.create("Coffre").id
        coffres.createPassphraseVault(coffre, PHRASE)
        val note = notes.create(folderId = coffre, title = "Relevé", content = "IBAN")
        coffres.lock(coffre)
        val carte = notes.find(note.id)!!
        assertThat(carte.isLocked).isTrue()
        return coffre to carte
    }

    /**
     * The next event of the home view model after [action]. Subscribed BEFORE the action — the flow
     * keeps nothing for a late collector — then resumed on the main thread, where it emits.
     */
    private suspend fun evenementDe(action: () -> Unit): HomeEvent = withTimeout(TIMEOUT_MILLIS) {
        val prochain = async(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) { accueil.eventFlow.first() }
        withContext(Dispatchers.Main) { action() }
        prochain.await()
    }

    private class HorlogeFigee(private val maintenant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = maintenant
    }

    private class HorlogeMonotone : MonotonicClock {
        override fun elapsedMillis(): Long = 0L
    }

    private class KeystoreEnMemoire : VaultKeystore {
        private val cles = mutableMapOf<String, ByteArray>()

        override fun createKey(alias: String): Boolean {
            if (cles.containsKey(alias)) return false
            cles[alias] = SecretBytes.randomBytes(VaultParams.FOLDER_KEY_BYTES)
            return true
        }

        override fun seal(alias: String, plaintext: ByteArray): SealedByKeystore {
            val cle = cles[alias] ?: throw KeystoreUnavailableException()
            val nonce = VaultCrypto.newNonce()
            return SealedByKeystore(VaultCrypto.seal(cle, nonce, plaintext, ByteArray(0)), nonce)
        }

        override fun open(alias: String, sealed: SealedByKeystore): ByteArray {
            val cle = cles[alias] ?: throw KeystoreUnavailableException()
            return VaultCrypto.open(cle, sealed.nonce, sealed.ciphertext, ByteArray(0))
        }

        override fun deleteKey(alias: String) {
            cles.remove(alias)
        }

        override fun deleteKeysWithPrefix(prefix: String): Int {
            val vises = cles.keys.filter { it.startsWith(prefix) }
            vises.forEach(cles::remove)
            return vises.size
        }

        override fun hasKey(alias: String): Boolean = cles.containsKey(alias)
    }

    private companion object {
        const val PHRASE = "une phrase secrete de test 2026"
        const val TIMEOUT_MILLIS = 10_000L

        /** `LegacyPreferences.FLUTTER_PREFS_FILE`, private there — diverted by [IsolatedPreferencesContext]. */
        const val FICHIER_DE_PREFERENCES = "FlutterSharedPreferences"
    }
}
