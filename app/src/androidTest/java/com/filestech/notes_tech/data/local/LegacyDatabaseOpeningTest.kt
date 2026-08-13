package com.filestech.notes_tech.data.local

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.data.local.entity.FolderEntity
import com.filestech.notes_tech.data.local.entity.NoteEntity
import com.filestech.notes_tech.security.kek.KekFailure
import com.filestech.notes_tech.security.kek.KekRepository
import com.filestech.notes_tech.security.kek.KekSource
import com.filestech.notes_tech.security.kek.WritableKekSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **Critère de sortie de la phase 2** — cf. `docs/00-PLAN.md`.
 *
 * Prouve que l'application Kotlin ouvre et lit une base écrite par la version Flutter, sur un
 * appareil réel. C'est le seul test qui exerce d'un bout à l'autre ce dont tout le portage dépend :
 *
 * 1. le matériel de clé au format `x'<hex>'` est bien accepté comme **clé brute** par SQLCipher ;
 * 2. Room **adopte** une base dépourvue de `room_master_table` au lieu de la rejeter ;
 * 3. sa validation de schéma passe contre le DDL hérité réel ;
 * 4. l'index FTS5 existant répond, et continue de masquer les notes verrouillées ;
 * 5. les écritures de Room ne changent aucun `rowid`, donc ne cassent ni l'index ni les liens.
 *
 * ## Ce que ce test ne prouve pas
 *
 * Il fabrique la base avec une KEK **connue**, injectée. Il ne dit donc rien de l'acquisition réelle
 * de la clé chez un utilisateur qui migre — les couches ① et ② ne peuvent pas être exercées depuis
 * une build isolée, qui n'a accès ni aux préférences ni au Keystore de l'application d'origine.
 * Cf. `docs/06-ISOLATION-PENDANT-LE-CHANTIER.md` §2.
 *
 * ⚠️ `connectedAndroidTest` **efface les données de l'application** avant de s'exécuter.
 * Ce test ne tourne que sur le S9 de test.
 */
@RunWith(AndroidJUnit4::class)
class LegacyDatabaseOpeningTest {

    private lateinit var context: Context
    private lateinit var databaseFile: File
    private var database: NotesDatabase? = null

