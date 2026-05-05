/// Ouverture de la base SQLite + migrations + index FTS5.
///
/// Une seule instance partagée. Migrations forward-only.
library;

import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:sqflite/sqflite.dart' hide DatabaseException;

import '../../core/constants.dart';
import '../../core/exceptions.dart';

class AppDatabase {
  AppDatabase._();
  static final AppDatabase instance = AppDatabase._();

  Database? _db;
  Future<Database>? _opening;

  Future<Database> get db async {
    final existing = _db;
    if (existing != null && existing.isOpen) return existing;
    return _opening ??= _open().whenComplete(() => _opening = null);
  }

  Future<Database> _open() async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final path = p.join(dir.path, AppConstants.dbFileName);
      final db = await openDatabase(
        path,
        version: AppConstants.dbVersion,
        onConfigure: _onConfigure,
        onCreate: _onCreate,
        onUpgrade: _onUpgrade,
      );
      _db = db;
      return db;
    } catch (e) {
      throw DatabaseException('Ouverture base échouée', cause: e);
    }
  }

  Future<void> close() async {
    final db = _db;
    if (db != null && db.isOpen) {
      await db.close();
    }
    _db = null;
  }

  // ---------------------------------------------------------------------
  // Configuration / migrations
  // ---------------------------------------------------------------------

  Future<void> _onConfigure(Database db) async {
    await db.execute('PRAGMA foreign_keys = ON;');
    await db.execute('PRAGMA journal_mode = WAL;');
    await db.execute('PRAGMA synchronous = NORMAL;');
    await db.execute('PRAGMA temp_store = MEMORY;');
    // 8 Mo de cache page (négatif = Ko). Améliore les scans FTS / listes longues.
    await db.execute('PRAGMA cache_size = -8000;');
  }

  Future<void> _onCreate(Database db, int version) async {
    await db.transaction((txn) async {
      await _createSchemaV1(txn);
    });
  }

  Future<void> _onUpgrade(Database db, int oldV, int newV) async {
    // Migrations forward-only. Chaque version applique son delta.
    // Aucune migration > v1 à ce jour.
  }

  // ---------------------------------------------------------------------
  // Schéma initial
  // ---------------------------------------------------------------------

  Future<void> _createSchemaV1(Transaction txn) async {
    // Dossiers
    await txn.execute('''
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
    await txn.execute(
      'CREATE INDEX idx_folders_parent ON folders(parent_id);',
    );

    // Notes
    await txn.execute('''
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
    // Index composite couvrant pour `listByFolder` (folder + filtres + tri).
    await txn.execute('''
      CREATE INDEX idx_notes_folder_active
      ON notes(folder_id, archived, trashed_at, updated_at DESC);
    ''');
    // Index utilisé par `purgeOldTrash` et `listTrash`.
    await txn.execute('CREATE INDEX idx_notes_trashed ON notes(trashed_at);');
    // Index utilisé par `listRecent` (toutes notes triées récent).
    await txn.execute('CREATE INDEX idx_notes_updated ON notes(updated_at);');

    // FTS5 : index plein texte sur titre + contenu
    await txn.execute('''
      CREATE VIRTUAL TABLE notes_fts USING fts5(
        title,
        content,
        tags,
        content='notes',
        content_rowid='rowid',
        tokenize='unicode61 remove_diacritics 2'
      );
    ''');

    // Triggers : maintien de l'index FTS5 synchrone
    await txn.execute('''
      CREATE TRIGGER notes_ai AFTER INSERT ON notes BEGIN
        INSERT INTO notes_fts(rowid, title, content, tags)
        VALUES (new.rowid, new.title, new.content, new.tags);
      END;
    ''');
    await txn.execute('''
      CREATE TRIGGER notes_ad AFTER DELETE ON notes BEGIN
        INSERT INTO notes_fts(notes_fts, rowid, title, content, tags)
        VALUES ('delete', old.rowid, old.title, old.content, old.tags);
      END;
    ''');
    await txn.execute('''
      CREATE TRIGGER notes_au AFTER UPDATE ON notes BEGIN
        INSERT INTO notes_fts(notes_fts, rowid, title, content, tags)
        VALUES ('delete', old.rowid, old.title, old.content, old.tags);
        INSERT INTO notes_fts(rowid, title, content, tags)
        VALUES (new.rowid, new.title, new.content, new.tags);
      END;
    ''');

    // Dossier racine par défaut
    final now = DateTime.now().millisecondsSinceEpoch;
    await txn.insert('folders', {
      'id': 'inbox',
      'name': 'Boîte de réception',
      'parent_id': null,
      'color': null,
      'icon': 'inbox',
      'created_at': now,
      'updated_at': now,
    });
  }
}
