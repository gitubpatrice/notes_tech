## 1) Trou dans la garde (coffre → perte de `encrypted_content` / clair au repos)

### 1.1 — `replaceContentPayload` permet d’écrire du clair **tout en laissant le blob chiffré**  
**Statut : CONFIRMÉ**  
**Localisation :** `NoteDao.kt:180-196`

**Pourquoi :** la requête écrit toujours `content = :content` et `encrypted_content = :encryptedContent`, sans contrainte liant les deux. Donc un appelant peut laisser `encrypted_content` non nul (note toujours “verrouillée” au sens `isLocked`) tout en stockant un `content` non vide en clair.

**Scénario d’échec concret :**
- Données : note de coffre existante `id=A`, `encrypted_content = <blob non nul>`, `content = ''`.
- Séquence :
  1. L’app déchiffre en mémoire (ou récupère un contenu clair) `"1234 5678"`.
  2. Bug d’appelant : `replaceContentPayload(id="A", content="1234 5678", encryptedContent=<blob>, title=null, encVersion=null)`.
- Résultat : en base, `encrypted_content` reste non nul **et** `content` contient `"1234 5678"` en clair → fuite au repos (même si la note “reste verrouillée” pour l’UI).

> C’est exactement le type de défaut que la suppression de l’`@Update` voulait rendre impossible “par construction”, mais il reste possible via **le seul** chemin qui écrit le payload.

---

### 1.2 — `replaceContentPayload` peut effacer le blob si on lui passe `encryptedContent = null`  
**Statut : PROBABLE** (dépend des appelants, non fournis)  
**Localisation :** `NoteDao.kt:180-196`

**Pourquoi :** `encryptedContent` est nullable, et la requête fait `encrypted_content = :encryptedContent`. Un appelant qui a en main une “éphémère déchiffrée” (où `encryptedContent == null`) peut, en l’utilisant par erreur ici, reproduire la destruction : blob effacé + clair persisté.

**Scénario d’échec concret :**
- Données : note coffre `id=A`, `encrypted_content=<blob>`, `content=''`.
- Séquence :
  1. L’éditeur détient un objet mémoire avec `content="..."` et `encryptedContent=null`.
  2. Mauvais appel (refactor/erreur) : `replaceContentPayload(id="A", content=<clair>, encryptedContent=null)`.
- Résultat : `encrypted_content` devient `NULL` et `content` devient clair → note définitivement déprotégée.

**Ce qu’il me manque pour trancher “confirmé” :** les appels repository/service qui utilisent `replaceContentPayload` (pin/trash/move/etc.) et la manière dont l’“éphémère déchiffrée” circule.

---

## 2) Régression fonctionnelle due au découpage (ce que `update(note)` faisait et que les 5 écritures ne font plus)

### 2.1 — Mise à jour atomique “tout-en-un” impossible en **un seul** `UPDATE`
**Statut : PROBABLE** (régression seulement si des call-sites comptaient sur l’atomicité)  
**Localisation :** `NoteDao.kt:130-165`, `198-206`, `209-210` (écritures séparées)

**Pourquoi :** avant, `update(note)` permettait de modifier en une écriture un mélange arbitraire de colonnes (ex. `folder_id` + `pinned` + `trashed_at` + `tags` + `updated_at`). Maintenant, ces groupes sont répartis entre plusieurs méthodes → plusieurs statements.

**Scénario d’échec concret :**
- Données : note `id=B` non verrouillée.
- Séquence :
  1. Une action UI “déplacer vers dossier X et archiver” (ou “restaurer et déplacer”, etc.) fait `moveToFolder(...)` puis `updateFlags(... archived=true ...)`.
  2. Crash/process kill entre les deux.
- Résultat : état partiellement appliqué (note déplacée mais non archivée, ou inverse), alors qu’avant `update(note)` appliquait tout d’un bloc.

**Ce qu’il me manque :** les opérations métier existantes et si elles encapsulent bien ces doubles écritures dans une transaction côté repository.

---

### 2.2 — (Cas coffre) éditer `tags/title` d’une note verrouillée sans toucher au payload n’existe plus
**Statut : PROBABLE** (dépend de la UX attendue ; Flutter peut ou non permettre ça)  
**Localisation :** `NoteDao.kt:130-137` (garde `encrypted_content IS NULL`) + absence d’une écriture “tags-only/title-only”

