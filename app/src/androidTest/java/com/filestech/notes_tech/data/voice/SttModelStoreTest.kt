package com.filestech.notes_tech.data.voice

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.data.export.NoteExporter
import com.filestech.notes_tech.domain.voice.SttModel
import com.filestech.notes_tech.domain.voice.SttModelChecksumMismatchException
import com.filestech.notes_tech.domain.voice.SttModelSourceInvalidException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * L'import du modèle, **sur un vrai système de fichiers**.
 *
 * ## Pourquoi ces tests ne peuvent pas être des tests JVM
 *
 * Ce que `CopieVerifieeTest` couvre — l'empreinte, la borne, l'annulation — se vérifie sur des flux
 * en mémoire. Ce qui reste ne se vérifie que sur un disque : le fichier temporaire est-il **vraiment**
 * effacé quand l'empreinte ne correspond pas ? le renommage a-t-il **vraiment** eu lieu ? Or c'est
 * précisément là qu'un défaut serait grave — un modèle non conforme laissé en place, ou un import
 * annoncé réussi sans fichier derrière.
 *
 * ## 🔴 Ce que ces tests protègent
 *
 * L'application ne peut pas télécharger le modèle : l'utilisateur le récupère lui-même. **L'empreinte
 * est donc le seul contrôle d'origine qui existe** sur un binaire de plusieurs dizaines de mégaoctets
 * exécuté par une bibliothèque native sur le contenu des notes. Un contrôle qui laisserait passer, ou
 * qui garderait le fichier refusé pour un prochain essai, ne protégerait rien.
 *
 * ⚠️ Les modèles utilisés ici sont **fabriqués pour le test** : quelques kilo-octets, avec leur vraie
 * empreinte calculée à la volée. Utiliser une entrée du catalogue supposerait d'avoir ses 57 Mo sur
 * l'appareil de test.
 *
 * ## 🔴🔴 Ces tests travaillent dans un `filesDir` DÉTOURNÉ, et c'est une réparation
 *
 * Jusqu'au 2026-08-17, `@Before` et `@After` faisaient
 * `SttModelStore.repertoireDesModeles(context).deleteRecursively()` sur le **vrai** répertoire de
 * l'application. Deux tests exigent en outre que ce répertoire soit **vide** — c'est ce qui rendait
 * la purge indispensable à leur passage.
 *
 * Conséquence mesurée : lancer la suite instrumentée **détruisait le modèle de 57 Mo** que
 * l'utilisateur avait importé à la main, et `TranscriptionSurAppareilTest` — qui tourne après, par
 * ordre alphabétique — se trouvait **silencieusement ignoré** par son `assumeTrue`. La suite
 * affichait « OK (144 tests) » alors que le seul test qui prouve que la dictée transcrit n'avait pas
 * tourné, et que le fichier de l'utilisateur était perdu.
 *
 * ⚠️ Ce n'est **pas** le piège de `connectedAndroidTest` (AGP qui désinstalle) : la précaution prise
 * contre celui-là — lancer par `adb shell am instrument` — ne protégeait de rien ici, puisque la
 * destruction venait d'un test. Cf. `04-PIEGES.md` §72.
 *
 * Le détournement porte sur `getFilesDir()` **seul** : `cacheDir` reste le vrai, parce qu'un des
 * tests passe par le `FileProvider` de l'application, qui n'expose que `cache/exports/`.
 */
@RunWith(AndroidJUnit4::class)
class SttModelStoreTest {

    private val contexteReel: Context = ApplicationProvider.getApplicationContext()

    /**
     * Un contexte dont `filesDir` pointe vers le cache, pour que le magasin n'aille jamais écrire —
     * ni **effacer** — dans la zone privée réelle.
     */
    private val context: Context = object : ContextWrapper(contexteReel) {
        override fun getFilesDir(): File = File(contexteReel.cacheDir, "faux-files").apply { mkdirs() }
    }

    private lateinit var magasin: SttModelStore
    private lateinit var bacASable: File

    /** Les fichiers deposes dans `cache/exports/` pour etre servis, retires apres chaque test. */
    private val exposes = mutableListOf<File>()

    @Before
    fun preparer() {
        magasin = SttModelStore(context)
        bacASable = File(contexteReel.cacheDir, "import-test").apply { mkdirs() }
        SttModelStore.repertoireDesModeles(context).deleteRecursively()
    }

