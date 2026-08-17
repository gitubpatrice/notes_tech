package com.filestech.notes_tech.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.notes_tech.ui.theme.NotesTechTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 🔴🔴 **Le témoin de [actionnablesSansNom], et il n'est pas décoratif.**
 *
 * Un détecteur qui ne détecte rien fait passer chaque écran pour sain. Celui-ci pose donc **trois**
 * cibles volontairement asymétriques — un clic muet, un clic **nommé**, un **appui long** muet — et
 * exige exactement **deux** signalements.
 *
 * Sans cette mesure, tous les balayages d'écran resteraient verts même si le filtre lisait la
 * mauvaise propriété de sémantique. C'est précisément l'erreur commise le 2026-08-17 sur un `grep`
 * ancré par `$` : l'instrument rendait « aucun » et c'est son **témoin positif** qui l'a dit.
 *
 * ⚠️ Le troisième cas est ce qui prouve que l'extension à l'appui long **fonctionne** : sans lui,
 * l'avoir ajoutée au filtre serait une intention, pas une mesure.
 *
 * ⚠️ Vit dans sa propre classe depuis l'extraction du 2026-08-17 : il valide l'outil, pas un écran.
 * Le laisser dans `AccueilTest` aurait fait croire qu'il ne couvre que l'accueil.
 */
@RunWith(AndroidJUnit4::class)
class BalayageDAccessibiliteTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun le_detecteur_signale_un_clic_muet_et_un_appui_long_muet_mais_pas_un_bouton_nomme() {
        regle.setContent {
            NotesTechTheme {
                Column {
                    Box(Modifier.size(48.dp).clickable {})
                    Box(Modifier.size(48.dp).semantics { contentDescription = "nomme" }.clickable {})
                    // ⚠️ `onLongClick` posé SEUL, à la main : `combinedClickable` expose aussi
                    // `OnClick`, donc il serait déjà pris par le premier filtre et ne prouverait rien.
                    Box(Modifier.size(48.dp).semantics { onLongClick { true } })
                }
            }
        }
        regle.waitForIdle()

        assertThat(regle.actionnablesSansNom()).hasSize(2)
    }

    /**
     * 🔴🔴 **Le témoin du motif INVERSE — une action perdue à la fusion.**
     *
     * Trois cibles, dont **une seule** est fautive, et les trois formes existent réellement dans le
     * dépôt :
     *
     * 1. **la faute de §74** : la sémantique nommante sur le parent, le `clickable` sur l'enfant. Le
     *    nœud fusionné porte le nom et **aucune** action — la carte s'annonce comme du texte ;
     * 2. **le correctif de §74** : les deux sur le **même** nœud. Correct, ne doit pas être signalé ;
     * 3. **un bouton ordinaire** dont le `Text` est un descendant sans action. Correct aussi, et c'est
     *    le cas qui compte le plus : si le filtre le signalait, il crierait sur tous les boutons de
     *    l'application et deviendrait illisible.
     *
     * ⚠️ Sans ce témoin, le filtre pourrait rendre une liste vide sur tous les écrans — et un
     * détecteur qui ne détecte rien fait passer chaque écran pour sain. C'est la leçon du `grep`
     * ancré par `$` du 2026-08-17, dont seul le **témoin positif** a révélé la faute.
     */
    @Test
    fun le_detecteur_signale_une_action_perdue_a_la_fusion_mais_pas_les_formes_correctes() {
        regle.setContent {
            NotesTechTheme {
                Column {
                    // 1. La faute : nom sur le parent fusionnant, action sur l'enfant.
                    Box(
                        Modifier.semantics(mergeDescendants = true) { contentDescription = "la faute" },
                    ) {
                        Box(Modifier.size(48.dp).clickable {})
                    }
                    // 2. Le correctif : les deux sur le même nœud.
                    Box(
                        Modifier
                            .size(48.dp)
                            .clickable {}
                            .semantics(mergeDescendants = true) { contentDescription = "correct" },
                    )
                    // 3. Un bouton ordinaire : son libellé est un descendant sans action.
                    Button(onClick = {}) { Text("Un bouton") }
                }
            }
        }
        regle.waitForIdle()

        assertThat(regle.actionsPerduesALaFusion()).hasSize(1)
    }

    /**
     * 🔴🔴 **Le témoin du TROISIÈME motif — une zone de saisie sans nom accessible.**
     *
     * Trois champs, dont **un seul** est fautif, et les trois formes existaient dans le dépôt :
     *
     * 1. **la faute** : un `placeholder` seul, sur un champ qui porte du texte. Le placeholder a
     *    disparu de l'écran **et** de l'arbre ; le champ n'a plus aucun nom ;
     * 2. **le correctif** : un `label`, qui flotte au-dessus du champ rempli et **reste** annoncé ;
     * 3. 🔴 **le champ VIDE avec son seul placeholder** — et c'est le cas qui compte le plus. Il n'est
     *    **pas** fautif : le placeholder est là et nomme le champ. C'est précisément l'état sous lequel
     *    un éditeur se relit, et c'est lui qui a caché le défaut de `NoteEditorScreen` pendant cinq
     *    écrans de parité. Un filtre qui le signalerait crierait sur tout formulaire vierge de
     *    l'application ; un filtre qui ne signale que lui n'aurait rien mesuré.
     *
     * ⚠️ Le premier cas est **la seule** raison pour laquelle ce filtre existe : `actionnablesSansNom`
     * exclut les nœuds portant un `EditableText`, donc ni lui ni `actionsPerduesALaFusion` — qui ne
     * regarde que les actionnables — ne pouvaient voir ce motif.
     *
     * ## ⚠️⚠️ L'assertion porte sur **lequel** est signalé, pas sur combien
     *
     * `hasSize(1)` seul serait un témoin à moitié vacant : le jour où Material3 change et où le filtre
     * se met à signaler le champ **vide** au lieu du champ fautif, il resterait vert — un signalement,
     * mais le mauvais. La comparaison se fait donc sur les **coordonnées** du champ fautif, relevées
     * sur son étiquette de test.
     *
     * ⚠️ `testTag` ne nomme **pas** un nœud pour un lecteur d'écran : il ne pose ni `ContentDescription`
     * ni `Text`, donc il ne peut pas faire disparaître le défaut qu'on mesure. Relevé par une relecture
     * externe (GPT-5.2, 2026-08-17).
     */
    @Test
    fun le_detecteur_signale_un_champ_rempli_sans_libelle_mais_ni_le_champ_libelle_ni_le_champ_vide() {
        regle.setContent {
            NotesTechTheme {
                Column {
                    // 1. La faute : placeholder seul, et du contenu qui l'a fait disparaître.
                    TextField(
                        value = "du texte",
                        onValueChange = {},
                        placeholder = { Text("indice") },
                        modifier = Modifier.testTag(CHAMP_FAUTIF),
                    )
                    // 2. Le correctif : un libellé, qui survit au remplissage.
                    TextField(value = "du texte", onValueChange = {}, label = { Text("libelle") })
                    // 3. Le champ vide : son placeholder le nomme. Rien à signaler.
                    TextField(value = "", onValueChange = {}, placeholder = { Text("indice vide") })
                }
            }
        }
        regle.waitForIdle()

        val fautif = regle.onNodeWithTag(CHAMP_FAUTIF).fetchSemanticsNode().boundsInRoot

        assertThat(regle.champsDeSaisieSansNom()).containsExactly(fautif)
    }

    private companion object {
        const val CHAMP_FAUTIF = "champ-sans-libelle"
    }
}
