package com.filestech.notes_tech.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
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
