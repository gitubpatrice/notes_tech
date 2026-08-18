package com.filestech.notes_tech.security.vault

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.core.crypto.SecretBytes
import com.filestech.notes_tech.data.local.DatabaseProvider
import com.filestech.notes_tech.data.local.LegacyDatabaseFixture
import com.filestech.notes_tech.data.local.LegacyDatabaseLocation
import com.filestech.notes_tech.data.local.NotesDatabaseFactory
import com.filestech.notes_tech.data.local.SqlCipherRawKey
import com.filestech.notes_tech.data.local.entity.FolderEntity
import com.filestech.notes_tech.data.prefs.LegacyPreferences
import com.filestech.notes_tech.data.repository.FoldersRepository
import com.filestech.notes_tech.data.repository.NotesRepository
import com.filestech.notes_tech.domain.model.EncryptedFormat
import com.filestech.notes_tech.domain.model.VaultMode
import com.filestech.notes_tech.security.kek.KekRepository
import com.filestech.notes_tech.security.kek.WritableKekSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * Les coffres, contre du vrai SQLCipher et le vrai schéma hérité.
 *
 * ## Pourquoi instrumentés
 *
 * Deux raisons distinctes, et il faut les tenir séparées. La base d'abord : les gardes de
 * `provisionPassphraseVault` sont **dans le SQL**, et un double factice ne les exercerait pas. Le
 * Keystore ensuite : il n'existe que sur un appareil.
 *
 * ## 🔴 Ce que ces tests NE prouvent pas
 *
 * Qu'un coffre créé par l'application publiée s'ouvre. `laCleDunCoffreEcritParFlutterSOuvre` s'en
 * approche autant qu'il est possible ici — il injecte en base les colonnes **réellement produites
 * par le Dart** et les ouvre depuis Kotlin — mais les octets viennent d'un vecteur, pas du
 * téléphone de quelqu'un. Le critère de sortie de la phase 4 reste inchangé.
 *
 * Et pour le mode à code, l'écart est structurel : la clé du Keystore est liée à l'UID, et la build
 * de portage porte un `applicationId` suffixé `.next`. Elle ne PEUT pas voir les clés de
 * l'application publiée. Cf. `docs/06-ISOLATION-PENDANT-LE-CHANTIER.md`.
 */
@RunWith(AndroidJUnit4::class)
class FolderVaultServiceTest {

    private lateinit var context: Context
    private lateinit var databaseFile: File
    private val kek = ByteArray(SqlCipherRawKey.KEY_SIZE_BYTES) { (it * 7 + 5).toByte() }
    private val horloge = HorlogeReglable(Instant.ofEpochMilli(1_700_000_000_000L))
    private val horlogeMonotone = HorlogeMonotoneReglable()

    private lateinit var provider: DatabaseProvider
    private lateinit var dossiers: FoldersRepository
    private lateinit var notes: NotesRepository
    private lateinit var sessions: VaultSessions
    private lateinit var keystore: KeystoreEnMemoire
    private lateinit var journal: VaultWipeJournal
    private lateinit var coffres: FolderVaultService

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        System.loadLibrary("sqlcipher")
        databaseFile = File(context.cacheDir, "vault-fixture/notes_tech.db")
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
        sessions = VaultSessions(horlogeMonotone)
        keystore = KeystoreEnMemoire()
        journal = VaultWipeJournal(LegacyPreferences(context))
        coffres = FolderVaultService(provider, keystore, sessions, journal, horloge)
        dossiers = FoldersRepository(provider, horloge)
        // Le service tient les deux contrats : il scelle et il ouvre. Ce sont deux dépendances
        // distinctes du dépôt, pas une seule — cf. `VaultOpener`.
        notes = NotesRepository(provider, dossiers, coffres, coffres, horloge)

