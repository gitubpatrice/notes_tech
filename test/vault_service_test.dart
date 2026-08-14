import 'dart:typed_data';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/services/security/vault_service.dart';

/// Mock minimal en mémoire — n'implémente que `read` / `write` / `delete`,
/// suffisant pour `VaultService`.
class _InMemoryStorage implements FlutterSecureStorage {
  final Map<String, String> _store = {};
  int reads = 0;
  int writes = 0;

  @override
  Future<String?> read({
    required String key,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    reads++;
    return _store[key];
  }

  @override
  Future<void> write({
    required String key,
    required String? value,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    writes++;
    if (value == null) {
      _store.remove(key);
    } else {
      _store[key] = value;
    }
  }

  @override
  Future<void> delete({
    required String key,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    _store.remove(key);
  }

  @override
  noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

/// Un stockage dont la suppression **ne fait rien**, sans lever.
///
/// C'est le comportement qu'il fallait pouvoir reproduire : `delete` ne rend
/// aucun statut, donc un échec côté plateforme — Keystore momentanément muet,
/// fichier verrouillé — est indiscernable d'un succès pour l'appelant. Seule
/// une relecture le distingue.
class _StorageQuiRefuseDeSupprimer extends _InMemoryStorage {
  @override
  Future<void> delete({
    required String key,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    // Rien. Et surtout pas d'exception : c'est tout l'intérêt du cas.
  }
}

void main() {
  group('VaultService', () {
    test('génère une KEK 32 octets au premier appel', () async {
      final storage = _InMemoryStorage();
      final vault = VaultService(storage: storage);
      final kek = await vault.getOrCreateKek();
      expect(kek, isA<Uint8List>());
      expect(kek.length, 32);
      expect(storage.writes, 1);
    });

    test('idempotent : 2e appel renvoie la même KEK sans réécrire', () async {
      final storage = _InMemoryStorage();
      final vault = VaultService(storage: storage);
      final a = await vault.getOrCreateKek();
      final b = await vault.getOrCreateKek();
      expect(b, equals(a));
      expect(storage.writes, 1);
    });

    test('KEK persistée n\'est pas prévisible (entropy raisonnable)', () async {
      final storage1 = _InMemoryStorage();
      final storage2 = _InMemoryStorage();
      final a = await VaultService(storage: storage1).getOrCreateKek();
      final b = await VaultService(storage: storage2).getOrCreateKek();
      expect(a, isNot(equals(b)));
    });

    test('hasKek false avant génération, true après', () async {
      final storage = _InMemoryStorage();
      final vault = VaultService(storage: storage);
      expect(await vault.hasKek(), isFalse);
      await vault.getOrCreateKek();
      expect(await vault.hasKek(), isTrue);
    });

    test('destroyKek efface définitivement', () async {
      final storage = _InMemoryStorage();
      final vault = VaultService(storage: storage);
      await vault.getOrCreateKek();
      await vault.destroyKek();
      expect(await vault.hasKek(), isFalse);
    });

    /// 🔴 La garantie minimale du mode panique se joue ici.
    ///
    /// `destroyKek` se déclarait réussie sans rien relire — la SEULE étape de
    /// la panique dans ce cas, alors que c'est celle dont tout dépend. Une
    /// suppression silencieusement inopérante faisait porter « kekDestroy OK »
    /// au rapport, et l'écran de fin annonçait des notes irrécupérables à
    /// quelqu'un dont les notes restaient parfaitement lisibles.
    ///
    /// Ce test échoue si la vérification disparaît.
    test('destroyKek LÈVE si la clé survit à la suppression', () async {
      final storage = _StorageQuiRefuseDeSupprimer();
      final vault = VaultService(storage: storage);
      await vault.getOrCreateKek();

      await expectLater(vault.destroyKek(), throwsA(isA<StateError>()));
    });

    test('wipe met tous les octets à zéro', () {
      final bytes = Uint8List.fromList(List.generate(32, (i) => i + 1));
      VaultService.wipe(bytes);
      expect(bytes.every((b) => b == 0), isTrue);
    });

    test('KEK persistée robuste à un encodage corrompu', () async {
      final storage = _InMemoryStorage();
      // Simule un payload corrompu (longueur correcte, caractères non-hex).
      await storage.write(key: 'notes_tech.vault.kek.v1', value: 'g' * 64);
      final vault = VaultService(storage: storage);
      // L'erreur doit être générique, sans fragment du payload corrompu.
      try {
        await vault.getOrCreateKek();
        fail('Une exception était attendue');
      } on FormatException catch (e) {
        expect(e.message, isNot(contains('gg')));
        expect(e.message, contains('KEK'));
      }
    });

    test(
      'appels parallèles partagent la même KEK (pas de double génération)',
      () async {
        final storage = _InMemoryStorage();
        final vault = VaultService(storage: storage);
        final results = await Future.wait([
          vault.getOrCreateKek(),
          vault.getOrCreateKek(),
          vault.getOrCreateKek(),
        ]);
        expect(results[0], equals(results[1]));
        expect(results[1], equals(results[2]));
        expect(storage.writes, 1);
      },
    );
  });
}
