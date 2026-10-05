## Réponses explicites (les 4 points demandés)

### 1) `bm25(notes_fts)` : sens du tri
**CONFIRMÉ — `ASC` est le bon sens**. Sur FTS5, `bm25()` renvoie un score où **plus petit = plus pertinent** (donc tri ascendant).  
Localisation : `NoteSearchDao.kt:56-62`.

### 2) `toMatchExpression` : saisie pouvant encore casser la syntaxe FTS5 ?
**CONFIRMÉ — je ne vois pas de saisie utilisateur qui produise encore une erreur de syntaxe FTS5** *avec le code actuel*, parce que :
- les tokens ne contiennent que `\p{L}\p{N}_` (`TOKEN_SEPARATOR`, `NoteSearchDao.kt:64-66`),
- chaque token est transformé en `"token"*` (`toMatchExpression`, `NoteSearchDao.kt:81-88`),
- guillemets doublés par précaution.

En revanche, je vois des **résultats surprenants mais valides** (donc pas une “erreur de syntaxe”, plutôt une surprise UX) :
- `l'été` → tokens `l` et `été` → requête `"l"* "été"*` : le terme `l` est très fréquent → beaucoup de faux positifs / score bm25 bruité.  
- `C++` → token `C` seulement → `"C"*` : match très large.
- `rock'n'roll` → `rock`, `n`, `roll` : le `n` (1 lettre) élargit énormément.

(Localisation : `NoteSearchDao.kt:64-88`)

### 3) Protection SQL de la boîte de réception dans `FolderDao.delete`
**CONFIRMÉ — pas de contournement “par paramètre”** tant que l’id inbox en base est bien exactement `'inbox'` :
```sql
DELETE FROM folders WHERE id = :id AND id != 'inbox'
```
Pour supprimer la ligne inbox, il faudrait simultanément `id = 'inbox'` et `id != 'inbox'`, impossible.  
Localisation : `FolderDao.kt:65-66`.

*Seule réserve hors “contournement” : si, pour une raison externe (base corrompue / héritage), la boîte de réception n’avait pas l’id `'inbox'`, alors elle ne serait pas protégée — mais ce ne serait plus “la” inbox au sens de la contrainte.*

### 4) `incrementVaultAttempts` + relecture derrière : suffisant contre des essais gratuits ?
**CONFIRMÉ — l’incrément en base empêche l’undercount concurrent (donc pas d’essais gratuits).**  
Localisation : `FolderDao.kt:79-83`.

**Mais** la séquence “UPDATE puis SELECT” (deux requêtes séparées) peut donner une valeur relue **supérieure** à “ma tentative” en cas de concurrence (ce qui ne donne pas d’essais gratuits, mais peut “sauter” des numéros côté UI / logique appelante si elle s’attend à un +1 exact).

Scénario concret :
1. `vault_attempts = 0`.
2. Tentative A et B en parallèle :
   - A exécute `UPDATE ... +1` → 1
   - B exécute `UPDATE ... +1` → 2
3. A exécute `SELECT vault_attempts` → lit `2` (alors que “sa” tentative était la 1ère)
4. Si l’UI affiche “Tentative 2/5” après la 1ère tentative, c’est surprenant, mais **pas permissif**.

---

## Constatations (par gravité)

### (1) Perte de données / chemins vers `INSERT OR REPLACE`
**CONFIRMÉ — aucun chemin vers `INSERT OR REPLACE` dans les DAO fournis.**
- `@Insert(...ABORT)` dans `FolderDao` et `NoteDao` (`FolderDao.kt:45-46`, `NoteDao.kt:75-77`)
- `@Update` (pas de REPLACE)
- `ensureInboxFolder` utilise `INSERT OR IGNORE` (non destructif) (`UnmanagedSchema.kt:151-162`)

➡️ **Aucun défaut sur ce motif** dans ces fichiers.

---

### (2) Divulgation : une note verrouillée qui ressort en recherche

#### Dépendance totale aux triggers existants (pas de garde côté requête)
**PROBABLE (selon l’état réel des bases en production) — fuite possible si triggers absents/anciens.**

Localisations :
- Requête de recherche **ne filtre pas** `encrypted_content` : `NoteSearchDao.kt:56-62`
- Création des triggers **uniquement sur base neuve** : `UnmanagedSchema.kt:125-137`

