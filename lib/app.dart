/// Widget racine — branchement thème + écran d'accueil + lifecycle.
library;

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'core/constants.dart';
import 'core/theme.dart';
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
    final settings = context.watch<SettingsService>();
    return MaterialApp(
      title: AppConstants.appName,
      debugShowCheckedModeBanner: false,
      themeMode: settings.themeMode,
      theme: AppTheme.light,
      darkTheme: AppTheme.dark,
      home: const HomeScreen(),
    );
  }
}
