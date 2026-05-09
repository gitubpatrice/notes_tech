/// Réglages utilisateur : thème, langue, tri par défaut, export, voix.
library;

import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart' show ValueListenable;
import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:path_provider/path_provider.dart';
import 'package:provider/provider.dart';
import 'package:share_plus/share_plus.dart';

import '../../data/models/folder.dart';
import '../../data/models/note.dart';
import '../../utils/snackbar_ext.dart';
import '../widgets/blocking_progress_dialog.dart';
import '../../data/repositories/folders_repository.dart';
import '../../data/repositories/notes_repository.dart';
import '../../l10n/app_localizations.dart';
import '../../services/embedder_coordinator.dart';
import '../../services/export/note_export_service.dart';
import '../../services/secure_window_service.dart';
import '../../services/security/folder_vault_service.dart';
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
    final t = AppLocalizations.of(context);

    return Scaffold(
      appBar: AppBar(title: Text(t.settingsTitle)),
      body: ListView(
        children: [
          _Section(label: t.settingsSectionAppearance, theme: theme),
          const _LanguageTile(),
          const _ThemeTile(),
          _Section(label: t.homeSortMode, theme: theme),
          MergeSemantics(
            child: ListTile(
              leading: const Icon(Icons.sort),
              title: Text(t.homeSortMode),
              subtitle: Text(_localizedSortLabel(t, settings.sortMode)),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => _showSortDialog(context, settings, t),
            ),
          ),
          _Section(label: t.searchHeadingSemantic, theme: theme),
          SwitchListTile(
            secondary: const Icon(Icons.auto_awesome_outlined),
            title: Text(t.settingsSemanticSearch),
            subtitle: Text(t.settingsSemanticSearchSubtitle),
            value: settings.semanticSearchEnabled,
            onChanged: (v) => settings.setSemanticSearchEnabled(v),
          ),
          _SemanticErrorTile(
            error: context.read<EmbedderCoordinator>().lastError,
          ),
          _Section(label: t.settingsSectionSecurity, theme: theme),
          SwitchListTile(
            secondary: const Icon(Icons.visibility_off_outlined),
            title: Text(t.settingsSecureWindow),
            subtitle: Text(t.settingsSecureWindowSubtitle),
            value: settings.secureWindowEnabled,
            onChanged: (v) async {
              final secure = context.read<SecureWindowService>();
              await settings.setSecureWindowEnabled(v);
              await secure.setEnabled(v);
            },
          ),
          SwitchListTile(
            secondary: const Icon(Icons.warning_amber_outlined),
            title: Text(t.settingsAcceptUnknownGemmaHash),
            subtitle: Text(t.settingsAcceptUnknownGemmaHashSubtitle),
            value: settings.acceptUnknownGemmaHash,
            onChanged: settings.setAcceptUnknownGemmaHash,
          ),
          const _VaultAutoLockTile(),
          _Section(label: t.voiceSetupTitle, theme: theme),
          const _VoiceSection(),
          _Section(label: t.settingsExportAll, theme: theme),
          const _ExportSection(),
          _Section(label: t.settingsPanic, theme: theme),
          const _PanicSection(),
          _Section(label: t.settingsSectionAbout, theme: theme),
          MergeSemantics(
            child: ListTile(
              leading: const Icon(Icons.info_outline),
              title: Text(t.settingsAbout),
              subtitle: Text(t.settingsAboutSubtitle),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute<void>(builder: (_) => const AboutScreen()),
              ),
            ),
          ),
        ],
      ),
    );
  }

  static String _localizedSortLabel(AppLocalizations t, NoteSortMode m) =>
      switch (m) {
        NoteSortMode.updatedDesc => t.homeSortRecentFirst,
        NoteSortMode.updatedAsc => t.homeSortOldFirst,
        NoteSortMode.createdDesc => t.homeSortRecentFirst,
        NoteSortMode.createdAsc => t.homeSortOldFirst,
        NoteSortMode.titleAsc => t.homeSortAlphaAsc,
        NoteSortMode.titleDesc => t.homeSortAlphaDesc,
      };

  static Future<void> _showSortDialog(
    BuildContext context,
    SettingsService settings,
    AppLocalizations t,
  ) async {
    final selected = await showDialog<NoteSortMode>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: Text(t.homeSortMode),
        children: NoteSortMode.values
            .map(
              (m) => ListTile(
                leading: Icon(
                  settings.sortMode == m
                      ? Icons.radio_button_checked
                      : Icons.radio_button_unchecked,
                ),
                title: Text(_localizedSortLabel(t, m)),
                onTap: () => Navigator.of(ctx).pop(m),
              ),
            )
            .toList(),
      ),
    );
    if (selected != null) await settings.setSortMode(selected);
  }
}

