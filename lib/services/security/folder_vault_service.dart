/// **Vault par dossier** — chiffrement par dossier avec passphrase distincte.
///
/// Modèle de menace cible : un attaquant qui voit le téléphone déverrouillé
/// (police, fouille, contrainte physique) ne doit pas pouvoir lire les notes
/// d'un dossier coffre sans connaître la passphrase qui n'est jamais stockée.
/// La biométrie est intentionnellement **non supportée** pour rester
/// résistant à la contrainte (police peut forcer un doigt sur le scanner ;
/// elle ne peut pas extraire un mot mémorisé).
///
/// ## Architecture cryptographique
///
/// Pour chaque dossier coffre :
///
/// ```
///   passphrase ─Argon2id(t=3, m=64MB, p=1, salt=16B)→ KEK 256 bits
///   folder_kek 256 bits (CSPRNG) ─AES-GCM(KEK, iv=12B, AAD=folder_id)→ vault_kek_wrapped
///   verifier = HMAC-SHA-256(folder_kek, "files-tech.notes_tech.vault.v1")
///
///   notes du dossier :
///     content_utf8 ─AES-GCM(folder_kek, AAD=note_id)→ encrypted_content
///       (12 octets nonce || ciphertext || 16 octets tag)
/// ```
///
/// **Pourquoi un verifier HMAC** ? Pour valider une passphrase saisie sans
/// avoir à déchiffrer toutes les notes du dossier. Au unlock :
///   1. Re-dérive KEK à partir de `passphrase + vault_salt`.
///   2. Tente de déchiffrer `vault_kek_wrapped` avec KEK (AAD=folder_id).
///      Si tag GCM invalide → mauvaise passphrase, abort.
///   3. Calcule `HMAC(folder_kek, "files-tech.notes_tech.vault.v1")` et
///      compare avec `vault_verifier`. Si différent → corruption (très
///      rare). Sinon, on tient `folder_kek` en RAM.
///
/// **Pourquoi AAD = folder_id sur le wrap** ? Empêche un attaquant qui aurait
/// l'écriture en DB de copier `vault_kek_wrapped` d'un dossier sur un autre
/// pour réutiliser sa passphrase. Idem AAD = note_id sur le content : on ne
/// peut pas re-coller le contenu d'une note dans une autre.
///
/// ## Sessions et auto-lock
///
/// Une fois unlock, `folder_kek` reste en RAM (`Map<folderId, _Session>`)
/// le temps que l'utilisateur consulte. Auto-lock après inactivité
/// configurable (défaut 15 min, cf. `AppConstants.vaultDefaultAutoLock`).
/// Le lock zeroize les bytes en mémoire avant de les libérer.
///
/// ## Mode panique
///
/// Le `PanicService` v0.7 détruit la KEK Keystore + écrase la DB. Les
/// `vault_*` columns disparaissent avec la DB → coffres détruits aussi.
/// La KEK Argon2id-derived n'existe que en RAM pendant les sessions
/// actives — `wipeAllSessions()` ci-dessous l'efface.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:cryptography/cryptography.dart';
import 'package:flutter/foundation.dart';

import '../../core/constants.dart';
import '../../data/models/folder.dart';
import '../../data/models/note.dart';
import '../../data/repositories/folders_repository.dart';
import '../../data/repositories/notes_repository.dart';

/// Erreur levée quand une passphrase saisie est incorrecte (le wrap
/// AES-GCM échoue au tag GCM ou le verifier HMAC ne matche pas).
class WrongPassphraseException implements Exception {
  const WrongPassphraseException();
  @override
  String toString() => 'WrongPassphraseException: passphrase incorrecte';
}

/// Erreur levée quand on tente d'opérer sur un coffre qui n'a pas été
/// déverrouillé (ou a été auto-locké entre temps).
class VaultLockedException implements Exception {
  const VaultLockedException(this.folderId);
  final String folderId;
  @override
  String toString() => 'VaultLockedException: dossier $folderId verrouillé';
}

/// Erreur de validation (passphrase trop courte, dossier déjà coffre…).
class VaultValidationException implements Exception {
  const VaultValidationException(this.message);
  final String message;
  @override
  String toString() => 'VaultValidationException: $message';
}

/// Session active d'un coffre déverrouillé. La `folder_kek` est gardée en
/// RAM pour la durée de la session ; l'auto-lock zeroize les bytes.
class _Session {
  _Session({required this.folderKek, required this.openedAt});
  Uint8List folderKek;
  DateTime openedAt;
  DateTime lastActivity = DateTime.now();
}

/// Constante DOMAIN-SEPARATED pour le HMAC verifier. Si on change ce
/// string, les vaults existants deviennent incompatibles → ne JAMAIS
/// modifier après livraison.
const String _kVerifierMessage = 'files-tech.notes_tech.vault.v1';

