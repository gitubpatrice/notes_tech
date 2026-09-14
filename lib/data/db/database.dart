/// Ouverture de la base SQLite chiffrée + migrations + index FTS5.
///
/// Sécurité v0.5 :
///  - Base chiffrée par SQLCipher 4 (AES-256 CBC + HMAC-SHA-512), clé
///    256 bits fournie par `VaultService` (KEK scellée AndroidKeystore).
///  - La clé est passée au format `x'<hex>'` pour forcer SQLCipher à
///    l'utiliser comme **raw key** (pas de re-dérivation PBKDF2).
///  - Migration automatique d'une éventuelle base existante en clair
///    (utilisateur v0.4.x) via `sqlcipher_export`. Idempotente,
///    point-de-non-retour atomique (rename + flag persisté).
///
/// Une seule instance partagée. Migrations forward-only.
library;

import 'dart:io';
import 'dart:typed_data';

import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/foundation.dart' show debugPrint, kDebugMode, visibleForTesting;
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite_sqlcipher/sqflite.dart' hide DatabaseException;

import '../../core/constants.dart';
import '../../core/exceptions.dart';
import '../../services/security/keystore_bridge.dart';
import '../../services/security/vault_service.dart';

class AppDatabase {
  AppDatabase._();
  static final AppDatabase instance = AppDatabase._();

  Database? _db;
  Future<Database>? _opening;

  /// Permet de fournir un `VaultService` mocké en test. En production,
  /// l'instance par défaut suffit.
  VaultService? _vaultOverride;

  /// À appeler une fois au bootstrap (avant tout `db`) pour injecter un
  /// vault custom (tests, démarrage avec une KEK déjà matérialisée).
  void useVault(VaultService vault) {
    _vaultOverride = vault;
  }

  /// Nom de fichier alternatif, **pour les tests sur appareil uniquement**.
  ///
  /// Les tests d'intégration tournent dans le sandbox de l'application
  /// installée : sans cette bascule, ils ouvriraient la base RÉELLE de
  /// l'utilisateur et y écriraient leurs dossiers et leurs notes. Sur un
  /// téléphone de test c'est du désordre ; sur un vrai téléphone ce serait
  /// une corruption de données. On isole donc systématiquement.
  ///
  /// Sans appel, la valeur reste `AppConstants.dbFileName` — le code de
  /// production ne change pas de comportement.
  @visibleForTesting
  void useFileNameOverride(String fileName) {
    _fileNameOverride = fileName;
  }

  String? _fileNameOverride;

  Future<Database> get db async {
    final existing = _db;
    if (existing != null && existing.isOpen) return existing;
    return _opening ??= _open().whenComplete(() => _opening = null);
  }

  Future<Database> _open() async {
    try {
      // Parallélisation : le scellage Keystore (round-trip JNI ~30-150 ms
      // au cold start) tourne en même temps que la résolution du
      // sandbox path. Gain typique : 30-80 ms sur S24.
      final vault = _vaultOverride ?? VaultService();
      final dirF = getApplicationDocumentsDirectory();
      final kekF = vault.getOrCreateKek();
      final dir = await dirF;
      final kek = await kekF;
      final path = p.join(
        dir.path,
        _fileNameOverride ?? AppConstants.dbFileName,
      );

      try {
        await _ensureEncrypted(path, kek);

        final db = await openDatabase(
          path,
          password: _formatRawKey(kek),
          version: AppConstants.dbVersion,
          onConfigure: _onConfigure,
          onCreate: _onCreate,
          onUpgrade: _onUpgrade,
          onOpen: _onOpen,
        );
        _db = db;
        // ── Passerelle 2.0.4 : re-scellage de la KEK pour la version Kotlin ──────────
        //
        // La 3.0.0 (portage Kotlin) lit la KEK sous un alias qu'elle maîtrise. Sans ce
        // geste, elle doit rejouer à l'envers la cryptographie interne de
        // `flutter_secure_storage` — ça marche, c'est mesuré, mais ça repose sur le
        // format d'une bibliothèque tierce que rien n'oblige à rester stable.
        //
        // ⚠️ **Ici et pas plus tôt** : la KEK est en main, la base est ouverte, et le
        // `finally` ci-dessous va l'effacer. Le faire après le `wipe` scellerait des
        // zéros.
        //
        // ⚠️⚠️ **Le `catch` est obligatoire, et large.** Un échec de scellement ne doit
        // pas empêcher l'utilisateur d'ouvrir ses notes : il le fera simplement basculer
        // sur la couche de secours au moment de la 3.0.0.
        //
        // ⚠️ **On n'enlève RIEN de `flutter_secure_storage`** : on ajoute un chemin, on
        // n'en supprime aucun. Un utilisateur qui reviendrait en arrière depuis la 3.0.0
        // doit retrouver une application qui fonctionne.
        try {
          await KeystoreBridge().sealDatabaseKek(kek);
        } catch (e) {
          if (kDebugMode) debugPrint('re-scellage de la KEK impossible : $e');
        }
        return db;
      } finally {
        // Wipe la KEK matérialisée en mémoire dès la DB ouverte —
        // SQLCipher en garde une copie native côté C.
        VaultService.wipe(kek);
      }
    } catch (e) {
      throw DatabaseException(
        'Ouverture base échouée',
        cause: _scrubKey(e.toString()),
      );
    }
  }

