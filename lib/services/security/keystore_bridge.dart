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
  ///
  /// Lève [KeystoreSoftwareOnlyException] si le device n'a pas de
  /// Keystore hardware-backed — le caller doit alors proposer un coffre
  /// passphrase (Argon2id) au lieu d'un PIN.
  Future<bool> createKey(String alias) async {
    try {
      final ok = await _channel.invokeMethod<bool>('createKey', {
        'alias': alias,
      });
      return ok ?? false;
    } on PlatformException catch (e) {
      throw _mapPlatformException(e, 'createKey');
    }
  }

  /// Vérifie qu'une clé existe pour cet alias (sans la matérialiser).
  Future<bool> hasKey(String alias) async {
    final has = await _channel.invokeMethod<bool>('hasKey', {'alias': alias});
    return has ?? false;
  }

  /// Chiffre `plaintext` avec la clé Keystore liée à `alias`. Le nonce
  /// (12 octets) est généré côté natif à chaque appel.
  ///
  /// Les `Uint8List` retournés sont **systématiquement copiés** via
  /// `Uint8List.fromList` : sur certaines versions de Flutter / Android,
  /// les buffers du codec MethodChannel sont des vues unmodifiables
  /// (backed par `_ByteBuffer`), ce qui fait planter tout `fillRange()`
  /// ultérieur (utilisé par `_wipe()`) avec
  /// `Unsupported operation: Cannot modify an unmodifiable list`.
  Future<KeystoreWrapResult> wrap(String alias, Uint8List plaintext) async {
    try {
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
      return KeystoreWrapResult(
        ciphertext: Uint8List.fromList(ct),
        nonce: Uint8List.fromList(nonce),
      );
    } on PlatformException catch (e) {
      throw _mapPlatformException(e, 'wrap');
    }
  }

  /// Déchiffre. Lève :
  ///  - [KeyPermanentlyInvalidatedException] si la clé Keystore a été
  ///    invalidée par l'OS (changement écran de verrouillage, factory
  ///    reset partiel, biométrie retirée). Wipe LÉGITIME du coffre.
  ///  - [KeystoreSoftwareOnlyException] si la création de clé a refusé un
  ///    Keystore software-only (pas de TEE/StrongBox).
  ///  - [KeystoreTransientException] sur exceptions transitoires
  ///    (StrongBox→TEE migration après OTA Samsung One UI, lockscreen
  ///    verrouillé bloquant l'accès, OOM JNI). Le caller DOIT rollback
  ///    `vault_attempts` et NE PAS auto-wipe — réessayer plus tard.
  ///  - [KeystoreException] (générique) sinon (mauvais alias, tampering
  ///    AES-GCM, format invalide).
  ///
  /// Le retour est copié en `Uint8List.fromList` pour permettre les
  /// `_wipe()` ultérieurs (cf. note dans [wrap] sur l'unmodifiability
  /// des buffers MethodChannel).
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
      return Uint8List.fromList(res);
    } on PlatformException catch (e) {
      throw _mapPlatformException(e, 'unwrap');
    }
  }

  /// Mappe une [PlatformException] depuis le canal Kotlin vers une
  /// exception Dart typée.
  /// Le code natif renvoie `e.javaClass.simpleName` comme `code`.
  static KeystoreException _mapPlatformException(
    PlatformException e,
    String op,
  ) {
    final code = e.code;
    final msg = e.message ?? '$op failed';
    if (code == 'KEY_PERMANENTLY_INVALIDATED' ||
        code == 'KeyPermanentlyInvalidatedException') {
      return KeyPermanentlyInvalidatedException(msg);
    }
    // Le code Kotlin throw `IllegalStateException("KEYSTORE_SOFTWARE_ONLY")`
    // si le device n'a pas de TEE/StrongBox.
    if (code == 'IllegalStateException' && msg.contains('KEYSTORE_SOFTWARE_ONLY')) {
      return const KeystoreSoftwareOnlyException();
    }
    // Liste blanche d'exceptions transitoires connues — toute autre
    // KeystoreException reste générique (= comportement antérieur).
    const transient = {
      'UserNotAuthenticatedException',
      'KeyStoreException',
      'ProviderException',
      'IllegalStateException', // catch-all transitoire (sauf SOFTWARE_ONLY ci-dessus)
    };
    if (transient.contains(code)) {
      return KeystoreTransientException(msg);
    }
    return KeystoreException(msg);
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

/// La clé Keystore a été invalidée définitivement par l'OS (changement
/// écran de verrouillage, factory reset partiel, biométrie retirée).
/// Le coffre PIN doit être considéré comme PERDU. Wipe LÉGITIME.
class KeyPermanentlyInvalidatedException extends KeystoreException {
  const KeyPermanentlyInvalidatedException(super.message);
  @override
  String toString() => 'KeyPermanentlyInvalidatedException: $message';
}

/// Exception transitoire Keystore (OTA en cours, lockscreen, OOM JNI).
/// Le caller DOIT rollback `vault_attempts` et proposer à l'utilisateur
/// de réessayer plus tard. NE PAS auto-wipe.
class KeystoreTransientException extends KeystoreException {
  const KeystoreTransientException(super.message);
  @override
  String toString() => 'KeystoreTransientException: $message';
}

/// Le device n'a pas de Keystore hardware-backed (TEE/StrongBox).
/// Le mode PIN est désactivé pour ce device — proposer le mode passphrase
/// (Argon2id RFC 9106 m=64MB t=3, qui ne dépend pas du Keystore).
class KeystoreSoftwareOnlyException extends KeystoreException {
  const KeystoreSoftwareOnlyException()
      : super('Device has no hardware-backed Keystore (TEE/StrongBox).');
  @override
  String toString() => 'KeystoreSoftwareOnlyException: $message';
}
