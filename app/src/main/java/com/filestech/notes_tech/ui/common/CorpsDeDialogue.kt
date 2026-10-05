package com.filestech.notes_tech.ui.common

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Le corps d'un dialogue, **défilant**, pour que ses actions ne soient jamais poussées hors écran.
 *
 * ## 🔴 Ce que ça répare, vu sur le S9 le 2026-08-15
 *
 * « Supprimer le dossier ? » porte un avertissement de six lignes et **trois** actions. Sur un écran
 * de cette taille, le texte prenait toute la hauteur disponible et la dernière action était
 * **écrasée à 72 px au lieu de 144**, son libellé tronqué en hauteur : « Supprimer définitivement »
 * n'était plus lisible, et le bas du dialogue était coupé.
 *
 * ⚠️⚠️ **Le défaut le plus grave n'est pas l'esthétique.** L'action rendue illisible était
 * l'irréversible. Un dialogue de confirmation qui montre mal celle des trois options qui détruit
 * tout est pire que pas de dialogue : il fait croire qu'on a choisi en connaissance de cause.
 *
 * ## Pourquoi le corps et pas les boutons
 *
 * `AlertDialog` sert d'abord le texte, qui réclame toute sa hauteur, puis donne le reste aux
 * actions. Rendre le texte défilant renverse la priorité : il se contente de ce qui reste, et les
 * actions gardent leur taille. C'est aussi ce que fait Material quand le contenu déborde.
 *
 * ⚠️ Un `verticalScroll` sous une contrainte de hauteur **non bornée** plante. Ici la contrainte
 * vient d'`AlertDialog`, qui la borne — même règle que le panneau de liens de l'éditeur et que les
 * feuilles de coffre.
 */
@Composable
fun CorpsDeDialogue(texte: String, modifier: Modifier = Modifier) {
    Text(text = texte, modifier = modifier.verticalScroll(rememberScrollState()))
}
