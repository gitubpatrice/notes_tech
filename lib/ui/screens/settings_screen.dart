/// Réglages utilisateur : thème, tri par défaut.
library;

import 'package:flutter/foundation.dart' show ValueListenable;
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../data/models/note.dart';
import '../../services/embedder_coordinator.dart';
import '../../services/secure_window_service.dart';
import '../../services/settings_service.dart';
import 'about_screen.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final settings = context.watch<SettingsService>();
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(title: const Text('Réglages')),
      body: ListView(
        children: [
          _Section(label: 'Apparence', theme: theme),
          ListTile(
            leading: const Icon(Icons.dark_mode_outlined),
            title: const Text('Thème'),
            subtitle: Text(_themeLabel(settings.themeMode)),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => _showThemeDialog(context, settings),
          ),
          _Section(label: 'Notes', theme: theme),
          ListTile(
            leading: const Icon(Icons.sort),
            title: const Text('Tri par défaut'),
            subtitle: Text(settings.sortMode.label),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => _showSortDialog(context, settings),
          ),
          _Section(label: 'Recherche sémantique', theme: theme),
          SwitchListTile(
            secondary: const Icon(Icons.auto_awesome_outlined),
            title: const Text('Mode avancé (MiniLM)'),
            subtitle: const Text(
              'Plus pertinent pour les synonymes et les paraphrases. '
              'La première activation indexe vos notes en arrière-plan.',
            ),
            value: settings.semanticSearchEnabled,
            onChanged: (v) => settings.setSemanticSearchEnabled(v),
          ),
          _SemanticErrorTile(
            error: context.read<EmbedderCoordinator>().lastError,
          ),
          _Section(label: 'Sécurité', theme: theme),
          SwitchListTile(
            secondary: const Icon(Icons.visibility_off_outlined),
            title: const Text('Masquer dans les apps récentes'),
            subtitle: const Text(
              'Empêche les captures d\'écran et masque l\'aperçu de l\'app '
              'dans le sélecteur Android (FLAG_SECURE).',
            ),
            value: settings.secureWindowEnabled,
            onChanged: (v) async {
              final secure = context.read<SecureWindowService>();
              await settings.setSecureWindowEnabled(v);
              await secure.setEnabled(v);
            },
          ),
          SwitchListTile(
            secondary: const Icon(Icons.warning_amber_outlined),
            title: const Text('Accepter un modèle Gemma non vérifié'),
            subtitle: const Text(
              'Réglage avancé : permet d\'importer un .task dont le SHA-256 '
              'ne correspond pas au modèle officiel (variantes, builds tiers). '
              'À vos risques.',
            ),
            value: settings.acceptUnknownGemmaHash,
            onChanged: settings.setAcceptUnknownGemmaHash,
          ),
          _Section(label: 'À propos', theme: theme),
          ListTile(
            leading: const Icon(Icons.info_outline),
            title: const Text('Notes Tech'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => Navigator.of(context).push(
              MaterialPageRoute<void>(builder: (_) => const AboutScreen()),
            ),
          ),
        ],
      ),
    );
  }

  static String _themeLabel(ThemeMode m) => switch (m) {
        ThemeMode.light => 'Clair',
        ThemeMode.dark => 'Sombre',
        ThemeMode.system => 'Système',
      };

  static Future<void> _showThemeDialog(
    BuildContext context,
    SettingsService settings,
  ) async {
    final selected = await showDialog<ThemeMode>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: const Text('Thème'),
        children: ThemeMode.values
            .map((m) => ListTile(
                  leading: Icon(settings.themeMode == m
                      ? Icons.radio_button_checked
                      : Icons.radio_button_unchecked),
                  title: Text(_themeLabel(m)),
                  onTap: () => Navigator.of(ctx).pop(m),
                ))
            .toList(),
      ),
    );
    if (selected != null) await settings.setThemeMode(selected);
  }

  static Future<void> _showSortDialog(
    BuildContext context,
    SettingsService settings,
  ) async {
    final selected = await showDialog<NoteSortMode>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: const Text('Tri par défaut'),
        children: NoteSortMode.values
            .map((m) => ListTile(
                  leading: Icon(settings.sortMode == m
                      ? Icons.radio_button_checked
                      : Icons.radio_button_unchecked),
                  title: Text(m.label),
                  onTap: () => Navigator.of(ctx).pop(m),
                ))
            .toList(),
      ),
    );
    if (selected != null) await settings.setSortMode(selected);
  }
}

/// Tuile d'avertissement affichée quand l'activation de MiniLM a échoué.
/// Visible seulement si `error.value != null`.
class _SemanticErrorTile extends StatelessWidget {
  const _SemanticErrorTile({required this.error});
  final ValueListenable<String?> error;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return ValueListenableBuilder<String?>(
      valueListenable: error,
      builder: (_, msg, _) {
        if (msg == null) return const SizedBox.shrink();
        return ListTile(
          leading: Icon(Icons.warning_amber_outlined,
              color: theme.colorScheme.error),
          title: Text(msg, style: TextStyle(color: theme.colorScheme.error)),
        );
      },
    );
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.label, required this.theme});
  final String label;
  final ThemeData theme;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Text(label.toUpperCase(),
          style: theme.textTheme.labelMedium?.copyWith(
            letterSpacing: 1.2,
            fontWeight: FontWeight.w600,
          )),
    );
  }
}
