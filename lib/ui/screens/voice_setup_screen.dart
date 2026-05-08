import 'package:file_picker/file_picker.dart';
import 'package:files_tech_voice/files_tech_voice.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../l10n/app_localizations.dart';
import '../../services/voice/voice_service.dart';
import '../../utils/snackbar_ext.dart';

/// Écran d'onboarding de la transcription vocale.
///
/// **Pourquoi ce flux manuel** ? Notes Tech est offline-by-design : la
/// permission INTERNET est retirée du manifest. L'utilisateur télécharge
/// donc le modèle Whisper lui-même depuis HuggingFace, le transfère sur
/// son téléphone (USB / Drive / WhatsApp), et l'importe via le sélecteur
/// de fichiers système. Notes Tech vérifie le SHA-256 et stocke le
/// fichier dans sa zone privée.
class VoiceSetupScreen extends StatefulWidget {
  const VoiceSetupScreen({super.key});

  @override
  State<VoiceSetupScreen> createState() => _VoiceSetupScreenState();
}

class _VoiceSetupScreenState extends State<VoiceSetupScreen> {
  SttModel _selectedModel = SttModelCatalog.defaultModel;
  bool _busy = false;
  double _progress = 0;
  String? _phaseLabel;

  Future<void> _pickAndImport() async {
    if (_busy) return;
    final t = AppLocalizations.of(context);

    // Filtre `.bin` via FileType.custom — n'affiche que les fichiers
    // d'extension `.bin` dans le picker SAF Android. Si le filtre échoue
    // sur l'appareil (extensions non-standard mal gérées par certains
    // OEM), l'utilisateur peut quand même naviguer manuellement et
    // sélectionner un fichier — la garantie d'intégrité finale reste
    // la vérification SHA-256 à l'import.
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: const ['bin'],
      allowMultiple: false,
      initialDirectory: '/storage/emulated/0/Download',
      dialogTitle: t.voiceSetupPickerDialogTitle(_selectedModel.id),
      withData: false, // on travaille en streaming, pas tout en RAM
    );
    if (!mounted || result == null || result.files.isEmpty) return;
    final path = result.files.single.path;
    if (path == null) {
      _showSnack(t.voiceSetupPathUnavailable);
      return;
    }

    setState(() {
      _busy = true;
      _progress = 0;
      _phaseLabel = t.voiceSetupVerifying;
    });

    try {
      await context.read<VoiceService>().importModel(
            sourcePath: path,
            model: _selectedModel,
            onProgress: (p) {
              if (!mounted) return;
              final t2 = AppLocalizations.of(context);
              setState(() {
                _progress = p.fraction;
                _phaseLabel = p.phase == 'copying'
                    ? t2.voiceSetupCopying
                    : t2.voiceSetupVerifying;
              });
            },
          );
      if (!mounted) return;
      Navigator.of(context).pop();
      _showSnack(t.voiceSetupInstallOk(_selectedModel.displayName));
    } on SttModelChecksumMismatch catch (e) {
      if (!mounted) return;
      await _showError(t.voiceSetupChecksumMismatchBody(e.message));
    } on SttException catch (e) {
      if (!mounted) return;
      await _showError(t.voiceSetupInstallFail(e.message));
    } catch (e) {
      if (!mounted) return;
      await _showError(t.voiceSetupInstallFail('$e'));
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _progress = 0;
          _phaseLabel = null;
        });
      }
    }
  }

  void _showSnack(String msg) {
    if (!mounted) return;
    context.showFloatingSnack(msg);
  }

  Future<void> _showError(String msg) async {
    if (!mounted) return;
    final t = AppLocalizations.of(context);
    return showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        icon: const Icon(Icons.error_outline),
        title: Text(t.voiceSetupImportErrorTitle),
        content: Text(msg),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: Text(t.commonOk),
          ),
        ],
      ),
    );
  }

  Future<void> _copyUrl(String url) async {
    await Clipboard.setData(ClipboardData(text: url));
    if (!mounted) return;
    final t = AppLocalizations.of(context);
    _showSnack(t.voiceSetupLinkCopied);
  }

  /// Délègue le téléchargement au navigateur système (Chrome / Brave / etc.).
  Future<void> _openInBrowser() async {
    final uri = Uri.parse(_selectedModel.url);
    final t = AppLocalizations.of(context);
    try {
      final ok = await launchUrl(
        uri,
        mode: LaunchMode.externalApplication,
      );
      if (!mounted) return;
      if (!ok) {
        await _showError(t.voiceSetupBrowserOpenFailed);
      }
    } catch (e) {
      if (!mounted) return;
      await _showError(t.voiceSetupBrowserOpenError(e.toString()));
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(t.voiceSetupAppBarTitle),
      ),
      body: AbsorbPointer(
        absorbing: _busy,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 100),
          children: [
            const _OfflineEngagementBanner(),
            const SizedBox(height: 24),
            Semantics(
              header: true,
              child: Text(
                t.voiceSetupHowToTitle,
                style: const TextStyle(
                    fontSize: 18, fontWeight: FontWeight.w600),
              ),
            ),
            const SizedBox(height: 12),
            _StepTile(
              number: '1',
              title: t.voiceSetupStep1Title,
              text: t.voiceSetupStep1Text,
            ),
            _ModelChoice(
              selected: _selectedModel,
              onChanged: (m) => setState(() => _selectedModel = m),
            ),
            const SizedBox(height: 16),
            _StepTile(
              number: '2',
              title: t.voiceSetupStep2Title,
              text: t.voiceSetupStep2Text,
              extra: _UrlRow(
                url: _selectedModel.url,
                onCopy: () => _copyUrl(_selectedModel.url),
              ),
            ),
            const SizedBox(height: 12),
            Padding(
              padding: const EdgeInsets.only(left: 42),
              child: FilledButton.tonalIcon(
                onPressed: _busy ? null : _openInBrowser,
                icon: const Icon(Icons.download_outlined),
                label: Text(t.voiceSetupDownload),
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(48),
                ),
              ),
            ),
            const SizedBox(height: 16),
            _StepTile(
              number: '3',
              title: t.voiceSetupStep3Title,
              text: t.voiceSetupStep3Text,
            ),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: _busy ? null : _pickAndImport,
              icon: const Icon(Icons.file_upload_outlined),
              label: Text(
                _busy ? t.voiceSetupImportInProgress : t.voiceSetupSelectFile,
              ),
              style: FilledButton.styleFrom(
                minimumSize: const Size.fromHeight(52),
              ),
            ),
            if (_busy) ...[
              const SizedBox(height: 20),
              _ProgressBlock(progress: _progress, label: _phaseLabel),
            ],
            const SizedBox(height: 28),
            const _SecurityFooter(),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Sous-widgets
