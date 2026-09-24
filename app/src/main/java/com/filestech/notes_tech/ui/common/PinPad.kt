package com.filestech.notes_tech.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.filestech.notes_tech.R
import com.filestech.notes_tech.ui.theme.Formes

// The PIN pad and its dots, shared by the PIN vault sheets and the app lock (D-023).
//
// Moved out of `ui/vault/VaultSheets.kt` on 2026-09-24 WITHOUT any change of behaviour — the only
// addition is `longueurMax`, which the vault passes as `VaultParams.PIN_MAX_LENGTH`. Two copies of a
// keypad would be the twin that gets fixed on one side only, the defect this repository keeps
// meeting (04-PIEGES.md). The French comments inside are the original ones.

@Composable
internal fun PointsDeSaisie(saisi: String, longueurMax: Int, visible: Boolean, onBasculer: () -> Unit) {
    val couleurs = MaterialTheme.colorScheme
    val annonce = stringResource(R.string.vault_pin_digits_announce, saisi.length, longueurMax)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.semantics { contentDescription = annonce },
        ) {
            repeat(longueurMax) { index ->
                val chiffre = saisi.getOrNull(index)
                if (visible && chiffre != null) {
                    // ⚠️ Même largeur qu'une pastille : sans cela, la rangée change de longueur au
                    // basculement et le pavé numérique sautille sous les doigts.
                    Text(
                        text = chiffre.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        color = couleurs.primary,
                        modifier = Modifier.width(14.dp).clearAndSetSemantics { },
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(14.dp).clip(CircleShape).clearAndSetSemantics { },
                        shape = CircleShape,
                        color = if (index < saisi.length) couleurs.primary else couleurs.surfaceContainerHighest,
                    ) {}
                }
            }
        }
        IconButton(onClick = onBasculer) {
            Icon(
                imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = stringResource(
                    if (visible) R.string.pin_hide_tooltip else R.string.pin_show_tooltip,
                ),
            )
        }
    }
}

/**
 * @param tailleDeTouche the vault sheets keep the original 72 dp. The app lock passes less: its screen
 *   carries a header and two more buttons, and at 72 dp it did not fit a Galaxy S9 (360 x 740 dp)
 *   without scrolling — its "forgot your PIN?" was below the fold (measured on 2026-09-24).
 */
@Composable
internal fun ClavierNumerique(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    tailleDeTouche: Dp = TAILLE_TOUCHE,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (ligne in TOUCHES) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (touche in ligne) {
                    when (touche) {
                        ' ' -> Surface(modifier = Modifier.size(tailleDeTouche), color = Color.Transparent) {}
                        '\b' -> TextButton(
                            onClick = onDelete,
                            enabled = enabled,
                            shape = Formes.bouton,
                            modifier = Modifier.size(tailleDeTouche),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = stringResource(R.string.vault_pin_key_delete),
                            )
                        }

                        else -> {
                            // ⚠️ `vault_pin_key_label` (« Touche %1$s ») existait et n'était jamais
                            // utilisée : le lecteur d'écran annonçait « 7 » tout court, indiscernable
                            // d'un texte affiché. Relevé par l'audit i18n du 2026-08-14.
                            val etiquette = stringResource(R.string.vault_pin_key_label, "$touche")
                            TextButton(
                                onClick = { onDigit(touche) },
                                enabled = enabled,
                                shape = Formes.bouton,
                                modifier = Modifier
                                    .size(tailleDeTouche)
                                    .semantics { contentDescription = etiquette },
                            ) {
                                Text(text = "$touche", style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val TOUCHES = listOf(
    charArrayOf('1', '2', '3'),
    charArrayOf('4', '5', '6'),
    charArrayOf('7', '8', '9'),
    charArrayOf(' ', '0', '\b'),
)

private val TAILLE_TOUCHE = 72.dp
