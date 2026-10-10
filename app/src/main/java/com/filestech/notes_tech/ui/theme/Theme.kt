package com.filestech.notes_tech.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * La palette de l'application publiée, à l'octet près.
 *
 * Portage de `lib/core/theme.dart`, classe `AppColors`. Ce sont des teintes inspirées de GitHub, et
 * elles sont **communes aux applications Files Tech** : ce n'est pas un réglage esthétique, c'est
 * l'identité visuelle du portefeuille.
 *
 * ⚠️ Ne rien y ajouter sans que la version Flutter l'ait. Une teinte de plus ici est une divergence
 * de plus à réconcilier à la bascule.
 */
private object AppColors {
    // Sombre — palette GitHub
    val DarkBg = Color(0xFF0D1117)
    val DarkSurface = Color(0xFF161B22)
    val DarkSurface2 = Color(0xFF21262D)
    val DarkBorder = Color(0xFF30363D)
    val DarkTextPrimary = Color(0xFFE6EDF3)
    val DarkTextSecondary = Color(0xFF8B949E)
    val DarkBlue = Color(0xFF58A6FF)
    val DarkBlueContainer = Color(0xFF1F6FEB)
    val DarkRed = Color(0xFFF85149)

    /**
     * Le rouge du logo Files Tech.
     *
     * ⚠️ **Réservé au thème CLAIR.** Sur `DarkSurface` il tombe à **3,08:1**, sous le seuil AA de
     * 4,5:1 pour du texte. Les deux rouges sont exactement complémentaires — mesures reprises de la
     * fiche de contraste du portefeuille :
     *
     * | Rouge | sur fond clair | sur `#161B22` |
     * |---|---|---|
     * | `DarkRed` `#F85149` | 3,35:1 ❌ | 5,16:1 ✅ |
     * | `BrandRed` `#C62828` | 5,62:1 ✅ | 3,08:1 ❌ |
     *
     * Aucun des deux ne convient partout ; c'est pourquoi `error` diffère selon le thème alors que
     * `theme.dart` posait le même dans les deux.
     */
    val BrandRed = Color(0xFFC62828)

    // Clair
    val LightBg = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFF6F8FA)
    val LightSurface2 = Color(0xFFEAEEF2)
    val LightBorder = Color(0xFFD0D7DE)
    val LightTextPrimary = Color(0xFF1F2328)
    val LightTextSecondary = Color(0xFF656D76)
    val LightBlue = Color(0xFF0969DA)
}

/**
 * ## 🔴 Les jetons dérivés sont déclarés À LA MAIN, et c'est une leçon de la version publiée
 *
 * Material 3 sait calculer `errorContainer`, `primaryContainer` et leurs premiers plans à partir
 * d'une couleur de base. Sur une palette qui n'est pas issue d'un générateur Material, le résultat
 * est imprévisible — la 2.0.3 y a gagné un `errorContainer` dérivé du rouge sombre dont le contraste
 * tombait sous le seuil en thème clair. Le commentaire de `theme.dart` le raconte (v1.1.4), et les
 * valeurs ci-dessous sont les siennes, vérifiées à la main à 4,5:1 au moins.
 */
