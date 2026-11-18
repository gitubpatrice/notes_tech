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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.notes_tech.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
 * - The hands: white with a grey outline (Patrice, 2026-11-17) — no longer SMS Tech's single dark tint,
 *   which looked heavy. Same emoji, same frame, same sentence.
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
        // An emoji keeps its colours whatever the text colour. Patrice, 2026-11-17: "fond blanc avec un
        // contour gris" — dark grey, as in SMS Tech, looked heavy. So two layers: its silhouette in grey,
        // drawn around itself, then the emoji brought close to white, its inner lines kept faint.
        // A mid grey in both themes: `outline` is the frame's own light grey here, too pale for a contour,
        // and the secondary text's grey at full strength is the dark grey that looked heavy.
        val contour = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = OPACITE_DU_CONTOUR)
        Text(
            text = MAINS_JOINTES,
            fontSize = 22.sp,
            modifier = Modifier
                .clearAndSetSemantics {}
                .drawWithCache {
                    val epaisseur = EPAISSEUR_DU_CONTOUR.toPx()
                    val zone = Rect(-epaisseur, -epaisseur, size.width + epaisseur, size.height + epaisseur)
                    val pinceauDuContour = Paint().apply { colorFilter = ColorFilter.tint(contour, BlendMode.SrcIn) }
                    val pinceauDuFond = Paint().apply { colorFilter = ColorFilter.colorMatrix(versLeBlanc(PLANCHER)) }
                    val pinceauDuMasque = Paint().apply { blendMode = BlendMode.DstIn }
                    val milieu = size.width / 2
                    val decalages = (0 until DIRECTIONS).map { i ->
                        val angle = 2 * PI * i / DIRECTIONS
                        Offset((cos(angle) * epaisseur).toFloat(), (sin(angle) * epaisseur).toFloat())
                    }
                    onDrawWithContent {
                        drawIntoCanvas { canvas ->
                            decalages.forEach { decalage ->
                                canvas.saveLayer(zone, pinceauDuContour)
                                translate(decalage.x, decalage.y) { this@onDrawWithContent.drawContent() }
                                canvas.restore()
                            }
                            canvas.saveLayer(zone, pinceauDuFond)
                            drawContent()
                            canvas.restore()
                            // The seam between the two hands (Patrice, 2026-11-18), faded to near white with the
                            // other inner lines: a grey line down the middle, kept to the emoji's own shape
                            // (`DstIn`). Every emoji font draws 🙏 symmetric, its hands meeting at the centre.
                            canvas.saveLayer(zone, Paint())
                            drawLine(contour, Offset(milieu, 0f), Offset(milieu, size.height), SEPARATION.toPx())
                            canvas.saveLayer(zone, pinceauDuMasque)
                            drawContent()
                            canvas.restore()
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

/** The grey outline's thickness and strength, and the directions it is drawn in around the emoji. */
private val EPAISSEUR_DU_CONTOUR = 1.5.dp
private const val OPACITE_DU_CONTOUR = 0.6f
private const val DIRECTIONS = 12

/** The width of the line between the two hands, in the outline's grey. */
private val SEPARATION = 1.dp

/** The darkest grey the fill keeps — its inner lines, faint: 0 would keep black, 1 a flat white. */
private const val PLANCHER = 0.72f

/**
 * A matrix that brings an image close to WHITE and keeps its shading faint: each pixel takes its luminance
 * (the weights of `ColorMatrix.setToSaturation(0)`), squeezed into [[plancher], 1] — light parts white,
 * dark lines a light grey. The alpha is kept, so the shape stays the emoji's.
 *
 * ⚠️ The offset column is in 0-255 units, as Android's `ColorMatrix` reads it (Compose hands the values
 * over unchanged).
 */
internal fun versLeBlanc(plancher: Float): ColorMatrix {
    val etendue = 1f - plancher
    val valeurs = FloatArray(COLONNES * LIGNES)
    for (ligne in 0 until LIGNE_ALPHA) {
        valeurs[ligne * COLONNES] = LUM_R * etendue
        valeurs[ligne * COLONNES + 1] = LUM_G * etendue
        valeurs[ligne * COLONNES + 2] = LUM_B * etendue
        valeurs[ligne * COLONNES + COLONNE_DECALAGE] = plancher * MAX_8_BITS
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
private const val COLONNE_DECALAGE = 4
private const val MAX_8_BITS = 255f
