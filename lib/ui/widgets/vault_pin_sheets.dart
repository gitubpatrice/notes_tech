/// Bottom sheets PIN (v0.9) — création + déverrouillage d'un coffre PIN.
///
/// UX volontairement minimaliste, calquée sur les écrans de verrouillage
/// Android : pavé numérique 0-9, dots indicator, pas de visibility toggle
/// (un PIN court visible = défense de l'épaule trop coûteuse à perdre).
///
/// Le design crypto vit dans `FolderVaultService.{createPinVault,
/// unlockWithPin}` — les sheets ne font que collecter les chiffres et
/// déléguer.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../../core/constants.dart';
import '../../data/models/folder.dart';
import '../../services/security/folder_vault_service.dart';
import 'vault_passphrase_sheets.dart';

// ─── Choix du mode (passphrase vs PIN) ───────────────────────────────

/// Sheet de choix initial : passphrase robuste, ou PIN pratique.
/// Retourne le [VaultMode] choisi, ou `null` si annulation.
Future<VaultMode?> showVaultModeChooserSheet({
  required BuildContext context,
  required String folderName,
}) {
  return showModalBottomSheet<VaultMode>(
    context: context,
    isScrollControlled: true,
    builder: (ctx) {
      final cs = Theme.of(ctx).colorScheme;
      return SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Center(
                child: Container(
                  width: 36,
                  height: 4,
                  margin: const EdgeInsets.only(bottom: 12),
                  decoration: BoxDecoration(
                    color: cs.outlineVariant,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              ),
              Row(
                children: [
                  Icon(Icons.lock_outline, color: cs.error),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      'Protéger « $folderName »',
                      style: Theme.of(ctx).textTheme.titleMedium,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              _ModeCard(
                icon: Icons.password_outlined,
                title: 'Passphrase',
                subtitle:
                    'Phrase secrète 8+ caractères. Robuste seule, '
                    'recommandé pour secret professionnel.',
                onTap: () => Navigator.of(ctx).pop(VaultMode.passphrase),
              ),
              const SizedBox(height: 12),
              _ModeCard(
                icon: Icons.dialpad_outlined,
                title: 'Code PIN (4-6 chiffres)',
                subtitle:
                    'Pratique au quotidien. Sécurité = device requis '
                    '+ auto-destruction après 5 tentatives ratées.',
                onTap: () => Navigator.of(ctx).pop(VaultMode.pin),
              ),
              const SizedBox(height: 16),
              TextButton(
                onPressed: () => Navigator.of(ctx).pop(),
                child: const Text('Annuler'),
              ),
              const SizedBox(height: 8),
            ],
          ),
        ),
      );
    },
  );
}

class _ModeCard extends StatelessWidget {
  const _ModeCard({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });
  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          border: Border.all(color: cs.outlineVariant),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, color: cs.primary),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: Theme.of(context).textTheme.titleSmall,
                  ),
                  const SizedBox(height: 4),
                  Text(
                    subtitle,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: cs.onSurfaceVariant,
                          height: 1.3,
                        ),
                  ),
                ],
              ),
            ),
            Icon(Icons.chevron_right, color: cs.outline),
          ],
        ),
      ),
    );
  }
}

// ─── Création PIN (saisie 2x) ────────────────────────────────────────

/// Sheet création PIN : saisie + confirmation. Retourne le PIN validé
/// (4-6 chiffres identiques aux deux étapes), ou `null` si annulation.
Future<String?> showCreatePinSheet({
  required BuildContext context,
  required String folderName,
}) {
  return showModalBottomSheet<String>(
    context: context,
    isScrollControlled: true,
    isDismissible: false,
    enableDrag: false,
    builder: (ctx) => Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(ctx).viewInsets.bottom,
      ),
      child: _CreatePinSheet(folderName: folderName),
    ),
  );
}

class _CreatePinSheet extends StatefulWidget {
  const _CreatePinSheet({required this.folderName});
  final String folderName;

  @override
  State<_CreatePinSheet> createState() => _CreatePinSheetState();
}

enum _CreateStep { first, confirm }

class _CreatePinSheetState extends State<_CreatePinSheet> {
  _CreateStep _step = _CreateStep.first;
  String _firstPin = '';
  String _entry = '';
  String? _error;

  void _onDigit(String d) {
    if (_entry.length >= AppConstants.vaultPinMaxLength) return;
    setState(() {
      _entry = '$_entry$d';
      _error = null;
    });
  }

  void _onBackspace() {
    if (_entry.isEmpty) return;
    setState(() {
      _entry = _entry.substring(0, _entry.length - 1);
      _error = null;
    });
  }

