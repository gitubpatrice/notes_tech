/// Export Markdown des notes — service pur (logique testable sans I/O).
///
/// Format produit :
///   - Markdown standard avec **frontmatter YAML** en tête (compatible
///     Obsidian, Logseq, Bear, Foam, Dendron).
///   - Champs frontmatter : `title`, `folder`, `tags`, `created`, `updated`,
///     `pinned`, `favorite`. Plus, plus tard : `versioning`.
///   - Encoding : UTF-8.
///   - Filename : titre sanitisé + `.md`. Si le titre est vide ou
///     entièrement composé de caractères invalides, fallback sur l'`id`
///     court de la note (8 premiers caractères) pour garantir l'unicité.
///
/// Choix techniques :
/// - Pas de dépendance à un package YAML : on échappe manuellement les
///   guillemets et on encadre toujours les chaînes en double-quotes pour
///   éviter les pièges YAML (les `:` dans un titre, les valeurs `true`/
///   `false` interprétées comme booléens, etc.).
/// - Pas de Provider / context ici : `NoteExportService` prend des Note
///   et Folder en paramètres et retourne des bytes / String. La couche UI
///   décide où écrire et comment partager.
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';
import 'package:flutter/foundation.dart' show compute;

import '../../core/constants.dart';
import '../../data/models/folder.dart';
import '../../data/models/note.dart';
import '../security/folder_vault_service.dart';

/// Constante non-localisée utilisée comme nom de dossier ZIP pour la
/// boîte de réception. Choix « technique » lowercase pour éviter de
/// transporter une localisation FR/EN dans le service pur ; la couche UI
/// localise quand elle affiche, le ZIP reste portable et déterministe.
const String _kInboxFolderDirName = 'inbox';

/// Résultat d'un export ZIP global. Sert à la couche UI pour informer
/// honnêtement l'utilisateur du nombre de notes skippées (coffres
/// verrouillés non déverrouillés au moment de l'export).
class ExportResult {
  const ExportResult({
    required this.zipBytes,
    required this.exportedCount,
    required this.skippedVaultedCount,
  });

  /// Bytes du ZIP prêts à écrire / partager.
  final Uint8List zipBytes;

  /// Nombre de notes effectivement incluses dans le ZIP.
  final int exportedCount;

  /// Nombre de notes ignorées car appartenant à un coffre verrouillé
  /// au moment de l'export. Pour ne pas exporter du blob AES-GCM
  /// illisible (UX dégradée), elles sont omises et ce compteur sert à
  /// avertir l'utilisateur via SnackBar / dialog.
  final int skippedVaultedCount;
}

class NoteExportService {
  const NoteExportService();

  /// Caractères de contrôle bidi/RTL et BOM, à filtrer dans tout nom de
  /// fichier ou de dossier exporté pour empêcher l'usurpation visuelle.
  /// Escapes Unicode obligatoires (sinon ces caractères, eux-mêmes
  /// invisibles, masqueraient leur propre rôle dans le code).
  ///
  ///   - U+202A LRE  (Left-to-Right Embedding)
  ///   - U+202B RLE  (Right-to-Left Embedding)
  ///   - U+202C PDF  (Pop Directional Formatting)
  ///   - U+202D LRO  (Left-to-Right Override)
  ///   - U+202E RLO  (Right-to-Left Override) — **classique** pour faire
  ///     `note<RLO>gpj.md` apparaître `note.mdgpj`
  ///   - U+2066 LRI / U+2067 RLI / U+2068 FSI / U+2069 PDI
  ///   - U+FEFF BOM / ZWNBSP
  static final RegExp _bidiPattern = RegExp(
    '[\u{202A}-\u{202E}\u{2066}-\u{2069}\u{FEFF}]',
  );

