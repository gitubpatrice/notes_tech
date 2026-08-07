/// Recherche plein-texte (FTS5).
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
import '../../l10n/app_localizations.dart';
import '../../utils/debouncer.dart';
import '../widgets/empty_state.dart';
import '../widgets/note_card.dart';
import 'note_editor_screen.dart';

class SearchScreen extends StatefulWidget {
  const SearchScreen({super.key});

  @override
  State<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends State<SearchScreen> {
  late final NotesRepository _notes;
  final _ctrl = TextEditingController();
  final _debouncer = Debouncer(AppConstants.searchDebounce);

  String _query = '';
  Future<List<Note>>? _future;

  @override
  void initState() {
    super.initState();
    _notes = context.read<NotesRepository>();
  }

  @override
  void dispose() {
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

  void _runSearch() {
    if (_query.isEmpty) {
      setState(() => _future = null);
      return;
    }
    setState(() => _future = _runFts());
  }

  Future<List<Note>> _runFts() => _notes.search(_query);

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(
        title: TextField(
          controller: _ctrl,
          autofocus: true,
          onChanged: _onChanged,
          enableSuggestions: false,
          autocorrect: false,
          textInputAction: TextInputAction.search,
          decoration: InputDecoration(
            labelText: t.searchTitle,
            hintText: t.searchHint,
            border: InputBorder.none,
            enabledBorder: InputBorder.none,
            focusedBorder: InputBorder.none,
            filled: false,
          ),
        ),
      ),
      body: SafeArea(
        child: Column(children: [Expanded(child: _buildResults(t))]),
      ),
    );
  }

  Widget _buildResults(AppLocalizations t) {
    final f = _future;
    if (f == null) {
      return EmptyState(
        icon: Icons.search,
        title: t.searchEmptyTitle,
        subtitle: t.searchEmptySubtitleFts,
      );
    }
    return FutureBuilder<List<Note>>(
      future: f,
      builder: (context, snap) {
        if (snap.connectionState == ConnectionState.waiting) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snap.hasError) {
          return Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Text(t.searchErrorGeneric),
            ),
          );
        }
        final results = snap.data ?? const [];
        if (results.isEmpty) {
          return EmptyState(icon: Icons.search_off, title: t.searchEmpty);
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
                MaterialPageRoute<void>(
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
