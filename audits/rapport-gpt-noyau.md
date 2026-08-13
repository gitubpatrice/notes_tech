## 1) Perte de données

### 1.1 Génération concurrente de deux KEK différentes (installation neuve) → base chiffrée avec une KEK, préférences contenant l’autre
**CONFIRMÉ** — `KekRepository.kt:45-57` + `KekRepository.kt:112-126` + `KeystoreSealedKekSource.kt:90-116`

**Scénario concret**
- État : première installation (pas de fichier DB), démarrage à froid.
- Séquence :
  1. Deux composants (ex. écran + worker, ou deux injections simultanées) appellent en parallèle `NotesDatabaseFactory.build()` → `kekRepository.acquire()`.
  2. Dans les deux threads : `firstAvailableKey()` rend `null` (aucune source n’a encore de clé) (`KekRepository.kt:46`).
  3. Dans les deux threads : `databaseExists()` rend `false` (`KekRepository.kt:50`), donc chacun part sur `generateAndPersist()` (`KekRepository.kt:56`).
  4. Chacun génère une KEK aléatoire différente et appelle `primary.store(kek)` (`KekRepository.kt:119-123`, puis `KeystoreSealedKekSource.kt:90-116`), ce qui **écrase** les préférences de l’autre (dernier commit gagnant).
  5. Chaque thread continue avec *sa* KEK en mémoire et peut tenter de créer/ouvir la base.

**Résultat faux / perte**
- Si la base est créée/chiffrée avec KEK₁ mais que les préférences finissent avec KEK₂, alors au redémarrage suivant l’app relira KEK₂ et la base KEK₁ devient **illisible définitivement** (notes créées pendant la session perdues à jamais).
- Même sans notes, c’est un “brick” durable : fichier DB présent mais aucune clé correspondante persistée.

> Rien dans `acquire()`/`generateAndPersist()` n’est sérialisé : c’est bien un chemin d’exécution possible tel quel.

---

### 1.2 Interférences concurrentes dans `KeystoreSealedKekSource.store` (alias identique) pouvant produire un couple (prefs, clé Keystore) incohérent
**PROBABLE** (dépend du comportement exact du provider Keystore lors de `generateKey()` concurrent sur le même alias) — `KeystoreSealedKekSource.kt:95-116` + `KeystoreSealedKekSource.kt:126-139`

**Scénario concret**
- État : première installation, deux threads entrent simultanément dans `store()`.
- Séquence possible :
  1. Thread A : `existingKey() == null` → `createKey()` génère une clé sous l’alias `notes_tech.db.kek.v1`.
  2. Thread B : idem, génère aussi (ou remplace) une clé sous **le même alias**.
  3. Interleaving : A chiffre la KEK avec la clé A mais B remplace l’alias par la clé B avant que A ne commit ses prefs (ou l’inverse).
  4. Les prefs finissent avec un ciphertext issu d’une clé, mais le Keystore contient l’autre.

**Résultat faux / perte**
- Au prochain `load()`, `existingKey()` rend une clé qui ne correspond pas au blob → `doFinal()` échoue → `SourceUnavailable` (`KeystoreSealedKekSource.kt:71-81`).
- Si une base a été créée avec la KEK “perdue” pendant cette course, elle devient **indéchiffrable**.

Ce point est **distinct** de 1.1 : même si une seule KEK était générée, la course sur l’alias Keystore + prefs peut suffire à casser l’ouverture.

---

## 2) Défaut d’ouverture

### 2.1 Ouverture OK côté clé, mais risque de validation Room si le DDL hérité a des `DEFAULT` non reflétés
**PROBABLE** (il me manque le DDL exact des tables `folders` et `notes` tel qu’écrit par Flutter / sqflite sur les bases en production) — impact à l’ouverture Room.

**Scénario concret**
- État : utilisateur migrant avec base existante.
- Séquence :
  1. SQLCipher s’ouvre correctement.
  2. Room fait `onValidateSchema` au premier accès (`NotesDatabaseFactory.kt`, forçage via `writableDatabase`).
  3. Si, dans la base réelle, une colonne a un `DEFAULT` (ou absence de default) différent de ce qu’attend Room (annotations `defaultValue = ...` et/ou absence de `defaultValue`), Room lève une exception “Expected… Found…” et **refuse d’ouvrir**.

Je ne peux pas confirmer une divergence *dans ton code* sans voir le SQL réel correspondant (ou un `PRAGMA table_info` / `sqlite_master` d’une base utilisateur). À la lecture, tes `defaultValue` “0/1/''” sont plausibles, mais Room est strict.

---

## 3) Fuite de secret

### 3.1 Nettoyage mémoire : KEK non effacée si une exception **non**-`KekFailure` sort de `store()`
**PROBABLE** — `KekRepository.kt:119-123` + `KeystoreSealedKekSource.kt:90-116`

**Scénario concret**
- État : première installation.
- Séquence :
  1. `generateAndPersist()` génère `kek`.
  2. `primary.store(kek)` lève une exception qui **n’est pas** une `KekFailure` (ex. `ProviderException` / `RuntimeException` venant du provider crypto ou de `commit()` en cas extrême).
  3. Le `catch (e: KekFailure)` de `generateAndPersist()` ne s’exécute pas (`KekRepository.kt:121-124`) → `kek.wipe()` n’est pas appelé.

