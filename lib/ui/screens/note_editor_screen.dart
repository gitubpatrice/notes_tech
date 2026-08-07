/// Écran d'édition d'une note.
///
/// - Édition titre + contenu Markdown brut (preview différée à v0.2).
/// - Auto-save debounced ; flush garanti sur sortie.
/// - Toggle pin / favori / corbeille sans round-trip DB.
library;

import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import 'package:provider/provider.dart';
import 'package:share_plus/share_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/constants.dart';
import '../../core/exceptions.dart';
import '../../data/models/note.dart';
import '../../data/models/note_link.dart';
import '../../data/repositories/folders_repository.dart';
import '../../data/repositories/notes_repository.dart';
import '../../l10n/app_localizations.dart';
import '../../services/backlinks_service.dart';
import '../../services/export/note_export_service.dart';
import '../../services/note_actions.dart';
import '../../services/secure_window_service.dart';
import '../../services/security/folder_vault_service.dart';
import '../../utils/debouncer.dart';
import '../../utils/error_localize.dart';
import '../../utils/snackbar_ext.dart';
import '../widgets/backlinks_panel.dart';
import '../widgets/link_autocomplete_sheet.dart';
import '../widgets/move_to_folder_sheet.dart';
import '../widgets/vault_pin_sheets.dart';
import '../widgets/voice_record_button.dart';

class NoteEditorScreen extends StatefulWidget {
  const NoteEditorScreen({super.key, required this.noteId});
  final String noteId;

  @override
  State<NoteEditorScreen> createState() => _NoteEditorScreenState();
}

class _NoteEditorScreenState extends State<NoteEditorScreen> {
  late final NotesRepository _repo;
  late final FolderVaultService _vault;
  StreamSubscription<void>? _changesSub;
  final _titleCtrl = TextEditingController();
  final _contentCtrl = TextEditingController();
  final _autosave = Debouncer(AppConstants.autosaveDebounce);
  final _savingNotifier = ValueNotifier<bool>(false);

  /// Throttle pour `SemanticsService.announce(noteEditorAnnounceSavedSuccess)`
  /// — autosave debounce 500ms peut déclencher 1 save / 1.5s en édition
  /// continue ; sans throttle, TalkBack saturé. 5s = équilibre confort.
  DateTime? _lastSavedAnnounce;

  Note? _note;
  bool _loading = true;
  bool _stale =
      false; // note supprimée / mise en corbeille → édition désactivée
  String? _error;
  Future<void>? _pendingSave;

  /// `true` si la note vient d'un dossier coffre. `_note` ci-dessus est
  /// l'éphémère déchiffrée (content rempli, encryptedContent null) — on
  /// retient cette information pour que `_doSave` ré-encrypte avant
  /// persistance, gardant le modèle « toujours chiffré au repos ».
  bool _wasLocked = false;

  /// Vrai dès que l'écran a été refermé pour cause de coffre verrouillé.
  /// Rend `_closeOnVaultLock` idempotent — voir son commentaire.
  bool _fermeSurVerrouillage = false;

  /// v1.0.7 UI I1 — force FLAG_SECURE pendant que le contenu d'une note
  /// déchiffrée vault est en RAM, même si l'utilisateur a désactivé la
  /// préférence globale. Refcount via [SecureWindowService] : on appelle
  /// `forceEnabled()` une seule fois quand la note s'avère locked, puis
  /// `restore()` une seule fois au dispose. Le flag `_secureForced` évite
  /// le double-call si on rebascule entre vault et non-vault via move.
  final _secureWindow = SecureWindowService();
  bool _secureForced = false;

  void _ensureSecureForced() {
    if (_secureForced) return;
    _secureForced = true;
    _secureWindow.forceEnabled();
  }

  @override
  void initState() {
    super.initState();
    _repo = context.read<NotesRepository>();
    _vault = context.read<FolderVaultService>();
    // Le correctif de fermeture n'etait branche que sur les chemins de
    // SAUVEGARDE. Un utilisateur qui LIT sa note sans y toucher ne declenche
    // aucun auto-save : l'auto-lock tombait, le coffre se fermait, et le clair
    // restait affiche indefiniment. La fuite subsistait donc pour le cas le
    // plus courant — lire une note et poser son telephone. Relevee par une
    // relecture externe (GPT-5.5).
    //
    // `FolderVaultService` est un `ChangeNotifier` : on ecoute directement le
    // verrouillage plutot que d'attendre une ecriture qui ne viendra pas.
    _vault.addListener(_onVaultChanged);
    _load();
    // Si la note est supprimée/mise à la corbeille depuis un autre écran,
    // on désactive l'édition pour éviter de "ressusciter" la note via
    // un save final dans dispose.
    _changesSub = _repo.changes.listen((event) async {
      if (!mounted) return;
      // P1.2 v1.0.9 — Ne re-`get` que si l'événement concerne CETTE note
      // (ou est un bulk qui peut purger la corbeille). Avant : `get`
      // déclenché à chaque save d'une autre note → 1 SELECT SQLCipher/s en
      // édition continue (autosave 500 ms + multi-écrans ouverts).
      if (!event.isBulk && event.id != widget.noteId) return;
      final fresh = await _repo.get(widget.noteId);
      if (!mounted) return;
      if (fresh == null || fresh.isTrashed) {
        setState(() => _stale = true);
      }
    });
  }

