/// Écran "À propos" — promesse confidentialité + licences + notice d'emploi.
///
/// v1.1.1 — Refonte design alignée sur PDF Tech v1.12.4 : header centré
/// logo+titre+pill version, sections regroupées (privacy card Wrap colorée,
/// features en ListTile dense Card), CircleAvatar auteur. 100 % du contenu
/// fonctionnel (5 privacy + search engine dynamique + voice notice +
/// licences + contact + legal) PRÉSERVÉ. Cohérence visuelle Files Tech.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../core/constants.dart';
import '../../l10n/app_localizations.dart';
import '../../services/embedding/embedding_provider.dart';
import '../../services/indexing_service.dart';
import '../../utils/snackbar_ext.dart';
import 'mentions_legales_screen.dart';

class AboutScreen extends StatelessWidget {
  const AboutScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final t = AppLocalizations.of(context);

    return Scaffold(
      appBar: AppBar(title: Text(t.aboutTitle)),
      body: ListView(
        // `AlwaysScrollableScrollPhysics` v1.0.6 — feedback de défilement
        // garanti même si le contenu fait pile la hauteur de l'écran.
        // Évite le bug perçu « on ne peut pas scroller » signalé sur S24.
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(16, 24, 16, 40),
        children: [
          // ── Header centré (style PDF Tech) ───────────────────────────────
          const _AppHeader(),

          const SizedBox(height: 28),

          // ── Confidentialité ──────────────────────────────────────────────
          _SectionTitle(t.aboutSectionPrivacy),
          const SizedBox(height: 8),
          _PrivacyCard(
            title: t.aboutPrivacyCardTitle,
            items: [
              (
                icon: Icons.cloud_off_outlined,
                color: const Color(0xFF43A047),
                label: t.aboutPrivacy1,
              ),
              (
                icon: Icons.account_circle_outlined,
                color: const Color(0xFF1976D2),
                label: t.aboutPrivacy2,
              ),
              (
                icon: Icons.bar_chart_outlined,
                color: const Color(0xFFFF7043),
                label: t.aboutPrivacy3,
              ),
              (
                icon: Icons.lock_outline,
                color: const Color(0xFF7B1FA2),
                label: t.aboutPrivacy4,
              ),
              (
                icon: Icons.visibility_off_outlined,
                color: const Color(0xFF00897B),
                label: t.aboutPrivacy5,
              ),
            ],
          ),

          const SizedBox(height: 24),

          // ── Moteur de recherche (dynamique) ──────────────────────────────
          _SectionTitle(t.aboutSectionSearch),
          const SizedBox(height: 8),
          const Card(
            child: Padding(
              padding: EdgeInsets.fromLTRB(14, 10, 14, 10),
              child: _SearchEngineInfo(),
            ),
          ),

          const SizedBox(height: 24),

          // ── Q&A (questions / réponses) ───────────────────────────────────
          _SectionTitle(t.aboutSectionQa),
          const SizedBox(height: 8),
          _FeatureRow(
            icon: Icons.psychology_outlined,
            color: cs.primary,
            label: t.aboutQa1,
          ),
          _FeatureRow(
            icon: Icons.shield_outlined,
            color: cs.primary,
            label: t.aboutQa2,
          ),
          _FeatureRow(
            icon: Icons.flash_on_outlined,
            color: cs.primary,
            label: t.aboutQa3,
          ),

          const SizedBox(height: 24),

          // ── Voix (dictée locale Whisper) ─────────────────────────────────
          _SectionTitle(t.aboutSectionVoice),
          const SizedBox(height: 8),
          _FeatureRow(
            icon: Icons.mic_none_outlined,
            color: cs.primary,
            label: t.aboutVoice1,
          ),
          _FeatureRow(
            icon: Icons.shield_outlined,
            color: cs.primary,
            label: t.aboutVoice2,
          ),
          _FeatureRow(
            icon: Icons.delete_sweep_outlined,
            color: cs.primary,
            label: t.aboutVoice3,
          ),
          _FeatureRow(
            icon: Icons.memory_outlined,
            color: cs.primary,
            label: t.aboutVoice4,
          ),
          const SizedBox(height: 12),
          _NoticeBox(
            title: t.aboutNoticeTitle,
            steps: [
              t.aboutNoticeStep1,
              t.aboutNoticeStep2,
              t.aboutNoticeStep3,
              t.aboutNoticeStep4,
              t.aboutNoticeStep5,
            ],
          ),

          const SizedBox(height: 24),

          // ── Licences ─────────────────────────────────────────────────────
          _SectionTitle(t.aboutSectionLicenses),
          const SizedBox(height: 8),
          Card(
            child: Column(
              children: [
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
              ],
            ),
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _Badge(
                icon: Icons.gavel_outlined,
                color: const Color(0xFF546E7A),
                label: t.aboutLicense,
              ),
              _Badge(
                icon: Icons.attach_money_outlined,
                color: const Color(0xFF43A047),
                label: t.aboutFree,
              ),
            ],
          ),

          const SizedBox(height: 24),

          // ── Contact ──────────────────────────────────────────────────────
          _SectionTitle(t.aboutSectionContact),
          const SizedBox(height: 8),
          const Card(
            child: Column(
              children: [
                _LinkTile(
                  icon: Icons.public,
                  title: 'Files Tech',
                  subtitle: 'files-tech.com',
                  url: 'https://www.files-tech.com',
                ),
                _LinkTile(
                  icon: Icons.mail_outline,
                  title: 'contact@files-tech.com',
                  subtitle: 'contact@files-tech.com',
                  url: 'mailto:contact@files-tech.com',
                ),
              ],
            ),
          ),
          const SizedBox(height: 12),

          // ── Auteur ───────────────────────────────────────────────────────
          Card(
            child: ListTile(
              leading: CircleAvatar(
                backgroundColor: cs.primaryContainer,
                child: Icon(Icons.person_outline, color: cs.primary),
              ),
              title: const Text(AppConstants.appAuthor),
              subtitle: Text(
                t.aboutContactQuestions,
                style: theme.textTheme.bodySmall,
              ),
            ),
          ),

          const SizedBox(height: 24),

          // ── Mentions légales (page dédiée) ───────────────────────────────
          // v1.0 — page dédiée plutôt que long bloc inline (UX small screen).
          _SectionTitle(t.aboutSectionLegal),
          const SizedBox(height: 8),
          Card(
            child: ListTile(
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
          ),
        ],
      ),
    );
  }
}