  /// Construit le contenu Markdown complet d'une note (frontmatter + corps).
  /// Le [folder] est optionnel : si fourni, son nom est inclus dans le
  /// frontmatter. Sinon, on écrit l'id `inbox` quand l'id matche
  /// [AppConstants.inboxFolderId] — la couche UI localise à l'affichage.
  ///
  /// [inboxFallbackName] permet de surcharger le label inbox (la couche
  /// UI peut passer `t.homeFolderInbox` pour un frontmatter localisé).
  /// [vaultMention] est ajouté en commentaire YAML quand la note vient
  /// d'un coffre déverrouillé (cf. `ExportResult` / `exportAllAsZip`).
  String renderNoteAsMarkdown(
    Note note, {
    Folder? folder,
    String? inboxFallbackName,
    String? vaultMention,
  }) {
    final folderLabel =
        folder?.name ??
        (note.folderId == AppConstants.inboxFolderId
            ? (inboxFallbackName ?? _kInboxFolderDirName)
            : note.folderId);

    final buf = StringBuffer()
      ..writeln('---')
      ..writeln('title: ${_yamlString(note.title)}')
      ..writeln('folder: ${_yamlString(folderLabel)}');

    if (note.tags.isNotEmpty) {
      buf
        ..write('tags: [')
        ..write(note.tags.map(_yamlString).join(', '))
        ..writeln(']');
    } else {
      buf.writeln('tags: []');
    }

    buf
      ..writeln('created: ${note.createdAt.toIso8601String()}')
      ..writeln('updated: ${note.updatedAt.toIso8601String()}');
    if (note.pinned) buf.writeln('pinned: true');
    if (note.favorite) buf.writeln('favorite: true');
    if (vaultMention != null && vaultMention.isNotEmpty) {
      // Commentaire YAML — non-clé pour ne pas polluer les imports
      // Obsidian/Logseq, mais visible à l'œil humain qui dézippe.
      buf.writeln('# ${vaultMention.replaceAll('\n', ' ')}');
    }
    buf
      ..writeln('---')
      ..writeln()
      ..write(note.content);

    // Garantit un saut de ligne final (convention POSIX, certains parseurs
    // râlent sur l'absence de trailing newline).
    if (!note.content.endsWith('\n')) buf.writeln();
    return buf.toString();
  }

  /// Nom de fichier sécurisé pour FAT32/exFAT/ext4/APFS.
  ///
  /// Filtres appliqués :
  /// - **Caractères réservés Windows** : `< > : " / \ | ? *`
  /// - **Caractères de contrôle** : `\x00`-`\x1f`, `\x7f`
  /// - **Bidi/RTL Unicode** (U+202A-U+202E LRE/RLE/PDF/LRO/RLO,
  ///   U+2066-U+2069 LRI/RLI/FSI/PDI, U+FEFF BOM/ZWNBSP) — vecteur
  ///   d'usurpation visuelle : un nom `note<U+202E>gpj.md` s'afficherait
  ///   `note.mdgpj` dans le sheet de partage Drive ou Files.
  /// - **`.` et `..`** standalone (interprétés comme parent/courant par
  ///   les FS et les utilitaires de dézippage).
  /// - **Compression** des espaces multiples + `trim`.
  /// - **Troncature** à 80 caractères (marge sous la limite 255 octets).
  /// - **Noms réservés Windows** (CON, PRN, COM1-9, LPT1-9...).
  ///
  /// Fallback sur [fallbackId] si vide après nettoyage. Si
  /// [unlockedVaultSuffix] vaut `true`, le suffixe ` [déverrouillé]` est
  /// ajouté avant l'extension pour signaler visuellement à l'utilisateur
  /// qui dézippe que la note venait d'un coffre déverrouillé au moment
  /// de l'export.
  String safeFileName(
    String title, {
    required String fallbackId,
    bool unlockedVaultSuffix = false,
  }) {
    var clean = title.trim();
    // 1. Retire caractères réservés + contrôle ASCII (et DEL = 0x7f).
    clean = clean.replaceAll(RegExp(r'[<>:"/\\|?*\x00-\x1f\x7f]'), '');
    // 2. Retire les overrides bidirectionnels et BOM (anti-spoofing).
    //    Escapes Unicode pour rester visibles dans le code source.
    clean = clean.replaceAll(_bidiPattern, '');
    // 3. Compresse les espaces.
    clean = clean.replaceAll(RegExp(r'\s+'), ' ').trim();
    // 4. Tronque (marge UTF-8 ↔ 255 octets).
    if (clean.length > 80) clean = clean.substring(0, 80).trim();
    // 5. Rejet `.` `..` qui interpréteraient comme parent/courant
    //    après concat dans un chemin ZIP. Suffit le strict equality
    //    car on a déjà strippé les `/` plus haut.
    if (clean == '.' || clean == '..') clean = '';
    // 6. Garde-fou noms réservés Windows (case-insensitive).
    const reserved = {
      'CON',
      'PRN',
      'AUX',
      'NUL',
      'COM1',
      'COM2',
      'COM3',
      'COM4',
      'COM5',
      'COM6',
      'COM7',
      'COM8',
      'COM9',
      'LPT1',
      'LPT2',
      'LPT3',
      'LPT4',
      'LPT5',
      'LPT6',
      'LPT7',
      'LPT8',
      'LPT9',
    };
    if (clean.isEmpty || reserved.contains(clean.toUpperCase())) {
      clean = 'note-${fallbackId.replaceAll('-', '').substring(0, 8)}';
    }
    if (unlockedVaultSuffix) {
      clean = '$clean [unlocked]';
    }
    return '$clean.md';
  }

