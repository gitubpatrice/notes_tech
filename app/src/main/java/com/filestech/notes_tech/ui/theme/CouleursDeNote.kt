package com.filestech.notes_tech.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.filestech.notes_tech.domain.model.NoteColor

/** A note's colour as drawn on a card: a pastel background and a stronger border of the same hue. */
data class TeinteDeNote(val fond: Color, val bord: Color)

/**
 * The notes' colours (3.1.0), one pair per theme.
 *
 * ## ⚠️ Two palettes, chosen on the REAL surface, like [SemanticColors]
 *
 * A light pastel under the dark theme's light text is unreadable, and a dark tint under the light
 * theme's dark text too. So a colour is a stored id ([NoteColor]) and the pair is chosen here, on the
 * luminance of the surface actually drawn — the threshold of [SemanticColors.favoriteIconOn], so that a
 * card and the star drawn on it never disagree on what is dark.
 *
 * ## The contrasts, measured — not eyeballed
 *
 * Every background was computed against the app's own text colours (WCAG 2.1, relative luminance):
 *
 * | | light theme | dark theme |
 * |---|---|---|
 * | body text (`onSurface`) | 13.5 – 14.7:1 | 11.9 – 12.7:1 |
 * | date, excerpt, tags (`onSurfaceVariant`, small text, AA = 4.5) | 4.56 – 4.87:1 | 4.57 – 4.89:1 |
 * | links of the reading card (`primary`) | 4.51 – 4.82:1 | 5.56 – 5.96:1 |
 * | lock icon (`error`, AA non-text = 3) | 4.8 – 5.2:1 | 4.2 – 4.5:1 |
 * | favourite star | 4.8 – 5.2:1 | 10.9 – 11.6:1 |
 *
 * ⚠️ The light backgrounds are paler than a "pastel" picked by eye on purpose: the first, more
 * saturated set left the secondary text at 4.1 – 4.4:1, under AA; and the pink was paled once more when
 * the reading card put the links on it (4.47:1, caught by `CouleursDeNoteTest`). The hue is carried by the border,
 * which is what tells the notes apart at a glance — and what Patrice asked for ("une bordure un peu
 * plus prononcée"). Retouching a value means measuring again: the contrast is the constraint.
 *
 * No red: red is the border of a locked note.
 */
object CouleursDeNote {

    fun teinte(couleur: NoteColor, scheme: ColorScheme): TeinteDeNote =
        if (scheme.surface.luminance() < SemanticColors.DARK_SURFACE_THRESHOLD) {
            SOMBRES.getValue(couleur)
        } else {
            CLAIRES.getValue(couleur)
        }

    private val CLAIRES = mapOf(
        NoteColor.YELLOW to TeinteDeNote(Color(0xFFFFF7D1), Color(0xFFC99A00)),
        NoteColor.ORANGE to TeinteDeNote(Color(0xFFFFEDDE), Color(0xFFD9762B)),
        NoteColor.GREEN to TeinteDeNote(Color(0xFFE6F6E1), Color(0xFF3E9A45)),
        NoteColor.TEAL to TeinteDeNote(Color(0xFFDFF4F1), Color(0xFF1F8A7E)),
        NoteColor.BLUE to TeinteDeNote(Color(0xFFE6F0FD), Color(0xFF3D7BD0)),
        NoteColor.PURPLE to TeinteDeNote(Color(0xFFF4EEFB), Color(0xFF8459C4)),
        NoteColor.PINK to TeinteDeNote(Color(0xFFFDEDF4), Color(0xFFC8528A)),
        NoteColor.GRAY to TeinteDeNote(Color(0xFFEEF0F3), Color(0xFF7D8691)),
    )

    private val SOMBRES = mapOf(
        NoteColor.YELLOW to TeinteDeNote(Color(0xFF2D2A17), Color(0xFFC9A227)),
        NoteColor.ORANGE to TeinteDeNote(Color(0xFF382819), Color(0xFFD9823F)),
        NoteColor.GREEN to TeinteDeNote(Color(0xFF1C3021), Color(0xFF4FA357)),
        NoteColor.TEAL to TeinteDeNote(Color(0xFF16302D), Color(0xFF36A296)),
        NoteColor.BLUE to TeinteDeNote(Color(0xFF192A42), Color(0xFF4D8DD9)),
        NoteColor.PURPLE to TeinteDeNote(Color(0xFF29223F), Color(0xFF9675D6)),
        NoteColor.PINK to TeinteDeNote(Color(0xFF36202D), Color(0xFFCF6A9C)),
        NoteColor.GRAY to TeinteDeNote(Color(0xFF24292F), Color(0xFF8B949E)),
    )
}
