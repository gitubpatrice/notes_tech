package com.filestech.notes_tech.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.filestech.notes_tech.domain.model.NoteColor
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * The notes' colours keep what the card draws on them readable, in both themes (3.1.0).
 *
 * Measured on the app's own schemes, not on constants copied here: a theme that changes its text colour
 * is checked against the palette as it is. The first palette, picked by eye, left the date and the
 * excerpt at 4.1 – 4.4:1 on most light backgrounds — under AA; this is what stops it coming back.
 */
@DisplayName("Couleurs des notes")
class CouleursDeNoteTest {

    @ParameterizedTest
    @EnumSource(NoteColor::class)
    fun `every text and icon a card draws stays readable on the colour, in both themes`(couleur: NoteColor) {
        for ((nom, schema) in listOf("clair" to schemeClair(), "sombre" to schemeSombre())) {
            val fond = CouleursDeNote.teinte(couleur, schema).fond
            fun verifier(quoi: String, premier: Color, seuil: Double) =
                assertWithMessage("$couleur, thème $nom, $quoi").that(contraste(premier, fond)).isAtLeast(seuil)

            verifier("titre (onSurface)", schema.onSurface, AA_TEXTE)
            verifier("date, extrait, étiquettes (onSurfaceVariant)", schema.onSurfaceVariant, AA_TEXTE)
            verifier("cadenas (error)", schema.error, AA_NON_TEXTE)
            verifier("étoile des favoris", SemanticColors.favoriteIconOn(schema), AA_NON_TEXTE)
        }
    }

    @ParameterizedTest
    @EnumSource(NoteColor::class)
    fun `the border marks the colour against the plain card around it`(couleur: NoteColor) {
        for ((nom, schema) in listOf("clair" to schemeClair(), "sombre" to schemeSombre())) {
            val bord = CouleursDeNote.teinte(couleur, schema).bord
            assertWithMessage(
                "$couleur, thème $nom",
            ).that(contraste(bord, schema.surfaceContainerLow)).isAtLeast(BORD_VISIBLE)
        }
    }

    @ParameterizedTest
    @EnumSource(NoteColor::class)
    fun `each theme gets its own pair, never the other theme's`(couleur: NoteColor) {
        val clair = CouleursDeNote.teinte(couleur, schemeClair())
        val sombre = CouleursDeNote.teinte(couleur, schemeSombre())
        assertWithMessage("$couleur").that(clair.fond.luminance()).isGreaterThan(sombre.fond.luminance())
    }

    private fun contraste(a: Color, b: Color): Double {
        val (claire, sombre) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (claire + 0.05) / (sombre + 0.05)
    }

    private companion object {
        const val AA_TEXTE = 4.5
        const val AA_NON_TEXTE = 3.0

        /** A border meant to be seen, not a hairline: 1.5:1 at least against the card around it. */
        const val BORD_VISIBLE = 1.5
    }
}
