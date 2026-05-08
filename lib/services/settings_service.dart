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
  NoteSortMode get sortMode {
    final raw = _prefs.getString(AppConstants.prefKeySortMode);
    return NoteSortMode.values.firstWhere(
      (s) => s.name == raw,
      orElse: () => NoteSortMode.updatedDesc,
    );
  }

  Future<void> setSortMode(NoteSortMode mode) async {
    await _prefs.setString(AppConstants.prefKeySortMode, mode.name);
    notifyListeners();
  }

  // -------- Recherche sémantique avancée (MiniLM) --------
  // Désactivée par défaut : LocalEmbedder est instantané et suffit pour
  // démarrer. L'utilisateur active MiniLM quand il accepte de patienter
  // pour la première indexation (quelques secondes par centaine de notes).
  bool get semanticSearchEnabled =>
      _prefs.getBool(AppConstants.prefKeySemanticSearchEnabled) ?? false;

  Future<void> setSemanticSearchEnabled(bool value) async {
    await _prefs.setBool(AppConstants.prefKeySemanticSearchEnabled, value);
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

  /// Réglage avancé : accepter un modèle Gemma dont le SHA-256 ne
  /// correspond pas à l'empreinte de référence. Désactivé par défaut.
  /// L'utilisateur informé peut activer pour importer une variante
  /// (Gemma 3 4B int4, build différent, etc.).
  bool get acceptUnknownGemmaHash =>
      _prefs.getBool(AppConstants.prefKeyAcceptUnknownGemmaHash) ?? false;

  Future<void> setAcceptUnknownGemmaHash(bool value) async {
    await _prefs.setBool(AppConstants.prefKeyAcceptUnknownGemmaHash, value);
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
    await _prefs.setInt(
      AppConstants.prefKeyVaultAutoLockMinutes,
      minutes,
    );
    notifyListeners();
  }
}
