// Test de fumée : l'APPLICATION RÉELLE démarre et se parcourt.
//
// Pourquoi ce fichier existe : le retrait de l'IA embarquée a modifié cinq
// écrans en supprimant des `Provider` du graphe d'injection. Un `Provider`
// manquant ne casse PAS à la compilation — `flutter analyze` reste vert, les
// tests unitaires passent, et l'écran explose en `ProviderNotFoundException`
// au moment précis où l'utilisateur y navigue. Les tests du coffre n'auraient
// rien vu : ils instancient les services à la main, sans monter l'arbre de
// widgets.
//
// ⚠️ CE FICHIER A ÉTÉ INSTABLE, ET LA CAUSE MÉRITE D'ÊTRE LUE AVANT DE LE
// MODIFIER. Il échouait en « did not complete », sans aucune exception, alors
// que l'application était saine. Quatre défauts successifs, tous ici :
//
//   1. `app.main()` appelé dans chaque `testWidgets` empile plusieurs
//      MaterialApp dans le même processus → un seul test, une seule session.
//   2. `tester.pageBack()` cherche un tooltip « Back » ; l'application est en
//      français, il vaut « Retour » → on tape le `BackButton` de l'AppBar.
//   3. Popper `find.byType(Navigator).first` vide parfois la pile de routes et
//      FERME l'application — d'où le « did not complete » muet.
//   4. LA VRAIE CAUSE : le splash de premier lancement dure 5,5 s et anime en
//      boucle. Au premier lancement il est là, aux suivants non — le drapeau
//      est consommé. Aucune attente d'une durée fixe ne peut être juste dans
//      les deux cas, et `pumpAndSettle` ne rend jamais la main tant qu'une
//      animation tourne.
//
// La solution n'est pas d'attendre le splash mieux : c'est de SUPPRIMER LA
// VARIABLE. `FirstLaunchFlag.markShown()` avant `main()` garantit qu'il ne
// s'affiche pas, et le test devient déterministe.
//
// ÉTAT : stabilité mesurée, pas supposée. Le fichier a été retiré une fois
// (`6aba882`) faute de l'être. Il n'est revenu qu'après CINQ exécutions
// consécutives vertes sur Galaxy S9 avec le splash neutralisé — trois de
// cette version, précédées de deux d'une version sans l'étape du tiroir.
// Un seul vert ne prouvait rien sur un test dont l'instabilité était
// justement le défaut.
//
// S'il redevient intermittent, le retirer est de nouveau le bon geste : un
// test rouge une fois sur deux apprend à ignorer la CI, ce qui est pire que
// son absence. Et l'expérience de ce fichier dit où chercher — quatre
// défauts sur quatre étaient dans le test, aucun dans l'application.
//
// 🔴 Appareil de test uniquement, `-d <deviceId>` obligatoire. Ce fichier
//    utilise la base RÉELLE de l'application — c'est le but, on teste le vrai
//    chemin de démarrage — donc il écrit sur l'appareil. Et le build de test
//    étant un `assembleDebug`, un appareil portant un versionCode supérieur
//    fait échouer l'installation en `INSTALL_FAILED_VERSION_DOWNGRADE`, après
//    quoi Flutter DÉSINSTALLE la version en place — emportant les notes.
//
//    flutter test integration_test/app_smoke_test.dart -d 22dbb7390a057ece
//
// (`22dbb7390a057ece` = Galaxy S9 de test. Le S24 FE `RZCY41EGKYL` est le
//  téléphone réel : ne jamais le viser ici.)

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:notes_tech/main.dart' as app;
import 'package:notes_tech/services/first_launch_flag.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() async {
    // Neutralise le splash : c'est LA variable qui rendait ce test instable.
    await FirstLaunchFlag.markShown();
  });

  /// Pompe jusqu'à ce que [finder] existe, borné à 15 s.
  ///
  /// `pumpAndSettle` est écarté : il attend qu'AUCUNE animation ne tourne, et
  /// une seule animation persistante suffit à le faire expirer sans rien dire
  /// d'utile. Ici on attend une CONDITION observable, et l'échec nomme ce
  /// qu'on cherchait.
  Future<void> waitFor(WidgetTester tester, Finder finder, String quoi) async {
    for (var i = 0; i < 150; i++) {
      await tester.pump(const Duration(milliseconds: 100));
      if (finder.evaluate().isNotEmpty) return;
    }
    fail('jamais apparu : $quoi');
  }

  /// Attend [finder], laisse l'animation d'ouverture se terminer, puis tape.
  ///
  /// ⚠️ NE PAS remplacer par `waitFor` + `tap` : `waitFor` rend la main dès
  /// que le widget EXISTE dans l'arbre, or un `PopupMenu` en cours
  /// d'ouverture est enveloppé dans un `IgnorePointer`. Le widget est là,
  /// visible, mesurable — et aucun pointeur ne l'atteint. Le `tap()` part
  /// alors dans le vide, sans exception, et le test échoue bien plus loin,
  /// sur l'écran suivant qui n'apparaît jamais.
  ///
  /// Constaté sur `Icons.info_outline`, systématiquement. Le même défaut
  /// existait depuis le début sur `Icons.settings_outlined` : il gagnait la
  /// course par chance. C'est exactement la forme d'instabilité qui avait
  /// fait retirer ce fichier.
  ///
  /// 500 ms : l'ouverture d'un menu Material dure 300 ms.
  Future<void> waitThenTap(
    WidgetTester tester,
    Finder finder,
    String quoi,
  ) async {
    await waitFor(tester, finder, quoi);
    for (var i = 0; i < 5; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    await tester.tap(finder.first);
  }

  testWidgets('parcours des écrans touchés par le retrait de l\'IA', (
    tester,
  ) async {
    await app.main();
    await waitFor(tester, find.byIcon(Icons.travel_explore), 'accueil');
    expect(
      tester.takeException(),
      isNull,
      reason:
          'Le bootstrap ne doit lever aucune exception — un Provider '
          'retiré du graphe se manifeste ici.',
    );

    // ── Recherche ──────────────────────────────────────────────────────
    await tester.tap(find.byIcon(Icons.travel_explore).first);
    await waitFor(tester, find.byType(BackButton), 'écran de recherche');
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
    await tester.tap(find.byType(BackButton).first);
    await waitFor(tester, find.byIcon(Icons.more_vert), 'retour à l\'accueil');

    // ── Réglages ───────────────────────────────────────────────────────
    await tester.tap(find.byIcon(Icons.more_vert).first);
    await waitThenTap(tester, find.byIcon(Icons.settings_outlined), 'menu');
    await waitFor(tester, find.byType(BackButton), 'écran de réglages');
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

    // ── À propos ───────────────────────────────────────────────────────
    // Cet écran manquait au parcours, et ça s'est vu : il a continué de
    // créditer « Source du modèle Gemma 3 1B » avec un lien vers Kaggle
    // pendant tout le retrait de l'IA. Un écran de licences qui crédite un
    // composant absent est une affirmation fausse affichée à l'utilisateur,
    // pas une coquille de commentaire.
    await tester.tap(find.byType(BackButton).first);
    await waitFor(tester, find.byIcon(Icons.more_vert), 'retour à l\'accueil');
    await tester.tap(find.byIcon(Icons.more_vert).first);
    await waitThenTap(tester, find.byIcon(Icons.info_outline), 'menu');
    await waitFor(tester, find.byType(BackButton), 'écran à propos');
    expect(tester.takeException(), isNull);
    for (final mot in ['Gemma', 'Kaggle', 'sémantique']) {
      expect(
        find.textContaining(mot, findRichText: true),
        findsNothing,
        reason:
            'L\'écran à propos mentionne encore « $mot » alors que '
            'l\'application ne contient plus rien de tel.',
      );
    }

    // ── Tiroir des dossiers ────────────────────────────────────────────
    // Dernier écran touché par le retrait : il lisait `IndexingService` pour
    // la bannière d'indexation. On l'ouvre par l'API du Scaffold plutôt que
    // par un geste de glissement, qui dépend de la largeur de l'écran.
    await tester.tap(find.byType(BackButton).first);
    await waitFor(tester, find.byIcon(Icons.more_vert), 'retour à l\'accueil');
    tester.firstState<ScaffoldState>(find.byType(Scaffold)).openDrawer();
    await waitFor(tester, find.byType(Drawer), 'tiroir des dossiers');
    expect(tester.takeException(), isNull);
  });
}
