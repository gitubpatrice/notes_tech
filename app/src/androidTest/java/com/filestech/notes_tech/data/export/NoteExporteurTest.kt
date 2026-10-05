package com.filestech.notes_tech.data.export

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.core.crypto.SecretBytes
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.ZipInputStream

/**
 * **Ce que l'exporteur ÉCRIT sur le disque.**
 *
 * Ligne `note_export_service.dart` de `docs/05-PARITE.md`, critère écrit : *« export `.md` d'une
 * note de coffre — le corps ne doit pas être vide »*.
 *
 * ## 🔴🔴 Pourquoi ce fichier n'existait pas, et ce que ça laissait dehors
 *
 * `PariteExportAvecFlutterTest` couvre **22 cas**, tous justes, et tous sur `NoteMarkdown` et
 * `NoteArchive` — des fonctions pures : un nom de fichier, un frontmatter, une entrée d'archive.
 * `NoteExporter`, les 400 lignes qui **déchiffrent, décident de l'origine coffre, écrivent le
 * fichier et le retirent quand ça rate**, n'était exercé par aucun test, ni JVM ni instrumenté.
 *
 * C'est exactement le partage qui laisse passer le défaut que cette ligne de parité nomme : rendre
 * un Markdown correct à partir d'une note vide **est** le comportement attendu de `NoteMarkdown`.
 * Le corps vide ne se voit que là où quelqu'un décide s'il faut déchiffrer avant d'appeler.
 *
 * ## Pourquoi instrumentés
 *
 * SQLCipher est natif, le scellement passe par un `VaultKeystore`, et `FileProvider` demande un
 * vrai contexte. Un double de coffre ne prouverait rien ici : ce qu'on vérifie, c'est qu'un clair
 * **réellement chiffré en base** ressort **réellement** dans le fichier.
 *
 * ## ⚠️ Ce que ces cas NE prouvent pas
 *
 * Que le partage Android transmette le fichier. `ExportResult.uri` est construite et non ouverte ;
 * la suite du geste appartient au sélecteur du système.
 *
 * Et le repli de `dossierEstUnCoffre` — « une base indisponible ne doit pas faire échouer un export »
 * — nest **pas** exercé. `DatabaseProvider.close()` remet simplement linstance à zéro et la base se
 * rouvre au premier accès : un cas écrit ainsi passerait sans jamais atteindre le `catch`, et ce
 * serait un test vacant de plus. Le forcer demanderait de sceller la base comme le fait le mode
 * panique. ⚠️ *Le dire vaut mieux que de laisser croire que la ligne est couverte.*
 */
@RunWith(AndroidJUnit4::class)
class NoteExporteurTest {

    private lateinit var context: Context
    private lateinit var fichierDeBase: File
    private val kek = ByteArray(SqlCipherRawKey.KEY_SIZE_BYTES) { (it * 11 + 3).toByte() }
    private val horloge = HorlogeFigee(Instant.ofEpochMilli(1_760_000_000_000L))

    private lateinit var provider: DatabaseProvider
    private lateinit var dossiers: FoldersRepository
    private lateinit var notes: NotesRepository
    private lateinit var coffres: FolderVaultService
    private lateinit var exporteur: NoteExporter

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        System.loadLibrary("sqlcipher")
        fichierDeBase = File(context.cacheDir, "export-fixture/notes_tech.db")
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
        val journal = VaultWipeJournal(LegacyPreferences(context))
        coffres = FolderVaultService(provider, KeystoreEnMemoire(), VaultSessions(HorlogeMonotone()), journal, horloge)
        dossiers = FoldersRepository(provider, horloge)
        notes = NotesRepository(provider, dossiers, coffres, coffres, horloge)
        exporteur = NoteExporter(context, notes, dossiers, coffres, horloge)

