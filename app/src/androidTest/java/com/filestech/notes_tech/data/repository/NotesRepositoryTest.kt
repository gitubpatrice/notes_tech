package com.filestech.notes_tech.data.repository

import android.content.Context
import android.os.Looper
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.data.local.DatabaseProvider
import com.filestech.notes_tech.data.local.LegacyDatabaseFixture
import com.filestech.notes_tech.data.local.LegacyDatabaseLocation
import com.filestech.notes_tech.data.local.NotesDatabase
import com.filestech.notes_tech.data.local.NotesDatabaseFactory
import com.filestech.notes_tech.data.local.SqlCipherRawKey
import com.filestech.notes_tech.domain.model.EncryptedBody
import com.filestech.notes_tech.domain.model.EncryptedFormat
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.repository.UnavailableVaultOpener
import com.filestech.notes_tech.domain.repository.UnavailableVaultSealer
import com.filestech.notes_tech.domain.repository.VaultLockedException
import com.filestech.notes_tech.domain.repository.VaultOpener
import com.filestech.notes_tech.domain.repository.VaultSealer
import com.filestech.notes_tech.security.kek.KekRepository
import com.filestech.notes_tech.security.kek.WritableKekSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Les écritures de notes, exercées contre du vrai SQLCipher et le vrai schéma hérité.
 *
 * ## Pourquoi ces tests sont instrumentés et non sur la JVM
 *
 * Presque tout ce qu'ils vérifient est porté par la **base**, pas par le code Kotlin : la garde
 * `AND encrypted_content IS NULL`, les cascades de clés étrangères, l'atomicité d'une transaction,
 * le `content = ''` écrit en dur. Un test à double factice vérifierait que les doubles se comportent
 * comme on croit que SQLite se comporte.
 *
 * ## Ce qu'ils cherchent à faire échouer
 *
 * Chaque test ci-dessous peut échouer pour une raison précise, et la plupart correspondent à un
 * défaut réellement survenu — dans l'application publiée, ou dans une première version de ce
 * portage. Un test qui ne peut pas échouer ne prouve rien.
 */
@RunWith(AndroidJUnit4::class)
class NotesRepositoryTest {

    private lateinit var context: Context
    private lateinit var databaseFile: File
    private var opened: NotesDatabase? = null
    private val kek = ByteArray(SqlCipherRawKey.KEY_SIZE_BYTES) { (it * 11 + 3).toByte() }

    private val horloge = HorlogeReglable(Instant.ofEpochMilli(1_700_000_000_000L))
    private var scelleur: VaultSealer = UnavailableVaultSealer()
    private var ouvreur: VaultOpener = UnavailableVaultOpener()

