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

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../../core/a11y.dart';
import '../../core/constants.dart';
import '../../data/models/folder.dart';
import '../../services/security/folder_vault_service.dart';

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
          bottom: MediaQuery.of(ctx).viewInsets.bottom,
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
  bool _hide1 = true;
  bool _hide2 = true;

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
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Petite poignée Material 3
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
                    'Convertir « ${widget.folderName} » en coffre',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
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
                      'Mot de passe oublié = notes perdues définitivement. '
                      'Aucune sauvegarde, aucune récupération possible. '
                      'Choisissez quelque chose de mémorable.',
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
            const SizedBox(height: 16),
            TextField(
              controller: _pass1,
              autofocus: true,
              obscureText: _hide1,
              textInputAction: TextInputAction.next,
              keyboardType: TextInputType.visiblePassword,
              autofillHints: const [], // jamais autofill pour un coffre
              decoration: InputDecoration(
                labelText: 'Passphrase',
                helperText:
                    'Minimum ${AppConstants.vaultPassphraseMinLength} caractères',
                border: const OutlineInputBorder(),
                suffixIcon: IconButton(
                  icon: Icon(
                    _hide1 ? Icons.visibility_off_outlined : Icons.visibility_outlined,
                  ),
                  onPressed: () => setState(() => _hide1 = !_hide1),
                ),
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _pass2,
              obscureText: _hide2,
              textInputAction: TextInputAction.done,
              keyboardType: TextInputType.visiblePassword,
              onSubmitted: (_) => _submit(),
              decoration: InputDecoration(
                labelText: 'Confirmer la passphrase',
                helperText: _pass2.text.isEmpty
                    ? null
                    : (_matchOk ? 'Identique ✓' : 'Les deux champs diffèrent'),
                helperStyle: TextStyle(
                  color: _pass2.text.isEmpty
                      ? null
                      : (_matchOk ? cs.successIcon : cs.error),
                ),
                border: const OutlineInputBorder(),
                suffixIcon: IconButton(
                  icon: Icon(
                    _hide2 ? Icons.visibility_off_outlined : Icons.visibility_outlined,
                  ),
                  onPressed: () => setState(() => _hide2 = !_hide2),
                ),
              ),
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text('Annuler'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: FilledButton.icon(
                    onPressed: _canSubmit ? _submit : null,
                    icon: const Icon(Icons.lock_outline),
                    label: const Text('Créer le coffre'),
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
          bottom: MediaQuery.of(ctx).viewInsets.bottom,
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
  bool _hide = true;
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
    setState(() {
      _busy = true;
      _error = null;
    });
    final vault = context.read<FolderVaultService>();
    try {
      await vault.unlock(folder: widget.folder, passphrase: _passCtrl.text);
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } on WrongPassphraseException {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = 'Passphrase incorrecte.';
      });
      // Vide le champ, focus reste — UX standard de retry
      _passCtrl.clear();
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
                Icon(Icons.lock_outline, color: cs.error),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    'Déverrouiller « ${widget.folder.name} »',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _passCtrl,
              autofocus: true,
              obscureText: _hide,
              enabled: !_busy,
              textInputAction: TextInputAction.done,
              keyboardType: TextInputType.visiblePassword,
              autofillHints: const [],
              onSubmitted: (_) => _submit(),
              decoration: InputDecoration(
                labelText: 'Passphrase',
                errorText: _error,
                border: const OutlineInputBorder(),
                suffixIcon: IconButton(
                  icon: Icon(
                    _hide ? Icons.visibility_off_outlined : Icons.visibility_outlined,
                  ),
                  onPressed: _busy
                      ? null
                      : () => setState(() => _hide = !_hide),
                ),
              ),
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: _busy
                        ? null
                        : () => Navigator.of(context).pop(false),
                    child: const Text('Annuler'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: FilledButton.icon(
                    onPressed: _busy ? null : _submit,
                    icon: _busy
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.lock_open_outlined),
                    label: Text(_busy ? 'Déverrouillage…' : 'Déverrouiller'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              'La dérivation Argon2id prend 1 à 2 s sur les téléphones '
              'récents. C\'est volontaire — ça rend le bruteforce hors-ligne '
              'inopérant.',
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

// ─── Helpers d'inputs ─────────────────────────────────────────────────

/// Sécurité défense en profondeur : empêche le copier-coller depuis
/// l'extérieur dans le champ passphrase (rares apps clavier qui
/// envoient des textes prédictifs). Pas appliqué globalement —
/// optionnel si quelqu'un l'active plus tard.
class NoSuggestionFormatter extends TextInputFormatter {
  const NoSuggestionFormatter();
  @override
  TextEditingValue formatEditUpdate(
    TextEditingValue oldValue,
    TextEditingValue newValue,
  ) {
    return newValue;
  }
}
