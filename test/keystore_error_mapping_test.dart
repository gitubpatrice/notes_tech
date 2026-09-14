/// How native Keystore failures reach Dart.
///
/// The distinction matters beyond the message: a transient failure must never
/// trigger a vault wipe, while a software-only keystore or a missing screen
/// lock must tell the user what to do. `IllegalStateException` is also the
/// transient catch-all, so the two specific markers have to be recognised
/// before it. Found while testing PIN vault creation on an emulator without a
/// screen lock (2.0.9).
library;

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/services/security/keystore_bridge.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('notes_tech/keystore');
  late PlatformException nativeError;

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall call) async {
          throw nativeError;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  /// Same shape as `KeystoreBridge.kt`: code = exception class, message =
  /// "Class: message".
  PlatformException fromNative(String type, String message) =>
      PlatformException(code: type, message: '$type: $message');

  Future<Object?> createKeyFailure() async {
    try {
      await KeystoreBridge().createKey('vault_pin_test');
    } catch (e) {
      return e;
    }
    return null;
  }

  test('a missing screen lock is not taken for a transient failure', () async {
    nativeError = fromNative('IllegalStateException', 'DEVICE_NOT_SECURE');
    expect(await createKeyFailure(), isA<KeystoreDeviceNotSecureException>());
  });

  test('a software-only keystore is recognised', () async {
    nativeError = fromNative('IllegalStateException', 'KEYSTORE_SOFTWARE_ONLY');
    expect(await createKeyFailure(), isA<KeystoreSoftwareOnlyException>());
  });

  test('any other IllegalStateException stays transient', () async {
    nativeError = fromNative('IllegalStateException', 'missing IV');
    final e = await createKeyFailure();
    expect(e, isA<KeystoreTransientException>());
    expect(e, isNot(isA<KeystoreDeviceNotSecureException>()));
  });

  test('an invalidated key keeps its own type', () async {
    nativeError = PlatformException(
      code: 'KEY_PERMANENTLY_INVALIDATED',
      message: 'Keystore key invalidated: gone',
    );
    expect(await createKeyFailure(), isA<KeyPermanentlyInvalidatedException>());
  });
}
