/// Bottom sheets PIN (v0.9) — création + déverrouillage d'un coffre PIN.
///
/// UX volontairement minimaliste, calquée sur les écrans de verrouillage
/// Android : pavé numérique 0-9, dots indicator, pas de visibility toggle
/// (un PIN court visible = défense de l'épaule trop coûteuse à perdre).
///
/// Le design crypto vit dans `FolderVaultService.{createPinVault,
/// unlockWithPin}` — les sheets ne font que collecter les chiffres et
/// déléguer.
///
/// **Performance** (v0.9.2) : le pavé numérique est isolé du parent via
/// `ValueListenableBuilder` sur les états `_entry`/`_busy`/`_error`.
/// Conséquence : taper un chiffre rebuild **uniquement** les dots + le
/// texte d'erreur, **pas les 12 boutons du clavier**. Sans ça, un tap
/// déclenchait un rebuild de 12 `OutlinedButton` avec resolution thème +
/// états Material → jank visible sur S9/POCO C75 (et même S24 FE en
/// debug). Les boutons utilisent maintenant `Material + InkWell` direct
/// (plus léger qu'`OutlinedButton`).
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../../core/constants.dart';
import '../../data/models/folder.dart';
import '../../l10n/app_localizations.dart';
import '../../services/secure_window_service.dart';
import '../../services/security/folder_vault_service.dart';
import 'sheet_handle.dart';
import 'vault_passphrase_sheets.dart';
import 'vault_warning_banner.dart';

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
      final t = AppLocalizations.of(ctx);
      return SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SheetHandle(),
              Row(
                children: [
                  Icon(Icons.lock_outline, color: cs.error),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      t.vaultModeChoose,
                      style: Theme.of(ctx).textTheme.titleMedium,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              _ModeCard(
                icon: Icons.password_outlined,
                title: t.vaultModePassphrase,
                subtitle: t.vaultModePassphraseDesc,
                onTap: () => Navigator.of(ctx).pop(VaultMode.passphrase),
              ),
              const SizedBox(height: 12),
              _ModeCard(
                icon: Icons.dialpad_outlined,
                title: t.vaultModePin,
                subtitle: t.vaultModePinDesc,
                onTap: () => Navigator.of(ctx).pop(VaultMode.pin),
              ),
              const SizedBox(height: 16),
              TextButton(
                onPressed: () => Navigator.of(ctx).pop(),
                child: Text(t.commonCancel),
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
                  Text(title, style: Theme.of(context).textTheme.titleSmall),
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
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(ctx).bottom),
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
  // ValueNotifiers : le pavé numérique ne dépend que de `_busyN` (toujours
  // false dans CreatePin, mais structure identique à Unlock pour
  // cohérence) — l'ajout d'un chiffre ne le rebuild PAS.
  final _entryN = ValueNotifier<String>('');
  final _errorN = ValueNotifier<String?>(null);
  final _stepN = ValueNotifier<_CreateStep>(_CreateStep.first);
  String _firstPin = '';

  @override
  void dispose() {
    _entryN.dispose();
    _errorN.dispose();
    _stepN.dispose();
    super.dispose();
  }

  void _onDigit(String d) {
    if (_entryN.value.length >= AppConstants.vaultPinMaxLength) return;
    _entryN.value = '${_entryN.value}$d';
    _errorN.value = null;
  }

  void _onBackspace() {
    if (_entryN.value.isEmpty) return;
    _entryN.value = _entryN.value.substring(0, _entryN.value.length - 1);
    _errorN.value = null;
  }

  void _onValidate() {
    final t = AppLocalizations.of(context);
    final entry = _entryN.value;
    if (entry.length < AppConstants.vaultPinMinLength) {
      _errorN.value = t.vaultPinTooShort(
        AppConstants.vaultPinMinLength,
        AppConstants.vaultPinMaxLength,
      );
      return;
    }
    if (_stepN.value == _CreateStep.first) {
      _firstPin = entry;
      _entryN.value = '';
      _errorN.value = null;
      _stepN.value = _CreateStep.confirm;
      return;
    }
    if (entry != _firstPin) {
      _entryN.value = '';
      _errorN.value = t.vaultPinMismatch;
      _firstPin = '';
      _stepN.value = _CreateStep.first;
      return;
    }
    Navigator.of(context).pop(_firstPin);
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SheetHandle(),
            ValueListenableBuilder<_CreateStep>(
              valueListenable: _stepN,
              builder: (_, step, _) => Row(
                children: [
                  Icon(Icons.dialpad_outlined, color: cs.error),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      step == _CreateStep.first
                          ? t.vaultPinCreateTitle
                          : t.vaultPinConfirmField,
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),
            ValueListenableBuilder<_CreateStep>(
              valueListenable: _stepN,
              builder: (_, step, _) {
                if (step != _CreateStep.first) return const SizedBox.shrink();
                return VaultWarningBanner(message: t.vaultPinWarningWipe);
              },
            ),
            const SizedBox(height: 18),
            // Dots : reconstruits SEULS sur change de _entry — léger.
            ValueListenableBuilder<String>(
              valueListenable: _entryN,
              builder: (_, entry, _) => _DotsIndicator(
                filled: entry.length,
                max: AppConstants.vaultPinMaxLength,
              ),
            ),
            ValueListenableBuilder<String?>(
              valueListenable: _errorN,
              builder: (_, error, _) {
                if (error == null) return const SizedBox(height: 12);
                return Padding(
                  padding: const EdgeInsets.only(top: 8, bottom: 4),
                  child: Text(
                    error,
                    textAlign: TextAlign.center,
                    style: Theme.of(
                      context,
                    ).textTheme.bodyMedium?.copyWith(color: cs.error),
                  ),
                );
              },
            ),
            // Pavé numérique : NE rebuild jamais sur saisie d'un chiffre
            // (ni le step ni le _busy ne change pendant la création).
            // Performance : 12 tap sur le pavé n'allouent plus aucun
            // OutlinedButton, juste un repaint local des dots.
            const SizedBox(height: 4),
            RepaintBoundary(
              child: _NumericKeypad(
                onDigit: _onDigit,
                onBackspace: _onBackspace,
                onValidate: _onValidate,
                disabledListenable: _kAlwaysFalse,
              ),
            ),
            const SizedBox(height: 4),
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: Text(t.commonCancel),
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
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(ctx).bottom),
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

class _UnlockPinSheetState extends State<_UnlockPinSheet>
    with SecureWindowGuardMixin {
  // ValueNotifiers : pavé isolé des changements d'entry.
  // `_busyN` est consommé à la fois par le pavé (disable) ET par le
  // bouton Annuler/Fermer + le spinner.
  final _entryN = ValueNotifier<String>('');
  final _errorN = ValueNotifier<String?>(null);
  final _busyN = ValueNotifier<bool>(false);
  final _wipedN = ValueNotifier<bool>(false);

  @override
  void dispose() {
    _entryN.dispose();
    _errorN.dispose();
    _busyN.dispose();
    _wipedN.dispose();
    super.dispose();
  }

  void _onDigit(String d) {
    if (_busyN.value || _wipedN.value) return;
    if (_entryN.value.length >= AppConstants.vaultPinMaxLength) return;
    _entryN.value = '${_entryN.value}$d';
    _errorN.value = null;
  }

  void _onBackspace() {
    if (_busyN.value || _wipedN.value) return;
    if (_entryN.value.isEmpty) return;
    _entryN.value = _entryN.value.substring(0, _entryN.value.length - 1);
    _errorN.value = null;
  }

  Future<void> _onValidate() async {
    if (_busyN.value || _wipedN.value) return;
    final t = AppLocalizations.of(context);
    final entry = _entryN.value;
    if (entry.length < AppConstants.vaultPinMinLength) {
      _errorN.value = t.vaultPinTooShort(
        AppConstants.vaultPinMinLength,
        AppConstants.vaultPinMaxLength,
      );
      return;
    }
    // Feedback visuel IMMÉDIAT : flip _busyN avant tout `await` pour
    // que le spinner s'affiche au prochain frame, sans attendre le
    // round-trip Keystore + Argon2id.
    _busyN.value = true;
    _errorN.value = null;
    final vault = context.read<FolderVaultService>();
    try {
      await vault.unlockWithPin(folder: widget.folder, pin: entry);
      if (!mounted) return;
      // A11y : annonce TalkBack que le coffre PIN est déverrouillé.
      unawaited(
        // ignore: deprecated_member_use
        SemanticsService.announce(
          t.homeAnnounceVaultUnlocked,
          TextDirection.ltr,
        ),
      );
      Navigator.of(context).pop(true);
    } on WrongPinException catch (e) {
      if (!mounted) return;
      _busyN.value = false;
      _entryN.value = '';
      _errorN.value =
          '${t.vaultPinWrong} ${t.vaultPinAttemptsLeft(e.attemptsRemaining)}';
    } on VaultPinWipedException {
      if (!mounted) return;
      _busyN.value = false;
      _wipedN.value = true;
      _errorN.value = t.vaultPinWiped;
      // A11y : annonce critique, l'utilisateur DOIT savoir que le coffre
      // a été détruit même s'il ne lit pas l'écran.
      // ignore: deprecated_member_use
      unawaited(SemanticsService.announce(t.vaultPinWiped, TextDirection.ltr));
    } catch (e) {
      if (!mounted) return;
      _busyN.value = false;
      _errorN.value = t.commonErrorWith(e.toString());
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SheetHandle(),
            Row(
              children: [
                Icon(Icons.dialpad_outlined, color: cs.error),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    t.vaultPinUnlockTitle,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              t.vaultPinUnlockBody(widget.folder.name),
              style: Theme.of(
                context,
              ).textTheme.bodyMedium?.copyWith(color: cs.onSurfaceVariant),
            ),
            const SizedBox(height: 18),
            ValueListenableBuilder<String>(
              valueListenable: _entryN,
              builder: (_, entry, _) => Semantics(
                liveRegion: true,
                label: t.vaultPinDigitsAnnounce(
                  entry.length,
                  AppConstants.vaultPinMaxLength,
                ),
                child: ExcludeSemantics(
                  child: _DotsIndicator(
                    filled: entry.length,
                    max: AppConstants.vaultPinMaxLength,
                  ),
                ),
              ),
            ),
            // Spinner busy : feedback visuel immédiat dès _busyN=true,
            // sans attendre le retour Argon2id+Keystore.
            ValueListenableBuilder<bool>(
              valueListenable: _busyN,
              builder: (_, busy, _) {
                if (!busy) return const SizedBox(height: 12);
                return Padding(
                  padding: const EdgeInsets.only(top: 12, bottom: 4),
                  child: Center(
                    child: Semantics(
                      liveRegion: true,
                      label: t.vaultPassDeriving,
                      child: const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      ),
                    ),
                  ),
                );
              },
            ),
            ValueListenableBuilder<String?>(
              valueListenable: _errorN,
              builder: (_, error, _) {
                if (error == null) return const SizedBox.shrink();
                return Padding(
                  padding: const EdgeInsets.only(top: 8, bottom: 4),
                  child: Text(
                    error,
                    textAlign: TextAlign.center,
                    style: Theme.of(
                      context,
                    ).textTheme.bodyMedium?.copyWith(color: cs.error),
                  ),
                );
              },
            ),
            const SizedBox(height: 8),
            // Pavé : rebuild SEULEMENT quand `_busyN` ou `_wipedN`
            // changent (jamais à la saisie d'un chiffre).
            ValueListenableBuilder<bool>(
              valueListenable: _wipedN,
              builder: (_, wiped, _) {
                if (wiped) return const SizedBox.shrink();
                return RepaintBoundary(
                  child: _NumericKeypad(
                    onDigit: _onDigit,
                    onBackspace: _onBackspace,
                    onValidate: _onValidate,
                    disabledListenable: _busyN,
                  ),
                );
              },
            ),
            const SizedBox(height: 4),
            ValueListenableBuilder<bool>(
              valueListenable: _busyN,
              builder: (_, busy, _) => ValueListenableBuilder<bool>(
                valueListenable: _wipedN,
                builder: (_, wiped, _) => TextButton(
                  onPressed: busy
                      ? null
                      : () => Navigator.of(context).pop(false),
                  child: Text(wiped ? t.commonClose : t.commonCancel),
                ),
              ),
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

/// `ValueListenable<bool>` immutable retournant toujours `false`,
/// utilisé par `_NumericKeypad` quand le pavé n'a pas de notion de
/// "busy" (création PIN, validation synchrone).
///
/// Implémentation custom plutôt qu'un `ValueNotifier` global :
/// (a) pas besoin de `dispose()` (pas de listeners actifs),
/// (b) garantit qu'aucun code ne peut muter la valeur par accident.
class _AlwaysFalseListenable extends ValueListenable<bool> {
  const _AlwaysFalseListenable();
  @override
  bool get value => false;
  @override
  void addListener(VoidCallback listener) {
    /* no-op : valeur fixe */
  }
  @override
  void removeListener(VoidCallback listener) {
    /* no-op */
  }
}

const _AlwaysFalseListenable _kAlwaysFalse = _AlwaysFalseListenable();

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
///
/// **Performance** : utilise `Material + InkWell` au lieu de
/// `OutlinedButton` (moins d'overhead thème/states/animation) et
/// consomme `disabledListenable` via `ValueListenableBuilder` interne
/// pour ne pas dépendre du rebuild parent.
class _NumericKeypad extends StatelessWidget {
  const _NumericKeypad({
    required this.onDigit,
    required this.onBackspace,
    required this.onValidate,
    required this.disabledListenable,
  });

  final void Function(String digit) onDigit;
  final VoidCallback onBackspace;
  final VoidCallback onValidate;
  final ValueListenable<bool> disabledListenable;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return ValueListenableBuilder<bool>(
      valueListenable: disabledListenable,
      builder: (_, disabled, _) {
        Widget btn(
          String label,
          VoidCallback? onPressed, {
          IconData? icon,
          String? semanticsLabel,
        }) {
          return Expanded(
            child: Padding(
              padding: const EdgeInsets.all(6),
              child: AspectRatio(
                aspectRatio: 1.6,
                child: Semantics(
                  label: semanticsLabel ?? label,
                  button: true,
                  enabled: !disabled,
                  child: ExcludeSemantics(
                    child: _KeypadButton(
                      onTap: disabled ? null : onPressed,
                      icon: icon,
                      label: icon == null ? label : null,
                    ),
                  ),
                ),
              ),
            ),
          );
        }

        Widget digitBtn(String d) =>
            btn(d, () => onDigit(d), semanticsLabel: t.vaultPinKeyLabel(d));

        Widget row(List<Widget> children) => Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: children,
        );

        return Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            row([
              for (final d in ['1', '2', '3']) digitBtn(d),
            ]),
            row([
              for (final d in ['4', '5', '6']) digitBtn(d),
            ]),
            row([
              for (final d in ['7', '8', '9']) digitBtn(d),
            ]),
            row([
              btn(
                '',
                onBackspace,
                icon: Icons.backspace_outlined,
                semanticsLabel: t.vaultPinKeyDelete,
              ),
              digitBtn('0'),
              btn(
                '',
                onValidate,
                icon: Icons.check_circle_outline,
                semanticsLabel: t.commonValidate,
              ),
            ]),
          ],
        );
      },
    );
  }
}

/// Bouton individuel du pavé — `Material` + `InkWell` au lieu de
/// `OutlinedButton` pour réduire l'overhead (moins de wrappers, pas de
/// `ButtonStyleButton` qui résout le thème pour chaque état Material).
class _KeypadButton extends StatelessWidget {
  const _KeypadButton({required this.onTap, this.icon, this.label});
  final VoidCallback? onTap;
  final IconData? icon;
  final String? label;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final enabled = onTap != null;
    final fg = enabled ? cs.onSurface : cs.onSurface.withValues(alpha: 0.38);
    return Material(
      color: Colors.transparent,
      shape: RoundedRectangleBorder(
        side: BorderSide(color: cs.outline.withValues(alpha: 0.5)),
        borderRadius: BorderRadius.circular(12),
      ),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Center(
          child: icon != null
              ? Icon(icon, size: 26, color: fg)
              : Text(
                  label ?? '',
                  // v1.0.7 UI I2 — base sur titleLarge (22sp) puis copyWith
                  // pour conserver le textScaler système (a11y). Avant :
                  // fontSize statique ignorait MediaQuery.textScaler.
                  style: Theme.of(context).textTheme.titleLarge?.copyWith(
                    fontWeight: FontWeight.w500,
                    color: fg,
                  ),
                ),
        ),
      ),
    );
  }
}

/// Filtre saisie chiffres uniquement — réservé pour un futur clavier
/// matériel branché (le pavé virtuel filtre déjà via `_onDigit`). Gardé
/// ici @visibleForTesting pour ne pas être tree-shaken si jamais
/// référencé par un test custom.
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
