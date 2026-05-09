/// Dialogue de confirmation avant déclenchement du mode panique.
///
/// Garde-fou critique : l'utilisateur doit **taper littéralement** le
/// mot-clé localisé (« EFFACER » en FR, « WIPE » en EN) pour activer le
/// bouton de validation. Sans ça, un tap par erreur dans Settings
/// entraînerait une perte définitive de toutes les notes — risque
/// inacceptable.
///
/// Pourquoi taper plutôt qu'un hold-to-confirm ? Parce que c'est :
/// - **Plus défensif** sous contrainte physique : un attaquant qui veut
///   forcer la panique doit savoir le mot exact (improbable s'il ne
///   l'a pas vu de l'app).
/// - **Plus accessible** : un utilisateur tremblant ou pressé se rend
///   compte qu'il faut un acte délibéré, pas un swipe inattentif.
library;

import 'package:flutter/material.dart';

import '../../l10n/app_localizations.dart';

/// Affiche le dialogue. Retourne `true` si l'utilisateur a tapé la
/// phrase exacte ET validé. `false` ou `null` sinon — dans les deux cas
/// le caller ne déclenche PAS la panique.
Future<bool?> confirmPanicDialog(BuildContext context) {
  return showDialog<bool>(
    context: context,
    barrierDismissible: false, // ferme uniquement par les boutons
    builder: (ctx) => const _PanicDialog(),
  );
}

class _PanicDialog extends StatefulWidget {
  const _PanicDialog();

  @override
  State<_PanicDialog> createState() => _PanicDialogState();
}

class _PanicDialogState extends State<_PanicDialog> {
  final _controller = TextEditingController();
  bool _canConfirm = false;
  String _keyword = '';

  @override
  void initState() {
    super.initState();
    _controller.addListener(_onChanged);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Le keyword dépend de la locale : on le récupère ici (context prêt).
    _keyword = AppLocalizations.of(context).panicConfirmKeyword;
    _onChanged();
  }

  void _onChanged() {
    // Comparaison case-insensitive : `textCapitalization.characters`
    // ne s'applique pas aux claviers physiques ni à certains IME
    // tiers, et l'utilisateur sous stress peut taper le mot sans
    // forcer la majuscule. Le geste reste délibéré (mot exact tapé).
    final ok = _controller.text.trim().toUpperCase() == _keyword.toUpperCase();
    if (ok != _canConfirm) setState(() => _canConfirm = ok);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    return AlertDialog(
      icon: ExcludeSemantics(
        child: Icon(Icons.warning_amber_outlined, color: cs.error, size: 40),
      ),
      title: Text(t.panicConfirmTitle),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            t.panicConfirmDestroyIntro,
            style: TextStyle(color: cs.onSurface, height: 1.5),
          ),
          const SizedBox(height: 12),
          _Item(t.panicConfirmItem1),
          _Item(t.panicConfirmItem2),
          _Item(t.panicConfirmItem3),
          const SizedBox(height: 16),
          Text(
            t.panicConfirmIrreversible,
            style: TextStyle(color: cs.error, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 20),
          Text(
            t.panicConfirmTypePrompt(_keyword),
            // Audit a11y P1 : helper >=14sp pour rester lisible.
            style: const TextStyle(fontSize: 14),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _controller,
            autofocus: true,
            textCapitalization: TextCapitalization.characters,
            decoration: InputDecoration(
              border: const OutlineInputBorder(),
              labelText: t.panicConfirmFieldLabel,
              hintText: _keyword,
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: Text(t.commonCancel),
        ),
        FilledButton.icon(
          onPressed: _canConfirm ? () => Navigator.of(context).pop(true) : null,
          style: FilledButton.styleFrom(
            backgroundColor: _canConfirm ? cs.error : null,
          ),
          icon: const Icon(Icons.local_fire_department_outlined),
          label: Text(t.panicConfirmYes),
        ),
      ],
    );
  }
}

class _Item extends StatelessWidget {
  const _Item(this.label);
  final String label;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const ExcludeSemantics(
            child: Padding(
              padding: EdgeInsets.only(top: 4, right: 8),
              child: Icon(Icons.fiber_manual_record, size: 8),
            ),
          ),
          Expanded(child: Text(label, style: const TextStyle(height: 1.4))),
        ],
      ),
    );
  }
}