  /// Sérialise une note en bytes UTF-8 prêts à écrire sur disque.
  Uint8List exportNoteAsBytes(
    Note note, {
    Folder? folder,
    String? inboxFallbackName,
    String? vaultMention,
  }) {
    return Uint8List.fromList(
      utf8.encode(
        renderNoteAsMarkdown(
          note,
          folder: folder,
          inboxFallbackName: inboxFallbackName,
          vaultMention: vaultMention,
        ),
      ),
    );
  }

  /// Construit un ZIP pure-Dart contenant toutes les [notes], rangées en
  /// sous-dossiers correspondant à leur [Folder]. Les notes en corbeille
  /// sont exclues par le caller (passer une liste filtrée).
  ///
  /// Le ZIP inclut un fichier `README.md` racine avec date d'export et
  /// total de notes, pour aider l'utilisateur qui dézippe à 6 mois.
  Uint8List exportAsZip({
    required List<Note> notes,
    required Map<String, Folder> foldersById,
    Set<String> vaultedDecryptedFolderIds = const <String>{},
    String? inboxFallbackName,
    String Function(String folderName)? vaultMentionBuilder,
  }) {
    final archive = Archive();
    final now = DateTime.now();
    final usedNames = <String, int>{}; // collisions : suffixe -2, -3...

    for (final note in notes) {
      final folder = foldersById[note.folderId];
      final folderName = _safeFolderDirName(folder, note.folderId);
      final fromUnlockedVault = vaultedDecryptedFolderIds.contains(
        note.folderId,
      );
      final baseName = safeFileName(
        note.title,
        fallbackId: note.id,
        unlockedVaultSuffix: fromUnlockedVault,
      );
      final unique = _disambiguate(usedNames, '$folderName/$baseName');
      final mention = fromUnlockedVault && vaultMentionBuilder != null
          ? vaultMentionBuilder(folder?.name ?? folderName)
          : null;
      final bytes = exportNoteAsBytes(
        note,
        folder: folder,
        inboxFallbackName: inboxFallbackName,
        vaultMention: mention,
      );
      archive.addFile(ArchiveFile(unique, bytes.length, bytes));
    }

    final readmeBytes = utf8.encode(_buildReadme(notes.length, now));
    archive.addFile(ArchiveFile('README.md', readmeBytes.length, readmeBytes));

    final encoded = ZipEncoder().encode(archive);
    // Si l'archive_4 retourne déjà un Uint8List, on évite la copie
    // (économie de RAM pic sur archives de plusieurs Mo).
    return encoded is Uint8List ? encoded : Uint8List.fromList(encoded);
  }

  /// Variante isolate de [exportAsZip] : encode dans un thread séparé
  /// via `compute()`. À utiliser depuis l'UI pour éviter de bloquer le
  /// thread principal sur 1 000+ notes (jank ~500 ms à plusieurs
  /// secondes sur S9/POCO C75 sinon).
  ///
  /// `Note` et `Folder` sont des objets purs sérialisables (pas de
  /// callbacks ni de streams) — transit isolate sûr.
  static Future<Uint8List> exportAsZipInIsolate({
    required List<Note> notes,
    required Map<String, Folder> foldersById,
    Set<String> vaultedDecryptedFolderIds = const <String>{},
    String? inboxFallbackName,
    String? vaultMentionTemplate,
  }) {
    return compute<_ZipJob, Uint8List>(
      _zipWorker,
      _ZipJob(
        notes: notes,
        foldersById: foldersById,
        vaultedDecryptedFolderIds: vaultedDecryptedFolderIds,
        inboxFallbackName: inboxFallbackName,
        vaultMentionTemplate: vaultMentionTemplate,
      ),
    );
  }

