/// Service de gestion de la clé maître (KEK) chiffrant la base SQLite.
///
/// Architecture :
///   - 32 octets aléatoires (CSPRNG `Random.secure`) générés au tout
///     premier lancement.
///   - Stockés dans `flutter_secure_storage` qui s'appuie côté Android
///     sur `EncryptedSharedPreferences` — clé maître scellée par
///     `AndroidKeystore` (hardware-backed sur S24).
///   - Encodage hexadécimal lors de la persistance pour rester compatible
///     avec l'API SharedPreferences (string-only).
///   - L'API publique retourne la KEK en `Uint8List` ; l'appelant est
///     responsable du wipe (zeroization) après usage. La méthode
///     `wipe(Uint8List)` est fournie pour standardiser ce geste.
///
/// Garanties :
///   - **Idempotent** : `getOrCreateKek` ne génère qu'une seule fois.
///   - **Concurrence-safe** : appels parallèles partagent un Future
///     unique pendant la génération initiale.
///   - **Pas de log de la KEK** : aucune valeur sensible n'est jamais
///     transmise à `debugPrint` ou aux exceptions toString().
///
/// Cette classe n'a pas connaissance de la base SQLite ; le câblage
/// réel se fait dans `AppDatabase.open(kek)`.
library;

import 'dart:async';
import 'dart:typed_data';

import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class VaultService {
  VaultService({FlutterSecureStorage? storage})
      : _storage = storage ?? _defaultStorage();

  final FlutterSecureStorage _storage;

  static const String _kekStorageKey = 'notes_tech.vault.kek.v1';

  /// Taille de la clé : 32 octets = 256 bits, exigé par sqlcipher
  /// quand on lui passe une clé brute (`PRAGMA key = "x'...'"`).
  static const int _kekLengthBytes = 32;

  Future<Uint8List>? _inflight;

  static FlutterSecureStorage _defaultStorage() => const FlutterSecureStorage(
        aOptions: AndroidOptions(
          encryptedSharedPreferences: true,
          // `keyCipherAlgorithm` et `storageCipherAlgorithm` laissent
          // les défauts les plus récents (RSA_ECB_PKCS1Padding pour le
          // wrap key, AES_256_GCM_NoPadding pour le contenu).
          resetOnError: false,
        ),
      );

  /// Récupère la KEK existante ou en génère une nouvelle au premier appel.
  /// Retourne une **copie** : l'appelant peut wiper sans corrompre l'état
  /// interne.
  Future<Uint8List> getOrCreateKek() async {
    final inflight = _inflight;
    if (inflight != null) return inflight;
    final completer = Completer<Uint8List>();
    _inflight = completer.future;
    try {
      final existing = await _storage.read(key: _kekStorageKey);
      if (existing != null && existing.length == _kekLengthBytes * 2) {
        completer.complete(_decodeHex(existing));
      } else {
        final fresh = _generateKek();
        await _storage.write(
          key: _kekStorageKey,
          value: _encodeHex(fresh),
        );
        completer.complete(fresh);
      }
    } catch (e, st) {
      completer.completeError(e, st);
    } finally {
      _inflight = null;
    }
    return completer.future;
  }

  /// Détruit la KEK persistée. Action irréversible — toutes les données
  /// chiffrées avec cette clé deviennent inaccessibles.
  /// Utilisé par le mode panique (cf. `PanicStep.kekDestroy` dans
  /// `panic_service.dart`) ou un reset utilisateur.
  Future<void> destroyKek() async {
    await _storage.delete(key: _kekStorageKey);
  }

  /// Vérifie qu'une KEK existe sans la matérialiser.
  Future<bool> hasKek() async {
    final v = await _storage.read(key: _kekStorageKey);
    return v != null && v.length == _kekLengthBytes * 2;
  }

  /// Remplit `bytes` de zéros. À appeler dès que la KEK n'est plus
  /// nécessaire en mémoire (ex. après ouverture de la DB).
  /// Forwarder vers `SecretBytes.wipe` pour cohérence Files Tech.
  static void wipe(Uint8List bytes) => SecretBytes.wipe(bytes);

  // ---------------------------------------------------------------------

  static Uint8List _generateKek() => SecretBytes.randomBytes(_kekLengthBytes);

  static String _encodeHex(Uint8List bytes) {
    final buf = StringBuffer();
    for (final b in bytes) {
      buf.write(b.toRadixString(16).padLeft(2, '0'));
    }
    return buf.toString();
  }

  /// Décodage hex sans fuite : `int.parse` rejette tout caractère
  /// hors `[0-9a-fA-F]` mais pourrait inclure le fragment fautif dans
  /// son `FormatException.toString()`. On rethrow une exception générique
  /// pour ne pas faire fuiter d'octets de la KEK persistée.
  static Uint8List _decodeHex(String hex) {
    if (hex.length.isOdd) {
      throw const FormatException('KEK persistée : longueur invalide.');
    }
    final out = Uint8List(hex.length ~/ 2);
    try {
      for (var i = 0; i < out.length; i++) {
        out[i] = int.parse(hex.substring(i * 2, i * 2 + 2), radix: 16);
      }
    } catch (_) {
      throw const FormatException('KEK persistée : encodage corrompu.');
    }
    return out;
  }
}