  /// Referme l'ecran des que le coffre de la note se verrouille, quelle
  /// qu'en soit la cause : auto-lock, verrouillage manuel, mode panique.
  void _onVaultChanged() {
    if (_fermeSurVerrouillage) return;
    final n = _note;
    if (n == null || !_wasLocked) return;
    if (_vault.isUnlocked(n.folderId)) return;
    // ⚠️ DIFFÉRÉ D'UNE FRAME, et ce n'est pas de la superstition.
    // `notifyListeners` peut tomber PENDANT la construction d'une frame —
    // typiquement quand le balayage d'auto-lock verrouille alors qu'un autre
    // écran se reconstruit. Dépiler une route à ce moment lève
    // « setState() or markNeedsBuild() called during build ». On ferme donc
    // à la frame suivante, quand l'arbre est stable.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _fermeSurVerrouillage) return;
      _showError(
        AppLocalizations.of(context).noteEditorErrorVaultRelockedDuringEdit,
      );
      _closeOnVaultLock();
    });
  }

  @override
  void dispose() {
    _vault.removeListener(_onVaultChanged);
    _changesSub?.cancel();
    _autosave.cancel();
    // Save final SEULEMENT si la note est encore valide.
    final n = _note;
    if (n != null && !_stale) {
      final title = _titleCtrl.text;
      final content = _contentCtrl.text;
      if (title != n.title || content != n.content) {
        // v1.0.7 qual H2 — sérialise le flush final derrière `_pendingSave`.
        // Avant : dispose lançait un `unawaited` save en parallèle d'un
        // autosave debounce déjà en vol → 2 UPDATE concurrents possibles
        // sur la même note. Maintenant on attend la fin du save courant
        // avant de lancer le final (au pire les deux opèrent sur le même
        // contenu et le 2e est un no-op, mais l'ordre est garanti).
        final pending = _pendingSave;
        final flush = pending == null
            ? _flushFinalSave(n, title, content)
            : pending.then((_) => _flushFinalSave(n, title, content));
        unawaited(flush);
      }
    }
    _titleCtrl.dispose();
    _contentCtrl.dispose();
    _savingNotifier.dispose();
    // v1.0.7 UI I1 — relâche le refcount FLAG_SECURE si on l'avait poussé.
    // Le restore est idempotent côté natif (clamp à 0) — mais on garde le
    // flag local pour ne pas spammer le canal au reload du screen.
    if (_secureForced) {
      _secureForced = false;
      _secureWindow.restore();
    }
    super.dispose();
  }

  /// Save terminal exécuté après que l'autosave courant est terminé.
  /// Logique miroir de `_doSave` mais sans dépendance à `mounted` ni à
  /// `context` (l'écran est déjà disposed à ce stade).
  Future<void> _flushFinalSave(Note n, String title, String content) async {
    try {
      if (_wasLocked) {
        // Note coffre : ré-encrypter AVANT save. Si la session a
        // expiré, on ABANDONNE la modif plutôt que d'écrire le contenu
        // en clair dans la DB (invariant : jamais clair au repos pour
        // une note de coffre).
        if (_vault.isUnlocked(n.folderId)) {
          // `isUnlocked` est un ÉCHANTILLON : l'auto-lock peut fermer la
          // session entre ce test et `encryptNote` (Argon2id n'est pas en
          // jeu ici, mais le sweep tourne sur son propre timer). Sans ce
          // catch dédié, la `VaultLockedException` partait dans le catch
          // global du bas et la modification était perdue **en silence** —
          // le bookkeeping F11 ne tournait pas, donc aucun banner au boot
          // suivant. C'est précisément ce que F11 voulait supprimer.
          try {
            final draft = n.copyWith(title: title, content: content);
            final encrypted = await _vault.encryptNote(draft);
            await _repo.save(encrypted);
          } on VaultLockedException {
            await _recordLostDraft(n.id);
          }
        } else {
          await _recordLostDraft(n.id);
        }
      } else {
        await _repo.save(n.copyWith(title: title, content: content));
      }
    } catch (e) {
      if (kDebugMode) debugPrint('flush save (dispose) : $e');
    }
  }

  /// F11 v1.1.0 — Avant : « perte acceptée » sans signal UI, l'utilisateur
  /// croyait l'auto-save infaillible. Désormais on persiste l'id de la note
  /// dans un set `vault_lost_drafts` consulté au prochain boot pour afficher
  /// un banner « N notes de coffre ont perdu leurs dernières modifications
  /// (coffre verrouillé pendant la sauvegarde) ».
  ///
  /// Appelé depuis les DEUX issues possibles : coffre déjà fermé au moment
  /// du test, et coffre fermé pendant le chiffrement.
  Future<void> _recordLostDraft(String noteId) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final cur =
          prefs.getStringList(AppConstants.prefKeyVaultLostDrafts) ??
          const <String>[];
      if (!cur.contains(noteId)) {
        await prefs.setStringList(AppConstants.prefKeyVaultLostDrafts, [
          ...cur,
          noteId,
        ]);
      }
    } catch (_) {
      // Best-effort : si la persistance échoue, on retombe sur le silence
      // pré-F11 — pas pire qu'avant.
    }
    if (kDebugMode) {
      debugPrint('flush save (dispose) skipped: vault locked');
    }
  }

  Future<void> _load() async {
    final t = AppLocalizations.of(context);
    try {
      final note = await _repo.get(widget.noteId);
      if (!mounted) return;
      if (note == null) {
        setState(() {
          _loading = false;
          _error = t.noteEditorErrorNotFound;
        });
        return;
      }

      // Note dans un coffre verrouillé : tente le déverrouillage via sheet.
      // Si l'utilisateur annule ou échoue, on ferme l'éditeur (back to home).
      Note resolved = note;
      if (note.isLocked) {
        final vault = context.read<FolderVaultService>();
        if (!vault.isUnlocked(note.folderId)) {
          final folder = await context.read<FoldersRepository>().get(
            note.folderId,
          );
          // `!mounted` ne doit PAS mener a `setState` : l'ecran est deja
          // demonte, et Flutter leve « setState() called after dispose() ».
          // Les deux conditions etaient melangees dans le meme test.
          if (!mounted) return;
          if (folder == null) {
            setState(() {
              _loading = false;
              _error = t.noteEditorErrorVaultFolderMissing;
            });
            return;
          }
          final ok = await showUnlockVaultAdaptive(
            context: context,
            folder: folder,
          );
          if (!mounted) return;
          if (ok != true) {
            // Annulation → retour au HomeScreen sans afficher la note.
            Navigator.of(context).pop();
            return;
          }
        }
        _wasLocked = true;
        // F8 v1.0.9 — Pose FLAG_SECURE AVANT le déchiffrement. Avant :
        // fenêtre ~5-20 ms (MethodChannel round-trip) entre `decryptNote`
        // et `_ensureSecureForced` pendant laquelle un screenshot manuel
        // ou MediaProjection captait le plaintext. Désormais le flag est
        // actif avant tout accès au clair.
        _ensureSecureForced();
        // Vault déverrouillé : déchiffrement éphémère en RAM.
        resolved = await vault.decryptNote(note);
      }

      // Le test de montage vient AVANT de toucher aux controleurs : le
      // dechiffrement ci-dessus est un `await`, l'ecran peut avoir ete
      // demonte entre-temps, et ecrire dans un `TextEditingController`
      // dispose leve.
      if (!mounted) return;
      _titleCtrl.text = resolved.title;
      _contentCtrl.text = resolved.content;
      setState(() {
        _note = resolved;
        _loading = false;
      });
    } on VaultPinWipedException {
      // Coffre auto-détruit après 5 PINs ratés : information cruciale,
      // l'utilisateur doit comprendre pourquoi son contenu a disparu.
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = t.noteEditorErrorVaultWiped;
      });
    } on VaultLockedException {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = t.noteEditorErrorVaultRelocked;
      });
    } catch (e, st) {
      if (!mounted) return;
      // En production : message générique.
      // En debug : type + stack pour diagnostic terrain rapide.
      if (kDebugMode) {
        debugPrint('NoteEditor _load — ${e.runtimeType}: $e\n$st');
      }
      setState(() {
        _loading = false;
        _error = t.noteEditorErrorLoadGeneric;
      });
    }
  }

  void _scheduleSave() => _autosave.run(_saveNow);

  /// Annule l'auto-save en attente puis force un save immédiat. Pattern
  /// utilisé avant toute opération qui doit voir l'état persisté à jour
  /// (move-to-folder, export, copie Markdown, "Terminé", suivi d'un
  /// backlink…). Centralisé pour éviter le `_autosave.cancel()` +
  /// `await _saveNow()` dupliqué 5 fois dans cet écran.
  Future<void> _flushSave() async {
    _autosave.cancel();
    await _saveNow();
  }

  /// Idempotent : si une sauvegarde est déjà en vol, on attend la fin
  /// avant d'en lancer une autre. Évite tout double UPDATE concurrent.
  ///
  /// `while` et non `if` : avec un simple `if`, deux appelants bloqués sur
  /// la MÊME sauvegarde en vol se réveillent ensemble à sa fin et partent
  /// tous les deux en écriture — la sérialisation ne tenait que pour un
  /// seul attendeur. Pour une note de coffre, cela signifiait deux
  /// `encryptNote` concurrents sur la même ligne.
  Future<void> _saveNow() async {
    while (_pendingSave != null) {
      await _pendingSave;
    }
    // L'attente ci-dessus peut durer : l'ecran a pu etre demonte pendant
    // qu'une sauvegarde concurrente se terminait. Sans ce test, on lisait
    // des controleurs disposes et on ecrivait dans `_savingNotifier`.
    if (!mounted) return;
    final current = _note;
    if (current == null) return;
    final title = _titleCtrl.text;
    final content = _contentCtrl.text;
    if (title == current.title && content == current.content) return;

    _savingNotifier.value = true;
    final future = _doSave(current, title, content);
    _pendingSave = future;
    try {
      await future;
    } finally {
      if (identical(_pendingSave, future)) _pendingSave = null;
    }
  }

  Future<void> _doSave(Note current, String title, String content) async {
    final t = AppLocalizations.of(context);
    try {
      // Note du coffre : ré-encrypte le contenu AVANT persistance pour
      // garder l'invariant « toujours chiffré au repos ». Si l'auto-lock
      // a fermé la session entre-temps, `vault.encryptNote` lève
      // `VaultLockedException` — on intercepte et on alerte l'utilisateur
      // sans écrire le contenu en clair dans la DB.
      Note toSave = current.copyWith(title: title, content: content);
      if (_wasLocked) {
        final vault = context.read<FolderVaultService>();
        if (!vault.isUnlocked(toSave.folderId)) {
          if (!mounted) return;
          _savingNotifier.value = false;
          _showError(t.noteEditorErrorVaultRelockedDuringEdit);
          _closeOnVaultLock();
          return;
        }
        toSave = await vault.encryptNote(toSave);
        // Marque l'activité côté vault pour décaler l'auto-lock.
        vault.touchActivity(toSave.folderId);
      }
      final saved = await _repo.save(toSave);
      if (!mounted) return;
      // Pour l'éditeur, on conserve la version EN CLAIR en mémoire
      // (titre + content) pour permettre la suite de l'édition. Le
      // « saved » qu'on reçoit est la version chiffrée pour le coffre,
      // mais l'éditeur a besoin de l'état déchiffré en RAM.
      _note = _wasLocked
          ? saved.copyWith(content: content, clearEncrypted: true)
          : saved;
      _savingNotifier.value = false;
      // A11y v1.0 : annonce TalkBack throttlée 5s pour ne pas saturer.
      final now = DateTime.now();
      if (_lastSavedAnnounce == null ||
          now.difference(_lastSavedAnnounce!).inSeconds >= 5) {
        _lastSavedAnnounce = now;
        unawaited(
          // ignore: deprecated_member_use
          SemanticsService.announce(
            t.noteEditorAnnounceSavedSuccess,
            TextDirection.ltr,
          ),
        );
      }
    } on ValidationException catch (e) {
      if (!mounted) return;
      _savingNotifier.value = false;
      final code = e.code;
      _showError(code != null ? code.localize(t) : t.commonErrorWith('$e'));
    } catch (_) {
      if (!mounted) return;
      _savingNotifier.value = false;
      _showError(t.noteEditorErrorSaveFailed);
    }
  }

  void _showError(String msg) {
    context.showErrorSnack(msg);
  }

  Future<void> _togglePin() async {
    final n = _note;
    if (n == null) return;
    final updated = await _repo.togglePin(n);
    if (mounted) setState(() => _note = updated);
  }

  Future<void> _toggleFavorite() async {
    final n = _note;
    if (n == null) return;
    final updated = await _repo.toggleFavorite(n);
    if (mounted) setState(() => _note = updated);
  }

  /// Bouton « Terminé » : flush l'auto-save courant + pop l'écran.
  /// L'auto-save garantit déjà la persistance, mais ce bouton donne un
  /// signal explicite de fin d'édition aux utilisateurs qui cherchent
  /// un équivalent au "Save" classique.
  ///
  /// Cas spécial coffre re-verrouillé : si la note vient d'un coffre
  /// auto-locké pendant l'édition, `_doSave` abandonne l'écriture
  /// (jamais de clair au repos) et affiche un SnackBar. On ne pop PAS
  /// dans ce cas — l'utilisateur doit voir l'avertissement, sinon il
  /// croit avoir sauvegardé alors que ses modifs sont perdues.
  /// Referme l'éditeur et efface le clair de la RAM quand le coffre s'est
  /// reverrouillé pendant l'édition.
  ///
  /// ⚠️ CE GESTE PERD LES MODIFICATIONS NON ENREGISTRÉES, et c'est assumé :
  /// elles n'étaient de toute façon plus enregistrables. Sans clé en RAM,
  /// rien ne peut être chiffré, et les écrire en clair serait exactement la
  /// fuite que le coffre existe pour empêcher.
  ///
  /// Avant, l'écran restait ouvert « pour que l'utilisateur lise le message ».
  /// Le contenu déchiffré restait donc affiché, éditable et en mémoire, alors
  /// que le coffre était cryptographiquement fermé — un téléphone posé sur
  /// une table montrait le contenu d'un coffre verrouillé. Relevé en CRITIQUE
  /// par une relecture externe (Gemini 3.1 Pro).
  ///
  /// Le message reste visible : `ScaffoldMessenger` vit au-dessus de la route
  /// qu'on dépile.
  void _closeOnVaultLock() {
    // ⚠️ IDEMPOTENT, et ce n'est pas du confort. `_doneEditing` appelle
    // `_flushSave`, qui peut DEJA avoir referme l'ecran ici ; au retour,
    // `_note` vaut null, `isUnlocked('')` repond faux, et la fermeture etait
    // rejouee — un second `pop()` depilant la route PRECEDENTE. Exactement la
    // regression « contrat change sans verifier les appelants ». Relevee par
    // une relecture externe (GPT-5.5) sur ce correctif meme.
    if (_fermeSurVerrouillage) return;
    _fermeSurVerrouillage = true;
    _autosave.cancel();
    _titleCtrl.clear();
    _contentCtrl.clear();
    _note = null;
    if (!mounted) return;
    final nav = Navigator.of(context);
    if (nav.canPop()) nav.pop();
  }

  Future<void> _doneEditing() async {
    await _flushSave();
    if (!mounted) return;
    if (_wasLocked && !_vault.isUnlocked(_note?.folderId ?? '')) {
      // Save abandonnée : le coffre s'est refermé. On ne laisse pas l'écran
      // ouvert sur du clair — voir `_closeOnVaultLock`.
      _closeOnVaultLock();
      return;
    }
    // v1.1.4 (B2) — feedback haptique sur le "Terminé" explicite.
    // L'auto-save (debounce 500 ms) n'a pas d'haptique pour ne pas saturer
    // pendant la frappe ; le tap "Terminé" est lui un signal de fin clair.
    unawaited(HapticFeedback.mediumImpact());
    Navigator.of(context).pop();
  }

  Future<void> _moveToTrash() async {
    final n = _note;
    if (n == null) return;
    await _flushSave();
    // `_flushSave` peut avoir REFERME l'ecran si le coffre s'est verrouille.
    // Sans ce test on mettait la note a la corbeille puis on depilait une
    // SECONDE route — celle d'en dessous. Meme motif que dans `_doneEditing`.
    if (_fermeSurVerrouillage || !mounted) return;
    await _repo.moveToTrash(n);
    if (!mounted) return;
    Navigator.of(context).pop();
  }

  Future<void> _copyMarkdown() async {
    final n = _note;
    if (n == null) return;
    final t = AppLocalizations.of(context);
    // U1 v1.1.0 — feedback haptique sur copy (selectionClick = action
    // utilisateur réussie). Aligné Pass Tech v2.4.4 U9 / AI Tech U4.
    await HapticFeedback.selectionClick();
    await NoteActions.instance.copyMarkdown(n);
    if (!mounted) return;
    context.showFloatingSnack(t.noteEditorCopiedToClipboard);
  }

  /// Exporte la note courante en fichier Markdown (`.md`) avec frontmatter
  /// YAML, puis ouvre le sheet de partage Android (Drive, mail, USB, etc.).
  /// Le fichier est écrit dans `getTemporaryDirectory()` — Android le purge
  /// automatiquement, on n'a pas à le supprimer nous-mêmes.
  Future<void> _exportMarkdown() async {
    final n = _note;
    if (n == null) return;
    final messenger = ScaffoldMessenger.of(context);
    final cs = Theme.of(context).colorScheme; // snack d'erreur post-await
    final t = AppLocalizations.of(context);
    // Flush avant export pour ne pas exporter une version stale du contenu.
    await _flushSave();
    if (_fermeSurVerrouillage) return;
    final fresh = await _repo.get(n.id);
    if (fresh == null || !mounted) return;
    final folder = await context.read<FoldersRepository>().get(fresh.folderId);
    if (!mounted) return;
    // C1 — `_repo.get` rend la ligne DB BRUTE : pour une note de coffre,
    // `content` est vide (le clair vit dans `encryptedContent`, cf.
    // `FolderVaultService.encryptNote` qui persiste `content: ''`). Exporter
    // `fresh` tel quel produisait un .md au frontmatter correct mais au CORPS
    // VIDE — perte de données silencieuse. On redéchiffre en RAM, comme
    // `_load` le fait à l'ouverture. Si l'auto-lock a fermé la session
    // entre-temps, on abandonne l'export plutôt que d'écrire un fichier vide.
    try {
      // Le déchiffrement est DANS le try : entre `isUnlocked` et
      // `decryptNote` l'auto-lock peut fermer la session (course de quelques
      // ms), et `decryptNote` lève alors `VaultLockedException`.
      Note exported = fresh;
      if (fresh.isLocked) {
        if (!_vault.isUnlocked(fresh.folderId)) {
          _showError(t.noteEditorErrorVaultRelockedDuringEdit);
          return;
        }
        exported = await _vault.decryptNote(fresh);
        if (!mounted) return;
      }
      const exporter = NoteExportService();
      // Jumeau de l'export ZIP : quand le contenu vient d'un coffre
      // déverrouillé, le fichier porte le suffixe ` [unlocked]` et le
      // frontmatter une mention d'origine. Le `.md` unitaire sortait le
      // clair sans aucun de ces deux signaux — celui qui reçoit le fichier
      // ne pouvait pas savoir qu'il tenait le contenu d'un coffre.
      final fromVault = fresh.isLocked;
      final bytes = exporter.exportNoteAsBytes(
        exported,
        folder: folder,
        vaultMention: fromVault
            ? t.exportNoteFromVault(folder?.name ?? fresh.folderId)
            : null,
      );
      final fileName = exporter.safeFileName(
        exported.title,
        fallbackId: exported.id,
        unlockedVaultSuffix: fromVault,
      );
      final tmpDir = await getTemporaryDirectory();
      final file = File('${tmpDir.path}/$fileName');
      await file.writeAsBytes(bytes, flush: true);
      if (!mounted) return;
      try {
        // share_plus 10.x : `Share.shareXFiles` (l'API SharePlus.instance
        // existe à partir de v11). Quand on bump le package, basculer.
        await Share.shareXFiles(
          [XFile(file.path)],
          subject: 'Note Notes Tech',
          text: 'Note exportée depuis Notes Tech',
        );
      } finally {
        // ⚠️ SUPPRESSION DIFFÉRÉE, PAS IMMÉDIATE.
        //
        // `shareXFiles` rend la main quand la feuille de partage se ferme —
        // pas quand l'application destinataire a fini de LIRE le fichier.
        // Un client mail ou une messagerie ouvre l'URI quelques instants
        // après ; supprimer sur-le-champ faisait échouer le partage de façon
        // aléatoire, selon la vitesse de l'appareil. Relevé par une relecture
        // externe (Gemini 3.1 Pro).
        //
        // 30 s laissent largement le temps au destinataire, et le fichier ne
        // survit pas : le boot purge `exports/` et le mode panique aussi.
        // Fire-and-forget : on ne fait pas attendre l'utilisateur pour un
        // ménage.
        unawaited(
          Future<void>.delayed(const Duration(seconds: 30), () async {
            try {
              if (await file.exists()) await file.delete();
            } catch (_) {
              /* best-effort — le boot suivant purge */
            }
          }),
        );
      }
    } catch (e) {
      if (!mounted) return;
      messenger.showErrorSnack(t.noteEditorExportFailed(e.toString()), cs);
    }
  }

  /// Ouvre le bottom sheet de sélection de dossier. Si un autre dossier
  /// est choisi, persiste la note avec son nouveau `folderId` et flush
  /// l'auto-save courant pour ne pas écraser la modification.
  Future<void> _moveToFolder() async {
    final n = _note;
    if (n == null) return;
    final messenger = ScaffoldMessenger.of(context);
    final cs = Theme.of(context).colorScheme; // snacks post-await
    final t = AppLocalizations.of(context);
    final foldersRepo = context.read<FoldersRepository>();
    final targetId = await showMoveToFolderSheet(
      context: context,
      currentFolderId: n.folderId,
    );
    if (targetId == null || targetId == n.folderId || !mounted) return;

    final targetFolder = await foldersRepo.get(targetId);
    if (targetFolder == null || !mounted) return;

    // Si la destination est un coffre verrouillé, on demande la
    // passphrase AVANT de toucher au contenu — sinon on aurait flush
    // une note en clair dans une DB cassée vis-à-vis du coffre.
    if (targetFolder.isVault && !_vault.isUnlocked(targetId)) {
      final ok = await showUnlockVaultAdaptive(
        context: context,
        folder: targetFolder,
      );
      if (ok != true || !mounted) return;
    }

    // F1 v1.1.0 — Avant : un déplacement note vault → dossier ordinaire
    // persistait silencieusement le plaintext en DB SQLCipher (avec
    // `encrypted_content` purgé), action irréversible sans signal UI.
    // L'auto-lock d'un coffre pendant cette mutation rendait le flush
    // invisible. Désormais : confirmation EXPLICITE via dialog destructif
    // (cs.errorContainer + Cancel autofocus) si on quitte un coffre.
    // ⚠️ Testait `n.encryptedContent != null` — condition TOUJOURS fausse :
    // `_load` remplace `_note` par l'éphémère déchiffrée
    // (`clearEncrypted: true`), donc `encryptedContent` est nul dès que la
    // note est ouverte. Le dialog destructif « vous sortez cette note du
    // coffre » ne s'affichait donc JAMAIS, et la note était réécrite en
    // clair sans consentement explicite — exactement ce que le fix F1
    // v1.1.0 voulait empêcher. `_wasLocked` est le signal survivant au
    // déchiffrement.
    final isVaultExit = _wasLocked && !targetFolder.isVault;
    if (isVaultExit) {
      final cs = Theme.of(context).colorScheme;
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          icon: Icon(Icons.lock_open, color: cs.error, size: 28),
          title: Text(t.noteEditorExitVaultTitle),
          content: Text(t.noteEditorExitVaultBody),
          actions: [
            TextButton(
              autofocus: true,
              onPressed: () => Navigator.pop(ctx, false),
              child: Text(t.commonCancel),
            ),
            FilledButton.tonal(
              style: FilledButton.styleFrom(
                backgroundColor: cs.errorContainer,
                foregroundColor: cs.onErrorContainer,
              ),
              onPressed: () => Navigator.pop(ctx, true),
              child: Text(t.noteEditorExitVaultConfirm),
            ),
          ],
        ),
      );
      if (confirmed != true || !mounted) return;
    }

    // Flush avant la mutation pour ne pas perdre les éditions en cours.
    await _flushSave();
    if (!mounted) return;
    final current = _note;
    if (current == null) return;

    // On reconstruit la version cible à partir du contenu EN CLAIR
    // détenu en RAM (titre/contenu via les controllers) et on purge
    // toujours `encryptedContent` source — soit on ré-encrypte avec
    // la KEK cible (vault → vault, ou clair → vault), soit on persiste
    // en clair (vault → clair).
    final plainTitle = _titleCtrl.text;
    final plainContent = _contentCtrl.text;
    Note candidate = current.copyWith(
      folderId: targetId,
      title: plainTitle,
      content: plainContent,
      clearEncrypted: true,
    );

    try {
      if (targetFolder.isVault) {
        candidate = await _vault.encryptNote(candidate);
        _vault.touchActivity(targetId);
      }
      final saved = await _repo.save(candidate);
      if (!mounted) return;
      setState(() {
        _wasLocked = targetFolder.isVault;
        _note = _wasLocked
            ? saved.copyWith(content: plainContent, clearEncrypted: true)
            : saved;
      });
      // v1.0.7 UI I1 — déplacer une note vers un coffre expose son
      // plaintext en RAM dans l'éditeur ; force le flag jusqu'au dispose.
      if (_wasLocked) _ensureSecureForced();
      messenger.showSuccessSnack(t.noteEditorMoved, cs);
    } catch (e) {
      if (!mounted) return;
      messenger.showErrorSnack(t.noteEditorMoveFailed(e.toString()), cs);
    }
  }

  // ---------------------------------------------------------------------
  // Backlinks `[[Titre]]`
  // ---------------------------------------------------------------------

  /// Ouvre la bottom sheet d'auto-complétion et insère `[[Titre]]` à la
  /// position du curseur du contenu. Crée la note cible si nécessaire.
  Future<void> _insertLink() async {
    if (_note == null) return;
    final service = context.read<BacklinksService>();
    final result = await showLinkAutocompleteSheet(
      context: context,
      service: service,
      excludeNoteId: _note?.id,
    );
    if (result == null || !mounted) return;

    String title = result.title;
    if (result.isCreate) {
      // Crée la note dans la même boîte que celle en cours.
      final created = await _createSibling(title);
      if (created == null || !mounted) return;
      title = created.title;
    }
    _insertAtCursor('[[$title]]');
    _scheduleSave();
  }

  /// Crée une note « sœur » dans le dossier de la note courante, en
  /// respectant l'invariant de coffre.
  ///
  /// ⚠️ Jumeau de `HomeScreen._createNote` : quand le dossier cible est un
  /// coffre, la note fraîchement créée DOIT être chiffrée immédiatement,
  /// avant d'être ouverte. Sans ça, elle naît avec `encryptedContent ==
  /// null` ; l'éditeur qui l'ouvre évalue `isLocked` à faux, laisse
  /// `_wasLocked` à faux, et TOUT ce que l'utilisateur tape ensuite est
  /// auto-sauvegardé **en clair** dans un dossier coffre. Les deux chemins
  /// de création de l'éditeur (lien fantôme et auto-complétion) passaient
  /// à côté de cette garde que l'écran d'accueil applique depuis v0.8.
  ///
  /// Retourne `null` si la création doit être abandonnée — l'appelant ne
  /// doit alors ni insérer de lien ni naviguer.
  Future<Note?> _createSibling(String title) async {
    final messenger = ScaffoldMessenger.of(context);
    final cs = Theme.of(context).colorScheme; // snack post-await
    final t = AppLocalizations.of(context);
    final foldersRepo = context.read<FoldersRepository>();
    final folderId = _note?.folderId ?? AppConstants.inboxFolderId;
    final folder = await foldersRepo.get(folderId);
    if (!mounted) return null;

    final created = await _repo.create(folderId: folderId, title: title);
    if (folder == null || !folder.isVault) return created;

    // ⚠️ NE PAS RE-CHIFFRER UNE NOTE DÉJÀ SCELLÉE. Depuis que le repository
    // scelle avant de persister, `create` dans un coffre revient déjà avec
    // son blob, `title` et `content` vidés. La rechiffrer packerait deux
    // chaînes VIDES et écraserait le blob : le titre serait perdu, sans la
    // moindre erreur pour le signaler.
    if (created.encryptedContent != null) {
      _vault.touchActivity(folderId);
      return created;
    }

    try {
      final encrypted = await _vault.encryptNote(created);
      await _repo.save(encrypted);
      _vault.touchActivity(folderId);
      return encrypted;
    } catch (e) {
      // Le coffre s'est refermé entre la création et le chiffrement. On
      // SUPPRIME la note neuve plutôt que de laisser une ligne non
      // chiffrée dans un dossier coffre — elle est vide, rien à perdre.
      try {
        await _repo.deletePermanently(created.id);
      } catch (_) {
        /* best-effort */
      }
      if (!mounted) return null;
      messenger.showErrorSnack(t.homeVaultCreateError(e.toString()), cs);
      return null;
    }
  }

  /// Insère un texte transcrit par la voix au curseur. Ajoute un espace
  /// devant si le caractère précédent n'est pas déjà un séparateur, pour
  /// éviter de coller la dictée à un mot précédent. Schedule un save pour
  /// que le texte soit auto-persisté.
  void _insertTranscribedText(String text) {
    final clean = text.trim();
    if (clean.isEmpty) return;
    final ctrl = _contentCtrl;
    final value = ctrl.text;
    final sel = ctrl.selection;
    final start = sel.start >= 0 ? sel.start : value.length;
    final needsLeadingSpace =
        start > 0 && !RegExp(r'[\s\n]$').hasMatch(value.substring(0, start));
    final toInsert = (needsLeadingSpace ? ' ' : '') + clean;
    _insertAtCursor(toInsert);
    _scheduleSave();
  }

  void _insertAtCursor(String text) {
    final ctrl = _contentCtrl;
    final sel = ctrl.selection;
    final value = ctrl.text;
    final start = sel.start >= 0 ? sel.start : value.length;
    final end = sel.end >= 0 ? sel.end : value.length;
    final updated = value.replaceRange(start, end, text);
    ctrl.value = TextEditingValue(
      text: updated,
      selection: TextSelection.collapsed(offset: start + text.length),
    );
  }

  /// Ouvre la note ciblée par un backlink (id résolu).
  Future<void> _openLinkedNote(String noteId) async {
    if (noteId == widget.noteId) return; // self-link, no-op
    // Flush avant de naviguer pour ne pas perdre les modifs.
    await _flushSave();
    // Ne pas empiler une route sur un ecran qu'on vient de refermer.
    if (_fermeSurVerrouillage || !mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => NoteEditorScreen(noteId: noteId)),
    );
  }

  /// Lien fantôme tapé : on propose de créer la note cible avec ce titre,
  /// puis de l'ouvrir directement.
  Future<void> _createFromDangling(NoteLink link) async {
    // Passe par `_createSibling` : le lien fantôme créait la note SANS la
    // chiffrer, même dans un coffre (cf. doc de `_createSibling`).
    final created = await _createSibling(link.targetTitle);
    if (created == null || !mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => NoteEditorScreen(noteId: created.id),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final t = AppLocalizations.of(context);
    if (_loading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (_error != null || _note == null) {
      return Scaffold(
        appBar: AppBar(),
        body: Center(child: Text(_error ?? t.noteEditorErrorNotFound)),
      );
    }
    final note = _note!;

    return Scaffold(
      appBar: AppBar(
        title: ValueListenableBuilder<bool>(
          valueListenable: _savingNotifier,
          builder: (_, saving, _) => Semantics(
            // liveRegion : TalkBack annonce le changement d'état
            // (Enregistrement… → Enregistré) sans que l'utilisateur
            // doive explorer l'AppBar — confort journalistes/seniors.
            liveRegion: true,
            label: saving ? t.noteEditorSaving : t.noteEditorSaved,
            child: Row(
              children: [
                if (saving)
                  const SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                else
                  ExcludeSemantics(
                    child: Icon(
                      Icons.cloud_done_outlined,
                      size: 16,
                      color: theme.iconTheme.color,
                    ),
                  ),
                const SizedBox(width: 8),
                Text(
                  saving ? t.noteEditorSaving : t.noteEditorSaved,
                  style: theme.textTheme.bodySmall,
                ),
              ],
            ),
          ),
        ),
        actions: [
          IconButton(
            tooltip: note.pinned ? t.homeUnpin : t.noteEditorTooltipPin,
            icon: Icon(note.pinned ? Icons.push_pin : Icons.push_pin_outlined),
            onPressed: _togglePin,
          ),
          IconButton(
            tooltip: note.favorite ? t.homeUnfav : t.noteEditorTooltipFav,
            icon: Icon(note.favorite ? Icons.star : Icons.star_outline),
            onPressed: _toggleFavorite,
          ),
          IconButton(
            tooltip: t.noteEditorTooltipInsertLink,
            icon: const Icon(Icons.link),
            onPressed: _insertLink,
          ),
          VoiceRecordButton(onInsert: _insertTranscribedText),
          // Bouton « Terminé » explicite — l'auto-save garantit déjà la
          // persistance, mais sans bouton visible, l'utilisateur ne sait
          // pas qu'il peut quitter sans risque (cf. retour user 2026-05-06).
          // Force un flush + pop pour les gens qui veulent un signal
          // explicite de fin d'édition.
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 8),
            child: Tooltip(
              message: t.noteEditorTooltipDone,
              child: FilledButton.tonalIcon(
                onPressed: _doneEditing,
                icon: const Icon(Icons.check, size: 18),
                label: Text(t.noteEditorTooltipDone),
                style: FilledButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                ),
              ),
            ),
          ),
          PopupMenuButton<String>(
            tooltip: t.noteEditorTooltipMore,
            onSelected: (v) {
              switch (v) {
                case 'move':
                  _moveToFolder();
                case 'export':
                  _exportMarkdown();
                case 'copy':
                  _copyMarkdown();
                case 'trash':
                  _moveToTrash();
              }
            },
            itemBuilder: (_) => [
              PopupMenuItem(
                value: 'move',
                child: ListTile(
                  leading: const Icon(Icons.drive_file_move_outline),
                  title: Text(t.noteEditorMenuMove),
                ),
              ),
              PopupMenuItem(
                value: 'export',
                child: ListTile(
                  leading: const Icon(Icons.file_download_outlined),
                  title: Text(t.noteEditorMenuExport),
                ),
              ),
              PopupMenuItem(
                value: 'copy',
                child: ListTile(
                  leading: const Icon(Icons.content_copy),
                  title: Text(t.noteEditorMenuCopyMarkdown),
                ),
              ),
              PopupMenuItem(
                value: 'trash',
                child: ListTile(
                  leading: const Icon(Icons.delete_outline),
                  title: Text(t.noteEditorMenuTrash),
                ),
              ),
            ],
          ),
        ],
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 8),
              TextField(
                controller: _titleCtrl,
                onChanged: (_) => _scheduleSave(),
                textInputAction: TextInputAction.next,
                enableSuggestions: false,
                autocorrect: false,
                // U3 v1.1.0 — capitalisation auto sur titre (saisie tactile
                // mémoire à doigt unique sans Shift). Aligné AI Tech U7.
                textCapitalization: TextCapitalization.sentences,
                style: theme.textTheme.titleLarge,
                decoration: InputDecoration(
                  labelText: t.noteEditorTitle,
                  hintText: t.noteEditorTitle,
                  border: InputBorder.none,
                  enabledBorder: InputBorder.none,
                  focusedBorder: InputBorder.none,
                  filled: false,
                  contentPadding: const EdgeInsets.symmetric(vertical: 8),
                ),
                inputFormatters: [
                  LengthLimitingTextInputFormatter(
                    AppConstants.noteTitleMaxLength,
                  ),
                ],
              ),
              Divider(color: theme.dividerColor, height: 1),
              Expanded(
                child: TextField(
                  controller: _contentCtrl,
                  onChanged: (_) => _scheduleSave(),
                  enableSuggestions: false,
                  autocorrect: false,
                  // U3 v1.1.0 — capitalisation auto sur contenu (Markdown
                  // est principalement de la prose).
                  textCapitalization: TextCapitalization.sentences,
                  maxLines: null,
                  expands: true,
                  textAlignVertical: TextAlignVertical.top,
                  keyboardType: TextInputType.multiline,
                  style: theme.textTheme.bodyLarge,
                  decoration: InputDecoration(
                    labelText: t.noteEditorContent,
                    hintText: t.noteEditorContentHint,
                    border: InputBorder.none,
                    enabledBorder: InputBorder.none,
                    focusedBorder: InputBorder.none,
                    filled: false,
                    contentPadding: const EdgeInsets.symmetric(vertical: 12),
                  ),
                ),
              ),
              BacklinksPanel(
                note: note,
                onOpenNoteId: _openLinkedNote,
                onTapDangling: _createFromDangling,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
