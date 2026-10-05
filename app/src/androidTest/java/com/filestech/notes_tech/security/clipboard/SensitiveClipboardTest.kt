package com.filestech.notes_tech.security.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.ui.MainActivity
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le presse-papiers sensible.
 *
 * ## ⚠️⚠️ Ces tests supposent que le presse-papiers est LISIBLE
 *
 * Depuis Android 10, seule une application qui a le focus peut lire le presse-papiers. Une suite
 * instrumentée n'a pas de fenêtre au premier plan : `getPrimaryClip` peut rendre `null` quoi qu'on
 * y ait mis. C'est une limite de la plateforme, pas un défaut du code — et un test qui échouerait
 * pour cette raison affirmerait quelque chose de faux sur le service.
 *
 * D'où [assumeTrue] : la vérification est **ignorée**, pas réussie, quand la lecture est refusée.
 * ⚠️ Un test ignoré n'est pas un test vert ; si cette suite est systématiquement ignorée sur un
 * appareil, c'est que la couverture réelle du service est celle du contrôle manuel, et rien d'autre.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SensitiveClipboardTest {

    // ⚠️ Ordre 0 : la règle Hilt doit avoir bâti le composant avant que quoi que ce soit ne
    // lance `MainActivity`, qui est un `@AndroidEntryPoint`.
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    private lateinit var context: Context
    private lateinit var presse: ClipboardManager

    /**
     * ⚠️⚠️ **Sans activité à l'écran, TOUT ce fichier est ignoré en silence.**
     *
     * Mesuré sur un S9 (Android 10) : sans elle, les six tests lèvent l'hypothèse et
     * l'instrumentation rend `OK (6 tests)` — une ligne verte qui ne couvre rien. `am instrument`
     * redémarre le processus, donc lancer l'application à la main juste avant ne suffit pas non
     * plus : il faut une activité **résumée dans ce processus-ci**.
     */
    private var scene: ActivityScenario<MainActivity>? = null

    @Before
    fun preparer() {
        context = ApplicationProvider.getApplicationContext()
        presse = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        hilt.inject()
        scene = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun fermer() {
        scene?.close()
        scene = null
    }

    private fun texteCourant(): String? =
        presse.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    /** Vrai si la plateforme laisse cette suite lire ce qu'elle vient d'écrire. */
    private fun lectureAutorisee(): Boolean {
        presse.setPrimaryClip(ClipData.newPlainText("sonde", "sonde"))
        return texteCourant() == "sonde"
    }

    @Test
    fun copier_depose_le_texte() = runTest {
        assumeTrue("presse-papiers illisible depuis un test", lectureAutorisee())
        val clipboard = SensitiveClipboard(context, backgroundScope)

        clipboard.copier("secret")

        assertThat(texteCourant()).isEqualTo("secret")
    }

    @Test
    fun le_texte_est_efface_a_l_echeance() = runTest {
        assumeTrue("presse-papiers illisible depuis un test", lectureAutorisee())
        val clipboard = SensitiveClipboard(context, backgroundScope)

        clipboard.copier("secret")
        advanceTimeBy(61_000)

        assertThat(texteCourant()).isNotEqualTo("secret")
    }

    /**
     * 🔴 **Le défaut que la génération existe pour empêcher.**
     *
     * L'échéance de la copie A tombe alors que la copie B est en place. Sans compteur commun, A
     * remettait l'état à zéro : la minuterie de B survivait mais ne trouvait plus rien à effacer, et
     * **le contenu de B restait indéfiniment dans le presse-papiers**. Séquence décrite par une
     * relecture externe sur `note_actions.dart`.
     *
     * On vérifie donc les deux moitiés : à l'échéance de A, B est **toujours là** ; à celle de B, il
     * est parti.
     */
    @Test
    fun l_echeance_d_une_copie_perimee_n_efface_ni_ne_desarme_la_suivante() = runTest {
        assumeTrue("presse-papiers illisible depuis un test", lectureAutorisee())
        val clipboard = SensitiveClipboard(context, backgroundScope)

        clipboard.copier("A")
        advanceTimeBy(30_000)
        clipboard.copier("B")

        // L'échéance de A (t=60 s) passe : elle est périmée et ne doit rien toucher.
        advanceTimeBy(31_000)
        assertThat(texteCourant()).isEqualTo("B")

        // Celle de B (t=90 s) doit, elle, aboutir.
        advanceTimeBy(30_000)
        assertThat(texteCourant()).isNotEqualTo("B")
    }

    /** Ce qu'un tiers a copié entre-temps ne nous appartient pas. */
    @Test
    fun un_texte_tiers_n_est_pas_efface() = runTest {
        assumeTrue("presse-papiers illisible depuis un test", lectureAutorisee())
        val clipboard = SensitiveClipboard(context, backgroundScope)

        clipboard.copier("le notre")
        presse.setPrimaryClip(ClipData.newPlainText("autre", "celui d'une autre application"))
        advanceTimeBy(61_000)

        assertThat(texteCourant()).isEqualTo("celui d'une autre application")
    }

    /**
     * 🔴 **Lisible mais pas du texte ≠ illisible.**
     *
     * Une image ou une URI dans le presse-papiers se lit très bien, mais n'a pas de `.text`. Les
     * deux cas rendaient `null` et étaient traités pareil : depuis que la relance est sans borne,
     * celui-ci aurait fait tourner une coroutine indéfiniment en gardant le clair en mémoire, pour
     * un texte qui n'y était déjà plus. Relevé par une relecture externe (GPT-5.2).
     *
     * On vérifie ce qui est observable : le contenu non-textuel n'est **pas** effacé.
     */
    @Test
    fun un_contenu_non_textuel_n_est_pas_efface_et_clot_la_surveillance() = runTest {
        assumeTrue("presse-papiers illisible depuis un test", lectureAutorisee())
        val clipboard = SensitiveClipboard(context, backgroundScope)

        clipboard.copier("secret")
        presse.setPrimaryClip(ClipData.newRawUri("image", "content://exemple/1".toUri()))

        advanceTimeBy(61_000)

        assertThat(presse.primaryClip?.getItemAt(0)?.uri?.toString()).isEqualTo("content://exemple/1")
    }

    @Test
    fun annuler_et_effacer_vide_immediatement() = runTest {
        assumeTrue("presse-papiers illisible depuis un test", lectureAutorisee())
        val clipboard = SensitiveClipboard(context, backgroundScope)

        clipboard.copier("secret")
        clipboard.annulerEtEffacer()

        assertThat(texteCourant()).isNotEqualTo("secret")
    }

    /**
     * La purge invalide aussi la minuterie en cours : après elle, l'échéance de la copie ne doit
     * plus rien effacer — pas même un contenu que l'utilisateur aurait copié depuis.
     */
    @Test
    fun apres_une_purge_l_echeance_de_la_copie_ne_touche_plus_a_rien() = runTest {
        assumeTrue("presse-papiers illisible depuis un test", lectureAutorisee())
        val clipboard = SensitiveClipboard(context, backgroundScope)

        clipboard.copier("secret")
        clipboard.annulerEtEffacer()
        presse.setPrimaryClip(ClipData.newPlainText("apres", "copié après la panique"))

        advanceTimeBy(61_000)

        assertThat(texteCourant()).isEqualTo("copié après la panique")
    }
}