// ============================================================================
// Header app : logo 80dp centré + nom + pill version + tagline
// ============================================================================

class _AppHeader extends StatelessWidget {
  const _AppHeader();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final t = AppLocalizations.of(context);

    return Center(
      child: Column(
        children: [
          ExcludeSemantics(
            child: ClipRRect(
              borderRadius: BorderRadius.circular(20),
              child: Image.asset(
                'assets/icon/app_icon.png',
                width: 80,
                height: 80,
                // U4 v1.0.9 (porté pour la nouvelle taille 80dp) :
                // `cacheWidth` borné à 3× pour DPR ≤ 3 (S24 = 3x → 240px).
                // Sans ça, le PNG source 1024×1024 décodé prend ~12 Mo RAM.
                cacheWidth: 240,
                cacheHeight: 240,
                fit: BoxFit.cover,
              ),
            ),
          ),
          const SizedBox(height: 14),
          Text(
            AppConstants.appName,
            style: theme.textTheme.titleLarge?.copyWith(
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 4),
          // Pill version : fond bleu translucide (lisible sur dark + light)
          // au lieu de cs.primaryContainer (non défini dans Notes Tech →
          // fallback M3 trop foncé en dark, pill invisible).
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
            decoration: BoxDecoration(
              color: cs.primary.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(20),
              border: Border.all(
                color: cs.primary.withValues(alpha: 0.4),
                width: 1,
              ),
            ),
            child: Text(
              'v${AppConstants.appVersion}',
              style: TextStyle(
                color: cs.primary,
                fontWeight: FontWeight.w700,
                fontSize: 14,
              ),
            ),
          ),
          const SizedBox(height: 8),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24),
            child: Text(
              t.aboutTagline,
              textAlign: TextAlign.center,
              style: theme.textTheme.bodySmall?.copyWith(
                fontStyle: FontStyle.italic,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ============================================================================
// Titre de section
// ============================================================================

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.label);
  final String label;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(left: 2),
      child: Semantics(
        header: true,
        child: Text(
          label,
          style: theme.textTheme.titleMedium?.copyWith(
            color: theme.colorScheme.primary,
            fontWeight: FontWeight.w700,
            fontSize: 17,
            letterSpacing: 0.3,
          ),
        ),
      ),
    );
  }
}

// ============================================================================
// Privacy card — Wrap de badges colorés (style PDF Tech)
// ============================================================================

class _PrivacyCard extends StatelessWidget {
  const _PrivacyCard({required this.title, required this.items});

