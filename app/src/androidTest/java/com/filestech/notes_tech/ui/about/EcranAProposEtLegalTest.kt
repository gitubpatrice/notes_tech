package com.filestech.notes_tech.ui.about

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.R
import com.filestech.notes_tech.ui.actionnablesSansNom
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * **« À propos » et les mentions légales : la version affichée, et le vrai texte juridique.**
 *
 * Lignes `about_screen.dart` et `mentions_legales_screen.dart` de `docs/05-PARITE.md`. 707 lignes de
 * code de production à elles deux, et **aucun test**.
 *
 * ## 🔴 Ce que ces écrans risquent, et que rien ne voyait
 *
 * Une ressource `raw` absente ne lève pas : Android **retombe en silence** sur la langue par défaut.
 * Un utilisateur anglais lirait la politique de confidentialité en français sans que rien ne le
 * signale — sur les deux seuls écrans de l'application qui engagent juridiquement.
 *
 * Et une version affichée fausse n'est pas visible non plus : elle a l'air d'une version.
 *
 * ## ⚠️ La ligne de parité se trompait sur le publié
 *
 * Elle dit *« version lue dynamiquement via `PackageInfo` »*. C'est faux : l'application publiée
 * affiche `AppConstants.appVersion`, une **constante statique** (`constants.dart:8`) qu'il faut
 * bumper en même temps que `pubspec.yaml` — le piège de release déjà consigné pour le portefeuille.
 * Le portage lit `BuildConfig.VERSION_NAME`, qui **dérive** du `versionName` Gradle et ne peut donc
 * pas dériver de lui. Cf. `04-PIEGES.md` §116.
 */