class FolderVaultService extends ChangeNotifier {
  FolderVaultService({
    required FoldersRepository folders,
    required NotesRepository notes,
    Duration autoLockAfter = AppConstants.vaultDefaultAutoLock,
  })  : _folders = folders,
        _notes = notes,
        _autoLockAfter = autoLockAfter;

  final FoldersRepository _folders;
  final NotesRepository _notes;
  Duration _autoLockAfter;

  /// Sessions actives, indexées par `folder.id`. Une entrée présente
  /// signifie que le coffre est déverrouillé en mémoire.
  final Map<String, _Session> _unlocked = {};

  Timer? _autoLockTimer;

  /// Liste les `id` des coffres actuellement déverrouillés (pour l'UI
  /// qui peut afficher un badge « verrouiller maintenant »).
  Set<String> get unlockedFolderIds => _unlocked.keys.toSet();

  bool isUnlocked(String folderId) => _unlocked.containsKey(folderId);

  /// Met à jour le délai d'auto-lock (depuis Settings). Si > 0, replanifie.
  void setAutoLockAfter(Duration d) {
    _autoLockAfter = d;
    _scheduleAutoLockSweep();
  }

  /// Marque une activité (touche tap, scroll, save…) sur un coffre
  /// déverrouillé pour décaler son auto-lock.
  void touchActivity(String folderId) {
    final s = _unlocked[folderId];
    if (s == null) return;
    s.lastActivity = DateTime.now();
    _scheduleAutoLockSweep();
  }

  // ── Création ───────────────────────────────────────────────────────

  /// Convertit un dossier ordinaire en coffre. La passphrase doit être
  /// validée par l'UI (longueur min, confirmation 2x). Génère salt +
  /// folder_kek aléatoires, chiffre folder_kek avec KEK-Argon2id,
  /// calcule le verifier, persiste sur le dossier.
  ///
  /// **Ne ré-encrypte PAS les notes existantes du dossier** — c'est le
  /// rôle de [encryptAllNotesInFolder] qui prend une [Uint8List]
  /// folder_kek déjà déverrouillée. Le caller (UI) enchaîne les deux
  /// dans une transaction logique.
  Future<Folder> createVault({
    required Folder folder,
    required String passphrase,
  }) async {
    if (folder.isVault) {
      throw const VaultValidationException('Dossier déjà un coffre.');
    }
    _validatePassphrase(passphrase);

    final salt = _randomBytes(AppConstants.vaultSaltBytes);
    final folderKek = _randomBytes(32);
    final iv = _randomBytes(12);

    final kekFromPass =
        await _deriveKekArgon2id(passphrase: passphrase, salt: salt);
    try {
      final wrapped = await _aesGcmEncrypt(
        key: kekFromPass,
        iv: iv,
        plaintext: folderKek,
        aad: utf8Bytes(folder.id),
      );
      final verifier = await _verifierFor(folderKek);

      // Persiste. On utilise FoldersRepository pour bénéficier de
      // l'event `changes` (drawer rebuild auto).
      final updated = folder.copyWith(
        vaultSalt: salt,
        vaultKekWrapped: wrapped,
        vaultIv: iv,
        vaultVerifier: verifier,
        updatedAt: DateTime.now(),
      );
      await _folders.update(updated);

      // La folder_kek reste en RAM le temps de la session (l'UI peut
      // enchaîner avec encryptAllNotesInFolder).
      _unlocked[updated.id] = _Session(
        folderKek: folderKek,
        openedAt: DateTime.now(),
      );
      _scheduleAutoLockSweep();
      notifyListeners();
      return updated;
    } finally {
      _wipe(kekFromPass);
    }
  }

  // ── Déverrouillage / verrouillage ──────────────────────────────────

  /// Déverrouille le coffre avec la passphrase saisie par l'utilisateur.
  /// La `folder_kek` est gardée en RAM jusqu'au lock manuel ou auto-lock.
  ///
  /// Lève [WrongPassphraseException] si la passphrase est incorrecte
  /// (tag GCM invalide ou verifier HMAC ne matche pas — une seule
  /// possibilité avec proba ~ 2^-128, donc en pratique = mauvaise
  /// passphrase).
  Future<void> unlock({
    required Folder folder,
    required String passphrase,
  }) async {
    if (!folder.isVault) {
      throw const VaultValidationException(
        'Le dossier n\'est pas un coffre.',
      );
    }
    final salt = folder.vaultSalt!;
    final wrapped = folder.vaultKekWrapped!;
    final iv = folder.vaultIv!;
    final expectedVerifier = folder.vaultVerifier!;

    final kekFromPass =
        await _deriveKekArgon2id(passphrase: passphrase, salt: salt);
    Uint8List? folderKek;
    try {
      folderKek = await _aesGcmDecrypt(
        key: kekFromPass,
        iv: iv,
        wrapped: wrapped,
        aad: utf8Bytes(folder.id),
      );
    } on SecretBoxAuthenticationError {
      _wipe(kekFromPass);
      throw const WrongPassphraseException();
    } catch (_) {
      _wipe(kekFromPass);
      rethrow;
    }
    _wipe(kekFromPass);

    final actualVerifier = await _verifierFor(folderKek);
    if (!_constantTimeEq(actualVerifier, expectedVerifier)) {
      _wipe(folderKek);
      throw const WrongPassphraseException();
    }

    _unlocked[folder.id] = _Session(
      folderKek: folderKek,
      openedAt: DateTime.now(),
    );
    _scheduleAutoLockSweep();
    notifyListeners();
  }

