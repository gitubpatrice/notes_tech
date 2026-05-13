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
import 'package:flutter/semantics.dart';
import 'package:flutter/services.dart';

import '../../l10n/app_localizations.dart';

class PanicCompleteScreen extends StatefulWidget {
  const PanicCompleteScreen({super.key});

  @override
  State<PanicCompleteScreen> createState() => _PanicCompleteScreenState();
}

class _PanicCompleteScreenState extends State<PanicCompleteScreen> {
  bool _announced = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_announced) return;
    _announced = true;
    // SemanticsService.announce nécessite un context localisé : on attend
    // didChangeDependencies pour avoir AppLocalizations disponible.
    final msg = AppLocalizations.of(context).panicAnnounceDone;
    // ignore: deprecated_member_use
    SemanticsService.announce(msg, TextDirection.ltr);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final t = AppLocalizations.of(context);
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
                ExcludeSemantics(
                  child: Icon(
                    Icons.check_circle_outline,
                    size: 96,
                    color: theme.colorScheme.primary,
                  ),
                ),
                const SizedBox(height: 24),
                Semantics(
                  header: true,
                  child: Text(
                    t.panicCompleteTitle,
                    textAlign: TextAlign.center,
                    style: theme.textTheme.headlineSmall,
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  t.panicCompleteBody,
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodyMedium?.copyWith(height: 1.55),
                ),
                const SizedBox(height: 32),
                _Bullet(icon: Icons.lock_outline, text: t.panicCompleteBullet1),
                _Bullet(
                  icon: Icons.delete_forever_outlined,
                  text: t.panicCompleteBullet2,
                ),
                _Bullet(
                  icon: Icons.psychology_outlined,
                  text: t.panicCompleteBullet3,
                ),
                _Bullet(
                  icon: Icons.settings_backup_restore,
                  text: t.panicCompleteBullet4,
                ),
                const Spacer(),
                FilledButton.icon(
                  onPressed: () => SystemNavigator.pop(),
                  icon: const Icon(Icons.exit_to_app),
                  label: Text(t.panicCompleteClose),
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(52),
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  t.panicCompleteFooter,
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
          ExcludeSemantics(
            child: Icon(
              icon,
              size: 20,
              color: Theme.of(context).colorScheme.primary,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(child: Text(text)),
        ],
      ),
    );
  }
}