    private lateinit var provider: DatabaseProvider
    private lateinit var dossiers: FoldersRepository
    private lateinit var notes: NotesRepository
    private lateinit var liens: LinksRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        System.loadLibrary("sqlcipher")
        databaseFile = File(context.cacheDir, "repository-fixture/notes_tech.db")
        LegacyDatabaseFixture.create(databaseFile, SqlCipherRawKey.encode(kek))

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
        dossiers = FoldersRepository(provider, horloge)
        notes = NotesRepository(provider, dossiers, ScelleurDelegue { scelleur }, OuvreurDelegue { ouvreur }, horloge)
        liens = LinksRepository(provider)
    }

    @After
    fun tearDown(): Unit = runBlocking {
        provider.close()
        opened = null
        LegacyDatabaseLocation.SIDECAR_SUFFIXES.forEach { File(databaseFile.path + it).delete() }
    }

    // ── Indexation des liens dans la transaction d'écriture ──────────────────

    @Test
    fun creer_une_note_indexe_ses_liens_dans_la_meme_transaction(): Unit = runBlocking {
        val note = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
            title = "Compte rendu",
            content = "Voir [[Réunion budget]] et [[Sujet inconnu]].",
        )

        val sortants = liens.observeOutgoing(note.id).first()
        assertThat(sortants.map { it.targetTitle }).containsExactly("Réunion budget", "Sujet inconnu").inOrder()
        // Le premier vise une note qui existe : il est résolu. Le second reste fantôme.
        assertThat(sortants[0].targetId).isEqualTo(LegacyDatabaseFixture.Fixtures.NOTE_PLAIN)
        assertThat(sortants[1].targetId).isNull()
    }

    // ───────────────────────────────────────────────────────────────────────────────────────────
    // 🔴 L'asymétrie des archives — écart relevé dans l'application publiée, REPRODUIT ici
    // ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * **Une note archivée quitte son dossier et reste dans « toutes les notes ».**
     *
     * L'asymétrie vient de l'application publiée : `notes_dao.dart:156` filtre sur
     * `trashed_at IS NULL` seul, quand `listByFolder` (ligne 59) ajoute `archived = 0`. Elle était
     * notée comme un **écart à trancher**, et ce n'en est pas un : c'est la définition même d'une
     * archive — sortie de sa liste, toujours atteignable ailleurs. Le contraire — la filtrer des
     * deux côtés — rendrait la note **invisible partout**, sans aucun écran pour la retrouver, alors
     * qu'aucun geste ne permet de la désarchiver.
     *
     * ## ⚠️⚠️ Pourquoi ce test existe alors que rien ne peut archiver
     *
     * `NotesRepository.setArchived` **n'a aucun appelant** — comme dans l'application publiée, dont
     * aucun écran n'archive non plus : la colonne vaut `0` partout, et l'asymétrie est donc
     * aujourd'hui **inobservable**. C'est précisément ce qui la rendait fragile : une règle que rien
     * n'exerce est une règle qu'un refactor déplace sans que personne ne le voie. Le jour où
     * l'archivage arrive, il héritera d'un comportement **décidé**, et non d'un accident de deux
     * requêtes écrites à deux moments.
     *
     * ⚠️ Le chemin d'écriture n'est **pas** du code mort à supprimer : il est la surface que
     * l'application publiée expose aussi. Le retirer ferait divergence, et le critère de sortie de
     * la phase 8 est la parité.
     */
    @Test
    fun une_note_archivee_quitte_son_dossier_et_reste_dans_toutes_les_notes(): Unit = runBlocking {
        val note = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
            title = "À classer plus tard",
            content = "Corps.",
        )
        // ⚠️ Le témoin AVANT l'archivage : sans lui, un test qui ne trouve la note nulle part
        // passerait pour une preuve d'archivage alors qu'il prouverait un échec de création.
        assertThat(dansLeDossier().map { it.id }).contains(note.id)
        assertThat(dansToutesLesNotes().map { it.id }).contains(note.id)

        assertThat(notes.setArchived(note.id, archived = true)).isTrue()

        assertThat(dansLeDossier().map { it.id }).doesNotContain(note.id)
        assertThat(dansToutesLesNotes().map { it.id }).contains(note.id)
    }

    /**
     * ⚠️ **Le retour en arrière compte autant** : `archived = false` la remet dans son dossier.
     *
     * Sans ce cas, une implémentation qui déplacerait la note ou perdrait son `folder_id` en
     * l'archivant passerait le test précédent — la note serait bien absente de son dossier, mais
     * pour la mauvaise raison, et elle n'y reviendrait jamais.
     */
    @Test
    fun desarchiver_une_note_la_ramene_dans_son_dossier(): Unit = runBlocking {
        val note = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
            title = "Aller-retour",
            content = "Corps.",
        )
        notes.setArchived(note.id, archived = true)
        assertThat(dansLeDossier().map { it.id }).doesNotContain(note.id)

        assertThat(notes.setArchived(note.id, archived = false)).isTrue()

        assertThat(dansLeDossier().map { it.id }).contains(note.id)
    }

    /**
     * ⚠️ `observeInFolder` sait **inclure** les archives sur demande, et personne ne le demande.
     *
     * Le paramètre existe, il est câblé jusqu'au SQL, et le mesurer ici évite qu'il devienne un
     * drapeau qu'on croit actif — ou qu'un refactor inverse sans rien casser de visible.
     */
    @Test
    fun le_dossier_sait_inclure_ses_archives_quand_on_le_demande(): Unit = runBlocking {
        val note = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
            title = "Sur demande",
            content = "Corps.",
        )
        notes.setArchived(note.id, archived = true)

        assertThat(dansLeDossier(archives = true).map { it.id }).contains(note.id)
        assertThat(dansLeDossier(archives = false).map { it.id }).doesNotContain(note.id)
    }

    private suspend fun dansLeDossier(archives: Boolean = false) = notes.observeInFolder(
        folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
        includeArchived = archives,
    ).first()

    private suspend fun dansToutesLesNotes() = notes.observeAllAlive().first()

    /**
     * ⚠️ Le cas que le raccourci « pas de `[[` dans le texte » pourrait manquer.
     *
     * Une note dont on **retire** le dernier lien doit perdre ses liens. Une implémentation qui
     * sortirait tôt en constatant l'absence de `[[` les laisserait en place, et les rétroliens
     * continueraient de désigner une note qui ne cite plus rien.
     */
    @Test
    fun retirer_le_dernier_lien_efface_les_liens_de_la_note(): Unit = runBlocking {
        val note = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
            title = "Brouillon",
            content = "Voir [[Réunion budget]].",
        )
        assertThat(liens.observeOutgoing(note.id).first()).hasSize(1)

        notes.saveEdits(note.id, title = "Brouillon", content = "Plus aucun lien.", tags = emptyList())

        assertThat(liens.observeOutgoing(note.id).first()).isEmpty()
    }

    @Test
    fun un_lien_ecrit_avant_sa_cible_se_resout_a_la_creation_de_celle_ci(): Unit = runBlocking {
        val source = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
            title = "Source",
            content = "Un renvoi vers [[Rétrospective]].",
        )
        assertThat(liens.observeOutgoing(source.id).first().single().targetId).isNull()

        val cible = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Rétrospective")

        assertThat(liens.observeOutgoing(source.id).first().single().targetId).isEqualTo(cible.id)
        assertThat(liens.observeBacklinks(cible).first().map(Note::id)).containsExactly(source.id)
    }

    @Test
    fun renommer_une_cible_detache_les_liens_qui_la_visaient(): Unit = runBlocking {
        val cible = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Rétrospective")
        val source = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
            title = "Source",
            content = "Un renvoi vers [[Rétrospective]].",
        )
        assertThat(liens.observeOutgoing(source.id).first().single().targetId).isEqualTo(cible.id)

        notes.saveEdits(cible.id, title = "Bilan trimestriel", content = "", tags = emptyList())

        assertThat(liens.observeOutgoing(source.id).first().single().targetId).isNull()
    }

    /**
     * ⚠️ Un rétrolien se trouve aussi par **titre**, pas seulement par identifiant.
     *
     * C'est le défaut qu'une première version de `NoteLinkDao.backlinks` portait : elle ne joignait
     * que sur `target_id`, donc un lien écrit avant sa cible n'apparaissait pas dans les rétroliens
     * de celle-ci tant qu'aucune réindexation n'était passée. Invisible sans écrire le lien
     * **avant** la note.
     */
    @Test
    fun les_retroliens_incluent_les_liens_fantomes_qui_visent_le_titre(): Unit = runBlocking {
        val source = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
            title = "Source",
            content = "Un renvoi vers [[Note à venir]].",
        )
        // La cible est fabriquée SANS passer par le repository : aucune résolution n'a lieu, le
        // lien reste donc fantôme. C'est l'état qu'on veut observer.
        val db = provider.get()
        db.noteWriteDao().insert(
            com.filestech.notes_tech.data.local.entity.NoteEntity(
                id = "cible-brute",
                title = "Note à venir",
                content = "",
                encryptedContent = null,
                folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
                tags = "",
                pinned = false,
                favorite = false,
                archived = false,
                trashedAt = null,
                createdAt = horloge.millis(),
                updatedAt = horloge.millis(),
                encVersion = 1,
            ),
        )

        val cible = notes.find("cible-brute")!!
        assertThat(liens.observeBacklinks(cible).first().map(Note::id)).containsExactly(source.id)
    }

    @Test
    fun une_note_qui_se_cite_elle_meme_ne_produit_pas_de_lien_vers_elle_meme(): Unit = runBlocking {
        val note = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
            title = "Journal",
            content = "Je me cite : [[Journal]].",
        )

        assertThat(liens.observeOutgoing(note.id).first().single().targetId).isNull()
    }

    /**
     * ⚠️ Quirk hérité, reproduit délibérément : deux notes de même titre normalisé résolvent vers
     * la **moins récemment modifiée**.
     *
     * La table d'appariement de l'application publiée est un littéral de map construit sur une liste
     * triée `updated_at DESC`, où les clés en double sont écrasées par la dernière rencontrée. Ce
     * test le fige : changer le tri de `titlesForLinking`, ou remplacer `associate` par une
     * construction qui garde la première, ferait pointer les liens ambigus vers une autre note.
     */
    @Test
    fun un_titre_ambigu_resout_vers_la_note_la_moins_recemment_modifiee(): Unit = runBlocking {
        val ancienne = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Doublon")
        horloge.avance(60_000)
        val recente = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Doublon")
        horloge.avance(60_000)

        val source = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
            title = "Source",
            content = "Renvoi ambigu vers [[Doublon]].",
        )

        assertThat(liens.observeOutgoing(source.id).first().single().targetId).isEqualTo(ancienne.id)
        assertThat(liens.observeOutgoing(source.id).first().single().targetId).isNotEqualTo(recente.id)
    }

    // ── A `[[Title]]` tapped in the Markdown preview (D-024) ─────────────────

    @Test
    fun resolve_title_matches_as_the_indexer_does_case_accents_and_spaces(): Unit = runBlocking {
        val cible = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Réunion d'équipe")

        assertThat(notes.resolveTitle("  reunion D'EQUIPE ")).isEqualTo(cible.id)
        assertThat(notes.resolveTitle("Réunion")).isNull()
        assertThat(notes.resolveTitle("   ")).isNull()
    }

    /**
     * The preview and the links panel must name **the same** note for an ambiguous title: the panel
     * shows what the indexer stored, the preview asks `resolveTitle` — one map for both.
     */
    @Test
    fun an_ambiguous_title_resolves_where_the_indexer_resolved_it(): Unit = runBlocking {
        val ancienne = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Doublon")
        horloge.avance(60_000)
        notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Doublon")
        horloge.avance(60_000)
        val source = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
            title = "Source",
            content = "[[Doublon]]",
        )

        val indexee = liens.observeOutgoing(source.id).first().single().targetId
        assertThat(indexee).isEqualTo(ancienne.id)
        assertThat(notes.resolveTitle("Doublon")).isEqualTo(indexee)
    }

    /**
     * 🔴 A vault note is never a target — **even when its title is stored in clear** (format 1): only
     * the query's `encrypted_content IS NULL` stands between a tapped link and the vault's existence.
     * The control: the same title outside a vault is found.
     */
    @Test
    fun resolve_title_never_names_a_vault_note_even_with_its_title_in_clear(): Unit = runBlocking {
        scelleur = ScelleurDeTest(EncryptedFormat.CONTENT_ONLY)
        val scellee = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_VAULT,
            title = "Code de la carte",
            content = "0000",
        )
        assertThat(notes.find(scellee.id)!!.title).isEqualTo("Code de la carte")

        assertThat(notes.resolveTitle("Code de la carte")).isNull()

        val claire = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Code de la carte")
        assertThat(notes.resolveTitle("Code de la carte")).isEqualTo(claire.id)
    }

    /**
     * 🔴 **From a vault note, the note of that vault** — solution B (Patrice, 2026-09-25). Its title is
     * in the blob (format 2): only the open session reads it. The control: from outside the vault,
     * the same title names nothing, as it never did.
     *
     * The second vault is the one resolved from: the fixture's vault holds a legacy blob the fake
     * vault cannot open — and resolving from another vault must not try to.
     */
    @Test
    fun resolve_title_from_a_vault_note_finds_the_note_of_that_vault(): Unit = runBlocking {
        val coffre = CoffreFactice()
        scelleur = coffre
        ouvreur = coffre
        val second = provisionnerUnSecondCoffre()
        val codes = notes.create(folderId = second, title = "Codes", content = "0000")
        assertThat(notes.find(codes.id)!!.title).isEmpty()

        assertThat(notes.resolveTitleFrom(second, "  codes ")).isEqualTo(codes.id)
        assertThat(notes.resolveTitleFrom(LegacyDatabaseFixture.Fixtures.FOLDER_WORK, "Codes")).isNull()
        assertThat(notes.resolveTitle("Codes")).isNull()
    }

    /** From a vault note: never a note of another vault, and still a note outside every vault. */
    @Test
    fun resolve_title_from_a_vault_note_skips_other_vaults_and_still_reaches_ordinary_notes(): Unit = runBlocking {
        val coffre = CoffreFactice()
        scelleur = coffre
        ouvreur = coffre
        val second = provisionnerUnSecondCoffre()
        notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_VAULT, title = "Codes", content = "0000")
        val adresses = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Adresses")

        assertThat(notes.resolveTitleFrom(second, "Codes")).isNull()
        assertThat(notes.resolveTitleFrom(second, "Adresses")).isEqualTo(adresses.id)
    }

    /** The vault's own note comes first, and a tie goes the indexer's way: the least recently modified. */
    @Test
    fun from_a_vault_its_own_note_comes_first_and_a_tie_goes_the_indexers_way(): Unit = runBlocking {
        val coffre = CoffreFactice()
        scelleur = coffre
        ouvreur = coffre
        val second = provisionnerUnSecondCoffre()
        val ordinaire = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Codes")
        horloge.avance(60_000)
        val ancienne = notes.create(folderId = second, title = "Codes", content = "a")
        horloge.avance(60_000)
        notes.create(folderId = second, title = "Codes", content = "b")

        assertThat(notes.resolveTitleFrom(second, "Codes")).isEqualTo(ancienne.id)
        assertThat(notes.resolveTitleFrom(LegacyDatabaseFixture.Fixtures.FOLDER_WORK, "Codes")).isEqualTo(ordinaire.id)
    }

    /**
     * A closed session fails the call, as `VaultOpener` wants every failure to be a refusal: nothing is
     * resolved — and the editor, which creates the note only when nothing is found, creates nothing.
     */
    @Test
    fun resolve_title_from_a_closed_vault_fails(): Unit = runBlocking {
        val coffre = CoffreFactice()
        scelleur = coffre
        ouvreur = coffre
        val second = provisionnerUnSecondCoffre()
        notes.create(folderId = second, title = "Codes", content = "0000")
        ouvreur = UnavailableVaultOpener()

        val echec = runCatching { notes.resolveTitleFrom(second, "Codes") }.exceptionOrNull()

        assertThat(echec).isInstanceOf(VaultLockedException::class.java)
    }

    /**
     * The vault's notes are opened off the main thread, **even when asked from it** — the editor asks
     * from there, and the vault service decrypts on its caller's thread (GPT-5.6 review, 2026-09-25).
     * Discriminating: called from a test thread, the check would pass whatever the code does.
     */
    @Test
    fun a_vault_is_opened_off_the_main_thread_even_when_asked_from_it(): Unit = runBlocking {
        val coffre = CoffreFactice()
        scelleur = coffre
        val second = provisionnerUnSecondCoffre()
        val codes = notes.create(folderId = second, title = "Codes", content = "0000")
        val fils = mutableListOf<Boolean>()
        ouvreur = object : VaultOpener {
            override suspend fun decrypt(note: Note): Note {
                synchronized(fils) { fils += Looper.myLooper() == Looper.getMainLooper() }
                return coffre.decrypt(note)
            }
        }

        val trouvee = withContext(Dispatchers.Main) { notes.resolveTitleFrom(second, "Codes") }

        assertThat(trouvee).isEqualTo(codes.id)
        assertThat(fils).isNotEmpty()
        assertThat(fils).doesNotContain(true)
    }

    // ── Protection des notes de coffre ───────────────────────────────────────

    /**
     * 🔴 **Le test de non-régression de tout le projet.**
     *
     * Épingler une note de coffre ouverte détruisait sa protection dans l'application publiée : le
     * geste passait par une réécriture de ligne entière, avec l'éphémère déchiffrée que détenait
     * l'éditeur. Ni le contenu chiffré ni le blob ne doivent bouger ici.
     */
    @Test
    fun epingler_une_note_de_coffre_ne_touche_ni_son_contenu_ni_son_blob(): Unit = runBlocking {
        val avant = notes.find(LegacyDatabaseFixture.Fixtures.NOTE_LOCKED)!!
        assertThat(avant.isLocked).isTrue()

        assertThat(notes.setPinned(avant.id, pinned = true)).isTrue()

        val apres = notes.find(avant.id)!!
        assertThat(apres.pinned).isTrue()
        assertThat(apres.isLocked).isTrue()
        assertThat(apres.encrypted).isEqualTo(avant.encrypted)
        assertThat(apres.content).isEmpty()
        assertThat(apres.title).isEmpty()
    }

    /**
     * 🔴 La garde SQL `AND encrypted_content IS NULL`, exercée sur le chemin qu'elle protège.
     *
     * L'éditeur détient l'éphémère déchiffrée d'une note de coffre. Enregistrer depuis cet objet ne
     * doit **rien** écrire — pas même le titre.
     */
    @Test
    fun enregistrer_l_ephemere_dechiffree_d_une_note_de_coffre_n_ecrit_rien(): Unit = runBlocking {
        val avant = notes.find(LegacyDatabaseFixture.Fixtures.NOTE_LOCKED)!!

        // Le dossier de la note EST un coffre, mais le scelleur refuse : c'est l'état d'un coffre
        // verrouillé pendant l'édition. L'écriture doit être refusée, pas dégradée.
        assertThrows(VaultLockedException::class.java) {
            runBlocking {
                notes.saveEdits(avant.id, title = "Codes bancaires", content = "1234 5678", tags = emptyList())
            }
        }

        val apres = notes.find(avant.id)!!
        assertThat(apres.title).isEmpty()
        assertThat(apres.content).isEmpty()
        assertThat(apres.encrypted).isEqualTo(avant.encrypted)
    }

    /**
     * 🔴 Une écriture refusée ne laisse **aucune** trace : la transaction est annulée entière.
     *
     * Sans transaction, la note serait insérée en clair puis l'échec du scellement survolerait un
     * disque déjà écrit.
     */
    @Test
    fun une_creation_refusee_dans_un_coffre_n_insere_rien(): Unit = runBlocking {
        val avant = notes.countInFolder(LegacyDatabaseFixture.Fixtures.FOLDER_VAULT)

        assertThrows(VaultLockedException::class.java) {
            runBlocking {
                notes.create(
                    folderId = LegacyDatabaseFixture.Fixtures.FOLDER_VAULT,
                    title = "Code de la carte",
                    content = "0000",
                )
            }
        }

        assertThat(notes.countInFolder(LegacyDatabaseFixture.Fixtures.FOLDER_VAULT)).isEqualTo(avant)
    }

    @Test
    fun creer_dans_un_coffre_ouvert_ecrit_le_chiffre_et_rien_en_clair(): Unit = runBlocking {
        scelleur = ScelleurDeTest(EncryptedFormat.TITLE_AND_CONTENT)

        val note = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_VAULT,
            title = "Code de la carte",
            content = "0000",
        )

        val relue = notes.find(note.id)!!
        assertThat(relue.isLocked).isTrue()
        assertThat(relue.title).isEmpty()
        assertThat(relue.content).isEmpty()
        assertThat(relue.encVersion).isEqualTo(EncryptedFormat.TITLE_AND_CONTENT)
    }

    /**
     * ⚠️ Le défaut qu'une première version portait : une note verrouillée au **format 1** garde son
     * titre en clair, donc « le titre n'a pas changé ».
     *
     * Une réaccroche des liens entrants conditionnée au changement de titre laissait alors les liens
     * résolus vers une note qui vient de partir au coffre — et une note non protégée continuait
     * d'afficher un lien cliquable vers elle.
     */
    @Test
    fun verrouiller_une_note_sans_changer_son_titre_detache_quand_meme_ses_retroliens(): Unit = runBlocking {
        val cible = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Secrets")
        val source = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
            title = "Index",
            content = "Voir [[Secrets]].",
        )
        assertThat(liens.observeOutgoing(source.id).first().single().targetId).isEqualTo(cible.id)

        // Le scelleur garde le titre en clair : c'est le format 1, celui des coffres d'avant 2.0.0.
        scelleur = ScelleurDeTest(EncryptedFormat.CONTENT_ONLY)
        notes.moveToFolder(cible.id, LegacyDatabaseFixture.Fixtures.FOLDER_VAULT)

        assertThat(notes.find(cible.id)!!.isLocked).isTrue()
        assertThat(notes.find(cible.id)!!.title).isEqualTo("Secrets") // le titre n'a PAS changé
        assertThat(liens.observeOutgoing(source.id).first().single().targetId).isNull()
    }

    @Test
    fun deplacer_une_note_verrouillee_est_refuse(): Unit = runBlocking {
        assertThrows(VaultRelocationException::class.java) {
            runBlocking {
                notes.moveToFolder(
                    LegacyDatabaseFixture.Fixtures.NOTE_LOCKED,
                    LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
                )
            }
        }

        assertThat(notes.find(LegacyDatabaseFixture.Fixtures.NOTE_LOCKED)!!.folderId)
            .isEqualTo(LegacyDatabaseFixture.Fixtures.FOLDER_VAULT)
    }

    /**
     * 🔴 **Effacer le contenu d'une note de coffre doit détruire le secret, pas l'ignorer.**
     *
     * Le défaut : `sealIfVault` sortait tôt quand la note ne portait plus de lisible — ce qui est le
     * cas d'une note qu'on vient de **vider** — et `lockNote` réécrivait alors l'**ancien blob**.
     * L'effacement était ignoré en silence, et le secret réapparaissait intact à la réouverture.
     *
     * Ce test lit le blob **à travers le coffre factice** : vérifier que la note est toujours
     * verrouillée ne prouverait rien, puisqu'elle l'était déjà avec l'ancien chiffré.
     *
     * Relevé par une relecture externe (Gemini 3.1 Pro, 2026-08-15).
     */
    @Test
    fun vider_une_note_de_coffre_rescelle_et_n_oublie_pas_l_effacement(): Unit = runBlocking {
        val coffre = CoffreFactice()
        scelleur = coffre
        ouvreur = coffre
        val note = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_VAULT,
            title = "Codes bancaires",
            content = "Le code est 4242",
        )
        assertThat(coffre.decrypt(notes.find(note.id)!!).content).isEqualTo("Le code est 4242")

        notes.saveEdits(note.id, title = "", content = "", tags = emptyList())

        val relue = notes.find(note.id)!!
        assertThat(relue.isLocked).isTrue()
        val ouverte = coffre.decrypt(relue)
        assertThat(ouverte.content).isEmpty()
        assertThat(ouverte.title).isEmpty()
    }

    // ── Sortir une note d'un coffre ──────────────────────────────────────────
    //
    // 🔴 `relocateLockedNote` est le seul geste qui retire la protection d'UNE note. Tout ce qui
    // suit cherche à lui faire écrire du clair là où il ne faut pas, ou à lui faire perdre le texte.

    @Test
    fun sortir_une_note_dun_coffre_ecrit_le_clair_le_deplace_et_indexe_ses_liens(): Unit = runBlocking {
        val coffre = CoffreFactice()
        scelleur = coffre
        ouvreur = coffre
        val note = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_VAULT,
            title = "Codes bancaires",
            content = "Voir [[Réunion budget]] pour le contexte.",
        )
        assertThat(notes.find(note.id)!!.isLocked).isTrue()
        // Verrouillée, elle ne cite personne : ses liens ont été effacés à l'écriture.
        assertThat(liens.observeOutgoing(note.id).first()).isEmpty()

        assertThat(notes.relocateLockedNote(note.id, LegacyDatabaseFixture.Fixtures.FOLDER_WORK)).isTrue()

        val relue = notes.find(note.id)!!
        assertThat(relue.isLocked).isFalse()
        assertThat(relue.folderId).isEqualTo(LegacyDatabaseFixture.Fixtures.FOLDER_WORK)
        assertThat(relue.title).isEqualTo("Codes bancaires")
        assertThat(relue.content).isEqualTo("Voir [[Réunion budget]] pour le contexte.")
        // Redevenue lisible, elle redevient indexable : son lien est résolu vers la note du jeu d'essai.
        assertThat(liens.observeOutgoing(note.id).first().single().targetId)
            .isEqualTo(LegacyDatabaseFixture.Fixtures.NOTE_PLAIN)
    }

    /**
     * L'autre sens, celui qu'on oublie : la note **redevient une cible**.
     *
     * Le jeu d'essai porte un lien fantôme vers « Archive 2025 ». Tant que la note de ce titre est
     * au coffre, il doit le rester — `resolveIncoming` force la clé de titre à vide pour une note
     * verrouillée, précisément pour qu'un rétrolien ne révèle ni son titre ni son existence. En
     * sortant du coffre, elle cesse d'être secrète, et le lien doit enfin l'atteindre.
     */
    @Test
    fun sortir_une_note_dun_coffre_la_rend_visible_aux_liens_qui_la_visaient(): Unit = runBlocking {
        val coffre = CoffreFactice()
        scelleur = coffre
        ouvreur = coffre
        val note = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_VAULT, title = "Archive 2025")
        val source = LegacyDatabaseFixture.Fixtures.NOTE_LINK_SOURCE
        assertThat(liens.observeOutgoing(source).first().single { it.targetTitle == "Archive 2025" }.targetId)
            .isNull()

        notes.relocateLockedNote(note.id, LegacyDatabaseFixture.Fixtures.FOLDER_WORK)

        assertThat(liens.observeOutgoing(source).first().single { it.targetTitle == "Archive 2025" }.targetId)
            .isEqualTo(note.id)
    }

    /**
     * ⚠️ Le test le plus important de la série : un ouvreur qui **ne déchiffre pas** ne doit pas
     * pouvoir vider la note.
     *
     * `unlockNote` écrit `content` et `title` depuis ce que l'ouvreur a rendu, et efface le blob en
     * dur. Si l'ouvreur rend la note inchangée — encore scellée, donc titre et contenu vides — la
     * note perdrait à la fois son texte et sa protection. C'est le jumeau du scelleur négligent, et
     * il détruit là où l'autre laisse fuir.
     */
    @Test
    fun un_ouvreur_qui_ne_dechiffre_pas_est_refuse_et_ne_vide_pas_la_note(): Unit = runBlocking {
        val coffre = CoffreFactice()
        scelleur = coffre
        ouvreur = coffre
        val note = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_VAULT,
            title = "Codes bancaires",
            content = "0000",
        )
        ouvreur = OuvreurNegligent()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { notes.relocateLockedNote(note.id, LegacyDatabaseFixture.Fixtures.FOLDER_WORK) }
        }

        val relue = notes.find(note.id)!!
        assertThat(relue.isLocked).isTrue()
        assertThat(relue.folderId).isEqualTo(LegacyDatabaseFixture.Fixtures.FOLDER_VAULT)
        assertThat(coffre.coffreDOrigine(relue)).isEqualTo(LegacyDatabaseFixture.Fixtures.FOLDER_VAULT)
    }

    @Test
    fun sortir_est_refuse_si_le_coffre_dorigine_est_ferme(): Unit = runBlocking {
        val coffre = CoffreFactice()
        scelleur = coffre
        ouvreur = coffre
        val note = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_VAULT,
            title = "Codes bancaires",
            content = "0000",
        )
        // Plus aucune session : c'est ce que rend l'ouvreur quand la clé n'existe pas en mémoire.
        ouvreur = UnavailableVaultOpener()

        assertThrows(VaultLockedException::class.java) {
            runBlocking { notes.relocateLockedNote(note.id, LegacyDatabaseFixture.Fixtures.FOLDER_WORK) }
        }

        assertThat(notes.find(note.id)!!.isLocked).isTrue()
    }

    /**
     * Un geste nommé « sortir du coffre » sur une note qui n'y est pas veut dire que l'appelant
     * s'est trompé de chemin — ou que l'état de son écran est périmé. Le silence y masquerait une
     * confirmation demandée à l'utilisateur pour une action qui n'était pas celle-là.
     */
    @Test
    fun sortir_une_note_qui_nest_pas_verrouillee_est_refuse(): Unit = runBlocking {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                notes.relocateLockedNote(
                    LegacyDatabaseFixture.Fixtures.NOTE_PLAIN,
                    LegacyDatabaseFixture.Fixtures.FOLDER_VAULT,
                )
            }
        }

        assertThat(notes.find(LegacyDatabaseFixture.Fixtures.NOTE_PLAIN)!!.folderId)
            .isEqualTo(LegacyDatabaseFixture.Fixtures.FOLDER_WORK)
    }

    /**
     * D'un coffre à l'autre : le blob doit être **refait avec la clé de destination**, jamais
     * transporté tel quel. Une note déplacée avec un blob que la clé du dossier d'arrivée n'ouvre
     * pas est une note perdue, sans le moindre message — c'est pour cela que `moveToFolder` refuse.
     */
    @Test
    fun passer_dun_coffre_a_lautre_rescelle_avec_la_cle_de_destination(): Unit = runBlocking {
        val coffre = CoffreFactice()
        scelleur = coffre
        ouvreur = coffre
        val second = provisionnerUnSecondCoffre()
        val note = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_VAULT,
            title = "Codes bancaires",
            content = "0000",
        )

        assertThat(notes.relocateLockedNote(note.id, second)).isTrue()

        val relue = notes.find(note.id)!!
        assertThat(relue.folderId).isEqualTo(second)
        assertThat(relue.isLocked).isTrue()
        assertThat(relue.title).isEmpty()
        assertThat(relue.content).isEmpty()
        assertThat(coffre.coffreDOrigine(relue)).isEqualTo(second)
        // Et elle reste lisible avec la clé du dossier où elle est désormais.
        assertThat(coffre.decrypt(relue).content).isEqualTo("0000")
    }

    /**
     * Un dossier ordinaire promu coffre par le même chemin que le service de coffres, avec du
     * matériel factice : ces tests ne déchiffrent rien pour de vrai, ils vérifient que le blob écrit
     * est bien celui de la destination.
     */
    private suspend fun provisionnerUnSecondCoffre(): String {
        val dossier = dossiers.create("Second coffre")
        val converti = provider.get().folderDao().provisionPassphraseVault(
            id = dossier.id,
            salt = ByteArray(16) { (it + 40).toByte() },
            kekWrapped = ByteArray(60) { (it + 9).toByte() },
            iv = ByteArray(12) { (it + 2).toByte() },
            verifier = ByteArray(32) { (it * 3).toByte() },
            updatedAt = horloge.millis(),
        )
        check(converti == 1) { "le second coffre n'a pas ete provisionne" }
        return dossier.id
    }

    /**
     * Une note vide créée dans un coffre ne déclenche **pas** le scellement — parité avec
     * l'application publiée, où la garde sort tôt quand il n'y a rien à protéger.
     *
     * C'est ce qui permet à l'éditeur de créer la note avant que l'utilisateur ait tapé quoi que ce
     * soit. Le premier caractère saisi, lui, passera par le scellement.
     */
    @Test
    fun une_note_vide_dans_un_coffre_ne_declenche_pas_le_scellement(): Unit = runBlocking {
        val note = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_VAULT)

        assertThat(note.isLocked).isFalse()
        assertThat(notes.find(note.id)).isNotNull()
    }

    /**
     * La limite de 200 caractères sur le titre — valeur héritée d'`AppConstants.noteTitleMaxLength`.
     *
     * ⚠️ Elle n'avait **aucun test**, alors que l'éditeur s'appuie désormais dessus pour dire à
     * l'utilisateur *pourquoi* son enregistrement échoue. Une règle appliquée sans être vérifiée,
     * et un message qui la cite : si la limite bougeait, le message mentirait sans que rien
     * n'échoue.
     */
    @Test
    fun un_titre_trop_long_est_refuse(): Unit = runBlocking {
        val note = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Court")

        val limite = NotesRepository.TITLE_MAX_LENGTH
        // Exactement la limite : accepté.
        notes.saveEdits(id = note.id, title = "a".repeat(limite), content = "corps", tags = emptyList())
        assertThat(notes.find(note.id)!!.title).hasLength(limite)

        // Un caractère de plus : refusé, et **rien n'est écrit**.
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                notes.saveEdits(
                    id = note.id,
                    title = "a".repeat(limite + 1),
                    content = "autre corps",
                    tags = emptyList(),
                )
            }
        }
        val apres = notes.find(note.id)!!
        assertThat(apres.title).hasLength(limite)
        assertThat(apres.content).isEqualTo("corps")
    }

    // ── Corbeille ────────────────────────────────────────────────────────────

    @Test
    fun mettre_a_la_corbeille_efface_les_liens_sortants_et_detache_les_entrants(): Unit = runBlocking {
        val cible = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Cible")
        val source = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
            title = "Source",
            content = "Voir [[Cible]] et [[Réunion budget]].",
        )
        assertThat(liens.observeOutgoing(source.id).first()).hasSize(2)

        notes.moveToTrash(source.id)

        assertThat(liens.observeOutgoing(source.id).first()).isEmpty()
        assertThat(liens.observeBacklinks(cible).first()).isEmpty()
    }

    @Test
    fun restaurer_depuis_la_corbeille_reindexe_les_liens(): Unit = runBlocking {
        val source = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
            title = "Source",
            content = "Voir [[Réunion budget]].",
        )
        notes.moveToTrash(source.id)
        assertThat(liens.observeOutgoing(source.id).first()).isEmpty()

        notes.restoreFromTrash(source.id)

        assertThat(liens.observeOutgoing(source.id).first().single().targetId)
            .isEqualTo(LegacyDatabaseFixture.Fixtures.NOTE_PLAIN)
    }

    /** La rétention de trente jours, mesurée sur une horloge qu'on fait avancer. */
    @Test
    fun la_purge_respecte_la_retention_de_trente_jours(): Unit = runBlocking {
        val note = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Jetée")
        notes.moveToTrash(note.id)

        horloge.avance(29L * 24 * 60 * 60 * 1000)
        assertThat(notes.purgeExpiredTrash()).isEqualTo(0)
        assertThat(notes.find(note.id)).isNotNull()

        horloge.avance(2L * 24 * 60 * 60 * 1000)
        assertThat(notes.purgeExpiredTrash()).isEqualTo(1)
        assertThat(notes.find(note.id)).isNull()
    }

    /**
     * L'invariant du vidage : il emporte la corbeille, **et seulement elle**. Une requête sans le
     * `WHERE trashed_at IS NOT NULL` viderait la base entière sans la moindre erreur.
     */
    @Test
    fun vider_la_corbeille_ne_touche_pas_aux_notes_vivantes(): Unit = runBlocking {
        val jetee = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Jetée")
        val gardee = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Gardée")
        notes.moveToTrash(jetee.id)

        assertThat(notes.emptyTrash()).isEqualTo(1)

        assertThat(notes.find(jetee.id)).isNull()
        assertThat(notes.find(gardee.id)).isNotNull()
        assertThat(notes.find(LegacyDatabaseFixture.Fixtures.NOTE_PLAIN)).isNotNull()
    }

    /**
     * Vider n'est pas purger : la rétention protège de l'oubli, pas d'une demande explicite. Le même
     * test mesure les deux chemins sur la même note pour que l'écart soit lisible.
     */
    @Test
    fun vider_la_corbeille_ignore_la_retention_de_trente_jours(): Unit = runBlocking {
        val note = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "Jetée à l'instant")
        notes.moveToTrash(note.id)

        assertThat(notes.purgeExpiredTrash()).isEqualTo(0)
        assertThat(notes.emptyTrash()).isEqualTo(1)
        assertThat(notes.find(note.id)).isNull()
    }

    /**
     * 🔴 **Sans la clé du coffre.** Aucune session n'est ouverte ici : si le vidage exigeait de
     * déchiffrer, un coffre dont la phrase secrète est perdue aurait une corbeille invidable, et la
     * note y resterait indéfiniment.
     */
    @Test
    fun vider_la_corbeille_detruit_une_note_de_coffre_encore_scellee(): Unit = runBlocking {
        notes.moveToTrash(LegacyDatabaseFixture.Fixtures.NOTE_LOCKED)
        assertThat(notes.find(LegacyDatabaseFixture.Fixtures.NOTE_LOCKED)!!.isLocked).isTrue()

        assertThat(notes.emptyTrash()).isEqualTo(1)

        assertThat(notes.find(LegacyDatabaseFixture.Fixtures.NOTE_LOCKED)).isNull()
    }

    /** Vider une corbeille déjà vide ne détruit rien et le dit : zéro, pas une réussite muette. */
    @Test
    fun vider_une_corbeille_vide_ne_supprime_rien(): Unit = runBlocking {
        assertThat(notes.emptyTrash()).isEqualTo(0)
        assertThat(notes.find(LegacyDatabaseFixture.Fixtures.NOTE_PLAIN)).isNotNull()
    }

    // ── Dossiers ─────────────────────────────────────────────────────────────

    @Test
    fun renommer_un_coffre_ne_touche_pas_son_materiel_cryptographique(): Unit = runBlocking {
        val db = provider.get()
        val avant = db.folderDao().findById(LegacyDatabaseFixture.Fixtures.FOLDER_VAULT)!!

        assertThat(dossiers.rename(avant.id, "Coffre renommé")).isTrue()

        val apres = db.folderDao().findById(avant.id)!!
        assertThat(apres.name).isEqualTo("Coffre renommé")
        assertThat(apres.vaultSalt).isEqualTo(avant.vaultSalt)
        assertThat(apres.vaultKekWrapped).isEqualTo(avant.vaultKekWrapped)
        assertThat(apres.vaultVerifier).isEqualTo(avant.vaultVerifier)
        assertThat(apres.vaultAttempts).isEqualTo(avant.vaultAttempts)
    }

    @Test
    fun un_coffre_est_reconnu_par_son_sel_meme_sans_mode(): Unit = runBlocking {
        val db = provider.get()
        // Un coffre créé en 0.8, avant l'existence de `vault_mode`. La migration l'a normalement
        // rétro-rempli ; ce test décrit ce qui se passe si elle ne l'a pas fait.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE folders SET vault_mode = NULL WHERE id = ?",
            arrayOf<Any?>(LegacyDatabaseFixture.Fixtures.FOLDER_VAULT),
        )

        assertThat(dossiers.isVaultFolder(LegacyDatabaseFixture.Fixtures.FOLDER_VAULT)).isTrue()
        assertThat(dossiers.listVaults().map { it.id })
            .contains(LegacyDatabaseFixture.Fixtures.FOLDER_VAULT)
    }

    /** ⚠️ Un dossier inconnu est traité comme un coffre : le repli ferme, il n'ouvre pas. */
    @Test
    fun un_dossier_inconnu_est_traite_comme_un_coffre(): Unit = runBlocking {
        assertThat(dossiers.isVaultFolder("dossier-qui-n-existe-pas")).isTrue()
    }

    @Test
    fun deplacer_un_dossier_sous_son_propre_descendant_est_refuse(): Unit = runBlocking {
        val parent = dossiers.create("Parent")
        val enfant = dossiers.create("Enfant", parentId = parent.id)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dossiers.move(parent.id, enfant.id) }
        }

        assertThat(dossiers.find(parent.id)!!.parentId).isNull()
    }

    @Test
    fun supprimer_un_dossier_en_gardant_ses_notes_les_reassigne_d_abord(): Unit = runBlocking {
        val source = dossiers.create("À supprimer")
        notes.create(folderId = source.id, title = "Note 1")
        notes.create(folderId = source.id, title = "Note 2")

        val deplacees = dossiers.deleteKeepingNotes(source.id, LegacyDatabaseFixture.Fixtures.FOLDER_WORK)

        assertThat(deplacees).isEqualTo(2)
        assertThat(dossiers.find(source.id)).isNull()
        assertThat(notes.countInFolder(LegacyDatabaseFixture.Fixtures.FOLDER_WORK)).isEqualTo(3)
    }

    /**
     * 🔴 Une suppression refusée ne doit rien laisser derrière elle.
     *
     * Une première version rendait `null` au lieu de lever quand la suppression touchait la boîte de
     * réception. La transaction se terminait normalement, Room la validait, et le dossier survivait
     * **vidé de toutes ses notes**. Relevé par une relecture externe.
     */
    @Test
    fun refuser_de_supprimer_la_boite_de_reception_annule_aussi_la_reassignation(): Unit = runBlocking {
        val inbox = com.filestech.notes_tech.domain.model.Folder.INBOX_ID
        notes.create(folderId = inbox, title = "Note d'accueil")
        val avantInbox = notes.countInFolder(inbox)
        val avantTravail = notes.countInFolder(LegacyDatabaseFixture.Fixtures.FOLDER_WORK)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dossiers.deleteKeepingNotes(inbox, LegacyDatabaseFixture.Fixtures.FOLDER_WORK) }
        }

        assertThat(notes.countInFolder(inbox)).isEqualTo(avantInbox)
        assertThat(notes.countInFolder(LegacyDatabaseFixture.Fixtures.FOLDER_WORK)).isEqualTo(avantTravail)
        assertThat(dossiers.find(inbox)).isNotNull()
    }

    /**
     * Même exigence pour un dossier inexistant : la transaction est annulée, pas seulement stérile.
     *
     * ⚠️ **Le refus vient maintenant de la garde de coffre, et c'est le durcissement du 2026-08-15.**
     *
     * `FolderDao.isVault` rend `null` pour un dossier inconnu, et son contrat dit de traiter ce cas
     * **comme un coffre**. Les deux gardes lisaient `!= true`, ce qui laissait passer le `null` :
     * « je ne sais pas » valait « ce n'est pas un coffre », sur les deux conditions qui empêchent
     * des notes d'entrer en clair dans un coffre ou d'en sortir sans leur clé. Elles lisent
     * désormais `== false`.
     *
     * La conséquence visible ici est le **type** de l'exception : le refus tombe plus tôt, sur un
     * `require` et non plus sur le `check` de la suppression. Ce que le test garantit n'a pas
     * changé — rien n'est modifié — et il garantit en plus, maintenant, que le doute ferme la garde
     * au lieu de l'ouvrir. Relevé par l'audit par motifs du 2026-08-15.
     */
    @Test
    fun supprimer_un_dossier_inconnu_en_gardant_ses_notes_annule_tout(): Unit = runBlocking {
        val avant = notes.countInFolder(LegacyDatabaseFixture.Fixtures.FOLDER_WORK)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                dossiers.deleteKeepingNotes("dossier-inconnu", LegacyDatabaseFixture.Fixtures.FOLDER_WORK)
            }
        }

        assertThat(notes.countInFolder(LegacyDatabaseFixture.Fixtures.FOLDER_WORK)).isEqualTo(avant)
        // Le dossier de destination est intact : aucune note n'y a été réassignée avant le refus.
        assertThat(dossiers.find(LegacyDatabaseFixture.Fixtures.FOLDER_WORK)).isNotNull()
    }

    /**
     * 🔴 Verrouiller depuis une édition doit conserver les étiquettes et faire remonter la note.
     *
     * Une première version de `lockNote` n'écrivait ni l'un ni l'autre : les étiquettes saisies
     * étaient perdues en silence, et la note ne remontait pas dans « modifiées récemment » —
     * divergence visible avec l'application publiée. Relevé par une relecture externe.
     */
    @Test
    fun verrouiller_depuis_une_edition_conserve_les_etiquettes_et_la_date(): Unit = runBlocking {
        scelleur = ScelleurDeTest(EncryptedFormat.TITLE_AND_CONTENT)
        val note = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "À classer")
        notes.moveToFolder(note.id, LegacyDatabaseFixture.Fixtures.FOLDER_VAULT)
        val avant = notes.find(note.id)!!
        assertThat(avant.isLocked).isTrue()

        horloge.avance(120_000)
        notes.saveEdits(note.id, title = "Secret", content = "corps", tags = listOf("perso", "urgent"))

        val apres = notes.find(note.id)!!
        assertThat(apres.tags).containsExactly("perso", "urgent").inOrder()
        assertThat(apres.updatedAt).isGreaterThan(avant.updatedAt)
        // Et rien n'a fui au passage.
        assertThat(apres.content).isEmpty()
        assertThat(apres.title).isEmpty()
        assertThat(apres.isLocked).isTrue()
    }

    /**
     * ⚠️ Le pendant du test précédent : une **reprotection** ne réordonne pas l'écran.
     *
     * Le déplacement vers un coffre écrit `updated_at` par son propre chemin ; `lockNote` reçoit
     * `null` pour ne pas l'écrire deux fois. Ce test fige le fait que la note ne prend pas deux
     * horodatages différents pour un seul geste.
     */
    @Test
    fun entrer_dans_un_coffre_n_ecrit_qu_un_seul_horodatage(): Unit = runBlocking {
        scelleur = ScelleurDeTest(EncryptedFormat.TITLE_AND_CONTENT)
        val note = notes.create(folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK, title = "À classer")

        horloge.avance(60_000)
        val instantDuDeplacement = horloge.instant()
        notes.moveToFolder(note.id, LegacyDatabaseFixture.Fixtures.FOLDER_VAULT)

        assertThat(notes.find(note.id)!!.updatedAt).isEqualTo(instantDuDeplacement)
    }

    /** Une note verrouillée n'a pas de rétroliens, et la requête n'est même pas lancée. */
    @Test
    fun une_note_verrouillee_n_a_aucun_retrolien(): Unit = runBlocking {
        val verrouillee = notes.find(LegacyDatabaseFixture.Fixtures.NOTE_LOCKED)!!

        assertThat(liens.observeBacklinks(verrouillee).first()).isEmpty()
    }

    /**
     * 🔴 Le trou par lequel du clair entrait dans un coffre.
     *
     * La réassignation en bloc est un `UPDATE` nu : elle ne passe pas par le scellement. Vider un
     * dossier ordinaire **vers un coffre** y déposait des notes en clair, `encrypted_content` à
     * `NULL`. Relevé par la relecture des correctifs, après que le lot initial eut été déclaré
     * exempt de fuite de clair.
     */
    @Test
    fun vider_un_dossier_vers_un_coffre_est_refuse(): Unit = runBlocking {
        val source = dossiers.create("Ordinaire")
        val note = notes.create(folderId = source.id, title = "En clair", content = "texte lisible")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dossiers.deleteKeepingNotes(source.id, LegacyDatabaseFixture.Fixtures.FOLDER_VAULT) }
        }

        val relue = notes.find(note.id)!!
        assertThat(relue.folderId).isEqualTo(source.id)
        assertThat(relue.isLocked).isFalse()
        assertThat(dossiers.find(source.id)).isNotNull()
    }

    /**
     * 🔴 L'autre face du même trou : garder les notes d'un coffre en supprimant le coffre revient à
     * garder des blobs dont on vient d'effacer la clé.
     *
     * C'est **pire** que la suppression qu'on croit éviter : supprimer le coffre avec ses notes est
     * propre, les sauver sans leur clé produit des blobs que plus rien n'ouvrira.
     */
    @Test
    fun vider_un_coffre_vers_un_dossier_ordinaire_est_refuse(): Unit = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                dossiers.deleteKeepingNotes(
                    LegacyDatabaseFixture.Fixtures.FOLDER_VAULT,
                    LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
                )
            }
        }

        val verrouillee = notes.find(LegacyDatabaseFixture.Fixtures.NOTE_LOCKED)!!
        assertThat(verrouillee.folderId).isEqualTo(LegacyDatabaseFixture.Fixtures.FOLDER_VAULT)
        assertThat(dossiers.find(LegacyDatabaseFixture.Fixtures.FOLDER_VAULT)).isNotNull()
    }

    /** L'auto-complétion ne doit jamais proposer le titre d'une note de coffre. */
    @Test
    fun l_auto_completion_ne_propose_pas_les_titres_de_coffre(): Unit = runBlocking {
        // Une note de coffre au FORMAT 1 : son titre est légitimement en clair dans la colonne.
        val db = provider.get()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE notes SET title = ?, enc_v = 1 WHERE id = ?",
            arrayOf<Any?>("Codes bancaires", LegacyDatabaseFixture.Fixtures.NOTE_LOCKED),
        )

        val suggestions = notes.suggestTitles("Codes")

        assertThat(suggestions).isEmpty()
    }

    @Test
    fun la_boite_de_reception_ne_se_supprime_pas(): Unit = runBlocking {
        assertThat(dossiers.delete(com.filestech.notes_tech.domain.model.Folder.INBOX_ID)).isFalse()
        assertThat(dossiers.find(com.filestech.notes_tech.domain.model.Folder.INBOX_ID)).isNotNull()
    }

    /**
     * ⚠️ **Jumeau asymétrique** : deux requêtes lisaient `note_links`, une seule refusait les notes
     * de coffre comme source.
     *
     * `target_title` est un titre écrit **dans le texte** d'une note. Si cette note est au coffre,
     * son texte est censé être illisible ; la liste des liens fantomes en laissait pourtant filtrer
     * un fragment. Relevé par une relecture externe.
     */
    @Test
    fun les_liens_fantomes_d_une_note_de_coffre_ne_remontent_pas(): Unit = runBlocking {
        // État hérité : une ligne de `note_links` dont la source est verrouillée. Elle ne peut pas
        // être produite par ce portage, mais la base est partagée avec l'application Flutter et
        // aucune migration ne la nettoie.
        val db = provider.get()
        db.linkWriter.replaceLinksOf(
            LegacyDatabaseFixture.Fixtures.NOTE_LOCKED,
            listOf(
                com.filestech.notes_tech.data.local.OutgoingLink(
                    targetId = null,
                    targetTitle = "Nom cite dans le coffre",
                    targetTitleNorm = "nom cite dans le coffre",
                    position = 0,
                ),
            ),
        )

        val fantomes = liens.observeDangling().first()

        assertThat(fantomes.map { it.targetTitle }).doesNotContain("Nom cite dans le coffre")
        assertThat(fantomes.map { it.sourceId }).doesNotContain(LegacyDatabaseFixture.Fixtures.NOTE_LOCKED)
    }

    /**
     * ⚠️ Un titre en clair sans corps, dans un coffre, doit être vu par la passe de réparation.
     *
     * L'application publiée ne teste que `content <> ''` et laisse donc passer une note intitulée
     * « Codes de la carte bleue » au corps vide — limite que son propre code documente comme ouverte.
     * Relevé par une relecture externe.
     */
    @Test
    fun une_note_de_coffre_au_titre_en_clair_et_au_corps_vide_est_detectee(): Unit = runBlocking {
        val db = provider.get()
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO notes (id, title, content, encrypted_content, folder_id, tags, pinned, " +
                "favorite, archived, trashed_at, created_at, updated_at, enc_v) " +
                "VALUES ('titre-seul', 'Codes de la carte bleue', '', NULL, ?, '', 0, 0, 0, NULL, 1, 1, 1)",
            arrayOf<Any?>(LegacyDatabaseFixture.Fixtures.FOLDER_VAULT),
        )

        val aReparer = db.noteDao().findPlaintextInFolder(LegacyDatabaseFixture.Fixtures.FOLDER_VAULT)

        assertThat(aReparer.map { it.id }).contains("titre-seul")
        // Et le cas hérité légitime n'est PAS retenu : format 1, titre en clair, blob présent.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE notes SET title = 'Titre legitime', enc_v = 1 WHERE id = ?",
            arrayOf<Any?>(LegacyDatabaseFixture.Fixtures.NOTE_LOCKED),
        )
        assertThat(db.noteDao().findPlaintextInFolder(LegacyDatabaseFixture.Fixtures.FOLDER_VAULT).map { it.id })
            .doesNotContain(LegacyDatabaseFixture.Fixtures.NOTE_LOCKED)
    }

    // ── L'hypothèse sur laquelle repose toute la conception ─────────────────

    /**
     * 🔴 **La question la plus importante de ce lot, et elle se mesure au lieu de se supposer.**
     *
     * `NoteLinkWriter` ouvre son propre `withTransaction` alors que l'appelant est déjà dans une
     * transaction. Toute la décision D-009 — « écrire une note et ses liens est une seule
     * opération » — repose sur le fait que cette imbrication n'est **pas** une seconde transaction
     * qui pourrait être validée séparément.
     *
     * Si elle l'était, une exception levée après l'écriture des liens laisserait `note_links` écrite
     * et `notes` annulée : exactement la divergence que D-009 prétend rendre impossible. Le tout
     * sans le moindre signal.
     *
     * Signalé comme point à trancher par une relecture externe (GPT-5.2, 2026-08-13), qui notait
     * justement qu'elle ne pouvait pas l'affirmer sans le mesurer. Ce test le mesure : il force une
     * exception **après** l'écriture des liens et vérifie que les deux tables reviennent en arrière.
     */
    @Test
    fun une_exception_apres_l_ecriture_des_liens_annule_aussi_les_liens(): Unit = runBlocking {
        val note = notes.create(
            folderId = LegacyDatabaseFixture.Fixtures.FOLDER_WORK,
            title = "Source",
            content = "Voir [[Réunion budget]].",
        )
        assertThat(liens.observeOutgoing(note.id).first()).hasSize(1)

        val db = provider.get()
        val explosion = RuntimeException("interruption volontaire apres l'ecriture des liens")
        val leve = runCatching {
            db.withTransaction {
                db.linkWriter.replaceLinksOf(
                    note.id,
                    listOf(
                        com.filestech.notes_tech.data.local.OutgoingLink(null, "Ajouté", "ajoute", 0),
                        com.filestech.notes_tech.data.local.OutgoingLink(null, "Encore", "encore", 9),
                    ),
                )
                db.noteWriteDao().updateTags(note.id, "marqueur", horloge.millis())
                throw explosion
            }
        }.exceptionOrNull()

        assertThat(leve).isSameInstanceAs(explosion)
        // Les DEUX écritures sont annulées : celle qui passe par Room comme celle qui passe par
        // `execSQL` dans une transaction imbriquée.
        assertThat(liens.observeOutgoing(note.id).first().map { it.targetTitle })
            .containsExactly("Réunion budget")
        assertThat(notes.find(note.id)!!.tags).isEmpty()
    }

    /**
     * 🔴 Un scelleur défaillant ne peut pas faire écrire du clair : le repository vérifie ce qu'il
     * rend.
     *
     * La phase 4 n'existe pas encore, donc ce test porte sur du code à venir — c'est précisément le
     * moment de poser le contrôle. Signalé par une relecture externe (GPT-5.2) comme le défaut qui
     * « explosera au moment où le vrai scelleur arrivera ».
     */
    @Test
    fun un_scelleur_qui_oublie_de_vider_le_clair_fait_echouer_l_ecriture(): Unit = runBlocking {
        scelleur = ScelleurNegligent()
        val avant = notes.countInFolder(LegacyDatabaseFixture.Fixtures.FOLDER_VAULT)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                notes.create(
                    folderId = LegacyDatabaseFixture.Fixtures.FOLDER_VAULT,
                    title = "Code",
                    content = "SECRET",
                )
            }
        }

        assertThat(notes.countInFolder(LegacyDatabaseFixture.Fixtures.FOLDER_VAULT)).isEqualTo(avant)
    }

    // ── Doubles de test ──────────────────────────────────────────────────────

    /**
     * Une horloge que le test fait avancer.
     *
     * Injectée des **deux** côtés — l'ouverture de la base et les repositories — parce qu'une
     * horloge à moitié injectable rend les tests vacants : on écrit avec l'heure réelle et on vérifie
     * contre une heure figée, et les assertions passent pour la mauvaise raison.
     */
    private class HorlogeReglable(private var maintenant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = maintenant
        fun avance(millis: Long) {
            maintenant = maintenant.plusMillis(millis)
        }
    }

    /**
     * Un scelleur qui chiffre pour de faux, mais respecte le contrat qui compte : le clair sort de
     * la note, le blob y entre.
     *
     * [format] permet d'exercer les deux formats. Le format 1 garde le titre en clair, ce qui est
     * précisément le cas où « le titre n'a pas changé » masquait un défaut.
     */
    private class ScelleurDeTest(private val format: Int) : VaultSealer {
        override suspend fun seal(note: Note): Note = note.copy(
            title = if (format == EncryptedFormat.TITLE_AND_CONTENT) "" else note.title,
            content = "",
            encrypted = EncryptedBody("chiffre:${note.title}|${note.content}".toByteArray()),
            encVersion = format,
        )
    }

    /**
     * Un scelleur qui chiffre **mais oublie de vider le clair** — la faute la plus plausible d'une
     * implémentation réelle, et la seule que le repository ne peut pas se permettre de laisser
     * passer.
     */
    private class ScelleurNegligent : VaultSealer {
        override suspend fun seal(note: Note): Note = note.copy(
            encrypted = EncryptedBody("chiffre".toByteArray()),
            encVersion = EncryptedFormat.TITLE_AND_CONTENT,
            // `title` et `content` restent remplis : c'est tout l'objet du test.
        )
    }

    /** Permet de changer de scelleur au milieu d'un test, le repository étant construit une fois. */
    private class ScelleurDelegue(private val courant: () -> VaultSealer) : VaultSealer {
        override suspend fun seal(note: Note): Note = courant().seal(note)
    }

    /** Le jumeau de [ScelleurDelegue], pour la même raison. */
    private class OuvreurDelegue(private val courant: () -> VaultOpener) : VaultOpener {
        override suspend fun decrypt(note: Note): Note = courant().decrypt(note)
    }

    /**
     * Un coffre factice qui **lie le chiffré à son dossier**, comme la vraie cryptographie le fait
     * par sa clé et son AAD.
     *
     * Sans ce lien, un test de passage d'un coffre à l'autre serait vacant : n'importe quel blob
     * s'ouvrirait n'importe où, et « rescellé avec la clé de destination » ne pourrait pas se
     * distinguer de « blob transporté tel quel » — qui est précisément la faute à empêcher.
     *
     * Le séparateur est l'octet nul : il ne peut pas apparaître dans un titre ou un contenu saisis.
     */
    private class CoffreFactice :
        VaultSealer,
        VaultOpener {

        override suspend fun seal(note: Note): Note = note.copy(
            title = "",
            content = "",
            encrypted = EncryptedBody("${note.folderId}\u0000${note.title}\u0000${note.content}".toByteArray()),
            encVersion = EncryptedFormat.TITLE_AND_CONTENT,
        )

        override suspend fun decrypt(note: Note): Note {
            val blob = note.encrypted ?: return note
            val morceaux = String(blob.toByteArray()).split('\u0000', limit = 3)
            check(morceaux[0] == note.folderId) {
                "blob scelle par ${morceaux[0]}, presente comme appartenant a ${note.folderId}"
            }
            return note.copy(title = morceaux[1], content = morceaux[2], encrypted = null)
        }

        /** Le dossier qui a scellé ce blob — ce que l'assertion regarde. */
        fun coffreDOrigine(note: Note): String =
            String(requireNotNull(note.encrypted).toByteArray()).substringBefore('\u0000')
    }

    /**
     * Un ouvreur qui rend la note **inchangée**, donc encore scellée.
     *
     * C'est le jumeau de [ScelleurNegligent], et il est plus dangereux que lui : un scelleur
     * négligent laisse fuir, un ouvreur négligent **détruit**. `unlockNote` écrirait alors
     * `content = ""` et `title = ""` — la note serait vidée, et son blob effacé avec.
     */
    private class OuvreurNegligent : VaultOpener {
        override suspend fun decrypt(note: Note): Note = note
    }
}
