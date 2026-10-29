package com.filestech.notes_tech.data.sauvegarde

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.data.local.DatabaseProvider
import com.filestech.notes_tech.data.local.LegacyDatabaseFixture
import com.filestech.notes_tech.data.local.LegacyDatabaseLocation
import com.filestech.notes_tech.data.local.NotesDatabaseFactory
import com.filestech.notes_tech.data.local.SqlCipherRawKey
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import com.filestech.notes_tech.data.repository.FoldersRepository
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.security.kek.KekRepository
import com.filestech.notes_tech.security.kek.WritableKekSource
import com.filestech.notes_tech.security.sauvegarde.FichierDeSauvegarde
import com.filestech.notes_tech.security.vault.FolderVaultService
import com.filestech.notes_tech.security.vault.KeystoreEnMemoire
import com.filestech.notes_tech.security.vault.MonotonicClock
import com.filestech.notes_tech.security.vault.VaultParams
import com.filestech.notes_tech.security.vault.VaultSessions
import com.filestech.notes_tech.security.vault.VaultWipeJournal
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * **The encrypted backup, both ways, on real SQLCipher** (3.2.0, D-029 to D-031): a passphrase vault
 * and a PIN vault in it, the PIN vault restored with a new PIN — and what a wrong secret or a damaged
 * file leaves behind: nothing.
 *
 * The Keystore is in memory ([KeystoreEnMemoire]): a PIN vault needs no screen lock here. Argon2id
 * at 64 MiB runs for real, once per pass — about a second each on an emulator.
 */
@RunWith(AndroidJUnit4::class)
class SauvegardeChiffreeTest {