@RunWith(AndroidJUnit4::class)
class EcranAProposEtLegalTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val contexte: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun texte(id: Int) = regle.activity.getString(id)

    private fun ressources(langue: Locale): Resources = contexte
        .createConfigurationContext(
            Configuration(contexte.resources.configuration).apply { setLocale(langue) },
        )
        .resources

    // ── « À propos » ─────────────────────────────────────────────────────────────────────────────

    /**
     * 🔴 **La version affichée est celle que le paquet installé déclare.**
     *
     * Le contrôle ne compare pas à `BuildConfig` — ce serait circulaire, puisque c'est la source de
     * l'affichage. Il compare à ce que le **gestionnaire de paquets** rend, c'est-à-dire au
     * `versionName` du manifeste de l'APK réellement posée sur l'appareil. Une constante écrite à la
     * main s'en écarterait dès le premier bump oublié.
     */
    @Test
    fun la_version_affichee_est_celle_du_paquet_installe() {
        val duPaquet = contexte.packageManager.getPackageInfo(contexte.packageName, 0).versionName
        assertThat(duPaquet).isNotEmpty()

        regle.setContent { NotesTechTheme { AboutRoute(onBack = {}, onOpenLegal = {}) } }
        regle.waitForIdle()

        regle.onNodeWithText("v$duPaquet").assertIsDisplayed()
    }

    /** ⚠️ Le balayage maison sur un écran plein de liens sortants. */
    @Test
    fun aucun_element_actionnable_n_est_sans_nom_sur_a_propos() {
        regle.setContent { NotesTechTheme { AboutRoute(onBack = {}, onOpenLegal = {}) } }
        regle.waitForIdle()

        assertThat(regle.actionnablesSansNom()).isEmpty()
    }

    // ── Les mentions légales ─────────────────────────────────────────────────────────────────────

    /**
     * 🔴🔴 **Les quatre textes existent, sont substantiels, et DIFFÈRENT d'une langue à l'autre.**
     *
     * C'est le contrôle qui compte, et il ne passe pas par l'écran : une ressource `raw-fr`
     * manquante ferait retomber Android sur `raw` **sans un mot**, et l'écran afficherait un texte
     * parfaitement lisible — dans la mauvaise langue.
     *
     * ⚠️ Exiger la **différence** est ce qui distingue « les deux langues sont là » de « la
     * résolution est retombée sur le défaut ». Une simple vérification de non-vacuité passerait dans
     * les deux cas.
     */
    @Test
    fun les_QUATRE_textes_legaux_existent_et_different_par_langue() {
        for (fichier in listOf(R.raw.privacy, R.raw.terms)) {
            val fr = lire(ressources(Locale.FRENCH), fichier)
            val en = lire(ressources(Locale.ENGLISH), fichier)

            // Un texte juridique de moins de mille caractères serait un fichier tronqué.
            assertThat(fr.length).isGreaterThan(1_000)
            assertThat(en.length).isGreaterThan(1_000)
            assertThat(fr).isNotEqualTo(en)
        }
    }

    /**
     * 🔴 Les deux onglets rendent **du texte**, et pas le même.
     *
     * ⚠️ Le titre de niveau 1 de chaque fichier sert d'ancre, **sans son croisillon** : il vérifie
     * du même coup que le rendu Markdown minimal fait son travail. Un fichier affiché brut
     * montrerait « # Politique de confidentialité ».
     */
    @Test
    fun les_deux_onglets_rendent_leur_texte_sans_le_croisillon() {
        val titrePrivacy = premierTitre(R.raw.privacy)
        val titreTerms = premierTitre(R.raw.terms)
        assertThat(titrePrivacy).isNotEqualTo(titreTerms)

        regle.setContent { NotesTechTheme { LegalRoute(onBack = {}) } }
        regle.waitForIdle()

        regle.onNodeWithText(titrePrivacy).assertIsDisplayed()

        regle.onNodeWithText(texte(R.string.legal_tab_terms)).performClick()
        regle.waitForIdle()

        regle.onNodeWithText(titreTerms).assertIsDisplayed()
    }

    /**
     * 🔴 **La licence MIT s'affichait avec ses chevrons.**
     *
     * `terms.md` reproduit la licence MIT de `whisper.cpp` — elle l'exige — sous forme de citation
     * Markdown : **20 lignes commençant par `>`**, dans chacune des deux langues. Le rendu ne
     * connaissait pas les citations, elles tombaient dans la branche par défaut, et l'utilisateur
     * lisait `> MIT License`, `> Copyright (c) 2023-2024 The ggml authors`.
     *
     * ⚠⚠ Le KDoc du rendu affirmait pourtant qu'aucune citation n'existait dans ces fichiers, et
     * précisait « vérifié en les lisant, pas supposé ». *Une affirmation de vérification se vérifie
     * comme les autres.*
     *
     * Le cas est écrit **à partir du fichier**, pas d'une constante : il relit la première ligne
     * citée et exige qu'elle soit à l'écran **sans** son chevron. Si quelqu'un remplace la licence
     * embarquée, le cas suit sans qu'on y touche.
     */
    @Test
    fun les_citations_de_la_licence_s_affichent_sans_leur_chevron() {
        val brut = lire(regle.activity.resources, R.raw.terms)
        val citees = brut.lines().map { it.trim() }.filter { it.startsWith(">") }
        // Contrôle de l'instrument : sans citation dans le fichier, ce cas ne prouverait rien.
        assertThat(citees).isNotEmpty()
        val premiere = citees.first { it.removePrefix(">").trim().isNotEmpty() }
        val attendu = premiere.removePrefix(">").trim()

        regle.setContent { NotesTechTheme { LegalRoute(onBack = {}) } }
        regle.waitForIdle()
        regle.onNodeWithText(texte(R.string.legal_tab_terms)).performClick()
        regle.waitForIdle()

        regle.onNodeWithText(attendu).performScrollTo().assertIsDisplayed()
        // ⚠️ Et la forme brute ne doit PAS être à l'écran — sinon le cas passerait aussi bien sur le
        // rendu fautif, qui affichait les deux fois la même ligne, chevron en plus.
        regle.onAllNodesWithText(premiere).assertCountEquals(0)
    }

    /** ⚠️ Le balayage, onglets compris. */
    @Test
    fun aucun_element_actionnable_n_est_sans_nom_sur_les_mentions() {
        regle.setContent { NotesTechTheme { LegalRoute(onBack = {}) } }
        regle.waitForIdle()

        assertThat(regle.actionnablesSansNom()).isEmpty()
    }

    private fun lire(ressources: Resources, fichier: Int): String =
        ressources.openRawResource(fichier).bufferedReader().use { it.readText() }

    /** Le premier titre `# …` du fichier, tel que l'écran doit le rendre : sans le croisillon. */
    private fun premierTitre(fichier: Int): String = lire(regle.activity.resources, fichier)
        .lines()
        .first { it.trim().startsWith("# ") }
        .trim()
        .removePrefix("# ")
}
