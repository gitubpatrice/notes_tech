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
import 'package:url_launcher/url_launcher.dart';

import '../../core/constants.dart';
import '../../l10n/app_localizations.dart';
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
      // `SafeArea` — même idiome que home / search / trash / éditeur. Sans lui,
      // `targetSdk 36` impose l'edge-to-edge : la liste s'étend SOUS la barre de
      // navigation et sa fin reste inatteignable. Le correctif v1.0.6 ci-dessous
      // ne traitait que le FEEDBACK de défilement, pas le contenu masqué — deux
      // symptômes voisins, deux causes distinctes.
      body: SafeArea(
        child: ListView(
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
            Card(
              child: Column(
                children: [
                  const _LinkTile(
                    icon: Icons.public,
                    title: 'Files Tech',
                    subtitle: 'files-tech.com',
                    url: 'https://www.files-tech.com',
                  ),
                  // Le titre portait l'adresse, le sous-titre AUSSI : la tuile
                  // affichait deux fois la même chaîne. Le titre nomme
                  // désormais l'action, comme la tuile voisine qui nomme le
                  // site et donne son domaine dessous.
                  _LinkTile(
                    icon: Icons.mail_outline,
                    title: t.aboutContactEmail,
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
          const SizedBox(height: 16),
          // Vérifier les mises à jour : Notes Tech n'a PAS la permission
          // INTERNET (retirée dans le manifeste — cf. aboutPrivacy1). On ne
          // fait donc AUCUN appel réseau depuis l'app : on délègue à l'OS
          // l'ouverture de la page GitHub releases (même pattern que le
          // téléchargement des modèles voix). La promesse zéro-Internet reste
          // vérifiable dans le manifeste.
          FilledButton.icon(
            icon: const Icon(Icons.system_update_outlined, size: 18),
            label: Text(t.aboutCheckUpdates),
            onPressed: () => _openReleases(context),
          ),
          const SizedBox(height: 6),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24),
            child: Text(
              t.aboutCheckUpdatesHint,
              textAlign: TextAlign.center,
              style: theme.textTheme.bodySmall?.copyWith(
                color: cs.onSurfaceVariant,
                fontSize: 11,
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// Ouvre la page des versions GitHub dans le navigateur système. Aucun
  /// appel réseau depuis l'app (pas de permission INTERNET) — c'est le
  /// navigateur qui charge la page. Repli presse-papiers si aucun navigateur.
  Future<void> _openReleases(BuildContext context) async {
    final messenger = ScaffoldMessenger.of(context);
    final copiedMsg = AppLocalizations.of(context).aboutLinkCopied;
    const url = 'https://github.com/gitubpatrice/notes_tech/releases/latest';
    final ok = await launchUrl(
      Uri.parse(url),
      mode: LaunchMode.externalApplication,
    );
    if (ok) return;
    await Clipboard.setData(const ClipboardData(text: url));
    messenger.showFloatingSnack(copiedMsg);
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
            // 17 -> 15 : les titres de section pesaient plus lourd que le
            // `titleMedium` du theme (16) qu'ils sont censes suivre.
            fontSize: 15,
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
          //
          // `Flexible` : sans lui, le `Row` en `MainAxisSize.min` réclame la
          // largeur intrinsèque du libellé et déborde. Constaté sur Galaxy S9
          // (360 dp, police à 1.0) : « Apache License 2.0 — code source
          // ouvert, vérifiable » débordait de 33 px, et le badge « Gratuit »
          // de 49 px. En debug ce sont des rayures jaunes ; en release le
          // texte est tronqué SANS que rien ne le signale. Ces libellés sont
          // des phrases traduites — leur longueur n'est pas bornée, aucune
          // largeur d'écran ne peut être supposée suffisante.
          Flexible(
            child: Text(
              label,
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                color: Theme.of(context).colorScheme.onSurface,
              ),
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
