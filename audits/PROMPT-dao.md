# Relecture — couche DAO

## Contexte

Portage Kotlin natif d'une application Flutter publiée. Ces DAO accèdent à la base **déjà présente
chez les utilisateurs** : notes personnelles, aucune sauvegarde, base chiffrée par une clé scellée
dans le Keystore de l'appareil.

Schéma hérité, non modifiable (créé par sqflite) :

```sql
CREATE TABLE folders (
  id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, parent_id TEXT, color INTEGER, icon TEXT,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
  vault_salt BLOB, vault_kek_wrapped BLOB, vault_iv BLOB, vault_verifier BLOB,
  vault_mode TEXT, vault_pin_blob BLOB, vault_pin_iv BLOB,
  vault_attempts INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY (parent_id) REFERENCES folders(id) ON DELETE SET NULL);
CREATE INDEX idx_folders_parent ON folders(parent_id);

CREATE TABLE notes (
  id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL,
  encrypted_content BLOB, folder_id TEXT NOT NULL, tags TEXT NOT NULL DEFAULT '',
  pinned INTEGER NOT NULL DEFAULT 0, favorite INTEGER NOT NULL DEFAULT 0,
  archived INTEGER NOT NULL DEFAULT 0, trashed_at INTEGER,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, enc_v INTEGER NOT NULL DEFAULT 1,
  FOREIGN KEY (folder_id) REFERENCES folders(id) ON DELETE CASCADE);
CREATE INDEX idx_notes_folder_active ON notes(folder_id, archived, trashed_at, updated_at DESC);
CREATE INDEX idx_notes_trashed ON notes(trashed_at);
CREATE INDEX idx_notes_updated ON notes(updated_at);

CREATE TABLE note_links (
  source_id TEXT NOT NULL, target_id TEXT, target_title TEXT NOT NULL,
  target_title_norm TEXT NOT NULL, position INTEGER NOT NULL,
  FOREIGN KEY (source_id) REFERENCES notes(id) ON DELETE CASCADE,
  FOREIGN KEY (target_id) REFERENCES notes(id) ON DELETE SET NULL);

CREATE VIRTUAL TABLE notes_fts USING fts5(
  title, content, tags, content='notes', content_rowid='rowid',
  tokenize='unicode61 remove_diacritics 2');
```

Trois triggers (`notes_ai`, `notes_ad`, `notes_au`) maintiennent l'index et **masquent**
titre/contenu/etiquettes de toute note dont `encrypted_content` n'est pas nul — c'est la seule
chose qui empêche une note verrouillee de ressortir dans une recherche.

`encrypted_content` non nul = note verrouillee dans un coffre. `enc_v` = 1 (contenu seul, titre en
clair) ou 2 (titre ET contenu chiffres, colonne `title` videe).

## Ce que je cherche, par ordre de gravite

1. **Perte de donnees.** Tout chemin aboutissant a un `INSERT OR REPLACE`, meme indirect. Sur ce
   schema, REPLACE = DELETE + INSERT = nouveau rowid = index FTS5 desynchronise + cascade qui
   supprime les backlinks. Et `PRAGMA recursive_triggers` vaut OFF, donc le trigger de suppression
   ne se declenche meme pas.
2. **Divulgation.** Toute requete par laquelle le contenu ou le titre d'une note verrouillee
   pourrait ressortir.
3. **Requete fausse.** Tri, filtre, ou jointure qui rendrait un resultat incorrect. Attention
   particuliere a `bm25` (sens du tri) et a la syntaxe FTS5 (une saisie utilisateur brute est de la
   SYNTAXE, pas seulement une valeur : `l'ete`, `C++`, une parenthese seule).
4. **Index non utilise** alors qu'il existe.
5. **Concurrence.** Compteur de tentatives, ouverture unique de la base.

## Choix deliberes — NE PAS les signaler

- `note_links` n'est pas une entite Room : la table heritee n'a pas de cle primaire, et Room en
  exige une. Les lectures passeront par `@RawQuery(observedEntities = [NoteEntity::class])`.
- Les entites ne sont pas des `data class` (elles portent des `ByteArray`).
- `NoteLinkDao` n'existe pas encore — c'est prevu, pas un oubli.
- Le masquage des notes verrouillees est fait par les triggers, pas par les requetes.

## Regles de forme

- **Aucun renommage, aucune preference de style, aucune architecture alternative.**
- Pour chaque constat : scenario d'echec concret (quelles donnees, quelle sequence, quel resultat
  faux ou quelle perte).
- Distingue CONFIRME (tu montres le chemin) de PROBABLE (dis ce qui te manque).
- Cite fichier:ligne.
- **« Aucun defaut sur ce motif » est une reponse attendue.** Ne remplis pas le rapport.

## Points ou je veux un avis explicite

1. `bm25(notes_fts)` : je trie ASC en affirmant que le score croit vers le MOINS pertinent. Exact ?
2. Ma transformation de la saisie en expression FTS5 (`toMatchExpression`) : decoupage sur tout ce
   qui n'est ni lettre ni chiffre ni tiret bas, puis chaque terme entre guillemets suivi de `*`.
   Vois-tu une saisie qui produirait encore une erreur de syntaxe FTS5, ou un resultat surprenant ?
3. `FolderDao.delete` protege la boite de reception par `AND id != 'inbox'` dans le SQL lui-meme.
   Vois-tu un contournement ?
4. `incrementVaultAttempts` incremente en base (`vault_attempts = vault_attempts + 1`) pour eviter
   qu'un lire-modifier-ecrire concurrent n'offre des essais gratuits sur un coffre limite a 5
   tentatives. Est-ce suffisant, sachant que l'appelant relit ensuite la valeur par une seconde
   requete ?
