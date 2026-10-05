package com.filestech.notes_tech.ui.home

import android.content.Context
import android.content.res.Resources
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.notes_tech.R
import com.filestech.notes_tech.ui.folders.FolderEvent
import com.filestech.notes_tech.ui.folders.SortDesNotes
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * **Ce que dit la suppression d'un dossier — et le fait que les deux gestes ne disent pas la même
 * chose.**
 *
 * L'application publiée se taisait sur les deux, et le portage la suivait : `folders_drawer.dart`
 * n'affiche aucun message après un renommage ni après une suppression. C'était noté comme un **écart
 * assumé**, au motif que « le tiroir se met à jour sous les yeux de l'utilisateur ».
 *
 * 🔴🔴 **L'argument vaut pour le renommage, pas pour la suppression.** Un renommage montre le nouveau
 * nom ; une création montre la nouvelle ligne. Une suppression ne montre que la **disparition du
 * dossier**, et tait le sort de ses notes — déplacées vers la boîte de réception, ou détruites — qui
 * n'étaient pas à l'écran. Cf. `04-PIEGES.md` §97.
 *
 * ## Ce que ces cas cherchent à faire échouer
 *
 * 1. **Le jumeau** : les deux gestes partageaient un seul `movedNotes: Int`, et un `0` était ambigu.
 *    Le cas [les_deux_gestes_ne_disent_PAS_la_meme_chose] échouerait sur toute implémentation qui
 *    déduirait le message du seul compteur.
 * 2. **Le pluriel** : mesuré sur les vraies ressources, pas sur un identifiant.
 * 3. **La catégorie `many`** : réclamée par `lintDebug` sur les pluriels français, elle ne vaut
 *    qu'aux multiples exacts d'un million. La mesurer a appris qu'elle **ne sort pas sur le S9** —
 *    l'ICU d'Android 10 ne connaît pas encore cette catégorie et retombe sur `other`. Le cas
 *    [le_pluriel_francais_reste_grammatical_a_un_million] accepte donc les deux formes et refuse
 *    tout le reste : exiger l'une ou l'autre reviendrait à mesurer la version d'ICU de la machine
 *    de test.
 */
@RunWith(AndroidJUnit4::class)
class MessageDeSuppressionTest {

    private val contexte: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * ⚠️ **Des ressources FRANÇAISES explicites**, et non celles de l'appareil : le S9 peut être en
     * anglais, et les cas de pluriel ci-dessous ne prouveraient alors rien du français — la seule
     * langue où la catégorie `many` existe.
     */
    private val fr: Resources = contexte
        .createConfigurationContext(
            android.content.res.Configuration(contexte.resources.configuration).apply {
                setLocale(Locale.FRENCH)
            },
        )
        .resources

    private fun message(sort: SortDesNotes, notes: Int): String =
        messageDeSuppression(fr, FolderEvent.Deleted(sort, notes))

    /**
     * 🔴 **Un dossier vide n'a pas de sort de notes à annoncer.**
     *
     * `getQuantityString` avec `0` rendrait « Dossier supprimé, 0 note déplacée » — la catégorie
     * `one` du français couvre zéro. Une phrase courte et vraie vaut mieux qu'un compte à zéro.
     */
    @Test
    fun un_dossier_vide_ne_parle_pas_de_ses_notes() {
        val attendu = fr.getString(R.string.folder_deleted)

        assertThat(message(SortDesNotes.DEPLACEES, 0)).isEqualTo(attendu)
        assertThat(message(SortDesNotes.SUPPRIMEES, 0)).isEqualTo(attendu)
        assertThat(attendu).doesNotContain("0")
    }

    /**
     * 🔴🔴 **Le cas du jumeau supprimé.** Déplacer vers la boîte de réception et détruire sont deux
     * gestes ; à nombre égal, ils doivent produire deux phrases. Toute implémentation qui déduirait
     * le message du seul compteur échouerait ici.
     */
    @Test
    fun les_deux_gestes_ne_disent_PAS_la_meme_chose() {
        val deplacees = message(SortDesNotes.DEPLACEES, 3)
        val supprimees = message(SortDesNotes.SUPPRIMEES, 3)

        assertThat(deplacees).isNotEqualTo(supprimees)
        // ⚠️ Et chacune nomme bien son sort : deux phrases différentes pourraient l'être par accident.
        assertThat(deplacees).contains("déplacées")
        assertThat(supprimees).doesNotContain("déplacées")
    }

    /** ⚠️ Singulier et pluriel, sur les vraies ressources — un identifiant ne prouverait rien. */
    @Test
    fun le_singulier_et_le_pluriel_sont_distincts() {
        for (sort in SortDesNotes.entries) {
            assertThat(message(sort, 1)).isNotEqualTo(message(sort, 4))
        }
        assertThat(message(SortDesNotes.DEPLACEES, 1)).contains("1 note déplacée")
        assertThat(message(SortDesNotes.DEPLACEES, 4)).contains("4 notes déplacées")
    }

    /**
     * ⚠️⚠️ **La catégorie `many` du français est DÉCLARÉE, et sa sélection dépend de l'appareil.**
     *
     * `lintDebug` la réclamait sur les quatre pluriels français (`MissingQuantity`) : la ressource
     * était incomplète pour la locale. Elle est désormais écrite, et l'avertissement a disparu.
     *
     * 🔴 **Mais elle ne sort pas partout, et c'est mesuré, pas supposé.** La première version de ce
     * cas affirmait « 1 000 000 de notes » et a **échoué sur le S9** : Android 10 rend
     * « 1000000 notes ». La catégorie `many` du français est entrée dans CLDR 38 (2020) et l'ICU
     * embarquée par cette version du système ne la connaît pas — elle retombe donc sur `other`. Sur
     * un système plus récent, dont l'ICU est mise à jour par les modules, elle est sélectionnée.
     *
     * ⚠️ **Ce test accepte donc les deux formes**, et refuse tout le reste. Exiger `many` le ferait
     * échouer sur les vieux appareils, exiger `other` le ferait échouer sur les neufs : dans les deux
     * cas il mesurerait la version d'ICU de la machine de test et rien d'autre. Ce qui **doit** tenir
     * partout, c'est que la phrase reste grammaticale et porte le nombre.
     *
     * ⚠️ Le témoin est le cas à un million **moins un** : lui n'a droit qu'à `other`, sur toute
     * version. Sans lui, une ressource dont les deux formes seraient identiques passerait.
     */
    @Test
    fun le_pluriel_francais_reste_grammatical_a_un_million() {
        val many = fr.getQuantityString(R.plurals.trash_emptied, UN_MILLION, UN_MILLION)
        val other = fr.getQuantityString(R.plurals.trash_emptied, UN_MILLION - 1, UN_MILLION - 1)

        // Les deux formes admissibles, et rien d'autre : « de notes » (CLDR ≥ 38) ou « notes ».
        assertThat(many).matches("$UN_MILLION (de )?notes supprimées définitivement")
        assertThat(other).isEqualTo("${UN_MILLION - 1} notes supprimées définitivement")
    }

    private companion object {
        const val UN_MILLION = 1_000_000
    }
}
