/// Écran "À propos" — promesse confidentialité + licences + notice d'emploi.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../core/constants.dart';
import '../../services/embedding/embedding_provider.dart';
import '../../services/indexing_service.dart';
import 'mentions_legales_screen.dart';

class AboutScreen extends StatelessWidget {
  const AboutScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('À propos')),
      body: ListView(
        // `AlwaysScrollableScrollPhysics` : feedback de défilement
        // garanti même si le contenu fait pile la hauteur de l'écran.
        // Évite le bug perçu « on ne peut pas scroller » signalé sur S24.
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 40),
        children: [
          _AppHeader(theme: theme),
          const SizedBox(height: 28),

          const _SectionTitle('Confidentialité'),
          const _Badge(
            icon: Icons.cloud_off_outlined,
            text: 'Aucune connexion réseau — vérifiable dans le manifeste',
          ),
          const _Badge(
            icon: Icons.account_circle_outlined,
            text: 'Aucun compte, aucune inscription',
          ),
          const _Badge(
            icon: Icons.bar_chart_outlined,
            text: 'Aucun tracker, aucune publicité',
          ),
          const _Badge(
            icon: Icons.lock_outline,
            text: 'Notes chiffrées localement (SQLCipher + Android Keystore)',
          ),
          const _Badge(
            icon: Icons.visibility_off_outlined,
            text: 'Mode "masquer dans les apps récentes" disponible',
          ),

          const SizedBox(height: 28),
          const _SectionTitle('Recherche par similarité'),
          const _SearchEngineInfo(),

          const SizedBox(height: 28),
          const _SectionTitle('Q&A "Demander à mes notes"'),
          const _Badge(
            icon: Icons.psychology_outlined,
            text: 'Modèle Gemma 3 1B int4 (~530 Mo, importé manuellement)',
          ),
          const _Badge(
            icon: Icons.shield_outlined,
            text: 'Empreinte SHA-256 vérifiée à l\'import du modèle',
          ),
          const _Badge(
            icon: Icons.flash_on_outlined,
            text: 'Inférence 100 % locale, MediaPipe LLM Inference',
          ),

          const SizedBox(height: 28),
          const _SectionTitle('Dictée vocale'),
          const _Badge(
            icon: Icons.mic_none_outlined,
            text: 'Whisper on-device (whisper.cpp via files_tech_voice)',
          ),
          const _Badge(
            icon: Icons.shield_outlined,
            text: 'Modèle vérifié SHA-256 au DL et avant chaque chargement',
          ),
          const _Badge(
            icon: Icons.delete_sweep_outlined,
            text: 'Audio capturé jamais persisté (effacé après transcription)',
          ),
          const _Badge(
            icon: Icons.memory_outlined,
            text: 'Coordination RAM Gemma ↔ Whisper (anti-OOM)',
          ),

          const SizedBox(height: 12),
          const _NoticeBox(
            title: 'Notice d\'emploi — activer la dictée',
            children: [
              Text(
                '1. Réglages → Dictée vocale → Activer la dictée vocale.',
                style: TextStyle(height: 1.5),
              ),
              SizedBox(height: 6),
              Text(
                '2. Choisissez un modèle (Whisper Base 57 Mo recommandé).',
                style: TextStyle(height: 1.5),
              ),
              SizedBox(height: 6),
              Text(
                '3. Tapez "Télécharger sur ce téléphone" — le navigateur '
                'système télécharge le fichier .bin dans Téléchargements. '
                'Notes Tech reste sans permission Internet : c\'est votre '
                'navigateur qui télécharge, pas l\'app.',
                style: TextStyle(height: 1.5),
              ),
              SizedBox(height: 6),
              Text(
                '4. Tapez "Sélectionner le fichier .bin" — l\'app vérifie '
                'l\'empreinte cryptographique puis copie le modèle dans sa '
                'zone privée.',
                style: TextStyle(height: 1.5),
              ),
              SizedBox(height: 6),
              Text(
                '5. Dans une note, tapez l\'icône micro 🎤 dans la barre '
                'du haut. Parlez, puis tapez "Arrêter". Le texte transcrit '
                's\'insère au curseur.',
                style: TextStyle(height: 1.5),
              ),
            ],
          ),

          const SizedBox(height: 28),
          const _SectionTitle('Sources, licences et code ouvert'),
          const SizedBox(height: 4),
          const _LinkTile(
            icon: Icons.code,
            title: 'Notes Tech (cette app)',
            subtitle: 'github.com/gitubpatrice/notes_tech',
            url: 'https://github.com/gitubpatrice/notes_tech',
          ),
          const _LinkTile(
            icon: Icons.code,
            title: 'files_tech_voice (module Whisper STT)',
            subtitle: 'github.com/gitubpatrice/files_tech_voice',
            url: 'https://github.com/gitubpatrice/files_tech_voice',
          ),
          const _LinkTile(
            icon: Icons.code,
            title: 'Source des modèles Whisper (.bin)',
            subtitle: 'huggingface.co/ggerganov/whisper.cpp',
            url: 'https://huggingface.co/ggerganov/whisper.cpp',
          ),
          const _LinkTile(
            icon: Icons.code,
            title: 'Source du modèle Gemma 3 1B',
            subtitle: 'kaggle.com/models/google/gemma-3 → tfLite',
            url: 'https://www.kaggle.com/models/google/gemma-3/tfLite',
          ),
          const SizedBox(height: 8),
          const _Badge(
            icon: Icons.gavel_outlined,
            text: 'Apache License 2.0 — code source ouvert, vérifiable',
          ),
          const _Badge(
            icon: Icons.attach_money_outlined,
            text: 'Gratuit — pas de version premium, pas d\'abonnement',
          ),

          const SizedBox(height: 28),
          const _SectionTitle('Auteur & contact'),
          const _LinkTile(
            icon: Icons.public,
            title: 'Files Tech',
            subtitle: 'files-tech.com',
            url: 'https://www.files-tech.com',
          ),
          const _LinkTile(
            icon: Icons.mail_outline,
            title: 'contact@files-tech.com',
            subtitle: 'Questions, suggestions, retours',
            url: 'mailto:contact@files-tech.com',
          ),
          const SizedBox(height: 8),
          Text(AppConstants.appAuthor, style: theme.textTheme.bodyMedium),

          const SizedBox(height: 28),
          const _SectionTitle('Mentions légales'),
          // Page dédiée au lieu d'un long bloc inline : la liste des
          // mentions est volumineuse (éditeur, hébergement, données,
          // permissions, droits, licence) et alourdissait l'AboutScreen
          // au point que les utilisateurs sur petits écrans ne voyaient
          // pas les sections du bas.
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.gavel_outlined),
            title: const Text('Voir les mentions légales complètes'),
            subtitle: const Text(
              'Éditeur, données collectées, permissions, droits, licence',
              style: TextStyle(fontSize: 12),
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
    return Row(
      children: [
        Container(
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
        const SizedBox(width: 16),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(AppConstants.appName, style: theme.textTheme.titleLarge),
              Text(
                'Version ${AppConstants.appVersion}',
                style: theme.textTheme.bodySmall,
              ),
              const SizedBox(height: 4),
              Text(
                'Vos notes restent dans votre poche. L\'IA aussi.',
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
      child: Text(label, style: Theme.of(context).textTheme.titleMedium),
    );
  }
}

class _SearchEngineInfo extends StatelessWidget {
  const _SearchEngineInfo();

  @override
  Widget build(BuildContext context) {
    final embedderNotifier =
        context.watch<ValueNotifier<EmbeddingProvider>>();
    final indexing = context.read<IndexingService>();
    final embedder = embedderNotifier.value;
    final isMiniLm = embedder.modelId.startsWith('minilm');
    final label = isMiniLm
        ? 'Modèle MiniLM-L6-v2 (quantifié) — recherche sémantique'
        : 'Encodeur local (n-grammes + hashing trick) — chargement '
              'sémantique en arrière-plan';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _Badge(
          icon: isMiniLm ? Icons.auto_awesome : Icons.functions,
          text: label,
        ),
        _Badge(icon: Icons.straighten, text: 'Dimension : ${embedder.dim}'),
        FutureBuilder<int>(
          future: indexing.indexedCount(),
          builder: (_, snap) {
            final n = snap.data ?? 0;
            return _Badge(
              icon: Icons.inventory_2_outlined,
              text: 'Notes indexées : $n',
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
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: Icon(icon, size: 18, color: theme.iconTheme.color),
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
          Text(
            title,
            style: const TextStyle(fontWeight: FontWeight.w600),
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
    final uri = Uri.parse(url);
    final ok = await launchUrl(uri, mode: LaunchMode.externalApplication);
    if (ok) return;
    // Fallback : navigateur indisponible → copie l'URL dans le presse-papiers.
    // On capture `messenger` AVANT l'await pour ne pas dépendre du
    // BuildContext après la frontière asynchrone.
    await Clipboard.setData(ClipboardData(text: url));
    messenger.showSnackBar(
      const SnackBar(
        content: Text('Lien copié — collez-le dans votre navigateur.'),
        behavior: SnackBarBehavior.floating,
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

