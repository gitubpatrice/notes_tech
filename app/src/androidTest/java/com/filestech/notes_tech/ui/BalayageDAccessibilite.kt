package com.filestech.notes_tech.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * **Le balayage « actionnable sans nom », à recopier écran par écran.**
 *
 * Extrait de `AccueilTest` le 2026-08-17, à la deuxième ligne de parité : ce filtre a trouvé le
 * bouton flottant muet de l'accueil (`04-PIEGES.md` §71), le même défaut existe dans **SMS Tech
 * publiée**, et il reste huit écrans à passer. Le recopier huit fois serait le laisser diverger.
 *
 * La question posée est : *quels nœuds **actionnables** de l'arbre **fusionné** n'ont ni description
 * ni texte ?* — c'est-à-dire ce qu'un lecteur d'écran annoncerait « bouton », sans dire lequel.
 *
 * ⚠️ **L'arbre fusionné, jamais l'autre.** `useUnmergedTree = true` fait apparaître les libellés que
 * `clearAndSetSemantics` retire, donc exactement l'arbre où ces défauts sont invisibles. La
 * tentation est réelle : c'est l'arbre où un test écrit sur `onNodeWithText` « trouve enfin ».
 *
 * ⚠️⚠️ **L'appui long compte autant que le clic.** Un nœud n'exposant qu'`OnLongClick` est
 * actionnable pour l'utilisateur et invisible à `hasClickAction()`. Le dépôt en a un précédent :
 * `05-PARITE.md` note qu'un appui long sur la boîte de réception **manquait entièrement** au
 * portage, et qu'aucune chaîne orpheline ne le signalait.
 *
 * Le témoin de ce filtre vit dans [BalayageDAccessibiliteTest] — sans lui, un filtre qui lit la
 * mauvaise propriété rendrait une liste vide et **tout écran passerait pour sain**.
 */
internal fun ComposeTestRule.actionnablesSansNom(): List<Rect> =
    onAllNodes(hasClickAction() or APPUI_LONG).fetchSemanticsNodes()
        .filter { noeud ->
            val description = noeud.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            val texte = noeud.config.getOrNull(SemanticsProperties.Text).orEmpty()
            // ⚠️ Les champs de saisie sont exclus : leur nom vient de leur `EditableText`, et un
            // champ vide n'est pas un défaut d'étiquetage.
            val saisie = noeud.config.getOrNull(SemanticsProperties.EditableText) != null
            description.all { it.isBlank() } && texte.all { it.text.isBlank() } && !saisie
        }
        .map { it.boundsInRoot }

/**
 * ⚠️ `hasLongClickAction()` n'existe pas dans l'API de test : le pendant de `hasClickAction()` se
 * construit à la main sur la clé de l'action.
 */
internal val APPUI_LONG = SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)

/**
 * **Le balayage du motif INVERSE : une action perdue à la fusion.**
 *
 * [actionnablesSansNom] cherche une action **sans nom**. Le défaut §74 était l'inverse — un **nom sans
 * action** — et ce filtre-là ne pouvait pas le voir : `NoteCard` portait sa `contentDescription` sur son
 * `Surface` et son `clickable` sur la `Column` fille, si bien que le nœud **fusionné** portait le nom et
 * **aucune** action `OnClick`. La carte s'annonçait comme du **texte**, sans dire qu'on peut l'ouvrir.
 *
 * ⚠️⚠️ **Les actions d'un descendant ne remontent PAS au nœud fusionné**, contrairement au texte et aux
 * descriptions. C'est la mesure du 2026-08-17, et c'est ce qui rend le défaut invisible : le geste
 * fonctionne quand même, parce qu'un double-appui de lecteur d'écran envoie un toucher au **centre du
 * nœud focalisé**, qui atteint la fille cliquable. Et `performClick()` ne le voit pas non plus — il
 * injecte un toucher aux coordonnées et n'exige aucune action de sémantique.
 *
 * ## Ce que ce filtre mesure, exactement
 *
 * Pour chaque nœud **actionnable** de l'arbre non fusionné, on remonte à ses **ancêtres** — en
 * s'excluant soi-même — jusqu'au premier qui **fusionne** ses descendants. Si cet ancêtre porte un
 * **nom** et **aucune action**, alors c'est lui que le lecteur d'écran focalise et annonce : nommé, et
 * inerte. L'action existe un cran plus bas, invisible à l'annonce.
 *
 * ## 🔴🔴 Il a fallu TROIS versions de ce filtre, et le témoin a arrêté les deux premières
 *
 * 1. « remonter au premier ancêtre fusionnant, **soi-même inclus** » — **0 sur tout**, y compris sur la
 *    faute, parce que **`Modifier.clickable` fusionne lui-même ses descendants** : le premier nœud
 *    fusionnant était donc toujours le nœud cliquable, qui porte l'action par construction.
 * 2. « un nœud actionnable de l'arbre non fusionné **absent** de l'arbre fusionné » — **0 sur tout**
 *    aussi : le nœud cliquable **existe** dans les deux arbres. Le défaut §74 n'est pas une absorption,
 *    c'est **deux nœuds distincts**, l'un qui nomme et l'autre qui agit.
 * 3. celle-ci.
 *
 * ⚠️⚠️ **Les deux premières auraient fait passer les neuf écrans pour sains**, et seul le témoin l'a
 * dit — troisième fois de la journée qu'un témoin positif rattrape un instrument muet, après le `grep`
 * ancré par `$` et l'assertion négative sur la carte de corbeille. *Un filtre qui ne signale rien est
 * indiscernable d'un code sans défaut.*
 *
 * Son témoin est dans [BalayageDAccessibiliteTest].
 */
internal fun ComposeTestRule.actionsPerduesALaFusion(): List<Rect> =
    onAllNodes(hasClickAction() or APPUI_LONG, useUnmergedTree = true).fetchSemanticsNodes()
        .filter { noeud -> annoncePerdue(noeud) }
        .map { it.boundsInRoot }

/**
 * `true` si l'ancêtre fusionnant de [noeud] porte un nom **sans** porter d'action.
 *
 * ⚠️ La remontée **exclut** le nœud de départ : `clickable` fusionne, donc s'inclure ferait toujours
 * répondre « l'action est là », ce qui était la faute de la première version.
 */
private fun annoncePerdue(noeud: SemanticsNode): Boolean {
    var courant = noeud.parent
    while (courant != null) {
        val config = courant.config
        if (config.isMergingSemanticsOfDescendants) {
            val nomme = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                .any { it.isNotBlank() } ||
                config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.isNotBlank() }
            val agit = config.getOrNull(SemanticsActions.OnClick) != null ||
                config.getOrNull(SemanticsActions.OnLongClick) != null
            return nomme && !agit
        }
        courant = courant.parent
    }
    // Aucun ancêtre ne fusionne : le nœud est annoncé tel quel, avec son action. Rien à reprocher.
    return false
}
