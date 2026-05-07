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

import 'package:cryptography/cryptography.dart';
import 'package:files_tech_core/files_tech_core.dart';
import 'package:flutter/foundation.dart';

import 'package:shared_preferences/shared_preferences.dart';

import '../../core/constants.dart';
import '../../core/exceptions.dart';
import '../../data/models/folder.dart';
import '../../data/models/note.dart';
import '../../data/repositories/folders_repository.dart';
import '../../data/repositories/notes_repository.dart';
import 'keystore_bridge.dart';

/// Erreur levée quand une passphrase saisie est incorrecte (le wrap
/// AES-GCM échoue au tag GCM ou le verifier HMAC ne matche pas).
class WrongPassphraseException extends NotesTechException {
  const WrongPassphraseException() : super('Passphrase incorrecte.');
}

/// Erreur levée quand on tente d'opérer sur un coffre qui n'a pas été
/// déverrouillé (ou a été auto-locké entre temps).
class VaultLockedException extends NotesTechException {
  const VaultLockedException(this.folderId)
      : super('Coffre verrouillé.');
  final String folderId;
}

/// Erreur de validation (passphrase trop courte, dossier déjà coffre…).
/// Hérite de [ValidationException] pour que les call-sites attrapant
/// `ValidationException` (note_editor_screen) couvrent aussi les erreurs
/// vault — single source of truth.
class VaultValidationException extends ValidationException {
  const VaultValidationException(super.message);
}

/// Erreur levée quand un coffre PIN a été auto-détruit après trop de
/// tentatives ratées (`vaultPinMaxAttempts`). À ce stade la clé Keystore
/// a été supprimée, les notes verrouillées ont été effacées, et le
/// dossier a été démoté en dossier ordinaire — les données sont perdues
/// définitivement.
class VaultPinWipedException extends NotesTechException {
  const VaultPinWipedException(this.folderId)
      : super('Coffre auto-détruit après trop de tentatives ratées.');
  final String folderId;
}

