package com.filestech.notes_tech.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Le format de date des listes de notes.
 *
 * Portage de `ui/widgets/note_card.dart:38-47` : `dd MMM yyyy · HH:mm` en français,
 * `MMM d, yyyy · HH:mm` en anglais. Les deux motifs sont **relevés**, pas choisis — un format
 * différent serait un écart visible sur chaque ligne de chaque liste.
 *
 * ⚠️ **`HH` et jamais `hh`.** `hh` est l'heure sur douze sans indicateur : 21 h s'y affiche « 09:00 »
 * et devient indiscernable de 9 h du matin. Les deux lettres se ressemblent, le défaut ne se voit
 * pas avant 13 h, et un test écrit le matin passe.
 *
 * ⚠️ Le formateur est **mémoïsé sur la locale**. En construire un par ligne de liste alloue un objet
 * lourd à chaque recomposition, sur le chemin le plus chaud de l'application.
 */
@Composable
fun rememberNoteDateFormatter(): (Instant) -> String {
    val configuration = LocalConfiguration.current
    val locale = ConfigurationCompat.firstLocale(configuration)
    return remember(locale) {
        // `format(Instant)` exige un fuseau : sans `withZone`, la mise en forme lève au premier
        // appel parce qu'un instant ne porte aucune notion de date locale.
        val formateur = formateurDeDate(locale).withZone(ZoneId.systemDefault())
        ({ instant: Instant -> formateur.format(instant) })
    }
}

/**
 * The formatter for [locale]: its language's pattern, or English for a language the app does not
 * speak — as the screens fall back to `values/`, and as notes_tech 2.0.9 does.
 *
 * ⚠️ It used to choose "English, or else French": a German screen got French-shaped dates, and a
 * Portuguese phone — English screens, the app speaking no Portuguese — French-shaped dates with
 * Portuguese months (found while adding German, Spanish and Italian, 2026-09-25).
 */
internal fun formateurDeDate(locale: Locale): DateTimeFormatter {
    val parlee = if (locale.language in MOTIFS_DE_DATE) locale else Locale.ENGLISH
    return DateTimeFormatter.ofPattern(MOTIFS_DE_DATE.getValue(parlee.language), parlee)
}

/**
 * One pattern per language of the app (`LocalePreference.LANGUES`, checked by a test). French and
 * English are 2.0.9's; German, Spanish and Italian keep its order — day, month, year, 24-hour time.
 */
internal val MOTIFS_DE_DATE = mapOf(
    "fr" to "dd MMM yyyy · HH:mm",
    "en" to "MMM d, yyyy · HH:mm",
    "de" to "d. MMM yyyy · HH:mm",
    "es" to "d MMM yyyy · HH:mm",
    "it" to "d MMM yyyy · HH:mm",
)

/**
 * `Configuration.locales` n'existe qu'à partir de l'API 24 — le plancher exact du projet — mais
 * `getLocales()` y renvoie une liste possiblement vide sur certains appareils. Le repli sur
 * [Locale.getDefault] évite un `IndexOutOfBounds` sur un chemin d'affichage.
 */
private object ConfigurationCompat {
    fun firstLocale(configuration: android.content.res.Configuration): Locale =
        configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
}
