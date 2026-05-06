/// Helpers d'accessibilité (WCAG 2.3.3, RGAA 13.1).
///
/// Centralise les conversions sensibles à `MediaQuery.disableAnimations`
/// et les couleurs sémantiques qu'on hardcode parfois faute de token
/// adapté dans `ColorScheme` (favori, succès…).
library;

import 'package:flutter/material.dart';

/// Retourne `Duration.zero` si l'utilisateur a activé "Réduire les
/// animations" dans Réglages Android, sinon la durée demandée.
///
/// À utiliser pour TOUTES les `AnimatedFoo` ayant une durée explicite,
/// pour respecter WCAG 2.3.3 (Pause, Stop, Hide).
Duration accessibleDuration(BuildContext context, Duration d) {
  return MediaQuery.disableAnimationsOf(context) ? Duration.zero : d;
}

/// Couleurs sémantiques manquantes dans Material 3 `ColorScheme`.
///
/// - `favoriteIcon` : étoile favori (Material historique = ambre). On
///   bascule sur `cs.tertiary` quand la palette est déjà chaude, sinon
///   on garde l'ambre Material avec un fallback `cs.tertiary` en mode
///   sombre pour éviter le contraste insuffisant.
/// - `successIcon` : checkmark de validation (vert). Aligné sur
///   `cs.primary` pour rester cohérent avec le thème ; les designs qui
///   exigent un vert spécifique peuvent surcharger localement.
extension SemanticColors on ColorScheme {
  /// Couleur du badge favori — chaude, lisible en clair ET en sombre.
  Color get favoriteIcon => brightness == Brightness.dark
      ? const Color(0xFFFFCC80) // Material amber 200 (clair, contrasté)
      : const Color(0xFFFB8C00); // Material amber 700 (foncé, contrasté)

  /// Couleur d'état "OK / réussi" — réutilise `primary` pour rester
  /// dans la palette du thème (évite Colors.green hardcodé qui ignore
  /// le mode sombre et le thème personnalisé).
  Color get successIcon => primary;
}
