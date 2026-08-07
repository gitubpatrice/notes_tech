// Parité FR ↔ EN des chaînes localisées.
//
// Rien ne garantissait jusqu'ici qu'une chaîne ajoutée d'un côté le soit de
// l'autre. Une clé manquante en anglais ne casse pas la compilation : elle
// tombe en repli sur le français, et l'utilisateur anglophone lit une phrase
// française au milieu de son écran — typiquement dans un dialogue de
// confirmation, c'est-à-dire au pire moment.
//
// Ce test vérifie aussi les PLACEHOLDERS. Une chaîne paramétrée dont les
// deux versions ne déclarent pas les mêmes variables produit soit un `{name}`
// affiché brut, soit une génération de code qui diverge entre les locales.

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> _load(String path) =>
    json.decode(File(path).readAsStringSync()) as Map<String, dynamic>;

/// Clés de traduction réelles : on écarte les entrées de métadonnées `@clé`
/// et l'en-tête `@@locale`.
Set<String> _messageKeys(Map<String, dynamic> arb) =>
    arb.keys.where((k) => !k.startsWith('@')).toSet();

Set<String> _placeholders(Map<String, dynamic> arb, String key) {
  final meta = arb['@$key'];
  if (meta is! Map) return const {};
  final ph = meta['placeholders'];
  if (ph is! Map) return const {};
  return ph.keys.map((k) => k.toString()).toSet();
}

void main() {
  final fr = _load('lib/l10n/app_fr.arb');
  final en = _load('lib/l10n/app_en.arb');

  test('aucune chaîne française sans équivalent anglais', () {
    final manquantes = _messageKeys(fr).difference(_messageKeys(en)).toList()
      ..sort();
    expect(
      manquantes,
      isEmpty,
      reason:
          'Ces clés existent en FR mais pas en EN : l\'utilisateur '
          'anglophone verra du français.',
    );
  });

  test('aucune chaîne anglaise orpheline', () {
    final orphelines = _messageKeys(en).difference(_messageKeys(fr)).toList()
      ..sort();
    expect(
      orphelines,
      isEmpty,
      reason:
          'Ces clés existent en EN mais pas en FR — soit la traduction '
          'française manque, soit la clé est morte et doit partir.',
    );
  });

  test('les deux versions déclarent les mêmes placeholders', () {
    final divergences = <String>[];
    for (final key in _messageKeys(fr).intersection(_messageKeys(en))) {
      final pFr = _placeholders(fr, key);
      final pEn = _placeholders(en, key);
      if (pFr.length != pEn.length || !pFr.containsAll(pEn)) {
        divergences.add('$key : FR=$pFr EN=$pEn');
      }
    }
    divergences.sort();
    expect(divergences, isEmpty);
  });

  test('aucune chaîne vide', () {
    final vides = <String>[];
    for (final arb in [('fr', fr), ('en', en)]) {
      for (final key in _messageKeys(arb.$2)) {
        final v = arb.$2[key];
        if (v is String && v.trim().isEmpty) vides.add('${arb.$1}/$key');
      }
    }
    expect(vides, isEmpty);
  });

  test('aucune chaîne traduite n\'est orpheline', () {
    // Motif « chemin mort » de la méthode d'audit : une chaîne traduite que
    // personne n'appelle est soit du poids mort, soit — bien pire — le
    // vestige d'une fonctionnalité annoncée et devenue inatteignable. Sur ce
    // portefeuille, une ressource orpheline a déjà signalé une fonctionnalité
    // livrée mais non câblée.
    final code = StringBuffer();
    for (final f in Directory('lib').listSync(recursive: true)) {
      if (f is! File || !f.path.endsWith('.dart')) continue;
      // Les fichiers générés de `lib/l10n/` déclarent toutes les clés : les
      // inclure rendrait le test toujours vert.
      if (f.path.replaceAll(r'\', '/').contains('lib/l10n/')) continue;
      code.write(f.readAsStringSync());
    }
    final source = code.toString();
    final orphelines = _messageKeys(fr).where((k) {
      return !RegExp('[.\\b]${RegExp.escape(k)}\\b').hasMatch(source);
    }).toList()..sort();
    expect(
      orphelines,
      isEmpty,
      reason:
          'Ces clés sont traduites mais appelées nulle part. Soit le '
          'code qui devait les utiliser manque, soit elles doivent partir.',
    );
  });

  test('le corps d\'avertissement du coffre existe dans les deux langues', () {
    // Garde ciblée : ce dialogue est la seule chose qui prévient l'utilisateur
    // que vider un coffre vers la Boîte de réception déchiffre TOUTES ses
    // notes. Il n'avertissait de rien avant, et présentait même ce choix
    // comme l'option sûre.
    for (final arb in [fr, en]) {
      expect(arb['folderDeleteVaultChoiceBody'], isNotNull);
      expect(arb['folderDeleteMoveToInboxVault'], isNotNull);
    }
    expect(_placeholders(fr, 'folderDeleteVaultChoiceBody'), {'name'});
  });
}