  void _onValidate() {
    if (_entry.length < AppConstants.vaultPinMinLength) {
      setState(() => _error =
          'Minimum ${AppConstants.vaultPinMinLength} chiffres.');
      return;
    }
    if (_step == _CreateStep.first) {
      setState(() {
        _firstPin = _entry;
        _entry = '';
        _step = _CreateStep.confirm;
      });
      return;
    }
    if (_entry != _firstPin) {
      setState(() {
        _entry = '';
        _error = 'Les deux PIN ne correspondent pas. Recommencez.';
        _step = _CreateStep.first;
        _firstPin = '';
      });
      return;
    }
    Navigator.of(context).pop(_firstPin);
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final title = _step == _CreateStep.first
        ? 'Choisir un code PIN pour « ${widget.folderName} »'
        : 'Confirmer le code';
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 36,
                height: 4,
                margin: const EdgeInsets.only(bottom: 12),
                decoration: BoxDecoration(
                  color: cs.outlineVariant,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            Row(
              children: [
                Icon(Icons.dialpad_outlined, color: cs.error),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    title,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            if (_step == _CreateStep.first)
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: cs.errorContainer.withValues(alpha: 0.5),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(Icons.warning_amber_outlined,
                        color: cs.onErrorContainer, size: 20),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        'PIN oublié = données perdues. 5 tentatives '
                        'ratées détruisent définitivement le coffre.',
                        style: TextStyle(
                          color: cs.onErrorContainer,
                          height: 1.4,
                          fontSize: 13,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            const SizedBox(height: 18),
            _DotsIndicator(
              filled: _entry.length,
              max: AppConstants.vaultPinMaxLength,
            ),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(
                _error!,
                textAlign: TextAlign.center,
                style: TextStyle(color: cs.error, fontSize: 13),
              ),
            ],
            const SizedBox(height: 12),
            _NumericKeypad(
              onDigit: _onDigit,
              onBackspace: _onBackspace,
              onValidate: _onValidate,
            ),
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Annuler'),
            ),
            const SizedBox(height: 4),
          ],
        ),
      ),
    );
  }
}

// ─── Déverrouillage PIN ──────────────────────────────────────────────

/// Sheet de déverrouillage PIN. Gère lui-même l'appel au service +
/// retry sur PIN incorrect. Retourne `true` si le coffre est ouvert,
/// `false` si annulation, `null` si fermé externe.
///
/// Affiche en cas d'erreur :
/// - PIN incorrect → "PIN incorrect, X tentatives restantes" en rouge
/// - 5 fails atteint → "Coffre détruit" + dismiss après info
Future<bool?> showUnlockPinSheet({
  required BuildContext context,
  required Folder folder,
}) {
  return showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    isDismissible: true,
    builder: (ctx) => Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(ctx).viewInsets.bottom,
      ),
      child: _UnlockPinSheet(folder: folder),
    ),
  );
}

class _UnlockPinSheet extends StatefulWidget {
  const _UnlockPinSheet({required this.folder});
  final Folder folder;

  @override
  State<_UnlockPinSheet> createState() => _UnlockPinSheetState();
}

class _UnlockPinSheetState extends State<_UnlockPinSheet> {
  String _entry = '';
  bool _busy = false;
  String? _error;
  bool _wiped = false;

  void _onDigit(String d) {
    if (_busy || _wiped) return;
    if (_entry.length >= AppConstants.vaultPinMaxLength) return;
    setState(() {
      _entry = '$_entry$d';
      _error = null;
    });
  }

  void _onBackspace() {
    if (_busy || _wiped || _entry.isEmpty) return;
    setState(() {
      _entry = _entry.substring(0, _entry.length - 1);
      _error = null;
    });
  }

  Future<void> _onValidate() async {
    if (_busy || _wiped) return;
    if (_entry.length < AppConstants.vaultPinMinLength) {
      setState(() => _error =
          'Minimum ${AppConstants.vaultPinMinLength} chiffres.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    final vault = context.read<FolderVaultService>();
    try {
      await vault.unlockWithPin(folder: widget.folder, pin: _entry);
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } on WrongPinException catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _entry = '';
        _error = 'PIN incorrect. ${e.attemptsRemaining} '
            'tentative${e.attemptsRemaining > 1 ? 's' : ''} restante'
            '${e.attemptsRemaining > 1 ? 's' : ''}.';
      });
    } on VaultPinWipedException {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _wiped = true;
        _error = 'Trop de tentatives ratées. Le coffre a été détruit. '
            'Les données du dossier sont définitivement perdues.';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = 'Erreur de déverrouillage : $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 36,
                height: 4,
                margin: const EdgeInsets.only(bottom: 12),
                decoration: BoxDecoration(
                  color: cs.outlineVariant,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            Row(
              children: [
                Icon(Icons.dialpad_outlined, color: cs.error),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    'Code PIN — « ${widget.folder.name} »',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 18),
            _DotsIndicator(
              filled: _entry.length,
              max: AppConstants.vaultPinMaxLength,
            ),
            if (_busy) ...[
              const SizedBox(height: 12),
              const Center(
                child: SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              ),
            ],
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(
                _error!,
                textAlign: TextAlign.center,
                style: TextStyle(color: cs.error, fontSize: 13),
              ),
            ],
            const SizedBox(height: 12),
            if (!_wiped)
              _NumericKeypad(
                onDigit: _onDigit,
                onBackspace: _onBackspace,
                onValidate: _onValidate,
                disabled: _busy,
              ),
            const SizedBox(height: 4),
            TextButton(
              onPressed: _busy
                  ? null
                  : () => Navigator.of(context).pop(_wiped ? false : false),
              child: Text(_wiped ? 'Fermer' : 'Annuler'),
            ),
            const SizedBox(height: 4),
          ],
        ),
      ),
    );
  }
}

