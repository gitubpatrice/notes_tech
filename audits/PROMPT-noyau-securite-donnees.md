# Relecture — noyau sécurité et accès aux données

## Contexte

Application Android Kotlin native. C'est le **portage** d'une application Flutter publiée
(Notes Tech 2.0.3), qui doit ouvrir **la base de données déjà présente chez les utilisateurs**.

La base : SQLite chiffrée par SQLCipher 4, dans `app_flutter/notes_tech.db`. Elle contient les
notes personnelles des utilisateurs. Il n'y a **aucune sauvegarde** : `allowBackup=false`, et la
clé est scellée dans l'`AndroidKeyStore` de l'appareil, donc non exportable.

**Conséquence : toute faute qui rend la clé introuvable ou qui écrit une clé neuve détruit
définitivement toutes les notes de l'utilisateur.** C'est le risque n°1 et l'objet principal de
cette relecture.

Le code ci-joint est neuf, non encore compilé, non encore exécuté.

## Ce que je te demande de chercher, par ordre de gravité

1. **Perte de données.** Un chemin où une clé neuve serait générée alors qu'une base existe. Un
   chemin où une exception transitoire (Keystore momentanément indisponible, appareil verrouillé,
   démarrage à froid) serait interprétée comme « aucune clé n'existe ». Un chemin où une base
   serait effacée, écrasée ou re-chiffrée.
2. **Défaut d'ouverture.** Un cas où la base légitime d'un utilisateur ne s'ouvrirait pas :
   mauvais format de clé, mauvais chemin, pragma appliqué trop tard, durée de vie du tableau de
   mot de passe trop courte pour le pool de connexions SQLCipher.
3. **Fuite de secret.** Une valeur secrète qui atteindrait un journal, un message d'exception, une
   trace de plantage. Un tableau d'octets sensible non effacé sur un chemin d'erreur.
4. **Concurrence.** Chargement de bibliothèque native, initialisation paresseuse, accès concurrent
   aux préférences.
5. **Cohérence du schéma.** Les entités Room doivent décrire **exactement** la base héritée, sinon
   la validation de schéma échoue à l'ouverture chez l'utilisateur.

## Choix délibérés — NE PAS les signaler

Ils sont documentés et assumés. Les signaler serait un faux positif :

- `note_links` n'est **pas** une entité Room. La table héritée n'a pas de clé primaire, or Room en
  exige une, et déclarer une clé absente ferait échouer la validation chez tous les utilisateurs.
- La base reste dans `app_flutter/` et n'est **pas** déplacée vers `databases/`.
- `@Database(version = 9)` sans migration : Room adopte une base sans `room_master_table` par son
  chemin « base pré-empaquetée » (`onValidateSchema` puis écriture de l'empreinte).
- Absence totale de `fallbackToDestructiveMigration*` : volontaire.
- Les entités ne sont **pas** des `data class` : elles portent des `ByteArray`, dont l'`equals`
  généré comparerait les références.
- `PRAGMA foreign_keys` n'est pas posé : Room l'active lui-même.
- `PRAGMA mmap_size` absent : retiré volontairement (blocage au démarrage à froid constaté).
- Les `String` intermédiaires portant la clé ne sont pas effaçables en Java — limite connue,
  identique à la version Flutter.
- La couche ② d'acquisition de la KEK (lecture directe du format `flutter_secure_storage`)
  n'existe pas encore. Son absence est prévue, pas un oubli.
- Le paquet Kotlin contient un tiret bas (`notes_tech`) : imposé par la compatibilité des données.

## Règles de forme

- **Ne propose aucun renommage, aucune reformulation, aucune préférence de style.**
- **Ne propose pas d'architecture alternative.** Juge le code tel qu'il est.
- Pour chaque constat, donne un **scénario d'échec concret** : quelles entrées, quel état de
  l'appareil, quelle séquence, et quel résultat faux ou quelle perte.
- Distingue **CONFIRMÉ** (tu peux montrer le chemin d'exécution) de **PROBABLE** (tu soupçonnes
  mais un élément te manque — dis lequel).
- Cite `fichier:ligne`.
- **« Aucun défaut sur ce motif » est une réponse attendue et utile.** Ne remplis pas le rapport.

## Points où je veux un avis explicite, même si tu ne vois pas de défaut

1. `SqlCipherRawKey.encode` produit les 67 octets ASCII de `x'<64 hex>'`. J'affirme que SQLCipher
   reconnaît ce motif dans le matériel de clé et l'utilise comme **clé brute**, sans dérivation,
   qu'il arrive par `PRAGMA key` ou par `sqlite3_key`. Est-ce exact ?
2. `SupportOpenHelperFactory(rawKey.copyOf(), hook, false)` — le troisième argument est
   `clearPassphrase`. J'affirme qu'à `true` la bibliothèque met le tableau à zéro après la
   première ouverture, ce qui casse la connexion suivante du pool. Est-ce exact, et la copie
   fournie doit-elle bien survivre à l'appel ?
3. `KekRepository.acquire` : le seul discriminant entre « première installation » et « utilisateur
   qui migre » est l'existence du fichier de base. Est-ce suffisant ? Vois-tu un état de l'appareil
   où le fichier existe mais où générer une clé serait néanmoins correct, ou l'inverse ?
4. `NotesDatabase.callback.onOpen` écrit dans la table `folders` à **chaque** ouverture
   (`INSERT OR IGNORE`). Est-ce sûr sous WAL, vis-à-vis du suivi d'invalidation de Room et de ses
   transactions ?
5. Les valeurs par défaut déclarées sur les entités (`defaultValue = "''"` pour `tags`, `"0"`,
   `"1"`) doivent correspondre exactement à ce que `PRAGMA table_info` renvoie pour une table créée
   par le SQL cité en commentaire. Vois-tu une divergence ?