**Scénario d’échec concret :**
- Données : note coffre `id=C`, `encrypted_content != NULL`.
- Séquence :
  1. L’utilisateur veut ajouter un tag (ou renommer) **sans déverrouiller** (action possible dans certaines apps).
  2. Avant : `update(note)` pouvait écrire `tags/title` sans toucher au blob si l’appelant recopiant les champs le faisait.
  3. Maintenant : `updateEditableFields` ne touche rien (garde), `updateFlags` n’a pas `tags/title`.
- Résultat : l’opération devient impossible (ou force un chemin plus lourd via `replaceContentPayload`, donc déchiffrement/réchiffrement).

**Ce qu’il me manque :** la spec produit/Flutter : est-ce un usage supporté ou non.

---

## 3) `COALESCE(:x, x)` avec des nullables Kotlin

**Statut : AUCUN DÉFAUT SUR CE MOTIF**  
**Localisation :** `NoteDao.kt:149-165`

- Les paramètres sont bien typés nullable (`Boolean?`), donc Room peut binder `NULL` (et ne pas le remplacer par `0/false`).
- La sémantique SQL est bien “laisser inchangé” quand l’argument est `NULL`, et “écraser” quand il est non nul (y compris `false`).

> Le seul cas qui changerait cette conclusion serait un mapping Room qui binderait `false` à la place de `NULL` pour un `Boolean?` — ce n’est pas le comportement normal de Room.

---

## 4) Fidélité FTS5 (Kotlin vs Dart) — divergence de saisie

### 4.1 — Espaces Unicode (NBSP, narrow no‑break…) : split/trim peuvent diverger
**Statut : PROBABLE → très plausible** (divergence dépend de la définition exacte de `\s` / `trim` côté Dart/JS)  
**Localisation :** `FtsMatchExpression.kt:38` (WHITESPACE = `\\s+`), `:56-63`

**Saisie divergente typique :** `reunion\u202Fbudget` (U+202F NARROW NO‑BREAK SPACE, fréquent en typographie FR avant `:;!?` selon claviers/collers)

- **Dart** : `split(RegExp(r'\s+'))` a de fortes chances de considérer U+202F comme whitespace ⇒ tokens `["reunion","budget"]`, dernier prefixable ⇒ `"reunion" "budget"*`.
- **Kotlin/Java Regex** : `\s` peut être ASCII-only selon l’engine/flags ⇒ pas de split ⇒ token unique `"reunion\u202Fbudget"` (non prefixable, pas de `*`).

**Scénario d’échec concret :**
- Données : une note indexée contenant “Réunion budget”.
- Séquence :
  1. L’utilisateur copie/colle “reunion budg” (avec U+202F) depuis une app.
  2. Flutter : construit une expression avec préfixe sur “budg” ⇒ match.
  3. Kotlin : construit une phrase unique sans préfixe ⇒ peut ne rien retourner (ou retourner différemment).
- Résultat : résultats différents Flutter vs Kotlin pour une saisie réaliste (copier-coller / clavier FR).

**Ce qu’il me manque pour “confirmé” à 100% :** la sémantique exacte de `\s` dans l’environnement Dart visé (mais en pratique elle est généralement Unicode-large via RegExp JS).

---

## 5) Test vacant (passe aussi bien avant le correctif)

### `une_saisie_qui_n_est_que_ponctuation_ne_fait_pas_echouer_la_recherche`
**Statut : CONFIRMÉ (test non-probant pour le correctif de découpage/prefix)**  
**Localisation :** `LegacyDatabaseOpeningTest.kt:160-173`

**Pourquoi il est “vacant” vis-à-vis du correctif #2 :**
- Il vérifie surtout “ne crash pas” et souvent “renvoie vide”.
- Avec l’ancien algo fautif (split ponctuation + `*` sur chaque terme), ces assertions peuvent **toutes** passer sur ce fixture :
  - `"l'été"` aurait pu devenir `"l"* "été"*` (algo fautif) mais le fixture ne contient visiblement ni “l” ni “été” ⇒ résultat vide quand même (`:172`).
  - `"NEAR"` (`:170`) peut renvoyer vide qu’il soit neutralisé ou interprété comme terme/opérateur, selon corpus ⇒ l’assertion ne discrimine pas.

**Scénario concret :**
- Avant correctif #2, l’algo pouvait produire une expression beaucoup trop large sur certains corpus (ex. `"l"* ...`).
- Sur *ce* jeu de données, ça reste vide ⇒ le test passe avant/après ⇒ il ne prouve pas que le découpage a été rendu fidèle.

---