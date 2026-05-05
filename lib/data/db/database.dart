/// Ouverture de la base SQLite + migrations + index FTS5.
///
/// Une seule instance partagée. Migrations forward-only.
library;

import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:sqflite_sqlcipher/sqflite.dart' hide DatabaseException;

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
        onOpen: _onOpen,
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
    // `journal_mode` retourne une valeur (le mode appliqué) ; sur Android
    // récent, sqflite impose alors `rawQuery` plutôt que `execute`.
    await db.rawQuery('PRAGMA journal_mode = WAL;');
    await db.execute('PRAGMA synchronous = NORMAL;');
    await db.execute('PRAGMA temp_store = MEMORY;');
    // 8 Mo de cache page (négatif = Ko). Améliore scans FTS / listes longues.
    await db.execute('PRAGMA cache_size = -8000;');
  }

  Future<void> _onOpen(Database db) async {
    // Garantit que `inbox` existe à chaque ouverture (suppression accidentelle,
    // restauration de backup, migration future).
    await _ensureInboxFolder(db);
  }

  Future<void> _onCreate(Database db, int version) async {
    await db.transaction((txn) async {
      await _createSchemaV1(txn);
    });
  }

  Future<void> _onUpgrade(Database db, int oldV, int newV) async {
    // Migrations forward-only. Chaque version applique son delta.
    await db.transaction((txn) async {
      if (oldV < 2) await _migrateToV2(txn);
    });
  }

  /// v2 : ajout de la table `note_embeddings` (recherche par similarité).
  Future<void> _migrateToV2(Transaction txn) async {
    await _createEmbeddingsTable(txn);
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

    // Embeddings — table v2 (créée d'emblée pour les nouvelles installations).
    await _createEmbeddingsTable(txn);

    // Dossier racine par défaut.
    await _ensureInboxFolder(txn);
  }

  /// Garantit l'existence du dossier racine `inbox`. Idempotent — peut être
  /// rappelé à chaque ouverture sans risque (INSERT OR IGNORE).
  Future<void> _ensureInboxFolder(DatabaseExecutor txn) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    await txn.rawInsert('''
      INSERT OR IGNORE INTO folders
        (id, name, parent_id, color, icon, created_at, updated_at)
      VALUES (?, ?, NULL, NULL, ?, ?, ?);
    ''', ['inbox', 'Boîte de réception', 'inbox', now, now]);
  }

  Future<void> _createEmbeddingsTable(Transaction txn) async {
    await txn.execute('''
      CREATE TABLE note_embeddings (
        note_id     TEXT PRIMARY KEY NOT NULL,
        vector      BLOB NOT NULL,
        dim         INTEGER NOT NULL,
        model_id    TEXT NOT NULL,
        source_hash INTEGER NOT NULL,
        updated_at  INTEGER NOT NULL,
        FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE CASCADE
      );
    ''');
    await txn.execute(
      'CREATE INDEX idx_emb_model ON note_embeddings(model_id);',
    );
  }
}
