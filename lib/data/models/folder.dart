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
    this.vaultSalt,
    this.vaultKekWrapped,
    this.vaultIv,
    this.vaultVerifier,
  });

  final String id;
  final String name;
  final String? parentId;
  final int? color;
  final String? icon;
  final DateTime createdAt;
  final DateTime updatedAt;

  /// v0.8 — coffre-fort.
  ///
  /// Quand `vaultSalt != null`, le dossier est marqué « coffre » :
  /// - [vaultSalt] : 16 octets CSPRNG, sel Argon2id pour dériver la KEK
  ///   à partir de la passphrase utilisateur.
  /// - [vaultKekWrapped] : la `folder_kek` 32 octets (AES-256), elle-même
  ///   chiffrée AES-256-GCM avec la KEK dérivée Argon2id (AAD = id).
  /// - [vaultIv] : nonce 12 octets du wrap GCM ci-dessus.
  /// - [vaultVerifier] : HMAC-SHA-256 d'une constante avec la `folder_kek`
  ///   pour valider rapidement une passphrase saisie sans déchiffrer
  ///   toutes les notes (échec → mauvaise passphrase, abort proprement).
  ///
  /// Tous-NULL = dossier ordinaire. Les 4 champs vont ensemble (soit
  /// tous renseignés, soit tous null — invariants garantis par
  /// `FolderVaultService`).
  final Uint8List? vaultSalt;
  final Uint8List? vaultKekWrapped;
  final Uint8List? vaultIv;
  final Uint8List? vaultVerifier;

  /// `true` si le dossier est marqué comme coffre. La présence de
  /// [vaultSalt] est la source de vérité (les autres champs sont
  /// dépendants par invariant).
  bool get isVault => vaultSalt != null;

  Folder copyWith({
    String? name,
    String? parentId,
    int? color,
    String? icon,
    DateTime? updatedAt,
    Uint8List? vaultSalt,
    Uint8List? vaultKekWrapped,
    Uint8List? vaultIv,
    Uint8List? vaultVerifier,
    // Sentinels pour effacer un champ (passer à null) — utile lors de la
    // conversion coffre → dossier ordinaire.
    bool clearVault = false,
  }) {
    return Folder(
      id: id,
      name: name ?? this.name,
      parentId: parentId ?? this.parentId,
      color: color ?? this.color,
      icon: icon ?? this.icon,
      createdAt: createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
      vaultSalt: clearVault ? null : (vaultSalt ?? this.vaultSalt),
      vaultKekWrapped:
          clearVault ? null : (vaultKekWrapped ?? this.vaultKekWrapped),
      vaultIv: clearVault ? null : (vaultIv ?? this.vaultIv),
      vaultVerifier:
          clearVault ? null : (vaultVerifier ?? this.vaultVerifier),
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
        'vault_salt': vaultSalt,
        'vault_kek_wrapped': vaultKekWrapped,
        'vault_iv': vaultIv,
        'vault_verifier': vaultVerifier,
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
        vaultSalt: _asBytes(row['vault_salt']),
        vaultKekWrapped: _asBytes(row['vault_kek_wrapped']),
        vaultIv: _asBytes(row['vault_iv']),
        vaultVerifier: _asBytes(row['vault_verifier']),
      );

  /// Coerce un BLOB SQLite en `Uint8List`. SQLite peut renvoyer
  /// `List<int>` ou `Uint8List` selon le driver — on normalise.
  static Uint8List? _asBytes(Object? raw) {
    if (raw == null) return null;
    if (raw is Uint8List) return raw;
    if (raw is List<int>) return Uint8List.fromList(raw);
    return null;
  }
}
