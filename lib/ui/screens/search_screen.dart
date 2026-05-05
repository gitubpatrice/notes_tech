/// Recherche plein-texte (FTS5) ou par similarité (embeddings locaux).
///
/// Toggle utilisateur : `Mots exacts` / `Similaires`. La barre supérieure
/// affiche un compteur du nombre de notes indexées en mode similarité.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../core/constants.dart';
import '../../data/models/note.dart';
import '../../data/repositories/notes_repository.dart';
import '../../services/indexing_service.dart';
import '../../services/semantic_search_service.dart';
import '../../utils/debouncer.dart';
import '../widgets/empty_state.dart';
import '../widgets/note_card.dart';
import 'note_editor_screen.dart';

enum _SearchMode { fts, semantic }

class SearchScreen extends StatefulWidget {
  const SearchScreen({super.key});

  @override
  State<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends State<SearchScreen> {
  late final NotesRepository _notes;
  late final SemanticSearchService _semantic;
  late final IndexingService _indexing;
  late final StreamSubscription<void> _indexSub;
  final _ctrl = TextEditingController();
  final _debouncer = Debouncer(AppConstants.searchDebounce);

  _SearchMode _mode = _SearchMode.fts;
  String _query = '';
  Future<List<Note>>? _future;

  @override
  void initState() {
    super.initState();
    _notes = context.read<NotesRepository>();
    _semantic = context.read<SemanticSearchService>();
    _indexing = context.read<IndexingService>();
    _indexSub = _indexing.changes.listen((_) {
      _semantic.invalidateCache();
      if (mounted && _mode == _SearchMode.semantic && _query.isNotEmpty) {
        _runSearch();
      }
    });
  }

  @override
  void dispose() {
    _indexSub.cancel();
    _debouncer.dispose();
    _ctrl.dispose();
    super.dispose();
  }

  void _onChanged(String value) {
    _debouncer.run(() {
      if (!mounted) return;
      setState(() => _query = value.trim());
      _runSearch();
    });
  }

  void _setMode(_SearchMode mode) {
    if (mode == _mode) return;
    setState(() => _mode = mode);
    if (_query.isNotEmpty) _runSearch();
  }

  void _runSearch() {
    if (_query.isEmpty) {
      setState(() => _future = null);
      return;
    }
    setState(() {
      _future = switch (_mode) {
        _SearchMode.fts => _runFts(),
        _SearchMode.semantic => _runSemantic(),
      };
    });
  }

  Future<List<Note>> _runFts() => _notes.search(_query);

  Future<List<Note>> _runSemantic() async {
    final hits = await _semantic.search(_query);
    return hits.map((h) => h.note).toList(growable: false);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: TextField(
          controller: _ctrl,
          autofocus: true,
          onChanged: _onChanged,
          enableSuggestions: false,
          autocorrect: false,
          textInputAction: TextInputAction.search,
          decoration: const InputDecoration(
            hintText: 'Rechercher…',
            border: InputBorder.none,
            enabledBorder: InputBorder.none,
            focusedBorder: InputBorder.none,
            filled: false,
          ),
        ),
      ),
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
              child: SegmentedButton<_SearchMode>(
                segments: const [
                  ButtonSegment(
                    value: _SearchMode.fts,
                    icon: Icon(Icons.text_fields),
                    label: Text('Mots exacts'),
                  ),
                  ButtonSegment(
                    value: _SearchMode.semantic,
                    icon: Icon(Icons.auto_awesome),
                    label: Text('Similaires'),
                  ),
                ],
                selected: {_mode},
                showSelectedIcon: false,
                onSelectionChanged: (s) => _setMode(s.first),
              ),
            ),
            Expanded(child: _buildResults()),
          ],
        ),
      ),
    );
  }

  Widget _buildResults() {
    final f = _future;
    if (f == null) {
      return EmptyState(
        icon: _mode == _SearchMode.semantic
            ? Icons.auto_awesome
            : Icons.search,
        title: 'Tapez pour rechercher',
        subtitle: _mode == _SearchMode.semantic
            ? 'La recherche par similarité trouve des notes proches '
                "même sans le mot exact."
            : 'Recherche plein texte instantanée et 100% locale.',
      );
    }
    return FutureBuilder<List<Note>>(
      future: f,
      builder: (context, snap) {
        if (snap.connectionState == ConnectionState.waiting) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snap.hasError) {
          return const Center(
              child: Padding(
            padding: EdgeInsets.all(24),
            child: Text('Une erreur est survenue.'),
          ));
        }
        final results = snap.data ?? const [];
        if (results.isEmpty) {
          return const EmptyState(
            icon: Icons.search_off,
            title: 'Aucun résultat',
          );
        }
        return ListView.separated(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 24),
          itemCount: results.length,
          separatorBuilder: (_, _) => const SizedBox(height: 8),
          itemBuilder: (_, i) {
            final n = results[i];
            return NoteCard(
              note: n,
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (_) => NoteEditorScreen(noteId: n.id),
                ),
              ),
            );
          },
        );
      },
    );
  }
}
