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
  }) {
    final messenger = ScaffoldMessenger.maybeOf(this);
    if (messenger == null) return;
    messenger.showFloatingSnack(
      message,
      duration: duration,
      backgroundColor: backgroundColor,
    );
  }
}

/// Variante pour les flux async où l'appelant a capturé [ScaffoldMessenger]
/// AVANT un `await` afin de ne pas dépendre du `BuildContext` après la
/// frontière asynchrone (pattern recommandé par les lints
/// `use_build_context_synchronously`).
extension SnackbarMessengerExt on ScaffoldMessengerState {
  /// Affiche un SnackBar avec les défauts du thème global (floating).
  void showFloatingSnack(
    String message, {
    Duration? duration,
    Color? backgroundColor,
  }) {
    showSnackBar(
      SnackBar(
        content: Text(message),
        duration: duration ?? const Duration(seconds: 4),
        backgroundColor: backgroundColor,
      ),
    );
  }
}
