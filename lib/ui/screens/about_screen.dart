/// Écran "À propos" — promesse confidentialité + licences + notice d'emploi.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../core/constants.dart';
import '../../l10n/app_localizations.dart';
import '../../services/embedding/embedding_provider.dart';
import '../../services/indexing_service.dart';
import 'mentions_legales_screen.dart';

class AboutScreen extends StatelessWidget {
  const AboutScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final t = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(t.aboutTitle)),
      body: ListView(
        // `AlwaysScrollableScrollPhysics` : feedback de défilement
        // garanti même si le contenu fait pile la hauteur de l'écran.
        // Évite le bug perçu « on ne peut pas scroller » signalé sur S24.
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 40),
        children: [
          _AppHeader(theme: theme),
          const SizedBox(height: 28),

          _SectionTitle(t.aboutSectionPrivacy),
          _Badge(icon: Icons.cloud_off_outlined, text: t.aboutPrivacy1),
          _Badge(icon: Icons.account_circle_outlined, text: t.aboutPrivacy2),
          _Badge(icon: Icons.bar_chart_outlined, text: t.aboutPrivacy3),
          _Badge(icon: Icons.lock_outline, text: t.aboutPrivacy4),
          _Badge(icon: Icons.visibility_off_outlined, text: t.aboutPrivacy5),

          const SizedBox(height: 28),
          _SectionTitle(t.aboutSectionSearch),
          const _SearchEngineInfo(),

          const SizedBox(height: 28),
          _SectionTitle(t.aboutSectionQa),
          _Badge(icon: Icons.psychology_outlined, text: t.aboutQa1),
          _Badge(icon: Icons.shield_outlined, text: t.aboutQa2),
          _Badge(icon: Icons.flash_on_outlined, text: t.aboutQa3),

          const SizedBox(height: 28),
          _SectionTitle(t.aboutSectionVoice),
          _Badge(icon: Icons.mic_none_outlined, text: t.aboutVoice1),
          _Badge(icon: Icons.shield_outlined, text: t.aboutVoice2),
          _Badge(icon: Icons.delete_sweep_outlined, text: t.aboutVoice3),
          _Badge(icon: Icons.memory_outlined, text: t.aboutVoice4),

          const SizedBox(height: 12),
          _NoticeBox(
            title: t.aboutNoticeTitle,
            children: [
              Text(t.aboutNoticeStep1, style: const TextStyle(height: 1.5)),
              const SizedBox(height: 6),
              Text(t.aboutNoticeStep2, style: const TextStyle(height: 1.5)),
              const SizedBox(height: 6),
              Text(t.aboutNoticeStep3, style: const TextStyle(height: 1.5)),
              const SizedBox(height: 6),
              Text(t.aboutNoticeStep4, style: const TextStyle(height: 1.5)),
              const SizedBox(height: 6),
              Text(t.aboutNoticeStep5, style: const TextStyle(height: 1.5)),
            ],
          ),

          const SizedBox(height: 28),
          _SectionTitle(t.aboutSectionLicenses),
          const SizedBox(height: 4),
          _LinkTile(
            icon: Icons.code,
            title: t.aboutLinkRepo,
            subtitle: 'github.com/gitubpatrice/notes_tech',
            url: 'https://github.com/gitubpatrice/notes_tech',
          ),
          _LinkTile(
            icon: Icons.code,
            title: t.aboutLinkVoice,
            subtitle: 'github.com/gitubpatrice/files_tech_voice',
            url: 'https://github.com/gitubpatrice/files_tech_voice',
          ),
          _LinkTile(
            icon: Icons.code,
            title: t.aboutLinkWhisper,
            subtitle: 'huggingface.co/ggerganov/whisper.cpp',
            url: 'https://huggingface.co/ggerganov/whisper.cpp',
          ),
          _LinkTile(
            icon: Icons.code,
            title: t.aboutLinkGemma,
            subtitle: 'kaggle.com/models/google/gemma-3 → tfLite',
            url: 'https://www.kaggle.com/models/google/gemma-3/tfLite',
          ),
          const SizedBox(height: 8),
          _Badge(icon: Icons.gavel_outlined, text: t.aboutLicense),
          _Badge(icon: Icons.attach_money_outlined, text: t.aboutFree),

          const SizedBox(height: 28),
          _SectionTitle(t.aboutSectionContact),
          const _LinkTile(
            icon: Icons.public,
            title: 'Files Tech',
            subtitle: 'files-tech.com',
            url: 'https://www.files-tech.com',
          ),
          _LinkTile(
            icon: Icons.mail_outline,
            title: 'contact@files-tech.com',
            subtitle: t.aboutContactQuestions,
            url: 'mailto:contact@files-tech.com',
          ),
          const SizedBox(height: 8),
          Text(AppConstants.appAuthor, style: theme.textTheme.bodyMedium),

          const SizedBox(height: 28),
          _SectionTitle(t.aboutSectionLegal),
          // Page dédiée au lieu d'un long bloc inline : la liste des
          // mentions est volumineuse (éditeur, hébergement, données,
          // permissions, droits, licence) et alourdissait l'AboutScreen
          // au point que les utilisateurs sur petits écrans ne voyaient
          // pas les sections du bas.
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.gavel_outlined),
            title: Text(t.aboutLegalLink),
            subtitle: Text(
              t.aboutLegalSubtitle,
              style: const TextStyle(fontSize: 12),
            ),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => Navigator.of(context).push(
              MaterialPageRoute<void>(
                builder: (_) => const MentionsLegalesScreen(),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Sous-widgets
// ---------------------------------------------------------------------------

class _AppHeader extends StatelessWidget {
  const _AppHeader({required this.theme});
  final ThemeData theme;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Row(
      children: [
        ExcludeSemantics(
          child: Container(
            width: 56,
            height: 56,
            decoration: BoxDecoration(
              color: theme.colorScheme.primary,
              borderRadius: BorderRadius.circular(14),
            ),
            child: const Icon(
              Icons.note_alt_outlined,
              color: Colors.white,
              size: 32,
            ),
          ),
        ),
        const SizedBox(width: 16),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(AppConstants.appName, style: theme.textTheme.titleLarge),
              Text(
                t.aboutVersion(AppConstants.appVersion),
                style: theme.textTheme.bodySmall,
              ),
              const SizedBox(height: 4),
              Text(
                t.aboutTagline,
                style: theme.textTheme.bodySmall?.copyWith(
                  fontStyle: FontStyle.italic,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.label);
  final String label;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Semantics(
        header: true,
        child: Text(label, style: Theme.of(context).textTheme.titleMedium),
      ),
    );
  }
}

class _SearchEngineInfo extends StatelessWidget {
  const _SearchEngineInfo();

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final embedderNotifier =
        context.watch<ValueNotifier<EmbeddingProvider>>();
    final indexing = context.read<IndexingService>();
    final embedder = embedderNotifier.value;
    final isMiniLm = embedder.modelId.startsWith('minilm');
    final label = isMiniLm
        ? t.aboutSearchEngineMiniLm
        : t.aboutSearchEngineLocal;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _Badge(
          icon: isMiniLm ? Icons.auto_awesome : Icons.functions,
          text: label,
        ),
        _Badge(
          icon: Icons.straighten,
          text: t.aboutSearchDim(embedder.dim),
        ),
        FutureBuilder<int>(
          future: indexing.indexedCount(),
          builder: (_, snap) {
            final n = snap.data ?? 0;
            return _Badge(
              icon: Icons.inventory_2_outlined,
              text: t.aboutSearchIndexed(n),
            );
          },
        ),
      ],
    );
  }
}

