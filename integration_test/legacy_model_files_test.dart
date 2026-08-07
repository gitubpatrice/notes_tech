// Purge des fichiers de modèles hérités — les DEUX faces.
//
// Pourquoi ce fichier existe : une relecture externe (GPT-5.2) a relevé que
// l'étape `PanicStep.legacyModelsWipe` n'avait aucune couverture.
// `panic_service_test.dart` ne vérifie que l'ordre de l'énumération et la
// comptabilité de `PanicReport` — l'étape pouvait donc être listée « OK »
// dans le rapport alors que rien n'était effacé sur le disque, puisque
// `_runStep` avale les exceptions en best-effort. Le constat était juste.
//
// Ce que ça vaut : chez un utilisateur venu d'une version ≤ 1.1.6, ce dossier
// contient jusqu'à 530 Mo — le `.task` Gemma qu'il avait importé à la main.
// Le mode panique promet zéro résidu ; personne ne vérifiait la promesse.
//
// LA SECONDE FACE COMPTE AUTANT. Le modèle de dictée vocale vit dans
// `<appSupport>/stt/`, à côté. Se tromper de dossier effacerait un fichier de
// 57 Mo que l'utilisateur a dû télécharger puis importer lui-même, sans
// aucun message pour le lui expliquer. Le test l'exige explicitement.
//
// 🔴 Appareil de test, `-d <deviceId>` obligatoire :
//
//    flutter test integration_test/legacy_model_files_test.dart -d 22dbb7390a057ece
//
// Le test écrit dans le sandbox RÉEL de l'application — `path_provider` n'a
// pas d'équivalent en pur Dart. Il ne crée que ses propres fichiers sondes et
// les retire, et ne supprime jamais `stt/` lui-même.

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:notes_tech/services/legacy_model_files.dart';
import 'package:path_provider/path_provider.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('purge efface `models/` mais épargne le modèle de dictée', (
    _,
  ) async {
    final support = await getApplicationSupportDirectory();
    final models = Directory(
      '${support.path}/${LegacyModelFiles.directoryName}',
    );
    final stt = Directory('${support.path}/stt');
    final sondeStt = File('${stt.path}/sonde_a_conserver.bin');

    // ── Reconstitue l'état d'un utilisateur venu de v1.1.6 ────────────────
    final sousDossier = Directory('${models.path}/cache_minilm');
    await sousDossier.create(recursive: true);
    final gemma = File('${models.path}/gemma-3-1b-int4.task');
    await gemma.writeAsBytes(List<int>.filled(4096, 42));
    await File('${sousDossier.path}/vocab.txt').writeAsString('sonde');

    // Un fichier dans `stt/`, qui NE DOIT PAS bouger. Créé par le test et
    // nettoyé à la fin : si un vrai modèle Whisper est déjà là, il n'est ni
    // lu ni touché — on vérifie la survie du dossier, pas son contenu réel.
    final sttPreexistant = await stt.exists();
    if (!sttPreexistant) await stt.create(recursive: true);
    await sondeStt.writeAsString('modèle de dictée — ne pas effacer');

    expect(await models.exists(), isTrue, reason: 'préparation ratée');
    expect(await gemma.exists(), isTrue, reason: 'préparation ratée');

    // ── La purge ─────────────────────────────────────────────────────────
    expect(await LegacyModelFiles.purge(), isTrue);

    expect(
      await models.exists(),
      isFalse,
      reason:
          'Le dossier des modèles hérités survit à la purge. Chez un '
          'utilisateur qui met à jour, ce sont jusqu\'à 530 Mo qu\'il ne '
          'voit pas et ne peut effacer qu\'en détruisant ses notes.',
    );
    expect(
      await gemma.exists(),
      isFalse,
      reason: 'la suppression n\'est pas récursive',
    );

    expect(
      await sondeStt.exists(),
      isTrue,
      reason:
          '⚠️ LA PURGE A MORDU SUR `stt/`. Le modèle de dictée vocale y vit. '
          'L\'utilisateur devrait le retélécharger et le réimporter à la '
          'main, sans qu\'aucun message ne lui dise pourquoi.',
    );
    expect(await stt.exists(), isTrue);

    // ── Idempotence : la panique rappelle la purge après le boot ─────────
    expect(
      await LegacyModelFiles.purge(),
      isTrue,
      reason:
          'Un second appel sur un dossier déjà absent doit réussir, pas '
          'échouer : le démarrage purge une fois, la panique repurge sans '
          'dépendre de ce premier passage.',
    );

    // ── Ménage ───────────────────────────────────────────────────────────
    if (await sondeStt.exists()) await sondeStt.delete();
    if (!sttPreexistant && await stt.exists()) await stt.delete();
  });
}
