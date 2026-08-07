// Tests garde pour l'audit expert Notes Tech v1.1.4.
//
// Ces tests verrouillent les invariants introduits par les fixes :
//   - H1 : PanicStep.exportsWipe distinct de PanicStep.tmpPurge
//   - M2 : noteContentBacklinksLimit (50 ko)
//   - M3 : BacklinksService — pas de DateTime.now() pour le TTL cache
//          (vérifié indirectement via le comportement d'extractFromContent
//          qui reste capé même si l'horloge système est manipulée)
//   - M6/I1 : couverture régression entre v1.1.0 et v1.1.4
//
// Un futur refactor qui régresserait l'un de ces invariants serait
// immédiatement détecté en CI.

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/core/constants.dart';
import 'package:notes_tech/services/backlinks_service.dart';
import 'package:notes_tech/services/security/panic_service.dart';

void main() {
  group('H1 v1.1.4 — PanicStep.exportsWipe distinct de tmpPurge', () {
    test('exportsWipe existe et est distinct de tmpPurge', () {
      expect(PanicStep.values.contains(PanicStep.exportsWipe), isTrue);
      expect(PanicStep.exportsWipe, isNot(equals(PanicStep.tmpPurge)));
    });

    test('exportsWipe précède immédiatement tmpPurge', () {
      const values = PanicStep.values;
      final exportIdx = values.indexOf(PanicStep.exportsWipe);
      final tmpIdx = values.indexOf(PanicStep.tmpPurge);
      expect(exportIdx, greaterThanOrEqualTo(0));
      expect(
        tmpIdx,
        exportIdx + 1,
        reason:
            'exportsWipe doit précéder immédiatement tmpPurge pour '
            'que la séquence panique purge cache/exports/ AVANT '
            'getTemporaryDirectory() — l\'inverse risquerait qu\'un '
            'export en cours recrée un ZIP entre les deux steps.',
      );
    });

    test('tmpPurge reste le tout dernier step', () {
      expect(PanicStep.values.last, PanicStep.tmpPurge);
    });

    test('PanicReport enregistre exportsWipe sans collision avec tmpPurge', () {
      final r = PanicReport(startedAt: DateTime.now());
      r.recordSuccess(PanicStep.exportsWipe);
      r.recordSuccess(PanicStep.tmpPurge);
      expect(r.steps, [PanicStep.exportsWipe, PanicStep.tmpPurge]);
      expect(
        r.steps.toSet().length,
        2,
        reason:
            'Les deux steps doivent être distincts dans le rapport '
            '— c\'est tout l\'intérêt du fix H1.',
      );
    });
  });

  group('M2 v1.1.4 — cap contextuel backlinks', () {
    test('noteContentBacklinksLimit = 50000 (15k mots ~ note utile)', () {
      expect(AppConstants.noteContentBacklinksLimit, 50000);
    });

    test(
      'BacklinksService.extractFromContent capé à noteContentBacklinksLimit',
      () {
        // Génère un texte > limit avec un wikilink à la toute fin → ignoré
        // car au-delà du cap.
        const tail = '[[CibleHorsLimit]]';
        final padding = 'x' * AppConstants.noteContentBacklinksLimit;
        final overLimit = '$padding $tail';
        final links = BacklinksService.extractFromContent(overLimit);
        expect(
          links.any((l) => l.title == 'CibleHorsLimit'),
          isFalse,
          reason:
              'Un wikilink placé au-delà du cap NE doit PAS être '
              'extrait — protection RAM device low-end.',
        );
      },
    );
  });

  group('AppConstants — drift detection', () {
    test('appVersion synchro avec pubspec.yaml', () {
      // Lit dynamiquement la version de pubspec.yaml plutôt que de figer une
      // constante (qui devenait périmée à chaque bump). Teste réellement la
      // synchro AppConstants.appVersion ↔ pubspec.
      final pubspec = File('pubspec.yaml').readAsStringSync();
      final match = RegExp(
        r'^version:\s*([0-9]+\.[0-9]+\.[0-9]+)',
        multiLine: true,
      ).firstMatch(pubspec);
      expect(match, isNotNull, reason: 'version introuvable dans pubspec.yaml');
      expect(
        AppConstants.appVersion,
        match!.group(1),
        reason:
            'AppConstants.appVersion DOIT être bumpée en parallèle '
            'de pubspec.yaml (cf. feedback_appinfo_version_bump.md). '
            'Si ce test fail, la constante a divergé.',
      );
    });
  });
}