class _Badge extends StatelessWidget {
  const _Badge({required this.icon, required this.text});
  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ExcludeSemantics(
            child: Padding(
              padding: const EdgeInsets.only(top: 2),
              child: Icon(icon, size: 18, color: theme.iconTheme.color),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(child: Text(text, style: theme.textTheme.bodyMedium)),
        ],
      ),
    );
  }
}

class _NoticeBox extends StatelessWidget {
  const _NoticeBox({required this.title, required this.children});
  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 4),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(10),
      ),
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
          const SizedBox(height: 10),
          ...children,
        ],
      ),
    );
  }
}

class _LinkTile extends StatelessWidget {
  const _LinkTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.url,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final String url;

  Future<void> _open(BuildContext context) async {
    final messenger = ScaffoldMessenger.of(context);
    final copiedMsg = AppLocalizations.of(context).aboutLinkCopied;
    final uri = Uri.parse(url);
    final ok = await launchUrl(uri, mode: LaunchMode.externalApplication);
    if (ok) return;
    // Fallback : navigateur indisponible → copie l'URL dans le presse-papiers.
    // On capture `messenger` AVANT l'await pour ne pas dépendre du
    // BuildContext après la frontière asynchrone.
    await Clipboard.setData(ClipboardData(text: url));
    messenger.showSnackBar(
      SnackBar(
        content: Text(copiedMsg),

      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      visualDensity: const VisualDensity(vertical: -2),
      leading: Icon(icon),
      title: Text(title),
      subtitle: Text(subtitle, style: const TextStyle(fontSize: 12)),
      trailing: const Icon(Icons.open_in_new, size: 18),
      onTap: () => _open(context),
    );
  }
}
