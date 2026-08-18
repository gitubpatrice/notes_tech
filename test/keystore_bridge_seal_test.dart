import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/services/security/keystore_bridge.dart';

/// **Le contrat d'appel de la passerelle 2.0.4.**
///
/// `sealDatabaseKek` est le seul geste de cette version de transition : elle re-scelle la KEK de la
/// base sous l'alias que lira la version Kotlin, pendant qu'elle est encore installée et qu'elle
/// sait la lire. Ce que ces cas protègent, c'est le **contrat entre deux applications** — le nom de
/// méthode et la forme de l'argument — qu'aucun compilateur ne vérifie et qu'aucun test de l'une des
/// deux ne verrait seul.
///
/// 🔴 **Une erreur ici ne se voit pas.** Un nom de méthode changé, un argument renommé, et la 2.0.4
/// aurait l'air d'avoir fonctionné : `catch` large côté appelant, aucune erreur affichée, et la
/// 3.0.0 basculerait silencieusement sur sa couche de secours. C'est le même motif que le préfixe
/// `flutter.` de `shared_preferences`, qui est la raison pour laquelle l'écriture des préférences se
/// fait côté natif.
///
/// ⚠️ Ce que ces cas **ne** couvrent pas : le scellement lui-même, qui vit dans `KeystoreBridge.kt`
/// et demande un vrai AndroidKeyStore. Il se vérifie sur le S9, par la procédure de
/// `notes_files_tech/docs/10-PASSERELLE-2.0.4.md` §6. *Le dire vaut mieux que de laisser croire le
/// contraire.*
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const canal = MethodChannel('notes_tech/keystore');
  final appels = <MethodCall>[];
  Object? reponse = true;

  setUp(() {
    appels.clear();
    reponse = true;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(canal, (MethodCall appel) async {
          appels.add(appel);
          return reponse;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(canal, null);
  });

  final kek = Uint8List.fromList(List<int>.generate(32, (i) => i * 7 % 256));

  test('appelle sealDatabaseKek avec les 32 octets BRUTS sous la clé `kek`', () async {
    await KeystoreBridge().sealDatabaseKek(kek);

    expect(appels, hasLength(1));
    expect(appels.single.method, 'sealDatabaseKek');

    final args = appels.single.arguments as Map<Object?, Object?>;
    // ⚠️ La clé de l'argument fait partie du contrat : `KeystoreBridge.kt` lit `"kek"`.
    expect(args.keys, ['kek']);

    final envoye = args['kek'];
    // ⚠️⚠️ Des OCTETS, jamais une chaîne hexadécimale : une `String` portant la KEK serait une copie
    // du secret que Dart ne sait pas effacer. Le type est donc une assertion de sécurité, pas de
    // confort.
    expect(envoye, isA<Uint8List>());
    expect(envoye, equals(kek));
    expect((envoye as Uint8List).length, 32);
  });

  test('rend ce que le natif répond', () async {
    reponse = false;
    expect(await KeystoreBridge().sealDatabaseKek(kek), isFalse);

    reponse = true;
    expect(await KeystoreBridge().sealDatabaseKek(kek), isTrue);
  });

  /// ⚠️ Un natif plus ancien — ou un canal absent — rend `null`. Le traiter comme « rien scellé »
  /// plutôt que de laisser filer un `null` évite une exception dans un chemin de démarrage.
  test('une réponse nulle vaut « rien scellé », pas une exception', () async {
    reponse = null;

    expect(await KeystoreBridge().sealDatabaseKek(kek), isFalse);
  });
}