    @After
    fun nettoyer() {
        exposes.forEach { it.delete() }
        exposes.clear()
        bacASable.deleteRecursively()
        SttModelStore.repertoireDesModeles(context).deleteRecursively()
        // Le faux `filesDir` lui-même : sans ça, le prochain test hériterait de son contenu.
        context.filesDir.deleteRecursively()
    }

    /**
     * 🔴 **Le témoin de l'isolement.** Sans lui, le détournement de `filesDir` serait une intention :
     * une faute de frappe qui le ferait retomber sur le vrai répertoire rendrait tous les autres
     * tests verts, et détruirait de nouveau le modèle de l'utilisateur en silence.
     */
    @Test
    fun le_magasin_de_test_n_ecrit_jamais_dans_le_repertoire_reel_de_l_application() {
        val reel = SttModelStore.repertoireDesModeles(contexteReel)
        val detourne = SttModelStore.repertoireDesModeles(context)

        assertThat(detourne.absolutePath).isNotEqualTo(reel.absolutePath)
        assertThat(detourne.absolutePath).startsWith(contexteReel.cacheDir.absolutePath)
    }

    @Test
    fun unFichierConformeEstInstalleEtVerifiable() = runBlocking {
        val contenu = contenuDeTest(4_096)
        val modele = modelePour(contenu)
        val source = fichierSource("modele-valide.bin", contenu)

        val installe = magasin.importer(source.toUri(), modele)

        assertThat(installe.exists()).isTrue()
        assertThat(installe.readBytes()).isEqualTo(contenu)
        assertThat(installe.name).isEqualTo(modele.fileName)
        assertThat(magasin.estInstalle(modele)).isTrue()
        // ⚠️ Le fichier d'origine appartient à l'utilisateur : il peut vouloir le garder pour
        // réimporter plus tard, et l'import ne doit pas en disposer.
        assertThat(source.exists()).isTrue()
    }

    @Test
    fun uneEmpreinteFausseRefuseETEffaceLeFichierCopie() = runBlocking {
        val contenu = contenuDeTest(4_096)
        // Même taille, contenu différent : la garde de taille laisse passer, l'empreinte tranche.
        val impostieur = contenuDeTest(4_096, graine = 7)
        val modele = modelePour(contenu)
        val source = fichierSource("modele-impostieur.bin", impostieur)

        val echec = runCatching { magasin.importer(source.toUri(), modele) }.exceptionOrNull()

        assertThat(echec).isInstanceOf(SttModelChecksumMismatchException::class.java)
        // 🔴 Le point du test : le temporaire ne survit pas. Le garder inviterait à réessayer avec
        // exactement les mêmes octets, et laisserait un binaire non identifié dans la zone privée.
        val racine = SttModelStore.repertoireDesModeles(context)
        assertThat(racine.listFiles().orEmpty().map { it.name }).isEmpty()
        assertThat(magasin.estPresent(modele)).isFalse()
    }

    /**
     * 🔴 Le cas où la source **annonce** sa taille : le refus doit tomber **avant** toute lecture.
     *
     * ⚠️⚠️ Passe par un vrai `content://`, servi par le `FileProvider` de l'application. C'est le
     * seul moyen d'exercer ce chemin : un `file://` ne porte **pas** `OpenableColumns.SIZE`, donc la
     * garde de taille ne s'y déclenche jamais. La première version de ce test l'ignorait et
     * échouait — non pas parce que le code laissait passer le fichier, mais parce qu'il le refusait
     * **par l'empreinte**, un cran plus loin que ce que le test prétendait vérifier. Un test qui se
     * trompe de garde ne prouve rien de celle qu'il nomme.
     */
    @Test
    fun uneTailleAnnonceeSansRapportEstRefuseeAvantLecture() = runBlocking {
        val modele = modelePour(contenuDeTest(4_096))
        val source = sourceExposee("photo.jpg", contenuDeTest(64))

        val echec = runCatching { magasin.importer(source, modele) }.exceptionOrNull()

        assertThat(echec).isInstanceOf(SttModelSourceInvalidException::class.java)
        assertThat(SttModelStore.repertoireDesModeles(context).listFiles().orEmpty()).isEmpty()
    }

