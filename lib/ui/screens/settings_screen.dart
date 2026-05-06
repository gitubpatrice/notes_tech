/// Réglages utilisateur : thème, tri par défaut.
library;

import 'package:flutter/foundation.dart' show ValueListenable;
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../data/models/note.dart';
import '../../services/embedder_coordinator.dart';
import '../../services/secure_window_service.dart';
import '../../services/settings_service.dart';
import '../../services/voice/voice_service.dart';
import 'about_screen.dart';
import 'voice_setup_screen.dart';

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
          _Section(label: 'Dictée vocale', theme: theme),
          const _VoiceSection(),
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

/// Section "Dictée vocale" : présence du modèle Whisper, changer/désinstaller.
///
/// Trois états visuels :
/// - **Pas de modèle** : un seul ListTile "Activer la dictée vocale" qui
///   pousse [VoiceSetupScreen].
/// - **Modèle installé** : ListTile descriptif (nom + taille) + ListTile
///   "Changer de modèle" + ListTile "Désinstaller le modèle".
/// - **Erreur transitoire** : pas affichée ici (overlay capture s'en occupe).
///
/// Le widget écoute [VoiceService] via `Consumer` pour rebuild automatique
/// après import / désinstallation / changement de modèle.
class _VoiceSection extends StatelessWidget {
  const _VoiceSection();

  @override
  Widget build(BuildContext context) {
    return Consumer<VoiceService>(
      builder: (context, voice, _) {
        final model = voice.activeModel;
        if (model == null) {
          return ListTile(
            leading: const Icon(Icons.mic_none_outlined),
            title: const Text('Activer la dictée vocale'),
            subtitle: const Text(
              'Importez un modèle Whisper pour dicter vos notes hors-ligne.',
            ),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => _openSetup(context),
          );
        }
        return Column(
          children: [
            ListTile(
              leading: const Icon(Icons.mic_outlined),
              title: const Text('Modèle actif'),
              subtitle: Text(
                '${model.displayName}\n'
                '${_formatSize(model.sizeBytes)}',
              ),
              isThreeLine: true,
            ),
            ListTile(
              leading: const Icon(Icons.swap_horiz_outlined),
              title: const Text('Changer de modèle'),
              subtitle: const Text(
                'Remplace le modèle actuel par un autre fichier .bin.',
              ),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => _openSetup(context),
            ),
            ListTile(
              leading: Icon(
                Icons.delete_outline,
                color: Theme.of(context).colorScheme.error,
              ),
              title: Text(
                'Désinstaller le modèle',
                style: TextStyle(
                  color: Theme.of(context).colorScheme.error,
                ),
              ),
              subtitle: Text(
                'Libère ${_formatSize(model.sizeBytes)}. La dictée vocale '
                'sera désactivée jusqu\'à un nouvel import.',
              ),
              onTap: () => _confirmUninstall(context, voice, model.displayName),
            ),
          ],
        );
      },
    );
  }

  Future<void> _openSetup(BuildContext context) async {
    await Navigator.of(context).push<void>(
      MaterialPageRoute<void>(builder: (_) => const VoiceSetupScreen()),
    );
  }

  Future<void> _confirmUninstall(
    BuildContext context,
    VoiceService voice,
    String displayName,
  ) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        icon: const Icon(Icons.delete_outline),
        title: const Text('Désinstaller le modèle ?'),
        content: Text(
          'Le fichier "$displayName" sera supprimé du téléphone. '
          'Vous pourrez le réimporter plus tard si besoin.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Annuler'),
          ),
          FilledButton.tonal(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Désinstaller'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    await voice.uninstallActiveModel();
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Modèle désinstallé.'),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  /// Affiche une taille en Mo arrondie à l'entier le plus proche. Cohérent
  /// avec l'affichage utilisé dans `voice_setup_screen.dart` (catalogue
  /// mentionne "57 Mo", pas "57.3 Mo").
  static String _formatSize(int bytes) {
    final mb = (bytes / (1024 * 1024)).round();
    return '$mb Mo';
  }
}
