import 'package:file_picker/file_picker.dart';
import 'package:files_tech_voice/files_tech_voice.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../services/voice/voice_service.dart';

/// Écran d'onboarding de la transcription vocale.
///
/// **Pourquoi ce flux manuel** ? Notes Tech est offline-by-design : la
/// permission INTERNET est retirée du manifest. L'utilisateur télécharge
/// donc le modèle Whisper lui-même depuis HuggingFace, le transfère sur
/// son téléphone (USB / Drive / WhatsApp), et l'importe via le sélecteur
/// de fichiers système. Notes Tech vérifie le SHA-256 et stocke le
/// fichier dans sa zone privée.
///
/// L'écran présente :
/// 1. Un en-tête expliquant l'engagement offline.
/// 2. La liste des modèles supportés (avec lien copiable vers HuggingFace).
/// 3. Un guide pas-à-pas (3 étapes claires).
/// 4. Un bouton "Choisir un fichier .bin" qui ouvre le file picker.
/// 5. Une barre de progression pendant l'import + vérification.
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

    // Le picker file_picker (déjà utilisé par Notes Tech pour Gemma)
    // accepte n'importe quel fichier. On filtre côté UX (.bin attendu)
    // mais la garantie d'intégrité reste le SHA-256.
    final result = await FilePicker.platform.pickFiles(
      type: FileType.any,
      withData: false, // on travaille en streaming, pas tout en RAM
    );
    if (!mounted || result == null || result.files.isEmpty) return;
    final path = result.files.single.path;
    if (path == null) {
      _showSnack('Chemin du fichier indisponible. Réessayez.');
      return;
    }

    setState(() {
      _busy = true;
      _progress = 0;
      _phaseLabel = 'Vérification…';
    });

    try {
      await context.read<VoiceService>().importModel(
            sourcePath: path,
            model: _selectedModel,
            onProgress: (p) {
              if (!mounted) return;
              setState(() {
                _progress = p.fraction;
                _phaseLabel = p.phase == 'copying'
                    ? 'Copie sécurisée…'
                    : 'Vérification SHA-256…';
              });
            },
          );
      if (!mounted) return;
      Navigator.of(context).pop();
      _showSnack('Transcription vocale activée ✓');
    } on SttModelChecksumMismatch catch (e) {
      if (!mounted) return;
      await _showError(
        'Le fichier ne correspond pas au modèle attendu.\n\n'
        '${e.message}\n\n'
        'Vérifiez la source officielle : '
        'huggingface.co/ggerganov/whisper.cpp',
      );
    } on SttException catch (e) {
      if (!mounted) return;
      await _showError(e.message);
    } catch (e) {
      if (!mounted) return;
      await _showError('Erreur inattendue : $e');
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
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating),
    );
  }

  Future<void> _showError(String msg) async {
    if (!mounted) return;
    return showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        icon: const Icon(Icons.error_outline),
        title: const Text('Import impossible'),
        content: Text(msg),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('Compris'),
          ),
        ],
      ),
    );
  }

  Future<void> _copyUrl(String url) async {
    await Clipboard.setData(ClipboardData(text: url));
    _showSnack('Lien copié.');
  }

  /// Délègue le téléchargement au navigateur système (Chrome / Brave / etc.).
  /// Notes Tech reste sans permission INTERNET — c'est l'OS qui ouvre le
  /// browser via un Intent, et c'est le browser qui télécharge le `.bin`
  /// dans `/Downloads/` du téléphone. L'utilisateur revient ensuite ici
  /// pour l'importer.
  Future<void> _openInBrowser() async {
    final uri = Uri.parse(_selectedModel.url);
    try {
      final ok = await launchUrl(
        uri,
        mode: LaunchMode.externalApplication,
      );
      if (!mounted) return;
      if (!ok) {
        await _showError(
          'Aucun navigateur n\'a pu ouvrir le lien. '
          'Copiez l\'URL et collez-la dans votre navigateur préféré.',
        );
      }
    } catch (e) {
      if (!mounted) return;
      await _showError(
        'Impossible d\'ouvrir le navigateur : $e\n\n'
        'Copiez l\'URL et collez-la manuellement.',
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Activer la voix')),
      body: AbsorbPointer(
        absorbing: _busy,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 100),
          children: [
            _OfflineEngagementBanner(),
            const SizedBox(height: 24),
            const Text(
              'Comment activer la transcription vocale',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 12),
            const _StepTile(
              number: '1',
              title: 'Choisir un modèle',
              text: 'Whisper Base (57 Mo) est recommandé. '
                  'Whisper Tiny (32 Mo) est plus rapide mais moins précis.',
            ),
            _ModelChoice(
              selected: _selectedModel,
              onChanged: (m) => setState(() => _selectedModel = m),
            ),
            const SizedBox(height: 16),
            _StepTile(
              number: '2',
              title: 'Télécharger le fichier .bin',
              text: 'Le navigateur de votre téléphone s\'occupe du '
                  'téléchargement — Notes Tech n\'a pas la permission '
                  'd\'accéder à Internet. Source officielle (signée par '
                  'l\'auteur de whisper.cpp) :',
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
                label: const Text('Télécharger sur ce téléphone'),
                style: FilledButton.styleFrom(
                  minimumSize: const Size.fromHeight(48),
                ),
              ),
            ),
            const SizedBox(height: 16),
            const _StepTile(
              number: '3',
              title: 'Importer le fichier dans Notes Tech',
              text:
                  'Une fois le téléchargement terminé (notification du '
                  'navigateur), revenez ici et appuyez sur le bouton '
                  'ci-dessous pour sélectionner le fichier.',
            ),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: _busy ? null : _pickAndImport,
              icon: const Icon(Icons.file_upload_outlined),
              label: Text(
                _busy ? 'Import en cours…' : 'Sélectionner le fichier .bin',
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
  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
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
              'Notes Tech ne se connecte jamais à Internet. '
              'Vous téléchargez le modèle Whisper vous-même, '
              'l\'app le vérifie cryptographiquement (SHA-256) '
              'et le stocke dans sa zone privée.',
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
                Text(
                  title,
                  style: const TextStyle(fontWeight: FontWeight.w600),
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
          IconButton(
            tooltip: 'Copier le lien',
            icon: const Icon(Icons.copy_outlined, size: 20),
            onPressed: onCopy,
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
    return Text.rich(
      TextSpan(
        style: TextStyle(
          fontSize: 12,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
          height: 1.5,
        ),
        children: const [
          TextSpan(
            text: '🔒 Garanties.  ',
            style: TextStyle(fontWeight: FontWeight.w600),
          ),
          TextSpan(
            text:
                'Le fichier modèle est vérifié par empreinte cryptographique '
                '(SHA-256) avant d\'être accepté — un fichier altéré est '
                'détecté et rejeté. L\'audio capté pour la transcription '
                'reste dans la mémoire du téléphone, n\'est jamais envoyé '
                'sur Internet et est effacé immédiatement après la '
                'reconnaissance.',
          ),
        ],
      ),
    );
  }
}