        journal.pendingFolderIds().forEach(journal::clearPending)
        NoteExporter.purgerLesArchives(context)
    }

    @After
    fun tearDown(): Unit = runBlocking {
        coffres.lockAll()
        provider.close()
        LegacyDatabaseLocation.SIDECAR_SUFFIXES.forEach { File(fichierDeBase.path + it).delete() }
        // ⚠️ Ce répertoire porte du clair, y compris quand un cas échoue. Le mode panique compte
        // dessus, et un test qui en laisserait ferait échouer une mesure de panique sans rapport.
        NoteExporter.purgerLesArchives(context)
    }

    // ── Le défaut que la ligne de parité nomme ───────────────────────────────────────────────────

    /**
     * 🔴🔴 **Le corps ne doit pas être vide, et c'est un vrai défaut de l'application publiée.**
     *
     * Une note de coffre a `content` **vide** en base : le clair vit dans `encrypted_content`. Le
     * Dart de la 2.0.3 exportait la ligne brute et produisait un `.md` au frontmatter impeccable et
     * au corps vide — perte de données silencieuse, puisque l'export « réussissait ». Son correctif
     * est documenté à `note_editor_screen.dart:583` sous le nom C1.
     *
     * ⚠️ Le cas exige le **contenu**, pas seulement un fichier non vide : un frontmatter seul pèse
     * déjà deux cents octets. C'est la nuance qui a laissé passer le défaut d'origine.
     */
    @Test
    fun une_note_de_coffre_exportee_seule_sort_son_CLAIR(): Unit = runBlocking {
        val dossier = coffre()
        val note = notes.create(folderId = dossier, title = TITRE, content = CONTENU)

        // Le témoin qui donne son sens au reste : en base, il n'y a rien à lire.
        val ligne = provider.get().noteDao().findById(note.id)!!
        assertThat(ligne.content).isEmpty()
        assertThat(ligne.encryptedContent).isNotNull()

        val resultat = exporteur.exportOne(notes.find(note.id)!!, "Coffre", ::mention)
        val texte = fichierExporte(resultat.fileName).readText()

        assertThat(texte).contains(CONTENU)
        assertThat(texte).contains(TITRE)
    }

    /**
     * 🔴 Le jumeau du chemin archive : le fichier **dit** qu'il sort d'un coffre.
     *
     * Une fois déchiffrée, plus rien dans la note ne le raconte. Le suffixe ` [unlocked]` et la
     * mention YAML sont les deux seuls signaux, et l'export unitaire les omettait tous les deux.
     */
    @Test
    fun le_fichier_dune_note_de_coffre_porte_le_suffixe_ET_la_mention(): Unit = runBlocking {
        val dossier = coffre()
        val note = notes.create(folderId = dossier, title = TITRE, content = CONTENU)

        val resultat = exporteur.exportOne(notes.find(note.id)!!, DOSSIER_NOM, ::mention)

        assertThat(resultat.fileName).contains("[unlocked]")
        assertThat(fichierExporte(resultat.fileName).readText()).contains(mention("Coffre"))
    }

    /**
     * 🔴 **Une note EN CLAIR dans un dossier coffre porte les mêmes marques.**
     *
     * Cet état est réel : c'est ce qu'une conversion partielle laisse derrière elle. Le critère
     * `note.isLocked` seul le ratait — deux exports du même secret, une seule marque.
     *
     * ⚠️ Le témoin est la **note d'un dossier ordinaire**, exportée dans le même cas : sans elle, un
     * exporteur qui poserait le suffixe sur tout passerait aussi.
     */
    @Test
    fun une_note_claire_dans_un_dossier_coffre_porte_les_marques_elle_aussi(): Unit = runBlocking {
        val dossier = dossiers.create(DOSSIER_NOM).id
        val autre = dossiers.create("Courses").id
        val note = notes.create(folderId = dossier, title = TITRE, content = CONTENU)
        val ordinaire = notes.create(folderId = autre, title = "Liste", content = "pain, sel")
        // Le dossier devient un coffre APRÈS, et la note reste en clair : c'est ce qu'une
        // conversion interrompue laisse derrière elle.
        coffres.createPassphraseVault(dossier, PHRASE)
        assertThat(notes.find(note.id)!!.isLocked).isFalse()

        val duCoffre = exporteur.exportOne(notes.find(note.id)!!, DOSSIER_NOM, ::mention)
        assertThat(duCoffre.fileName).contains("[unlocked]")
        assertThat(fichierExporte(duCoffre.fileName).readText()).contains(mention(DOSSIER_NOM))

        val hors = exporteur.exportOne(notes.find(ordinaire.id)!!, "Courses", ::mention)
        assertThat(hors.fileName).doesNotContain("[unlocked]")
        assertThat(fichierExporte(hors.fileName).readText()).doesNotContain(mention("Courses"))
    }

    /**
     * 🔴🔴 **Coffre refermé : on lève, et on ne laisse RIEN sur le disque.**
     *
     * L'auto-verrouillage peut fermer la session entre le geste et le déchiffrement. Écrire quand
     * même produirait le corps vide du défaut d'origine ; écrire puis échouer laisserait un fichier
     * en clair partiel dans le cache.
     *
     * ⚠️ Le contrôle porte sur **le répertoire entier**, pas sur un nom attendu : un fichier laissé
     * sous un autre nom serait tout aussi lisible.
     */
    @Test
    fun un_coffre_referme_fait_echouer_l_export_sans_laisser_de_fichier(): Unit = runBlocking {
        val dossier = coffre()
        val note = notes.create(folderId = dossier, title = TITRE, content = CONTENU)
        val scellee = notes.find(note.id)!!
        coffres.lock(dossier)

        var leve = false
        try {
            exporteur.exportOne(scellee, DOSSIER_NOM, ::mention)
        } catch (e: Exception) {
            leve = true
        }
        assertThat(leve).isTrue()

        val restants = NoteExporter.repertoireDExport(context).walkTopDown().filter { it.isFile }.toList()
        assertThat(restants).isEmpty()
    }

    // ── L'archive ────────────────────────────────────────────────────────────────────────────────

    /**
     * 🔴🔴 **Une archive n'écrit jamais un blob, et le DIT.**
     *
     * Coffre fermé : la note ne peut pas être déchiffrée. Le repli rend la note **encore scellée**,
     * pour qu'elle soit comptée comme omise — et non une note vide, qui serait passée pour un export
     * réussi.
     *
     * ⚠️ Le contrôle lit les **octets du ZIP**, pas seulement le compte : un blob écrit sous un nom
     * de fichier anodin satisferait `skippedLocked` tout en sortant le chiffré du coffre.
     */
    @Test
    fun une_note_scellee_est_OMISE_de_l_archive_et_comptee(): Unit = runBlocking {
        // ⚠️ **Une référence MESURÉE, pas un compte écrit en dur.** La base d'essai est semée de
        // deux notes, dont une scellée : exiger « 1 omise » ferait échouer ce cas pour une raison
        // qui n'a rien à voir avec l'export, et le jour où la semence change, un compte figé
        // deviendrait faux sans que personne ne sache pourquoi. Mesuré ici, avant de rien créer.
        val reference = exporteur.exportAll("Boîte de réception", ::mention)
        // ⚠️ La référence a rendu son compte ; son archive doit disparaître, sinon deux fichiers
        // de même nom cohabitent — cf. [fichierExporte].
        NoteExporter.purgerLesArchives(context)

        val dossier = coffre()
        notes.create(folderId = dossier, title = TITRE, content = CONTENU)
        notes.create(folderId = dossiers.create("Courses").id, title = "Liste", content = "pain, sel")
        coffres.lock(dossier)

        val resultat = exporteur.exportAll("Boîte de réception", ::mention)

        assertThat(resultat.skippedLocked).isEqualTo(reference.skippedLocked + 1)
        assertThat(resultat.exported).isEqualTo(reference.exported + 1)

        val octets = fichierExporte(resultat.fileName).readBytes()
        val texte = String(octets, Charsets.ISO_8859_1)
        assertThat(texte).doesNotContain(CONTENU)
        assertThat(texte).doesNotContain(TITRE)
    }

    /**
     * Le symétrique, et c'est lui qui empêche le cas ci-dessus de passer sur un exporteur qui
     * n'exporterait **rien** : coffre ouvert, la note entre dans l'archive, en clair et marquée.
     */
    @Test
    fun un_coffre_OUVERT_entre_dans_l_archive_en_clair_et_marque(): Unit = runBlocking {
        val reference = exporteur.exportAll("Boîte de réception", ::mention)
        // ⚠️ La référence a rendu son compte ; son archive doit disparaître, sinon deux fichiers
        // de même nom cohabitent — cf. [fichierExporte].
        NoteExporter.purgerLesArchives(context)

        notes.create(folderId = coffre(), title = TITRE, content = CONTENU)

        val resultat = exporteur.exportAll("Boîte de réception", ::mention)

        // Rien de plus n'est omis : la note du coffre ouvert est SORTIE, pas contournée.
        assertThat(resultat.skippedLocked).isEqualTo(reference.skippedLocked)
        assertThat(resultat.exported).isEqualTo(reference.exported + 1)

        val entrees = entreesDuZip(fichierExporte(resultat.fileName))
        // ⚠️ L'assertion porte sur la liste ENTIÈRE des entrées : en cas d'échec, Truth l'imprime,
        // et on sait tout de suite sous quel nom la note est sortie.
        assertThat(entrees.keys.joinToString(" | ")).contains("[unlocked]")
        val note = entrees.entries.first { it.key.contains("[unlocked]") }
        assertThat(note.value).contains(CONTENU)
        assertThat(note.value).contains(mention(DOSSIER_NOM))
    }

    // ── Aides ────────────────────────────────────────────────────────────────────────────────────

    /** Un dossier coffre, créé comme l'application le crée : dossier d'abord, coffre ensuite. */
    private suspend fun coffre(nom: String = DOSSIER_NOM): String =
        dossiers.create(nom).id.also { coffres.createPassphraseVault(it, PHRASE) }

    private fun mention(dossier: String) = "<!-- venu du coffre $dossier -->"

    /**
     * Le fichier écrit, retrouvé par son nom sous la racine d'export.
     *
     * ⚠️⚠️ **`single`, jamais `first`.** L'horloge est figée : deux exports d'un même cas portent le
     * **même nom de fichier**, dans deux sous-répertoires différents. Un `first` rendait
     * silencieusement le premier — donc l'archive de RÉFÉRENCE — et un cas dont toutes les
     * assertions sont des absences (`doesNotContain`) passait au vert sur le mauvais fichier.
     * C'est arrivé le 2026-08-19. Ici, une ambiguïté doit faire échouer le cas, pas le décider.
     */
    private fun fichierExporte(nom: String): File =
        NoteExporter.repertoireDExport(context).walkTopDown().single { it.isFile && it.name == nom }

    private fun entreesDuZip(zip: File): Map<String, String> = buildMap {
        ZipInputStream(zip.inputStream()).use { flux ->
            while (true) {
                val entree = flux.nextEntry ?: break
                if (!entree.isDirectory) put(entree.name, flux.readBytes().toString(Charsets.UTF_8))
                flux.closeEntry()
            }
        }
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
        const val DOSSIER_NOM = "Coffre"
        const val PHRASE = "une phrase secrete de test 2026"
        const val TITRE = "Relevé bancaire"
        const val CONTENU = "IBAN FR76 — ne pas partager"
    }
}