  /// Verrouille manuellement un coffre. Idempotent.
  void lock(String folderId) {
    final s = _unlocked.remove(folderId);
    if (s != null) {
      _wipe(s.folderKek);
      notifyListeners();
    }
  }

  /// Verrouille tous les coffres déverrouillés. Appelé au pause de
  /// l'app (lifecycle) et par le mode panique.
  void lockAll() {
    if (_unlocked.isEmpty) return;
    for (final s in _unlocked.values) {
      _wipe(s.folderKek);
    }
    _unlocked.clear();
    notifyListeners();
  }

  // ── Chiffrement / déchiffrement de notes ───────────────────────────

  /// Chiffre le contenu d'une note avec la `folder_kek` du coffre
  /// (déverrouillé requis). Retourne la note avec `encryptedContent`
  /// rempli et `content` vidé.
  Future<Note> encryptNote(Note note) async {
    final session = _requireSession(note.folderId);
    final iv = _randomBytes(12);
    final ciphertext = await _aesGcmEncrypt(
      key: session.folderKek,
      iv: iv,
      plaintext: utf8Bytes(note.content),
      aad: utf8Bytes(note.id),
    );
    // Format wire : iv(12) || ciphertext+tag(16). Aligne avec la
    // convention `EncryptedJsonStore` côté AI Tech (cohérence Files Tech).
    final blob = Uint8List(iv.length + ciphertext.length)
      ..setAll(0, iv)
      ..setAll(iv.length, ciphertext);
    return note.copyWith(
      content: '',
      encryptedContent: blob,
      updatedAt: DateTime.now(),
    );
  }

  /// Déchiffre une note verrouillée. Retourne une note éphémère avec
  /// `content` rempli, **non persistée** — l'UI s'en sert pour afficher
  /// puis la jette. La note persistée reste dans son état chiffré.
  Future<Note> decryptNote(Note note) async {
    if (!note.isLocked) return note;
    final session = _requireSession(note.folderId);
    final blob = note.encryptedContent!;
    if (blob.length < 12 + 16) {
      throw const VaultValidationException(
        'Contenu chiffré invalide (trop court).',
      );
    }
    final iv = Uint8List.sublistView(blob, 0, 12);
    final ciphertext = Uint8List.sublistView(blob, 12);
    final plaintext = await _aesGcmDecrypt(
      key: session.folderKek,
      iv: iv,
      wrapped: ciphertext,
      aad: utf8Bytes(note.id),
    );
    return note.copyWith(
      content: utf8Decode(plaintext),
      clearEncrypted: true,
    );
  }

  /// Re-chiffre toutes les notes vivantes (hors corbeille) du dossier
  /// coffre déverrouillé. Utilisé lors de la conversion d'un dossier
  /// existant en coffre.
  ///
  /// Best-effort par note : si une note échoue, on continue (mais
  /// l'erreur est tracée dans le retour).
  ///
  /// Retourne le nombre de notes effectivement chiffrées.
  Future<int> encryptAllNotesInFolder(String folderId) async {
    _requireSession(folderId);
    final notes = await _notes.listByFolder(folderId, includeArchived: true);
    var ok = 0;
    for (final note in notes) {
      if (note.isLocked) continue;
      try {
        final encrypted = await encryptNote(note);
        await _notes.save(encrypted);
        ok++;
      } catch (_) {
        // Best-effort. Une exception cause l'arrêt du re-chiffrement
        // pour cette note précise, pas pour les suivantes.
      }
    }
    return ok;
  }

  // ── Auto-lock sweep ────────────────────────────────────────────────

  void _scheduleAutoLockSweep() {
    _autoLockTimer?.cancel();
    if (_unlocked.isEmpty || _autoLockAfter <= Duration.zero) return;
    _autoLockTimer = Timer(_autoLockAfter, _autoLockSweep);
  }