// ---------------------------------------------------------------------------

class _OfflineEngagementBanner extends StatelessWidget {
  const _OfflineEngagementBanner();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: cs.primaryContainer,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.cloud_off, color: cs.onPrimaryContainer),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              t.voiceSetupOfflineBanner,
              style: TextStyle(
                color: cs.onPrimaryContainer,
                height: 1.4,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _StepTile extends StatelessWidget {
  const _StepTile({
    required this.number,
    required this.title,
    required this.text,
    this.extra,
  });

  final String number;
  final String title;
  final String text;
  final Widget? extra;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 28,
            height: 28,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: cs.primary,
              shape: BoxShape.circle,
            ),
            child: Text(
              number,
              style: TextStyle(
                color: cs.onPrimary,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Semantics(
                  header: true,
                  child: Text(
                    title,
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                ),
                const SizedBox(height: 4),
                Text(text, style: const TextStyle(height: 1.45)),
                if (extra != null) ...[
                  const SizedBox(height: 8),
                  extra!,
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ModelChoice extends StatelessWidget {
  const _ModelChoice({required this.selected, required this.onChanged});
  final SttModel selected;
  final ValueChanged<SttModel> onChanged;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(left: 42, top: 8),
      child: RadioGroup<String>(
        groupValue: selected.id,
        onChanged: (id) {
          if (id == null) return;
          final model = SttModelCatalog.byId(id);
          if (model != null) onChanged(model);
        },
        child: Column(
          children: SttModelCatalog.all.map((m) {
            return RadioListTile<String>(
              value: m.id,
              title: Text(m.displayName),
              subtitle: Text(m.notes),
              dense: true,
              contentPadding: EdgeInsets.zero,
            );
          }).toList(),
        ),
      ),
    );
  }
}

class _UrlRow extends StatelessWidget {
  const _UrlRow({required this.url, required this.onCopy});
  final String url;
  final VoidCallback onCopy;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        children: [
          Expanded(
            child: SelectableText(
              url,
              style: const TextStyle(
                fontFamily: 'monospace',
                fontSize: 12,
                height: 1.4,
              ),
            ),
          ),
          const SizedBox(width: 8),
          Tooltip(
            message: t.voiceSetupCopyLinkTooltip,
            child: IconButton(
              tooltip: t.voiceSetupCopyLinkTooltip,
              icon: const Icon(Icons.copy_outlined, size: 20),
              onPressed: onCopy,
            ),
          ),
        ],
      ),
    );
  }
}

class _ProgressBlock extends StatelessWidget {
  const _ProgressBlock({required this.progress, this.label});
  final double progress;
  final String? label;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (label != null) ...[
          Text(label!, style: const TextStyle(fontWeight: FontWeight.w600)),
          const SizedBox(height: 8),
        ],
        ClipRRect(
          borderRadius: BorderRadius.circular(6),
          child: LinearProgressIndicator(
            value: progress > 0 ? progress : null,
            minHeight: 8,
          ),
        ),
        const SizedBox(height: 4),
        if (progress > 0)
          Text(
            '${(progress * 100).toStringAsFixed(0)} %',
            textAlign: TextAlign.right,
            style: const TextStyle(fontSize: 12),
          ),
      ],
    );
  }
}

class _SecurityFooter extends StatelessWidget {
  const _SecurityFooter();

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Text.rich(
      TextSpan(
        style: TextStyle(
          fontSize: 12,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
          height: 1.5,
        ),
        children: [
          TextSpan(
            text: '🔒 ${t.voiceSetupSecurityFooterLabel}.  ',
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
          TextSpan(text: t.voiceSetupSecurityFooterBody),
        ],
      ),
    );
  }
}
