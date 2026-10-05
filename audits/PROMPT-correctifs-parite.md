# Relecture — LES CORRECTIFS, pas le code d'origine

## Pourquoi cette relecture existe

Ces fichiers viennent d'etre **corriges** a la suite d'un audit. Sur ce portefeuille, les
correctifs d'audit contiennent des defauts plus souvent que le code d'origine : 12 defauts
introduits en corrigeant 53 constats sur une application voisine, 8 en deux passes sur une autre.

Je ne te demande donc pas de rejuger la conception. Je te demande de chercher ce que la
**correction** a casse ou oublie.

## Ce qui a ete corrige, et pourquoi

### 1. NoteDao — suppression de toute ecriture de ligne entiere

Avant : une seule methode `@Update fun update(note: NoteEntity)`.

Probleme : elle reecrit TOUTES les colonnes depuis l'objet fourni, `content` ET
`encrypted_content`. Or l'editeur detient l'ephemere DECHIFFREE d'une note de coffre
(`content` rempli, `encryptedContent == null`). Ecrire cet objet efface le blob chiffre et pose le
contenu en clair : la note perd sa protection definitivement, sans signal. **Cet incident a
reellement eu lieu dans l'application publiee**, sur un tap sur l'icone d'epinglage.

Apres : plus aucune ecriture generique. Cinq ecritures ciblees, et `updateEditableFields` porte
`AND encrypted_content IS NULL`.

### 2. FtsMatchExpression — transposition a l'identique de l'algorithme Flutter

Avant : decoupage sur toute ponctuation, prefixe `*` sur CHAQUE terme.
Apres : decoupage sur les espaces seuls, prefixe uniquement sur le DERNIER terme s'il est
alphanumerique — ce que fait l'application publiee.

### 3. NoteSearchDao — ajout de `AND n.archived = 0` et du tri secondaire `n.updated_at DESC`

## Ce que je veux que tu cherches, par ordre de gravite

1. **Un trou dans la garde.** Existe-t-il un chemin, parmi les cinq ecritures ciblees, par lequel
   une note de coffre pourrait encore perdre son `encrypted_content`, ou voir son contenu ecrit en
   clair ? Regarde en particulier `replaceContentPayload`, qui est le SEUL a ecrire le blob.
2. **Une regression fonctionnelle introduite par le decoupage.** Une operation que l'ancienne
   ecriture generique faisait, et qu'aucune des cinq ciblees ne fait plus. Enumere ce qui manque.
3. **`COALESCE(:x, x)` avec des parametres Kotlin nullables.** Vois-tu un cas ou Room lierait
   autre chose que NULL, ou ou la semantique differerait de « laisser inchange » ?
4. **La transposition FTS5 est-elle fidele ?** L'original Dart :
   ```dart
   final cleaned = raw.trim();
   if (cleaned.isEmpty) return '';
   final tokens = cleaned.split(RegExp(r'\s+')).where((t) => t.isNotEmpty)
       .map((t) => t.replaceAll('"', '""')).toList();
   if (tokens.isEmpty) return '';
   for (var i = 0; i < tokens.length; i++) {
     final isLast = i == tokens.length - 1;
     final canPrefix = isLast && RegExp(r'^[\p{L}\p{N}]+$', unicode: true).hasMatch(tokens[i]);
     quoted.add(canPrefix ? '"${tokens[i]}"*' : '"${tokens[i]}"');
   }
   return quoted.join(' ');
   ```
   Vois-tu une saisie pour laquelle le Kotlin et le Dart divergent ?
5. **Des tests vacants.** Un test qui passerait aussi bien AVANT le correctif, donc qui ne prouve
   rien. Dis lequel et pourquoi.

## Choix deliberes — NE PAS les signaler

- Les ecritures rendent un `Int` (lignes touchees) au lieu de lever : c'est au repository de
  trancher. Voulu.
- `replaceContentPayload` ne touche pas a `updated_at` : voulu, une reparation d'arriere-plan ne
  doit pas reordonner l'ecran de l'utilisateur.
- `observeActiveInFolder` trie par `pinned DESC` mais pas `observeRecent` : c'est ce que fait
  l'application publiee, les deux ecrans different.
- `note_links` hors du graphe Room, base dans `app_flutter/`, entites non `data class`.
- Les noms de tests en francais avec tirets bas.

## Regles de forme

- **Aucun renommage, aucune preference de style, aucune architecture alternative.**
- Scenario d'echec concret pour chaque constat : quelles donnees, quelle sequence, quel resultat.
- Distingue CONFIRME de PROBABLE (dis ce qui te manque).
- Cite fichier:ligne.
- **« Aucun defaut sur ce motif » est une reponse attendue.** Ne remplis pas le rapport.
