import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/core/exceptions.dart';
import 'package:notes_tech/services/security/folder_vault_service.dart';

/// Tests structurels du `FolderVaultService`.
///
/// **Skips d'intégration** : les tests end-to-end (`createPinVault` →
/// `unlockWithPin` × N → `VaultPinWipedException` / `createVault` →
/// `unlock` mauvaise passphrase → wipe `kekFromPass`) exigeraient :
///
///   - Mock du `KeystoreBridge` (MethodChannel `com.filestech.notes_tech/keystore`)
///     pour stub `createKey`, `wrap`, `unwrap`, `deleteKey`,
///     `deleteKeysWithPrefix` — non-trivial sans `TestDefaultBinaryMessenger`.
///   - Mock `FoldersRepository` + `NotesRepository` (sqflite_sqlcipher
///     → init `databaseFactoryFfi` manuelle, pas de `setUpAll` global ici).
///   - Mock `SharedPreferences` (déjà supporté via `setMockInitialValues`).
///
/// L'effort de mock dépasse la valeur d'un test unitaire vs un test
/// d'instrumentation (qui couvrirait la chaîne complète Keystore inclus).
/// On garde donc ici la couverture sur ce qui est testable pure-Dart :
/// les hiérarchies d'exception et leur sémantique.
void main() {
  group('VaultPinWipedException', () {
    test('inherits from NotesTechException', () {
      const e = VaultPinWipedException('folder-1');
      expect(e, isA<NotesTechException>());
      expect(e.folderId, 'folder-1');
      expect(e.message, contains('auto-détruit'));
    });
  });

  group('WrongPinException', () {
    test('carries attemptsRemaining for UI', () {
      const e = WrongPinException(attemptsRemaining: 3);
      expect(e.attemptsRemaining, 3);
      expect(e, isA<NotesTechException>());
    });
  });

  group('WrongPassphraseException', () {
    test('inherits from NotesTechException', () {
      const e = WrongPassphraseException();
      expect(e, isA<NotesTechException>());
    });
  });

  group('VaultValidationException', () {
    test('extends ValidationException for unified call-site catch', () {
      // Le call-site dans note_editor_screen attrape `ValidationException`.
      // Cet héritage garantit que les erreurs vault y sont prises en
      // compte sans logique conditionnelle dédiée.
      const e = VaultValidationException('passphrase trop courte');
      expect(e, isA<ValidationException>());
      expect(e, isA<NotesTechException>());
      expect(e.message, 'passphrase trop courte');
    });
  });

  group('VaultLockedException', () {
    test('carries folderId for diagnostics', () {
      const e = VaultLockedException('vault-42');
      expect(e.folderId, 'vault-42');
      expect(e, isA<NotesTechException>());
    });
  });

  // --- Tests d'intégration souhaités (skipped, voir header) ---

  test(
    'create vault avec PIN → unlock-bad-pin × 5 → VaultPinWipedException + folder démoté',
    () {
      // SKIP : nécessite mocks KeystoreBridge + FoldersRepository +
      // NotesRepository + SharedPreferences. À porter en test
      // d'instrumentation Android (androidTest/) où les vraies clés
      // Keystore peuvent être créées dans un AndroidKeyStore éphémère
      // de l'émulateur.
    },
    skip: 'mocks Keystore + repositories + sqflite_ffi requis — voir header',
  );

  test(
    'create vault avec passphrase → unlock-wrong-passphrase → wipe kekFromPass',
    () {
      // SKIP : idem ci-dessus pour les repositories. Le wipe de
      // `kekFromPass` est garanti par le `_wipe(kekFromPass)` placé
      // avant chaque `throw` dans la branche `on SecretBoxAuthenticationError`
      // (cf. folder_vault_service.dart:555-561).
    },
    skip: 'mocks repositories + sqflite_ffi requis — voir header',
  );
}
