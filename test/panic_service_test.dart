import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/services/security/panic_service.dart';

/// Tests structurels du `PanicService`.
///
/// Les tests d'intégration end-to-end (`trigger()` réel) exigeraient des
/// mocks lourds : `KeystoreBridge` (MethodChannel natif Android),
/// `AppDatabase` (sqflite_sqlcipher → sqflite_ffi avec init manuelle),
/// `GemmaService` / `VoiceService` (FS + plugins). Hors scope d'un test
/// unitaire pure-Dart : on couvre l'invariant CRITIQUE — l'ordre des
/// steps de la séquence panique — par assertion sur l'enum lui-même.
///
/// **Invariant audité** : `pinKeysWipe` DOIT précéder `kekDestroy`. Si
/// un dev réordonne par mégarde, ce test casse → la garantie de sécurité
/// (wipe Keystore vault_pin_* avant point de non-retour) est protégée
/// par un test au lieu de reposer uniquement sur la doc inline.
void main() {
  group('PanicStep ordering', () {
    test('pinKeysWipe precedes kekDestroy', () {
      final values = PanicStep.values;
      final pinIdx = values.indexOf(PanicStep.pinKeysWipe);
      final kekIdx = values.indexOf(PanicStep.kekDestroy);
      expect(pinIdx, greaterThanOrEqualTo(0));
      expect(kekIdx, greaterThanOrEqualTo(0));
      expect(
        pinIdx < kekIdx,
        isTrue,
        reason:
            'pinKeysWipe doit précéder kekDestroy : sinon un attaquant '
            'avec backup DB pré-wipe pourrait bruteforcer les coffres '
            'PIN via les clés Keystore résiduelles.',
      );
    });

    test('expected sequence is preserved', () {
      // Régression-guard : si un dev ajoute / réordonne un step, ce test
      // force une revue explicite. La liste reflète l'ordre documenté
      // dans le header de panic_service.dart.
      expect(PanicStep.values, [
        PanicStep.forceSecureWindow,
        PanicStep.voiceCancel,
        PanicStep.foldersLockAll,
        PanicStep.pinKeysWipe,
        PanicStep.kekDestroy,
        PanicStep.pauseBackgroundWork,
        PanicStep.dbWipe,
        PanicStep.voiceWipe,
        PanicStep.gemmaUninstall,
        PanicStep.prefsClear,
        PanicStep.tmpPurge,
      ]);
    });

    test('foldersLockAll precedes pinKeysWipe', () {
      final values = PanicStep.values;
      final lockIdx = values.indexOf(PanicStep.foldersLockAll);
      final pinIdx = values.indexOf(PanicStep.pinKeysWipe);
      expect(lockIdx, greaterThanOrEqualTo(0));
      expect(pinIdx, greaterThanOrEqualTo(0));
      expect(
        lockIdx < pinIdx,
        isTrue,
        reason:
            'foldersLockAll doit précéder pinKeysWipe : zeroize les '
            'folder_kek en RAM AVANT que les clés Keystore qui les '
            'rewrappent ne soient effacées, sinon fenêtre RAM '
            'exploitable pendant la séquence panique.',
      );
    });

    test('foldersLockAll < pinKeysWipe < kekDestroy < dbWipe', () {
      final values = PanicStep.values;
      final lockIdx = values.indexOf(PanicStep.foldersLockAll);
      final pinIdx = values.indexOf(PanicStep.pinKeysWipe);
      final kekIdx = values.indexOf(PanicStep.kekDestroy);
      final dbIdx = values.indexOf(PanicStep.dbWipe);
      expect(lockIdx, greaterThanOrEqualTo(0));
      expect(pinIdx, greaterThan(lockIdx));
      expect(kekIdx, greaterThan(pinIdx));
      expect(dbIdx, greaterThan(kekIdx));
    });

    test('forceSecureWindow is the very first step', () {
      expect(PanicStep.values.first, PanicStep.forceSecureWindow);
    });

    test('tmpPurge is the very last step', () {
      expect(PanicStep.values.last, PanicStep.tmpPurge);
    });
  });

  group('PanicReport', () {
    test('records steps in insertion order', () {
      final r = PanicReport(startedAt: DateTime.now());
      r.recordSuccess(PanicStep.forceSecureWindow);
      r.recordSuccess(PanicStep.voiceCancel);
      r.recordFailure(PanicStep.pinKeysWipe, StateError('mock'));
      expect(r.steps, [
        PanicStep.forceSecureWindow,
        PanicStep.voiceCancel,
        PanicStep.pinKeysWipe,
      ]);
      expect(r.errors, hasLength(1));
      expect(r.errors.first, contains('pinKeysWipe'));
      // Anti-leak : seul le runtimeType est exposé, pas le message.
      expect(r.errors.first, isNot(contains('mock')));
    });
  });
}
