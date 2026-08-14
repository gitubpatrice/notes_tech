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
        val motif = if (locale.language == "en") EN_PATTERN else FR_PATTERN
        val formateur = DateTimeFormatter.ofPattern(motif, locale).withZone(ZoneId.systemDefault())
        // `format(Instant)` exige un fuseau : sans `withZone`, la mise en forme lève au premier
        // appel parce qu'un instant ne porte aucune notion de date locale.
        ({ instant: Instant -> formateur.format(instant) })
    }
}

private const val FR_PATTERN = "dd MMM yyyy · HH:mm"
private const val EN_PATTERN = "MMM d, yyyy · HH:mm"

/**
 * `Configuration.locales` n'existe qu'à partir de l'API 24 — le plancher exact du projet — mais
 * `getLocales()` y renvoie une liste possiblement vide sur certains appareils. Le repli sur
 * [Locale.getDefault] évite un `IndexOutOfBounds` sur un chemin d'affichage.
 */
private object ConfigurationCompat {
    fun firstLocale(configuration: android.content.res.Configuration): Locale =
        configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
}
