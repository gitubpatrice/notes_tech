# Vecteurs de parité — comment ils ont été produits, comment les regénérer

> Créé le 2026-08-13, en portant `normalizeTitle`.

## Le problème que ces vecteurs règlent

Trois opérations de chaînes — découper sur les espaces, élaguer les bords, passer en minuscules —
ont l'air identiques en Dart et en Kotlin. Elles ne le sont pas.

Elles servent ici à calculer `note_links.target_title_norm`, la colonne par laquelle un lien
`[[Titre]]` retrouve sa note cible. **Les deux versions de l'application écrivent dans la même
base.** Une clé calculée différemment de part et d'autre ne produit pas d'erreur : elle produit des
rétroliens qui disparaissent, en silence, chez un utilisateur qui vient de migrer.

Une relecture ne trouve pas ce genre d'écart. Il faut faire tourner les deux.

## Les trois divergences mesurées

| Opération | Java / Kotlin | Dart | Exemple qui les sépare |
|---|---|---|---|
| `\s` en expression régulière | ASCII seul | espaces Unicode | `U+202F` (espace fine insécable), omniprésente en français devant `: ; ! ?` |
| `trim()` | `Char.isWhitespace` | `White_Space` ∪ `U+FEFF` | `U+001F` élagué à tort par Kotlin ; `U+0085` conservé à tort |
| `lowercase()` | casse **complète** | casse **simple** | `ΟΔΟΣ` → `οδος` en Java (sigma final), `οδοσ` en Dart. `İ` → deux caractères en Java, un seul en Dart |

La troisième est celle qu'on aurait ratée : rien dans la documentation des deux langages ne la
signale, et le cas ne se présente pas en français.

`DartTextSemantics` reproduit les trois. Le correctif de la troisième est d'itérer sur les **points
de code** avec `Character.toLowerCase(int)` — la casse simple — au lieu de `String.lowercase()`.

## L'écart résiduel, borné et assumé

La table de casse de Dart ignore quelques alphabets ajoutés à Unicode après elle. Mesuré : l'osage
(`U+104B0`) et l'adlam (`U+1E900`) restent en majuscules côté Dart, passent en minuscules côté Java.

`PariteAvecFlutterTest.divergencesAssumees` les liste, et la liste est **fermée dans les deux
sens** : un vecteur qui diverge sans y figurer fait échouer le test, et un vecteur qui y figure sans
plus diverger le fait échouer aussi. Sans cette seconde moitié, la liste se remplirait de dispenses
périmées.

Conséquence réelle : un lien `[[…]]` écrit dans l'un de ces alphabets ne s'apparierait pas entre les
deux versions. Il se répare de lui-même dès que la version Kotlin réindexe la note.

## Regénérer les vecteurs

À faire si `normalizeTitle`, `extractFromContent` ou `stripLatinDiacritic` changent côté Flutter.

1. Créer `notes_tech/test/_tmp_parity_vectors_test.dart`. Il doit **importer le vrai code** —
   `package:notes_tech/services/backlinks_service.dart` — et non recopier l'algorithme : recopier
   reviendrait à tester une copie contre une autre copie.
2. Appeler `BacklinksService.normalizeTitle` et `BacklinksService.extractFromContent` sur le corpus,
   échapper chaque chaîne en `\uXXXX` pour tout ce qui n'est pas ASCII imprimable — antislash et
   guillemet compris — et écrire un JSON.
3. `flutter test test/_tmp_parity_vectors_test.dart`
4. **Supprimer le fichier temporaire** et vérifier `git status` : `notes_tech` est gelé pendant le
   chantier, il doit ressortir intact.
5. Convertir le JSON en TSV avec le script de génération, vers
   `notes_files_tech/app/src/test/resources/parite/`.

Le corpus doit garder les cas qui **peuvent** échouer : espaces Unicode aux extrémités et au milieu,
`U+001F`, `U+0085`, `U+200B`, `U+FEFF`, sigma final, `İ`, ligatures, caractères hors du plan de
base, titres de 200 et 201 caractères, plus de 256 liens, contenu au-delà de 50 000 caractères.

## Où ils sont rejoués

`app/src/test/java/.../domain/links/PariteAvecFlutterTest.kt`, sur la JVM, à chaque `testDebugUnitTest`.

Deux fichiers de ressources :

| Fichier | Contenu |
|---|---|
| `parite/normalize.tsv` | 67 vecteurs `entrée → sortie` de `normalizeTitle` |
| `parite/extract.tsv` | 25 vecteurs `contenu → liens (titre, clé, position)` |

Le format est du TSV parce que chaque champ est déjà échappé en `\uXXXX` : la tabulation ne peut
donc jamais apparaître littéralement dans un champ, ce qui en fait un séparateur sûr.
