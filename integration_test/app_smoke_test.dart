// Test de fumée : l'APPLICATION RÉELLE démarre et se parcourt.
//
// Pourquoi ce fichier existe : le retrait de l'IA embarquée a modifié cinq
// écrans (accueil, recherche, réglages, à propos, tiroir des dossiers) en
// supprimant des `Provider` du graphe d'injection. Un `Provider` manquant ne
// casse PAS à la compilation — `flutter analyze` reste vert, les tests
// unitaires passent, et l'écran explose en `ProviderNotFoundException` au
// moment précis où l'utilisateur y navigue.
//
// Les treize tests de `vault_invariant_test.dart` n'auraient rien vu : ils
// instancient les services à la main, sans jamais monter l'arbre de widgets.
//
// UN SEUL test, une seule session. Deux raisons, apprises en écrivant ce
// fichier — les deux étaient des défauts de MON test, pas de l'application :
//   - `app.main()` appelé dans chaque `testWidgets` empile plusieurs
//     `MaterialApp` dans le même processus, et les finders voient alors
//     des arbres concurrents ;
//   - le splash de premier lancement dure jusqu'à 5,5 s. Un test qui ne
//     pompe que 2 s cherche ses icônes sur l'écran de démarrage et conclut
//     à tort à un bouton manquant.
// Une session unique qui navigue est aussi plus proche d'un usage réel.
//
// 🔴 Mêmes précautions que les autres tests d'intégration : appareil de test
//    uniquement, `-d <deviceId>` obligatoire.
//
//    flutter test integration_test/app_smoke_test.dart -d 22dbb7390a057ece
//
// ⚠️ Contrairement aux autres fichiers, celui-ci utilise la base RÉELLE de
//    l'application : c'est le but, on teste le vrai chemin de démarrage.
//    Il crée donc des données sur l'appareil de test — jamais le lancer sur
//    un téléphone porteur de vraies notes.

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:notes_tech/main.dart' as app;

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  /// `pumpAndSettle` échoue sur un écran qui anime en boucle (le splash en
  /// a). On pompe donc un nombre borné de frames : c'est la CONSTRUCTION de
  /// l'arbre qui lève en cas de `Provider` manquant, pas son animation.
  Future<void> settle(WidgetTester tester, {int frames = 20}) async {
    for (var i = 0; i < frames; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
  }

  /// Retour arrière par le Navigator, et NON `tester.pageBack()`.
  ///
  /// `pageBack` cherche un bouton dont le tooltip vaut « Back » : sur une
  /// application dont la locale est le français, ce tooltip est « Retour »
  /// et le finder échoue. Le test signalerait alors un défaut de navigation
  /// là où il n'y a qu'un problème de langue.
  Future<void> back(WidgetTester tester) async {
    tester.state<NavigatorState>(find.byType(Navigator).first).pop();
    await settle(tester);
  }

  testWidgets('parcours complet des écrans touchés par le retrait de l\'IA', (
    tester,
  ) async {
    await app.main();
    // 8 s : couvre le splash de premier lancement (auto-dismiss à 5,5 s).
    await settle(tester, frames: 80);

    expect(
      tester.takeException(),
      isNull,
      reason:
          'Le bootstrap ne doit lever aucune exception — un Provider '
          'retiré du graphe se manifeste ici.',
    );

    // ── Accueil ────────────────────────────────────────────────────────
    final search = find.byIcon(Icons.travel_explore);
    expect(
      search,
      findsOneWidget,
      reason:
          'L\'accueil doit être affiché et porter son bouton recherche. '
          'Si ce finder échoue, vérifier d\'abord que le splash est bien '
          'terminé avant de conclure à un défaut de l\'écran.',
    );

    // ── Recherche ──────────────────────────────────────────────────────
    await tester.tap(search);
    await settle(tester);
    expect(
      tester.takeException(),
      isNull,
      reason:
          'L\'écran de recherche lisait `SemanticSearchService` et '
          '`IndexingService` dans son `initState` : leur retrait du graphe '
          'aurait explosé ici, et nulle part ailleurs.',
    );
    expect(
      find.byType(SegmentedButton<dynamic>),
      findsNothing,
      reason:
          'Le sélecteur FTS / sémantique doit avoir disparu avec la '
          'recherche sémantique.',
    );
    await back(tester);

    // ── Réglages ───────────────────────────────────────────────────────
    await tester.tap(find.byIcon(Icons.more_vert));
    await settle(tester);
    await tester.tap(find.byIcon(Icons.settings_outlined));
    await settle(tester);
    expect(
      tester.takeException(),
      isNull,
      reason:
          'Les réglages lisaient `EmbedderCoordinator` pour afficher '
          'l\'erreur d\'embedder, et `GemmaService` pour la section modèle.',
    );
    for (final mot in ['Gemma', 'sémantique', 'Semantic']) {
      expect(
        find.textContaining(mot, findRichText: true),
        findsNothing,
        reason:
            'Les réglages affichent encore « $mot » : un libellé a '
            'survécu au retrait et ment à l\'utilisateur.',
      );
    }
    await back(tester);

    // ── Tiroir des dossiers ────────────────────────────────────────────
    tester.firstState<ScaffoldState>(find.byType(Scaffold)).openDrawer();
    await settle(tester);
    expect(tester.takeException(), isNull);
    expect(find.byType(Drawer), findsOneWidget);
  });
}
