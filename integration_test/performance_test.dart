// Mesures de performance SUR APPAREIL, sur un jeu de données réaliste.
//
// Motivation : j'ai passé deux jours à affirmer que telle requête « ne coûte
// rien » sans jamais l'avoir mesurée. Ce fichier remplace l'opinion par des
// chiffres, sur un Galaxy S9 (2018) qui représente le bas de gamme réel du
// parc — si c'est fluide là, c'est fluide partout.
//
// Ce qui est mesuré est choisi, pas exhaustif :
//   - la requête de la liste principale, celle que l'utilisateur déclenche le
//     plus souvent ;
//   - la recherche plein-texte FTS5 ;
//   - les DEUX requêtes que cet audit a ajoutées sur des chemins chauds
//     (`listSemanticIneligibleIds` à chaque recherche sémantique,
//     `listPlaintextInFolder` à chaque ouverture de coffre). J'ai affirmé
//     qu'elles étaient négligeables : ici on vérifie.
//
// Les seuils sont larges à dessein. Ce test n'est pas là pour faire du
// micro-tuning, mais pour détecter une RÉGRESSION d'ordre de grandeur — le
// jour où quelqu'un ajoutera un N+1 dans la liste de notes.
//
// 🔴 Mêmes précautions que `vault_invariant_test.dart` : appareil de test
//    uniquement, `-d <deviceId>` obligatoire, base isolée.
//
//   flutter test integration_test/performance_test.dart -d 22dbb7390a057ece

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:notes_tech/data/db/database.dart';
import 'package:notes_tech/data/db/folders_dao.dart';
import 'package:notes_tech/data/db/notes_dao.dart';
import 'package:notes_tech/data/models/note.dart';
import 'package:notes_tech/data/repositories/folders_repository.dart';
import 'package:sqflite_sqlcipher/sqflite.dart';

/// Volume cible. 1 000 notes est au-delà de ce que la majorité des
/// utilisateurs atteindront, ce qui est exactement l'intérêt : si les temps
/// tiennent ici, ils tiennent pour tout le monde.
const int _kNotes = 1000;

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  late Database db;
  late NotesDao notesDao;
  late String folderId;

  setUpAll(() async {
    AppDatabase.instance.useFileNameOverride('notes_tech_perf_test.db');
    db = await AppDatabase.instance.db;
    notesDao = NotesDao(db);
    final folders = FoldersRepository(FoldersDao(db));
    final f = await folders.create(name: 'perf');
    folderId = f.id;

    // Insertion en batch : c'est le SETUP, pas la mesure. Passer par le
    // repository ferait 1 000 events de changement et fausserait tout.
    final batch = db.batch();
    final now = DateTime.now().millisecondsSinceEpoch;
    for (var i = 0; i < _kNotes; i++) {
      batch.insert('notes', {
        'id': 'perf-$i',
        'title': 'Note numéro $i',
        'content':
            'Contenu de la note $i. Réunion, projet, budget, échéance, '
            'compte rendu, décision, action à mener. Ligne de remplissage '
            'pour donner à l\'index FTS5 de quoi travailler. mot$i',
        'folder_id': folderId,
        'tags': 'travail,perf',
        'pinned': 0,
        'favorite': 0,
        'archived': 0,
        'trashed_at': null,
        'created_at': now - i * 1000,
        'updated_at': now - i * 1000,
        'encrypted_content': null,
        'enc_v': 1,
      });
    }
    await batch.commit(noResult: true);
  });

  /// Exécute [f] plusieurs fois et rend la MÉDIANE : sur un téléphone réel,
  /// une mesure unique attrape n'importe quel pic d'ordonnancement.
  Future<int> medianMs(Future<void> Function() f, {int runs = 5}) async {
    final times = <int>[];
    for (var i = 0; i < runs; i++) {
      final sw = Stopwatch()..start();
      await f();
      sw.stop();
      times.add(sw.elapsedMilliseconds);
    }
    times.sort();
    return times[times.length ~/ 2];
  }

  testWidgets('la liste principale reste fluide à $_kNotes notes', (_) async {
    final ms = await medianMs(
      () => notesDao.listByFolder(folderId, sort: NoteSortMode.updatedDesc),
    );
    debugPrint('PERF listByFolder($_kNotes) : $ms ms');
    expect(
      ms,
      lessThan(400),
      reason:
          'Au-delà, l\'ouverture de l\'application se voit à l\'œil nu. '
          'Un dépassement signale typiquement un N+1 ou un index perdu.',
    );
  });

  testWidgets('la recherche plein-texte reste instantanée', (_) async {
    final ms = await medianMs(() => notesDao.search('budget', limit: 50));
    debugPrint('PERF search FTS5 : $ms ms');
    expect(
      ms,
      lessThan(200),
      reason:
          'La recherche se déclenche à la frappe : au-delà, chaque '
          'caractère tapé accumule du retard.',
    );
  });

  testWidgets('le filtre ajouté à la recherche sémantique est négligeable', (
    _,
  ) async {
    // J'ai affirmé que cette requête « ne coûte rien » en l'ajoutant sur le
    // chemin de chaque recherche sémantique. Vérification.
    final ms = await medianMs(() => notesDao.listSemanticIneligibleIds());
    debugPrint('PERF listSemanticIneligibleIds : $ms ms');
    expect(ms, lessThan(150));
  });

  testWidgets('le balayage de réparation ne pèse pas sur le déverrouillage', (
    _,
  ) async {
    // Tourne à CHAQUE ouverture de coffre. Ici le dossier contient 1 000
    // notes en clair, soit le pire cas imaginable pour cette requête —
    // en usage réel elle ne ramène presque jamais rien.
    final ms = await medianMs(() => notesDao.listPlaintextInFolder(folderId));
    debugPrint('PERF listPlaintextInFolder (pire cas, $_kNotes) : $ms ms');
    expect(
      ms,
      lessThan(500),
      reason:
          'Ce temps s\'ajoute à chaque déverrouillage de coffre, après '
          'les 3-5 s d\'Argon2id. Il doit rester dans le bruit.',
    );
  });

  testWidgets('la pagination de la liste récente est bornée', (_) async {
    final ms = await medianMs(() => notesDao.listRecent(limit: 50));
    debugPrint('PERF listRecent(50) : $ms ms');
    expect(
      ms,
      lessThan(100),
      reason:
          'Requête bornée par LIMIT : son temps ne doit PAS croître avec '
          'le nombre total de notes.',
    );
  });
}
