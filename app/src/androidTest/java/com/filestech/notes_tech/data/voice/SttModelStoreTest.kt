package com.filestech.notes_tech.data.voice

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 */
@RunWith(AndroidJUnit4::class)
class SttModelStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var magasin: SttModelStore
    private lateinit var bacASable: File

    @Before
    fun preparer() {
        magasin = SttModelStore(context)
        bacASable = File(context.cacheDir, "import-test").apply { mkdirs() }
        SttModelStore.repertoireDesModeles(context).deleteRecursively()
    }

    @After
    fun nettoyer() {
        bacASable.deleteRecursively()
        SttModelStore.repertoireDesModeles(context).deleteRecursively()
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

    @Test
    fun unFichierDeTailleSansRapportEstRefuseSansLecture() = runBlocking {
        val contenu = contenuDeTest(4_096)
        val modele = modelePour(contenu)
        val source = fichierSource("photo.jpg", contenuDeTest(64))

        val echec = runCatching { magasin.importer(source.toUri(), modele) }.exceptionOrNull()

        assertThat(echec).isInstanceOf(SttModelSourceInvalidException::class.java)
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
        notes = "fabrique pour le test",
        fichierAmont = "modele-de-test.bin",
    )

    private fun fichierSource(nom: String, contenu: ByteArray) = File(bacASable, nom).apply { writeBytes(contenu) }
}