  Future<void> close() async {
    final db = _db;
    if (db != null && db.isOpen) {
      await db.close();
    }
    _db = null;
  }

  /// **Mode panique** — ferme la DB ET supprime de manière sécurisée
  /// le fichier SQLite chiffré. Utilisé par [PanicService] (services/
  /// security). À appeler APRÈS `vault.destroyKek()` pour que la
  /// destruction de la clé maître ait précédé celle de la DB (séquence
  /// défensive : si on s'arrête après KEK destroy, la DB devient déjà
  /// illisible ; si on s'arrête entre les deux, c'est encore l'état
  /// "verrouillé à jamais").
  ///
  /// Idempotent : si la DB n'a jamais été ouverte ou si le fichier
  /// n'existe pas, no-op silencieux.
  /// ⚠️ LÈVE si un fichier survit à la purge, et c'est délibéré.
  ///
  /// Cette méthode avalait toute erreur d'I/O en silence. `PanicService`
  /// enregistre l'issue de chaque étape via `_runStep`, qui ne voit qu'une
  /// exception : sans remontée, `PanicReport` affichait « dbWipe OK » alors
  /// que le fichier était toujours là. Un rapport de panique qui ment sur un
  /// effacement est pire que pas de rapport. Relevé par une relecture externe
  /// (GPT-5.2).
  ///
  /// La séquence de panique n'est PAS interrompue pour autant : `_runStep`
  /// rattrape par étape et poursuit. La garantie minimale — KEK détruite,
  /// donc base cryptographiquement illisible — est acquise avant cet appel.
  Future<void> wipe() async {
    await close();
    final survivants = <String>[];
    try {
      final dir = await getApplicationDocumentsDirectory();
      final dbFile = File(p.join(dir.path, AppConstants.dbFileName));
      // Fichiers SQLite sidecars (-journal, -wal, -shm) — peuvent rester
      // après un crash. On les supprime aussi pour qu'aucune trace
      // déchiffrable ne survive.
      for (final cible in <File>[
        dbFile,
        for (final suffix in const ['-journal', '-wal', '-shm'])
          File('${dbFile.path}$suffix'),
      ]) {
        if (await cible.exists()) {
          await _securelyDelete(cible);
          if (await cible.exists()) survivants.add(p.basename(cible.path));
        }
      }
    } catch (e) {
      throw DatabaseException('Wipe DB échoué', cause: _scrubKey(e.toString()));
    }
    if (survivants.isNotEmpty) {
      throw DatabaseException(
        'Wipe DB incomplet — fichiers survivants : ${survivants.join(', ')}',
      );
    }
  }

  // ---------------------------------------------------------------------
  // Migration en clair → chiffrée (idempotent, atomique, one-shot)
  // ---------------------------------------------------------------------

  /// 16 premiers octets du fichier d'une base SQLite **non chiffrée**.
  /// Référence : https://www.sqlite.org/fileformat.html#magic_header_string
  ///
  /// ⚠️ Le 16ᵉ caractère de cette chaîne est un octet NUL **littéral**, et il
  /// est INTENTIONNEL : le magic SQLite se termine par `\0`. C'est pour ça que
  /// `grep` annonce « Binary file matches » sur ce fichier — ce n'est pas une
  /// corruption, et une relecture automatique l'a déjà signalé à tort comme
  /// telle.
  ///
  /// NE PAS le supprimer en croyant nettoyer le fichier : la chaîne
  /// tomberait à 15 caractères, `_isPlainSqlite` ne reconnaîtrait plus une
  /// base en clair, et la migration vers SQLCipher serait silencieusement
  /// sautée — une base utilisateur resterait non chiffrée sans que rien ne
  /// le signale. Si vous devez y toucher, remplacez-le par l'échappement
  /// `\x00`, jamais par rien.
  static const String _sqliteMagic = 'SQLite format 3 ';

  Future<void> _ensureEncrypted(String path, Uint8List kek) async {
    final prefs = await SharedPreferences.getInstance();
    final alreadyEncrypted =
        prefs.getBool(AppConstants.prefKeyDbEncryptedV1) ?? false;

    final encPath = '$path.enc';
    final bakPath = '$path.plain.bak';
    final mainFile = File(path);
    final encFile = File(encPath);
    final bakFile = File(bakPath);

    // Reprise après crash : si une migration précédente a réussi à produire
    // `.enc` mais a été tuée juste avant le rename, on finit le travail.
    if (encFile.existsSync() && !mainFile.existsSync()) {
      await encFile.rename(path);
    }

    // Nettoyage agressif de toute trace de DB en clair survivante
    // (.bak laissé par une version intermédiaire de v0.5.0 ou rename
    // partiellement appliqué).
    if (bakFile.existsSync()) {
      await _securelyDelete(bakFile);
    }

    if (alreadyEncrypted) {
      // Le flag est posé : tout `.enc` orphelin est forcément un déchet.
      if (encFile.existsSync()) await _securelyDelete(encFile);
      return;
    }

    if (!mainFile.existsSync()) {
      // Première installation : DB créée chiffrée d'emblée.
      await prefs.setBool(AppConstants.prefKeyDbEncryptedV1, true);
      return;
    }

    final plain = await _isPlainSqlite(mainFile);
    if (!plain) {
      // Header SQLCipher (16 premiers octets aléatoires) — déjà chiffrée,
      // on aligne le flag.
      await prefs.setBool(AppConstants.prefKeyDbEncryptedV1, true);
      return;
    }

    await _migratePlainToEncrypted(path, kek);

    // Le flag n'est posé qu'APRÈS validation que la DB chiffrée s'ouvre
    // bien (vérification dans `_validateEncryptedDb`) — un crash avant
    // ce point relancera la migration.
    await _validateEncryptedDb(path, kek);
    await prefs.setBool(AppConstants.prefKeyDbEncryptedV1, true);

    // Une fois validée, l'ancien fichier en clair (`.plain.bak`) est
    // écrasé puis supprimé. Plus jamais de notes en clair sur disque.
    if (bakFile.existsSync()) await _securelyDelete(bakFile);
  }

