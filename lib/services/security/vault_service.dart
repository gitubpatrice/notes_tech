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

  // flutter_secure_storage v10+ : EncryptedSharedPreferences (Jetpack Crypto)
  // est déprécié côté lib (sera retiré en v11). Le backend par défaut en 10.x
  // utilise ses propres ciphers (RSA_ECB_OAEPwithSHA_256andMGF1Padding pour
  // wrap key + AES_GCM_NoPadding pour contenu) avec une clé maître scellée
  // par AndroidKeystore. La migration depuis 9.x est AUTOMATIQUE via
  // `migrateOnAlgorithmChange: true` (défaut) : la lib lit la KEK existante
  // (chiffrée par ESP), puis ré-écrit avec les nouveaux ciphers.
  //
  // CRITIQUE : `resetOnError: false` — en 10.x le défaut est `true` ce qui
  // EFFACERAIT silencieusement la KEK en cas d'erreur transitoire de
  // déchiffrement (ex. crash Keystore au boot froid). Si la KEK disparaît,
  // SQLCipher refuse d'ouvrir la base et TOUTES LES NOTES UTILISATEUR
  // deviennent illisibles à jamais. On force donc le comportement
  // « préserver à tout prix » et on laisse l'erreur remonter — l'app
  // affichera un écran d'erreur plutôt que de wiper les données.
  static FlutterSecureStorage _defaultStorage() =>
      const FlutterSecureStorage(aOptions: AndroidOptions(resetOnError: false));

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
      if (existing != null) {
        // Une KEK existe déjà : on DOIT la lire ou échouer franchement.
        // Surtout pas la réécrire silencieusement — ce serait équivalent
        // à un wipe des données utilisateur.
        if (existing.length != _kekLengthBytes * 2) {
          throw const FormatException('KEK persistée : longueur invalide.');
        }
        // Defense-in-depth (P2-3) : valide regex AVANT decode pour ne pas
        // laisser une FormatException remonter avec un fragment hex fautif.
        if (!_hexPattern.hasMatch(existing)) {
          throw const FormatException('KEK persistée : encodage corrompu.');
        }
        try {
          completer.complete(SecretBytes.fromHex(existing));
        } catch (_) {
          throw const FormatException('KEK persistée : encodage corrompu.');
        }
      } else {
        final fresh = _generateKek();
        await _storage.write(
          key: _kekStorageKey,
          value: SecretBytes.toHex(fresh),
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

  /// Validation hex stricte avant `SecretBytes.fromHex` : defense-in-depth
  /// pour qu'une éventuelle KEK corrompue (ex. caractère non-hex injecté)
  /// soit rejetée avant le decode plutôt qu'en levant une `FormatException`
  /// avec fragment fautif visible dans la stack.
  static final RegExp _hexPattern = RegExp(r'^[0-9a-fA-F]+$');
}
