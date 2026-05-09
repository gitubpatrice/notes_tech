/// Service de bascule du flag `WindowManager.LayoutParams.FLAG_SECURE`.
///
/// Côté Android, ce flag :
///   - empêche `MediaProjection` / screenshots utilisateur sur les écrans
///     de l'app ;
///   - masque l'aperçu dans le sélecteur d'apps récentes (vignette noire).
///
/// La valeur réelle persiste dans `SharedPreferences` via `SettingsService`.
/// `MainActivity.kt` lit cette pref dans `onCreate` (pour appliquer le flag
/// AVANT le 1er frame) et écoute le canal pour les changements à chaud.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class SecureWindowService {
  SecureWindowService();

  static const MethodChannel _channel = MethodChannel(
    'notes_tech/secure_window',
  );

  /// Applique l'état demandé. Erreurs silencieuses en release : un échec
  /// de canal n'a aucun impact fonctionnel hors écran (le flag persiste
  /// déjà côté pref pour le prochain démarrage).
  Future<void> setEnabled(bool enabled) async {
    try {
      await _channel.invokeMethod<void>('setEnabled', <String, Object?>{
        'enabled': enabled,
      });
    } catch (e) {
      if (kDebugMode) {
        debugPrint('SecureWindowService.setEnabled($enabled) — $e');
      }
    }
  }
}