internal fun schemeSombre(): ColorScheme = ColorScheme(
    primary = AppColors.DarkBlue,
    onPrimary = Color.White,
    primaryContainer = AppColors.DarkBlueContainer,
    onPrimaryContainer = Color.White,
    inversePrimary = AppColors.LightBlue,
    secondary = AppColors.DarkBlue,
    onSecondary = Color.White,
    secondaryContainer = AppColors.DarkBlueContainer,
    onSecondaryContainer = Color.White,
    tertiary = AppColors.DarkBlue,
    onTertiary = Color.White,
    tertiaryContainer = AppColors.DarkBlueContainer,
    onTertiaryContainer = Color.White,
    background = AppColors.DarkBg,
    onBackground = AppColors.DarkTextPrimary,
    surface = AppColors.DarkSurface,
    onSurface = AppColors.DarkTextPrimary,
    surfaceVariant = AppColors.DarkSurface2,
    onSurfaceVariant = AppColors.DarkTextSecondary,
    surfaceTint = AppColors.DarkBlue,
    inverseSurface = AppColors.LightSurface,
    inverseOnSurface = AppColors.LightTextPrimary,
    error = AppColors.DarkRed,
    onError = Color.White,
    errorContainer = Color(0xFF8B1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = AppColors.DarkBorder,
    outlineVariant = AppColors.DarkBorder,
    scrim = Color.Black,
    surfaceBright = AppColors.DarkSurface2,
    surfaceDim = AppColors.DarkBg,
    surfaceContainer = AppColors.DarkSurface,
    surfaceContainerHigh = AppColors.DarkSurface2,
    surfaceContainerHighest = AppColors.DarkSurface2,
    surfaceContainerLow = AppColors.DarkSurface,
    surfaceContainerLowest = AppColors.DarkBg,
)

internal fun schemeClair(): ColorScheme = ColorScheme(
    primary = AppColors.LightBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = AppColors.LightTextPrimary,
    inversePrimary = AppColors.DarkBlue,
    secondary = AppColors.LightBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E4FF),
    onSecondaryContainer = AppColors.LightTextPrimary,
    tertiary = AppColors.LightBlue,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3E4FF),
    onTertiaryContainer = AppColors.LightTextPrimary,
    background = AppColors.LightBg,
    onBackground = AppColors.LightTextPrimary,
    surface = AppColors.LightSurface,
    onSurface = AppColors.LightTextPrimary,
    surfaceVariant = AppColors.LightSurface2,
    onSurfaceVariant = AppColors.LightTextSecondary,
    surfaceTint = AppColors.LightBlue,
    inverseSurface = AppColors.DarkSurface,
    inverseOnSurface = AppColors.DarkTextPrimary,
    // 🔴 **Écart assumé avec `theme.dart`, sur demande de Patrice (2026-08-15).** La version Flutter
    // pose `error: AppColors.darkRed` sans condition, donc le même `#F85149` dans les deux thèmes —
    // et ce rouge-là ne tient que **3,35:1** sur un fond clair. Le rouge du logo le remplace ici, où
    // il monte à 5,62:1. En thème sombre c'est l'inverse, d'où les deux valeurs : cf. [AppColors.BrandRed].
    error = AppColors.BrandRed,
    onError = Color.White,
    errorContainer = Color(0xFFFDECEC),
    onErrorContainer = Color(0xFF410002),
    outline = AppColors.LightBorder,
    outlineVariant = AppColors.LightBorder,
    scrim = Color.Black,
    surfaceBright = AppColors.LightBg,
    surfaceDim = AppColors.LightSurface2,
    surfaceContainer = AppColors.LightSurface,
    surfaceContainerHigh = AppColors.LightSurface2,
    surfaceContainerHighest = AppColors.LightSurface2,
    surfaceContainerLow = AppColors.LightSurface,
    surfaceContainerLowest = AppColors.LightBg,
)

/**
 * Les tailles de texte de `theme.dart`, reprises telles quelles.
 *
 * La version Flutter fixe six styles et laisse les autres au défaut. Le portage fait pareil : les
 * styles non listés gardent ceux de Material 3, comme côté Flutter ils gardent ceux du thème de base.
 *
 * ⚠️ **Les couleurs ne sont PAS posées ici**, contrairement à `theme.dart` qui les met dans le
 * `TextTheme`. En Compose, la couleur vient de la surface sur laquelle le texte est posé
 * (`contentColorFor`), et la figer dans la typographie casserait tout composant qui s'affiche sur
 * une surface inversée — un `Snackbar`, un conteneur d'erreur. Les mêmes couleurs sortent du
 * `ColorScheme` ci-dessus, par le chemin que Compose prévoit.
 */
private val TypographieFilesTech = Typography().let { defaut ->
    defaut.copy(
        bodyLarge = defaut.bodyLarge.copy(fontSize = 15.sp),
        bodyMedium = defaut.bodyMedium.copy(fontSize = 14.sp),
        bodySmall = defaut.bodySmall.copy(fontSize = 12.sp),
        titleLarge = defaut.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = defaut.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = defaut.labelMedium.copy(fontSize = 13.sp),
    )
}

/**
 * Thème de l'application.
 *
 * ## 🔴 Pas de couleur dynamique — décision revue le 2026-08-15
 *
 * Une première version appliquait Material You quand la plateforme le proposait, au motif que le
 * choix de l'utilisateur prime sur une palette imposée. L'argument se tient dans l'absolu ; il ne
 * tient pas **ici**, et Patrice a tranché : le portage doit ressembler à l'application publiée.
 *
 * Material You dérive `surface`, `primary` et tout le reste du fond d'écran de l'appareil. Deux
 * conséquences, toutes deux rédhibitoires pour une bascule :
 *
 * - **Notes Tech ne ressemblait plus à Notes Tech**, ni aux huit autres applications du
 *   portefeuille, et l'écart changeait d'un téléphone à l'autre.
 * - **Aucune comparaison de parité n'était possible** : deux captures du même écran, l'une Flutter
 *   l'autre Kotlin, ne pouvaient pas être rapprochées puisque la seconde dépendait du fond d'écran.
 *
 * Le portage reprend donc la palette de `theme.dart`, dans les deux thèmes, sans dépendance à
 * l'appareil.
 *
 * ⚠️ Ce que cela **ne** retire pas : le suivi du thème clair/sombre du système, qui reste. Ce qui
 * disparaît est la teinte tirée du fond d'écran, pas le respect du mode.
 */
@Composable
fun NotesTechTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) schemeSombre() else schemeClair(),
        typography = TypographieFilesTech,
        content = content,
    )
}