/// Erreur levée quand un PIN saisi est incorrect. Inclut le nombre de
/// tentatives restantes pour permettre à l'UI d'avertir l'utilisateur.
class WrongPinException extends NotesTechException {
  const WrongPinException({required this.attemptsRemaining})
      : super('PIN incorrect.');
  final int attemptsRemaining;
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
    KeystoreBridge? keystore,
  })  : _folders = folders,
        _notes = notes,
        _autoLockAfter = autoLockAfter,
        _keystore = keystore ?? KeystoreBridge();

  final FoldersRepository _folders;
  final NotesRepository _notes;
  final KeystoreBridge _keystore;
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

  /// Convertit un dossier ordinaire en coffre **mode PIN** (v0.9).
  ///
  /// Triple-couche cryptographique pour compenser la faible entropie
  /// d'un PIN 4-6 chiffres :
  ///
  ///   1. **Argon2id allégé** (t=2, m=32MB) sur PIN+salt → `pin_kek` 256b.
  ///   2. **AES-GCM** : `folder_kek` (32 octets CSPRNG) wrappée par
  ///      `pin_kek`, AAD = folder_id → blob intermédiaire.
  ///   3. **Keystore** : blob intermédiaire scellé par une clé AES-256-GCM
  ///      résidente dans AndroidKeyStore (alias = `vault_pin_<id>`,
  ///      StrongBox si dispo, fallback TEE). Cette clé n'est **jamais
  ///      extractible** → bruteforce hors-device impossible.
  ///
  /// Le rate-limit applicatif (auto-wipe à 5 fails) couvre le bruteforce
  /// on-device. Combinaison : un attaquant doit avoir le device, ne peut
  /// tester que via l'API (pas d'extraction), et n'a que 5 chances.
  ///
  /// Le `vault_kek_wrapped` est laissé NULL en mode PIN (le wrap réside
  /// dans `vault_pin_blob`). Le verifier HMAC reste calculé pareil.
  Future<Folder> createPinVault({
    required Folder folder,
    required String pin,
  }) async {
    if (folder.isVault) {
      throw const VaultValidationException('Dossier déjà un coffre.');
    }
    _validatePin(pin);

    final salt = _randomBytes(AppConstants.vaultSaltBytes);
    final folderKek = _randomBytes(32);
    final iv = _randomBytes(12);

    final pinKek = await _deriveKekArgon2idLight(pin: pin, salt: salt);
    try {
      // Couche 2 : wrap interne AES-GCM avec la KEK dérivée du PIN.
      final innerWrapped = await _aesGcmEncrypt(
        key: pinKek,
        iv: iv,
        plaintext: folderKek,
        aad: utf8Bytes(folder.id),
      );

      // Couche 3 : scellage Keystore. Crée la clé (idempotent — si
      // l'alias existait pour une raison improbable, on supprime + recrée
      // pour partir propre).
      final alias = _keystoreAlias(folder.id);
      await _keystore.deleteKey(alias);
      await _keystore.createKey(alias);
      final sealed = await _keystore.wrap(alias, innerWrapped);

      final verifier = await _verifierFor(folderKek);

      final updated = folder.copyWith(
        vaultSalt: salt,
        vaultIv: iv,
        vaultVerifier: verifier,
        vaultMode: VaultMode.pin,
        vaultPinBlob: sealed.ciphertext,
        vaultPinIv: sealed.nonce,
        vaultAttempts: 0,
        updatedAt: DateTime.now(),
      );
      await _folders.update(updated);

      _unlocked[updated.id] = _Session(
        folderKek: folderKek,
        openedAt: DateTime.now(),
      );
      _scheduleAutoLockSweep();
      notifyListeners();
      return updated;
    } finally {
      _wipe(pinKek);
    }
  }

  /// Déverrouille un coffre **mode PIN** avec rate-limit + auto-wipe.
  ///
  /// Comportement en cas d'erreur :
  /// - PIN incorrect → incrémente `vault_attempts` en DB, lève
  ///   [WrongPinException] avec le nombre de tentatives restantes.
  /// - À la N-ième tentative ratée (N = `vaultPinMaxAttempts`),
  ///   déclenche [_autoWipePinVault] et lève [VaultPinWipedException].
  /// - Sur succès, remet `vault_attempts` à 0.
  ///
  /// L'incrémentation est persistée AVANT la tentative pour résister à
  /// un attaquant qui kill l'app entre l'échec et l'incrément (sinon il
  /// pourrait bruteforcer infiniment).
  Future<void> unlockWithPin({
    required Folder folder,
    required String pin,
  }) async {
    if (!folder.isVault || !folder.isPinVault) {
      throw const VaultValidationException(
        'Le dossier n\'est pas un coffre PIN.',
      );
    }
    if (folder.vaultAttempts >= AppConstants.vaultPinMaxAttempts) {
      // Garde-fou : un coffre déjà au max ne devrait pas exister
      // (auto-wipe précédent l'aurait démoli) mais on couvre le cas
      // d'une DB restaurée d'un backup où l'auto-wipe a été interrompu.
      await _autoWipePinVault(folder);
      throw VaultPinWipedException(folder.id);
    }
    _validatePin(pin);

    // Incrément persisté AVANT tentative — anti-kill-loop.
    final attemptsBefore = folder.vaultAttempts + 1;
    var working = folder.copyWith(vaultAttempts: attemptsBefore);
    await _folders.update(working);

    final salt = folder.vaultSalt!;
    final iv = folder.vaultIv!;
    final pinBlob = folder.vaultPinBlob!;
    final pinIv = folder.vaultPinIv!;
    final expectedVerifier = folder.vaultVerifier!;

    // Couche 3 → 2 : déscelle Keystore puis dérive pin_kek pour unwrap.
    Uint8List innerWrapped;
    try {
      innerWrapped = await _keystore.unwrap(
        _keystoreAlias(folder.id),
        pinBlob,
        pinIv,
      );
    } on KeystoreException {
      // Clé Keystore disparue (factory reset partiel, désinstallation
      // incomplète, app data effacée mais pas la DB). Coffre irréparable.
      await _autoWipePinVault(folder);
      throw VaultPinWipedException(folder.id);
    }

    final pinKek = await _deriveKekArgon2idLight(pin: pin, salt: salt);
    // `folderKek` est nullable car peut ne pas être assignée si
    // `_aesGcmDecrypt` lève. Le `finally` wipe systématiquement si
    // assignée mais non encore transférée à la session — évite la
    // fenêtre où une exception inattendue dans `_verifierFor` ou
    // `_folders.update` laisserait la clé en RAM.
    Uint8List? folderKek;
    var transferredToSession = false;
    try {
      try {
        folderKek = await _aesGcmDecrypt(
          key: pinKek,
          iv: iv,
          wrapped: innerWrapped,
          aad: utf8Bytes(folder.id),
        );
      } on SecretBoxAuthenticationError {
        await _onPinFailure(working);
      }

      // Hardening invariant : `_onPinFailure` est `Future<Never>` →
      // si on atteint cette ligne, `folderKek` est forcément assignée.
      // L'assertion explicite blinde le flux face à un futur refactor
      // qui changerait la signature de `_onPinFailure`.
      final kek = folderKek;

      final actualVerifier = await _verifierFor(kek);
      if (!_constantTimeEq(actualVerifier, expectedVerifier)) {
        await _onPinFailure(working);
      }

      // Succès : reset compteur + ouvre session.
      working = working.copyWith(vaultAttempts: 0);
      await _folders.update(working);

      _unlocked[folder.id] = _Session(
        folderKek: kek,
        openedAt: DateTime.now(),
      );
      transferredToSession = true; // ownership transférée — ne pas wipe ici
      _scheduleAutoLockSweep();
      notifyListeners();
    } finally {
      _wipe(pinKek);
      // innerWrapped contenait folder_kek chiffrée par pin_kek (pas une
      // clé en clair, mais hygiène défense en profondeur).
      _wipe(innerWrapped);
      // Wipe folderKek si elle a été dérivée mais pas transférée à
      // une session active (cas exception entre _aesGcmDecrypt et
      // l'assignation à `_unlocked`).
      if (!transferredToSession && folderKek != null) {
        _wipe(folderKek);
      }
    }
  }

  /// Déclenché à chaque échec de PIN. Si on a atteint le max → auto-wipe
  /// + [VaultPinWipedException] ; sinon → [WrongPinException] avec le
  /// nombre de tentatives restantes.
  Future<Never> _onPinFailure(Folder afterIncrement) async {
    final remaining =
        AppConstants.vaultPinMaxAttempts - afterIncrement.vaultAttempts;
    if (remaining <= 0) {
      await _autoWipePinVault(afterIncrement);
      throw VaultPinWipedException(afterIncrement.id);
    }
    throw WrongPinException(attemptsRemaining: remaining);
  }

  /// Auto-destruction d'un coffre PIN après trop de tentatives :
  ///   0. Pose un flag prefs `vault_wipe_pending_<id>` (anti-reprise).
  ///   1. Supprime la clé Keystore (alias = `vault_pin_<id>`).
  ///   2. Supprime de la DB **toutes les notes locked** du dossier
  ///      (elles sont chiffrées avec une `folder_kek` à jamais
  ///      irrécupérable — autant ne pas garder de garbage).
  ///   3. Démote le dossier en dossier ordinaire (clearVault → null
  ///      sur tous les champs `vault_*`, `vault_attempts` revient à 0).
  ///   4. Retire le flag prefs.
  ///
  /// **Atomicité** : si l'app est tuée entre les steps 1-3, le flag
  /// reste posé. Au démarrage suivant, [resumePendingWipes] reprend
  /// les wipes interrompus → on évite les notes orphelines chiffrées
  /// éternellement avec une clé Keystore déjà supprimée.
  ///
  /// Le dossier reste, vide. L'utilisateur voit clairement que les
  /// données ont disparu — c'est le contrat (5 fails = perte définitive,
  /// équivalent factory reset Android).
  Future<void> _autoWipePinVault(Folder folder) async {
    final prefs = await SharedPreferences.getInstance();
    final flagKey =
        '${AppConstants.prefKeyVaultWipePendingPrefix}${folder.id}';
    await prefs.setBool(flagKey, true);

    // 1. Keystore : delete idempotent.
    try {
      await _keystore.deleteKey(_keystoreAlias(folder.id));
    } catch (_) {
      // Best-effort : si Keystore refuse, la clé devient orpheline mais
      // sans le blob côté DB elle est inutile à un attaquant.
    }

    // 2. Notes verrouillées : suppression directe (purge sans corbeille
    // — la corbeille les conserverait sans pouvoir les déchiffrer).
    final notes = await _notes.listByFolder(folder.id, includeArchived: true);
    for (final n in notes) {
      if (n.isLocked) {
        try {
          await _notes.deletePermanently(n.id);
        } catch (_) {/* best-effort */}
      }
    }

    // 3. Démote le dossier.
    final cleared = folder.copyWith(
      clearVault: true,
      updatedAt: DateTime.now(),
    );
    await _folders.update(cleared);

    // Ferme la session si ouverte (ne devrait pas l'être à ce stade).
    lock(folder.id);

    // 4. Retire le flag — wipe terminé proprement.
    await prefs.remove(flagKey);
  }

  /// Reprise au démarrage : pour chaque coffre dont le flag de wipe
  /// pending est encore posé en prefs, relance `_autoWipePinVault`
  /// (idempotent). À appeler une fois par session, depuis [main.dart]
  /// après l'initialisation des repositories.
  Future<void> resumePendingWipes() async {
    final prefs = await SharedPreferences.getInstance();
    final keys = prefs.getKeys();
    const prefix = AppConstants.prefKeyVaultWipePendingPrefix;
    for (final key in keys) {
      if (!key.startsWith(prefix)) continue;
      final folderId = key.substring(prefix.length);
      try {
        final folder = await _folders.get(folderId);
        if (folder == null) {
          // Folder déjà supprimé — rien à wiper, juste retirer le flag.
          await prefs.remove(key);
          continue;
        }
        await _autoWipePinVault(folder);
      } catch (_) {
        // Best-effort : on retire quand même le flag pour ne pas
        // boucler indéfiniment au prochain démarrage si la reprise
        // échoue de manière permanente (ex. DB corrompue).
        await prefs.remove(key);
      }
    }
  }

  /// Suppression propre d'un coffre PIN (par l'utilisateur, pas par
  /// auto-wipe) : appelée quand le dossier est supprimé via le drawer.
  /// L'orchestration des notes est faite par le caller (decrypt avant
  /// move ou delete) — ici on nettoie juste la clé Keystore.
  Future<void> deletePinKey(String folderId) async {
    try {
      await _keystore.deleteKey(_keystoreAlias(folderId));
    } catch (_) {/* best-effort */}
  }

  String _keystoreAlias(String folderId) =>
      '${AppConstants.vaultPinKeystoreAliasPrefix}$folderId';

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

    // Pattern identique au mode PIN : try/finally global pour garantir
    // que `folderKek` est wipée si une exception survient dans
    // `_verifierFor` (OOM dans l'isolate, dispose anticipée du
    // SecretKey…) avant le transfert à la session.
    var transferredToSession = false;
    try {
      final actualVerifier = await _verifierFor(folderKek);
      if (!_constantTimeEq(actualVerifier, expectedVerifier)) {
        throw const WrongPassphraseException();
      }
      _unlocked[folder.id] = _Session(
        folderKek: folderKek,
        openedAt: DateTime.now(),
      );
      transferredToSession = true; // ownership transférée
      _scheduleAutoLockSweep();
      notifyListeners();
    } finally {
      if (!transferredToSession) _wipe(folderKek);
    }
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

  /// Déchiffre toutes les notes verrouillées du dossier coffre
  /// déverrouillé et les persiste **en clair** (`encryptedContent`
  /// effacé). Utilisé avant la suppression d'un coffre via
  /// "Déplacer vers Boîte de réception", pour éviter la perte
  /// silencieuse des données (sinon les notes locked atterriraient
  /// dans inbox sans la KEK qui les déchiffrait).
  ///
  /// Le coffre doit être déverrouillé. Lève [VaultLockedException]
  /// sinon. Best-effort par note avec bilan retourné.
  Future<({int decrypted, int failed})> decryptAllNotesInFolder(
    String folderId,
  ) async {
    _requireSession(folderId);
    final notes = await _notes.listByFolder(folderId, includeArchived: true);
    var ok = 0;
    var failed = 0;
    for (final note in notes) {
      if (!note.isLocked) continue;
      try {
        final decrypted = await decryptNote(note);
        await _notes.save(decrypted);
        ok++;
      } catch (_) {
        failed++;
      }
    }
    return (decrypted: ok, failed: failed);
  }

  /// Re-chiffre toutes les notes vivantes (hors corbeille) du dossier
  /// coffre déverrouillé. Utilisé lors de la conversion d'un dossier
  /// existant en coffre.
  ///
  /// Retourne `(encrypted, failed)`. **`failed > 0` = état incohérent
  /// du dossier** (certaines notes encore en clair) — le caller DOIT
  /// le signaler à l'utilisateur, sinon il pense tout son dossier
  /// protégé alors qu'une partie reste en clair.
  ///
  /// Pourquoi pas un rollback transactionnel global ? Parce que ça
  /// nécessiterait d'inclure les events d'embedding et de backlinks
  /// dans la même transaction SQL, ce qui n'est pas le design des
  /// repositories. À la place, on remonte le bilan honnête et le
  /// caller décide.
  Future<({int encrypted, int failed})> encryptAllNotesInFolder(
    String folderId,
  ) async {
    _requireSession(folderId);
    final notes = await _notes.listByFolder(folderId, includeArchived: true);
    var ok = 0;
    var failed = 0;
    for (final note in notes) {
      if (note.isLocked) continue;
      try {
        final encrypted = await encryptNote(note);
        await _notes.save(encrypted);
        ok++;
      } catch (_) {
        failed++;
      }
    }
    return (encrypted: ok, failed: failed);
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

  /// Validation d'un PIN coffre v0.9 : longueur dans la fenêtre 4-6, et
  /// **uniquement des chiffres** (un PIN avec lettres serait acceptable
  /// crypto mais l'UX numérique est volontaire — sinon autant utiliser
  /// le mode passphrase).
  void _validatePin(String pin) {
    if (pin.length < AppConstants.vaultPinMinLength ||
        pin.length > AppConstants.vaultPinMaxLength) {
      throw const VaultValidationException(
        'PIN invalide : ${AppConstants.vaultPinMinLength} à '
        '${AppConstants.vaultPinMaxLength} chiffres.',
      );
    }
    if (!_pinDigitsOnly.hasMatch(pin)) {
      throw const VaultValidationException(
        'PIN invalide : chiffres uniquement.',
      );
    }
  }

  static final RegExp _pinDigitsOnly = RegExp(r'^\d+$');

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
    // Le package `cryptography` 2.7 exécute Argon2id en pur Dart sur le
    // thread courant (pas d'isolate interne). Avec t=3 / m=64 MB, c'est
    // ~600-900 ms sur S24 FE, mais 3-5 s sur S9 et 5-9 s sur POCO C75.
    // Sans `compute()`, le main thread gèle pendant la dérivation et
    // le `CircularProgressIndicator` du sheet `unlock` reste figé.
    // → On déporte dans un isolate via `compute()`. UI reste fluide,
    // l'utilisateur voit le spinner tourner.
    return compute<_Argon2Job, Uint8List>(
      _argon2WorkerEntry,
      _Argon2Job(
        passphrase: passphrase,
        salt: salt,
        memoryKb: AppConstants.vaultArgon2MemoryKb,
        iterations: AppConstants.vaultArgon2Iterations,
        parallelism: AppConstants.vaultArgon2Parallelism,
        hashLength: AppConstants.vaultArgon2HashBytes,
      ),
    );
  }

  /// Variante allégée pour le mode PIN (v0.9) : t=2, m=32MB. La sécurité
  /// réelle vient du scellage Keystore (device-bound) ; Argon2id ici n'est
  /// qu'un coût supplémentaire pour ralentir un attaquant on-device qui
  /// passerait outre le rate-limit applicatif. Inutile d'imposer 1-2 s
  /// par tap "Déverrouiller" légitime.
  Future<Uint8List> _deriveKekArgon2idLight({
    required String pin,
    required Uint8List salt,
  }) async {
    return compute<_Argon2Job, Uint8List>(
      _argon2WorkerEntry,
      _Argon2Job(
        passphrase: pin,
        salt: salt,
        memoryKb: AppConstants.vaultPinArgon2MemoryKb,
        iterations: AppConstants.vaultPinArgon2Iterations,
        parallelism: AppConstants.vaultArgon2Parallelism,
        hashLength: AppConstants.vaultArgon2HashBytes,
      ),
    );
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

  // v0.9.7 — `_randomBytes`, `_wipe`, `_constantTimeEq` ont migré vers
  // `SecretBytes` dans `files_tech_core`. Shims locaux pour limiter le diff
  // sur les callsites historiques.
  Uint8List _randomBytes(int length) => SecretBytes.randomBytes(length);

  void _wipe(Uint8List bytes) => SecretBytes.wipe(bytes);

  bool _constantTimeEq(Uint8List a, Uint8List b) =>
      SecretBytes.constantTimeEq(a, b);

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

// ─── Worker isolate pour Argon2id ─────────────────────────────────────

/// Argument transmis à l'isolate. Tous les champs sont passifs et
/// sérialisables (String, Uint8List, int).
class _Argon2Job {
  const _Argon2Job({
    required this.passphrase,
    required this.salt,
    required this.memoryKb,
    required this.iterations,
    required this.parallelism,
    required this.hashLength,
  });
  final String passphrase;
  final Uint8List salt;
  final int memoryKb;
  final int iterations;
  final int parallelism;
  final int hashLength;
}

/// Entrée top-level de l'isolate (requis par `compute`). Re-instancie
/// `Argon2id` dans l'isolate, dérive la KEK, retourne les bytes.
Future<Uint8List> _argon2WorkerEntry(_Argon2Job job) async {
  final algo = Argon2id(
    memory: job.memoryKb,
    iterations: job.iterations,
    parallelism: job.parallelism,
    hashLength: job.hashLength,
  );
  final secret = SecretKey(utf8Bytes(job.passphrase));
  final derived = await algo.deriveKey(secretKey: secret, nonce: job.salt);
  final bytes = await derived.extractBytes();
  return Uint8List.fromList(bytes);
}