  /// Discrimine une base SQLite en clair (header `'SQLite format 3\0'`)
  /// d'une base SQLCipher (premiers octets aléatoires/sel) ou de tout
  /// autre fichier. Robuste à l'absence de fichier et aux erreurs I/O.
  Future<bool> _isPlainSqlite(File file) async {
    try {
      final raf = await file.open();
      try {
        final header = await raf.read(_sqliteMagic.length);
        if (header.length < _sqliteMagic.length) return false;
        for (var i = 0; i < _sqliteMagic.length; i++) {
          if (header[i] != _sqliteMagic.codeUnitAt(i)) return false;
        }
        return true;
      } finally {
        await raf.close();
      }
    } catch (_) {
      return false;
    }
  }

  /// Migre une base SQLite en clair vers une base chiffrée par `kek`.
  ///
  /// Stratégie :
  ///   1. `ATTACH DATABASE '<path>.enc' KEY x'<hex>'`
  ///   2. `SELECT sqlcipher_export('encrypted')` — copie schéma + données
  ///   3. `DETACH`, fermeture des deux poignées
  ///   4. Rename atomique : `path → path.plain.bak` puis `path.enc → path`
  ///
  /// En cas d'échec, le `.enc` est nettoyé et l'original intact ; la
  /// prochaine ouverture relancera la migration.
  Future<void> _migratePlainToEncrypted(String path, Uint8List kek) async {
    final encPath = '$path.enc';
    final encFile = File(encPath);
    if (encFile.existsSync()) {
      try {
        encFile.deleteSync();
      } catch (_) {
        /* recréé juste après */
      }
    }

    // SQLite n'autorise pas de placeholder paramétré pour `ATTACH … KEY`
    // (la clé doit être présente en littéral SQL au moment du parse).
    // On inline la valeur via `_attachSql`, dont les seules sources
    // sont :
    //   - la KEK 32 octets aléatoires sortie de `Random.secure`, encodée
    //     en hex (`[0-9a-f]` strict, aucun caractère d'évasion) ;
    //   - le chemin du sandbox app, fixé par `getApplicationDocumentsDirectory`.
    // Aucune entrée utilisateur n'arrive jusqu'ici → pas de surface
    // d'injection SQL.
    Database? plain;
    try {
      plain = await openDatabase(path);
      await plain.execute(_attachSql(encPath, kek));
      await plain.rawQuery("SELECT sqlcipher_export('encrypted');");
      await plain.execute('DETACH DATABASE encrypted;');
    } catch (e) {
      if (encFile.existsSync()) {
        try {
          encFile.deleteSync();
        } catch (_) {}
      }
      // Scrub la KEK avant remontée : SQLCipher peut inclure le SQL
      // fautif dans son message d'erreur.
      throw DatabaseException(
        'Migration vers DB chiffrée échouée',
        cause: _scrubKey(e.toString()),
      );
    } finally {
      try {
        await plain?.close();
      } catch (_) {}
    }

    // Purge des sidecars WAL/SHM/journal de la base EN CLAIR. `_onConfigure`
    // active `journal_mode = WAL`, donc la base héritée laissait
    // `notes_tech.db-wal`/`-shm` avec des pages de notes EN CLAIR. À ce point,
    // `sqlcipher_export` a déjà capté les frames WAL committées et
    // `plain.close()` a checkpointé — ces fichiers ne sont plus que du
    // plaintext résiduel. Sans cette purge ils survivent à la migration
    // (fuite sur device rooté / extraction physique). Symétrique au wipe du
    // `.plain.bak`.
    for (final suffix in const ['-wal', '-shm', '-journal']) {
      final side = File('$path$suffix');
      if (side.existsSync()) await _securelyDelete(side);
    }

    // Rename : pas de blob en clair restant sur le chemin canonique.
    // L'opération est dans le même filesystem (sandbox app) → atomique
    // côté ext4/F2FS.
    final bakPath = '$path.plain.bak';
    final bakFile = File(bakPath);
    if (bakFile.existsSync()) bakFile.deleteSync();
    await File(path).rename(bakPath);
    await encFile.rename(path);
  }

