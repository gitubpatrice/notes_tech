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
}
