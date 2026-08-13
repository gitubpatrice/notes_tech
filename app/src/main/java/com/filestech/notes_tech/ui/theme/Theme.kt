package com.filestech.notes_tech.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Teinte de marque Files Tech, reprise de l'icône de la version Flutter (`#262660`).
 *
 * ⚠️ Ne pas s'en servir comme couleur de **texte** posée en dur sur une surface. Sous Material You,
 * `surface` dérive du fond d'écran de l'utilisateur : aucune couleur fixe ne garantit un rapport de
 * contraste sur un fond inconnu. Le premier plan se calcule à partir du fond réel, il ne se choisit
 * pas à l'avance.
 */
private val BrandIndigo = Color(0xFF262660)

private val LightColors = lightColorScheme(primary = BrandIndigo)
private val DarkColors = darkColorScheme(primary = Color(0xFFB9B9F0))

/**
 * Thème de l'application.
 *
 * La couleur dynamique (Material You) est appliquée quand la plateforme la propose, parce qu'elle
 * respecte le choix de l'utilisateur mieux qu'une palette imposée. La palette de marque sert de
 * repli sur les appareils antérieurs à Android 12 — soit tout l'intervalle API 24-30, qui reste
 * dans la cible.
 */
@Composable
fun NotesTechTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
