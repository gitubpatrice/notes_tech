# Notes Tech 3.2.0 — sauvegarde chiffrée, import Markdown, recherche dans la note, partage entrant

> Écrit le 2026-10-29, après la 3.1.1. Décision de Patrice du même jour : « ajoute 1, 2 et 3 », puis
> « fais-le, que ça marche, proprement, que tout soit bien fait ». Ce document fixe le périmètre et les
> décisions **avant** le code ; la partie sauvegarde est relue par un modèle externe avant toute ligne.

## 0. Pourquoi

- **Une désinstallation fait tout perdre.** La sauvegarde Android est coupée exprès
  (`allowBackup=false`, `data_extraction_rules.xml`) : la clé de la base est scellée par un Keystore non
  exportable, et la copier ne servirait à rien. L'export ZIP sort en clair et ne revient pas.
  Conséquences constatées : un utilisateur inquiet de perdre ses notes si F-Droid retirait l'appli
  (e-mail du 2026-10-26) ; passer d'une installation F-Droid à GitHub exige de désinstaller.
- **La recherche trouve la note, pas l'endroit** (idée de hcarrega, issue #10).
- **Faire entrer du texte** depuis une autre appli n'existe pas (aucun filtre `SEND`) — #36 en est la
  forme la plus simple.

## 1. Sauvegarde chiffrée et restauration

### D-029 — Un fichier `.ntbackup`, chiffré par une phrase choisie à la création

> Relu par GPT 5.6 « sol » (effort élevé) le 2026-10-29 sur la première version de ce plan :
> 16 constats, tous vérifiés ; ceux retenus sont intégrés ci-dessous et marqués **(R-n)**.

- **Contenu** : tous les dossiers et toutes les notes, **corbeille comprise** — une note à la corbeille
  est encore récupérable, l'omettre la perdrait au premier téléphone perdu (R-1). Elle revient dans la
  corbeille, avec sa date. Pas les réglages, pas le modèle de dictée, pas le verrou de l'appli.
- **Restaurer ramène ce que contient le fichier**, y compris ce qui a été supprimé depuis sa création :
  un fichier ne peut pas savoir ce qui sera supprimé après lui (R-2). L'écran de restauration le dit.
- **Un code de sauvegarde GÉNÉRÉ, par défaut** (Patrice, 2026-10-29 : « fais ce qu'il y a de mieux » ;
  R-14) : 24 caractères en 6 groupes de 4 (`K7QM-T2XB-…`), tirés par `SecureRandom` dans un alphabet de
  32 signes sans ambiguïté (ni `0`/`O`, ni `1`/`I`/`L`), soit 120 bits. Affiché **une fois**, à noter ;
  la création exige de le **recopier** avant d'écrire le fichier — un code mal noté, c'est une sauvegarde
  perdue. À la restauration, la saisie ignore la casse, les espaces et les tirets. Le fichier quitte le
  téléphone et s'attaque hors ligne, sans limite d'essais : seul un secret tiré au hasard y résiste à
  coup sûr.
