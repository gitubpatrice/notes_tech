/// Thème GitHub sombre / clair, cohérent avec la suite Files Tech.
library;

import 'package:flutter/material.dart';

class AppColors {
  AppColors._();

  // Sombre — palette GitHub
  static const Color darkBg = Color(0xFF0D1117);
  static const Color darkSurface = Color(0xFF161B22);
  static const Color darkSurface2 = Color(0xFF21262D);
  static const Color darkBorder = Color(0xFF30363D);
  static const Color darkTextPrimary = Color(0xFFE6EDF3);
  static const Color darkTextSecondary = Color(0xFF8B949E);
  static const Color darkBlue = Color(0xFF58A6FF);
  static const Color darkBlueContainer = Color(0xFF1F6FEB);
  static const Color darkGreen = Color(0xFF3FB950);
  static const Color darkRed = Color(0xFFF85149);
  static const Color darkYellow = Color(0xFFD29922);

  // Clair
  static const Color lightBg = Color(0xFFFFFFFF);
  static const Color lightSurface = Color(0xFFF6F8FA);
  static const Color lightSurface2 = Color(0xFFEAEEF2);
  static const Color lightBorder = Color(0xFFD0D7DE);
  static const Color lightTextPrimary = Color(0xFF1F2328);
  static const Color lightTextSecondary = Color(0xFF656D76);
  static const Color lightBlue = Color(0xFF0969DA);
}

class AppTheme {
  AppTheme._();

  static ThemeData get dark => _build(brightness: Brightness.dark);
  static ThemeData get light => _build(brightness: Brightness.light);

  static ThemeData _build({required Brightness brightness}) {
    final isDark = brightness == Brightness.dark;
    final bg = isDark ? AppColors.darkBg : AppColors.lightBg;
    final surface = isDark ? AppColors.darkSurface : AppColors.lightSurface;
    final border = isDark ? AppColors.darkBorder : AppColors.lightBorder;
    final textPri = isDark
        ? AppColors.darkTextPrimary
        : AppColors.lightTextPrimary;
    final textSec = isDark
        ? AppColors.darkTextSecondary
        : AppColors.lightTextSecondary;
    final accent = isDark ? AppColors.darkBlue : AppColors.lightBlue;

    // v1.1.4 — déclaration EXPLICITE des tokens M3 dérivés
    // (errorContainer / onErrorContainer / surfaceContainerHighest /
    // primaryContainer / onPrimaryContainer / secondaryContainer / onSurfaceVariant).
    // Sans ça, M3 les calcule automatiquement à partir d'une palette GitHub
    // custom et le résultat peut être imprévisible (ex : errorContainer
    // dérivé de darkRed avec ratio WCAG insuffisant en light mode).
    // Tokens vérifiés à la main : ratio ≥ 4.5:1 sur onContainer / Container.
    final errorContainer = isDark
        ? const Color(0xFF8B1A1A) // darkRed assombri
        : const Color(0xFFFDECEC); // pâle sur fond clair
    final onErrorContainer = isDark
        ? const Color(0xFFFFDAD6)
        : const Color(0xFF410002);
    final primaryContainer = isDark
        ? AppColors.darkBlueContainer
        : const Color(0xFFD3E4FF);
    final onPrimaryContainer = isDark ? Colors.white : AppColors.lightTextPrimary;
    final surfaceContainerHighest = isDark
        ? AppColors.darkSurface2
        : AppColors.lightSurface2;

    final scheme = ColorScheme(
      brightness: brightness,
      primary: accent,
      onPrimary: Colors.white,
      primaryContainer: primaryContainer,
      onPrimaryContainer: onPrimaryContainer,
      secondary: accent,
      onSecondary: Colors.white,
      secondaryContainer: primaryContainer,
      onSecondaryContainer: onPrimaryContainer,
      surface: surface,
      onSurface: textPri,
      onSurfaceVariant: textSec,
      surfaceContainerHighest: surfaceContainerHighest,
      error: AppColors.darkRed,
      onError: Colors.white,
      errorContainer: errorContainer,
      onErrorContainer: onErrorContainer,
      outline: border,
    );

    return ThemeData(
      useMaterial3: true,
      brightness: brightness,
      colorScheme: scheme,
      scaffoldBackgroundColor: bg,
      canvasColor: bg,
      dividerColor: border,
      appBarTheme: AppBarTheme(
        backgroundColor: bg,
        foregroundColor: textPri,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        titleTextStyle: TextStyle(
          color: textPri,
          fontSize: 18,
          fontWeight: FontWeight.w600,
        ),
      ),
      cardTheme: CardThemeData(
        color: surface,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: BorderSide(color: border),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: surface,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: BorderSide(color: border),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: BorderSide(color: border),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(8),
          borderSide: BorderSide(color: accent, width: 2),
        ),
      ),
      textTheme: TextTheme(
        bodyLarge: TextStyle(color: textPri, fontSize: 15),
        bodyMedium: TextStyle(color: textPri, fontSize: 14),
        bodySmall: TextStyle(color: textSec, fontSize: 12),
        titleLarge: TextStyle(
          color: textPri,
          fontSize: 20,
          fontWeight: FontWeight.w600,
        ),
        titleMedium: TextStyle(
          color: textPri,
          fontSize: 16,
          fontWeight: FontWeight.w600,
        ),
        labelMedium: TextStyle(color: textSec, fontSize: 13),
      ),
      iconTheme: IconThemeData(color: textSec),
      listTileTheme: ListTileThemeData(iconColor: textSec, textColor: textPri),
      floatingActionButtonTheme: FloatingActionButtonThemeData(
        backgroundColor: accent,
        foregroundColor: Colors.white,
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: isDark
            ? AppColors.darkSurface2
            : AppColors.lightSurface2,
        contentTextStyle: TextStyle(color: textPri),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }
}
