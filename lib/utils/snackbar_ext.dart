library;

import 'package:flutter/material.dart';

extension SnackbarExt on BuildContext {
  /// Affiche un SnackBar (le thème global pose déjà
  /// `behavior: SnackBarBehavior.floating`, pas besoin de le redéclarer).
  void showFloatingSnack(String message, {Duration? duration}) {
    final messenger = ScaffoldMessenger.maybeOf(this);
    if (messenger == null) return;
    messenger.showSnackBar(
      SnackBar(
        content: Text(message),
        duration: duration ?? const Duration(seconds: 4),
      ),
    );
  }
}
