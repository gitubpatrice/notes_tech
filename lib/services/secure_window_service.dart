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
///
/// v1.0.7 UI I1 — refcount de force (pattern Pass Tech v2.3.8) :
/// Certains écrans hautement sensibles (vault unlock sheets, ai_chat avec
/// contexte RAG, note vault déchiffrée) doivent garantir FLAG_SECURE
/// **même si l'utilisateur l'a désactivé globalement**. On utilise un
/// refcount pour imbriquer les forces : `forceEnabled()` pousse, `restore()`
/// dépile. Le flag effectif côté natif est `userPref OR forcedCount>0`.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

class SecureWindowService {
  SecureWindowService();

  static const MethodChannel _channel = MethodChannel(
    'notes_tech/secure_window',
  );

  /// Applique l'état demandé en tant que **préférence utilisateur**. Cette
  /// valeur est combinée avec le refcount de force côté natif pour décider
  /// du flag effectif.
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

  /// Force FLAG_SECURE ON pour la durée de l'écran appelant, quelle que
  /// soit la préférence utilisateur. Idempotent via refcount côté natif :
  /// chaque `forceEnabled()` DOIT être accompagné d'un `restore()` final
  /// (typiquement `initState` ↔ `dispose`).
  Future<void> forceEnabled() async {
    try {
      await _channel.invokeMethod<void>('forceEnabled');
    } catch (e) {
      if (kDebugMode) debugPrint('SecureWindowService.forceEnabled — $e');
    }
  }

  /// Décrémente le refcount de force. Quand il retombe à zéro, la
  /// préférence utilisateur reprend la main.
  Future<void> restore() async {
    try {
      await _channel.invokeMethod<void>('restore');
    } catch (e) {
      if (kDebugMode) debugPrint('SecureWindowService.restore — $e');
    }
  }
}

/// Mixin pour StatefulWidget — force FLAG_SECURE pendant la vie de l'écran.
///
/// Utilisation :
/// ```dart
/// class _MyScreenState extends State<MyScreen> with SecureWindowGuardMixin {
///   ...
/// }
/// ```
///
/// Aucune dépendance à Provider : le service est instancié localement
/// (stateless, ne contient que le `MethodChannel`). Si l'écran est mis
/// en arrière-plan puis revenu, le refcount reste consistant — c'est le
/// dispose qui décrémente, pas le lifecycle.
mixin SecureWindowGuardMixin<T extends StatefulWidget> on State<T> {
  final SecureWindowService _secureGuard = SecureWindowService();
  bool _secureGuardActive = false;

  @override
  void initState() {
    super.initState();
    _secureGuardActive = true;
    // Best-effort fire-and-forget : le délai d'aller-retour MethodChannel
    // (~5-20 ms) est invisible pour l'utilisateur.
    _secureGuard.forceEnabled();
  }

  @override
  void dispose() {
    if (_secureGuardActive) {
      _secureGuardActive = false;
      _secureGuard.restore();
    }
    super.dispose();
  }
}
