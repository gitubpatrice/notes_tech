/// Préférences utilisateur persistées via SharedPreferences.
library;

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../core/constants.dart';
import '../data/models/note.dart';

class SettingsService extends ChangeNotifier {
  SettingsService(this._prefs);

  final SharedPreferences _prefs;

  static Future<SettingsService> create() async {
    final prefs = await SharedPreferences.getInstance();
    return SettingsService(prefs);
  }

  // -------- Locale (v1.0) --------
  /// Locale forcée par l'utilisateur (`fr` / `en`) ou `null` pour suivre
  /// la locale système. Persistée sous `AppConstants.prefKeyLocale`.
  Locale? get locale {
    final raw = _prefs.getString(AppConstants.prefKeyLocale);
    return switch (raw) {
      'fr' => const Locale('fr'),
      'en' => const Locale('en'),
      _ => null,
    };
  }

  Future<void> setLocale(Locale? locale) async {
    final raw = locale?.languageCode ?? 'system';
    await _prefs.setString(AppConstants.prefKeyLocale, raw);
    notifyListeners();
  }

  // -------- Theme --------
  ThemeMode get themeMode {
    final raw = _prefs.getString(AppConstants.prefKeyThemeMode);
    return switch (raw) {
      'light' => ThemeMode.light,
      'dark' => ThemeMode.dark,
      _ => ThemeMode.system,
    };
  }

  Future<void> setThemeMode(ThemeMode mode) async {
    final raw = switch (mode) {
      ThemeMode.light => 'light',
      ThemeMode.dark => 'dark',
      ThemeMode.system => 'system',
    };
    await _prefs.setString(AppConstants.prefKeyThemeMode, raw);
    notifyListeners();
  }

  // -------- Tri --------
  // Littéraux explicites, comme `themeMode` juste au-dessus, et NON
  // `mode.name` : le build de release passe par `--obfuscate`, et un nom
  // d'enum n'est pas un contrat de sérialisation stable. Une valeur écrite
  // par une version puis relue par une autre retomberait sur le `orElse`
  // et réinitialiserait silencieusement le tri de l'utilisateur.
  // `switch` exhaustif et non une Map : ajouter un mode de tri sans décider
  // de sa clé persistée doit casser À LA COMPILATION. Une Map aurait rendu
  // le même service en apparence, mais l'oubli n'aurait explosé qu'à
  // l'exécution, sur un `!` null-check, au premier changement de tri.
  static String _sortModeKey(NoteSortMode mode) => switch (mode) {
    NoteSortMode.updatedDesc => 'updatedDesc',
    NoteSortMode.updatedAsc => 'updatedAsc',
    NoteSortMode.createdDesc => 'createdDesc',
    NoteSortMode.createdAsc => 'createdAsc',
    NoteSortMode.titleAsc => 'titleAsc',
    NoteSortMode.titleDesc => 'titleDesc',
  };

  NoteSortMode get sortMode {
    final raw = _prefs.getString(AppConstants.prefKeySortMode);
    for (final mode in NoteSortMode.values) {
      if (_sortModeKey(mode) == raw) return mode;
    }
    return NoteSortMode.updatedDesc;
  }

  Future<void> setSortMode(NoteSortMode mode) async {
    await _prefs.setString(AppConstants.prefKeySortMode, _sortModeKey(mode));
    notifyListeners();
  }

  // -------- Sécurité v0.5 --------

  /// FLAG_SECURE : empêche la capture d'écran et masque l'aperçu dans le
  /// sélecteur d'apps Android. Activé par défaut — la promesse de
  /// confidentialité justifie cette légère friction (impossible de prendre
  /// un screenshot d'une note).
  bool get secureWindowEnabled =>
      _prefs.getBool(AppConstants.prefKeySecureWindowEnabled) ?? true;

  Future<void> setSecureWindowEnabled(bool value) async {
    await _prefs.setBool(AppConstants.prefKeySecureWindowEnabled, value);
    notifyListeners();
  }

  /// Délai d'auto-verrouillage des coffres déverrouillés en minutes.
  /// `0` = jamais (le coffre reste déverrouillé jusqu'à fermeture
  /// manuelle ou pause de l'app). Défaut : 15 min (cf.
  /// `AppConstants.vaultDefaultAutoLock`).
  int get vaultAutoLockMinutes =>
      _prefs.getInt(AppConstants.prefKeyVaultAutoLockMinutes) ??
      AppConstants.vaultDefaultAutoLock.inMinutes;

  Future<void> setVaultAutoLockMinutes(int minutes) async {
    await _prefs.setInt(AppConstants.prefKeyVaultAutoLockMinutes, minutes);
    notifyListeners();
  }
}