        // Le journal vit dans les préférences réelles : un test précédent ne doit pas en léguer.
        journal.pendingFolderIds().forEach(journal::clearPending)
    }

    @After
    fun tearDown(): Unit = runBlocking {
        sessions.lockAll()
        journal.pendingFolderIds().forEach(journal::clearPending)
        provider.close()
        LegacyDatabaseLocation.SIDECAR_SUFFIXES.forEach { File(databaseFile.path + it).delete() }
    }

    // ── Coffre à phrase secrète ──────────────────────────────────────────────────────────────────

    @Test
    fun un_coffre_cree_puis_verrouille_se_rouvre_avec_sa_phrase(): Unit = runBlocking {
        coffres.createPassphraseVault(DOSSIER, PHRASE)
        val note = notes.create(folderId = DOSSIER, title = "Relevé", content = "IBAN FR76")

        // La note doit être partie chiffrée : c'est le scelleur réel qui vient d'être branché.
        assertThat(note.isLocked).isTrue()
        assertThat(note.title).isEmpty()
        assertThat(note.content).isEmpty()
        assertThat(note.encVersion).isEqualTo(EncryptedFormat.TITLE_AND_CONTENT)

        coffres.lock(DOSSIER)
        assertThat(coffres.isUnlocked(DOSSIER)).isFalse()

        coffres.unlockWithPassphrase(DOSSIER, PHRASE)
        val relue = coffres.decrypt(notes.find(note.id)!!)
        assertThat(relue.title).isEqualTo("Relevé")
        assertThat(relue.content).isEqualTo("IBAN FR76")
    }

    /**
     * ⚠️ Le contrôle qui compte vraiment : ce qu'il y a **sur le disque**.
     *
     * Vérifier que l'objet rendu est chiffré ne prouve rien sur ce qui a été écrit. Ici on relit la
     * ligne sans passer par le service, et ni le titre ni le contenu ne doivent y figurer.
     */
    @Test
    fun rien_de_lisible_ne_reste_en_base_apres_le_scellement(): Unit = runBlocking {
        coffres.createPassphraseVault(DOSSIER, PHRASE)
        val note = notes.create(folderId = DOSSIER, title = "Codes", content = "1234 secret")

        val ligne = provider.get().noteDao().findById(note.id)!!
        assertThat(ligne.title).isEmpty()
        assertThat(ligne.content).isEmpty()
        assertThat(ligne.encryptedContent).isNotNull()
        val blob = String(ligne.encryptedContent!!, Charsets.ISO_8859_1)
        assertThat(blob).doesNotContain("Codes")
        assertThat(blob).doesNotContain("secret")
    }

    // ── Conversion d'un dossier existant ─────────────────────────────────────────────────────────

    /**
     * Le geste central de la conversion : les notes **déjà présentes** passent sous la clé.
     *
     * ⚠️ `encryptAllNotesInFolder` n'a eu **aucun appelant** jusqu'au 2026-08-14 alors que la
     * documentation affirmait le contraire — un chemin mort trouvé par l'audit de cohérence, et qui
     * n'avait jusqu'ici aucun test pour le tenir.
     */
    @Test
    fun convertir_un_dossier_chiffre_les_notes_deja_presentes(): Unit = runBlocking {
        val avant = notes.create(folderId = DOSSIER, title = "Ancienne", content = "texte en clair")
        assertThat(provider.get().noteDao().findById(avant.id)!!.encryptedContent).isNull()

        coffres.createPassphraseVault(DOSSIER, PHRASE)
        val bilan = coffres.encryptAllNotesInFolder(DOSSIER)

        assertThat(bilan.failed).isEqualTo(0)
        assertThat(bilan.done).isAtLeast(1)

        val ligne = provider.get().noteDao().findById(avant.id)!!
        assertThat(ligne.encryptedContent).isNotNull()
        assertThat(ligne.title).isEmpty()
        assertThat(ligne.content).isEmpty()
    }

    /** Le chiffrement de masse est borné à SON dossier. Un `WHERE` oublié emporterait la base. */
    @Test
    fun le_chiffrement_de_masse_ne_touche_pas_aux_notes_dun_autre_dossier(): Unit = runBlocking {
        val ailleurs = notes.create(
            folderId = FolderEntity.INBOX_ID,
            title = "Hors coffre",
            content = "doit rester lisible",
        )

        coffres.createPassphraseVault(DOSSIER, PHRASE)
        coffres.encryptAllNotesInFolder(DOSSIER)

        val ligne = provider.get().noteDao().findById(ailleurs.id)!!
        assertThat(ligne.encryptedContent).isNull()
        assertThat(ligne.content).isEqualTo("doit rester lisible")
    }

    /**
     * 🔴 **Le rattrapage**, celui qui tourne juste après une conversion partielle.
     *
     * Le dossier est déjà marqué coffre : une note restée en clair y est lisible au repos **sous un
     * cadenas**. C'est l'état que cette méthode existe pour ne pas laisser durer.
     */
    @Test
    fun la_reprotection_reprend_une_note_restee_en_clair(): Unit = runBlocking {
        val oubliee = notes.create(folderId = DOSSIER, title = "Oubliée", content = "en clair")
        // Le dossier devient un coffre SANS que le contenu soit chiffré : exactement ce que laisse
        // une conversion dont le premier passage a échoué sur cette note.
        coffres.createPassphraseVault(DOSSIER, PHRASE)
        assertThat(provider.get().noteDao().findById(oubliee.id)!!.content).isEqualTo("en clair")

        val reprises = coffres.reprotectPlaintextNotes(DOSSIER)

        assertThat(reprises).isAtLeast(1)
        val ligne = provider.get().noteDao().findById(oubliee.id)!!
        assertThat(ligne.encryptedContent).isNotNull()
        assertThat(ligne.content).isEmpty()
        assertThat(ligne.title).isEmpty()
    }

    /**
     * 🔴🔴 **État mixte — un blob ET du clair : le blob fait foi.**
     *
     * L'invariant est écrit dans le code depuis le début et n'avait aucun test. Rechiffrer la
     * colonne claire écraserait un blob **potentiellement plus récent** : ce serait perdre la
     * dernière modification pour réparer une incohérence. On efface le clair, on garde le blob.
     */
    @Test
    fun un_etat_mixte_garde_le_blob_et_efface_le_clair(): Unit = runBlocking {
        coffres.createPassphraseVault(DOSSIER, PHRASE)
        val note = notes.create(folderId = DOSSIER, title = "Scellée", content = "version chiffrée")
        val blobAvant = provider.get().noteDao().findById(note.id)!!.encryptedContent!!.copyOf()

        // On réinjecte du clair À CÔTÉ du blob, sans y toucher : l'incohérence que la réparation
        // doit résoudre sans rien perdre.
        provider.get().openHelper.writableDatabase.execSQL(
            "UPDATE notes SET content = ?, title = ? WHERE id = ?",
            arrayOf<Any?>("clair reinjecte", "Titre reinjecte", note.id),
        )

        val reprises = coffres.reprotectPlaintextNotes(DOSSIER)

        assertThat(reprises).isAtLeast(1)
        val ligne = provider.get().noteDao().findById(note.id)!!
        assertThat(ligne.encryptedContent).isEqualTo(blobAvant)
        assertThat(ligne.content).isEmpty()
        assertThat(ligne.title).isEmpty()
    }

    /**
     * ⚠️ Une réparation silencieuse ne doit pas faire remonter les notes en tête de « modifiées
     * récemment ». Sans ce test, `updatedAt = null` pourrait devenir `clock.millis()` sans que rien
     * ne le signale — et tout le dossier remonterait à chaque ouverture du coffre.
     */
    @Test
    fun la_reprotection_ne_remonte_pas_les_notes_dans_la_liste(): Unit = runBlocking {
        val note = notes.create(folderId = DOSSIER, title = "Oubliée", content = "en clair")
        coffres.createPassphraseVault(DOSSIER, PHRASE)
        val avant = provider.get().noteDao().findById(note.id)!!.updatedAt

        horloge.avance(60_000)
        coffres.reprotectPlaintextNotes(DOSSIER)

        assertThat(provider.get().noteDao().findById(note.id)!!.updatedAt).isEqualTo(avant)
    }

    @Test
    fun une_mauvaise_phrase_est_refusee_et_arme_le_freinage(): Unit = runBlocking {
        coffres.createPassphraseVault(DOSSIER, PHRASE)
        coffres.lock(DOSSIER)

        assertThrows(WrongSecretException::class.java) {
            runBlocking { coffres.unlockWithPassphrase(DOSSIER, "une autre phrase") }
        }
        assertThat(coffres.isUnlocked(DOSSIER)).isFalse()

        // La tentative suivante est refusée AVANT toute dérivation : c'est ce qui rend un
        // dictionnaire coûteux.
        assertThrows(VaultLockoutInProgressException::class.java) {
            runBlocking { coffres.unlockWithPassphrase(DOSSIER, PHRASE) }
        }

        horlogeMonotone.avance(1_000)
        coffres.unlockWithPassphrase(DOSSIER, PHRASE)
        assertThat(coffres.isUnlocked(DOSSIER)).isTrue()
    }

    /**
     * 🔴 Le test le plus proche du critère de sortie de la phase 4.
     *
     * Les quatre colonnes injectées ici ont été **produites par le vrai code Dart** de la 2.0.3, et
     * recoupées contre OpenSSL et le C de référence d'Argon2 (`docs/09-VECTEURS-DE-PARITE.md`). Le
     * service ne reçoit que la phrase secrète et doit tout retrouver, y compris le contenu d'une
     * note chiffrée par ce Dart-là.
     *
     * ⚠️ Ce n'est **pas** un coffre pris sur le téléphone d'un utilisateur. Les octets sont
     * authentiques, leur provenance ne l'est qu'à moitié.
     */
    @Test
    fun la_cle_dun_coffre_ecrit_par_flutter_souvre_depuis_kotlin(): Unit = runBlocking {
        val vecteur = VecteurCoffreFlutter
        // ⚠️ Le dossier porte l'identifiant DU VECTEUR, pas celui du jeu d'essai. Il sert d'AAD au
        // scellement de la clé : le changer ferait échouer l'étiquette GCM, et le test conclurait à
        // une phrase secrète fausse là où seul l'identifiant aurait bougé.
        val base = provider.get().openHelper.writableDatabase
        base.execSQL(
            """
            INSERT INTO folders (id, name, parent_id, color, icon, created_at, updated_at,
                                 vault_salt, vault_kek_wrapped, vault_iv, vault_verifier,
                                 vault_mode, vault_pin_blob, vault_pin_iv, vault_attempts)
            VALUES (?, 'Coffre Flutter', NULL, NULL, NULL, 1, 1, ?, ?, ?, ?, 'passphrase', NULL, NULL, 0)
            """.trimIndent(),
            arrayOf(vecteur.FOLDER_ID, vecteur.SEL, vecteur.KEK_EMBALLEE, vecteur.NONCE, vecteur.VERIFICATEUR),
        )
        base.execSQL(
            """
            INSERT INTO notes (id, title, content, encrypted_content, folder_id, tags, pinned,
                               favorite, archived, trashed_at, created_at, updated_at, enc_v)
            VALUES (?, '', '', ?, ?, '', 0, 0, 0, NULL, 1, 1, 2)
            """.trimIndent(),
            arrayOf(vecteur.NOTE_ID, vecteur.NOTE_BLOB, vecteur.FOLDER_ID),
        )

        coffres.unlockWithPassphrase(vecteur.FOLDER_ID, vecteur.PASSPHRASE)

        val relue = coffres.decrypt(notes.find(vecteur.NOTE_ID)!!)
        assertThat(relue.title).isEqualTo(vecteur.NOTE_TITRE)
        assertThat(relue.content).isEqualTo(vecteur.NOTE_CONTENU)
    }

    // ── Migration du format hérité v1 vers v2 ───────────────────────────────────────────────────

    /**
     * 🔴🔴 **Le chemin qui perdrait les titres, et que rien n'exerçait — ni ici, ni dans le publié.**
     *
     * Le format v1 laisse le **titre en clair** dans sa colonne et ne chiffre que le contenu. Le v2
     * met les deux dans le blob. La migration doit donc, en une seule écriture, poser le nouveau blob
     * **et** vider la colonne de titre. Une inversion, une écriture partielle, un `pack` qui oublie le
     * titre — et le titre disparaît des deux côtés, sans erreur.
     *
     * ⚠️⚠️ **L'application publiée porte une fonction faite EXPRÈS pour rendre ce chemin vérifiable**
     * — `encryptNoteLegacyV1`, `@visibleForTesting`, dont le commentaire dit : *« sans ça, la
     * migration v1 → v2 ne serait vérifiable que sur des données simulées, c'est-à-dire pas vérifiée
     * du tout : c'est précisément le chemin où une erreur ferait perdre les titres des
     * utilisateurs »*. **Aucun test ne l'appelle.** Le seul test Dart qui mentionne la migration
     * `grep` le code source pour s'assurer que l'appel existe — il mesure une chaîne de caractères,
     * pas un comportement.
     *
     * Ce cas-ci fabrique une note v1 **avec la vraie clé du coffre** — celle que le service vient de
     * dériver — et non avec une clé de laboratoire. Le format du blob, lui, est déjà recoupé octet
     * pour octet contre le Dart par `PariteCoffreAvecFlutterTest.lesBlobsDeNoteSeRelisent`.
     *
     * ⚠️ Le **témoin avant migration** n'est pas décoratif : sans lui, un test qui trouve le titre
     * dans le blob à la fin ne distinguerait pas « migré » de « n'a jamais été en v1 ».
     */
    @Test
    fun une_note_au_format_v1_est_migree_en_v2_SANS_perdre_son_titre(): Unit = runBlocking {
        coffres.createPassphraseVault(DOSSIER, PHRASE)
        val idNote = "11111111-2222-4333-8444-555555555555"
        poserUneNoteV1(idNote, titre = TITRE_V1, contenu = CONTENU_V1)

        // Témoin : la note est bien en v1, titre en clair dans sa colonne.
        assertThat(colonne(idNote, "enc_v")).isEqualTo("1")
        assertThat(colonne(idNote, "title")).isEqualTo(TITRE_V1)

        coffres.lock(DOSSIER)
        coffres.unlockWithPassphrase(DOSSIER, PHRASE)

        // Le format a changé, et la colonne claire est vidée : le titre n'est plus qu'à un endroit.
        assertThat(colonne(idNote, "enc_v")).isEqualTo("2")
        assertThat(colonne(idNote, "title")).isEmpty()

        // 🔴 Et il n'a pas disparu pour autant.
        val relue = coffres.decrypt(notes.find(idNote)!!)
        assertThat(relue.title).isEqualTo(TITRE_V1)
        assertThat(relue.content).isEqualTo(CONTENU_V1)
    }

    /**
     * 🔴🔴 **Une note v1 illisible doit être LAISSÉE INTACTE, pas détruite.**
     *
     * C'est la propriété qui sépare une migration d'une perte de données. Un blob corrompu — support
     * abîmé, restauration partielle, clé d'un autre coffre — ne se déchiffre pas ; la tentation est
     * d'écrire quand même « ce qu'on a pu lire », et le titre encore présent dans la colonne claire
     * partirait avec.
     *
     * ⚠️ Le témoin est la **note saine du même dossier** : sans elle, un test où rien ne bouge
     * passerait aussi bien sur une migration qui ne trouve **aucune** note — c'est-à-dire sur une
     * requête cassée. Ici l'une migre pendant que l'autre est épargnée, dans le même passage.
     */
    @Test
    fun une_note_v1_illisible_est_laissee_INTACTE_et_ne_bloque_pas_les_autres(): Unit = runBlocking {
        coffres.createPassphraseVault(DOSSIER, PHRASE)
        val saine = "aaaaaaaa-2222-4333-8444-555555555555"
        val abimee = "bbbbbbbb-2222-4333-8444-555555555555"
        poserUneNoteV1(saine, titre = TITRE_V1, contenu = CONTENU_V1)
        poserUneNoteV1(abimee, titre = "Titre a sauver", contenu = "peu importe", corrompre = true)

        coffres.lock(DOSSIER)
        coffres.unlockWithPassphrase(DOSSIER, PHRASE)

        // La saine est passée en v2 : la migration a bien tourné sur ce dossier.
        assertThat(colonne(saine, "enc_v")).isEqualTo("2")

        // L'abîmée n'a pas bougé — et surtout, son titre est toujours là.
        assertThat(colonne(abimee, "enc_v")).isEqualTo("1")
        assertThat(colonne(abimee, "title")).isEqualTo("Titre a sauver")
    }

    /**
     * Écrit une note au format v1 : blob = nonce ‖ scellé du **contenu seul**, titre laissé en clair.
     *
     * ⚠️ Scellée avec la clé de session **réelle** du coffre, pas une clé fabriquée : une clé de
     * laboratoire mesurerait la crypto, pas la migration.
     *
     * @param corrompre retourne un octet du scellé — l'étiquette GCM refusera, comme sur un support
     *   abîmé.
     */
    private suspend fun poserUneNoteV1(id: String, titre: String, contenu: String, corrompre: Boolean = false) {
        val cle = requireNotNull(sessions.sessionKey(DOSSIER)) { "le coffre doit etre ouvert" }
        val nonce = ByteArray(VaultParams.NONCE_BYTES) { (it + 1).toByte() }
        val scelle = VaultCrypto.seal(
            key = cle,
            nonce = nonce,
            plaintext = contenu.toByteArray(Charsets.UTF_8),
            aad = id.toByteArray(Charsets.UTF_8),
        )
        cle.fill(0)
        if (corrompre) scelle[0] = (scelle[0].toInt() xor 0xFF).toByte()

        provider.get().openHelper.writableDatabase.execSQL(
            """
            INSERT INTO notes (id, title, content, encrypted_content, folder_id, tags, pinned,
                               favorite, archived, trashed_at, created_at, updated_at, enc_v)
            VALUES (?, ?, '', ?, ?, '', 0, 0, 0, NULL, 1, 1, 1)
            """.trimIndent(),
            arrayOf(id, titre, NoteEnvelope.composeBlob(nonce, scelle), DOSSIER),
        )
    }

    /** Lit une colonne brute — ce que le DAO rend est déjà interprété, et masquerait la migration. */
    private suspend fun colonne(idNote: String, nom: String): String {
        val base = provider.get().openHelper.writableDatabase
        return base.query("SELECT $nom FROM notes WHERE id = ?", arrayOf(idNote)).use { curseur ->
            assertThat(curseur.moveToFirst()).isTrue()
            curseur.getString(0) ?: ""
        }
    }

    @Test
    fun convertir_un_dossier_deja_coffre_est_refuse(): Unit = runBlocking {
        coffres.createPassphraseVault(DOSSIER, PHRASE)

        val erreur = assertThrows(VaultValidationException::class.java) {
            runBlocking { coffres.createPassphraseVault(DOSSIER, "une autre phrase encore") }
        }
        assertThat(erreur.reason).isEqualTo(VaultValidationException.Reason.ALREADY_A_VAULT)
    }

    // ── Coffre à code ────────────────────────────────────────────────────────────────────────────

    @Test
    fun un_coffre_a_code_se_rouvre_avec_son_code(): Unit = runBlocking {
        coffres.createPinVault(DOSSIER, CODE)
        val note = notes.create(folderId = DOSSIER, title = "Carte", content = "PIN 0000")
        coffres.lock(DOSSIER)

        coffres.unlockWithPin(DOSSIER, CODE)

        assertThat(coffres.decrypt(notes.find(note.id)!!).content).isEqualTo("PIN 0000")
        assertThat(provider.get().folderDao().vaultAttempts(DOSSIER)).isEqualTo(0)
    }

    @Test
    fun cinq_codes_faux_detruisent_le_coffre_et_ses_notes(): Unit = runBlocking {
        coffres.createPinVault(DOSSIER, CODE)
        val note = notes.create(folderId = DOSSIER, title = "Carte", content = "à perdre")
        coffres.lock(DOSSIER)

        repeat(VaultParams.PIN_MAX_ATTEMPTS - 1) { tour ->
            horlogeMonotone.avance(60_000)
            val erreur = assertThrows(WrongPinException::class.java) {
                runBlocking { coffres.unlockWithPin(DOSSIER, "999999") }
            }
            assertThat(erreur.attemptsRemaining).isEqualTo(VaultParams.PIN_MAX_ATTEMPTS - tour - 1)
        }

        horlogeMonotone.avance(60_000)
        assertThrows(VaultPinWipedException::class.java) {
            runBlocking { coffres.unlockWithPin(DOSSIER, "999999") }
        }

        // Le coffre est démoli : clé partie, notes verrouillées supprimées, dossier redevenu
        // ordinaire — et le drapeau de reprise retiré, puisque l'effacement est allé au bout.
        assertThat(keystore.hasKey(VaultParams.pinKeystoreAlias(DOSSIER))).isFalse()
        assertThat(notes.find(note.id)).isNull()
        assertThat(provider.get().folderDao().vaultMaterial(DOSSIER)!!.isVault).isFalse()
        assertThat(journal.pendingFolderIds()).doesNotContain(DOSSIER)
    }

    /**
     * 🔴 Le défaut que la version Flutter a corrigé en v1.0.3, et qu'il ne faut pas réintroduire.
     *
     * Un Keystore momentanément indisponible — mise à jour du système en cours, écran verrouillé —
     * ne prouve rien sur le code saisi. Le compter reviendrait à détruire le coffre d'un
     * utilisateur qui n'a rien fait de mal, en cinq lancements d'application.
     */
    @Test
    fun un_keystore_indisponible_ne_consomme_pas_de_tentative(): Unit = runBlocking {
        coffres.createPinVault(DOSSIER, CODE)
        coffres.lock(DOSSIER)
        keystore.panneTransitoire = true

        assertThrows(KeystoreUnavailableException::class.java) {
            runBlocking { coffres.unlockWithPin(DOSSIER, CODE) }
        }

        assertThat(provider.get().folderDao().vaultAttempts(DOSSIER)).isEqualTo(0)

        keystore.panneTransitoire = false
        coffres.unlockWithPin(DOSSIER, CODE)
        assertThat(coffres.isUnlocked(DOSSIER)).isTrue()
    }

    @Test
    fun une_cle_definitivement_invalidee_detruit_le_coffre(): Unit = runBlocking {
        coffres.createPinVault(DOSSIER, CODE)
        coffres.lock(DOSSIER)
        keystore.invalidationPermanente = true

        assertThrows(VaultPinWipedException::class.java) {
            runBlocking { coffres.unlockWithPin(DOSSIER, CODE) }
        }
        assertThat(provider.get().folderDao().vaultMaterial(DOSSIER)!!.isVault).isFalse()
    }

    @Test
    fun un_code_hors_format_est_refuse_au_deverrouillage_comme_a_la_creation(): Unit = runBlocking {
        // La règle « quatre à six chiffres » doit valoir des deux côtés. Ne la poser qu'à la
        // création laisserait une divergence entre les deux chemins.
        assertThrows(VaultValidationException::class.java) {
            runBlocking { coffres.createPinVault(DOSSIER, "12") }
        }
        coffres.createPinVault(DOSSIER, CODE)
        coffres.lock(DOSSIER)

        val erreur = assertThrows(VaultValidationException::class.java) {
            runBlocking { coffres.unlockWithPin(DOSSIER, "abcd") }
        }
        assertThat(erreur.reason).isEqualTo(VaultValidationException.Reason.PIN_NOT_DIGITS_ONLY)
        // Et le refus de format ne consomme pas de tentative.
        assertThat(provider.get().folderDao().vaultAttempts(DOSSIER)).isEqualTo(0)
    }

    // ── Ce qui ne doit JAMAIS consommer une tentative ────────────────────────────────────────────

    /**
     * 🔴 Un vérificateur incohérent n'est pas un mauvais code.
     *
     * AES-GCM est authentifié : que la clé soit sortie prouve déjà que le code était bon. Un
     * vérificateur qui ne concorde pas ensuite décrit une base abîmée. La version publiée compte
     * pourtant un échec ici — cinq lectures d'une colonne corrompue y détruiraient un coffre dont
     * le code était bon à chaque fois. Relevé par les deux relectures externes du 2026-08-14.
     */
    @Test
    fun un_verificateur_incoherent_ne_consomme_pas_de_tentative(): Unit = runBlocking {
        coffres.createPinVault(DOSSIER, CODE)
        coffres.lock(DOSSIER)
        provider.get().openHelper.writableDatabase.execSQL(
            "UPDATE folders SET vault_verifier = ? WHERE id = ?",
            arrayOf(ByteArray(32) { 0x5A }, DOSSIER),
        )

        assertThrows(MalformedVaultDataException::class.java) {
            runBlocking { coffres.unlockWithPin(DOSSIER, CODE) }
        }

        assertThat(provider.get().folderDao().vaultAttempts(DOSSIER)).isEqualTo(0)
        assertThat(coffres.isUnlocked(DOSSIER)).isFalse()
    }

    @Test
    fun un_verificateur_incoherent_narme_pas_le_freinage_du_mode_phrase(): Unit = runBlocking {
        // Le jumeau du test précédent. Les deux modes doivent conclure « base abîmée », pas
        // « mauvais secret » — et une première version plantait ici au lieu de refuser.
        coffres.createPassphraseVault(DOSSIER, PHRASE)
        coffres.lock(DOSSIER)
        provider.get().openHelper.writableDatabase.execSQL(
            "UPDATE folders SET vault_verifier = ? WHERE id = ?",
            arrayOf(ByteArray(32) { 0x5A }, DOSSIER),
        )

        assertThrows(MalformedVaultDataException::class.java) {
            runBlocking { coffres.unlockWithPassphrase(DOSSIER, PHRASE) }
        }

        assertThat(coffres.lockoutRemainingMillis(DOSSIER)).isEqualTo(0)
    }

    /**
     * 🔴 Une colonne de mauvaise longueur ne prouve rien non plus.
     *
     * Un `vault_iv` tronqué fait lever une `IllegalArgumentException` — pas une `VaultException`.
     * Une première version ne reprenait l'incrément que pour les erreurs du Keystore : cinq
     * lectures de cette base auraient effacé le coffre.
     */
    @Test
    fun une_colonne_de_mauvaise_longueur_ne_consomme_pas_de_tentative(): Unit = runBlocking {
        coffres.createPinVault(DOSSIER, CODE)
        coffres.lock(DOSSIER)
        provider.get().openHelper.writableDatabase.execSQL(
            "UPDATE folders SET vault_iv = ? WHERE id = ?",
            arrayOf(ByteArray(4), DOSSIER),
        )

        runCatching { coffres.unlockWithPin(DOSSIER, CODE) }

        assertThat(provider.get().folderDao().vaultAttempts(DOSSIER)).isEqualTo(0)
    }

    /**
     * 🔴 Revenir en arrière pendant la dérivation ne coûte pas une tentative.
     *
     * Argon2id dure de l'ordre de la seconde : l'annulation est le cas le plus fréquent de tous
     * ceux qui ne prouvent rien. Sans `NonCancellable` autour de la reprise, la reprise serait
     * elle-même annulée, et cinq hésitations détruiraient le coffre.
     */
    @Test
    fun une_tentative_annulee_ne_consomme_pas_de_tentative(): Unit = runBlocking {
        coffres.createPinVault(DOSSIER, CODE)
        coffres.lock(DOSSIER)

        val portee = CoroutineScope(Dispatchers.IO)
        val travail = portee.launch { coffres.unlockWithPin(DOSSIER, CODE) }
        // Laisse l'incrément partir en base, puis annule pendant la dérivation.
        while (provider.get().folderDao().vaultAttempts(DOSSIER) == 0) {
            delay(1)
        }
        travail.cancelAndJoin()

        assertThat(provider.get().folderDao().vaultAttempts(DOSSIER)).isEqualTo(0)
    }

    /**
     * ⚠️ Un coffre à code dont l'étiquette `vault_mode` a été perdue reste ouvrable.
     *
     * Se fier à l'étiquette plutôt qu'aux colonnes rendrait ce coffre inouvrable **par les deux
     * chemins à la fois** : refusé côté code faute d'étiquette, refusé côté phrase secrète faute de
     * `vault_kek_wrapped`.
     */
    @Test
    fun un_coffre_a_code_sans_etiquette_reste_ouvrable(): Unit = runBlocking {
        coffres.createPinVault(DOSSIER, CODE)
        val note = notes.create(folderId = DOSSIER, title = "Carte", content = "PIN 0000")
        coffres.lock(DOSSIER)
        provider.get().openHelper.writableDatabase.execSQL(
            "UPDATE folders SET vault_mode = NULL WHERE id = ?",
            arrayOf(DOSSIER),
        )

        coffres.unlockWithPin(DOSSIER, CODE)

        assertThat(coffres.decrypt(notes.find(note.id)!!).content).isEqualTo("PIN 0000")
    }

    /**
     * ⚠️ Le JUMEAU du test précédent, et la raison pour laquelle il existe.
     *
     * Le service savait déduire le mode des colonnes ; la couche qui alimente l'interface, elle,
     * lisait encore l'étiquette. Le même coffre était donc reconnu par l'un et pas par l'autre —
     * personne ne l'aurait vu avant que l'interface n'existe. Relevé par un audit de cohérence, sur
     * un correctif appliqué le jour même à un seul des deux sites.
     */
    @Test
    fun le_domaine_lit_le_mode_dans_les_colonnes_lui_aussi(): Unit = runBlocking {
        coffres.createPinVault(DOSSIER, CODE)
        provider.get().openHelper.writableDatabase.execSQL(
            "UPDATE folders SET vault_mode = NULL WHERE id = ?",
            arrayOf(DOSSIER),
        )

        val dossier = dossiers.find(DOSSIER)!!

        assertThat(dossier.isVault).isTrue()
        assertThat(dossier.vault!!.mode).isEqualTo(VaultMode.PIN)
    }

    // ── Reprise d'un effacement interrompu ───────────────────────────────────────────────────────

    @Test
    fun un_effacement_interrompu_est_repris_au_demarrage(): Unit = runBlocking {
        coffres.createPinVault(DOSSIER, CODE)
        val note = notes.create(folderId = DOSSIER, title = "Carte", content = "à perdre")
        coffres.lock(DOSSIER)
        // Ce que laisse une application tuée entre le drapeau et le premier geste.
        journal.markPending(DOSSIER)

        coffres.resumePendingWipes()

        assertThat(notes.find(note.id)).isNull()
        assertThat(provider.get().folderDao().vaultMaterial(DOSSIER)!!.isVault).isFalse()
        assertThat(journal.pendingFolderIds()).doesNotContain(DOSSIER)
    }

    /**
     * ⚠️ Le drapeau d'un dossier disparu est retiré — c'est le **seul** retrait légitime.
     *
     * Le retirer sur échec, en revanche, annulerait définitivement un effacement déclenché par cinq
     * codes faux : il suffirait de provoquer un plantage au démarrage pour sauver le coffre qu'on
     * vient de faire condamner.
     */
    @Test
    fun le_drapeau_dun_dossier_disparu_est_retire(): Unit = runBlocking {
        journal.markPending("dossier-qui-nexiste-plus")

        coffres.resumePendingWipes()

        assertThat(journal.pendingFolderIds()).doesNotContain("dossier-qui-nexiste-plus")
    }

    // ── Constat après annulation ─────────────────────────────────────────────────────────────────

    /**
     * `isVault` répond sur la **base**, pas sur la session — c'est toute sa raison d'être.
     *
     * L'état qu'il doit savoir décrire est celui d'une création annulée trop tard : le matériel du
     * coffre est écrit, `sessions.open` n'a jamais tourné. Un contrôle qui passerait par
     * `isUnlocked` répondrait « non » et laisserait l'utilisateur croire à une annulation propre.
     *
     * Le test le reproduit en verrouillant après coup, ce qui met la base et la session dans le même
     * désaccord — la course elle-même, elle, ne se provoque pas depuis un test.
     *
     * @see com.filestech.notes_tech.ui.vault.VaultViewModel.cancelAttempt
     */
    @Test
    fun un_dossier_est_dit_coffre_meme_quand_aucune_session_nest_ouverte(): Unit = runBlocking {
        assertThat(coffres.isVault(DOSSIER)).isFalse()

        coffres.createPassphraseVault(DOSSIER, PHRASE)
        coffres.lock(DOSSIER)

        assertThat(coffres.isUnlocked(DOSSIER)).isFalse()
        assertThat(coffres.isVault(DOSSIER)).isTrue()
    }

    /** Un dossier qui n'existe pas n'est pas un coffre — et ne fait pas lever le constat. */
    @Test
    fun un_dossier_inconnu_nest_pas_un_coffre(): Unit = runBlocking {
        assertThat(coffres.isVault("dossier-qui-nexiste-pas")).isFalse()
    }

    // ── Doubles et utilitaires ───────────────────────────────────────────────────────────────────

    private companion object {
        const val DOSSIER = LegacyDatabaseFixture.Fixtures.FOLDER_WORK
        const val PHRASE = "ma phrase de coffre"
        const val CODE = "482913"

        /** ⚠️ Un titre ACCENTUÉ : un `pack` qui compterait des caractères et non des octets passerait sur « abc ». */
        const val TITRE_V1 = "Relevé de février"
        const val CONTENU_V1 = "Contenu hérité du format 1"
    }

    /**
     * Le Keystore, en mémoire.
     *
     * Le vrai est exercé par `AndroidVaultKeystoreTest`. Ici il faut pouvoir **provoquer** ses
     * pannes — indisponibilité passagère, invalidation définitive — puisque c'est la distinction
     * entre les deux qui décide de détruire ou non les notes de quelqu'un.
     */
    private class KeystoreEnMemoire : VaultKeystore {
        private val cles = mutableMapOf<String, ByteArray>()
        var panneTransitoire = false
        var invalidationPermanente = false

        override fun createKey(alias: String): Boolean {
            garde()
            if (cles.containsKey(alias)) return false
            cles[alias] = SecretBytes.randomBytes(VaultParams.FOLDER_KEY_BYTES)
            return true
        }

        override fun seal(alias: String, plaintext: ByteArray): SealedByKeystore {
            garde()
            val cle = cles[alias] ?: throw KeystoreUnavailableException()
            val nonce = VaultCrypto.newNonce()
            return SealedByKeystore(VaultCrypto.seal(cle, nonce, plaintext, ByteArray(0)), nonce)
        }

        override fun open(alias: String, sealed: SealedByKeystore): ByteArray {
            garde()
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

        private fun garde() {
            if (invalidationPermanente) throw KeystorePermanentlyInvalidatedException()
            if (panneTransitoire) throw KeystoreUnavailableException()
        }
    }

    private class HorlogeReglable(private var maintenant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = maintenant

        /** Faire avancer le temps est la seule facon de prouver qu'une ecriture N'A PAS touche `updated_at`. */
        fun avance(millis: Long) {
            maintenant = maintenant.plusMillis(millis)
        }
    }

    private class HorlogeMonotoneReglable : MonotonicClock {
        private var millis = 0L
        override fun elapsedMillis(): Long = millis

        fun avance(de: Long) {
            millis += de
        }
    }

    /**
     * Les octets d'un coffre **produits par le Dart de la 2.0.3**, recopiés de
     * `app/src/test/resources/parite/coffre_complet.tsv`.
     *
     * Répétés en dur ici plutôt que lus depuis la ressource : les ressources de test JVM ne sont
     * pas au chemin de classe d'un test instrumenté, et les dupliquer sur cinq lignes coûte moins
     * qu'un mécanisme de partage. Le contrôle de cohérence, lui, est fait par
     * `PariteCoffreAvecFlutterTest`, qui lit la ressource et vérifie les mêmes valeurs.
     */
    private object VecteurCoffreFlutter {
        const val PASSPHRASE = "ma passphrase de coffre 2026"
        const val FOLDER_ID = "a1b2c3d4-e5f6-4789-abcd-ef0123456789"
        const val NOTE_ID = "c0ffee00-dead-4bee-8fee-0123456789ab"
        const val NOTE_TITRE = "Relevé bancaire"
        const val NOTE_CONTENU = "IBAN FR76 — ne pas partager"
        val SEL: ByteArray = SecretBytes.fromHex("0f1e2d3c4b5a69788796a5b4c3d2e1f0")
        val NONCE: ByteArray = SecretBytes.fromHex("000000000000000000000001")
        val KEK_EMBALLEE: ByteArray = SecretBytes.fromHex(
            "602156805229e425e7c5608398e10df032e910ffaefa00262efd135b52cd8180beae5f745081c2d5ebcf13118e01439a",
        )
        val VERIFICATEUR: ByteArray = SecretBytes.fromHex(
            "857d32c0920d35df93570d264a5db3ff12266ffe85e311f5f1c03aaad40c6370",
        )
        val NOTE_BLOB: ByteArray = SecretBytes.fromHex(
            "0a0b0c0d0e0f1011121314159569aebdf78cde7e713492e2caaed3708f40f331f86c4bb5f8ebf964a1662447d1584f" +
                "32af2633c811541e37215aae89bbf3512defacaa919e4af5843214b96e8d",
        )
    }
}
