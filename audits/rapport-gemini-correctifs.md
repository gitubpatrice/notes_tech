Voici le résultat de la relecture, classé par ordre de gravité selon tes critères.

### 1. Un trou dans la garde (CONFIRMÉ)
**Fichier :** `NoteDao.kt`, lignes 148-164 (`replaceContentPayload`)

**Scénario d'échec :** 
La méthode `replaceContentPayload` est désormais le seul chemin pour écrire le blob, mais elle ne possède **aucune garde** de type `AND encrypted_content IS NOT NULL`. De plus, son paramètre `encryptedContent` est nullable. 
Si un appelant (par erreur de programmation) utilise cette méthode au lieu de `updateEditableFields` pour sauvegarder l'éphémère déchiffrée d'une note de coffre, il passera le texte en clair dans `content` et `null` dans `encryptedContent`. La requête s'exécutera, écrasera le blob par `NULL` et posera le texte en clair. 
**Résultat :** L'invariant n'est pas totalement tenu par la base. La protection d'une note de coffre peut encore être détruite si l'appelant se trompe de méthode, car la base ne refuse pas la transition `NOT NULL -> NULL` sur cette requête.

### 2. Une régression fonctionnelle introduite par le découpage (CONFIRMÉ)
**Fichier :** `NoteDao.kt`, lignes 104-111 (`updateEditableFields`)

**Scénario d'échec :**
L'ancienne écriture générique permettait de modifier les étiquettes (`tags`) de n'importe quelle note. Dans la nouvelle architecture, `updateEditableFields` est la **seule** méthode capable d'écrire la colonne `tags`. Or, elle porte la garde `AND encrypted_content IS NULL`.
**Résultat :** Il est devenu impossible de modifier les étiquettes d'une note de coffre. Si l'utilisateur déverrouille une note, lui ajoute un tag et sauvegarde, la requête retournera `0` (bloquée par la garde) et le nouveau tag sera silencieusement perdu.

### 3. `COALESCE(:x, x)` avec des paramètres Kotlin nullables (CONFIRMÉ)
**Fichier :** `NoteDao.kt`, lignes 148-164 (`replaceContentPayload`)

**Scénario d'échec :**
La requête utilise `title = COALESCE(:title, title)`. Comme documenté, à partir du format 2, le titre vit dans le blob chiffré. Lors de la migration d'une note v1 vers v2 (ou lors du verrouillage d'une note en clair), la colonne `title` de la base de données doit être vidée pour ne pas fuiter.
Si l'appelant passe `title = null` à la méthode en s'attendant à ce que Room écrive `NULL` dans la colonne, `COALESCE(NULL, title)` évaluera à `title` (l'ancienne valeur).
**Résultat :** Le titre en clair d'origine restera intact dans la base de données à côté du nouveau blob chiffré, créant une fuite de données définitive. (Il faudrait passer `""` pour l'écraser, ce qui rend l'usage de `null` très piégeux ici).

### 4. La transposition FTS5 est-elle fidèle ?
**Aucun défaut sur ce motif.** 
Le comportement de `split(Regex("\\s+"))` en Kotlin, combiné à `replace` (qui remplace toutes les occurrences en Kotlin, équivalent au `replaceAll` de Dart) et à la vérification du dernier index, produit une chaîne strictement identique à l'algorithme Dart pour toutes les saisies (espaces multiples, mots avec apostrophes, caractères Unicode).

### 5. Des tests vacants (CONFIRMÉ)
**Fichier :** `LegacyDatabaseOpeningTest.kt`, ligne 152

**Test vacant :** `assertThat(dao.search("l'été").first()).isEmpty()` dans la méthode `une_saisie_qui_n_est_que_ponctuation_ne_fait_pas_echouer_la_recherche`.

**Pourquoi il ne prouve rien :** 
Ce test est censé prouver que le bug de l'apostrophe (qui découpait `"l'été"` en `"l"* "été"*`) est corrigé. Cependant, la base de données de test (le fixture) ne contient que deux notes ("Réunion budget" et "Codes bancaires"). 
Même **avant** le correctif, l'ancien algorithme bogué cherchait `"l"* "été"*`. Puisqu'aucun mot ne commence par "l" ni par "été" dans ce jeu d'essai restreint, la recherche retournait *déjà* une liste vide. L'assertion passait donc au vert avec le code défectueux. Elle ne prouve pas que la requête générée est correcte, seulement que la base ne contient pas ces mots.