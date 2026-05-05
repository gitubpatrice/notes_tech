import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/data/models/note.dart';
import 'package:notes_tech/services/ai/rag_service.dart';
import 'package:notes_tech/services/semantic_search_service.dart';

class _StubSearch implements SemanticSearchService {
  _StubSearch(this._hits);
  final List<SemanticHit> _hits;

  @override
  Future<List<SemanticHit>> search(
    String query, {
    int limit = 50,
    double minScore = 0.05,
  }) async =>
      _hits;

  @override
  noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

Note _note({required String id, required String title, required String body}) {
  final now = DateTime(2026, 1, 1);
  return Note(
    id: id,
    title: title,
    content: body,
    folderId: 'inbox',
    createdAt: now,
    updatedAt: now,
  );
}

void main() {
  group('RagService', () {
    test('aucune note → prompt explicite "rien trouvé"', () async {
      final rag = RagService(search: _StubSearch(const []));
      final ctx = await rag.build('quoi que ce soit');
      expect(ctx.sources, isEmpty);
      expect(ctx.systemPrompt, contains('Aucune note pertinente'));
    });

    test('texte vide → pas d\'appel search, sources vides', () async {
      final rag = RagService(search: _StubSearch(const []));
      final ctx = await rag.build('   ');
      expect(ctx.sources, isEmpty);
      expect(ctx.userPrompt, '');
    });

    test('cap par note appliqué dans le prompt', () async {
      final long = 'a' * 5000;
      final hit = SemanticHit(
        note: _note(id: 'n1', title: 'Long', body: long),
        score: 0.9,
      );
      final rag = RagService(search: _StubSearch([hit]));
      final ctx = await rag.build('résume');
      // Le prompt système ne doit pas dépasser les 5000 chars de "long" :
      // il est tronqué à _perNoteCharCap (1000) + suffixe.
      expect(ctx.systemPrompt.length, lessThan(2000));
      expect(ctx.systemPrompt, contains('Note 1 : Long'));
    });

    test('composePrompt inclut question + system', () async {
      final hit = SemanticHit(
        note: _note(id: 'n1', title: 'Test', body: 'corps'),
        score: 0.8,
      );
      final rag = RagService(search: _StubSearch([hit]));
      final ctx = await rag.build('combien');
      final prompt = rag.composePrompt(ctx);
      expect(prompt, contains('Note 1 : Test'));
      expect(prompt, contains('Question : combien'));
    });
  });
}