  /// Vérifie que la base chiffrée fraîchement écrite est exploitable :
  /// ouverture avec la KEK + lecture d'une métadonnée. Permet de poser
  /// le flag `prefKeyDbEncryptedV1` en conscience plutôt qu'optimiste.
  Future<void> _validateEncryptedDb(String path, Uint8List kek) async {
    Database? probe;
    try {
      // A1 v1.0.4 — appel `_onConfigure` explicite pour aligner le profil
      // (PRAGMA cipher_compatibility = 4, WAL, foreign_keys) sur la prod.
      // Sans ça, un bump futur du package sqflite_sqlcipher changeant le
      // profil par défaut faisait passer la validation sur un profil
      // différent de la prod → faux positif.
      probe = await openDatabase(
        path,
        password: _formatRawKey(kek),
        onConfigure: _onConfigure,
      );
      // Check structurel : la table `notes` doit exister (créée à v1 ou
      // après migration). Sans ça, une DB chiffrée vide passait la
      // validation (`sqlite_master LIMIT 1` retourne au minimum
      // `android_metadata` sur Android).
      final notesRows = await probe.rawQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='notes';",
      );
      if (notesRows.isEmpty) {
        throw const DatabaseException('DB chiffrée sans table notes');
      }
    } catch (e) {
      if (e is DatabaseException) rethrow;
      throw DatabaseException(
        'Validation post-migration échouée',
        cause: _scrubKey(e.toString()),
      );
    } finally {
      try {
        await probe?.close();
      } catch (_) {}
    }
  }

  /// Écrase le **header** du fichier de zéros avant suppression.
  ///
  /// Pourquoi ne PAS écraser tout le fichier ?
  /// - Sur ext4/F2FS + eMMC moderne avec wear-leveling et TRIM, l'écrit
  ///   logique de zéros n'a aucun lien garanti avec les blocs physiques
  ///   (le contrôleur peut décider de juste marquer libre). La sécurité
  ///   crypto vient déjà de la destruction de la KEK Keystore — sans
  ///   clé, le ciphertext AES-256-GCM est cryptographiquement illisible.
  /// - Écraser 100 Mo sur eMMC S9/POCO C75 prend 500 ms - 2 s sur le
  ///   thread d'event loop. Le mode panique doit aboutir vite.
  ///
  /// On écrase donc seulement les premiers 16 Mo, qui couvrent largement
  /// le header SQLite + les pages SQLCipher contenant les métadonnées
  /// et les premières tables — tout en restant rapide (~50-150 ms).
  /// Pour les fichiers plus petits, on écrase tout.
  static const int _wipeHeaderBytes = 16 * 1024 * 1024;

  Future<void> _securelyDelete(File file) async {
    try {
      final raf = await file.open(mode: FileMode.write);
      try {
        final length = await file.length();
        final overwrite = length < _wipeHeaderBytes ? length : _wipeHeaderBytes;
        const chunk = 64 * 1024;
        final zeros = Uint8List(chunk);
        var written = 0;
        while (written < overwrite) {
          final remaining = overwrite - written;
          await raf.writeFrom(zeros, 0, remaining < chunk ? remaining : chunk);
          written += chunk;
        }
        await raf.flush();
      } finally {
        await raf.close();
      }
    } catch (_) {
      // Best-effort. Si l'écrasement échoue, on supprime quand même —
      // la KEK détruite suffit à rendre le contenu illisible.
    }
    try {
      await file.delete();
    } catch (_) {}
  }

  /// Construit la requête `ATTACH` avec la forme canonique SQLCipher
  /// `KEY x'<hex>'` (pas de re-dérivation PBKDF2). Le path est entre
  /// quotes simples doublées.
  ///
  /// F14 v1.1.0 — validation regex stricte du path. `escapedPath` est
  /// safe en quotes-escaping basique MAIS le path entier provient
  /// d'`getApplicationDocumentsDirectory()` Flutter, qui peut être
  /// détourné via `LD_PRELOAD`/root setup vers un chemin contenant des
  /// méta-SQL (`'; DROP --`). Cas extrême root-only mais c'est la
  /// « source unique de vérité » de l'audit — l'attaquant qui contrôle
  /// le path control la DB. Validation : regex strictement positive sur
  /// caractères de chemins Android légitimes.
  static final RegExp _kSafeDbPath = RegExp(r'^[A-Za-z0-9_./:\\-]+$');

  static String _attachSql(String path, Uint8List kek) {
    if (!_kSafeDbPath.hasMatch(path)) {
      throw StateError(
        'F14 v1.1.0 — path DB invalide (caractères non autorisés). '
        'Refus de l\'`ATTACH` pour éviter une injection SQL via path.',
      );
    }
    final escapedPath = path.replaceAll("'", "''");
    final hex = SecretBytes.toHex(kek);
    return "ATTACH DATABASE '$escapedPath' AS encrypted KEY x'$hex';";
  }

  /// Retire toute occurrence d'une clé hex 64 caractères du message
  /// d'erreur — garde-fou anti-fuite si SQLCipher renvoie le SQL fautif.
  static String _scrubKey(String message) =>
      message.replaceAll(_hexKeyRegex, "x'<scrubbed>'");

  static final RegExp _hexKeyRegex = RegExp("x'[0-9a-fA-F]{64}'");

  /// Format `x'<hex>'` pour SQLCipher — force l'usage en raw key.
  static String _formatRawKey(Uint8List kek) => "x'${SecretBytes.toHex(kek)}'";

  // ---------------------------------------------------------------------
  // Configuration / migrations
  // ---------------------------------------------------------------------

  Future<void> _onConfigure(Database db) async {
    // A17 v1.0.4 — force SQLCipher format v4 explicite (anti-bump
    // sqflite_sqlcipher futur qui passerait à v5 silencieusement, ce
    // qui rendrait les vaults v1.0.x illisibles sans warning).
    try {
      await db.execute('PRAGMA cipher_compatibility = 4;');
    } catch (_) {
      // Variante : le pragma peut être indisponible sur certaines
      // configurations (mocks tests). On laisse passer en best-effort.
    }
    await db.execute('PRAGMA foreign_keys = ON;');
    // `journal_mode` retourne une valeur (le mode appliqué) ; sur Android
    // récent, sqflite impose alors `rawQuery` plutôt que `execute`.
    await db.rawQuery('PRAGMA journal_mode = WAL;');
    // `synchronous = NORMAL` : compromis perf/cohérence assumé. Avec WAL,
    // un crash système (panne batterie, kill brutal) PEUT laisser des
    // pages WAL non fsync'd — la DB se rouvre cohérente (SQLite garantit
    // l'atomicité du commit) mais les dernières secondes d'écriture
    // peuvent être perdues. `FULL` éviterait cette perte au prix d'un
    // fsync par commit, soit ~10× plus lent sur les flux d'auto-save.
    // Acceptable ici : les notes sont écrites toutes les 500ms pendant
    // l'édition, la perte max = 500ms d'édition non visible utilisateur.
    await db.execute('PRAGMA synchronous = NORMAL;');
    await db.execute('PRAGMA temp_store = MEMORY;');
    // v1.0.7 perf M2 — 32 Mo de cache page (négatif = Ko). Améliore scans
    // FTS / listes longues, particulièrement sur des bases >2000 notes.
    // 32 Mo représente <1% de la RAM dispo sur les devices ciblés (≥3 Go)
    // et compense largement par la réduction de re-lectures disque.
    await db.execute('PRAGMA cache_size = -32000;');
    // v1.0.7.1 hotfix — `PRAGMA mmap_size` retiré : sur certaines builds
    // natives SQLCipher Android, le mmap peut hang ou échouer silencieusement
    // au cold boot (page blanche bloquée). Le gain perf était marginal pour
    // les bases <100 Mo (notes texte), priorité à la robustesse de boot.
  }

  Future<void> _onOpen(Database db) async {
    // Garantit que `inbox` existe à chaque ouverture (suppression accidentelle,
    // restauration de backup, migration future).
    await _ensureInboxFolder(db);
  }

  Future<void> _onCreate(Database db, int version) async {
    // Fresh install : `_createSchemaV1` inclut DÉJÀ toutes les colonnes
    // v2-v5 (note_embeddings, note_links, vault_*, vault_pin_*). Les
    // `_migrateToV2/V3/V4/V5` ne sont JAMAIS appelés sur une nouvelle
    // installation — ils existent uniquement pour les utilisateurs qui
    // upgradent depuis une version antérieure.
    await db.transaction((txn) async {
      await _createSchemaV1(txn);
    });
  }

  Future<void> _onUpgrade(Database db, int oldV, int newV) async {
    // Migrations forward-only. Chaque version applique son delta.
    await db.transaction((txn) async {
      if (oldV < 2) await _migrateToV2(txn);
      if (oldV < 3) await _migrateToV3(txn);
      if (oldV < 4) await _migrateToV4(txn);
      if (oldV < 5) await _migrateToV5(txn);
      if (oldV < 6) await _migrateToV6(txn);
      if (oldV < 7) await _migrateToV7(txn);
      if (oldV < 8) await _migrateToV8(txn);
      if (oldV < 9) await _migrateToV9(txn);
    });
  }

  /// v6 (1.0.3) : F2 — réécrit les triggers `notes_ai/au/ad` pour ne plus
  /// indexer `title`/`tags` quand `encrypted_content IS NOT NULL`. Purge
  /// au passage les lignes FTS existantes des notes verrouillées (sinon
  /// la promesse n'est respectée que pour les nouvelles notes).
  /// v8 : supprime `note_embeddings` avec le retrait de la recherche
  /// sémantique.
  ///
  /// Ce n'est pas qu'un ménage de place. Ces vecteurs étaient **dérivés du
  /// texte en clair** des notes : les laisser en base reviendrait à garder
  /// une empreinte du contenu d'une fonctionnalité qui n'existe plus, y
  /// compris pour des notes ensuite mises au coffre. Le retrait de l'IA est
  /// donc aussi une purge de données.
  ///
  /// `IF EXISTS` : une base créée après ce commit n'a jamais eu la table.
  Future<void> _migrateToV8(Transaction txn) async {
    await txn.execute('DROP INDEX IF EXISTS idx_emb_model;');
    await txn.execute('DROP TABLE IF EXISTS note_embeddings;');
  }

  /// v9 (2.0.0) : les triggers FTS masquent aussi `content`.
  ///
  /// Ils masquaient déjà `title` et `tags` quand `encrypted_content IS NOT
  /// NULL`, mais indexaient `new.content` SANS CONDITION. La promesse « une
  /// note verrouillée ne remonte pas en recherche » reposait donc sur une
  /// convention Dart — « au verrouillage, on vide `content` » — qu'aucune
  /// contrainte SQL n'imposait. Un seul chemin d'écriture qui l'oublie, et le
  /// texte en clair entrait dans l'index, coffre fermé.
  ///
  /// La garde d'invariant de `NotesRepository` refuse déjà ces écritures, mais
  /// elle vit dans le code applicatif. Ici la garantie devient structurelle :
  /// même une écriture directe en SQL ne peut plus indexer le contenu d'une
  /// note chiffrée. Relevé en CRITIQUE par une relecture externe (GPT-5.2).
  ///
  /// ⚠️ CE PALIER NE TOUCHE PAS À L'INDEX, seulement aux triggers — et
  /// contrairement à `_migrateToV6`, c'est délibéré.
  ///
  /// La première version de ce palier reprenait le geste de v6 : purger les
  /// lignes FTS des notes verrouillées puis les réinjecter vidées. Elle a
  /// CORROMPU LA BASE, `database disk image is malformed (code 267)`, et le
  /// test de migration l'a attrapée avant toute livraison.
  ///
  /// La raison tient à FTS5 en mode *external content* : la commande
  /// `'delete'` doit recevoir les valeurs TELLES QU'ELLES ONT ÉTÉ INDEXÉES,
  /// pas les valeurs actuelles des colonnes. Or après v6, les lignes des
  /// notes verrouillées contiennent des chaînes vides. Supprimer avec
  /// `n.title, n.content, n.tags` décrit donc un document qui n'existe pas
  /// dans l'index, et corrompt sa structure. `rebuild` ne convient pas non
  /// plus : il ré-indexerait les colonnes brutes, donc le titre en clair des
  /// notes au format v1.
  ///
  /// Rien à purger, en pratique : l'invariant veut que `content` soit vide
  /// pour une note verrouillée, et la garde de `NotesRepository` refuse
  /// désormais toute écriture qui le violerait. Ce palier protège les
  /// écritures À VENIR, ce qui était le but.
  Future<void> _migrateToV9(Transaction txn) async {
    await txn.execute('DROP TRIGGER IF EXISTS notes_ai;');
    await txn.execute('DROP TRIGGER IF EXISTS notes_au;');
    await txn.execute('DROP TRIGGER IF EXISTS notes_ad;');
    await txn.execute(_ftsTriggerInsertSql());
    await txn.execute(_ftsTriggerDeleteSql());
    await txn.execute(_ftsTriggerUpdateSql());
  }

  /// v7 : `notes.enc_v` — version du format de `encrypted_content`.
  ///
  ///   1 = contenu seul dans le blob, titre en clair dans la colonne `title`
  ///   2 = titre ET contenu dans le blob, colonne `title` vidée
  ///
  /// `DEFAULT 1` : toutes les notes existantes restent lisibles exactement
  /// comme avant. Cette migration ne touche AUCUN blob — elle ne fait
  /// qu'ajouter l'étiquette qui permettra de les distinguer. Le passage
  /// effectif en v2 se fait note par note, à l'ouverture du coffre, seul
  /// moment où la clé existe : une migration de schéma n'a pas accès à la
  /// passphrase et ne peut donc rien déchiffrer.
  ///
  /// Conséquence assumée : un coffre jamais rouvert garde ses titres en
  /// clair dans la colonne. C'est le prix d'une migration qui ne peut pas
  /// perdre de données — l'alternative (tout migrer d'un coup) exigerait
  /// les passphrases de tous les coffres au même instant.
  Future<void> _migrateToV7(Transaction txn) async {
    await txn.execute(
      'ALTER TABLE notes ADD COLUMN enc_v INTEGER NOT NULL DEFAULT 1;',
    );
  }

  Future<void> _migrateToV6(Transaction txn) async {
    // 1. Drop les anciens triggers.
    await txn.execute('DROP TRIGGER IF EXISTS notes_ai;');
    await txn.execute('DROP TRIGGER IF EXISTS notes_au;');
    await txn.execute('DROP TRIGGER IF EXISTS notes_ad;');
    // 2. Recrée avec masquage `encrypted_content IS NOT NULL`.
    await txn.execute(_ftsTriggerInsertSql());
    await txn.execute(_ftsTriggerDeleteSql());
    await txn.execute(_ftsTriggerUpdateSql());
    // 3. Purge les lignes FTS des notes actuellement verrouillées (titres
    // déjà indexés en clair sous le régime v5).
    await txn.execute('''
      INSERT INTO notes_fts(notes_fts, rowid, title, content, tags)
      SELECT 'delete', n.rowid, n.title, n.content, n.tags
        FROM notes n
       WHERE n.encrypted_content IS NOT NULL;
    ''');
    // 4. Réinjecte les notes verrouillées avec titre/tags vidés (FTS doit
    // savoir que la rowid existe pour DELETE futurs cohérents).
    await txn.execute('''
      INSERT INTO notes_fts(rowid, title, content, tags)
      SELECT n.rowid, '', '', ''
        FROM notes n
       WHERE n.encrypted_content IS NOT NULL;
    ''');
  }

  // ── Triggers FTS5 ────────────────────────────────────────────────────
  // F2 v1.0.3 — `CASE WHEN encrypted_content IS NOT NULL` masque
  // title/tags pour les notes verrouillées. Le `content` reste inchangé
  // (déjà vidé au lock côté Dart : `Note.copyWith(content: '')`).
  String _ftsTriggerInsertSql() => '''
    CREATE TRIGGER notes_ai AFTER INSERT ON notes BEGIN
      INSERT INTO notes_fts(rowid, title, content, tags)
      VALUES (
        new.rowid,
        CASE WHEN new.encrypted_content IS NOT NULL THEN '' ELSE new.title END,
        CASE WHEN new.encrypted_content IS NOT NULL THEN '' ELSE new.content END,
        CASE WHEN new.encrypted_content IS NOT NULL THEN '' ELSE new.tags END
      );
    END;
  ''';

  String _ftsTriggerDeleteSql() => '''
    CREATE TRIGGER notes_ad AFTER DELETE ON notes BEGIN
      INSERT INTO notes_fts(notes_fts, rowid, title, content, tags)
      VALUES (
        'delete',
        old.rowid,
        CASE WHEN old.encrypted_content IS NOT NULL THEN '' ELSE old.title END,
        CASE WHEN old.encrypted_content IS NOT NULL THEN '' ELSE old.content END,
        CASE WHEN old.encrypted_content IS NOT NULL THEN '' ELSE old.tags END
      );
    END;
  ''';

  String _ftsTriggerUpdateSql() => '''
    CREATE TRIGGER notes_au AFTER UPDATE ON notes BEGIN
      INSERT INTO notes_fts(notes_fts, rowid, title, content, tags)
      VALUES (
        'delete',
        old.rowid,
        CASE WHEN old.encrypted_content IS NOT NULL THEN '' ELSE old.title END,
        CASE WHEN old.encrypted_content IS NOT NULL THEN '' ELSE old.content END,
        CASE WHEN old.encrypted_content IS NOT NULL THEN '' ELSE old.tags END
      );
      INSERT INTO notes_fts(rowid, title, content, tags)
      VALUES (
        new.rowid,
        CASE WHEN new.encrypted_content IS NOT NULL THEN '' ELSE new.title END,
        CASE WHEN new.encrypted_content IS NOT NULL THEN '' ELSE new.content END,
        CASE WHEN new.encrypted_content IS NOT NULL THEN '' ELSE new.tags END
      );
    END;
  ''';

  /// v2 : créait la table `note_embeddings` (recherche par similarité).
  ///
  /// Devenue vide avec le retrait de l'IA — mais CONSERVÉE : une base restée
  /// en v1 doit pouvoir monter jusqu'à v8, et `_onUpgrade` appelle les paliers
  /// dans l'ordre. Supprimer la méthode casserait la chaîne ; recréer la table
  /// pour la supprimer trois paliers plus loin serait absurde.
  Future<void> _migrateToV2(Transaction txn) async {}

  /// v3 : ajout de la table `note_links` (backlinks `[[Titre]]`).
  Future<void> _migrateToV3(Transaction txn) async {
    await _createLinksTable(txn);
  }

  /// v4 : vault par dossier (Argon2id passphrase distincte → KEK dédiée
  /// → AES-256-GCM par note). Colonnes ajoutées :
  ///   - `folders.vault_salt`         BLOB (16 octets, CSPRNG)
  ///   - `folders.vault_kek_wrapped`  BLOB (folder_kek 32 octets chiffré
  ///                                  par AES-GCM avec la KEK dérivée
  ///                                  Argon2id)
  ///   - `folders.vault_iv`           BLOB (nonce 12 octets du wrap)
  ///   - `folders.vault_verifier`     BLOB (HMAC-SHA-256 d'une constante
  ///                                  avec la folder_kek pour vérifier
  ///                                  une passphrase sans déchiffrer
  ///                                  toutes les notes)
  ///   - `notes.encrypted_content`    BLOB nullable (contenu chiffré
  ///                                  AES-256-GCM avec folder_kek,
  ///                                  AAD = note_id ; quand non-null,
  ///                                  `content` est vide en clair)
  ///
  /// Backward compatibility : toutes les colonnes sont nullable, les
  /// dossiers et notes existants restent fonctionnels (vault_salt NULL
  /// = dossier non-coffre).
  Future<void> _migrateToV4(Transaction txn) async {
    await txn.execute('ALTER TABLE folders ADD COLUMN vault_salt BLOB;');
    await txn.execute('ALTER TABLE folders ADD COLUMN vault_kek_wrapped BLOB;');
    await txn.execute('ALTER TABLE folders ADD COLUMN vault_iv BLOB;');
    await txn.execute('ALTER TABLE folders ADD COLUMN vault_verifier BLOB;');
    await txn.execute('ALTER TABLE notes ADD COLUMN encrypted_content BLOB;');
  }

  /// v5 : mode PIN par coffre (en plus du mode passphrase v4).
  ///   - `folders.vault_mode`       TEXT — 'passphrase' | 'pin' | NULL
  ///   - `folders.vault_pin_blob`   BLOB — wrap de `folder_kek` scellé par
  ///                                Keystore (alias = `vault_pin_<id>`),
  ///                                lui-même protégé par une couche
  ///                                AES-GCM dérivée du PIN via Argon2id
  ///                                (paramètres allégés t=2, m=32MB).
  ///   - `folders.vault_pin_iv`     BLOB — nonce 12 octets (wrap interne)
  ///   - `folders.vault_attempts`   INTEGER — compteur tentatives PIN
  ///                                ratées, reset à 0 sur succès,
  ///                                auto-wipe du coffre à 5.
  ///
  /// Toutes nullables : un coffre passphrase v0.8 reste fonctionnel après
  /// migration (vault_mode défini par défaut à 'passphrase' uniquement
  /// pour les folders ayant déjà des colonnes vault_* non-NULL).
  Future<void> _migrateToV5(Transaction txn) async {
    await txn.execute('ALTER TABLE folders ADD COLUMN vault_mode TEXT;');
    await txn.execute('ALTER TABLE folders ADD COLUMN vault_pin_blob BLOB;');
    await txn.execute('ALTER TABLE folders ADD COLUMN vault_pin_iv BLOB;');
    await txn.execute(
      'ALTER TABLE folders ADD COLUMN vault_attempts INTEGER NOT NULL DEFAULT 0;',
    );
    // Marque les coffres existants v0.8 comme mode passphrase. Un coffre
    // est identifié par la présence de `vault_salt` (NOT NULL).
    await txn.execute(
      "UPDATE folders SET vault_mode = 'passphrase' WHERE vault_salt IS NOT NULL;",
    );
  }

  // ---------------------------------------------------------------------
  // Schéma initial
  // ---------------------------------------------------------------------

  Future<void> _createSchemaV1(Transaction txn) async {
    // Dossiers (avec colonnes vault v0.8 — toutes nullable, NULL = pas
    // un coffre).
    await txn.execute('''
      CREATE TABLE folders (
        id                  TEXT PRIMARY KEY NOT NULL,
        name                TEXT NOT NULL,
        parent_id           TEXT,
        color               INTEGER,
        icon                TEXT,
        created_at          INTEGER NOT NULL,
        updated_at          INTEGER NOT NULL,
        vault_salt          BLOB,
        vault_kek_wrapped   BLOB,
        vault_iv            BLOB,
        vault_verifier      BLOB,
        vault_mode          TEXT,
        vault_pin_blob      BLOB,
        vault_pin_iv        BLOB,
        vault_attempts      INTEGER NOT NULL DEFAULT 0,
        FOREIGN KEY (parent_id) REFERENCES folders(id) ON DELETE SET NULL
      );
    ''');
    await txn.execute('CREATE INDEX idx_folders_parent ON folders(parent_id);');

    // Notes (avec encrypted_content v0.8 — non-null = note verrouillée
    // dans un coffre, content alors vide en clair).
    await txn.execute('''
      CREATE TABLE notes (
        id                  TEXT PRIMARY KEY NOT NULL,
        title               TEXT NOT NULL,
        content             TEXT NOT NULL,
        encrypted_content   BLOB,
        folder_id           TEXT NOT NULL,
        tags                TEXT NOT NULL DEFAULT '',
        pinned              INTEGER NOT NULL DEFAULT 0,
        favorite            INTEGER NOT NULL DEFAULT 0,
        archived            INTEGER NOT NULL DEFAULT 0,
        trashed_at          INTEGER,
        created_at          INTEGER NOT NULL,
        updated_at          INTEGER NOT NULL,
        -- Format du blob `encrypted_content` :
        --   1 = contenu seul (titre en clair dans la colonne `title`)
        --   2 = titre ET contenu dans le blob (`title` vidée)
        -- Colonne de schéma plutôt qu'un marqueur devine dans le blob : un
        -- préfixe de version à l'intérieur du chiffré serait ambigu avec un
        -- nonce commençant par la même valeur, et un contenu clair peut
        -- toujours imiter n'importe quelle enveloppe.
        enc_v               INTEGER NOT NULL DEFAULT 1,
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

    // Triggers : maintien de l'index FTS5 synchrone.
    // F2 v1.0.3 — masquage `title`/`tags` pour notes verrouillées
    // (`encrypted_content IS NOT NULL`). Avant : seul `content` était
    // vidé au lock → l'index FTS5 retournait quand même la note via
    // une recherche sur le titre, brisant la promesse de non-divulgation.
    await txn.execute(_ftsTriggerInsertSql());
    await txn.execute(_ftsTriggerDeleteSql());
    await txn.execute(_ftsTriggerUpdateSql());

    // Embeddings — table v2 (créée d'emblée pour les nouvelles installations).

    // Liens entre notes — table v3.
    await _createLinksTable(txn);

    // Dossier racine par défaut.
    await _ensureInboxFolder(txn);
  }

  /// Garantit l'existence du dossier racine `inbox`. Idempotent — peut être
  /// rappelé à chaque ouverture sans risque (INSERT OR IGNORE).
  Future<void> _ensureInboxFolder(DatabaseExecutor txn) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    await txn.rawInsert(
      '''
      INSERT OR IGNORE INTO folders
        (id, name, parent_id, color, icon, created_at, updated_at)
      VALUES (?, ?, NULL, NULL, ?, ?, ?);
    ''',
      ['inbox', AppConstants.inboxDefaultName, 'inbox', now, now],
    );
  }

  /// Liens `[[Titre]]` extraits des notes.
  ///
  /// `target_id` peut être NULL si le lien pointe vers un titre qui
  /// n'existe pas encore (lien fantôme — sera résolu si l'utilisateur
  /// crée une note avec ce titre plus tard).
  /// `target_title_norm` est la version normalisée (lowercase + accents
  /// dépouillés) du titre cible — utilisée pour le matching insensible
  /// à la casse et aux diacritiques.
  Future<void> _createLinksTable(Transaction txn) async {
    await txn.execute('''
      CREATE TABLE note_links (
        source_id          TEXT NOT NULL,
        target_id          TEXT,
        target_title       TEXT NOT NULL,
        target_title_norm  TEXT NOT NULL,
        position           INTEGER NOT NULL,
        FOREIGN KEY (source_id) REFERENCES notes(id) ON DELETE CASCADE,
        FOREIGN KEY (target_id) REFERENCES notes(id) ON DELETE SET NULL
      );
    ''');
    await txn.execute(
      'CREATE INDEX idx_links_source ON note_links(source_id);',
    );
    await txn.execute(
      'CREATE INDEX idx_links_target ON note_links(target_id);',
    );
    await txn.execute(
      'CREATE INDEX idx_links_target_norm ON note_links(target_title_norm);',
    );
  }
}