  /// Orchestre un export ZIP **complet** des notes en gérant proprement
  /// les coffres :
  ///
  /// - Notes coffrées (`note.encryptedContent != null`) :
  ///   - Si le dossier parent est **actuellement déverrouillé**, la note
  ///     est déchiffrée via [vault] (le user a saisi sa passphrase
  ///     pendant la session, il a déjà accès au contenu) et incluse en
  ///     Markdown clair. Le nom de fichier porte le suffixe
  ///     ` [unlocked].md` et le frontmatter contient un commentaire
  ///     `# Note du coffre : <folder>`.
  ///   - Sinon, la note est **ignorée** (un blob AES-GCM base64 dans un
  ///     `.md` serait illisible — UX dégradée). Le compteur
  ///     `skippedVaultedCount` permet à la couche UI de prévenir
  ///     l'utilisateur.
  /// - Notes non coffrées : exportées normalement.
  ///
  /// Le déchiffrement nécessite l'accès au `_Session` en RAM du vault
  /// service → ne peut **pas** s'exécuter dans un isolate. La phase
  /// décrypt est faite ici (main thread) ; le ZIP encoding est ensuite
  /// délégué à [exportAsZipInIsolate].
  Future<ExportResult> exportAllAsZip({
    required List<Note> notes,
    required Map<String, Folder> foldersById,
    required FolderVaultService vault,
    String? inboxFallbackName,
    String? vaultMentionTemplate,
  }) async {
    final unlocked = vault.unlockedFolderIds;
    final exportable = <Note>[];
    final decryptedFolderIds = <String>{};
    var skipped = 0;

    for (final note in notes) {
      if (note.encryptedContent == null) {
        exportable.add(note);
        continue;
      }
      // Note coffrée : on tente de la déchiffrer si le coffre est ouvert.
      if (unlocked.contains(note.folderId)) {
        try {
          final clear = await vault.decryptNote(note);
          exportable.add(clear);
          decryptedFolderIds.add(note.folderId);
        } catch (_) {
          // Best-effort : si le déchiffrement échoue (coffre lock entre
          // temps, blob corrompu), on traite comme skip — pas d'export
          // de blob illisible.
          skipped++;
        }
      } else {
        skipped++;
      }
    }

    final zipBytes = await exportAsZipInIsolate(
      notes: exportable,
      foldersById: foldersById,
      vaultedDecryptedFolderIds: decryptedFolderIds,
      inboxFallbackName: inboxFallbackName,
      vaultMentionTemplate: vaultMentionTemplate,
    );

    return ExportResult(
      zipBytes: zipBytes,
      exportedCount: exportable.length,
      skippedVaultedCount: skipped,
    );
  }

  // ---------------------------------------------------------------------
  // Internes
  // ---------------------------------------------------------------------

  String _yamlString(String raw) {
    // Échappe selon YAML 1.2 double-quoted style :
    //   - `\\` d'abord (sinon on double-échappe le `\\` qu'on vient
    //     d'introduire pour les autres séquences)
    //   - puis `"` pour ne pas fermer la chaîne
    //   - puis les caractères de contrôle communs (newline, CR, tab)
    //   - puis tous les autres caractères de contrôle 0x00-0x1F + 0x7F
    //     en `\xNN` (YAML 1.2 le permet) — un parser strict (Logseq)
    //     refuse les caractères de contrôle bruts dans une string.
    var escaped = raw
        .replaceAll('\\', r'\\')
        .replaceAll('"', r'\"')
        .replaceAll('\n', r'\n')
        .replaceAll('\r', r'\r')
        .replaceAll('\t', r'\t');
    escaped = escaped.replaceAllMapped(
      RegExp(r'[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]'),
      (m) => '\\x${m[0]!.codeUnitAt(0).toRadixString(16).padLeft(2, '0')}',
    );
    return '"$escaped"';
  }

