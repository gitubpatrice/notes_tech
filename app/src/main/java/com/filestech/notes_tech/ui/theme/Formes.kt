package com.filestech.notes_tech.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Les formes que `MaterialTheme.shapes` ne permet pas de fixer.
 *
 * ## Pourquoi ce fichier existe plutôt qu'un réglage de thème
 *
 * Material 3 dessine ses boutons en **pilule** : un rayon volontairement énorme, que la
 * bibliothèque nomme `CornerFull`. Ce rayon-là n'est **pas** exposé par `Shapes`, qui ne porte que
 * `extraSmall` → `extraExtraLarge` (vérifié dans l'artefact `material3-android:1.4.0`). Autrement
 * dit, on ne peut pas le changer une fois pour toutes depuis le thème : il faut le passer à chaque
 * bouton.
 *
 * ⚠️ **C'est exactement la situation qui fabrique des divergences.** Dix-huit sites d'appel qui
 * portent chacun leur `RoundedCornerShape(10.dp)`, c'est dix-huit endroits où la valeur peut
 * dériver, et un dix-neuvième bouton ajouté plus tard qui restera en pilule sans que rien ne le
 * signale. La constante vit donc ici, et les sites d'appel la **nomment** au lieu de la recopier.
 *
 * 🔧 Contrôle si un bouton paraît encore arrondi : `grep -rn "Button(" app/src/main` puis vérifier
 * que chaque site porte `shape = Formes.bouton`. Un site sans `shape` retombe sur la pilule.
 */
object Formes {

    /**
     * Les coins des boutons — **10 dp, choisis par Patrice le 2026-08-16** : « j'aime pas l'effet
     * arrondi ».
     *
     * C'est une décision d'apparence, pas une contrainte technique, et elle s'applique à toute la
     * famille des boutons pour que l'application garde **une** silhouette. Un bouton resté en
     * pilule au milieu des autres se remarque davantage que si tous l'étaient.
     */
    val bouton = RoundedCornerShape(10.dp)
}
