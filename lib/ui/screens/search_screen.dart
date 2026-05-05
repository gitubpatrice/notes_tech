/// Recherche plein-texte avancée (FTS5).
///
/// Identique à la barre d'accueil mais en plein écran avec compteur de résultats.
library;

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../core/constants.dart';
import '../../data/models/note.dart';
import '../../data/repositories/notes_repository.dart';
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
  Future<List<Note>>? _future;
  String _query = '';

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
      setState(() {
        _query = value.trim();
        _future = _query.isEmpty ? null : _notes.search(_query);
      });
    });
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
        child: _future == null
            ? const EmptyState(
                icon: Icons.search,
                title: 'Tapez pour rechercher',
                subtitle: 'La recherche est instantanée et 100% locale.',
              )
            : FutureBuilder<List<Note>>(
                future: _future,
                builder: (context, snap) {
                  if (snap.connectionState == ConnectionState.waiting) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  final notes = snap.data ?? const [];
                  if (notes.isEmpty) {
                    return const EmptyState(
                      icon: Icons.search_off,
                      title: 'Aucun résultat',
                    );
                  }
                  return ListView.separated(
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 24),
                    itemCount: notes.length,
                    separatorBuilder: (_, _) => const SizedBox(height: 8),
                    itemBuilder: (_, i) {
                      final n = notes[i];
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
              ),
      ),
    );
  }
}