/// Sélecteur de langue (système / fr / en).
///
/// Utilise un PopupMenuButton avec CheckedPopupMenuItem pour signaler
/// visuellement la sélection courante. Au choix, on appelle
/// `settings.setLocale(...)` puis on annonce le changement via
/// `SemanticsService` pour TalkBack/lecteurs d'écran.
class _LanguageTile extends StatelessWidget {
  const _LanguageTile();

  @override
  Widget build(BuildContext context) {
    final settings = context.watch<SettingsService>();
    final t = AppLocalizations.of(context);
    final current = settings.locale?.languageCode ?? 'system';

    String labelFor(String code) => switch (code) {
      'fr' => t.settingsLanguageFr,
      'en' => t.settingsLanguageEn,
      _ => t.settingsLanguageSystem,
    };

    return MergeSemantics(
      child: ListTile(
        leading: const Icon(Icons.language_outlined),
        title: Text(t.settingsLanguage),
        subtitle: Text(labelFor(current)),
        trailing: PopupMenuButton<String>(
          tooltip: t.settingsLanguage,
          icon: const Icon(Icons.arrow_drop_down),
          initialValue: current,
          onSelected: (code) async {
            final loc = switch (code) {
              'fr' => const Locale('fr'),
              'en' => const Locale('en'),
              _ => null,
            };
            await settings.setLocale(loc);
            // Annonce dans la nouvelle langue choisie (utile pour TalkBack).
            final announcement = switch (code) {
              'en' => t.settingsLanguageChangedEn,
              _ => t.settingsLanguageChangedFr,
            };
            unawaited(
              SemanticsService.announce(announcement, TextDirection.ltr),
            );
          },
          itemBuilder: (_) => [
            CheckedPopupMenuItem<String>(
              value: 'system',
              checked: current == 'system',
              child: Text(t.settingsLanguageSystem),
            ),
            CheckedPopupMenuItem<String>(
              value: 'fr',
              checked: current == 'fr',
              child: Text(t.settingsLanguageFr),
            ),
            CheckedPopupMenuItem<String>(
              value: 'en',
              checked: current == 'en',
              child: Text(t.settingsLanguageEn),
            ),
          ],
        ),
      ),
    );
  }
}

/// Sélecteur de thème (système / clair / sombre) en PopupMenuButton.
class _ThemeTile extends StatelessWidget {
  const _ThemeTile();

  @override
  Widget build(BuildContext context) {
    final settings = context.watch<SettingsService>();
    final t = AppLocalizations.of(context);
    final current = settings.themeMode;

    String labelFor(ThemeMode m) => switch (m) {
      ThemeMode.light => t.settingsThemeLight,
      ThemeMode.dark => t.settingsThemeDark,
      ThemeMode.system => t.settingsThemeSystem,
    };

    return MergeSemantics(
      child: ListTile(
        leading: const Icon(Icons.dark_mode_outlined),
        title: Text(t.settingsTheme),
        subtitle: Text(labelFor(current)),
        trailing: PopupMenuButton<ThemeMode>(
          tooltip: t.settingsTheme,
          icon: const Icon(Icons.arrow_drop_down),
          initialValue: current,
          onSelected: (m) async {
            await settings.setThemeMode(m);
            unawaited(
              SemanticsService.announce(labelFor(m), TextDirection.ltr),
            );
          },
          itemBuilder: (_) => [
            CheckedPopupMenuItem<ThemeMode>(
              value: ThemeMode.system,
              checked: current == ThemeMode.system,
              child: Text(t.settingsThemeSystem),
            ),
            CheckedPopupMenuItem<ThemeMode>(
              value: ThemeMode.light,
              checked: current == ThemeMode.light,
              child: Text(t.settingsThemeLight),
            ),
            CheckedPopupMenuItem<ThemeMode>(
              value: ThemeMode.dark,
              checked: current == ThemeMode.dark,
              child: Text(t.settingsThemeDark),
            ),
          ],
        ),
      ),
    );
  }
}

/// Tuile d'avertissement affichée quand l'activation de MiniLM a échoué.
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
          leading: Icon(
            Icons.warning_amber_outlined,
            color: theme.colorScheme.error,
          ),
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
      child: Semantics(
        header: true,
        child: Text(
          label.toUpperCase(),
          style: theme.textTheme.labelMedium?.copyWith(
            letterSpacing: 1.2,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
    );
  }
}

