/// Modèle immuable d'un dossier (carnet) de notes.
library;

import 'package:flutter/foundation.dart';

/// Mode de protection d'un coffre.
///
/// - [VaultMode.passphrase] (v0.8) : Argon2id RFC 9106 (m=64MB, t=3) sur la
///   passphrase utilisateur (8+ caractères) → wrap_kek → wrap de la
///   `folder_kek`. Robuste seul, mais dérivation 1-2 s sur S9.
/// - [VaultMode.pin] (v0.9) : PIN 4-6 chiffres + Argon2id allégé (t=2,
///   m=32MB) + scellage AndroidKeystore (device-bound). Le device est
///   exigé pour toute tentative ; la faible entropie du PIN est
///   compensée par le rate-limit applicatif (auto-wipe à 5 fails).
enum VaultMode {
  passphrase,
  pin;

  static VaultMode? fromDb(String? raw) => switch (raw) {
        'passphrase' => VaultMode.passphrase,
        'pin' => VaultMode.pin,
        _ => null,
      };

  String get dbValue => switch (this) {
        VaultMode.passphrase => 'passphrase',
        VaultMode.pin => 'pin',
      };
}

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
    this.vaultMode,
    this.vaultPinBlob,
    this.vaultPinIv,
    this.vaultAttempts = 0,
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
  ///   à partir de la passphrase OU du PIN utilisateur.
  /// - [vaultKekWrapped] : la `folder_kek` 32 octets (AES-256), elle-même
  ///   chiffrée AES-256-GCM avec la KEK dérivée Argon2id (AAD = id).
  ///   **Mode passphrase uniquement** — null en mode PIN (le wrap PIN
  ///   est dans [vaultPinBlob]).
  /// - [vaultIv] : nonce 12 octets du wrap GCM ci-dessus.
  /// - [vaultVerifier] : HMAC-SHA-256 d'une constante avec la `folder_kek`
  ///   pour valider rapidement une passphrase/PIN saisi sans déchiffrer
  ///   toutes les notes (échec → mauvais secret, abort proprement).
  ///
  /// Tous-NULL = dossier ordinaire (et [vaultMode] est lui aussi null).
  final Uint8List? vaultSalt;
  final Uint8List? vaultKekWrapped;
  final Uint8List? vaultIv;
  final Uint8List? vaultVerifier;

  /// v0.9 — mode du coffre. `null` = dossier ordinaire.
  final VaultMode? vaultMode;

  /// v0.9 — wrap **mode PIN** : la `folder_kek` est doublement scellée :
  ///   1. AES-GCM avec la KEK dérivée Argon2id du PIN (AAD = id) →
  ///      blob intermédiaire ;
  ///   2. wrap final via AndroidKeystore (alias = `vault_pin_<id>`,
  ///      AES-GCM 256, IV généré côté Keystore) → `vaultPinBlob`.
  /// La couche 2 rend le bruteforce hors-device impossible (clé Keystore
  /// non-extractible). La couche 1 ajoute le secret PIN au facteur device.
  final Uint8List? vaultPinBlob;
  final Uint8List? vaultPinIv;

  /// v0.9 — compteur de tentatives PIN ratées. Reset à 0 sur succès,
  /// déclenche l'auto-wipe à `vaultPinMaxAttempts` (= 5). Toujours 0
  /// pour un coffre passphrase.
  final int vaultAttempts;

  /// `true` si le dossier est marqué comme coffre. La présence de
  /// [vaultSalt] est la source de vérité (les autres champs sont
  /// dépendants par invariant).
  bool get isVault => vaultSalt != null;

  /// `true` si le coffre est en mode PIN (par opposition à passphrase).
  /// Faux pour un dossier ordinaire (non-coffre).
  bool get isPinVault => vaultMode == VaultMode.pin;

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
    VaultMode? vaultMode,
    Uint8List? vaultPinBlob,
    Uint8List? vaultPinIv,
    int? vaultAttempts,
    // Sentinels pour effacer un champ (passer à null) — utile lors de la
    // conversion coffre → dossier ordinaire, ou conversion PIN → passphrase.
    bool clearVault = false,
    bool clearPinFields = false,
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
      vaultMode: clearVault ? null : (vaultMode ?? this.vaultMode),
      vaultPinBlob: (clearVault || clearPinFields)
          ? null
          : (vaultPinBlob ?? this.vaultPinBlob),
      vaultPinIv: (clearVault || clearPinFields)
          ? null
          : (vaultPinIv ?? this.vaultPinIv),
      vaultAttempts: clearVault ? 0 : (vaultAttempts ?? this.vaultAttempts),
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
        'vault_mode': vaultMode?.dbValue,
        'vault_pin_blob': vaultPinBlob,
        'vault_pin_iv': vaultPinIv,
        'vault_attempts': vaultAttempts,
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
        vaultMode: VaultMode.fromDb(row['vault_mode'] as String?),
        vaultPinBlob: _asBytes(row['vault_pin_blob']),
        vaultPinIv: _asBytes(row['vault_pin_iv']),
        vaultAttempts: (row['vault_attempts'] as int?) ?? 0,
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
