/// Bottom sheets de création et de déverrouillage d'un coffre.
///
/// Deux flux distincts :
///
/// - **Création** ([showCreateVaultSheet]) : avertissement strict
///   « passphrase oubliée = données perdues », saisie 2× (champ + champ
///   confirmation), validation longueur minimale, bouton désactivé tant
///   que les 2 champs ne sont pas identiques et longueur OK.
///
/// - **Déverrouillage** ([showUnlockVaultSheet]) : champ unique, bouton
///   « Déverrouiller » qui valide via [FolderVaultService.unlock].
///   En cas de mauvaise passphrase, message rouge sous le champ et
///   l'utilisateur peut retenter sans fermer le sheet.
///
/// Aucune des deux versions ne stocke la passphrase : elle vit
/// uniquement dans le `TextEditingController` qui est `dispose`d à la
/// fermeture.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:provider/provider.dart';

import '../../core/constants.dart';
import '../../data/models/folder.dart';
import '../../l10n/app_localizations.dart';
import '../../services/security/folder_vault_service.dart';
import 'passphrase_text_field.dart';
import 'sheet_handle.dart';
import 'vault_warning_banner.dart';

// ─── Création ─────────────────────────────────────────────────────────

/// Affiche le sheet de création de coffre. Retourne la passphrase
/// validée (jamais vide, jamais inférieure au min) ou `null` si
/// l'utilisateur annule.
Future<String?> showCreateVaultSheet({
  required BuildContext context,
  required String folderName,
}) {
  return showModalBottomSheet<String>(
    context: context,
    isScrollControlled: true,
    isDismissible: false,
    enableDrag: false,
    builder: (ctx) {
      // Padding réactif au clavier (`viewInsets.bottom` quand IME ouvert).
      return Padding(
        padding: EdgeInsets.only(
          bottom: MediaQuery.viewInsetsOf(ctx).bottom,
        ),
        child: _CreateVaultSheet(folderName: folderName),
      );
    },
  );
}

class _CreateVaultSheet extends StatefulWidget {
  const _CreateVaultSheet({required this.folderName});
  final String folderName;

  @override
  State<_CreateVaultSheet> createState() => _CreateVaultSheetState();
}

class _CreateVaultSheetState extends State<_CreateVaultSheet> {
  final _pass1 = TextEditingController();
  final _pass2 = TextEditingController();

  bool get _lengthOk =>
      _pass1.text.length >= AppConstants.vaultPassphraseMinLength;
  bool get _matchOk =>
      _pass1.text.isNotEmpty && _pass1.text == _pass2.text;
  bool get _canSubmit => _lengthOk && _matchOk;

  @override
  void initState() {
    super.initState();
    _pass1.addListener(() => setState(() {}));
    _pass2.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _pass1.dispose();
    _pass2.dispose();
    super.dispose();
  }

  void _submit() {
    if (!_canSubmit) return;
    Navigator.of(context).pop(_pass1.text);
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
                Icon(Icons.lock_outline, color: cs.error),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    t.vaultPassCreateTitle,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              t.vaultPassCreateBody,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: cs.onSurfaceVariant,
                    height: 1.35,
                    fontSize: 14,
                  ),
            ),
            const SizedBox(height: 12),
            VaultWarningBanner(message: t.vaultPassWarningLost),
            const SizedBox(height: 16),
            PassphraseTextField(
              controller: _pass1,
              labelText: t.vaultPassField,
              autofocus: true,
              textInputAction: TextInputAction.next,
              helperText: t.vaultPassMinLength(
                AppConstants.vaultPassphraseMinLength,
              ),
            ),
            const SizedBox(height: 12),
            PassphraseTextField(
              controller: _pass2,
              labelText: t.vaultPassConfirmField,
              textInputAction: TextInputAction.done,
              onSubmitted: (_) => _submit(),
              errorText:
                  (_pass2.text.isNotEmpty && !_matchOk) ? t.vaultPassMismatch : null,
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: Text(t.commonCancel),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: FilledButton.icon(
                    onPressed: _canSubmit ? _submit : null,
                    icon: const Icon(Icons.lock_outline),
                    label: Text(t.vaultPassCreateAction),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }
}

// ─── Déverrouillage ───────────────────────────────────────────────────

/// Affiche le sheet de déverrouillage d'un coffre. Retourne `true` si
/// l'utilisateur a réussi à déverrouiller (`unlock` du service a réussi),
/// sinon `false` (annulation) ou `null`.
///
/// Le sheet gère lui-même l'appel à [FolderVaultService.unlock], affiche
/// un loader pendant l'Argon2id (~1-2 s sur S9) et un message d'erreur
/// si la passphrase est incorrecte — l'utilisateur peut retenter sans
/// fermer.
Future<bool?> showUnlockVaultSheet({
  required BuildContext context,
  required Folder folder,
}) {
  return showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    isDismissible: true,
    builder: (ctx) {
      return Padding(
        padding: EdgeInsets.only(
          bottom: MediaQuery.viewInsetsOf(ctx).bottom,
        ),
        child: _UnlockVaultSheet(folder: folder),
      );
    },
  );
}

class _UnlockVaultSheet extends StatefulWidget {
  const _UnlockVaultSheet({required this.folder});
  final Folder folder;

  @override
  State<_UnlockVaultSheet> createState() => _UnlockVaultSheetState();
}

class _UnlockVaultSheetState extends State<_UnlockVaultSheet> {
  final _passCtrl = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _passCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_busy) return;
    if (_passCtrl.text.isEmpty) return;
    final t = AppLocalizations.of(context);
    setState(() {
      _busy = true;
      _error = null;
    });
    final vault = context.read<FolderVaultService>();
    try {
      await vault.unlock(folder: widget.folder, passphrase: _passCtrl.text);
      if (!mounted) return;
      // A11y : annonce TalkBack/lecteur d'écran que le coffre est ouvert.
      unawaited(
        SemanticsService.announce(
          t.homeAnnounceVaultUnlocked,
          TextDirection.ltr,
        ),
      );
      Navigator.of(context).pop(true);
    } on WrongPassphraseException {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = t.vaultPassWrong;
      });
      // Vide le champ, focus reste — UX standard de retry
      _passCtrl.clear();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = t.commonErrorWith(e.toString());
      });
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
                Icon(Icons.lock_outline, color: cs.error),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    t.vaultPassUnlockTitle,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              t.vaultPassUnlockBody(widget.folder.name),
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: cs.onSurfaceVariant,
                    fontSize: 14,
                  ),
            ),
            const SizedBox(height: 12),
            PassphraseTextField(
              controller: _passCtrl,
              labelText: t.vaultPassField,
              enabled: !_busy,
              autofocus: true,
              textInputAction: TextInputAction.done,
              onSubmitted: (_) => _submit(),
              errorText: _error,
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: _busy
                        ? null
                        : () => Navigator.of(context).pop(false),
                    child: Text(t.commonCancel),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: FilledButton.icon(
                    onPressed: _busy ? null : _submit,
                    icon: _busy
                        ? Semantics(
                            liveRegion: true,
                            label: t.vaultPassDeriving,
                            child: const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            ),
                          )
                        : const Icon(Icons.lock_open_outlined),
                    label: Text(
                      _busy ? t.vaultPassDeriving : t.vaultPassUnlockAction,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              t.vaultPassDeriving,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: cs.onSurfaceVariant,
                  ),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }
}