// ─── Helper unifié : route selon le mode du coffre ───────────────────

/// Affiche le sheet de déverrouillage adapté au mode du coffre :
/// - [VaultMode.passphrase] → [showUnlockVaultSheet] (v0.8, Argon2id lourd)
/// - [VaultMode.pin]        → [showUnlockPinSheet] (v0.9, pavé numérique)
///
/// Évite aux call-sites (drawer, éditeur, home) de tester
/// `folder.isPinVault` partout. Retourne `true` si déverrouillé,
/// `false` si annulé / auto-wipe, `null` si fermé externe.
Future<bool?> showUnlockVaultAdaptive({
  required BuildContext context,
  required Folder folder,
}) {
  if (folder.isPinVault) {
    return showUnlockPinSheet(context: context, folder: folder);
  }
  return showUnlockVaultSheet(context: context, folder: folder);
}

// ─── Composants partagés ─────────────────────────────────────────────

/// Suite de cercles vides/remplis indiquant la progression de saisie.
class _DotsIndicator extends StatelessWidget {
  const _DotsIndicator({required this.filled, required this.max});
  final int filled;
  final int max;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: List.generate(max, (i) {
        final isFilled = i < filled;
        return Container(
          width: 16,
          height: 16,
          margin: const EdgeInsets.symmetric(horizontal: 6),
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: isFilled ? cs.primary : Colors.transparent,
            border: Border.all(
              color: isFilled ? cs.primary : cs.outline,
              width: 1.5,
            ),
          ),
        );
      }),
    );
  }
}

/// Pavé numérique 0-9 + backspace + valider, façon écran de verrouillage.
class _NumericKeypad extends StatelessWidget {
  const _NumericKeypad({
    required this.onDigit,
    required this.onBackspace,
    required this.onValidate,
    this.disabled = false,
  });

  final void Function(String digit) onDigit;
  final VoidCallback onBackspace;
  final VoidCallback onValidate;
  final bool disabled;

  @override
  Widget build(BuildContext context) {
    Widget btn(
      String label,
      VoidCallback? onPressed, {
      Widget? icon,
      String? semanticsLabel,
    }) {
      return Expanded(
        child: Padding(
          padding: const EdgeInsets.all(6),
          child: AspectRatio(
            aspectRatio: 1.6,
            child: Semantics(
              // Label explicite pour TalkBack : sans ça, les boutons
              // backspace / valider sont annoncés "bouton" sans plus.
              label: semanticsLabel ?? label,
              button: true,
              enabled: !disabled,
              child: ExcludeSemantics(
                // L'icône à l'intérieur ne doit pas être lue en plus du
                // label parent — sinon double annonce ("Effacer, image").
                child: OutlinedButton(
                  onPressed: disabled ? null : onPressed,
                  style: OutlinedButton.styleFrom(
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  child: icon ??
                      Text(
                        label,
                        style: const TextStyle(
                          fontSize: 22,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                ),
              ),
            ),
          ),
        ),
      );
    }

    Widget row(List<Widget> children) =>
        Row(crossAxisAlignment: CrossAxisAlignment.center, children: children);

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        row([for (final d in ['1', '2', '3']) btn(d, () => onDigit(d))]),
        row([for (final d in ['4', '5', '6']) btn(d, () => onDigit(d))]),
        row([for (final d in ['7', '8', '9']) btn(d, () => onDigit(d))]),
        row([
          btn('', onBackspace,
              icon: const Icon(Icons.backspace_outlined),
              semanticsLabel: 'Effacer le dernier chiffre'),
          btn('0', () => onDigit('0')),
          btn('', onValidate,
              icon: const Icon(Icons.check_circle_outline, size: 28),
              semanticsLabel: 'Valider le code'),
        ]),
      ],
    );
  }
}

/// Empêche tout autre input que numérique pour cohérence (au cas où on
/// branche un clavier matériel — sécurité défense en profondeur).
@visibleForTesting
class DigitsOnlyInputFormatter extends TextInputFormatter {
  const DigitsOnlyInputFormatter();
  static final RegExp _re = RegExp(r'^\d*$');
  @override
  TextEditingValue formatEditUpdate(
    TextEditingValue oldValue,
    TextEditingValue newValue,
  ) {
    if (_re.hasMatch(newValue.text)) return newValue;
    return oldValue;
  }
}
