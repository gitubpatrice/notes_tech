/// Réglages utilisateur : thème, tri par défaut, export, voix.
library;

import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart' show ValueListenable;
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:provider/provider.dart';
import 'package:share_plus/share_plus.dart';

import '../../data/models/folder.dart';
import '../../data/models/note.dart';
import '../../data/repositories/folders_repository.dart';
import '../../data/repositories/notes_repository.dart';
import '../../services/embedder_coordinator.dart';
import '../../services/export/note_export_service.dart';
import '../../services/secure_window_service.dart';
import '../../services/security/panic_service.dart';
import '../../services/settings_service.dart';
import '../../services/voice/voice_service.dart';
import '../widgets/panic_confirm_dialog.dart';
import 'about_screen.dart';
import 'panic_complete_screen.dart';
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
          _Section(label: 'Exporter mes données', theme: theme),
          const _ExportSection(),
          _Section(label: 'Mode panique', theme: theme),
          const _PanicSection(),
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

/// Section "Exporter mes données" : portabilité Markdown.
///
/// Trois actions :
/// - Export d'une note unique : se fait depuis l'éditeur (menu "..."),
///   pas ici. Cette section n'expose que les actions globales.
/// - Export de TOUTES les notes vivantes (hors corbeille) en ZIP avec
///   arborescence par dossier + frontmatter YAML compatible Obsidian.
///
/// Garanties :
/// - Aucun envoi réseau : le fichier est écrit dans le tmp privé app
///   puis transmis au sheet de partage Android (Intent OS).
/// - Pas d'accès aux notes en corbeille (rétention 30 j respectée — si
///   l'utilisateur veut les exporter, il restaure d'abord).
/// - Le fichier ZIP est chiffré uniquement par le système Android (zone
///   tmp privée). Une fois partagé, c'est l'app cible (Drive, mail) qui
///   gère sa sécurité — c'est un trade-off explicite de l'export.
class _ExportSection extends StatefulWidget {
  const _ExportSection();

  @override
  State<_ExportSection> createState() => _ExportSectionState();
}

class _ExportSectionState extends State<_ExportSection> {
  bool _busy = false;

  Future<void> _exportAllAsZip() async {
    if (_busy) return;
    final messenger = ScaffoldMessenger.of(context);
    final notesRepo = context.read<NotesRepository>();
    final foldersRepo = context.read<FoldersRepository>();
    setState(() => _busy = true);
    try {
      // 1. Récupère toutes les notes vivantes (hors corbeille — rétention
      //    préservée) + index id→Folder pour résoudre les noms.
      final notes = await notesRepo.listAllAlive();
      final folders = await foldersRepo.listAll();
      final foldersById = <String, Folder>{
        for (final f in folders) f.id: f,
      };

      if (notes.isEmpty) {
        if (!mounted) return;
        messenger.showSnackBar(
          const SnackBar(
            content: Text('Aucune note à exporter.'),
            behavior: SnackBarBehavior.floating,
          ),
        );
        return;
      }

      // 2. Encode le ZIP **dans un isolate** : ZipEncoder est CPU-bound
      //    pur, le faire sur le thread UI provoque un jank de plusieurs
      //    centaines de ms à plusieurs secondes (S9, POCO C75) avec un
      //    spinner qui ne tourne pas. Le `compute()` libère le main, le
      //    CircularProgressIndicator du `_busy` reste fluide.
      final zipBytes = await NoteExportService.exportAsZipInIsolate(
        notes: notes,
        foldersById: foldersById,
      );
      final tmpDir = await getTemporaryDirectory();
      final ts = DateTime.now()
          .toIso8601String()
          .replaceAll(':', '-')
          .split('.')
          .first;
      final file = File('${tmpDir.path}/notes-tech-export-$ts.zip');
      await file.writeAsBytes(zipBytes, flush: true);

      // 3. Transmet à Android via Intent (Drive, mail, USB...).
      if (!mounted) return;
      try {
        await Share.shareXFiles(
          [XFile(file.path, mimeType: 'application/zip')],
          subject: 'Export Notes Tech (${notes.length} notes)',
        );
      } finally {
        // Cleanup best-effort : le ZIP peut peser plusieurs Mo. On évite
        // l'accumulation entre deux purges automatiques d'Android.
        try {
          if (await file.exists()) await file.delete();
        } catch (_) {/* best-effort */}
      }
    } catch (e) {
      if (!mounted) return;
      messenger.showSnackBar(
        SnackBar(
          content: Text('Export impossible : $e'),
          behavior: SnackBarBehavior.floating,
        ),
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: const Icon(Icons.archive_outlined),
      title: const Text('Exporter toutes mes notes (.zip)'),
      subtitle: const Text(
        'Markdown + frontmatter YAML compatible Obsidian. '
        'Arborescence par dossier. Notes en corbeille exclues.',
      ),
      trailing: _busy
          ? const SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : const Icon(Icons.share_outlined),
      onTap: _busy ? null : _exportAllAsZip,
    );
  }
}

/// Section "Mode panique" : trigger d'effacement irréversible.
///
/// UI volontairement austère et discrète (pas de FAB rouge clignotant) :
/// - Tile en couleur d'erreur pour signaler la nature destructrice.
/// - Confirmation par phrase tapée (cf. `confirmPanicDialog`).
/// - Pendant l'exécution : Scaffold de blocage (impossible de revenir
///   en arrière). À la fin : navigation `pushReplacement` vers
///   PanicCompleteScreen — la pile précédente référence des objets dont
///   les données sont détruites.
class _PanicSection extends StatefulWidget {
  const _PanicSection();

