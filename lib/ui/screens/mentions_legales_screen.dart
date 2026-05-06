/// Écran « Mentions légales » : informations RGPD, données collectées,
/// permissions Android, droits utilisateur. Sortie de l'AboutScreen pour
/// éviter une page tentaculaire (le ListView fonctionnait mais ~12 sections
/// rendaient les mentions légales hors de portée du défilement perçu).
///
/// Page courte, autonome, accessible via une ListTile dans AboutScreen.
library;

import 'package:flutter/material.dart';

class MentionsLegalesScreen extends StatelessWidget {
  const MentionsLegalesScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final body = theme.textTheme.bodyMedium?.copyWith(height: 1.55);
    return Scaffold(
      appBar: AppBar(title: const Text('Mentions légales')),
      // `AlwaysScrollableScrollPhysics` : garantit le pull-to-overscroll
      // visible même si le contenu fait pile la hauteur de l'écran. Évite
      // l'impression « on ne peut pas scroller » sur appareils tactiles
      // où la zone de défilement n'a pas de feedback initial.
      body: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 40),
        children: [
          const _SectionTitle('Éditeur'),
          Text(
            'Files Tech / Patrice Haltaya — éditeur indépendant.\n'
            'Site officiel : https://www.files-tech.com\n'
            'Contact : contact@files-tech.com',
            style: body,
          ),
          const SizedBox(height: 20),

          const _SectionTitle('Hébergement'),
          Text(
            'Aucun hébergement. Notes Tech ne possède pas de serveur. '
            'L\'application n\'a pas la permission Android d\'accéder à '
            'Internet (déclaration tools:node="remove" dans le manifeste).',
            style: body,
          ),
          const SizedBox(height: 20),

          const _SectionTitle('Données collectées'),
          Text(
            'Aucune. Notes Tech ne collecte rien à distance — ni statistique '
            'd\'usage, ni identifiant publicitaire, ni adresse IP, ni crash '
            'reporter tiers (Firebase, Sentry, Crashlytics : absents).',
            style: body,
          ),
          const SizedBox(height: 20),

          const _SectionTitle('Données stockées localement'),
          Text(
            'Vos titres et contenus de notes, vos paramètres, vos modèles IA '
            'importés. Tout reste dans la zone privée de l\'application '
            '(/data/data/com.filestech.notes_tech), inaccessible aux autres '
            'applications par les garanties d\'isolation Android.\n\n'
            'La base de notes est chiffrée AES-256 (SQLCipher) avec une clé '
            'scellée par l\'Android Keystore — la désinstallation efface '
            'cette clé et rend la base illisible à jamais.',
            style: body,
          ),
          const SizedBox(height: 20),

          const _SectionTitle('Modèles d\'intelligence artificielle'),
          Text(
            'Vous les téléchargez vous-même depuis les sources officielles :\n'
            '• Gemma 3 1B int4 — Google Kaggle\n'
            '• Whisper Base/Tiny — HuggingFace ggerganov/whisper.cpp\n'
            '• MiniLM-L6-v2 — bundlé dans l\'application\n\n'
            'Notes Tech vérifie l\'empreinte cryptographique SHA-256 de '
            'chaque modèle avant chargement. Aucun modèle n\'est envoyé '
            'à l\'éditeur ni à un service tiers.',
            style: body,
          ),
          const SizedBox(height: 20),

          const _SectionTitle('Permissions Android'),
          Text(
            '• RECORD_AUDIO — demandée au premier appui sur le bouton micro '
            'de la dictée vocale. Refusable, peut être révoquée à tout '
            'moment dans les paramètres système.\n\n'
            'Aucune autre permission. Notamment :\n'
            '• Pas de INTERNET\n'
            '• Pas de ACCESS_NETWORK_STATE\n'
            '• Pas de FOREGROUND_SERVICE\n'
            '• Pas de POST_NOTIFICATIONS\n'
            '• Pas de READ_EXTERNAL_STORAGE (utilisation du Storage Access '
            'Framework pour l\'import de fichiers)',
            style: body,
          ),
          const SizedBox(height: 20),

          const _SectionTitle('Vos droits'),
          Text(
            'Vous gardez la pleine maîtrise de vos données.\n\n'
            '• Droit d\'accès : vos notes sont sur votre téléphone, '
            'consultables à tout moment dans l\'app.\n'
            '• Droit à l\'effacement : désinstallez l\'application. La clé '
            'Keystore est détruite, les notes deviennent illisibles, plus '
            'rien ne subsiste de votre passage.\n'
            '• Droit à la portabilité : export Markdown disponible dans '
            'Réglages → Exporter mes données. Format compatible Obsidian, '
            'Logseq, Bear (frontmatter YAML standard).\n'
            '• Droit à la rectification : édition libre dans l\'app.',
            style: body,
          ),
          const SizedBox(height: 20),

          const _SectionTitle('Licence'),
          Text(
            'Notes Tech est publié sous Apache License 2.0. Le code source '
            'intégral est consultable, modifiable et redistribuable selon '
            'les termes de cette licence :\n\n'
            'https://github.com/gitubpatrice/notes_tech\n\n'
            'Le module sibling files_tech_voice (dictée Whisper) est '
            'également sous Apache 2.0 :\n'
            'https://github.com/gitubpatrice/files_tech_voice',
            style: body,
          ),
          const SizedBox(height: 20),

          const _SectionTitle('Contact'),
          Text(
            'Pour toute question, suggestion, retour de bug ou demande '
            'liée à vos données :\n\n'
            'contact@files-tech.com',
            style: body,
          ),
        ],
      ),
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
      child: Text(
        label,
        style: Theme.of(context).textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.w600,
            ),
      ),
    );
  }
}