  String _safeFolderDirName(Folder? folder, String folderId) {
    // Nom de dossier ZIP non localisé : « inbox » est technique et
    // déterministe, ce qui évite que deux exports (FR / EN) produisent
    // des arborescences différentes. La couche UI traduit à l'affichage.
    final raw =
        folder?.name ??
        (folderId == AppConstants.inboxFolderId
            ? _kInboxFolderDirName
            : folderId);
    var clean = raw.replaceAll(RegExp(r'[<>:"/\\|?*\x00-\x1f\x7f]'), '');
    // Mêmes filtres que `safeFileName` : bidi/RTL + path traversal `..`.
    // Sans le rejet de `..`, un dossier malicieusement nommé `..`
    // produit une entrée ZIP `../note.md` qui s'extrait dans le parent
    // chez les destinataires utilisant un dézippeur naïf (ZipSlip).
    clean = clean.replaceAll(_bidiPattern, '');
    clean = clean.replaceAll(RegExp(r'\s+'), ' ').trim();
    if (clean.isEmpty || clean == '.' || clean == '..') {
      clean = 'sans-dossier';
    }
    return clean;
  }

  String _disambiguate(Map<String, int> used, String path) {
    // Clé du compteur en lowercase : Android FAT32 et Windows extraction
    // sont **case-insensitive**. Sans ça, deux notes "Reiki" et "reiki"
    // dans le même dossier collisionneraient silencieusement (écrasement)
    // chez le destinataire après dézippage. La clé de comptage est en
    // lowercase ; la valeur retournée garde la casse d'origine.
    final key = path.toLowerCase();
    final count = used[key] ?? 0;
    used[key] = count + 1;
    if (count == 0) return path;
    // foo/bar.md → foo/bar-2.md (en gardant l'extension)
    final dot = path.lastIndexOf('.');
    if (dot < 0) return '$path-${count + 1}';
    return '${path.substring(0, dot)}-${count + 1}${path.substring(dot)}';
  }

  String _buildReadme(int noteCount, DateTime exportedAt) {
    final iso = exportedAt.toIso8601String();
    return '# Export Notes Tech\n'
        '\n'
        '- Exporté le : $iso\n'
        '- Nombre de notes : $noteCount\n'
        '\n'
        'Format : un fichier Markdown par note, avec frontmatter YAML\n'
        '(`title`, `folder`, `tags`, `created`, `updated`, `pinned`,\n'
        '`favorite`). Compatible avec Obsidian, Logseq, Bear, Foam.\n'
        '\n'
        'L\'arborescence reflète vos dossiers à la date de l\'export.\n'
        'Les notes en corbeille ne sont PAS incluses.\n'
        '\n'
        'Notes Tech — https://www.files-tech.com\n';
  }
}

// ---------------------------------------------------------------------------
// Worker isolate (top-level requis par `compute`).
// ---------------------------------------------------------------------------

/// DTO sérialisable transmis à l'isolate. Ne contient que des valeurs
/// purement passives (Note, Folder = data classes immuables) → transit
/// SendPort sûr.
class _ZipJob {
  const _ZipJob({
    required this.notes,
    required this.foldersById,
    this.vaultedDecryptedFolderIds = const <String>{},
    this.inboxFallbackName,
    this.vaultMentionTemplate,
  });
  final List<Note> notes;
  final Map<String, Folder> foldersById;
  final Set<String> vaultedDecryptedFolderIds;
  final String? inboxFallbackName;

  /// Template ARB-style avec `{folder}` à substituer (ex. FR
  /// "Note du coffre : {folder}"). Sérialisable cross-isolate
  /// contrairement à un `String Function(String)` qui ne l'est pas.
  final String? vaultMentionTemplate;
}

/// Top-level wrapper requis par `compute` : ré-instancie le service
/// dans l'isolate (stateless, pas de coût) et délègue à la version
/// synchrone qui a fait ses preuves.
Uint8List _zipWorker(_ZipJob job) {
  final tpl = job.vaultMentionTemplate;
  return const NoteExportService().exportAsZip(
    notes: job.notes,
    foldersById: job.foldersById,
    vaultedDecryptedFolderIds: job.vaultedDecryptedFolderIds,
    inboxFallbackName: job.inboxFallbackName,
    vaultMentionBuilder: tpl == null
        ? null
        : (folderName) => tpl.replaceAll('{folder}', folderName),
  );
}
