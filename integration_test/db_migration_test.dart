// Migrations de schéma exercées sur de VRAIES bases héritées.
//
// Pourquoi ce fichier existe : tous les autres tests créent une base neuve.
// `onCreate` y pose directement le schéma courant, et `onUpgrade` n'est
// JAMAIS appelé. Autrement dit, la totalité du code de migration — sept
// paliers, dont celui qui vient de supprimer `note_embeddings` avec le
// retrait de l'IA — n'avait jamais tourné une seule fois, ni en test ni
// ailleurs. La première exécution réelle aurait eu lieu sur le téléphone
// d'un utilisateur, sur ses notes.
//
// Méthode : on fabrique le fichier hérité pour de bon (schéma d'époque +
// données + `PRAGMA user_version` de l'époque), on ferme, puis on rouvre
// par `AppDatabase` — sqflite compare alors les versions et déclenche le
// vrai `_onUpgrade`. Aucune migration n'est appelée à la main : c'est le
// chemin de production qui est mesuré, pas une imitation.
//
// Le schéma v1 ci-dessous est la copie exacte du commit initial
// (`git show 392eda4:lib/data/db/database.dart`), pas une reconstitution
// de mémoire — y compris les triggers FTS d'origine, ceux qui indexaient
// `title` et `tags` sans masquage.
//
// 🔴 Appareil de TEST uniquement, `-d <deviceId>` obligatoire :
//
//    flutter test integration_test/db_migration_test.dart -d 22dbb7390a057ece
//
// La base réelle de l'utilisateur n'est jamais touchée : chaque test
// travaille sur un nom de fichier dédié via `useFileNameOverride`, et le
// supprime avant de commencer.

import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:notes_tech/core/constants.dart';
import 'package:notes_tech/data/db/database.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:sqflite_sqlcipher/sqflite.dart';

/// DDL d'époque de `note_embeddings` — repris tel quel du commit précédant
/// le retrait de l'IA (`git show 8b2ef55~1:lib/data/db/database.dart`).
const String _kEmbeddingsDdl = '''
  CREATE TABLE note_embeddings (
    note_id     TEXT PRIMARY KEY NOT NULL,
    vector      BLOB NOT NULL,
    dim         INTEGER NOT NULL,
    model_id    TEXT NOT NULL,
    source_hash INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE CASCADE
  );
''';