/// Section "Dictée vocale" : présence du modèle Whisper, changer/désinstaller.
class _VoiceSection extends StatelessWidget {
  const _VoiceSection();

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Consumer<VoiceService>(
      builder: (context, voice, _) {
        final model = voice.activeModel;
        if (model == null) {
          return MergeSemantics(
            child: ListTile(
              leading: const Icon(Icons.mic_none_outlined),
              title: Text(t.voiceSetupEnable),
              subtitle: Text(t.voiceSetupSubtitle),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => _openSetup(context),
            ),
          );
        }
        return Column(
          children: [
            MergeSemantics(
              child: ListTile(
                leading: const Icon(Icons.mic_outlined),
                title: Text(t.aiChatModelLoaded),
                subtitle: Text(
                  '${model.displayName}\n${_formatSize(model.sizeBytes)}',
                ),
                isThreeLine: true,
              ),
            ),
            MergeSemantics(
              child: ListTile(
                leading: const Icon(Icons.swap_horiz_outlined),
                title: Text(t.voiceSetupChooseModel),
                subtitle: Text(t.voiceSetupSelectFile),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => _openSetup(context),
              ),
            ),
            MergeSemantics(
              child: ListTile(
                leading: Icon(
                  Icons.delete_outline,
                  color: Theme.of(context).colorScheme.error,
                ),
                title: Text(
                  t.voiceSetupRemove,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
                subtitle: Text(_formatSize(model.sizeBytes)),
                onTap: () =>
                    _confirmUninstall(context, voice, model.displayName, t),
              ),
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
    AppLocalizations t,
  ) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        icon: const Icon(Icons.delete_outline),
        title: Text(t.voiceSetupRemove),
        content: Text(displayName),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: Text(t.commonCancel),
          ),
          FilledButton.tonal(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: Text(t.commonRemove),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    await voice.uninstallActiveModel();
    if (!context.mounted) return;
    if (context.mounted) context.showFloatingSnack(t.voiceSetupRemove);
  }

  static String _formatSize(int bytes) {
    final mb = (bytes / (1024 * 1024)).round();
    return '$mb Mo';
  }
}

/// Section "Exporter mes données" : portabilité Markdown.
class _ExportSection extends StatefulWidget {
  const _ExportSection();

  @override
  State<_ExportSection> createState() => _ExportSectionState();
}

class _ExportSectionState extends State<_ExportSection> {
  bool _busy = false;

  Future<void> _exportAllAsZip() async {
    if (_busy) return;
    final t = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final notesRepo = context.read<NotesRepository>();
    final foldersRepo = context.read<FoldersRepository>();
    final vault = context.read<FolderVaultService>();
    setState(() => _busy = true);
    try {
      final notes = await notesRepo.listAllAlive();
      final folders = await foldersRepo.listAll();
      final foldersById = <String, Folder>{for (final f in folders) f.id: f};

      if (notes.isEmpty) {
        if (!mounted) return;
        messenger.showFloatingSnack(t.homeNoNotes);
        return;
      }

      final result = await const NoteExportService().exportAllAsZip(
        notes: notes,
        foldersById: foldersById,
        vault: vault,
        inboxFallbackName: t.homeFolderInbox,
        // Template `{folder}` substitué dans l'isolate (intl AppLocalizations
        // n'est pas accessible cross-isolate → on passe la chaîne déjà
        // localisée avec un placeholder simple).
        vaultMentionTemplate: t.exportNoteFromVault('{folder}'),
      );
      final zipBytes = result.zipBytes;
      // F4 v1.0.3 — sous-dossier `cache/exports/` dédié, purgé au boot
      // dans main.dart. Si le process est tué pendant le sheet de
      // partage (panic, OOM kill, force stop), le finally ci-dessous
      // ne tirera pas — mais le boot suivant nettoiera le résidu.
      final tmpDir = await getTemporaryDirectory();
      final exportsDir = Directory('${tmpDir.path}/exports');
      if (!await exportsDir.exists()) {
        await exportsDir.create(recursive: true);
      }
      final ts = DateTime.now()
          .toIso8601String()
          .replaceAll(':', '-')
          .split('.')
          .first;
      final file = File('${exportsDir.path}/notes-tech-export-$ts.zip');
      await file.writeAsBytes(zipBytes, flush: true);

      if (!mounted) return;
      try {
        await Share.shareXFiles([
          XFile(file.path, mimeType: 'application/zip'),
        ], subject: t.exportShareSubject(result.exportedCount));
        if (!mounted) return;
        final message = result.skippedVaultedCount == 0
            ? t.settingsExportDone(result.exportedCount)
            : t.settingsExportDonePartial(
                result.exportedCount,
                result.skippedVaultedCount,
              );
        messenger.showFloatingSnack(
          message,
          duration: result.skippedVaultedCount == 0
              ? const Duration(seconds: 4)
              : const Duration(seconds: 6),
        );
      } finally {
        try {
          if (await file.exists()) await file.delete();
        } catch (_) {
          /* best-effort */
        }
      }
    } catch (e) {
      if (!mounted) return;
      messenger.showFloatingSnack(t.settingsExportError(e.toString()));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return MergeSemantics(
      child: ListTile(
        leading: const Icon(Icons.archive_outlined),
        title: Text(t.settingsExportAll),
        subtitle: Text(t.settingsExportSubtitle),
        trailing: _busy
            ? const SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : Tooltip(
                message: t.commonShare,
                child: const Icon(Icons.share_outlined),
              ),
        onTap: _busy ? null : _exportAllAsZip,
      ),
    );
  }
}

/// Section "Mode panique" : trigger d'effacement irréversible.
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

    setState(() => _running = true);
    final navigator = Navigator.of(context);
    unawaited(
      showDialog<void>(
        context: context,
        barrierDismissible: false,
        builder: (ctx) {
          final t = AppLocalizations.of(ctx);
          return BlockingProgressDialog(
            title: t.panicProgress,
            subtitle: t.panicProgressSubtitle,
          );
        },
      ),
    );

    try {
      await panic.trigger();
    } catch (_) {
      // best-effort
    }
    if (!mounted) return;
    navigator.pop();
    await navigator.pushAndRemoveUntil<void>(
      MaterialPageRoute<void>(builder: (_) => const PanicCompleteScreen()),
      (_) => false,
    );
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    return MergeSemantics(
      child: ListTile(
        leading: Icon(Icons.local_fire_department_outlined, color: cs.error),
        title: Text(
          t.settingsPanic,
          style: TextStyle(color: cs.error, fontWeight: FontWeight.w600),
        ),
        subtitle: Text(t.settingsPanicSubtitle),
        trailing: _running
            ? const SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : Icon(Icons.chevron_right, color: cs.error),
        onTap: _running ? null : _trigger,
      ),
    );
  }
}

// `_PanicProgressDialog` retiré v1.0 : remplacé par `BlockingProgressDialog`
// (cf. `lib/ui/widgets/blocking_progress_dialog.dart`).

/// Tuile « Verrouillage auto des coffres » dans la section Sécurité.
class _VaultAutoLockTile extends StatelessWidget {
  const _VaultAutoLockTile();

  static const _options = <int>[0, 5, 15, 30, 60];

  String _labelFor(AppLocalizations t, int minutes) => minutes == 0
      ? t.settingsVaultAutoLockNever
      : t.settingsVaultAutoLockMinutes(minutes);

  Future<void> _showPicker(
    BuildContext context,
    SettingsService settings,
    FolderVaultService vault,
    AppLocalizations t,
  ) async {
    final current = settings.vaultAutoLockMinutes;
    final selected = await showDialog<int>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: Text(t.settingsVaultAutoLock),
        children: _options
            .map(
              (minutes) => ListTile(
                leading: Icon(
                  current == minutes
                      ? Icons.radio_button_checked
                      : Icons.radio_button_unchecked,
                ),
                title: Text(_labelFor(t, minutes)),
                onTap: () => Navigator.of(ctx).pop(minutes),
              ),
            )
            .toList(),
      ),
    );
    if (selected == null) return;
    await settings.setVaultAutoLockMinutes(selected);
    vault.setAutoLockAfter(Duration(minutes: selected));
  }

  @override
  Widget build(BuildContext context) {
    final settings = context.watch<SettingsService>();
    final vault = context.read<FolderVaultService>();
    final t = AppLocalizations.of(context);
    return MergeSemantics(
      child: ListTile(
        leading: const Icon(Icons.lock_clock_outlined),
        title: Text(t.settingsVaultAutoLock),
        subtitle: Text(_labelFor(t, settings.vaultAutoLockMinutes)),
        trailing: const Icon(Icons.chevron_right),
        onTap: () => _showPicker(context, settings, vault, t),
      ),
    );
  }
}
