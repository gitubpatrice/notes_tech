/// Pont Dart vers `KeystoreBridge.kt` (AndroidKeyStore).
///
/// Une instance par alias suffit ; la classe est sans état (les clés
/// résident côté natif). Tous les appels sont asynchrones (round-trip
/// JNI ~5-30 ms).
///
/// Usage typique (mode PIN d'un coffre v0.9) :
/// ```dart
/// final bridge = KeystoreBridge();
/// await bridge.createKey('vault_pin_<folder_id>');
/// final wrapped = await bridge.wrap(alias, plaintext);
/// // wrapped.ciphertext + wrapped.nonce sont persistés en DB
/// ...
/// final clear = await bridge.unwrap(alias, wrapped.ciphertext, wrapped.nonce);
/// ```
library;

import 'package:flutter/services.dart';

class KeystoreWrapResult {
  const KeystoreWrapResult({required this.ciphertext, required this.nonce});
  final Uint8List ciphertext;
  final Uint8List nonce;
}

class KeystoreBridge {
  KeystoreBridge();

  static const _channel = MethodChannel('notes_tech/keystore');

  /// Crée une clé AES-256-GCM dans AndroidKeyStore (StrongBox si dispo,
  /// fallback TEE software). Retourne `true` si nouvellement créée,
  /// `false` si l'alias existait déjà (idempotent).
  Future<bool> createKey(String alias) async {
    final ok = await _channel.invokeMethod<bool>('createKey', {
      'alias': alias,
    });
    return ok ?? false;
  }

  /// Vérifie qu'une clé existe pour cet alias (sans la matérialiser).
  Future<bool> hasKey(String alias) async {
    final has = await _channel.invokeMethod<bool>('hasKey', {'alias': alias});
    return has ?? false;
  }

  /// Chiffre `plaintext` avec la clé Keystore liée à `alias`. Le nonce
  /// (12 octets) est généré côté natif à chaque appel.
  Future<KeystoreWrapResult> wrap(String alias, Uint8List plaintext) async {
    final res = await _channel.invokeMapMethod<String, Object?>('wrap', {
      'alias': alias,
      'plaintext': plaintext,
    });
    if (res == null) {
      throw const KeystoreException('wrap returned null');
    }
    final ct = res['ciphertext'];
    final nonce = res['nonce'];
    if (ct is! Uint8List || nonce is! Uint8List) {
      throw const KeystoreException('wrap returned malformed result');
    }
    return KeystoreWrapResult(ciphertext: ct, nonce: nonce);
  }

  /// Déchiffre. Lève [KeystoreException] si la clé n'existe plus
  /// (auto-wipe précédent), ou si l'authentification GCM échoue
  /// (tampering / mauvais alias).
  Future<Uint8List> unwrap(
    String alias,
    Uint8List ciphertext,
    Uint8List nonce,
  ) async {
    try {
      final res = await _channel.invokeMethod<Uint8List>('unwrap', {
        'alias': alias,
        'ciphertext': ciphertext,
        'nonce': nonce,
      });
      if (res == null) {
        throw const KeystoreException('unwrap returned null');
      }
      return res;
    } on PlatformException catch (e) {
      throw KeystoreException(e.message ?? 'unwrap failed');
    }
  }

  /// Supprime la clé Keystore liée à `alias`. Idempotent : no-op si
  /// l'alias n'existe pas. Utilisé par l'auto-wipe d'un coffre PIN.
  Future<void> deleteKey(String alias) async {
    await _channel.invokeMethod<void>('deleteKey', {'alias': alias});
  }

  /// Supprime **toutes** les clés Keystore dont l'alias commence par
  /// [prefix]. Utilisé par le mode panique pour wiper d'un coup toutes
  /// les `vault_pin_*` sans dépendre de la DB. Retourne le nombre de
  /// clés effectivement supprimées (0 si aucune ne matchait).
  Future<int> deleteKeysWithPrefix(String prefix) async {
    final n = await _channel.invokeMethod<int>('deleteKeysWithPrefix', {
      'prefix': prefix,
    });
    return n ?? 0;
  }
}

class KeystoreException implements Exception {
  const KeystoreException(this.message);
  final String message;
  @override
  String toString() => 'KeystoreException: $message';
}
