/// Écran post-panique : confirme à l'utilisateur que l'effacement a eu
/// lieu, propose la fermeture de l'app.
///
/// **Ne navigue pas vers le HomeScreen** : la base est détruite, la KEK
/// effacée, les services internes pointent vers du néant. Seule action
/// proposée : fermer l'application. Au prochain lancement, Notes Tech
/// repartira sur une base vierge (création d'une nouvelle KEK + DB
/// fraîche au cold start).
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class PanicCompleteScreen extends StatelessWidget {
  const PanicCompleteScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return PopScope(
      // Bloque le bouton retour : remonter dans la pile rouvrirait des
      // écrans dont l'état (notes en mémoire, listeners...) référence des
      // données déjà détruites.
      canPop: false,
      child: Scaffold(
        body: SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Spacer(),
                Icon(
                  Icons.check_circle_outline,
                  size: 96,
                  color: theme.colorScheme.primary,
                ),
                const SizedBox(height: 24),
                Text(
                  'Effacement terminé',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.headlineSmall,
                ),
                const SizedBox(height: 12),
                Text(
                  'Toutes vos notes, modèles IA et préférences ont été '
                  'détruits. La clé maître est effacée du Keystore Android : '
                  'aucune récupération n\'est possible.',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodyMedium?.copyWith(height: 1.55),
                ),
                const SizedBox(height: 32),
                const _Bullet(
                  icon: Icons.lock_outline,
                  text: 'Clé maître Keystore : détruite',
                ),
                const _Bullet(
                  icon: Icons.delete_forever_outlined,
                  text: 'Base de notes : effacée et écrasée',
                ),
                const _Bullet(
                  icon: Icons.psychology_outlined,
                  text: 'Modèles IA (Gemma, Whisper) : désinstallés',
                ),
                const _Bullet(
                  icon: Icons.settings_backup_restore,
                  text: 'Préférences : remises à zéro',
                ),
                const Spacer(),
                FilledButton.icon(
                  onPressed: () => SystemNavigator.pop(),
                  icon: const Icon(Icons.exit_to_app),
                  label: const Text('Fermer l\'application'),
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(52),
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  'Au prochain lancement, Notes Tech repartira sur une '
                  'base vierge.',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 16),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Bullet extends StatelessWidget {
  const _Bullet({required this.icon, required this.text});
  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Icon(icon, size: 20, color: Theme.of(context).colorScheme.primary),
          const SizedBox(width: 12),
          Expanded(child: Text(text)),
        ],
      ),
    );
  }
}