    private lateinit var context: Context
    private lateinit var databaseFile: File
    private lateinit var fichier: File
    private val kek = ByteArray(SqlCipherRawKey.KEY_SIZE_BYTES) { (it * 13 + 7).toByte() }
    private val horloge = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)

    private lateinit var provider: DatabaseProvider
    private lateinit var dossiers: FoldersRepository
    private lateinit var notes: NotesRepository
    private lateinit var sessions: VaultSessions
    private lateinit var keystore: KeystoreEnMemoire
    private lateinit var coffres: FolderVaultService
    private lateinit var sauvegarde: SauvegardeChiffree
    private lateinit var restauration: RestaurationDeSauvegarde

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        System.loadLibrary("sqlcipher")
        databaseFile = File(context.cacheDir, "backup-fixture/notes_tech.db")
        LegacyDatabaseFixture.create(databaseFile, SqlCipherRawKey.encode(kek))
        fichier = File(context.cacheDir, "backup-fixture/test.ntbackup").also { it.delete() }

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
                databaseFile = { databaseFile },
            ),
            ioDispatcher = Dispatchers.IO,
        )
        sessions = VaultSessions(MonotonicClock { 0L })
        keystore = KeystoreEnMemoire()
        val journal = VaultWipeJournal(LegacyPreferences(context))
        coffres = FolderVaultService(provider, keystore, sessions, journal, horloge)
        dossiers = FoldersRepository(provider, horloge)
        notes = NotesRepository(provider, dossiers, coffres, coffres, horloge)
        sauvegarde = SauvegardeChiffree(context, provider, sessions, horloge)
        restauration = RestaurationDeSauvegarde(context, notes, coffres)
    }

    @After
    fun tearDown(): Unit = runBlocking {
        sessions.lockAll()
        provider.close()
        LegacyDatabaseLocation.SIDECAR_SUFFIXES.forEach { File(databaseFile.path + it).delete() }
        fichier.delete()
    }

    private val document: Uri get() = Uri.fromFile(fichier)

    /** A backup made as the screen makes it: previewed, written, then put into the document. */
    private suspend fun creer(): SauvegardeChiffree.Creation =
        sauvegarde.deposer(sauvegarde.preparer(CODE, sauvegarde.apercu().idsDesCoffresACodeOuverts), document)

    /** A restore made as the screen makes it: the trial, then the writing held to the trial's bytes. */
    private suspend fun restaurer(codes: Map<String, String>): RestaurationDeSauvegarde.Resultat =
        restauration.restaurer(document, CODE, codes, restauration.essayer(document, CODE))

    /** A PIN vault, open, holding one note sealed under its key. */
    private suspend fun unCoffreACode(): Pair<String, String> {
        val dossier = dossiers.create("Codes")
        coffres.createPinVault(dossier.id, CODE_DU_COFFRE)
        val note = notes.create(folderId = dossier.id, title = "Digicode", content = "4521B")
        return dossier.id to note.id
    }

    @Test
    fun une_sauvegarde_restaure_ce_qui_a_ete_perdu_et_un_coffre_a_code_revient_avec_un_nouveau_code(): Unit =
        runBlocking {
            val (coffre, noteDuCoffre) = unCoffreACode()
            val enClair = notes.create(
                folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
                title = "Courses",
                content = "pain",
            )

            val creee = creer()
            assertThat(creee.omises).isEqualTo(0)

            // Lost: a note in clear, and the PIN vault with its note and its Keystore key.
            notes.deletePermanently(enClair.id)
            sessions.lock(coffre)
            dossiers.delete(coffre)
            coffres.deletePinKey(coffre)

            val essai = restauration.essayer(document, CODE)
            assertThat(essai.coffresACode.map { it.id }).containsExactly(coffre)
            assertThat(essai.notesAjoutees).isEqualTo(2)
            assertThat(notes.find(enClair.id)).isNull()

            val fait = restauration.restaurer(document, CODE, mapOf(coffre to NOUVEAU_CODE), essai)

            assertThat(fait.notesAjoutees).isEqualTo(2)
            assertThat(fait.notesIgnorees).isEmpty()
            assertThat(notes.find(enClair.id)?.content).isEqualTo("pain")
            // The vault opens with the PIN chosen at the restore, its note sealed under the same key.
            coffres.unlockWithPin(coffre, NOUVEAU_CODE)
            val ouverte = coffres.decrypt(requireNotNull(notes.find(noteDuCoffre)))
            assertThat(ouverte.title).isEqualTo("Digicode")
            assertThat(ouverte.content).isEqualTo("4521B")
            // The passphrase vault and its sealed note were there all along: not written twice.
            assertThat(fait.notesDejaLa).isAtLeast(1)
        }

    /** 🔴 Nothing unauthenticated is committed, and no Keystore key outlives a rolled back restore. */
    @Test
    fun un_secret_faux_ou_une_fin_de_fichier_changee_ne_restaurent_rien(): Unit = runBlocking {
        val (coffre, _) = unCoffreACode()
        creer()
        sessions.lock(coffre)
        dossiers.delete(coffre)
        coffres.deletePinKey(coffre)
        val avant = notes.listAllAlive().map { it.id }.toSet()
        val essai = restauration.essayer(document, CODE)

        val faux = runCatching { restauration.restaurer(document, AUTRE_CODE, mapOf(coffre to NOUVEAU_CODE), essai) }
        // The last byte is the tag's: every note parses, every row is written, and the end refuses it.
        val octets = fichier.readBytes()
        fichier.writeBytes(octets.copyOf().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() })
        val abime = runCatching { restauration.restaurer(document, CODE, mapOf(coffre to NOUVEAU_CODE), essai) }

        assertThat(faux.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(abime.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(notes.listAllAlive().map { it.id }.toSet()).isEqualTo(avant)
        assertThat(dossiers.find(coffre)).isNull()
        assertThat(keystore.hasKey(VaultParams.pinKeystoreAlias(coffre))).isFalse()
    }

    @Test
    fun restaurer_deux_fois_n_ajoute_rien_la_seconde(): Unit = runBlocking {
        notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Courses", content = "pain")
        creer()
        val avant = notes.listAllAlive().size

        val fait = restaurer(emptyMap())

        assertThat(fait.notesAjoutees).isEqualTo(0)
        assertThat(fait.notesACote).isEqualTo(0)
        assertThat(fait.dossiersCrees).isEqualTo(0)
        assertThat(notes.listAllAlive()).hasSize(avant)
    }

    /** D-030: a closed PIN vault is named before the file is written, then left out with its notes. */
    @Test
    fun un_coffre_a_code_ferme_est_nomme_puis_omis_avec_ses_notes(): Unit = runBlocking {
        val (coffre, _) = unCoffreACode()
        sessions.lock(coffre)

        val apercu = sauvegarde.apercu()
        val creee = creer()

        assertThat(apercu.coffresACodeFermes).containsExactly("Codes")
        assertThat(creee.omises).isEqualTo(1)
        assertThat(restauration.essayer(document, CODE).coffresACode).isEmpty()
    }

    /**
     * 🔴 The system picker sends the app to the background, where every vault locks: the backup is
     * written before it, and an open PIN vault locked in between keeps its notes in the file. Written
     * after the picker, the backup of a S9 held none (2026-10-29).
     */
    @Test
    fun un_coffre_a_code_ferme_pendant_le_selecteur_garde_ses_notes_dans_la_sauvegarde(): Unit = runBlocking {
        val (coffre, _) = unCoffreACode()

        val preparee = sauvegarde.preparer(CODE, sauvegarde.apercu().idsDesCoffresACodeOuverts)
        sessions.lockAll() // what the picker does to the app
        val creee = sauvegarde.deposer(preparee, document)

        assertThat(creee.omises).isEqualTo(0)
        assertThat(preparee.fichier.exists()).isFalse()
        dossiers.delete(coffre)
        coffres.deletePinKey(coffre)
        val essai = restauration.essayer(document, CODE)
        assertThat(essai.coffresACode.map { it.id }).containsExactly(coffre)
        assertThat(essai.notesAjoutees).isEqualTo(1)
    }

    /**
     * Everything lost, as on a new phone: a passphrase vault and its sealed note, a subfolder and its
     * note in the trash, a PIN vault — all back, the passphrase vault opening with its own passphrase.
     */
    @Test
    fun ce_qui_est_perdu_revient_coffre_a_passphrase_sous_dossier_et_corbeille_compris(): Unit = runBlocking {
        val parent = dossiers.create("Projets")
        val enfant = dossiers.create("2026", parentId = parent.id)
        val jetee = notes.create(folderId = enfant.id, title = "Brouillon", content = "à jeter")
        notes.moveToTrash(jetee.id)
        val journal = dossiers.create("Journal")
        coffres.createPassphraseVault(journal.id, PASSPHRASE)
        val intime = notes.create(folderId = journal.id, title = "Lundi", content = "rien de neuf")
        val (coffre, _) = unCoffreACode()
        assertThat(creer().omises).isEqualTo(0)

        sessions.lockAll()
        listOf(enfant.id, parent.id, journal.id, coffre).forEach { dossiers.delete(it) }
        coffres.deletePinKey(coffre)
        val fait = restaurer(mapOf(coffre to NOUVEAU_CODE))

        assertThat(fait.dossiersCrees).isEqualTo(4)
        assertThat(fait.notesIgnorees).isEmpty()
        assertThat(fait.notesALaCorbeille).isEqualTo(1)
        assertThat(dossiers.find(enfant.id)?.parentId).isEqualTo(parent.id)
        assertThat(notes.find(jetee.id)?.isTrashed).isTrue()
        coffres.unlockWithPassphrase(journal.id, PASSPHRASE)
        assertThat(coffres.decrypt(requireNotNull(notes.find(intime.id))).content).isEqualTo("rien de neuf")
    }

    /**
     * The other side of the picker: a PIN vault the preview said open, locked BEFORE the writing (the
     * user left the app to note the code) — nothing written, the vault named, no file left waiting.
     */
    @Test
    fun un_coffre_annonce_ouvert_puis_referme_avant_l_ecriture_arrete_la_sauvegarde(): Unit = runBlocking {
        unCoffreACode()
        val apercu = sauvegarde.apercu()
        sessions.lockAll()

        val refus = runCatching { sauvegarde.preparer(CODE, apercu.idsDesCoffresACodeOuverts) }.exceptionOrNull()

        assertThat((refus as? SauvegardeChiffree.CoffreRefermeException)?.nom).isEqualTo("Codes")
        assertThat(SauvegardeChiffree.repertoireDAttente(context).listFiles().orEmpty()).isEmpty()
    }

    /** The writing pass is held to the trial's bytes: another valid backup in their place writes nothing. */
    @Test
    fun un_fichier_change_entre_l_essai_et_l_ecriture_ne_restaure_rien(): Unit = runBlocking {
        val premiere = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Un", content = "1")
        creer()
        val essai = restauration.essayer(document, CODE)
        notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Deux", content = "2")
        creer() // another valid backup, same code, in the file the trial read
        notes.deletePermanently(premiere.id)
        val avant = notes.listAllAlive().map { it.id }.toSet()

        val refus = runCatching { restauration.restaurer(document, CODE, emptyMap(), essai) }.exceptionOrNull()

        assertThat(refus).isInstanceOf(RestaurationDeSauvegarde.FichierChangeException::class.java)
        assertThat(notes.listAllAlive().map { it.id }.toSet()).isEqualTo(avant)
    }

    /** Before any secret is asked: what is not a backup, or one of a newer version, is refused as such. */
    @Test
    fun l_examen_refuse_sans_secret_ce_qui_n_est_pas_une_sauvegarde_lisible_ici(): Unit = runBlocking {
        creer()
        val vraie = fichier.readBytes()
        suspend fun refus(octets: ByteArray): FichierDeSauvegarde.Refus? {
            fichier.writeBytes(octets)
            val echec = runCatching { restauration.examiner(document) }.exceptionOrNull()
            return (echec as? FichierDeSauvegarde.SauvegardeRefuseeException)?.refus
        }

        assertThat(refus(vraie)).isNull()
        assertThat(refus(ByteArray(100) { 'x'.code.toByte() })).isEqualTo(FichierDeSauvegarde.Refus.PAS_UNE_SAUVEGARDE)
        assertThat(refus(vraie.copyOf(10))).isEqualTo(FichierDeSauvegarde.Refus.PAS_UNE_SAUVEGARDE)
        assertThat(refus(vraie.copyOf().also { it[9] = 2 })).isEqualTo(FichierDeSauvegarde.Refus.VERSION_INCONNUE)
    }

    private companion object {
        const val CODE = "K7QM-T2XB-0R1A-ZZ9P-HHJK-MN4V"
        const val PASSPHRASE = "un jardin bleu sous la pluie"
        const val CODE_DU_COFFRE = "1234"
        const val NOUVEAU_CODE = "5678"
        const val AUTRE_CODE = "AUTR-ECOD-E000-0000-0000-0000"
    }
}
