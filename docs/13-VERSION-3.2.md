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
  32 signes sans ambiguïté, soit 120 bits. ⚠️ **Tranché en codant** : retirer `0`, `O`, `1`, `I` et `L`
  ne laisse que 31 signes ; c'est donc la **base 32 de Crockford** (`0-9`, `A-Z` sans `I L O U`), dont la
  lecture replie les sosies (`O`→`0`, `I`/`L`→`1`) — l'ambiguïté disparaît à la saisie, pas à l'affichage.
  Une seule règle pour créer et restaurer (`SecretDeSauvegarde.octets`) : ce qui a la forme d'un code est
  pris sous sa forme canonique, le reste comme une phrase en NFC. Affiché **une fois**, à noter ;
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
- 🔴 **Écrite AVANT d'ouvrir le sélecteur** (corrigé le 2026-10-29, mesuré sur le S9) : le sélecteur
  envoie l'appli en arrière-plan, où **tous les coffres se referment** (`NotesTechApplication`, la
  protection principale — on n'y touche pas). Écrite au retour, la sauvegarde laissait de côté le coffre à
  code que l'aperçu venait d'annoncer ouvert : un fichier de 294 octets, « 0 note ». Désormais la
  sauvegarde est chiffrée et relue dans `cache/sauvegarde/` pendant que les coffres sont tels que
  l'aperçu les a vus, puis **recopiée** dans le document choisi et relue contre elle (même SHA-256).
  Pendant le sélecteur, seul ce fichier **chiffré** attend : ni le secret ni une clé de coffre ne sont
  gardés. Il est effacé dans tous les cas (Retour, échec, écran quitté), et sinon par la purge du
  démarrage et par le mode panique (`ClairDuCache`, même définition que les exports). Si l'appli est
  tuée pendant le sélecteur, le document vide qu'il a créé est supprimé et l'écran dit l'échec.
