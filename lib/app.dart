/// Widget racine — branchement thème + locale + écran d'accueil + lifecycle.
library;

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'core/constants.dart';
import 'core/theme.dart';
import 'l10n/app_localizations.dart';
import 'services/security/folder_vault_service.dart';
import 'services/settings_service.dart';
import 'ui/screens/home_screen.dart';

class NotesTechApp extends StatefulWidget {
  const NotesTechApp({super.key});

  @override
  State<NotesTechApp> createState() => _NotesTechAppState();
}

class _NotesTechAppState extends State<NotesTechApp>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Verrouille **TOUS** les coffres déverrouillés dès que l'app passe
    // en arrière-plan ou est mise en pause. Un attaquant qui prend le
    // tél déverrouillé après que l'utilisateur a quitté l'app ne pourra
    // pas re-rentrer dans un coffre sans la passphrase.
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached ||
        state == AppLifecycleState.hidden) {
      final vault = context.read<FolderVaultService>();
      vault.lockAll();
    }
  }

  @override
  Widget build(BuildContext context) {
    // Selector ciblé : ne reconstruit que sur changement themeMode/locale,
    // pas sur sortMode/semanticSearchEnabled/secureWindowEnabled/auto-lock
    // (audit perf P1-3 : context.watch<SettingsService>() reconstruisait
    // tout le MaterialApp à chaque changement, même non-théme/non-locale).
    return Selector<SettingsService, _AppSettingsTuple>(
      selector: (_, s) => _AppSettingsTuple(s.themeMode, s.locale),
      builder: (_, settings, _) {
        return MaterialApp(
          title: AppConstants.appName,
          debugShowCheckedModeBanner: false,
          themeMode: settings.themeMode,
          theme: AppTheme.light,
          darkTheme: AppTheme.dark,
          locale: settings.locale,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const HomeScreen(),
        );
      },
    );
  }
}

@immutable
class _AppSettingsTuple {
  const _AppSettingsTuple(this.themeMode, this.locale);
  final ThemeMode themeMode;
  final Locale? locale;

  @override
  bool operator ==(Object other) =>
      other is _AppSettingsTuple &&
      other.themeMode == themeMode &&
      other.locale?.languageCode == locale?.languageCode;

  @override
  int get hashCode => Object.hash(themeMode, locale?.languageCode);
}
