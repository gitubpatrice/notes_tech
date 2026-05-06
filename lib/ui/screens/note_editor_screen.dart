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
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import 'package:provider/provider.dart';
import 'package:share_plus/share_plus.dart';

import '../../core/constants.dart';
import '../../core/exceptions.dart';
import '../../data/models/note.dart';
import '../../data/models/note_link.dart';
import '../../data/repositories/folders_repository.dart';
import '../../data/repositories/notes_repository.dart';
import '../../services/backlinks_service.dart';
import '../../services/export/note_export_service.dart';
import '../../services/note_actions.dart';
import '../../services/security/folder_vault_service.dart';
import '../../utils/debouncer.dart';
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

  Note? _note;
  bool _loading = true;
  bool _stale = false; // note supprimée / mise en corbeille → édition désactivée
  String? _error;
  Future<void>? _pendingSave;

  /// `true` si la note vient d'un dossier coffre. `_note` ci-dessus est
  /// l'éphémère déchiffrée (content rempli, encryptedContent null) — on
  /// retient cette information pour que `_doSave` ré-encrypte avant
  /// persistance, gardant le modèle « toujours chiffré au repos ».
  bool _wasLocked = false;

  @override
  void initState() {
    super.initState();
    _repo = context.read<NotesRepository>();
    _vault = context.read<FolderVaultService>();
    _load();
    // Si la note est supprimée/mise à la corbeille depuis un autre écran,
    // on désactive l'édition pour éviter de "ressusciter" la note via
    // un save final dans dispose.
    _changesSub = _repo.changes.listen((_) async {
      if (!mounted) return;
      final fresh = await _repo.get(widget.noteId);
      if (!mounted) return;
      if (fresh == null || fresh.isTrashed) {
        setState(() => _stale = true);
      }
    });
  }

  @override
  void dispose() {
    _changesSub?.cancel();
    _autosave.cancel();
    // Save final SEULEMENT si la note est encore valide.
    final n = _note;
    if (n != null && !_stale) {
      final title = _titleCtrl.text;
      final content = _contentCtrl.text;
      if (title != n.title || content != n.content) {
        if (_wasLocked) {
          // Note coffre : ré-encrypter AVANT save. Si la session a
          // expiré, on ABANDONNE la modif plutôt que d'écrire le
          // contenu en clair dans la DB (invariant : jamais clair au
          // repos pour une note de coffre). L'utilisateur a quitté
          // l'écran, pas d'UI pour le signaler — perte acceptée.
          if (_vault.isUnlocked(n.folderId)) {
            final draft = n.copyWith(title: title, content: content);
            unawaited(() async {
              try {
                final encrypted = await _vault.encryptNote(draft);
                await _repo.save(encrypted);
              } catch (e) {
                if (kDebugMode) {
                  debugPrint('flush save (dispose, vault) : $e');
                }
              }
            }());
          } else if (kDebugMode) {
            debugPrint('flush save (dispose) skipped: vault locked');
          }
        } else {
          unawaited(_repo
              .save(n.copyWith(title: title, content: content))
              .catchError((Object e) {
            if (kDebugMode) debugPrint('flush save (dispose) : $e');
            return n;
          }));
        }
      }
    }
    _titleCtrl.dispose();
    _contentCtrl.dispose();
    _savingNotifier.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final note = await _repo.get(widget.noteId);
      if (!mounted) return;
      if (note == null) {
        setState(() {
          _loading = false;
          _error = 'Note introuvable';
        });
        return;
      }

      // Note dans un coffre verrouillé : tente le déverrouillage via sheet.
      // Si l'utilisateur annule ou échoue, on ferme l'éditeur (back to home).
      Note resolved = note;
      if (note.isLocked) {
        final vault = context.read<FolderVaultService>();
        if (!vault.isUnlocked(note.folderId)) {
          final folder = await context
              .read<FoldersRepository>()
              .get(note.folderId);
          if (folder == null || !mounted) {
            setState(() {
              _loading = false;
              _error = 'Dossier coffre introuvable';
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
        // Vault déverrouillé : déchiffrement éphémère en RAM.
        resolved = await vault.decryptNote(note);
        _wasLocked = true;
      }

      _titleCtrl.text = resolved.title;
      _contentCtrl.text = resolved.content;
      if (!mounted) return;
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
        _error =
            'Coffre auto-détruit après trop de tentatives ratées. Les notes du dossier sont définitivement perdues.';
      });
    } on VaultLockedException {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = 'Coffre re-verrouillé. Rouvrez la note pour réessayer.';
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = 'Une erreur est survenue lors du chargement.';
      });
    }
  }

  void _scheduleSave() => _autosave.run(_saveNow);

  /// Idempotent : si une sauvegarde est déjà en vol, on attend la fin
  /// avant d'en lancer une autre. Évite tout double UPDATE concurrent.
  Future<void> _saveNow() async {
    final pending = _pendingSave;
    if (pending != null) {
      await pending;
    }
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
          _showError(
            'Coffre re-verrouillé pendant l\'édition. '
            'Ré-ouvrez la note pour reprendre.',
          );
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
    } on ValidationException catch (e) {
      if (!mounted) return;
      _savingNotifier.value = false;
      _showError(e.message);
    } catch (_) {
      if (!mounted) return;
      _savingNotifier.value = false;
      _showError('Échec de sauvegarde');
    }
  }

  void _showError(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
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

  Future<void> _moveToTrash() async {
    final n = _note;
    if (n == null) return;
    _autosave.cancel();
    await _saveNow();
    await _repo.moveToTrash(n);
    if (!mounted) return;
    Navigator.of(context).pop();
  }

  Future<void> _copyMarkdown() async {
    final n = _note;
    if (n == null) return;
    await const NoteActions().copyMarkdown(n);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Copié dans le presse-papier')),
    );
  }

  /// Exporte la note courante en fichier Markdown (`.md`) avec frontmatter
  /// YAML, puis ouvre le sheet de partage Android (Drive, mail, USB, etc.).
  /// Le fichier est écrit dans `getTemporaryDirectory()` — Android le purge
  /// automatiquement, on n'a pas à le supprimer nous-mêmes.
  Future<void> _exportMarkdown() async {
    final n = _note;
    if (n == null) return;
    final messenger = ScaffoldMessenger.of(context);
    // Flush avant export pour ne pas exporter une version stale du contenu.
    _autosave.cancel();
    await _saveNow();
    final fresh = await _repo.get(n.id);
    if (fresh == null || !mounted) return;
    final folder = await context.read<FoldersRepository>().get(fresh.folderId);
    if (!mounted) return;
    try {
      const exporter = NoteExportService();
      final bytes = exporter.exportNoteAsBytes(fresh, folder: folder);
      final fileName = exporter.safeFileName(
        fresh.title,
        fallbackId: fresh.id,
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
        // Cleanup best-effort du tmp : Android purge cache/tmp à
        // intervalles, mais on évite l'accumulation entre deux purges.
        // Le sheet de partage a déjà reçu le contenu (Intent EXTRA_STREAM
        // copie ou tient une URI ouverte) avant qu'on supprime ici.
        try {
          if (await file.exists()) await file.delete();
        } catch (_) {/* best-effort */}
      }
    } catch (e) {
      if (!mounted) return;
      messenger.showSnackBar(
        SnackBar(
          content: Text('Export impossible : $e'),
          behavior: SnackBarBehavior.floating,
        ),
      );
    }
  }

  /// Ouvre le bottom sheet de sélection de dossier. Si un autre dossier
  /// est choisi, persiste la note avec son nouveau `folderId` et flush
  /// l'auto-save courant pour ne pas écraser la modification.
  Future<void> _moveToFolder() async {
    final n = _note;
    if (n == null) return;
    final messenger = ScaffoldMessenger.of(context);
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

    // Flush avant la mutation pour ne pas perdre les éditions en cours.
    _autosave.cancel();
    await _saveNow();
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
      messenger.showSnackBar(
        const SnackBar(
          content: Text('Note déplacée'),
          behavior: SnackBarBehavior.floating,
        ),
      );
    } catch (e) {
      if (!mounted) return;
      messenger.showSnackBar(
        SnackBar(
          content: Text('Déplacement impossible : $e'),
          behavior: SnackBarBehavior.floating,
        ),
      );
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
      final folderId = _note?.folderId ?? AppConstants.inboxFolderId;
      final created = await _repo.create(folderId: folderId, title: title);
      title = created.title;
    }
    _insertAtCursor('[[$title]]');
    _scheduleSave();
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
    final needsLeadingSpace = start > 0 &&
        !RegExp(r'[\s\n]$').hasMatch(value.substring(0, start));
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
    _autosave.cancel();
    await _saveNow();
    if (!mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => NoteEditorScreen(noteId: noteId)),
    );
  }

  /// Lien fantôme tapé : on propose de créer la note cible avec ce titre,
  /// puis de l'ouvrir directement.
  Future<void> _createFromDangling(NoteLink link) async {
    final folderId = _note?.folderId ?? AppConstants.inboxFolderId;
    final created = await _repo.create(
      folderId: folderId,
      title: link.targetTitle,
    );
    if (!mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => NoteEditorScreen(noteId: created.id),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    if (_loading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (_error != null || _note == null) {
      return Scaffold(
        appBar: AppBar(),
        body: Center(child: Text(_error ?? 'Note introuvable')),
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
            label: saving ? 'Enregistrement en cours' : 'Note enregistrée',
            child: Row(
              children: [
                if (saving)
                  const SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                else
                  Icon(Icons.cloud_done_outlined,
                      size: 16, color: theme.iconTheme.color),
                const SizedBox(width: 8),
                Text(saving ? 'Enregistrement…' : 'Enregistré',
                    style: theme.textTheme.bodySmall),
              ],
            ),
          ),
        ),
        actions: [
          IconButton(
            tooltip: note.pinned ? 'Désépingler' : 'Épingler',
            icon:
                Icon(note.pinned ? Icons.push_pin : Icons.push_pin_outlined),
            onPressed: _togglePin,
          ),
          IconButton(
            tooltip: note.favorite ? 'Retirer des favoris' : 'Favori',
            icon: Icon(note.favorite ? Icons.star : Icons.star_outline),
            onPressed: _toggleFavorite,
          ),
          IconButton(
            tooltip: 'Insérer un lien [[note]]',
            icon: const Icon(Icons.link),
            onPressed: _insertLink,
          ),
          VoiceRecordButton(onInsert: _insertTranscribedText),
          PopupMenuButton<String>(
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
            itemBuilder: (_) => const [
              PopupMenuItem(
                value: 'move',
                child: ListTile(
                  leading: Icon(Icons.drive_file_move_outline),
                  title: Text('Déplacer vers…'),
                ),
              ),
              PopupMenuItem(
                value: 'export',
                child: ListTile(
                  leading: Icon(Icons.file_download_outlined),
                  title: Text('Exporter en Markdown'),
                ),
              ),
              PopupMenuItem(
                value: 'copy',
                child: ListTile(
                  leading: Icon(Icons.content_copy),
                  title: Text('Copier le Markdown'),
                ),
              ),
              PopupMenuItem(
                value: 'trash',
                child: ListTile(
                  leading: Icon(Icons.delete_outline),
                  title: Text('Mettre à la corbeille'),
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
              Semantics(
                label: 'Titre de la note',
                textField: true,
                child: TextField(
                  controller: _titleCtrl,
                  onChanged: (_) => _scheduleSave(),
                  textInputAction: TextInputAction.next,
                  enableSuggestions: false,
                  autocorrect: false,
                  style: theme.textTheme.titleLarge,
                  decoration: const InputDecoration(
                    hintText: 'Titre',
                    border: InputBorder.none,
                    enabledBorder: InputBorder.none,
                    focusedBorder: InputBorder.none,
                    filled: false,
                    contentPadding: EdgeInsets.symmetric(vertical: 8),
                  ),
                  inputFormatters: [
                    LengthLimitingTextInputFormatter(
                        AppConstants.noteTitleMaxLength),
                  ],
                ),
              ),
              Divider(color: theme.dividerColor, height: 1),
              Expanded(
                child: Semantics(
                  label: 'Contenu de la note en Markdown',
                  textField: true,
                  child: TextField(
                    controller: _contentCtrl,
                    onChanged: (_) => _scheduleSave(),
                    enableSuggestions: false,
                    autocorrect: false,
                    maxLines: null,
                    expands: true,
                    textAlignVertical: TextAlignVertical.top,
                    keyboardType: TextInputType.multiline,
                    style: theme.textTheme.bodyLarge,
                    decoration: const InputDecoration(
                      hintText: 'Écrivez en Markdown… ([[Titre]] pour lier)',
                      border: InputBorder.none,
                      enabledBorder: InputBorder.none,
                      focusedBorder: InputBorder.none,
                      filled: false,
                      contentPadding: EdgeInsets.symmetric(vertical: 12),
                    ),
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