- **Ou une phrase choisie**, en option : saisie deux fois, **normalisée en Unicode NFC** avant usage (un
  même « é » tapé sur deux claviers donne deux suites d'octets ; R-15), **12 caractères au moins**, et
  refusée si elle est évidente : un seul caractère répété, une suite (`123456789012`, `abcdefghijkl`), ou
  une phrase de la courte liste des plus courantes. L'écran dit pourquoi.
- **Argon2id v1.3, t = 3, m = 64 Mio, p = 1**, sortie 32 octets (les paramètres des coffres à phrase,
  `VaultParams`) ; **AES-256-GCM, étiquette de 128 bits**, par Bouncy Castle (déjà dans l'appli pour
  Argon2) en flux.
- **Écrit où l'utilisateur veut** par `ACTION_CREATE_DOCUMENT` — toujours un **nouveau** document, jamais
  par-dessus un ancien (R-12). Aucun fichier intermédiaire en clair.
- **Le succès n'est annoncé qu'après relecture** : le fichier écrit est rouvert, déchiffré en entier et
  son étiquette vérifiée. En cas d'échec, le document créé est supprimé si le fournisseur le permet.
- **Lecture cohérente** : dossiers et notes lus dans **une seule transaction** de lecture ; les clés des
  coffres à code ouverts sont **copiées** avant d'écrire (une copie propre à la sauvegarde, que le
  verrouillage automatique n'efface pas), puis effacées (R-7).
- **Aucune permission nouvelle**, aucun réseau.

### Format, version 1

```
octets   taille  champ
0        8       magique "NTBACKUP" (ASCII)
8        2       version du format = 1 (uint16 gros-boutiste)
10       1       KDF = 1 (Argon2id v1.3)
11       4       t, itérations (uint32 gros-boutiste)
15       4       m, mémoire en Kio (uint32 gros-boutiste)
19       1       p, parallélisme
20       16      sel (aléatoire, à chaque fichier)
36       12      nonce GCM (aléatoire, à chaque fichier)
48       …       AES-256-GCM(clé = Argon2id(phrase NFC en UTF-8, sel, t, m, p), nonce,
                 AAD = les 48 octets d'en-tête) du contenu ; étiquette de 16 octets à la fin
```

- **L'en-tête est authentifié** (AAD).
- **Avant de dériver** (rien n'est encore authentifié) : magique, version = 1, KDF = 1, et **exactement**
  t = 3, m = 65 536 Kio, p = 1 — les seuls paramètres qu'écrit la version 1. Un fichier forgé ne peut ni
  faire allouer plus de 64 Mio ni faire calculer plus longtemps qu'une vraie sauvegarde (R-10). D'autres
  paramètres viendront avec une autre version du format.
- **Taille** : 48 + 16 octets au moins ; **64 Mio au plus**, comptés sur ce qui est **réellement lu** (un
  flux qui s'arrête au 64 Mio + 1ᵉ octet), la taille annoncée par le fournisseur n'étant qu'un premier
  refus (R-9). 64 Mio de notes, c'est des dizaines de milliers de pages ; la création refuse au-delà et le
  dit.
- **Contenu** : JSON UTF-8 écrit et lu **en flux** (`android.util.JsonWriter` / `JsonReader`), jamais
  d'arbre en mémoire (R-8). Les dossiers d'abord, puis les notes :
  ```
  { "format": "notes-tech-backup", "version": 1, "createdAt": <ms>,
    "folders": [ { "id", "name", "parentId"|null, "color", "icon"|null, "createdAt", "updatedAt",
                   "vault": null
                          | { "mode": "passphrase", "salt", "wrappedKey", "iv", "verifier" }   (Base64)
                          | { "mode": "pin", "folderKey" }                                    (Base64) } ],
    "notes":   [ { "id", "folderId", "title", "content", "sealed"|null (Base64), "envV",
                   "tags": [..], "pinned", "favorite", "archived", "trashedAt"|null, "colorId"|null,
                   "createdAt", "updatedAt" } ] }
  ```
  `folderId` n'est jamais nul (`notes.folder_id` est `NOT NULL` ; R-13).

### D-030 — Les coffres voyagent sans être ouverts… sauf ceux à code

- **Coffre à phrase** : ses colonnes (`vault_salt`, `vault_kek_wrapped`, `vault_iv`, `vault_verifier`)
  et les blobs scellés de ses notes sont copiés **tels quels** : ils ne dépendent que de la phrase du
  coffre et s'ouvrent avec elle sur un autre téléphone. Pas besoin de l'ouvrir pour le sauvegarder ; la
  sauvegarde ne le déchiffre jamais.
- **Coffre à code (PIN)** : sa clé est scellée par le Keystore **de ce téléphone**. Il n'est sauvegardé
  **que s'il est ouvert** : la clé de dossier (32 octets) entre dans le contenu chiffré, ses notes y
  restent **scellées**. À la restauration, l'utilisateur **choisit un code** pour ce coffre (saisi deux
  fois), et la clé est réemballée comme à la création d'un coffre à code : nouveau sel, nouveau nonce,
  nouvelle clé Keystore, vérificateur recalculé. ⚠️ **À dire à l'écran** : tant que le fichier existe,
  ce coffre n'est protégé que par la phrase de la sauvegarde. Un coffre à code fermé est **omis**, et
  l'écran le nomme avant d'écrire.
- **Les identifiants sont conservés** : le scellement d'une note est lié à son id (AAD = `noteId`),
  l'emballage de la clé d'un coffre à phrase à celui du dossier (AAD = `folderId`).
- **Un coffre se reconnaît à sa clé, pas à son sel** (R-5) : deux coffres sont « le même » quand leur
  **vérificateur** (HMAC-SHA256 de la clé de dossier) est égal. Pour un coffre à code de la sauvegarde,
  le vérificateur se calcule depuis la clé transportée.

### D-031 — Restaurer ajoute, n'écrase jamais

- **Deux passes sur le fichier**, chacune déchiffrée et **vérifiée jusqu'à l'étiquette** :
  1. **Passe d'essai**, sans rien écrire : tout est validé et compté, et un résumé s'affiche (dossiers,
     notes, coffres, conflits, ce qui sera ignoré et pourquoi). Les coffres à code demandent leur code.
  2. **Passe d'écriture**, dans **une seule transaction Room**, **annulée** si l'étiquette ne passe pas à
     la fin. Aucune donnée non authentifiée n'est donc jamais validée en base.
- **Dossiers** : graphe vérifié **acyclique** (y compris un dossier parent de lui-même), profondeur
  bornée à 32, sur l'union des dossiers de la sauvegarde et du téléphone ; insérés parents d'abord ; un
  parent absent devient « sans parent » (R-6). Un dossier dont l'id existe déjà est **réutilisé** tel
  qu'il est sur le téléphone.
- **Chaque note est validée contre le dossier où elle va réellement** (R-4) — celui du téléphone si
  l'id existe déjà, sinon celui de la sauvegarde :
  - dossier ordinaire → la note doit être **en clair** ;
  - coffre → la note doit être **scellée**, et le coffre de destination avoir **la même clé** que celui
    de la sauvegarde (vérificateurs égaux) ;
  - sinon la note est ignorée, et comptée. Une sauvegarde forgée ne peut donc ni glisser une note en
    clair dans un coffre existant, ni y glisser un blob qu'il ne saurait pas ouvrir.
- **Note dont l'id existe déjà** (R-3) : identique → rien à faire ; **différente et en clair** → la copie
  de la sauvegarde est ajoutée **à côté**, sous un nouvel id (rien n'est écrasé, les deux versions
  restent) ; **différente et scellée** → ignorée et comptée (un nouvel id casserait son scellement), et
  le résumé le dit.
- **Keystore hors transaction** (R-11) : les clés des coffres à code restaurés sont créées **avant** la
  transaction ; si elle échoue, elles sont supprimées. Jamais une clé d'un coffre déjà présent.
- Les liens `[[…]]` des notes en clair sont réindexés dans la transaction (D-009) ; ceux des notes
  scellées restent détachés, comme à l'ouverture de la base.
- **Mauvaise phrase et fichier abîmé** se présentent pareil (l'étiquette ne passe pas).

### Effacement

- Le mode panique ne peut pas atteindre un fichier enregistré hors de l'appli : **le dire** à l'écran
  de création et dans la politique de confidentialité.
- Clé dérivée, phrase et clés de coffre : tableaux effacés (`fill(0)`) dès qu'ils ne servent plus. C'est
  **au mieux** (R-16) : les chaînes Kotlin (titres, contenus, champ de saisie) ne s'effacent pas ; on les
  garde le moins longtemps possible.

## 2. Import Markdown

### D-032 — Des fichiers `.md` / `.txt`, ou un ZIP, vers des dossiers ordinaires

- `ACTION_OPEN_DOCUMENT` multiple : `.md`, `.markdown`, `.txt`, `.zip` (dont l'export de Notes Tech).
- **Frontmatter** lu s'il est là (celui de l'export : `title`, `tags`, `created`, `updated`, `pinned`,
  `favorite`) ; sinon titre = nom du fichier sans extension.
- **Dossier** : dans un ZIP, le premier niveau de chemin désigne un dossier **ordinaire** de ce nom
  (réutilisé s'il existe, créé sinon). **Jamais un coffre** : un nom qui correspond à un coffre envoie
  la note dans la Boîte de réception (et le résumé le dit). Fichier seul : Boîte de réception.
- Le `README.md` à la racine d'un export Notes Tech est reconnu et ignoré.
- **Bornes** : 5 000 fichiers, 5 Mio par fichier, 100 Mio décompressés au total (archive piégée) ;
  UTF-8 strict, sinon fichier ignoré et compté.
- Toujours des **nouvelles** notes (nouveaux ids) : importer deux fois crée deux copies — c'est un
  import, pas une restauration.

## 3. Recherche dans la note

### D-033 — Une barre « Rechercher dans la note », et l'arrivée depuis un résultat

- Menu ⋮ de l'éditeur → barre de recherche : occurrences **surlignées**, « 3 / 12 », précédente /
  suivante ; en lecture (fiche) comme en écriture.
- **Même règle que la recherche de l'appli** : insensible à la casse et aux accents, chaque mot cherché
  séparément.
- **Depuis un résultat de recherche**, la note s'ouvre avec la barre remplie et la première occurrence
  sous les yeux.
- Rien n'est indexé ni écrit : la recherche porte sur le texte affiché (une note de coffre ouverte
  aussi — elle n'est déjà déchiffrée qu'en mémoire).

**Fait (2026-10-29)** — `domain/recherche/RechercheDansLeTexte.kt`, `ui/editor/RechercheDansLaNote.kt`.
Tranché en codant :
- « Texte affiché » au pied de la lettre : en **écriture**, le titre et le Markdown tel que tapé (l'adresse
  d'un lien comprise) ; en **lecture**, le titre et le Markdown **dessiné** (ni `**`, ni l'adresse d'un lien).
  Le compte peut donc différer d'un mode à l'autre, et c'est juste : il compte ce qu'on voit.
- Dans les mots aussi (« budget » trouve « budgets »), comme une recherche dans la page. Apostrophes
  typographiques repliées sur `'` : FTS5 traite l'apostrophe comme un séparateur, une note trouvée par
  « l'été » doit montrer « l’été », pas « 0 / 0 ».
- Au plus **1 000 occurrences** (« 1000+ ») ; calcul hors du fil principal, annulable, positions dans le
  texte d'origine, jamais appliquées à un autre texte que celui calculé.
- La barre ne déplace curseur ou liste que **sur demande** (nouvelle recherche, précédente, suivante,
  arrivée) : taper dans le corps barre ouverte ne ramène pas le curseur à chaque frappe.
- Depuis **deux** résultats de recherche : l'écran Recherche et la liste de l'accueil filtrée par son
  champ — c'est la même recherche plein texte (`search.observe`). Argument `recherche` de la route.
- Le Retour ferme la barre avant de quitter la note ; la barre se ferme si le coffre se referme dessous.
Tests : 8 + 3 JVM, 5 écran, 2 de bout en bout (appli réelle), chacun vu échouer sous sabotage.

## 4. Partage entrant

- « Partager → Notes Tech » depuis une autre appli : un **texte** (`text/plain`) crée une note dans la
  Boîte de réception (titre = sujet du partage, sinon première ligne) ; un ou plusieurs **fichiers**
  texte ou Markdown passent par l'import (§2).
- **Une activité dédiée**, exportée, sans interface : elle lit le partage, le borne (1 Mio de texte,
  5 Mio par fichier) et le confie en mémoire à l'appli, puis ouvre `MainActivity`, qui **continue de
  jeter tous les extras** (audit E1) : aucune donnée extérieure n'atteint la navigation.
- **Verrou de l'appli** : la note n'est créée qu'après déverrouillage.
- Jamais dans un coffre.

## 5. Tests et relectures

- JVM : format de sauvegarde (aller-retour, en-tête modifié, bornes, mauvaise phrase, troncature),
  fusion (conflits, coffre différent, restaurer deux fois), import (frontmatter, bornes, ZIP piégé),
  correspondance insensible aux accents.
- Instrumentés : aller-retour réel sur SQLCipher avec un coffre à phrase et un coffre à code
  (restauration avec nouveau code), partage entrant, barre de recherche.
- Chaque nouveau test vu échouer une fois, sabotage à l'appui.
- Relecture externe (GPT) du format et du code de la sauvegarde ; audit de sécurité avant la release.