  void _autoLockSweep() {
    if (_unlocked.isEmpty) return;
    final cutoff = DateTime.now().subtract(_autoLockAfter);
    final expired = <String>[];
    for (final entry in _unlocked.entries) {
      if (entry.value.lastActivity.isBefore(cutoff)) {
        expired.add(entry.key);
      }
    }
    for (final id in expired) {
      lock(id);
    }
    if (_unlocked.isNotEmpty) {
      _scheduleAutoLockSweep();
    }
  }

  // ── Internes crypto ────────────────────────────────────────────────

  void _validatePassphrase(String passphrase) {
    if (passphrase.length < AppConstants.vaultPassphraseMinLength) {
      throw const VaultValidationException(
        'Passphrase trop courte (minimum 8 caractères).',
      );
    }
  }

  _Session _requireSession(String folderId) {
    final s = _unlocked[folderId];
    if (s == null) throw VaultLockedException(folderId);
    s.lastActivity = DateTime.now();
    return s;
  }

  Future<Uint8List> _deriveKekArgon2id({
    required String passphrase,
    required Uint8List salt,
  }) async {
    final algo = Argon2id(
      memory: AppConstants.vaultArgon2MemoryKb,
      iterations: AppConstants.vaultArgon2Iterations,
      parallelism: AppConstants.vaultArgon2Parallelism,
      hashLength: AppConstants.vaultArgon2HashBytes,
    );
    final secret = SecretKey(utf8Bytes(passphrase));
    final derived = await algo.deriveKey(secretKey: secret, nonce: salt);
    final bytes = await derived.extractBytes();
    return Uint8List.fromList(bytes);
  }

  Future<Uint8List> _aesGcmEncrypt({
    required Uint8List key,
    required Uint8List iv,
    required Uint8List plaintext,
    required Uint8List aad,
  }) async {
    final algo = AesGcm.with256bits();
    final secret = SecretKey(key);
    final box = await algo.encrypt(
      plaintext,
      secretKey: secret,
      nonce: iv,
      aad: aad,
    );
    final out = Uint8List(box.cipherText.length + box.mac.bytes.length)
      ..setAll(0, box.cipherText)
      ..setAll(box.cipherText.length, box.mac.bytes);
    return out;
  }

  Future<Uint8List> _aesGcmDecrypt({
    required Uint8List key,
    required Uint8List iv,
    required Uint8List wrapped,
    required Uint8List aad,
  }) async {
    if (wrapped.length < 16) {
      throw const VaultValidationException(
        'Wrap chiffré invalide (tag GCM tronqué).',
      );
    }
    final cipherText = Uint8List.sublistView(wrapped, 0, wrapped.length - 16);
    final macBytes = Uint8List.sublistView(wrapped, wrapped.length - 16);
    final algo = AesGcm.with256bits();
    final secret = SecretKey(key);
    final box = SecretBox(
      cipherText,
      nonce: iv,
      mac: Mac(macBytes),
    );
    final plain = await algo.decrypt(box, secretKey: secret, aad: aad);
    return Uint8List.fromList(plain);
  }

  Future<Uint8List> _verifierFor(Uint8List folderKek) async {
    final hmac = Hmac.sha256();
    final secret = SecretKey(folderKek);
    final mac =
        await hmac.calculateMac(utf8Bytes(_kVerifierMessage), secretKey: secret);
    return Uint8List.fromList(mac.bytes);
  }

  Uint8List _randomBytes(int length) {
    // `cryptography` fournit un secure random via `SecretKeyData.random()`,
    // mais c'est lourd pour générer juste N bytes. On utilise le pattern
    // déjà éprouvé dans `VaultService` (Random.secure).
    final rng = Random.secure();
    final out = Uint8List(length);
    for (var i = 0; i < length; i++) {
      out[i] = rng.nextInt(256);
    }
    return out;
  }

  void _wipe(Uint8List bytes) {
    bytes.fillRange(0, bytes.length, 0);
  }

  bool _constantTimeEq(Uint8List a, Uint8List b) {
    if (a.length != b.length) return false;
    var diff = 0;
    for (var i = 0; i < a.length; i++) {
      diff |= a[i] ^ b[i];
    }
    return diff == 0;
  }

  @override
  void dispose() {
    _autoLockTimer?.cancel();
    lockAll();
    super.dispose();
  }
}

// ─── Helpers UTF-8 ────────────────────────────────────────────────────

/// Encode une chaîne en UTF-8 → Uint8List. Helper top-level pour rester
/// cohérent avec le reste du codebase (cf. `EncryptedJsonStore`).
Uint8List utf8Bytes(String s) {
  return Uint8List.fromList(_utf8.encode(s));
}

String utf8Decode(Uint8List bytes) {
  return _utf8.decode(bytes);
}

const _utf8 = Utf8Codec();
