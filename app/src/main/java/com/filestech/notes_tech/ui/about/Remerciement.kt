package com.filestech.notes_tech.ui.about

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.notes_tech.R

/**
 * D-050 (3.5.0, Patrice, 2026-11-17): the thanks at the bottom of "About", framed in light grey and
 * opened by folded hands — taken from SMS Tech (`c7d6d36a`, `AboutScreen.kt` there), so that the two apps
 * say it the same way.
 *
 * - No contributor's name: nobody agreed to appear in the app, and a list of names would never be up to
 *   date (the same choice as SMS Tech, written next to `about_thanks_body`).
 * - The folded hands are the emoji U+1F64F (Unicode 6.0, on every Android version the app runs on): the
 *   Material icons have none. Decorative — a screen reader reads the text only.
 * - The frame follows the theme (`outlineVariant`: light grey in light, discreet in dark), like the
 *   dividers of this screen.
 *
 * ⚠️ `teinteMonochrome` is the same code as SMS Tech's, on purpose: the apps share no library.
 */
@Composable
internal fun Remerciement(modifier: Modifier = Modifier) {
    val forme = RoundedCornerShape(16.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = forme)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // An emoji keeps its colours whatever the text colour: it is drawn through a layer that brings it
        // to ONE tint (Patrice), its shading kept — see [teinteMonochrome].
        val teinte = MaterialTheme.colorScheme.onSurfaceVariant
        Text(
            text = MAINS_JOINTES,
            fontSize = 20.sp,
            modifier = Modifier
                .clearAndSetSemantics {}
                .drawWithCache {
                    val calque = Paint().apply { colorFilter = ColorFilter.colorMatrix(teinteMonochrome(teinte)) }
                    onDrawWithContent {
                        drawIntoCanvas { canvas ->
                            canvas.saveLayer(Rect(Offset.Zero, size), calque)
                            drawContent()
                            canvas.restore()
                        }
                    }
                },
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = stringResource(R.string.about_thanks_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Folded hands, U+1F64F (source in UTF-8). */
private const val MAINS_JOINTES = "🙏"

/**
 * A matrix that brings an image to ONE tint and keeps its shading: each pixel takes its luminance (the
 * weights of `ColorMatrix.setToSaturation(0)`), times the [teinte]; the alpha is kept. Light becomes the
 * tint, dark tends to black.
 */
internal fun teinteMonochrome(teinte: Color): ColorMatrix {
    val valeurs = FloatArray(COLONNES * LIGNES)
    // Rows R, G, B: the input's luminance, times the tint's component.
    for ((ligne, composante) in listOf(teinte.red, teinte.green, teinte.blue).withIndex()) {
        valeurs[ligne * COLONNES] = LUM_R * composante
        valeurs[ligne * COLONNES + 1] = LUM_G * composante
        valeurs[ligne * COLONNES + 2] = LUM_B * composante
    }
    // Row A: the input's alpha, unchanged.
    valeurs[LIGNE_ALPHA * COLONNES + LIGNE_ALPHA] = 1f
    return ColorMatrix(valeurs)
}

// Android's luminance weights (`ColorMatrix.setSaturation(0)`), and the shape of a 4 × 5 colour matrix.
private const val LUM_R = 0.213f
private const val LUM_G = 0.715f
private const val LUM_B = 0.072f
private const val LIGNES = 4
private const val COLONNES = 5
private const val LIGNE_ALPHA = 3