- **Le succès n'est annoncé qu'après relecture** : le fichier écrit est rouvert, déchiffré en entier et
  son étiquette vérifiée. En cas d'échec, le document créé est supprimé si le fournisseur le permet. Ce
  qui a été laissé de côté (coffre à code fermé) est redit dans le message final.
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
  dit. **Limite assumée** (audit 3 axes, P3) : passé 64 Mio, aucune sauvegarde n'est possible — ni découpe ni
  sauvegarde partielle ; à revoir si un utilisateur y arrive (l'import retient au plus 30 millions de
  caractères par passe, mais plusieurs imports s'additionnent).
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
- **Mauvaise phrase et fichier abîmé** se présentent pareil (l'étiquette ne passe pas) : le code est
  **redemandé sur place**, le fichier gardé, comme au déverrouillage d'un coffre.
- **Examen avant tout secret**, dès le fichier choisi : taille annoncée par le fournisseur au-delà de
  64 Mio, en-tête qui n'est pas celui d'une sauvegarde (ou plus court qu'un en-tête), version future —
  refusés avec leur raison, sans faire taper de code pour rien.

### Écrans (2026-10-29)

- Chaque dialogue porte le titre de la ligne qui l'a ouvert : « Créer une sauvegarde » de l'aperçu au
  message final, « Restaurer une sauvegarde » du code au bilan — attente et échec compris. Boutons
  constants : Annuler / action, OK pour finir. Bilan au futur avant, au passé après.
- Aucun sélecteur de documents sur l'appareil : `AppResultLauncher.launch` rend `false` au lieu de
  laisser `ActivityNotFoundException` faire planter l'appli, et chacun des quatre sélecteurs le dit
  (sauvegarde ×2 dans leur dialogue, import et modèle vocal par un bandeau).

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
- **Bornes** : 5 000 fichiers, **500 000 caractères par note** (5 Mio par fichier jusqu'au 2026-10-30, voir
  D-034), 100 Mio décompressés au total (archive piégée) ;
  UTF-8 strict, sinon fichier ignoré et compté.
- Toujours des **nouvelles** notes (nouveaux ids) : importer deux fois crée deux copies — c'est un
  import, pas une restauration.

**Fait (2026-10-29)** — `domain/importation/LectureDUnImport.kt`, `data/importation/NoteImporter.kt`,
`NotesRepository.importerDansDesDossiers`, Réglages → « Export et import ». Tranché en codant :
- 🔴 **Les 100 Mio comptent tout octet lu, sauté compris** : un ZIP lu en flux décompresse le reste d'une
  entrée pour atteindre la suivante, donc une entrée de zéros sautée sans compter tenait l'import des
  heures. Une borne atteinte arrête l'import, qui garde ce qu'il a lu et le dit.
- Le **bruit** n'est ni lu comme note ni compté : `__MACOSX/`, `.DS_Store`, tout nom commençant par un
  point (`.obsidian/`). Le reste qui n'est pas une note est compté dans le bilan.
- Le **README** reconnu à son titre (`NoteMarkdown.README_HEADING`, partagé avec l'export), à la racine.
- Le dossier vient du **chemin**, pas du `folder:` du frontmatter : `inbox/` désigne la Boîte de réception
  quelle que soit la langue de l'export, là où le frontmatter porte son nom traduit.
- Un dossier ordinaire de même nom l'emporte sur un coffre ; un nom qui n'est que celui d'un coffre envoie
  la note dans la Boîte de réception, compté et dit. Dossiers créés et notes écrites dans UNE transaction.
- Sélecteur : `text/*`, ZIP et `application/octet-stream` (plusieurs fournisseurs typent ainsi un `.md`) ;
  ce qui est lu se décide au nom et au contenu.
Tests : 7 JVM (export réel relu, bombe, borne de fichiers, archive abîmée…), 3 sur SQLCipher réel ;
sabotages tous vus rouges. Essayé sur émulateur par le vrai sélecteur.

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
- **Une activité dédiée**, exportée, sans interface : elle lit le partage, le borne (500 000 caractères
  par note, texte ou fichier — D-034 ; 1 Mio de texte et 5 Mio par fichier jusqu'au 2026-10-30) et le
  confie en mémoire à l'appli, puis ouvre `MainActivity`, qui **continue de
  jeter tous les extras** (audit E1) : aucune donnée extérieure n'atteint la navigation.
- **Verrou de l'appli** : la note n'est créée qu'après déverrouillage.
- Jamais dans un coffre.

## 5. Notes longues

### D-034 — Le corps s'écrit dans le champ d'Android, la lecture va par morceaux, 500 000 caractères à l'entrée

Constaté le 2026-10-29 en vérifiant qu'une note de 5 Mio (la borne de l'import) tenait : la base la tient
(la `CursorWindow` de SQLCipher 4.16 part de 16 Kio et grandit), mais **l'appli la montrait figée** et
l'**éditeur était inutilisable bien avant**. Mesuré sur le S9 (Android 10), build release :

| Note | Lecture | Écriture (champ Compose) : temps par image, médiane |
|---|---|---|
| 20 000 car. | immédiate | 9 ms |
| 50 000 | immédiate | 150 ms |
| 100 000 | immédiate | 950 ms |
| 200 000 | immédiate | 3,35 s — clavier après 9 s |
| > 500 000 | **figée** (un seul `Text`, tas plein) | — |

Cause, lue dans le bytecode de Compose ui-text 1.12.1 : `TextAndroidCanvas.getClipBounds` répond une
hauteur infinie, donc **Compose dessine toutes les lignes d'un texte** à chaque nouvel enregistrement ; un
champ en écriture est redessiné à chaque frappe et à chaque clignotement du curseur. Le champ
`TextFieldState` de Compose ne fait pas mieux (1,2 s à 100 000, mesuré).

**Décidé (Patrice, 2026-10-30 : « dans la 3.2.0 », « que tout soit fluide »)** :
- **Le corps en écriture est l'`EditText` d'Android** (`ui/editor/ChampDuCorps.kt`) : il ne remet en page
  que les paragraphes touchés et ne dessine que les lignes visibles — 23 ms par image à 2 000 comme à
  200 000 caractères sur le S9, l'ancien champ faisant 30 ms à 2 000. L'état reste le `TextFieldValue` du
  ViewModel (liens, dictée, recherche, enregistrement inchangés) ; le champ lui renvoie texte et sélection
  et applique ses changements par le plus petit remplacement. Ce qu'un `EditText` ouvre et que le champ
  Compose n'ouvrait pas est fermé : barre de sélection restreinte (ni Partager, ni Saisie automatique, ni
  les actions `PROCESS_TEXT` d'autres applis), classifieur `NO_OP`, ni saisie automatique ni capture de
  contenu, pas de correcteur orthographique, rien dans l'état sauvegardé, pas de clavier plein écran,
  pas de glisser hors d'une note de coffre, dépôt et collage en texte brut ; Copier et Couper passent par
  le presse-papiers de `SaisieDeCoffre` et une coupe n'efface qu'une fois copiée.
- **Le clavier ne lit qu'une fenêtre de 5 000 caractères autour du curseur** : le clavier Samsung redemande
  le texte extrait après CHAQUE frappe, et un `TextView` le donne entier — 900 Ko à 450 000 caractères,
  refusé par le Binder, 2 s d'attente à chaque frappe (mesuré sur le S9). Après correction : 450 000
  caractères se tapent comme une note courte.
- **Le clavier part avec le champ** (la coche, la flèche, un lien ouvert) : un `EditText` retiré de la
  fenêtre le laissait ouvert sur la liste (mesuré).
- **La lecture d'une note non analysée (> 500 000 caractères) va par morceaux** de 2 000 caractères, un
  élément paresseux chacun (`morceauxTelQuel`), au lieu d'un seul texte.
- **Import, fichier partagé, texte partagé : 500 000 caractères au plus** (`LongueurDUneNote`), la borne de
  l'aperçu — toute note importée est montrée dessinée. Patrice avait d'abord choisi 50 000 sur les mesures
  de l'ancien éditeur, puis proposé de monter « si c'est fluide » : mesuré fluide à 450 000 sur le S9. Une
  note tapée ou collée dans l'appli n'a pas de borne.

**Défilement en écriture (2026-10-30, essais de Patrice sur S24 et S9 : « ça rame, ça bloque même »)** :
deux défauts, chaque image pourtant dessinée en moins de 12 ms.
- Un `EditText` défile de la seule distance du doigt, sans élan : ajouté (`VelocityTracker` + `Scroller`), arrêté
  par un toucher ou une frappe.
- **La colonne de l'éditeur volait le geste** : journalisé sur le S24, le champ recevait ACTION_CANCEL 40 à
  90 px après chaque appui — le texte s'arrêtait net, l'élan ne partait jamais. Le champ garde désormais le geste
  dès que son texte peut défiler (`requestDisallowInterceptTouchEvent`). Mesuré, 8 gestes sur `note-450k` : S24
  24 → 862 images, 5 ms (médiane), 10 ms au pire, clavier ouvert ou non ; S9 540 images, 21 ms au pire — l'à-coup
  d'environ 1 s vu sur le S9 clavier ouvert, d'abord pris pour une limite du clavier Samsung, venait de ce geste
  coupé et a disparu. Un test instrumenté qui donnait les événements au champ lui-même ne pouvait pas le voir :
  `EditeurTest.a_long_body_scrolls_under_the_finger_all_the_way` passe par l'entrée de Compose (vu rouge sans le
  correctif : 135 px au lieu de plus de 904).

## 6. Tests et relectures

- JVM : format de sauvegarde (aller-retour, en-tête modifié, bornes, mauvaise phrase, troncature),
  fusion (conflits, coffre différent, restaurer deux fois), import (frontmatter, bornes, ZIP piégé),
  correspondance insensible aux accents.
- Instrumentés : aller-retour réel sur SQLCipher avec un coffre à phrase et un coffre à code
  (restauration avec nouveau code), partage entrant, barre de recherche.
- Chaque nouveau test vu échouer une fois, sabotage à l'appui.
- Relecture externe (GPT) du format et du code de la sauvegarde ; audit de sécurité avant la release.
