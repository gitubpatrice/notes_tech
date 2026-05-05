/// Écran d'édition d'une note.
///
/// - Édition titre + contenu Markdown brut (preview différée à v0.2).
/// - Auto-save debounced ; flush garanti sur sortie.
/// - Toggle pin / favori / corbeille sans round-trip DB.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../../core/constants.dart';
import '../../core/exceptions.dart';
import '../../data/models/note.dart';
import '../../data/repositories/notes_repository.dart';
import '../../services/note_actions.dart';
import '../../utils/debouncer.dart';

class NoteEditorScreen extends StatefulWidget {
  const NoteEditorScreen({super.key, required this.noteId});
  final String noteId;

  @override
  State<NoteEditorScreen> createState() => _NoteEditorScreenState();
}

class _NoteEditorScreenState extends State<NoteEditorScreen> {
  late final NotesRepository _repo;
  final _titleCtrl = TextEditingController();
  final _contentCtrl = TextEditingController();
  final _autosave = Debouncer(AppConstants.autosaveDebounce);
  final _savingNotifier = ValueNotifier<bool>(false);

  Note? _note;
  bool _loading = true;
  String? _error;
  Future<void>? _pendingSave;

  @override
  void initState() {
    super.initState();
    _repo = context.read<NotesRepository>();
    _load();
  }

  @override
  void dispose() {
    // Annule le timer puis lance un save final non bloquant.
    _autosave.cancel();
    final n = _note;
    if (n != null) {
      // Snapshot synchrone des champs avant dispose des controllers.
      final title = _titleCtrl.text;
      final content = _contentCtrl.text;
      if (title != n.title || content != n.content) {
        _repo.save(n.copyWith(title: title, content: content));
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
      _titleCtrl.text = note.title;
      _contentCtrl.text = note.content;
      setState(() {
        _note = note;
        _loading = false;
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
      final saved =
          await _repo.save(current.copyWith(title: title, content: content));
      if (!mounted) return;
      _note = saved;
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
          builder: (_, saving, _) => Row(
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
          PopupMenuButton<String>(
            onSelected: (v) {
              switch (v) {
                case 'copy':
                  _copyMarkdown();
                case 'trash':
                  _moveToTrash();
              }
            },
            itemBuilder: (_) => const [
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
              TextField(
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
              Divider(color: theme.dividerColor, height: 1),
              Expanded(
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
                    hintText: 'Écrivez en Markdown…',
                    border: InputBorder.none,
                    enabledBorder: InputBorder.none,
                    focusedBorder: InputBorder.none,
                    filled: false,
                    contentPadding: EdgeInsets.symmetric(vertical: 12),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
