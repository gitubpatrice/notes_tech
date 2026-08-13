# Audit deep-dive — Data Room (DAO / schéma hérité) — notes_files_tech (2026-08-13)

## Résumé exécutif

Périmètre : `FolderDao`, `NoteDao`, `NoteSearchDao`, `UnmanagedSchema`, `NotesDatabase`,
`DatabaseProvider`, `FolderEntity`, `NoteEntity`. Sur les motifs prioritaires du prompt (REPLACE /
rowid, fuite de note verrouillée, fidélité du schéma hérité), **le code est sain et même plus
défensif que ce que les deux relectures externes précédentes avaient pu établir** — vérifié par
bytecode Room, par tests SQLite empiriques, et par comparaison caractère-à-caractère avec le vrai
`database.dart` de Notes Tech (accessible localement sur ce poste, `j:\applications\notes_tech`,
ce que ni Gemini ni GPT n'avaient pu consulter).

En revanche, cette comparaison directe avec le code Flutter réel — au-delà du prompt fourni —
révèle **une lacune structurelle critique** (`NoteDao` n'a aucune écriture ciblée pour
pinned/favorite/archived, alors que Flutter a dû corriger un incident réel où cela déchiffrait
silencieusement une note de coffre) et **plusieurs divergences de parité confirmées** dans la
recherche et le tri (filtre `archived` absent, tri `pinned` absent de `listRecent`, tie-break
manquant, tokenisation FTS5 différente).

**Top 3 actions** :
1. Ajouter des écritures SQL ciblées (pinned/favorite/archived) à `NoteDao` avant que la couche
   repository soit écrite — sinon le premier appelant qui fait `update(note)` pour épingler une
   note de coffre ouverte la déchiffre en base, silencieusement (DR1).
2. Aligner `NoteSearchDao.search()` sur le comportement Flutter : filtrer `archived = 0` (DR2).
3. Décider consciemment (et documenter) si `observeRecent` doit trier par `pinned` — la version
   Flutter d'origine ne le fait pas (DR3).

## Score global
6 / 6 fichiers examinés en profondeur, y compris croisement avec le code source Flutter réel.
État : Attention — un point structurel à fermer avant d'écrire la couche repository (DR1),
plusieurs écarts de parité confirmés à trancher consciemment.

## Findings (tableau standard)

| # | Sévérité | Statut | Item | Fichier:ligne | Action recommandée |
|---|---|---|---|---|---|
| DR1 | Critique | CONFIRMÉ (lacune) | Pas d'écriture ciblée pinned/favorite/archived — reproduit un incident réel Flutter (déchiffrement silencieux) | NoteDao.kt:78-79 | Ajouter des `@Query` ciblées avant la couche repository |
| DR2 | Haute | CONFIRMÉ | Recherche ne filtre pas `archived = 0` (Flutter le fait) | NoteSearchDao.kt:56-62 | Ajouter `AND n.archived = 0` |
| DR3 | Moyenne | CONFIRMÉ | `observeRecent` trie par `pinned DESC` — absent du `listRecent` Flutter d'origine | NoteDao.kt:46-54 | Décider et documenter, sinon retirer `pinned DESC` |
| DR4 | Basse | CONFIRMÉ | `ORDER BY bm25(...)` sans tie-break `updated_at DESC` (présent côté Flutter) | NoteSearchDao.kt:56-62 | Ajouter `, n.updated_at DESC` |
| DR5 | Moyenne | CONFIRMÉ | `toMatchExpression` diverge sémantiquement de `_buildFtsMatch` Flutter sur ponctuation/apostrophes | NoteSearchDao.kt:81-88 | Décision de conception à documenter |
| DR6 | Moyenne | CONFIRMÉ | Écritures ciblées Kotlin muettes sur "id inconnu" (Flutter lève systématiquement une exception typée) | NoteDao.kt:78-92,102-103 ; FolderDao.kt:48-49 | Retourner `Int` (lignes affectées) sur ces méthodes |
| DR7 | Basse | CONFIRMÉ | Commentaire NoteDao.kt:34-35 surclaime la couverture d'index par l'ORDER BY | NoteDao.kt:34-35 | Corriger le commentaire (l'index couvre le WHERE, pas le tri) |
| DR8 | Basse | CONFIRMÉ | `reassignFolder` ne bump pas `updated_at` et ne renvoie pas le compte (Flutter fait les deux) | NoteDao.kt:120-121 | Aligner si le comportement doit être identique |
| DR9 | Info | CONFIRMÉ | Le SQL littéral des triggers n'est pas cité dans docs/02, seulement paraphrasé | docs/02-SCHEMA-HERITE.md §3 | Coller le SQL vérifié (voir Finding #9) |
| DR10 | Basse | PROBABLE | `incrementVaultAttempts` + relecture séparée = check-and-act non atomique | FolderDao.kt:79-83 | À traiter quand la couche appelante existera |

## Détail par finding

### Finding DR1 — `NoteDao` n'offre aucune écriture ciblée pour pinned/favorite/archived
- **Sévérité** : Critique
- **Statut** : CONFIRMÉ pour la lacune et le précédent ; PROBABLE pour la manifestation actuelle
  (aucun appelant n'existe encore dans le périmètre audité — la couche repository n'est pas écrite)
- **Preuve** : `NoteDao.kt` n'expose qu'une seule écriture générique, `update(note: NoteEntity)`
  (ligne 78-79), qui réécrit TOUTES les colonnes de la ligne — `content` et `encrypted_content`
  compris (comportement standard de `@Update` : Room génère un `UPDATE ... SET` sur toutes les
  colonnes non-PK de l'entité passée). Aucune méthode équivalente à `moveToTrash`/`restoreFromTrash`
  (qui, elles, ne touchent bien QUE `trashed_at`) n'existe pour pinned/favorite/archived.

  Le code Flutter réel (`j:\applications\notes_tech\lib\data\db\notes_dao.dart:219-271`) documente
  un **incident déjà survenu** avec ce motif exact :
  > "Ne JAMAIS repasser par [update] pour ça. `update` écrit la ligne ENTIÈRE depuis `toRow()`,
  > `content` et `encrypted_content` compris. Or l'éditeur détient l'éphémère DÉCHIFFRÉE d'une note
  > de coffre (`content` rempli, `encryptedContent == null`) : épingler une telle note réécrivait
  > son contenu en clair et effaçait son blob chiffré — la note perdait sa protection définitivement,
  > sans le moindre signal, sur un tap d'icône."

  Flutter a corrigé cela par trois méthodes étroites : `updateFlags()` (pinned/favorite/archived
  uniquement, `notes_dao.dart:245-271`), `replaceContentPayload()` (content/encrypted_content/title/
  enc_v uniquement, `notes_dao.dart:285-319`), `setTrashedAt()` (`notes_dao.dart:324-344`). Le
  générique `update()` n'est plus appelé qu'à **un seul endroit** dans tout le code Flutter,
  `notes_repository.dart:286`, encore protégé par un garde-fou d'invariant supplémentaire
  (`_guardVaultPlaintext`, appelé juste avant, `notes_repository.dart:280-283`).
- **Localisation** : `app/src/main/java/com/filestech/notes_tech/data/local/dao/NoteDao.kt:78-79`
- **Description** : le portage Kotlin n'a conservé QUE l'équivalent du chemin dangereux
  (`update()`), sans les alternatives étroites qui avaient été introduites pour le neutraliser. Rien
  ne l'exploite aujourd'hui — ni ViewModel, ni repository n'existent encore dans ce dépôt — mais
  c'est précisément le moment où le fermer coûte le moins cher : structurellement, avant que du code
  appelant n'existe, plutôt que comme un correctif après incident.
- **Impact** : si un futur code de "épingler/favoriser/archiver une note" prend la représentation en
  mémoire de la note (qui, pour une note de coffre ouverte à l'écran, est **déchiffrée** — `content`
  rempli, `encryptedContent` nul) et appelle `NoteDao.update(note)` pour n'en changer que `pinned`,
  la ligne SQL entière est réécrite : le contenu en clair remplace le blob chiffré, qui disparaît.
  La note perd sa protection **de façon permanente et silencieuse**, redevient indexée en clair par
  `notes_ai`/`notes_au` (le trigger ne masque plus rien puisque `encrypted_content` est devenu NULL),
  et apparaît désormais comme une note ordinaire dans son dossier — jusqu'à ce que l'utilisateur
  remarque que son coffre a perdu une note.
- **Action** : ajouter à `NoteDao` des `@Query` ciblées, sur le modèle de `moveToTrash`/
  `restoreFromTrash` déjà présent dans le fichier :
  ```kotlin
  @Query("UPDATE notes SET pinned = :pinned, updated_at = :updatedAt WHERE id = :id")
  suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long): Int

  @Query("UPDATE notes SET favorite = :favorite, updated_at = :updatedAt WHERE id = :id")
  suspend fun setFavorite(id: String, favorite: Boolean, updatedAt: Long): Int

  @Query("UPDATE notes SET archived = :archived, updated_at = :updatedAt WHERE id = :id")
  suspend fun setArchived(id: String, archived: Boolean, updatedAt: Long): Int
  ```
  Quand la couche repository sera écrite, elle devra en outre reproduire le garde-fou
  `_guardVaultPlaintext` de Flutter sur les appels restants au `update()` générique — hors périmètre
  de ces 6 fichiers, mais à ne pas oublier.
- **Effort** : S (le correctif DAO lui-même) — la vraie fermeture du risque (garde côté repository)
  est M et dépend d'une couche qui n'existe pas encore.
- **Référence** : aucun pattern équivalent documenté dans `docs/04-PIEGES.md` — recommandé d'y
  ajouter une entrée dédiée, sur le modèle des entrées §1/§2 déjà présentes.

### Finding DR2 — `NoteSearchDao.search()` ne filtre pas les notes archivées
- **Sévérité** : Haute
- **Statut** : CONFIRMÉ (lu + testé empiriquement)
- **Preuve** : `NoteSearchDao.kt:56-62` (`SEARCH_SQL`) :
  ```sql
  SELECT n.* FROM notes_fts f
  JOIN notes n ON n.rowid = f.rowid
  WHERE notes_fts MATCH ? AND n.trashed_at IS NULL
  ORDER BY bm25(notes_fts) ASC
  LIMIT ?
  ```
  Le Flutter réel (`notes_dao.dart:460-481`) filtre en plus `AND n.archived = 0`. J'ai rejoué les
  deux requêtes sur une base SQLite en mémoire avec une note active et une note archivée contenant
  toutes deux le terme cherché : la requête Kotlin renvoie les **deux**, la requête Flutter n'en
  renvoie **qu'une**. Résultat testé, pas déduit.

  Autre indice de cohérence interne : `NoteDao.observeActiveInFolder` (ligne 40) et
  `NoteDao.observeRecent` (ligne 49) filtrent, elles, correctement `archived = 0` — la recherche est
  la seule requête du fichier à l'omettre, ce qui suggère un oubli plutôt qu'un choix.
- **Localisation** : `NoteSearchDao.kt:56-62`
- **Impact** : un utilisateur qui archive une note pour la sortir de sa vue quotidienne continue de
  la voir remonter dans les résultats de recherche — contredisant l'intention explicite d'archivage,
  et divergeant du comportement de la version publiée qu'il connaît déjà.
- **Action** :
  ```diff
  -        WHERE notes_fts MATCH ? AND n.trashed_at IS NULL
  +        WHERE notes_fts MATCH ? AND n.trashed_at IS NULL AND n.archived = 0
  ```
- **Effort** : S

### Finding DR3 — `observeRecent` trie par `pinned DESC`, absent de `listRecent` Flutter
- **Sévérité** : Moyenne
- **Statut** : CONFIRMÉ
- **Preuve** : `NoteDao.kt:46-54` :
  ```sql
  SELECT * FROM notes
  WHERE archived = 0 AND trashed_at IS NULL
  ORDER BY pinned DESC, updated_at DESC
  LIMIT :limit
  ```
  Le Flutter réel, `notes_dao.dart:154-166` (`listRecent`) :
  ```dart
  orderBy: 'updated_at DESC',
  ```
  **Aucun `pinned`.** À l'inverse, `listByFolder` (`notes_dao.dart:59-80`) utilise bien
  `sort.sqlOrderBy`, et le mode par défaut `NoteSortMode.updatedDesc`
  (`lib/data/models/note.dart`, enum `NoteSortMode`) vaut exactement `'pinned DESC, updated_at
  DESC'` — donc `observeActiveInFolder` (`NoteDao.kt:37-44`), lui, reproduit fidèlement le
  comportement par défaut de Flutter. `observeRecent` est le seul des deux à diverger : Flutter le
  code délibérément SANS passer par `NoteSortMode`.
- **Localisation** : `NoteDao.kt:46-54`
- **Impact** : un utilisateur épingle une note ancienne (par exemple il y a six mois) puis continue
  d'utiliser l'application normalement. Dans la version Flutter, l'écran "Notes récentes" ne montre
  jamais cette note tant qu'elle n'est pas modifiée. Dans le portage Kotlin, elle reste bloquée en
  tête de "Récent" indéfiniment, devant des notes réellement touchées il y a cinq minutes — la liste
  cesse de représenter ce qu'elle annonce.
- **Action** : retirer `pinned DESC,` de `observeRecent`, sauf si c'est une amélioration UX
  délibérée — dans ce cas, la documenter (`docs/01-DECISIONS.md` est l'endroit prévu pour ce genre
  d'écart assumé).
- **Effort** : S

### Finding DR4 — Recherche : pas de tie-break après `bm25`
- **Sévérité** : Basse
- **Statut** : CONFIRMÉ
- **Preuve** : Flutter, `notes_dao.dart:472` : `ORDER BY bm25(notes_fts), n.updated_at DESC`.
  Kotlin, `NoteSearchDao.kt:60` : `ORDER BY bm25(notes_fts) ASC` — pas de second critère.
- **Localisation** : `NoteSearchDao.kt:56-62`
- **Impact** : quand deux notes ont exactement le même score de pertinence bm25 (fréquent sur des
  requêtes courtes ou des notes courtes), Flutter les départage par la plus récemment modifiée,
  Kotlin laisse l'ordre indéfini par SQLite — pas un résultat faux, mais un ordre non déterministe
  qui peut varier d'une exécution à l'autre pour la même recherche.
- **Action** : `ORDER BY bm25(notes_fts) ASC, n.updated_at DESC`
- **Effort** : S

### Finding DR5 — `toMatchExpression` diverge sémantiquement de l'algorithme Flutter
- **Sévérité** : Moyenne
- **Statut** : CONFIRMÉ
- **Preuve** : Kotlin (`NoteSearchDao.kt:64-88`) découpe sur **tout** caractère qui n'est ni lettre,
  ni chiffre, ni tiret bas (`TOKEN_SEPARATOR`), puis donne à **chaque** token le suffixe `*`. Flutter
  (`notes_dao.dart:483-503`, `_buildFtsMatch`) découpe uniquement sur les **espaces**, conserve la
  ponctuation à l'intérieur des tokens, et ne donne le suffixe `*` qu'au **dernier** token, et
  seulement s'il est purement alphanumérique.

  Conséquence concrète, testée sur une base SQLite en mémoire avec le tokenizer réel
  (`unicode61 remove_diacritics 2`) : pour la saisie `l'été`, Kotlin produit `"l"* "été"*` — un ET
  logique entre "un mot commençant par l" (extrêmement fréquent en français : le, la, les, l'...) et
  "été" — donnant des correspondances larges et bruitées. Flutter transmet la phrase `"l'été"` telle
  quelle à FTS5, qui la retokenise en interne selon `unicode61` (donc en pratique une recherche de
  phrase "l" suivi immédiatement de "été") — un résultat différent, plus étroit. Le même écart existe
  pour `C++`, `rock'n'roll`, et plus généralement tout mot avec apostrophe — extrêmement courant en
  français (l', d', j', n', qu', aujourd'hui...).

  Cette conclusion recoupe indépendamment le rapport `audits/rapport-gpt-dao.md` (section 2), qui
  avait identifié le symptôme (tokens courts et bruyants comme "l" ou "C") sans disposer du code
  Flutter réel pour établir que c'est une divergence de comportement et pas seulement un choix
  arbitraire.
- **Localisation** : `NoteSearchDao.kt:81-88`
- **Impact** : pas un bug de sécurité ni de perte de données, mais un changement de comportement
  perceptible sur une fonctionnalité coeur (recherche), pour un public francophone où les
  contractions par apostrophe sont omniprésentes. Aucune entrée de `docs/01-DECISIONS.md` ne
  documente ce choix comme délibéré.
- **Action** : décision de conception à prendre consciemment (reproduire l'algorithme Flutter, ou
  assumer et documenter l'écart) — pas de correctif mécanique à proposer sans cette décision.
- **Effort** : M

### Finding DR6 — Écritures ciblées Kotlin muettes sur "id inconnu"
- **Sévérité** : Moyenne
- **Statut** : CONFIRMÉ
- **Preuve** : côté Flutter, **quatre** méthodes de `notes_dao.dart` (`update` L219-233,
  `updateFlags` L245-271, `replaceContentPayload` L285-319, `setTrashedAt` L324-344) et
  `folders_dao.dart:66-80` (`update`) vérifient systématiquement `if (rows == 0) throw
  NoteNotFoundException(...)` / `FolderNotFoundException(...)`. C'est un motif répété assez de fois
  pour être clairement délibéré, pas accidentel.

  Côté Kotlin, les méthodes équivalentes ne renvoient rien (`Unit`) : `FolderDao.update` (ligne
  48-49), `NoteDao.update` (78-79), `NoteDao.moveToTrash` (88-89), `NoteDao.restoreFromTrash`
  (91-92), `NoteDao.deletePermanently` (102-103, plus bénin car idempotent par nature),
  `FolderDao.resetVaultAttempts` (69-70), `FolderDao.incrementVaultAttempts` (79-80). Aucune ne
  permet à l'appelant de distinguer "opération effectuée" de "identifiant inconnu, rien ne s'est
  passé". `FolderDao.delete` et `NoteDao.purgeTrashedBefore`, elles, renvoient bien `Int` — la
  cohérence n'est donc même pas respectée à l'intérieur du fichier Kotlin lui-même.
- **Localisation** : voir liste ci-dessus
- **Impact** : scénario concret — deux surfaces d'IU concurrentes sur la même note (auto-purge de la
  corbeille qui tourne en tâche de fond pendant qu'un écran affiche encore "Restaurer" pour cette
  note). Le "Restaurer" Kotlin se termine sans erreur, sans avoir rien fait ; l'IU n'a aucun moyen de
  savoir que l'opération a échoué et peut afficher un succès trompeur ou rester incohérente avec la
  base réelle. Pas une perte de données — la base reste correcte — mais une classe entière de bugs
  d'IU qui ne se signalera jamais d'elle-même.
- **Action** : faire renvoyer `Int` (nombre de lignes affectées) à ces méthodes, à l'image de ce que
  `FolderDao.delete` fait déjà, et laisser l'appelant décider s'il lève une exception. Room supporte
  nativement `Int` en retour de `@Update`/`@Query` UPDATE sans changement de la requête SQL.
- **Effort** : M (mécanique mais touche 7 signatures + leurs appelants futurs)

### Finding DR7 — Commentaire `NoteDao.kt:34-35` surclaime la couverture de l'index
- **Sévérité** : Basse
- **Statut** : CONFIRMÉ (`EXPLAIN QUERY PLAN` exécuté sur le DDL réel)
- **Preuve** : le commentaire affirme que l'ordre des clauses suit `idx_notes_folder_active`
  "pour que l'index couvre la requête". J'ai recréé exactement le DDL de `notes` +
  `idx_notes_folder_active` dans SQLite et exécuté `EXPLAIN QUERY PLAN` sur la requête réelle de
  `observeActiveInFolder` et `observeRecent` :
  ```
  observeActiveInFolder : SEARCH notes USING INDEX idx_notes_folder_active (folder_id=? AND archived=? AND trashed_at=?)
                           USE TEMP B-TREE FOR ORDER BY
  observeRecent         : SEARCH notes USING INDEX idx_notes_trashed (trashed_at=?)
                           USE TEMP B-TREE FOR ORDER BY
  ```
  L'index couvre bien le `WHERE` (c'est vrai et utile), mais **pas** le `ORDER BY pinned DESC,
  updated_at DESC` : `pinned` ne fait partie d'aucun des index existants (`idx_notes_folder_active`,
  `idx_notes_trashed`, `idx_notes_updated`, `idx_folders_parent`) — confirmé aussi en relisant le
  DDL réel de Flutter (`database.dart:769-829`), qui a le même trou. Ce n'est donc pas une régression
  introduite par le portage, seulement un commentaire Kotlin qui affirme plus que ce que SQLite fait.
- **Localisation** : `NoteDao.kt:34-35`
- **Impact** : pas de résultat faux — juste un tri en mémoire (`TEMP B-TREE`) sur chaque appel, dont
  le coût croît avec le nombre de notes actives du dossier (ou de la base entière pour
  `observeRecent`, où le tri s'applique à toutes les notes correspondantes AVANT que `LIMIT` ne
  s'applique). Négligeable pour un usage personnel typique (des centaines de notes), à surveiller si
  l'app gagne un jour l'import en masse.
- **Action** : corriger le commentaire pour préciser "couvre le filtre, pas le tri".
- **Effort** : S

### Finding DR8 — `reassignFolder` : pas de bump `updated_at`, pas de compte renvoyé
- **Sévérité** : Basse
- **Statut** : CONFIRMÉ
- **Preuve** : Flutter, `notes_dao.dart:365-386` : `reassignFolder` met à jour `folder_id` **et**
  `updated_at`, et renvoie `Future<int>` (nombre de notes déplacées) — avec un commentaire qui
  explique pourquoi : "pour que la liste reflète l'opération". Le commentaire Flutter révèle aussi
  une raison plus grave d'utiliser cette méthode : c'est **la garantie nécessaire avant
  `FoldersRepository.delete(id)`**, y compris pour les notes déjà en corbeille — sans elle, le
  `ON DELETE CASCADE` SQL les effacerait définitivement en cascade, **contournant la rétention de
  30 jours**.

  Kotlin, `NoteDao.kt:120-121` : ne touche que `folder_id`, ne renvoie rien.
  Le docstring Kotlin (lignes 114-119) évoque la même intention ("sans cela, la cascade ON DELETE
  CASCADE les emporterait"), mais la formule "quand l'utilisateur choisit de garder ses notes"
  suggère que c'est optionnel — alors que côté Flutter, c'est un **invariant de sécurité des
  données** qui s'applique même aux notes déjà à la corbeille, indépendamment d'un choix utilisateur.
- **Localisation** : `NoteDao.kt:120-121`
- **Impact direct (dans ces 6 fichiers)** : aucun — la méthode fait ce qu'elle fait correctement.
- **Impact différé (avertissement pour la suite)** : si la future logique de "suppression de
  dossier" implémente le "l'utilisateur choisit de tout supprimer" en appelant directement
  `FolderDao.delete()` sans d'abord réassigner les notes **en corbeille**, des notes dont il restait
  par exemple 25 jours de délai de grâce seraient détruites instantanément et définitivement par la
  cascade SQL — un contournement silencieux de la promesse de rétention. Ce n'est pas un défaut des
  6 fichiers audités (la couche qui orchestrerait cela n'existe pas encore), mais un piège à
  documenter maintenant, avant qu'il ne soit écrit.
- **Action** : aligner `updated_at` et le retour `Int` si la parité de comportement est voulue ; dans
  tous les cas, muscler le commentaire de `reassignFolder` pour préciser que réassigner les notes
  d'un dossier — y compris celles en corbeille — est une étape **obligatoire**, pas optionnelle,
  avant toute suppression de ce dossier, sous peine de contourner la rétention 30 jours.
- **Effort** : S (le correctif DAO) — l'invariant réel se ferme dans la future couche repository.

### Finding DR9 — Le SQL littéral des triggers n'est pas dans `docs/02-SCHEMA-HERITE.md`
- **Sévérité** : Info
- **Statut** : CONFIRMÉ (vérification faite, gap de documentation seulement)
- **Preuve** : `docs/02-SCHEMA-HERITE.md` §3 paraphrase les triggers en prose mais ne cite pas leur
  SQL, contrairement aux tables et index qui sont cités mot pour mot. J'ai comparé
  `UnmanagedSchema.kt` (fonctions `masked`/`ftsRow`/`CREATE_TRIGGERS`, lignes 96-123) au SQL réel des
  fonctions `_ftsTriggerInsertSql`/`_ftsTriggerDeleteSql`/`_ftsTriggerUpdateSql` de
  `database.dart:651-694` : **identiques au caractère près**, aux espaces près des parenthèses.
  Vérifié aussi : `note_links` (table + 3 index) et `notes_fts` (`CREATE VIRTUAL TABLE ... USING
  fts5(...)`, `tokenize='unicode61 remove_diacritics 2'`) sont identiques mot pour mot entre
  `UnmanagedSchema.kt` et `database.dart:773-903`. Le script existant
  `audits/verifier-schema-room-vs-flutter.py`, relancé, confirme aussi 0 divergence sur `folders`/
  `notes` (colonnes, types, defaults, FK, index, ordres de tri) via comparaison Room-schema-JSON vs
  PRAGMA SQLite — et son propre `FLUTTER_DDL` recopié dans le script correspond bien au vrai
  `database.dart` (à des commentaires SQL près, sans effet sur le schéma).
  **Aucune divergence trouvée nulle part sur ce motif** — meilleure preuve obtenue que ce que
  `docs/07-RELECTURES.md` pouvait établir (qui note explicitement que ni Gemini ni GPT n'avaient le
  DDL réel pour trancher un point voisin).
- **Localisation** : `docs/02-SCHEMA-HERITE.md` (fichier de doc, pas de code)
- **Impact** : aucun aujourd'hui — recommandation d'hygiène pour que la prochaine vérification n'ait
  pas à ressortir le dépôt Flutter.
- **Action** : coller le SQL exact des trois triggers dans `docs/02-SCHEMA-HERITE.md` §3, comme déjà
  fait pour les tables et index.
- **Effort** : S

### Finding DR10 — `incrementVaultAttempts` + relecture séparée : check-and-act non atomique
- **Sévérité** : Basse
- **Statut** : PROBABLE (aucun appelant n'existe encore dans le périmètre audité)
- **Preuve** : `FolderDao.kt:79-83` incrémente `vault_attempts` de façon atomique
  (`vault_attempts = vault_attempts + 1`, sérialisé par SQLite — confirmé : aucune tentative ne peut
  être "perdue" sous concurrence, donc pas d'essais gratuits). Mais la lecture qui suit
  (`vaultAttempts(id)`, lignes 82-83) est une **seconde requête séparée**. Sous deux tentatives PIN
  concurrentes, le lecteur A peut voir une valeur incrémentée par B entre-temps — pas de sous-
  comptage (donc pas de faille de sécurité), mais la décision "j'ai atteint 5, je déclenche
  l'auto-effacement" peut être prise par un appelant différent de celui qui a fait passer le compteur
  à 5, ou — cas plus délicat — un déverrouillage PIN concurrent réussi qui remet le compteur à 0
  (`resetVaultAttempts`) entre l'incrément et la relecture d'une tentative ratée pourrait faire lire
  0 à cette dernière, supprimant sa chance de déclencher le seuil. Fenêtre de course étroite,
  nécessite deux tentatives quasi simultanées sur le même coffre.
- **Localisation** : `FolderDao.kt:79-83`
- **Impact** : aucun aujourd'hui — la logique d'orchestration (incrémenter puis comparer au seuil de
  5) n'existe pas encore dans le périmètre fourni.
- **Action** : quand cette logique sera écrite, envisager de lire la valeur dans la MÊME requête que
  l'incrément (par exemple via `UPDATE ... RETURNING vault_attempts` si la version SQLite cible le
  permet) plutôt qu'en deux appels séparés.
- **Effort** : S (à traiter au moment de l'écriture de la couche appelante)

## Réponses explicites aux points invités par `audits/PROMPT-dao.md`

1. **`bm25(notes_fts)` trié `ASC`** : CONFIRMÉ correct — testé empiriquement sur deux documents (un
   texte long diluant le terme cherché, un texte court le concentrant) : SQLite FTS5 renvoie un
   score **plus négatif pour un meilleur match**, et `ASC` place bien le meilleur en tête.
2. **`toMatchExpression` et erreurs de syntaxe FTS5** : CONFIRMÉ qu'aucune saisie testée (parenthèses
   seules, `OR`/`AND`/`NOT`/`NEAR`, `title:milk`, guillemets, `*` seul, chaîne de 500 caractères,
   CJK, `-milk`) ne produit d'erreur SQLite — la neutralisation par guillemets doublés est robuste.
   En revanche, voir DR5 : les résultats peuvent diverger sémantiquement de ceux de Flutter.
3. **Contournement de la protection SQL de `FolderDao.delete` sur `inbox`** : aucun trouvé. Vérifié
   en particulier la piste la plus probable — une suppression en cascade via un dossier parent —
   sans issue, puisque `parent_id` est en `ON DELETE SET NULL` et non `CASCADE`, et que `inbox` a de
   toute façon `parent_id = NULL`.
4. Traité en DR10.

## Confrontation avec la relecture externe (`audits/rapport-gpt-dao.md`, GPT-5.2)

Corroboration mutuelle sur 5 points (REPLACE/rowid, `bm25` ASC, garde `inbox`, sûreté de
`DatabaseProvider.get()`, `incrementVaultAttempts` non permissif) — augmente la confiance sur ces
points.

Deux points où ma vérification tranche au-delà de ce que GPT avait pu établir :

- **"Référence à `notes_fts` alors que la table est aliasée `f`" (rapport-gpt-dao.md section 3,
  classé PROBABLE)** : **infirmé**. C'est l'usage standard et requis de FTS5 — `MATCH` et `bm25()`
  s'appliquent obligatoirement au nom de table virtuelle déclaré, pas à un alias. J'ai exécuté cette
  exacte requête (alias `f` pour le JOIN, `notes_fts`/`bm25(notes_fts)` sans alias pour `MATCH`) des
  dizaines de fois pendant cet audit sur SQLite 3.50.4, sans jamais d'erreur.
- **"Fuite possible si triggers absents/anciens sur une base héritée" (rapport-gpt-dao.md section 2,
  classé PROBABLE, faute d'accès au code Flutter)** : **résolu, pas seulement corroboré**. La chaîne
  de migration réelle de Flutter (`database.dart:589-596`, `_migrateToV9`) **supprime et recrée
  inconditionnellement** les trois triggers avec le masquage actuel, à chaque montée vers
  `user_version = 9`. Or `user_version = 9` est la condition sine qua non pour que ce code Kotlin
  accepte d'ouvrir la base du tout (`docs/01-DECISIONS.md` D-005). Toute base que l'app Kotlin peut
  ouvrir a donc, par construction de la chaîne de migration Flutter elle-même, des triggers à jour —
  sauf corruption du fichier hors du fonctionnement normal de l'application, scénario hors du
  périmètre de ce défaut.

GPT n'a identifié aucun des DR1/DR2/DR3/DR4/DR6/DR8 — tous nécessitaient un accès au code source
Flutter réel (`j:\applications\notes_tech`), que ni GPT ni Gemini n'avaient dans leur revue.

## Ce qui est sain

- **Item 1 (rowid/REPLACE)** : aucun défaut. Confirmé par décompilation bytecode
  (`javap -v` sur `room-common-jvm-2.8.4.jar`) que `@Insert`/`@Update` par défaut valent
  `OnConflictStrategy.ABORT` (= 3), pas `REPLACE` — aucun DAO ne le change. `UnmanagedSchema` n'émet
  que `INSERT OR IGNORE`, jamais `REPLACE`. Une hypothèse initiale (une suppression en cascade
  déclenchée par `FolderDao.delete` pourrait, sous `PRAGMA recursive_triggers = OFF` par défaut, ne
  pas déclencher `notes_ad` et laisser l'index FTS5 orphelin) a été testée empiriquement et
  **réfutée** : une suppression en cascade via clé étrangère déclenche bien les triggers `AFTER
  DELETE` du côté enfant, quel que soit `recursive_triggers` — vérifié avec un schéma minimal
  reproduisant `folders`/`notes`/trigger. Une seconde hypothèse (VACUUM renumérotant les `rowid`
  d'une table sans `INTEGER PRIMARY KEY`) a également été testée sur SQLite 3.50.4 avec des motifs de
  trous réalistes : aucune renumérotation observée — hypothèse abandonnée faute de preuve, plutôt que
  rapportée sur la seule foi d'un souvenir de documentation. Une suppression groupée en une seule
  instruction (`purgeTrashedBefore`) a aussi été testée : le trigger se déclenche bien une fois par
  ligne affectée.
- **Item 2 (fuite de note verrouillée par contournement de l'index)** : aucun défaut. Seul
  `NoteSearchDao` touche `notes_fts` ; il rejoint toujours `notes` par `rowid`. Testé empiriquement,
  y compris un scénario délibérément pessimiste (une note "verrouillée" dont le contenu clair n'a
  PAS été vidé par l'appelant, simulant un oubli du côté écriture) : le masquage est fait au niveau
  du trigger, exclusivement sur `encrypted_content IS NOT NULL`, donc **indépendant** de la
  discipline de l'appelant — plus robuste que ce que la documentation elle-même en dit.
- **Item 3 (fidélité du schéma)** : aucune divergence, vérifiée deux fois — contre `docs/02` ET
  contre le vrai `database.dart` — pour les tables `folders`/`notes` (script Python existant,
  ré-exécuté, 0 divergence), `note_links`, `notes_fts`, et les trois triggers (comparaison
  caractère-à-caractère que j'ai effectuée moi-même, cf. DR9).
- **`FolderDao.insert`/`NoteDao.insert`** : `OnConflictStrategy.ABORT` explicite, cohérent avec
  Flutter (`ConflictAlgorithm.abort` dans `folders_dao.dart:59` et `notes_dao.dart:212`).
- **`FolderDao.observeChildren`** : usage correct de `IS :parentId` (gère `NULL` correctement, une
  simple `=` ne le ferait pas).
- **`FolderDao.delete`** : la garde SQL contre la suppression d'`inbox` est en fait **plus robuste**
  que l'équivalent Flutter, qui fait le même contrôle en Dart avant d'émettre le SQL
  (`folders_dao.dart:84-93`) — un chemin d'appel alternatif pourrait en théorie la contourner côté
  Flutter, pas côté Kotlin.
- **`DatabaseProvider`** : double-checked locking correct avec `Mutex` + `@Volatile`, pas de fuite de
  `Flow` (l'objet n'expose que des fonctions `suspend`).
- **`NoteSearchDao.toMatchExpression`** : robuste contre l'injection de syntaxe FTS5 (testé contre
  parenthèses, opérateurs, guillemets, Unicode) — la question de fidélité comportementale (DR5) est
  distincte de la question de sûreté syntaxique, qui est, elle, sans défaut.
- **Item 5 (fuite de Flow / invalidation `note_links`)** : rien à signaler dans le périmètre fourni —
  `NoteLinkDao` n'existe pas encore (confirmé par recherche dans tout `app/src/main/java`), conforme
  à ce que `docs/04-PIEGES.md` et `UnmanagedSchema.kt` annoncent. Note d'avertissement pour plus
  tard : le Flutter réel (`notes_dao.dart:400-424`, `findByTitleLike`) documente un **second
  précédent réel** distinct de DR1 — une version antérieure filtrait les notes de coffre APRÈS avoir
  appliqué `LIMIT` côté SQL, ce qui amincissait les suggestions d'autocomplétion sans raison
  apparente sur un gros coffre. Le correctif Flutter a déplacé le filtre `encrypted_content IS NULL`
  DANS le SQL. À reproduire dès la conception du futur `NoteLinkDao`.

## Plan d'action ordonné

1. **Immédiat** : DR1 (écritures ciblées avant toute couche repository), DR2 (filtre archived en
   recherche).
2. **Court terme** : DR3, DR5, DR6 — trois décisions de parité à trancher consciemment et
   documenter dans `docs/01-DECISIONS.md`, pas seulement à corriger mécaniquement.
3. **Backlog** : DR4, DR7, DR8, DR9, DR10.

## Garde-fous appliqués

- Doctrine `~/.claude/references/doctrine-patrice.md` respectée — aucune reco de suppression d'une
  garde existante ; DR1/DR2/DR6 proposent au contraire d'en AJOUTER.
- Aucun fichier modifié pendant l'audit (lecture seule, y compris sur `j:\applications\notes_tech`).
- Aucun build lancé. Le script `verifier-schema-room-vs-flutter.py` a été ré-exécuté (lecture d'un
  JSON déjà généré + SQLite en mémoire) — pas une compilation.
- Chaque finding cite un fichier:ligne réel, côté Kotlin ET côté Flutter quand la comparaison
  l'exige. Trois hypothèses initialement plausibles (VACUUM/rowid, cascade FK ne déclenchant pas le
  trigger, alias FTS5 invalide) ont été testées empiriquement plutôt qu'affirmées ; deux ont été
  réfutées et retirées du rapport, une a été confirmée.
