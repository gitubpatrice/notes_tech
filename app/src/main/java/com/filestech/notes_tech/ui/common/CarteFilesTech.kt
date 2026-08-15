package com.filestech.notes_tech.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Une carte du portefeuille : fond `surface`, **bordure**, rayon 12, aucune ombre.
 *
 * Portage du `cardTheme` de `theme.dart:114-122`, qui s'applique à **toutes** les cartes de
 * l'application publiée. C'est pourquoi ce composable est ici et non recopié écran par écran : deux
 * cartes qui divergent d'un rayon ou d'une bordure se voient tout de suite quand on fait défiler.
 *
 * ## ⚠️ La bordure n'est pas décorative
 *
 * `surface` et le fond de page sont **proches** dans cette palette — `#161B22` contre `#0D1117` en
 * thème sombre, `#F6F8FA` contre blanc en clair. Sans bordure, une carte ne se distingue pas de la
 * page : le regroupement visuel que la carte est censée produire disparaît, et l'écran redevient
 * une liste à plat.
 *
 * @param bordure permet à un appelant de teinter le contour — la zone destructrice des réglages le
 *   fait en `error`, comme l'application publiée (`settings_screen.dart:130-140`).
 */
@Composable
fun CarteFilesTech(
    modifier: Modifier = Modifier,
    bordure: Color = MaterialTheme.colorScheme.outline,
    contenu: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, bordure),
        modifier = modifier.fillMaxWidth(),
        content = contenu,
    )
}

/**
 * Un titre de section : `primary`, gras, 15 sp.
 *
 * Repris de `_SectionTitle` (`about_screen.dart:357-382`, `settings_screen.dart:349-374`), qui est
 * **le même widget dans les deux écrans** côté Flutter. Le portage en avait deux versions
 * différentes — `titleSmall` dans les réglages, rien du tout dans « à propos ».
 *
 * L'espacement fait partie du style : 24 au-dessus, 8 en dessous. C'est lui qui donne son rythme à
 * une page de sections.
 */
@Composable
fun TitreDeSection(titre: String, modifier: Modifier = Modifier) {
    Text(
        text = titre,
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 24.dp, bottom = 8.dp, start = 2.dp).semantics { heading() },
    )
}
