import 'package:shared_preferences/shared_preferences.dart';

/// v1.1.1 — Drapeau persistant "splash de présentation déjà vu".
///
/// Miroir Flutter de [AdvancedSettings.splashShown] côté SMS Tech Kotlin
/// (DataStore). Une seule clé booléenne ; défaut `false` = pas encore vu →
/// splash à afficher au premier lancement.
///
/// Effacer les données depuis Paramètres système Android = la clé disparaît →
/// le splash réapparaît à la prochaine ouverture (sémantique attendue, alignée
/// SMS Tech v1.3.7 + Pass Tech v2.4.5).
///
/// Hydratation : doit être lu dans `main()` AVANT `runApp` pour décider du
/// premier frame sans flash.
class FirstLaunchFlag {
  static const String _key = 'splash_shown_v1';

  /// Retourne `true` si le splash doit être affiché (= jamais vu).
  static Future<bool> shouldShow() async {
    final prefs = await SharedPreferences.getInstance();
    return !(prefs.getBool(_key) ?? false);
  }

  /// Pose le flag à `true` une fois le splash dismissé (auto-dismiss, tap, back).
  /// Idempotent — appel multiple = no-op après le premier.
  static Future<void> markShown() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_key, true);
  }
}
