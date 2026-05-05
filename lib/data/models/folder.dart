/// Modèle immuable d'un dossier (carnet) de notes.
library;

import 'package:flutter/foundation.dart';

@immutable
class Folder {
  const Folder({
    required this.id,
    required this.name,
    required this.createdAt,
    required this.updatedAt,
    this.parentId,
    this.color,
    this.icon,
  });

  final String id;
  final String name;
  final String? parentId;
  final int? color;
  final String? icon;
  final DateTime createdAt;
  final DateTime updatedAt;

  Folder copyWith({
    String? name,
    String? parentId,
    int? color,
    String? icon,
    DateTime? updatedAt,
  }) {
    return Folder(
      id: id,
      name: name ?? this.name,
      parentId: parentId ?? this.parentId,
      color: color ?? this.color,
      icon: icon ?? this.icon,
      createdAt: createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }

  Map<String, Object?> toRow() => {
        'id': id,
        'name': name,
        'parent_id': parentId,
        'color': color,
        'icon': icon,
        'created_at': createdAt.millisecondsSinceEpoch,
        'updated_at': updatedAt.millisecondsSinceEpoch,
      };

  factory Folder.fromRow(Map<String, Object?> row) => Folder(
        id: (row['id'] as String?) ?? '',
        name: (row['name'] as String?) ?? '',
        parentId: row['parent_id'] as String?,
        color: row['color'] as int?,
        icon: row['icon'] as String?,
        createdAt: DateTime.fromMillisecondsSinceEpoch(
            (row['created_at'] as int?) ?? 0),
        updatedAt: DateTime.fromMillisecondsSinceEpoch(
            (row['updated_at'] as int?) ?? 0),
      );
}
