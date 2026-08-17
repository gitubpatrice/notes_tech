package com.filestech.notes_tech.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
}
