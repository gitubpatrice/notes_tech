package com.filestech.notes_tech.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.ui.theme.Formes

/**
 * Une action proposée par un dialogue ou une feuille : **son contour a la couleur de son texte**.
 *
 * ## Pourquoi un contour
 *
 * Les actions d'un `AlertDialog` étaient des `TextButton` nus — du texte, sans limite visible. Rien
 * n'indiquait où commence et où finit la zone touchable, et deux actions voisines se lisaient comme
 * une phrase plutôt que comme deux boutons. Le contour donne à chacune une frontière, sans rien
 * changer à ce qu'elle dit.
 *
 * ## ⚠️ Le contour prend la couleur du texte, pas une couleur à lui
 *
 * C'est ce qui préserve la hiérarchie déjà en place : une action destructrice est écrite en rouge,
 * son contour l'est aussi, et elle reste plus alarmante que sa voisine sans qu'on ait eu à décider
 * d'une seconde échelle de couleurs. Un contour neutre autour d'un libellé rouge aurait au contraire
 * **atténué** l'avertissement.
 *
 * ## ⚠️⚠️ Une bordure, et RIEN d'autre en largeur
 *
 * 12 dp à l'horizontale, comme un `TextButton` — et non les 24 dp par défaut d'un `OutlinedButton`,
 * qui feraient de chaque action un pavé et déborderaient dès qu'une rangée en porte deux ou trois.
 *
 * À la verticale, 4 dp au lieu de 8 : la hauteur est la ressource rare d'un dialogue, et
 * « Supprimer le dossier ? » en porte **trois** sous un avertissement de six lignes. Ce qui est
 * repris ici va aux actions.
 *
 * ⚠️ Ce n'est pas un réglage d'esthétique isolé. Une action qui grossit, c'est un libellé qui se
 * coupe — mesuré le 2026-08-15 sur la barre de l'éditeur, où une action de plus avait écrasé le
 * titre **à zéro pixel**. Un changement d'apparence ne doit pas être un changement de mise en page,
 * et celui-ci se vérifie à l'écran, pas dans le code.
 *
 * @param couleur celle du texte **et** du contour. Par défaut l'accent ; passer
 *   `MaterialTheme.colorScheme.error` pour une action destructrice.
 */
@Composable
fun ActionDeDialogue(
    texte: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    couleur: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
) {
    // ⚠️ Le contour suit l'état : une action refusée dont le contour resterait franc se lirait comme
    // disponible. C'est le même défaut que l'icône qui ne suivait pas l'état d'épinglage — ce que le
    // code fait, et ce que l'utilisateur voit.
    val teinte = if (enabled) couleur else couleur.copy(alpha = ALPHA_DESACTIVE)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = Formes.bouton,
        border = BorderStroke(EPAISSEUR_DU_CONTOUR, teinte),
        contentPadding = REMPLISSAGE,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = couleur,
            disabledContentColor = teinte,
        ),
    ) {
        Text(texte)
    }
}

/** Celle de Material 3 pour un contenu désactivé. */
private const val ALPHA_DESACTIVE = 0.38f

private val EPAISSEUR_DU_CONTOUR = 1.dp

/** Voir la note de fonction : la largeur d'un `TextButton`, la hauteur resserrée. */
private val REMPLISSAGE = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
