import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/services/embedding/local_embedder.dart';
import 'package:notes_tech/utils/vector_math.dart';

void main() {
  const e = LocalEmbedder();

  test('dimension stable et > 0', () {
    expect(e.dim, greaterThan(0));
    final v = e.embed('bonjour le monde');
    expect(v.length, e.dim);
  });

  test('vecteurs L2-normalisés', () {
    final v = e.embed('un texte de test avec quelques mots');
    expect(VectorMath.l2Norm(v), closeTo(1.0, 1e-3));
  });

  test('déterminisme strict', () {
    final v1 = e.embed('Voici une note de test');
    final v2 = e.embed('Voici une note de test');
    for (var i = 0; i < v1.length; i++) {
      expect(v1[i], v2[i]);
    }
  });

  test('insensibilité aux accents et à la casse', () {
    final a = e.embed('Été à Paris');
    final b = e.embed('ete a paris');
    final sim = VectorMath.cosineNormalized(a, b);
    expect(sim, greaterThan(0.95));
  });

  test('similarité plus haute pour textes proches', () {
    final base = e.embed('méditation guidée pour le sommeil');
    final close = e.embed('méditation pour mieux dormir');
    final far = e.embed('comptabilité fiscale entreprise');
    final simClose = VectorMath.cosineNormalized(base, close);
    final simFar = VectorMath.cosineNormalized(base, far);
    expect(simClose, greaterThan(simFar));
    expect(simClose, greaterThan(0.2));
  });

  test('texte vide produit un vecteur nul', () {
    final v = e.embed('');
    var allZero = true;
    for (var i = 0; i < v.length; i++) {
      if (v[i] != 0.0) {
        allZero = false;
        break;
      }
    }
    expect(allZero, isTrue);
  });

  test('embedTitleAndBody : un titre pertinent pèse plus que le bruit du corps',
      () {
    const noise =
        'plein de remarques diverses sans rapport vacances jardin courses';
    final aBodyOnly = e.embedTitleAndBody(
      title: '',
      body: 'recette tarte tatin $noise',
    );
    final bTitled = e.embedTitleAndBody(
      title: 'recette tarte tatin',
      body: noise,
    );
    final query = e.embed('tarte tatin');
    final simBody = VectorMath.cosineNormalized(aBodyOnly, query);
    final simTitled = VectorMath.cosineNormalized(bTitled, query);
    expect(simTitled, greaterThan(simBody));
  });
}