const String _kEmbeddingsIndexDdl =
    'CREATE INDEX idx_emb_model ON note_embeddings(model_id);';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  /// Supprime le fichier de test et ses sidecars WAL/SHM/journal.
  ///
  /// Sans la purge des sidecars, un `-wal` laissé par un run précédent
  /// serait rejoué à l'ouverture du fichier suivant et ressusciterait des
  /// pages d'un schéma qu'on croyait avoir effacé — le test passerait ou
  /// échouerait selon ce qu'un run antérieur a laissé traîner.
  Future<void> deleteDbFile(String fileName) async {
    final dir = await getApplicationDocumentsDirectory();
    final base = p.join(dir.path, fileName);
    for (final suffix in const ['', '-wal', '-shm', '-journal']) {
      final f = File('$base$suffix');
      if (f.existsSync()) await f.delete();
    }
  }

  /// Ouvre une base de travail neuve sous [fileName], via `AppDatabase`.
  Future<Database> openFresh(String fileName) async {
    await AppDatabase.instance.close();
    await deleteDbFile(fileName);
    AppDatabase.instance.useFileNameOverride(fileName);
    return AppDatabase.instance.db;
  }

  /// Rouvre le même fichier — c'est ICI que `_onUpgrade` se déclenche.
  Future<Database> reopen(String fileName) async {
    await AppDatabase.instance.close();
    AppDatabase.instance.useFileNameOverride(fileName);
    return AppDatabase.instance.db;
  }

  Future<int> userVersion(Database db) async {
    final rows = await db.rawQuery('PRAGMA user_version;');
    return rows.first.values.first! as int;
  }

  Future<bool> objectExists(Database db, String type, String name) async {
    final rows = await db.rawQuery(
      'SELECT name FROM sqlite_master WHERE type = ? AND name = ?;',
      [type, name],
    );
    return rows.isNotEmpty;
  }

  Future<Set<String>> columnsOf(Database db, String table) async {
    final rows = await db.rawQuery('PRAGMA table_info($table);');
    return {for (final r in rows) r['name']! as String};
  }

  Future<String> triggerSql(Database db, String name) async {
    final rows = await db.rawQuery(
      "SELECT sql FROM sqlite_master WHERE type = 'trigger' AND name = ?;",
      [name],
    );
    return rows.isEmpty ? '' : (rows.first['sql'] as String? ?? '');
  }

  /// Efface tout le schéma applicatif pour reconstruire celui d'une version
  /// antérieure. `foreign_keys = OFF` le temps du démontage : les tables se
  /// référencent mutuellement et l'ordre de suppression n'aurait sinon
  /// aucune solution.
  Future<void> stripSchema(Database db) async {
    await db.execute('PRAGMA foreign_keys = OFF;');
    for (final t in const ['notes_ai', 'notes_au', 'notes_ad']) {
      await db.execute('DROP TRIGGER IF EXISTS $t;');
    }
    for (final t in const [
      'notes_fts',
      'note_links',
      'note_embeddings',
      'notes',
      'folders',
    ]) {
      await db.execute('DROP TABLE IF EXISTS $t;');
    }
    await db.execute('PRAGMA foreign_keys = ON;');
  }

  /// Reconstruit le schéma v1 D'ORIGINE, à l'identique du commit 392eda4.
  Future<void> buildSchemaV1(Database db) async {
    await db.execute('''
      CREATE TABLE folders (
        id          TEXT PRIMARY KEY NOT NULL,
        name        TEXT NOT NULL,
        parent_id   TEXT,
        color       INTEGER,
        icon        TEXT,
        created_at  INTEGER NOT NULL,
        updated_at  INTEGER NOT NULL,
        FOREIGN KEY (parent_id) REFERENCES folders(id) ON DELETE SET NULL
      );
    ''');
    await db.execute('CREATE INDEX idx_folders_parent ON folders(parent_id);');
    await db.execute('''
      CREATE TABLE notes (
        id          TEXT PRIMARY KEY NOT NULL,
        title       TEXT NOT NULL,
        content     TEXT NOT NULL,
        folder_id   TEXT NOT NULL,
        tags        TEXT NOT NULL DEFAULT '',
        pinned      INTEGER NOT NULL DEFAULT 0,
        favorite    INTEGER NOT NULL DEFAULT 0,
        archived    INTEGER NOT NULL DEFAULT 0,
        trashed_at  INTEGER,
        created_at  INTEGER NOT NULL,
        updated_at  INTEGER NOT NULL,
        FOREIGN KEY (folder_id) REFERENCES folders(id) ON DELETE CASCADE
      );
    ''');
    await db.execute('''
      CREATE INDEX idx_notes_folder_active
      ON notes(folder_id, archived, trashed_at, updated_at DESC);
    ''');
    await db.execute('CREATE INDEX idx_notes_trashed ON notes(trashed_at);');
    await db.execute('CREATE INDEX idx_notes_updated ON notes(updated_at);');
    await db.execute('''
      CREATE VIRTUAL TABLE notes_fts USING fts5(
        title,
        content,
        tags,
        content='notes',
        content_rowid='rowid',
        tokenize='unicode61 remove_diacritics 2'
      );
    ''');
    // Triggers d'ORIGINE : aucun masquage, `title`/`tags` indexés tels quels.
    await db.execute('''
      CREATE TRIGGER notes_ai AFTER INSERT ON notes BEGIN
        INSERT INTO notes_fts(rowid, title, content, tags)
        VALUES (new.rowid, new.title, new.content, new.tags);
      END;
    ''');
    await db.execute('''
      CREATE TRIGGER notes_ad AFTER DELETE ON notes BEGIN
        INSERT INTO notes_fts(notes_fts, rowid, title, content, tags)
        VALUES ('delete', old.rowid, old.title, old.content, old.tags);
      END;
    ''');
    await db.execute('''
      CREATE TRIGGER notes_au AFTER UPDATE ON notes BEGIN
        INSERT INTO notes_fts(notes_fts, rowid, title, content, tags)
        VALUES ('delete', old.rowid, old.title, old.content, old.tags);
        INSERT INTO notes_fts(rowid, title, content, tags)
        VALUES (new.rowid, new.title, new.content, new.tags);
      END;
    ''');
    final now = DateTime.now().millisecondsSinceEpoch;
    await db.insert('folders', {
      'id': 'inbox',
      'name': 'Boîte de réception',
      'parent_id': null,
      'color': null,
      'icon': 'inbox',
      'created_at': now,
      'updated_at': now,
    });
  }

  // ═════════════════════════════════════════════════════════════════════
  // 0. Garde-fou
  // ═════════════════════════════════════════════════════════════════════

  testWidgets('la version de schéma attendue par ces tests est celle du code', (
    _,
  ) async {
    expect(
      AppConstants.dbVersion,
      8,
      reason:
          'Le schéma a été bumpé sans que ces tests suivent. Ajouter un '
          'palier ici AVANT de livrer : une migration non testée est '
          'exactement ce que ce fichier existe pour empêcher.',
    );
  });

  // ═════════════════════════════════════════════════════════════════════
  // 1. v7 → v8 : le retrait de l'IA sur une base qui contenait des vecteurs
  // ═════════════════════════════════════════════════════════════════════

  testWidgets('v7 → v8 : les embeddings partent, les notes restent', (_) async {
    const file = 'migration_v7_test.db';
    var db = await openFresh(file);

    // Le schéma v7 = schéma courant + `note_embeddings`. `_migrateToV8` ne
    // fait rien d'autre que supprimer cette table et son index : recréer
    // ces deux objets suffit à reconstituer fidèlement une base v7.
    await db.execute(_kEmbeddingsDdl);
    await db.execute(_kEmbeddingsIndexDdl);

    final now = DateTime.now().millisecondsSinceEpoch;
    await db.insert('folders', {
      'id': 'coffre',
      'name': 'Coffre',
      'created_at': now,
      'updated_at': now,
      // Un dossier reconnu comme coffre : `vault_salt` non NULL.
      'vault_salt': Uint8List.fromList(List<int>.filled(16, 7)),
      'vault_mode': 'passphrase',
    });
    await db.insert('notes', {
      'id': 'n-clair',
      'title': 'Recette brouillon',
      'content': 'Farine, oeufs, patience.',
      'folder_id': 'inbox',
      'tags': 'cuisine',
      'created_at': now,
      'updated_at': now,
      'enc_v': 1,
    });
    // Une note verrouillée : c'est elle qui compte. Si la migration touchait
    // au blob, l'utilisateur perdrait le contenu SANS aucun message — la
    // clé étant dans sa tête, personne ne pourrait le reconstituer.
    // `Uint8List`, pas `List<int>` : sqflite refuse le second pour un BLOB
    // (avertissement en 2.x, exception annoncée). Écrire le test avec le
    // mauvais type reviendrait à mesurer autre chose que ce que la
    // couche DAO produit en production.
    final blob = Uint8List.fromList(
      List<int>.generate(64, (i) => (i * 37) % 256),
    );
    await db.insert('notes', {
      'id': 'n-coffre',
      'title': '',
      'content': '',
      'encrypted_content': blob,
      'folder_id': 'coffre',
      'tags': '',
      'created_at': now,
      'updated_at': now,
      'enc_v': 2,
    });
    await db.insert('note_embeddings', {
      'note_id': 'n-clair',
      'vector': Uint8List.fromList(List<int>.filled(32, 1)),
      'dim': 8,
      'model_id': 'minilm-l6-v2',
      'source_hash': 123456,
      'updated_at': now,
    });

    await db.execute('PRAGMA user_version = 7;');
    expect(await userVersion(db), 7);

    // ── LA migration ──────────────────────────────────────────────────
    db = await reopen(file);

    expect(
      await userVersion(db),
      8,
      reason: 'sqflite n\'a pas déclenché la montée de version',
    );
    expect(
      await objectExists(db, 'table', 'note_embeddings'),
      isFalse,
      reason:
          'Les vecteurs étaient dérivés du texte EN CLAIR des notes. Les '
          'laisser en base, c\'est garder une empreinte du contenu d\'une '
          'fonctionnalité qui n\'existe plus — y compris pour des notes '
          'passées au coffre depuis.',
    );
    expect(
      await objectExists(db, 'index', 'idx_emb_model'),
      isFalse,
      reason:
          'SQLite supprime bien l\'index avec sa table ; ce test verrouille '
          'le `DROP INDEX` explicite, qui protège le cas où la table aurait '
          'déjà disparu et l\'index survécu.',
    );

    final clair = await db.query(
      'notes',
      where: 'id = ?',
      whereArgs: ['n-clair'],
    );
    expect(clair, hasLength(1));
    expect(clair.first['content'], 'Farine, oeufs, patience.');
    expect(clair.first['title'], 'Recette brouillon');

    final coffre = await db.query(
      'notes',
      where: 'id = ?',
      whereArgs: ['n-coffre'],
    );
    expect(coffre, hasLength(1));
    expect(
      coffre.first['encrypted_content'],
      blob,
      reason: 'le blob chiffré doit être identique octet pour octet',
    );
    expect(
      coffre.first['enc_v'],
      2,
      reason:
          'La version de format doit survivre : la relire à 1 ferait '
          'chercher un titre en clair dans une colonne vidée.',
    );

    final folders = await db.query(
      'folders',
      where: 'id = ?',
      whereArgs: ['coffre'],
    );
    expect(folders, hasLength(1));
    expect(folders.first['vault_salt'], isNotNull);
    expect(folders.first['vault_mode'], 'passphrase');
  });

  // ═════════════════════════════════════════════════════════════════════
  // 2. v1 → v8 : la chaîne complète, sept paliers d'affilée
  // ═════════════════════════════════════════════════════════════════════

  testWidgets('v1 → v8 : la chaîne complète monte sans perdre de note', (
    _,
  ) async {
    const file = 'migration_v1_test.db';
    var db = await openFresh(file);

    await stripSchema(db);
    await buildSchemaV1(db);

    final now = DateTime.now().millisecondsSinceEpoch;
    await db.insert('notes', {
      'id': 'vieille-note',
      'title': 'Brouillon de 2024',
      'content': 'Ce texte doit traverser sept migrations intact.',
      'folder_id': 'inbox',
      'tags': 'archive',
      'pinned': 1,
      'favorite': 1,
      'created_at': now,
      'updated_at': now,
    });
    await db.execute('PRAGMA user_version = 1;');

    // ── LA chaîne ─────────────────────────────────────────────────────
    db = await reopen(file);

    expect(await userVersion(db), 8);

    final rows = await db.query(
      'notes',
      where: 'id = ?',
      whereArgs: ['vieille-note'],
    );
    expect(rows, hasLength(1), reason: 'la note d\'origine a disparu');
    expect(
      rows.first['content'],
      'Ce texte doit traverser sept migrations intact.',
    );
    expect(rows.first['pinned'], 1);
    expect(rows.first['favorite'], 1);

    // v7 : la colonne de format existe et vaut 1 pour l'existant. Toute
    // autre valeur ferait lire les notes héritées avec le mauvais format.
    final notesCols = await columnsOf(db, 'notes');
    expect(notesCols, contains('enc_v'));
    expect(notesCols, contains('encrypted_content'));
    expect(rows.first['enc_v'], 1);

    // v4 + v5 : colonnes de coffre présentes sur `folders`.
    final folderCols = await columnsOf(db, 'folders');
    expect(
      folderCols,
      containsAll(<String>[
        'vault_salt',
        'vault_kek_wrapped',
        'vault_iv',
        'vault_verifier',
        'vault_mode',
        'vault_pin_blob',
        'vault_pin_iv',
        'vault_attempts',
      ]),
    );
    final inbox = await db.query(
      'folders',
      where: 'id = ?',
      whereArgs: ['inbox'],
    );
    expect(inbox, hasLength(1));
    expect(
      inbox.first['vault_attempts'],
      0,
      reason:
          'La colonne est NOT NULL DEFAULT 0 : un NULL ici planterait la '
          'lecture du compteur de tentatives PIN.',
    );
    expect(
      inbox.first['vault_mode'],
      isNull,
      reason:
          'v5 ne marque `passphrase` que les dossiers ayant déjà un '
          'vault_salt. La boîte de réception n\'est pas un coffre.',
    );

    // v3 : table des backlinks.
    expect(await objectExists(db, 'table', 'note_links'), isTrue);

    // v2 devenu no-op : la table d'embeddings ne doit jamais réapparaître
    // sur le chemin v1 → v8.
    expect(
      await objectExists(db, 'table', 'note_embeddings'),
      isFalse,
      reason:
          'Le palier v2 est un no-op documenté. S\'il recréait la table, '
          'v8 la supprimerait aussitôt — mais une base v1 rouverte par une '
          'version future qui oublierait v8 la garderait.',
    );

    // v6 : les triggers FTS ont bien été RÉÉCRITS. C'est le palier qui
    // tient la promesse de non-divulgation des titres verrouillés.
    for (final t in const ['notes_ai', 'notes_au', 'notes_ad']) {
      expect(
        await triggerSql(db, t),
        contains('encrypted_content'),
        reason:
            'Le trigger $t est resté celui de v1 : il indexerait le titre '
            'd\'une note verrouillée, et une recherche plein texte la '
            'ferait remonter coffre fermé.',
      );
    }

    // L'index FTS hérité reste exploitable après la réécriture des triggers.
    final hits = await db.rawQuery(
      'SELECT n.id FROM notes_fts f JOIN notes n ON n.rowid = f.rowid '
      "WHERE notes_fts MATCH 'brouillon';",
    );
    expect(
      hits.map((r) => r['id']),
      contains('vieille-note'),
      reason:
          'Les lignes FTS écrites sous le régime v1 doivent rester '
          'interrogeables : v6 remplace les triggers, pas l\'index.',
    );
  });

  // ═════════════════════════════════════════════════════════════════════
  // 3. Idempotence : rouvrir une base déjà à jour ne doit rien faire
  // ═════════════════════════════════════════════════════════════════════

  testWidgets('v8 → v8 : une réouverture ne rejoue aucune migration', (
    _,
  ) async {
    const file = 'migration_v8_test.db';
    var db = await openFresh(file);

    final now = DateTime.now().millisecondsSinceEpoch;
    await db.insert('notes', {
      'id': 'stable',
      'title': 'Inchangée',
      'content': 'Rien ne doit bouger.',
      'folder_id': 'inbox',
      'created_at': now,
      'updated_at': now,
    });

    db = await reopen(file);

    expect(await userVersion(db), 8);
    final rows = await db.query(
      'notes',
      where: 'id = ?',
      whereArgs: ['stable'],
    );
    expect(rows, hasLength(1));
    expect(rows.first['content'], 'Rien ne doit bouger.');
    // `_migrateToV7` ferait un `ALTER TABLE ADD COLUMN enc_v` : rejoué, il
    // lèverait « duplicate column name ». Le test ci-dessus serait déjà
    // rouge, mais on nomme la panne attendue pour qu'un futur lecteur
    // sache quoi chercher.
    expect(
      (await columnsOf(db, 'notes')).where((c) => c == 'enc_v'),
      hasLength(1),
    );

    await AppDatabase.instance.close();
    await deleteDbFile(file);
    await deleteDbFile('migration_v7_test.db');
    await deleteDbFile('migration_v1_test.db');
  });
}