Scénario concret de fuite (si un utilisateur a une base héritée avec triggers “anciens” qui n’effacent pas le titre, ou pas de trigger du tout) :
1. Note verrouillée `enc_v=1` : `title = "Mon secret"`, `encrypted_content IS NOT NULL`.
2. Trigger (ancien/défectueux) indexe encore `title="Mon secret"` dans `notes_fts`.
3. L’utilisateur cherche `secret`.
4. `notes_fts MATCH ?` matche la ligne, puis `JOIN notes n ON n.rowid = f.rowid` renvoie `n.*` (donc le `title` en clair).
5. Résultat : **divulgation du titre d’une note verrouillée** (et potentiellement de son existence / classement).

Ce qui me manque pour trancher en **CONFIRMÉ** : la garantie que *toutes* les bases existantes (tous historiques de versions Flutter) ont bien les triggers “masquants” actuels (ou qu’un mécanisme les met à jour à l’ouverture). Ici, ce code **ne les met pas à jour** sur une base existante.

---

### (3) Requête fausse / erreur runtime : FTS5 et alias de table

#### Référence à `notes_fts` alors que la table est aliasée `f`
**PROBABLE — risque d’erreur SQL selon parseur/version SQLite/SQLCipher.**  
Localisation : `NoteSearchDao.kt:56-62`

Dans `SEARCH_SQL` :
```sql
FROM notes_fts f
...
WHERE notes_fts MATCH ?
ORDER BY bm25(notes_fts) ASC
```

Scénario concret :
1. Sur un build SQLite où, une fois aliasée, la table n’est plus référencable par son nom dans `MATCH` / fonctions aux (au lieu de l’alias),
2. Exécution de `search("abc")`,
3. Exception du type `SQLiteException: no such column: notes_fts` ou erreur d’analyse sur `MATCH`.

Ce qui me manque pour trancher en **CONFIRMÉ** : la version exacte SQLite/FTS5 embarquée (et SQLCipher le cas échéant) sur vos cibles, et un test d’exécution de cette requête sur ces versions.

---

### (4) Index non utilisé alors qu’il existe

#### `observeActiveInFolder` : tri par `pinned` casse l’ordre de l’index `idx_notes_folder_active`
**CONFIRMÉ — index non couvrant pour l’ORDER BY (temp sort probable).**  
Localisation : `NoteDao.kt:37-44`

Index existant : `(folder_id, archived, trashed_at, updated_at DESC)`  
Requête :
```sql
WHERE folder_id=? AND archived=0 AND trashed_at IS NULL
ORDER BY pinned DESC, updated_at DESC
```

Scénario concret :
- Dossier avec 20 000 notes actives.
- Beaucoup ont `pinned=0`, quelques `pinned=1`.
- SQLite peut utiliser l’index pour filtrer `folder_id/archived/trashed_at`, **mais** doit trier par `pinned` (colonne non dans l’index) ⇒ création d’un B-tree temporaire / tri en mémoire.
- Résultat : pas “faux”, mais l’index annoncé comme “couvrant” dans le commentaire ne l’est pas pour l’ordre.

#### `observeRecent` : aucun index adapté (filtre + ordre)
**CONFIRMÉ — scan/tri probable.**  
Localisation : `NoteDao.kt:46-55`

Scénario concret :
- Base avec 100 000 notes, dont 70 000 non archivées et non supprimées.
- `observeRecent(limit=50)` doit appliquer `WHERE archived=0 AND trashed_at IS NULL` puis trier `pinned DESC, updated_at DESC`.
- Index `idx_notes_updated(updated_at)` ne peut pas satisfaire `pinned DESC` en tête + filtrage `archived/trashed_at` ⇒ plan probable : scan large + tri, répétitif à chaque invalidation.

---

### (5) Concurrence

#### `DatabaseProvider.get()` : instance unique
**CONFIRMÉ — aucun défaut sur ce motif** : double-checked + `Mutex` + `@Volatile` (`DatabaseProvider.kt:39-55`).  
Scénario évité explicitement : double ouverture concurrente donnant deux pools Room.

#### `incrementVaultAttempts` : pas d’essais gratuits, mais lecture “après coup”
Déjà traité dans les réponses explicites : **CONFIRMÉ** safe contre undercount, mais valeur relue peut “sauter” sous concurrence (`FolderDao.kt:79-83`).