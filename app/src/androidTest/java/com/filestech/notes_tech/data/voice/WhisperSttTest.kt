package com.filestech.notes_tech.data.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.domain.voice.SttEngineUnavailableException
import com.filestech.notes_tech.domain.voice.SttModel
import com.filestech.notes_tech.domain.voice.SttModelChecksumMismatchException
import com.filestech.notes_tech.domain.voice.SttModelMissingException
import com.filestech.notes_tech.domain.voice.WavPcm16
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Le moteur de transcription, **sur un vrai appareil**.
 *
 * ## 🔴 Ce que ces tests couvrent, et ce qu'ils ne couvrent PAS
 *
 * Ils ne transcrivent rien. Le faire exigerait le modèle — cinquante mégaoctets que l'utilisateur
 * importe à la main — et un appareil de test qui l'ait déjà. **Cette limite est réelle et doit être
 * dite** : la qualité de la transcription, la détection de langue et le découpage en segments
 * restent vérifiés par l'usage, pas par cette suite.
 *
 * Ce qu'ils couvrent est ce qu'aucun test JVM ne peut atteindre :
 *
 * 1. 🔴 **que `libnotes_stt.so` se charge vraiment**, sur l'architecture de l'appareil. C'est le
 *    seul contrôle qui distingue « le CMake a produit un fichier » de « le fichier est chargeable
 *    ici » — une bibliothèque compilée pour une autre architecture, un `abiFilters` désaccordé du
 *    découpage par ABI, un symbole manquant à l'édition de liens, tout cela produit un binaire que
 *    Gradle accepte et que l'appareil refuse ;
 * 2. que les trois refus du contrat tombent bien dans la hiérarchie scellée, sans toucher au natif.
 *
 * ⚠️ **Un `UnsatisfiedLinkError` est une `Error`, pas une `Exception`** : il traverse un
 * `catch (e: Exception)` sans être vu. Sans le premier test, son symptôme serait l'application
 * entière qui tombe au premier usage de la dictée, en release seulement.
 */
@RunWith(AndroidJUnit4::class)
class WhisperSttTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var magasin: SttModelStore
    private lateinit var moteur: WhisperStt

    @Before
    fun preparer() {
        magasin = SttModelStore(context)
        moteur = WhisperStt(magasin)
        SttModelStore.repertoireDesModeles(context).deleteRecursively()
    }

    /**
     * ⚠️⚠️ **Corps de bloc, pas corps d'expression.** JUnit 4 exige que `@Test` et `@After` rendent
     * `void` ; `fun nettoyer() = runBlocking { … }` rend le type de la dernière expression — ici un
     * `Boolean` — et JUnit **refuse alors la classe ENTIÈRE**, en la comptant pour un seul échec
     * nommé `initializationError`. Les cinq tests n'avaient jamais tourné, et le total n'avait
     * augmenté que de un.
     */
    @After
    fun nettoyer() {
        runBlocking { moteur.dispose() }
        SttModelStore.repertoireDesModeles(context).deleteRecursively()
    }

    /**
     * 🔴 Le test qui justifie toute la vendorisation : la bibliothèque existe et se charge.
     *
     * Il échouerait si `abiFilters` cessait de couvrir l'architecture de l'appareil, si le nom de la
     * bibliothèque changeait dans le CMake sans changer dans `WhisperNatif`, ou si l'édition de
     * liens laissait un symbole non résolu — trois pannes qu'aucune compilation ne signale.
     */
    @Test
    fun laBibliothequeNativeSeCharge() {
        assertThat(WhisperNatif.disponible).isTrue()
    }

    @Test
    fun transcrireAvantInitialiserLeveDansLaHierarchie() {
        val audio = File(context.cacheDir, "vide.wav").apply { writeBytes(WavPcm16.entete(0)) }
        try {
            val echec = runBlocking {
                runCatching { moteur.transcribeFile(audio.absolutePath) }.exceptionOrNull()
            }

            // ⚠️ Le point : une `IllegalStateException` serait passée à travers un `when` exhaustif
            // chez l'appelant, pour l'échec le plus banal de tous — le moteur pas encore chargé.
            assertThat(echec).isInstanceOf(SttEngineUnavailableException::class.java)
            assertThat(moteur.isInitialized).isFalse()
        } finally {
            audio.delete()
        }
    }

    @Test
    fun initialiserSansModeleInstalleLeSignale() = runBlocking {
        val echec = runCatching { moteur.initialize(modeleFictif()) }.exceptionOrNull()

        // L'interface doit conduire vers l'import, jamais proposer de réessayer : rien ne changerait.
        assertThat(echec).isInstanceOf(SttModelMissingException::class.java)
        assertThat(moteur.isInitialized).isFalse()
    }

    /**
     * 🔴 La revérification de l'empreinte **à chaque chargement**, pas seulement à l'import.
     *
     * Le fichier est en place, au bon nom, à la bonne taille — mais son contenu n'est pas celui
     * qu'on attend. C'est le scénario d'un remplacement ou d'une corruption après un import réussi,
     * et le seul contrôle qui l'attrape est celui-ci.
     */
    @Test
    fun unModeleAlterePeuAvantLeChargementEstRefuseETSupprime() = runBlocking {
        val modele = modeleFictif()
        val fichier = magasin.fichierDuModele(modele).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(modele.sizeBytes.toInt()) { 0x7F })
        }

        val echec = runCatching { moteur.initialize(modele) }.exceptionOrNull()

        assertThat(echec).isInstanceOf(SttModelChecksumMismatchException::class.java)
        // ⚠️ Supprimé, comme à l'import : le garder inviterait à réessayer avec les mêmes octets.
        assertThat(fichier.exists()).isFalse()
        assertThat(moteur.isInitialized).isFalse()
    }

    @Test
    fun libererDeuxFoisNeCasseRien() = runBlocking {
        moteur.dispose()
        moteur.dispose()

        assertThat(moteur.isInitialized).isFalse()
        assertThat(moteur.loadedModel).isNull()
    }

    /**
     * Un modèle qui n'existera jamais : sa taille est plausible, son empreinte ne correspond à rien.
     *
     * ⚠️ Volontairement petit. Fabriquer un fichier de cinquante mégaoctets à chaque test userait
     * l'appareil pour ne rien vérifier de plus : les contrôles portent sur la taille et l'empreinte,
     * pas sur le volume.
     */
    private fun modeleFictif() = SttModel(
        id = "modele-absent",
        displayName = "Modele absent",
        expectedSha256 = "0".repeat(64),
        sizeBytes = 8_192,
        language = "fr",
        fichierAmont = "modele-absent.bin",
    )
}
