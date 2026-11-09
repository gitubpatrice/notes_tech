package com.filestech.notes_tech.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.filestech.notes_tech.domain.model.Note
import com.filestech.notes_tech.domain.model.NoteSortMode
import com.filestech.notes_tech.ui.common.ConfigurationCompat
import com.filestech.notes_tech.ui.common.MOTIFS_DE_DATE
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The archive's [notes] with a heading before each month (Patrice, 2026-11-09): by the date the sort
 * [tri] orders them by — created for the "created" orders, last modified for the "modified" ones (which
 * an archiving is: archiving writes the date, as pinning always has) —, in [zone]. A sort by title has
 * no dates to group by: no heading.
 *
 * Pure, and tested so (`LignesDeLArchiveTest`).
 */
internal fun lignesDeLArchive(notes: List<Note>, tri: NoteSortMode, zone: ZoneId): List<LigneDeLArchive> {
    val date: ((Note) -> Instant)? = when (tri) {
        NoteSortMode.UPDATED_DESC, NoteSortMode.UPDATED_ASC -> Note::updatedAt
        NoteSortMode.CREATED_DESC, NoteSortMode.CREATED_ASC -> Note::createdAt
        NoteSortMode.TITLE_ASC, NoteSortMode.TITLE_DESC -> null
    }
    if (date == null) return notes.map { LigneDeLArchive.Carte(it) }
    val lignes = ArrayList<LigneDeLArchive>(notes.size + notes.size / 2)
    var precedent: YearMonth? = null
    for (note in notes) {
        val mois = YearMonth.from(date(note).atZone(zone))
        // Compared with the line before, not looked up in a set: the order is by date, so a month seen
        // again after another would be a new heading — which the order rules out, and which is better
        // shown than hidden if a clock ever wrote a date out of order.
        if (mois != precedent) {
            lignes += LigneDeLArchive.Mois(mois)
            precedent = mois
        }
        lignes += LigneDeLArchive.Carte(note)
    }
    return lignes
}

/**
 * A line of the archive's list (3.3.0): a month's heading, or a note.
 *
 * [cle] is the line's key in the `LazyColumn`: a note's is its id, as in every list; a month's is the
 * month itself — each month comes once, the archive's order being by date alone (see
 * [NoteSortMode.orderByIgnoringPin]).
 */
internal sealed interface LigneDeLArchive {
    val cle: String

    data class Mois(val mois: YearMonth) : LigneDeLArchive {
        override val cle: String get() = "mois-$mois"
    }

    data class Carte(val note: Note) : LigneDeLArchive {
        override val cle: String get() = note.id
    }
}

/**
 * The heading's text — "November 2026", "Novembre 2026", "November 2026", "Novembre 2026",
 * "Noviembre de 2026" — in the app's language, or English for one it does not speak, as the cards'
 * dates do ([MOTIFS_DE_DATE]). Capitalised: a heading, while French, Italian and Spanish write months in
 * lower case inside a sentence.
 */
internal fun formateurDeMois(locale: Locale): (YearMonth) -> String {
    val parlee = if (locale.language in MOTIFS_DE_MOIS) locale else Locale.ENGLISH
    val formateur = DateTimeFormatter.ofPattern(MOTIFS_DE_MOIS.getValue(parlee.language), parlee)
    return { mois ->
        formateur.format(mois).replaceFirstChar { if (it.isLowerCase()) it.titlecase(parlee) else it.toString() }
    }
}

/** One pattern per language of the app — the same five as [MOTIFS_DE_DATE], checked by a test. */
internal val MOTIFS_DE_MOIS = mapOf(
    "fr" to "MMMM yyyy",
    "en" to "MMMM yyyy",
    "de" to "MMMM yyyy",
    "es" to "MMMM 'de' yyyy",
    "it" to "MMMM yyyy",
)

@Composable
internal fun rememberFormateurDeMois(): (YearMonth) -> String {
    // The same language as the cards' dates (`rememberNoteDateFormatter`): read from the configuration,
    // so a change of language in the settings reaches the headings too.
    val locale = ConfigurationCompat.firstLocale(LocalConfiguration.current)
    return remember(locale) { formateurDeMois(locale) }
}