**Résultat faux / fuite**
- La KEK reste en mémoire plus longtemps que prévu sur ce chemin de crash.
- Je ne vois pas de fuite directe dans logs (le message n’inclut pas la valeur), c’est essentiellement une question de durée de vie mémoire sur crash.

**Aucun défaut confirmé** de type “la clé apparaît dans un log / message d’exception” dans les fichiers fournis.

---

## 4) Concurrence (hors perte de données)

### 4.1 Chargement natif SQLCipher
**Aucun défaut sur ce motif** : `NotesDatabaseFactory.loadNativeLibraryOnce()` est bien `@Synchronized` + drapeau `@Volatile` (`NotesDatabaseFactory.kt`, bloc companion), ce qui évite les doubles chargements concurrents.

### 4.2 Lecture prefs en deux `getString` séparés
**PROBABLE** (plutôt “défaut d’ouverture transitoire”) — `KeystoreSealedKekSource.kt:58-69`

**Scénario**
- Un thread fait `load()` pendant qu’un autre fait `store()` (commit).
- Entre `getString(KEY_BLOB)` et `getString(KEY_NONCE)`, la valeur peut changer.

**Résultat**
- Couple (blob, nonce) incohérent → échec GCM → `SourceUnavailable` → refus d’ouverture alors qu’une clé valide existe. Ce n’est pas une perte de données, mais un échec d’ouverture sporadique sous concurrence.

---

## 5) Cohérence du schéma

**Aucun défaut confirmé** dans les entités elles-mêmes *par inspection statique* (types, FK, index, `orders` sur l’index composite, `BLOB` via `typeAffinity` sont cohérents).  
**PROBABLE** uniquement sur le point “valeurs par défaut exactement identiques à la base réelle” (cf. 2.1), faute de DDL/`PRAGMA table_info` réels.

---

# Avis explicite sur tes 5 points

## (1) `SqlCipherRawKey.encode` et reconnaissance `x'<64 hex>'` comme clé brute
Oui : en SQLCipher 4, le motif `x'…'` de longueur exacte (préfixe `x'`, hex, suffixe `'`) est reconnu comme **représentation hexadécimale d’une clé** et évite la dérivation PBKDF2 de “passphrase”.  
Ça vaut en pratique pour les voies d’entrée usuelles côté Android (`PRAGMA key` et `sqlite3_key` via la lib), car la détection se fait sur le matériau fourni.

Nuance : “sans dérivation” signifie “pas de PBKDF2 sur passphrase”. SQLCipher peut toujours dériver en interne les sous-clés (HMAC, etc.) à partir de cette clé, mais c’est le comportement attendu et compatible.

## (2) `clearPassphrase=true` casse le pool, et la copie doit survivre
Oui, c’est exactement le piège connu des wrappers SQLCipher Android :
- `clearPassphrase=true` met à zéro le tableau **détenu par la factory** après la première ouverture.
- Or SQLCipher/SQLite peut ouvrir des connexions supplémentaires plus tard (pool, multi-thread, invalidation tracker, etc.) et relire la passphrase depuis la config → les ouvertures suivantes échouent.

Donc :
- Ton `clearPassphrase = false` est cohérent avec “la passphrase doit vivre tant que la DB vit”.
- Le `rawKey.copyOf()` passé à la factory doit bien **survivre** et ne pas être effacé par l’appelant (ce que tu fais).

## (3) Discriminant “première installation vs migration” = existence du fichier DB
Globalement **oui, c’est suffisant** pour l’objectif de sûreté (“ne jamais générer si une base existe”). Je ne vois pas d’état normal où “fichier DB existe” et “générer une nouvelle KEK” serait correct sans détruire l’accès aux notes.

Les seuls cas limites que je vois sont hors-norme (corruption/artefact, fichier vide, bricolage utilisateur/root, restauration partielle OEM) : dans ces cas, refuser d’ouvrir est plus sûr que générer.

Le seul angle dangereux n’est pas logique mais **concurrent** (cf. 1.1) : deux acquéreurs peuvent décider “pas de DB” en même temps.

## (4) `callback.onOpen` fait `INSERT OR IGNORE` dans `folders` à chaque ouverture
Oui, c’est **sûr** vis-à-vis WAL/SQLite : `INSERT OR IGNORE` est atomique, idempotent, et n’a pas l’effet destructif de `REPLACE` (tu l’as explicitement évité).

Vis-à-vis Room :
- Ça peut provoquer une invalidation “folders” au démarrage (selon l’ordre exact d’initialisation de l’invalidation tracker), donc au pire un rafraîchissement/émission supplémentaire.
- Je ne vois pas de risque de corruption ni de deadlock structurel du seul fait d’être dans `onOpen`.

## (5) Exactitude des `defaultValue = "''"`, `"0"`, `"1"`
Sur SQLite, si le DDL est bien de la forme :
- `DEFAULT ''` → `PRAGMA table_info` renvoie typiquement `''` (avec les quotes) : ton `defaultValue = "''"` est cohérent.
- `DEFAULT 0` / `DEFAULT 1` → renvoie `0` / `1` : tes `"0"` et `"1"` sont cohérents.

La seule divergence typique possible serait si le DDL hérité utilise `DEFAULT ""` (double quotes), ou `DEFAULT false/true`, ou aucune valeur par défaut là où tu en déclares une (ou l’inverse). Sans le SQL exact cité “en commentaire” (non inclus ici) ou un `PRAGMA table_info` réel, je ne peux pas confirmer à 100%, mais **je ne vois pas d’incohérence intrinsèque** dans les littéraux que tu as choisis.