    /**
     * KEK fixe et connue. Une clé aléatoire rendrait un échec irreproductible, et c'est justement
     * sur ce chemin qu'on veut pouvoir rejouer un défaut à l'identique.
     */
    private val kek = ByteArray(SqlCipherRawKey.KEY_SIZE_BYTES) { (it * 11 + 3).toByte() }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // La fabrique de jeu d'essai ouvre SQLCipher avant que `NotesDatabaseFactory` n'ait eu
        // l'occasion de charger la bibliothèque native.
        System.loadLibrary("sqlcipher")
        databaseFile = File(context.cacheDir, "legacy-fixture/notes_tech.db")
        LegacyDatabaseFixture.create(databaseFile, SqlCipherRawKey.encode(kek))
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        LegacyDatabaseLocation.SIDECAR_SUFFIXES.forEach { File(databaseFile.path + it).delete() }
    }

    // ── 1. Ouverture ─────────────────────────────────────────────────────────

    @Test
    fun room_adopte_une_base_heritee_depourvue_de_room_master_table() {
        // Contrôle négatif d'abord : sans lui, on ne saurait pas si l'adoption a réellement eu
        // lieu ou si la table était là depuis le début.
        assertThat(tableExists("room_master_table")).isFalse()

        val db = openDatabase()

        assertThat(db.isOpen).isTrue()
        // Room a validé le schéma puis écrit son empreinte : c'est la preuve que la validation
        // est passée, pas seulement que le fichier s'est ouvert.
        assertThat(tableExists("room_master_table")).isTrue()
    }

    @Test
    fun la_cle_brute_hexadecimale_est_le_seul_format_qui_ouvre_la_base() {
        // Contrôle positif : la base s'ouvre avec le format `x'<hex>'`.
        assertThat(opensWith(SqlCipherRawKey.encode(kek))).isTrue()

        // Contrôle négatif — c'est lui qui donne sa valeur au précédent. Les 32 octets bruts
        // seraient traités par SQLCipher comme une passphrase et passés à PBKDF2 : la base ne
        // doit PAS s'ouvrir. Si cette assertion tombait, cela voudrait dire que le format n'a
        // aucune importance, et donc que le test du dessus ne prouve rien.
        assertThat(opensWith(kek.copyOf())).isFalse()
    }

    // ── 2. Lecture des données héritées ──────────────────────────────────────

    @Test
    fun les_dossiers_et_leurs_colonnes_de_coffre_traversent_room_sans_alteration(): Unit = runBlocking {
        val dao = openDatabase().folderDao()

        val vault = dao.findById(LegacyDatabaseFixture.Fixtures.FOLDER_VAULT)
        assertThat(vault).isNotNull()
        assertThat(vault!!.vaultMode).isEqualTo("passphrase")
        assertThat(vault.vaultAttempts).isEqualTo(2)
        // Les BLOB sont le point sensible : une conversion fautive ne se verrait qu'au moment de
        // déchiffrer un coffre, c'est-à-dire trop tard.
        assertThat(vault.vaultSalt).isEqualTo(ByteArray(16) { it.toByte() })
        assertThat(vault.vaultIv).isEqualTo(ByteArray(12) { (it + 1).toByte() })
        assertThat(vault.vaultVerifier).isEqualTo(ByteArray(32) { (it * 7).toByte() })

        val inbox = dao.findById(FolderEntity.INBOX_ID)
        assertThat(inbox).isNotNull()
        // Le libellé d'origine est conservé : `onOpen` fait un INSERT OR IGNORE, il ne réécrit pas.
        assertThat(inbox!!.name).isEqualTo("Boîte de réception")
    }

    @Test
    fun les_notes_en_clair_et_verrouillees_sont_lues_avec_leur_format(): Unit = runBlocking {
        val dao = openDatabase().noteDao()

        val plain = dao.findById(LegacyDatabaseFixture.Fixtures.NOTE_PLAIN)!!
        assertThat(plain.title).isEqualTo(LegacyDatabaseFixture.Fixtures.PLAIN_TITLE)
        assertThat(plain.isLocked).isFalse()
        assertThat(plain.pinned).isTrue()
        assertThat(plain.encVersion).isEqualTo(1)

        val locked = dao.findById(LegacyDatabaseFixture.Fixtures.NOTE_LOCKED)!!
        assertThat(locked.isLocked).isTrue()
        assertThat(locked.encryptedContent).hasLength(80)
        // Format 2 : titre ET contenu dans le blob, donc la colonne `title` est vide.
        assertThat(locked.encVersion).isEqualTo(2)
        assertThat(locked.title).isEmpty()
    }

    // ── 3. Index plein texte ─────────────────────────────────────────────────

    @Test
    fun la_recherche_fts5_repond_et_ignore_les_diacritiques(): Unit = runBlocking {
        val dao = openDatabase().noteSearchDao()

        // La note s'intitule « Réunion budget ». `remove_diacritics 2` doit rendre les deux
        // graphies équivalentes — c'est ce qui rend la recherche utilisable en français.
        val sansAccent = dao.search("reunion").first()
        assertThat(sansAccent.map(NoteEntity::id))
            .containsExactly(LegacyDatabaseFixture.Fixtures.NOTE_PLAIN)

        assertThat(dao.search("Réunion").first()).hasSize(1)
        // Recherche par préfixe : c'est ce qu'attend quelqu'un qui tape au fil de la frappe.
        assertThat(dao.search("budg").first()).hasSize(1)
    }

    @Test
    fun une_saisie_qui_n_est_que_ponctuation_ne_fait_pas_echouer_la_recherche(): Unit = runBlocking {
        val dao = openDatabase().noteSearchDao()

        // Sans neutralisation, ces saisies sont interprétées comme de la SYNTAXE FTS5 et lèvent
        // une SQLiteException. Autrement dit : l'utilisateur qui tape une apostrophe fait planter
        // sa recherche.
        assertThat(dao.search("   ").first()).isEmpty()
        assertThat(dao.search("\"").first()).isEmpty()
        assertThat(dao.search("(((").first()).isEmpty()
        assertThat(dao.search("NEAR").first()).isEmpty()
        assertThat(dao.search("budget*)").first()).hasSize(1)
        assertThat(dao.search("l'été").first()).isEmpty()
    }

    @Test
    fun une_note_verrouillee_ne_ressort_jamais_de_la_recherche(): Unit = runBlocking {
        val dao = openDatabase().noteSearchDao()

        // Ni par son titre, ni par son étiquette : les triggers insèrent des chaînes vides pour
        // toute note dont `encrypted_content` n'est pas nul.
        assertThat(dao.search(LegacyDatabaseFixture.Fixtures.LOCKED_TITLE).first()).isEmpty()
        assertThat(dao.search(LegacyDatabaseFixture.Fixtures.LOCKED_TAG).first()).isEmpty()
        assertThat(dao.search("bancaires").first()).isEmpty()
    }

    @Test
    fun meme_si_l_index_fuite_la_requete_filtre_la_note_verrouillee(): Unit = runBlocking {
        // On produit délibérément l'état fautif : le titre d'une note verrouillée est écrit dans
        // l'index en contournant les triggers. C'est ce qu'on obtiendrait sur une base dont les
        // triggers seraient absents, anciens ou abîmés — ils ont été écrits par une AUTRE
        // application et rien ici ne les recrée à l'ouverture.
        LegacyDatabaseFixture.leakLockedNoteIntoIndex(databaseFile, SqlCipherRawKey.encode(kek))

        // Contrôle que la fuite a bien eu lieu : sans cette assertion, le test réussirait tout
        // aussi bien si `leakLockedNoteIntoIndex` ne faisait rien, et ne prouverait alors rien.
        assertThat(indexContains(LegacyDatabaseFixture.Fixtures.LOCKED_TITLE)).isTrue()

        val resultat = openDatabase().noteSearchDao()
            .search(LegacyDatabaseFixture.Fixtures.LOCKED_TITLE).first()

        // La seconde ligne de défense tient : la requête filtre sur `encrypted_content IS NULL`,
        // donc la promesse de non-divulgation ne dépend plus de données écrites par un autre
        // programme.
        assertThat(resultat).isEmpty()
    }

    // ── 4. Les écritures de Room ne cassent rien ─────────────────────────────

    @Test
    fun une_note_inseree_par_room_entre_dans_l_index_fts5(): Unit = runBlocking {
        val db = openDatabase()
        db.noteDao().insert(
            NoteEntity(
                id = "note-neuve",
                title = "Chantier portage",
                content = "Migration vers Kotlin natif",
                encryptedContent = null,
                folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
                tags = "",
                pinned = false,
                favorite = false,
                archived = false,
                trashedAt = null,
                createdAt = 1L,
                updatedAt = 1L,
                encVersion = 1,
            ),
        )

        // Prouve que les triggers hérités se déclenchent bien sur les écritures de Room — ils sont
        // hors de son graphe d'entités, donc rien ne le garantissait a priori.
        assertThat(db.noteSearchDao().search("chantier").first().map(NoteEntity::id))
            .containsExactly("note-neuve")
    }

    @Test
    fun une_mise_a_jour_par_room_preserve_le_rowid_et_les_liens(): Unit = runBlocking {
        val db = openDatabase()
        val avant = rowIdOf(LegacyDatabaseFixture.Fixtures.NOTE_PLAIN)
        assertThat(linkCountFrom(LegacyDatabaseFixture.Fixtures.NOTE_PLAIN)).isEqualTo(1)

        val note = db.noteDao().findById(LegacyDatabaseFixture.Fixtures.NOTE_PLAIN)!!
        val touchees = db.noteDao().updateEditableFields(
            id = note.id,
            title = "Réunion budget révisée",
            content = note.content,
            tags = note.tags,
            updatedAt = note.updatedAt + 1,
        )
        assertThat(touchees).isEqualTo(1)

        // Si un `INSERT OR REPLACE` s'était glissé dans le DAO, le rowid changerait — et la
        // cascade `ON DELETE` aurait emporté les backlinks au passage, silencieusement.
        assertThat(rowIdOf(LegacyDatabaseFixture.Fixtures.NOTE_PLAIN)).isEqualTo(avant)
        assertThat(linkCountFrom(LegacyDatabaseFixture.Fixtures.NOTE_PLAIN)).isEqualTo(1)
        assertThat(db.noteSearchDao().search("révisée").first()).hasSize(1)
    }

    // ── 5. L'invariant du coffre est tenu par la base ────────────────────────

    @Test
    fun une_note_verrouillee_ne_peut_PAS_etre_ecrite_en_clair(): Unit = runBlocking {
        val db = openDatabase()
        val avant = db.noteDao().findById(LegacyDatabaseFixture.Fixtures.NOTE_LOCKED)!!
        assertThat(avant.isLocked).isTrue()

        // Le geste exact qui a déjà détruit une note de coffre dans l'application publiée :
        // l'éditeur détient l'éphémère DÉCHIFFRÉE et écrit son contenu en clair. Le code Flutter
        // documente l'incident (`notes_dao.dart:234-241`) — épingler une telle note effaçait son
        // blob chiffré, définitivement, sur un tap d'icône.
        val touchees = db.noteDao().updateEditableFields(
            id = LegacyDatabaseFixture.Fixtures.NOTE_LOCKED,
            title = "Codes bancaires",
            content = "1234 5678 9012 3456",
            tags = "",
            updatedAt = 9_999L,
        )

        // La garde SQL `AND encrypted_content IS NULL` rend la requête inopérante. Ce n'est pas un
        // avertissement qu'il faut se rappeler : c'est la base qui refuse.
        assertThat(touchees).isEqualTo(0)

        val apres = db.noteDao().findById(LegacyDatabaseFixture.Fixtures.NOTE_LOCKED)!!
        assertThat(apres.encryptedContent).isEqualTo(avant.encryptedContent)
        assertThat(apres.content).isEmpty()
        assertThat(apres.title).isEmpty()
        // Et rien n'a fuité dans l'index plein texte au passage.
        assertThat(db.noteSearchDao().search("bancaires").first()).isEmpty()
    }

    @Test
    fun epingler_une_note_de_coffre_ne_touche_ni_son_contenu_ni_son_blob(): Unit = runBlocking {
        val db = openDatabase()
        val avant = db.noteDao().findById(LegacyDatabaseFixture.Fixtures.NOTE_LOCKED)!!

        val touchees = db.noteDao().updateFlags(
            id = LegacyDatabaseFixture.Fixtures.NOTE_LOCKED,
            updatedAt = 9_999L,
            pinned = true,
        )

        assertThat(touchees).isEqualTo(1)
        val apres = db.noteDao().findById(LegacyDatabaseFixture.Fixtures.NOTE_LOCKED)!!
        assertThat(apres.pinned).isTrue()
        // Les drapeaux non passés restent inchangés — `COALESCE(:x, x)`.
        assertThat(apres.favorite).isEqualTo(avant.favorite)
        assertThat(apres.archived).isEqualTo(avant.archived)
        // Et surtout : la protection est intacte.
        assertThat(apres.encryptedContent).isEqualTo(avant.encryptedContent)
        assertThat(apres.content).isEmpty()
    }

    @Test
    fun la_recherche_ignore_les_notes_archivees(): Unit = runBlocking {
        val db = openDatabase()
        assertThat(db.noteSearchDao().search("reunion").first()).hasSize(1)

        db.noteDao().updateFlags(
            id = LegacyDatabaseFixture.Fixtures.NOTE_PLAIN,
            updatedAt = 9_999L,
            archived = true,
        )

        // `AND n.archived = 0` — la version Flutter filtre ainsi (`notes_dao.dart:471`). Sans ce
        // filtre, archiver une note ne la retirait pas des résultats de recherche.
        assertThat(db.noteSearchDao().search("reunion").first()).isEmpty()
    }

    // ── 6. Refus sans destruction ────────────────────────────────────────────

    @Test
    fun sans_kek_et_avec_une_base_presente_l_ouverture_est_refusee_et_la_base_intacte() {
        val empreinteAvant = databaseFile.readBytes()

        val vide = object : KekSource {
            override val name = "vide"
            override fun load(): ByteArray? = null
        }
        val repository = KekRepository(
            sources = listOf(vide),
            primary = RefusingWritableSource,
            databaseExists = { true },
        )

        assertThrows(KekFailure.NoKeyForExistingDatabase::class.java) {
            NotesDatabaseFactory(repository, databaseFile = { databaseFile }).build(context)
        }

        // L'assertion qui compte. L'écran d'erreur promet à l'utilisateur que ses notes sont
        // intactes ; cette ligne est ce qui rend la promesse vraie.
        assertThat(databaseFile.exists()).isTrue()
        assertThat(databaseFile.readBytes()).isEqualTo(empreinteAvant)
    }

    // ── Utilitaires ──────────────────────────────────────────────────────────

    private fun openDatabase(): NotesDatabase {
        val source = FixedKekSource(kek)
        val repository = KekRepository(
            sources = listOf(source),
            primary = source,
            databaseExists = { databaseFile.exists() },
        )
        return NotesDatabaseFactory(
            kekRepository = repository,
            nowMillis = { FIXED_NOW },
            databaseFile = { databaseFile },
        ).build(context).also { database = it }
    }

    private fun opensWith(key: ByteArray): Boolean = try {
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            key,
            null,
            SQLiteDatabase.OPEN_READONLY,
            null,
        ).use { db ->
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        }
        true
    } catch (_: Throwable) {
        false
    }

    private fun tableExists(name: String): Boolean =
        rawQueryLong("SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?", name) > 0

    private fun rowIdOf(noteId: String): Long = rawQueryLong("SELECT rowid FROM notes WHERE id = ?", noteId)

    private fun linkCountFrom(noteId: String): Long =
        rawQueryLong("SELECT count(*) FROM note_links WHERE source_id = ?", noteId)

    /** Interroge l'index FTS5 **directement**, sans la garde de la requête applicative. */
    private fun indexContains(terme: String): Boolean =
        rawQueryLong("SELECT count(*) FROM notes_fts WHERE notes_fts MATCH ?", "\"$terme\"") > 0

    /**
     * Interroge la base **hors de Room**, avec sa propre connexion.
     *
     * Délibéré : vérifier le `rowid` ou l'existence d'une table à travers la couche qu'on teste
     * reviendrait à lui demander de se juger elle-même.
     */
    private fun rawQueryLong(sql: String, vararg args: String): Long {
        val key = SqlCipherRawKey.encode(kek)
        return SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            key,
            null,
            SQLiteDatabase.OPEN_READONLY,
            null,
        ).use { db ->
            db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        }
    }

    private class FixedKekSource(private val kek: ByteArray) : WritableKekSource {
        override val name = "test"
        override fun load(): ByteArray = kek.copyOf()
        override fun store(kek: ByteArray) = Unit
        override fun replaceKeyAndStore(kek: ByteArray) = Unit
    }

    /** Toute écriture est une erreur sur le chemin testé : le test doit échouer, pas absorber. */
    private object RefusingWritableSource : WritableKekSource {
        override val name = "refus"
        override fun load(): ByteArray? = null
        override fun store(kek: ByteArray) = error("aucune écriture ne doit avoir lieu ici")
        override fun replaceKeyAndStore(kek: ByteArray) = error("aucune écriture ne doit avoir lieu ici")
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
    }
}
