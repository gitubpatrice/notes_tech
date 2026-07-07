library;

import 'package:flutter/material.dart';

extension SnackbarExt on BuildContext {
  /// Affiche un SnackBar (le thème global pose déjà
  /// `behavior: SnackBarBehavior.floating`, pas besoin de le redéclarer).
  ///
  /// Le caller peut surcharger [duration] et [backgroundColor] (utile pour
  /// les erreurs : `cs.error` avec durée plus longue 6-8s).
  void showFloatingSnack(
    String message, {
    Duration? duration,
    Color? backgroundColor,
    Color? foregroundColor,
  }) {
    final messenger = ScaffoldMessenger.maybeOf(this);
    if (messenger == null) return;
    messenger.showFloatingSnack(
      message,
      duration: duration,
      backgroundColor: backgroundColor,
      foregroundColor: foregroundColor,
    );
  }

  /// v1.1.4 (M4) — helper canonique snack d'erreur. Pose automatiquement
  /// la paire `cs.errorContainer` + `cs.onErrorContainer` pour garantir
  /// le contraste WCAG AA, et ferme la porte aux usages où un caller
  /// omettait `foregroundColor`. Durée par défaut 6 s (lecture erreur).
  void showErrorSnack(String message, {Duration? duration}) {
    final cs = Theme.of(this).colorScheme;
    showFloatingSnack(
      message,
      backgroundColor: cs.errorContainer,
      foregroundColor: cs.onErrorContainer,
      duration: duration ?? const Duration(seconds: 6),
    );
  }

  /// v1.1.4 (M4) — helper canonique snack succès. Pose la paire
  /// `cs.primaryContainer` + `cs.onPrimaryContainer`. Durée par défaut
  /// 3 s (acquittement bref).
  void showSuccessSnack(String message, {Duration? duration}) {
    final cs = Theme.of(this).colorScheme;
    showFloatingSnack(
      message,
      backgroundColor: cs.primaryContainer,
      foregroundColor: cs.onPrimaryContainer,
      duration: duration ?? const Duration(seconds: 3),
    );
  }
}

/// Variante pour les flux async où l'appelant a capturé [ScaffoldMessenger]
/// AVANT un `await` afin de ne pas dépendre du `BuildContext` après la
/// frontière asynchrone (pattern recommandé par les lints
/// `use_build_context_synchronously`).
extension SnackbarMessengerExt on ScaffoldMessengerState {
  /// Affiche un SnackBar avec les défauts du thème global (floating).
  ///
  /// U2 v1.1.0 — Le caller peut passer [foregroundColor] pour garantir
  /// le contraste WCAG AA (ex: pour une erreur, `cs.onErrorContainer`
  /// sur un fond `cs.errorContainer`). Avant : texte par défaut
  /// (souvent `textPri` clair) sur fond rouge brut donnait un contraste
  /// limite en light mode (~3.5:1, exigence AA 4.5:1).
  void showFloatingSnack(
    String message, {
    Duration? duration,
    Color? backgroundColor,
    Color? foregroundColor,
  }) {
    showSnackBar(
      SnackBar(
        content: Text(
          message,
          style: foregroundColor != null
              ? TextStyle(color: foregroundColor)
              : null,
        ),
        duration: duration ?? const Duration(seconds: 4),
        backgroundColor: backgroundColor,
      ),
    );
  }

  /// v1.1.5 — pendant `ScaffoldMessengerState` de [SnackbarExt.showErrorSnack].
  /// Le [cs] doit être capturé AVANT l'`await` (comme le messenger), car cette
  /// extension n'a pas de `BuildContext` pour résoudre le thème. Pose la paire
  /// `errorContainer`/`onErrorContainer` (contraste WCAG AA). Sans ce helper,
  /// les erreurs émises dans un `catch` post-await s'affichaient en style
  /// neutre (le contraste erreur annoncé n'était pas effectif).
  void showErrorSnack(
    String message,
    ColorScheme cs, {
    Duration? duration,
  }) {
    showFloatingSnack(
      message,
      backgroundColor: cs.errorContainer,
      foregroundColor: cs.onErrorContainer,
      duration: duration ?? const Duration(seconds: 6),
    );
  }

  /// v1.1.5 — pendant `ScaffoldMessengerState` de [SnackbarExt.showSuccessSnack].
  /// [cs] capturé avant l'`await`. Pose `primaryContainer`/`onPrimaryContainer`.
  void showSuccessSnack(
    String message,
    ColorScheme cs, {
    Duration? duration,
  }) {
    showFloatingSnack(
      message,
      backgroundColor: cs.primaryContainer,
      foregroundColor: cs.onPrimaryContainer,
      duration: duration ?? const Duration(seconds: 3),
    );
  }
}
