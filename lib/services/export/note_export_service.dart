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
  /// frontmatter. Sinon, on écrit `folder: "Boîte de réception"` quand
  /// l'id matche [AppConstants.inboxFolderId].
  String renderNoteAsMarkdown(Note note, {Folder? folder}) {
    final folderLabel = folder?.name ??
        (note.folderId == AppConstants.inboxFolderId
            ? 'Boîte de réception'
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
  /// Fallback sur [fallbackId] si vide après nettoyage.
  String safeFileName(String title, {required String fallbackId}) {
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
      'CON', 'PRN', 'AUX', 'NUL',
      'COM1', 'COM2', 'COM3', 'COM4', 'COM5', 'COM6', 'COM7', 'COM8', 'COM9',
      'LPT1', 'LPT2', 'LPT3', 'LPT4', 'LPT5', 'LPT6', 'LPT7', 'LPT8', 'LPT9',
    };
    if (clean.isEmpty || reserved.contains(clean.toUpperCase())) {
      clean = 'note-${fallbackId.replaceAll('-', '').substring(0, 8)}';
    }
    return '$clean.md';
  }

  /// Sérialise une note en bytes UTF-8 prêts à écrire sur disque.
  Uint8List exportNoteAsBytes(Note note, {Folder? folder}) {
    return Uint8List.fromList(
      utf8.encode(renderNoteAsMarkdown(note, folder: folder)),
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
  }) {
    final archive = Archive();
    final now = DateTime.now();
    final usedNames = <String, int>{}; // collisions : suffixe -2, -3...

    for (final note in notes) {
      final folder = foldersById[note.folderId];
      final folderName = _safeFolderDirName(folder, note.folderId);
      final baseName = safeFileName(note.title, fallbackId: note.id);
      final unique = _disambiguate(usedNames, '$folderName/$baseName');
      final bytes = exportNoteAsBytes(note, folder: folder);
      archive.addFile(ArchiveFile(unique, bytes.length, bytes));
    }

    final readmeBytes = utf8.encode(_buildReadme(notes.length, now));
    archive.addFile(
      ArchiveFile('README.md', readmeBytes.length, readmeBytes),
    );

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
  }) {
    return compute<_ZipJob, Uint8List>(
      _zipWorker,
      _ZipJob(notes: notes, foldersById: foldersById),
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
    final raw = folder?.name ??
        (folderId == AppConstants.inboxFolderId
            ? 'Boîte de réception'
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
  const _ZipJob({required this.notes, required this.foldersById});
  final List<Note> notes;
  final Map<String, Folder> foldersById;
}

/// Top-level wrapper requis par `compute` : ré-instancie le service
/// dans l'isolate (stateless, pas de coût) et délègue à la version
/// synchrone qui a fait ses preuves.
Uint8List _zipWorker(_ZipJob job) {
  return const NoteExportService().exportAsZip(
    notes: job.notes,
    foldersById: job.foldersById,
  );
}
