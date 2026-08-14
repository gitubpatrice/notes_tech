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

---

## Les vecteurs des coffres — 2026-08-14

Même méthode que pour la normalisation des titres, avec **un contrôle de plus** : les valeurs sont
recoupées contre une **tierce implantation**, ce que la première série n'avait pas.

### Ce qui est figé

| Ressource | Contenu |
|---|---|
| `coffre_argon2.tsv` | 16 dérivations, deux jeux de paramètres (t=3/64 Mo et t=2/32 Mo) |
| `coffre_verifier.tsv` | 3 vérificateurs HMAC-SHA-256 |
| `coffre_wrap.tsv` | 2 scellements de clé de coffre, dont un `folder_id` non ASCII |
| `coffre_note.tsv` | 5 blobs de note, formats 1 et 2, titres vides et hors BMP |
| `coffre_complet.tsv` | un coffre entier : les quatre colonnes `vault_*` et une note dedans |
| `coffre_pin.tsv` | les deux couches internes d'un coffre à code |

### La procédure, reproductible

1. Écrire un test temporaire dans `j:\applications\notes_tech\test\`, qui **recopie verbatim** les
   expressions crypto de `folder_vault_service.dart` et écrit un JSON.
2. `flutter test <ce fichier>`.
3. Recouper le JSON contre `argon2-cffi` (le C de référence de la RFC 9106) et contre
   `cryptography` Python (OpenSSL).
4. Convertir en TSV ASCII — tout champ textuel en hexadécimal UTF-8.
5. **Supprimer le fichier temporaire**, et vérifier que `git status` de `notes_tech` est revenu à
   son état d'avant.

Résultat de l'étape 3 le 2026-08-14 : **37 concordances, 0 divergence**.

### ⚠️ Pourquoi l'étape 3 n'est pas facultative

Sans elle, un défaut du paquet `cryptography` Dart reproduit à l'identique côté Kotlin passerait
pour une réussite : les deux seraient d'accord, et tous deux faux. Trois implantations
indépendantes qui concordent, c'est le standard qui est implanté des deux côtés.

### ⚠️ Ce que ces vecteurs ne prouvent pas

Ils rejouent ce que j'ai **recopié** du service, pas le service lui-même. La fidélité de la recopie
se vérifie en relecture — c'est un des points sur lesquels R-008 portait — et non ici.

Et surtout : ils figent un **format**, pas une migration. Le critère de sortie de la phase 4 reste
d'ouvrir un coffre réellement créé par l'application publiée. Cf. `11-COFFRES.md` §4.

### Les cas choisis, et pourquoi

| Cas | Ce qu'il ferme |
|---|---|
| `accents_precomposes` **contre** `accents_decomposes` | la même chaîne perçue en NFC et en NFD donne des clés **différentes** : aucune normalisation Unicode ne doit s'immiscer |
| `hors_bmp` | les paires de substitution UTF-16 de Java doivent produire les mêmes octets UTF-8 que Dart |
| `espaces_aux_bords` | rien ne doit être élagué — une phrase secrète n'est pas un identifiant |
| `folderId_non_ascii` | l'AAD est en UTF-8 ; un passage en UTF-16 ou Latin-1 ne produirait aucune erreur visible, juste un tag qui ne valide plus |
| `v2_titre_vide`, `v2_contenu_vide` | le préfixe de longueur à zéro, des deux côtés de la frontière |