  @override
  State<_PanicSection> createState() => _PanicSectionState();
}

class _PanicSectionState extends State<_PanicSection> {
  bool _running = false;

  Future<void> _trigger() async {
    if (_running) return;
    final panic = context.read<PanicService>();
    final confirmed = await confirmPanicDialog(context);
    if (confirmed != true || !mounted) return;

    // Affiche un loader bloquant pendant l'exécution. Le mode panique
    // doit aboutir en quelques secondes même sur S9 — Gemma uninstall
    // (~530 Mo delete) + DB wipe (zeroize + delete) + tmp purge.
    setState(() => _running = true);
    final navigator = Navigator.of(context);
    // `unawaited` explicite : le dialog est non-bloquant côté code, mais
    // visuellement modal côté utilisateur. On le ferme manuellement après
    // `panic.trigger()`. Pas de `await` ici sinon on attendrait la
    // fermeture du dialog (ce qui n'arrive jamais sans pop manuel).
    unawaited(
      showDialog<void>(
        context: context,
        barrierDismissible: false,
        builder: (_) => const _PanicProgressDialog(),
      ),
    );

    try {
      await panic.trigger();
    } catch (_) {
      // Tous les steps sont best-effort dans PanicService.trigger ;
      // l'exception est cosmétique (ne devrait pas remonter), mais
      // on ne veut pas planter ici. La séquence a au minimum tenté
      // de détruire la KEK.
    }
    if (!mounted) return;
    // Ferme le dialogue de progression PUIS remplace la stack pour que
    // l'utilisateur ne puisse pas revenir sur Settings (les services
    // sont vides désormais).
    navigator.pop(); // ferme le dialog
    await navigator.pushAndRemoveUntil<void>(
      MaterialPageRoute<void>(builder: (_) => const PanicCompleteScreen()),
      (_) => false,
    );
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return ListTile(
      leading: Icon(Icons.local_fire_department_outlined, color: cs.error),
      title: Text(
        'Tout effacer maintenant',
        style: TextStyle(color: cs.error, fontWeight: FontWeight.w600),
      ),
      subtitle: const Text(
        'Détruit notes + clé maître + modèles IA + préférences en quelques '
        'secondes. Action irréversible — confirmation par mot tapé.',
      ),
      trailing: _running
          ? const SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : Icon(Icons.chevron_right, color: cs.error),
      onTap: _running ? null : _trigger,
    );
  }
}

/// Dialogue modal pendant l'exécution de la panique. PopScope bloque le
/// retour — l'utilisateur ne doit pas pouvoir interrompre l'effacement
/// en cours (sinon état partiellement détruit, rare mais possible).
class _PanicProgressDialog extends StatelessWidget {
  const _PanicProgressDialog();

  @override
  Widget build(BuildContext context) {
    return const PopScope(
      canPop: false,
      child: AlertDialog(
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text(
              'Effacement en cours…',
              style: TextStyle(fontWeight: FontWeight.w600),
            ),
            SizedBox(height: 4),
            Text(
              'Quelques secondes.',
              style: TextStyle(fontSize: 12),
            ),
          ],
        ),
      ),
    );
  }
}