    /**
     * ⚠️ Le cas jumeau : la source **n'annonce rien**, et le contrôle doit tenir quand même.
     *
     * Certains fournisseurs — stockage en nuage, documents virtuels — ne rendent aucune taille. La
     * garde bon marché se tait alors, par construction, et c'est l'empreinte qui tranche. Ce que ce
     * test fige, c'est qu'elle tranche **et** que le fichier copié ne survit pas : sans quoi le
     * silence d'un tiers suffirait à laisser un binaire non identifié dans la zone privée.
     */
    @Test
    fun uneSourceMuetteSurSaTailleEstQuandMemeVerifiee() = runBlocking {
        val modele = modelePour(contenuDeTest(4_096))
        val source = fichierSource("sans-taille.bin", contenuDeTest(64))

        val echec = runCatching { magasin.importer(source.toUri(), modele) }.exceptionOrNull()

        assertThat(echec).isInstanceOf(SttModelChecksumMismatchException::class.java)
        assertThat(SttModelStore.repertoireDesModeles(context).listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun unSecondImportNeRelitPasLaSource() = runBlocking {
        val contenu = contenuDeTest(4_096)
        val modele = modelePour(contenu)
        val source = fichierSource("modele-valide.bin", contenu)
        magasin.importer(source.toUri(), modele)

        // ⚠️ La source disparaît entre les deux appels. Un second import qui la relirait échouerait ;
        // l'idempotence se voit donc ici, et pas au chronomètre.
        assertThat(source.delete()).isTrue()
        val secondPassage = magasin.importer(source.toUri(), modele)

        assertThat(secondPassage.readBytes()).isEqualTo(contenu)
    }

    @Test
    fun unModeleRemplaceUnPrecedentNonConforme() = runBlocking {
        val contenu = contenuDeTest(4_096)
        val modele = modelePour(contenu)
        // Un import précédent dont l'empreinte s'est révélée fausse au chargement : le fichier est
        // en place, au bon nom, avec le mauvais contenu.
        val racine = SttModelStore.repertoireDesModeles(context).apply { mkdirs() }
        File(racine, modele.fileName).writeBytes(contenuDeTest(4_096, graine = 9))

        val installe = magasin.importer(fichierSource("bon.bin", contenu).toUri(), modele)

        assertThat(installe.readBytes()).isEqualTo(contenu)
        assertThat(magasin.estInstalle(modele)).isTrue()
    }

    @Test
    fun laPurgeEmporteToutLeRepertoire() = runBlocking {
        val contenu = contenuDeTest(4_096)
        val modele = modelePour(contenu)
        magasin.importer(fichierSource("bon.bin", contenu).toUri(), modele)

        SttModelStore.purgerLesModeles(context)

        assertThat(SttModelStore.repertoireDesModeles(context).exists()).isFalse()
        assertThat(magasin.estPresent(modele)).isFalse()
    }

    // ── Fabriques ────────────────────────────────────────────────────────────

    private fun contenuDeTest(octets: Int, graine: Int = 1) =
        ByteArray(octets) { ((it + 1) * 31 * graine % 251).toByte() }

    private fun modelePour(contenu: ByteArray) = SttModel(
        id = "modele-de-test",
        displayName = "Modele de test",
        expectedSha256 = MessageDigest.getInstance("SHA-256").digest(contenu)
            .joinToString("") { "%02x".format(it) },
        sizeBytes = contenu.size.toLong(),
        language = "fr",
        fichierAmont = "modele-de-test.bin",
    )

    private fun fichierSource(nom: String, contenu: ByteArray) = File(bacASable, nom).apply { writeBytes(contenu) }

    /**
     * Un `content://` qui **annonce sa taille**, servi par le `FileProvider` de l'application.
     *
     * ⚠️ Le fichier est écrit dans `cache/exports/`, **seul chemin déclaré** dans `file_paths.xml`.
     * Ce n'est pas un contournement : élargir cette déclaration pour la commodité d'un test
     * ouvrirait en production ce qu'elle restreint exprès — le fichier de test se range donc dans le
     * répertoire prévu, et repart avec lui.
     *
     * ⚠️ Le répertoire est demandé à `NoteExporter`, jamais recopié : même règle que partout ailleurs.
     */
    private fun sourceExposee(nom: String, contenu: ByteArray): Uri {
        val racine = NoteExporter.repertoireDExport(context).apply { mkdirs() }
        val fichier = File(racine, nom).apply { writeBytes(contenu) }
        exposes += fichier
        return FileProvider.getUriForFile(context, "${context.packageName}.exports", fichier)
    }
}
