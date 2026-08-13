// Compte de mots d'une note — le cas qu'un `isEmpty` mal placé laissait passer.
//
// `''.split(RegExp(r'\s+'))` rend `['']`, donc une longueur de 1. Le contenu
// entièrement vide était traité par une sortie anticipée, mais pas le contenu
// fait uniquement d'espaces : une note contenant trois espaces annonçait « 1 mot ».
//
// Corrigé en élaguant AVANT de tester la vacuité. Ces tests figent les deux cas,
// celui qui marchait déjà comme celui qui ne marchait pas.

import 'package:flutter_test/flutter_test.dart';
import 'package:notes_tech/data/models/note.dart';

Note _noteAvecContenu(String contenu) {
  final maintenant = DateTime.fromMillisecondsSinceEpoch(1700000000000);
  return Note(
    id: 'note-test',
    title: 'Titre',
    content: contenu,
    folderId: 'inbox',
    createdAt: maintenant,
    updatedAt: maintenant,
  );
}

void main() {
  group('Note.wordCount', () {
    test('contenu vide : aucun mot', () {
      expect(_noteAvecContenu('').wordCount, 0);
    });

    test('contenu fait uniquement d\'espaces : aucun mot', () {
      expect(_noteAvecContenu('   ').wordCount, 0);
      expect(_noteAvecContenu('\n\n').wordCount, 0);
      expect(_noteAvecContenu(' \t \n ').wordCount, 0);
    });

    test('un mot, quelles que soient les marges', () {
      expect(_noteAvecContenu('bonjour').wordCount, 1);
      expect(_noteAvecContenu('   bonjour   ').wordCount, 1);
    });

    test('plusieurs mots, espaces multiples compris', () {
      expect(_noteAvecContenu('un deux trois').wordCount, 3);
      expect(_noteAvecContenu('un    deux\n\ntrois').wordCount, 3);
    });

    test('la ponctuation isolée compte comme un mot — comportement hérité', () {
      // `split(RegExp(r'\s+'))` découpe sur les espaces et rien d'autre : les
      // marqueurs Markdown d'une case à cocher comptent donc chacun pour un mot.
      // Ce n'est pas idéal, mais c'est ce que l'application affiche depuis
      // toujours, et le corriger changerait un chiffre visible sans le dire.
      expect(_noteAvecContenu('- [ ] tâche').wordCount, 4);
    });
  });
}