  final String title;
  final List<({IconData icon, Color color, String label})> items;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(
                  Icons.shield_outlined,
                  color: Color(0xFF43A047),
                  size: 18,
                ),
                const SizedBox(width: 6),
                Semantics(
                  header: true,
                  child: Text(
                    title,
                    style: TextStyle(
                      fontWeight: FontWeight.w700,
                      fontSize: 13,
                      color: theme.colorScheme.onSurface,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: items
                  .map(
                    (it) =>
                        _Badge(icon: it.icon, color: it.color, label: it.label),
                  )
                  .toList(),
            ),
          ],
        ),
      ),
    );
  }
}

// ============================================================================
// Badge pill coloré (icône + texte)
// ============================================================================

class _Badge extends StatelessWidget {
  const _Badge({required this.icon, required this.color, required this.label});
  final IconData icon;
  final Color color;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: color.withValues(alpha: 0.3)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 14, color: color),
          const SizedBox(width: 5),
          // U3 PDF Tech v1.12.4 : texte en `onSurface` (contraste WCAG AA)
          // au lieu de la `color` thématique (~2.5:1 sur color.withAlpha 0.12).
          // L'icône colorée conserve le signal visuel.
          Text(
            label,
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              color: Theme.of(context).colorScheme.onSurface,
            ),
          ),
        ],
      ),
    );
  }
}

// ============================================================================
// FeatureRow — Card + ListTile dense (icône primary + label + desc optionnel)
// ============================================================================

class _FeatureRow extends StatelessWidget {
  const _FeatureRow({
    required this.icon,
    required this.color,
    required this.label,
  });

  final IconData icon;
  final Color color;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 6),
      child: ListTile(
        dense: true,
        leading: Icon(icon, size: 20, color: color),
        title: Text(
          label,
          style: const TextStyle(fontWeight: FontWeight.w500, fontSize: 13),
        ),
      ),
    );
  }
}

// ============================================================================
// Moteur de recherche (dynamique provider) — préservé tel quel, juste enrobé
// ============================================================================

class _SearchEngineInfo extends StatelessWidget {
  const _SearchEngineInfo();

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    final embedderNotifier = context.watch<ValueNotifier<EmbeddingProvider>>();
    final indexing = context.read<IndexingService>();
    final embedder = embedderNotifier.value;
    final isMiniLm = embedder.modelId.startsWith('minilm');
    final label = isMiniLm
        ? t.aboutSearchEngineMiniLm
        : t.aboutSearchEngineLocal;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _InfoRow(
          icon: isMiniLm ? Icons.auto_awesome : Icons.functions,
          label: label,
        ),
        _InfoRow(icon: Icons.straighten, label: t.aboutSearchDim(embedder.dim)),
        FutureBuilder<int>(
          future: indexing.indexedCount(),
          builder: (_, snap) {
            final n = snap.data ?? 0;
            return _InfoRow(
              icon: Icons.inventory_2_outlined,
              label: t.aboutSearchIndexed(n),
            );
          },
        ),
      ],
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({required this.icon, required this.label});
  final IconData icon;
  final String label;

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
          Expanded(child: Text(label, style: theme.textTheme.bodyMedium)),
        ],
      ),
    );
  }
}

// ============================================================================
// Notice box (mode d'emploi voix) — bloc à fond surfaceContainerHighest
// ============================================================================

class _NoticeBox extends StatelessWidget {
  const _NoticeBox({required this.title, required this.steps});
  final String title;
  final List<String> steps;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 4),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(12),
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
          ...steps.asMap().entries.map(
            (e) => Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '${e.key + 1}. ',
                    style: TextStyle(
                      fontWeight: FontWeight.w700,
                      color: cs.primary,
                    ),
                  ),
                  Expanded(
                    child: Text(e.value, style: const TextStyle(height: 1.4)),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ============================================================================
// LinkTile — ListTile cliquable ouvrant une URL (fallback clipboard si fail)
// ============================================================================

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
    // Capture du messenger AVANT l'await pour ne pas dépendre du BuildContext
    // après la frontière asynchrone (lint `use_build_context_synchronously`).
    final messenger = ScaffoldMessenger.of(context);
    final copiedMsg = AppLocalizations.of(context).aboutLinkCopied;
    final uri = Uri.parse(url);
    final ok = await launchUrl(uri, mode: LaunchMode.externalApplication);
    if (ok) return;
    // Fallback : navigateur indisponible → copie l'URL dans le presse-papiers.
    await Clipboard.setData(ClipboardData(text: url));
    messenger.showFloatingSnack(copiedMsg);
  }

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: Icon(icon),
      title: Text(title, style: const TextStyle(fontSize: 14)),
      subtitle: Text(subtitle, style: const TextStyle(fontSize: 12)),
      trailing: const Icon(Icons.open_in_new, size: 18),
      onTap: () => _open(context),
    );
  }
}
