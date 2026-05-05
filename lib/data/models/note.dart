/// Modèle immuable d'une note Markdown.
library;

import 'package:flutter/foundation.dart';

@immutable
class Note {
  Note({
    required this.id,
    required this.title,
    required this.content,
    required this.folderId,
    required this.createdAt,
    required this.updatedAt,
    this.tags = const <String>[],
    this.pinned = false,
    this.favorite = false,
    this.archived = false,
    this.trashedAt,
  });

  final String id;
  final String title;
  final String content;
  final String folderId;
  final List<String> tags;
  final bool pinned;
  final bool favorite;
  final bool archived;
  final DateTime? trashedAt;
  final DateTime createdAt;
  final DateTime updatedAt;

  bool get isTrashed => trashedAt != null;
  int get characterCount => content.length;
  int get wordCount {
    if (content.isEmpty) return 0;
    return content.trim().split(RegExp(r'\s+')).length;
  }

  Note copyWith({
    String? title,
    String? content,
    String? folderId,
    List<String>? tags,
    bool? pinned,
    bool? favorite,
    bool? archived,
    DateTime? trashedAt,
    bool clearTrashedAt = false,
    DateTime? updatedAt,
  }) {
    return Note(
      id: id,
      title: title ?? this.title,
      content: content ?? this.content,
      folderId: folderId ?? this.folderId,
      tags: tags ?? this.tags,
      pinned: pinned ?? this.pinned,
      favorite: favorite ?? this.favorite,
      archived: archived ?? this.archived,
      trashedAt: clearTrashedAt ? null : (trashedAt ?? this.trashedAt),
      createdAt: createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }

  Map<String, Object?> toRow() => {
        'id': id,
        'title': title,
        'content': content,
        'folder_id': folderId,
        'tags': tags.join(','),
        'pinned': pinned ? 1 : 0,
        'favorite': favorite ? 1 : 0,
        'archived': archived ? 1 : 0,
        'trashed_at': trashedAt?.millisecondsSinceEpoch,
        'created_at': createdAt.millisecondsSinceEpoch,
        'updated_at': updatedAt.millisecondsSinceEpoch,
      };

  factory Note.fromRow(Map<String, Object?> row) {
    final rawTags = (row['tags'] as String?) ?? '';
    final tags = rawTags.isEmpty
        ? const <String>[]
        : rawTags.split(',').where((t) => t.isNotEmpty).toList(growable: false);
    return Note(
      id: (row['id'] as String?) ?? '',
      title: (row['title'] as String?) ?? '',
      content: (row['content'] as String?) ?? '',
      folderId: (row['folder_id'] as String?) ?? 'inbox',
      tags: tags,
      pinned: (row['pinned'] as int? ?? 0) == 1,
      favorite: (row['favorite'] as int? ?? 0) == 1,
      archived: (row['archived'] as int? ?? 0) == 1,
      trashedAt: row['trashed_at'] == null
          ? null
          : DateTime.fromMillisecondsSinceEpoch(row['trashed_at']! as int),
      createdAt: DateTime.fromMillisecondsSinceEpoch(
          (row['created_at'] as int?) ?? 0),
      updatedAt: DateTime.fromMillisecondsSinceEpoch(
          (row['updated_at'] as int?) ?? 0),
    );
  }

  /// Extrait Markdown nettoyé pour les listes. Calculé une seule fois par instance.
  late final String excerpt = _computeExcerpt(content);

  static final RegExp _reHeader = RegExp(r'^#{1,6}\s+', multiLine: true);
  static final RegExp _reCode = RegExp(r'`{1,3}[^`]*`{1,3}');
  static final RegExp _reLink = RegExp(r'\[([^\]]+)\]\([^)]+\)');
  static final RegExp _reEmphasis = RegExp(r'[*_~>]');
  static final RegExp _reSpaces = RegExp(r'\s+');

  static String _computeExcerpt(String md) {
    if (md.isEmpty) return '';
    final stripped = md
        .replaceAll(_reHeader, '')
        .replaceAll(_reCode, '')
        .replaceAllMapped(_reLink, (m) => m.group(1) ?? '')
        .replaceAll(_reEmphasis, '')
        .replaceAll(_reSpaces, ' ')
        .trim();
    return stripped.length > 200 ? stripped.substring(0, 200) : stripped;
  }
}

enum NoteSortMode {
  updatedDesc,
  updatedAsc,
  createdDesc,
  createdAsc,
  titleAsc,
  titleDesc;

  String get sqlOrderBy => switch (this) {
        NoteSortMode.updatedDesc => 'pinned DESC, updated_at DESC',
        NoteSortMode.updatedAsc => 'pinned DESC, updated_at ASC',
        NoteSortMode.createdDesc => 'pinned DESC, created_at DESC',
        NoteSortMode.createdAsc => 'pinned DESC, created_at ASC',
        NoteSortMode.titleAsc => 'pinned DESC, title COLLATE NOCASE ASC',
        NoteSortMode.titleDesc => 'pinned DESC, title COLLATE NOCASE DESC',
      };

  String get label => switch (this) {
        NoteSortMode.updatedDesc => 'Modifiée — récent',
        NoteSortMode.updatedAsc => 'Modifiée — ancien',
        NoteSortMode.createdDesc => 'Créée — récent',
        NoteSortMode.createdAsc => 'Créée — ancien',
        NoteSortMode.titleAsc => 'Titre A→Z',
        NoteSortMode.titleDesc => 'Titre Z→A',
      };
}
