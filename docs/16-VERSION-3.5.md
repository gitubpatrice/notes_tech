# Notes Tech 3.5.0 — écran d'accueil au choix, listes à cocher

> Écrit le 2026-11-14, la 3.4.0 étant fusionnée mais **pas publiée** (release prévue le lundi 16 novembre).
> Origine : l'issue #59 (koad1x, « Discussion », réponse de Patrice du 11-11 : « for a later version ») et
> un courriel reçu par Patrice le 11-14 : *« Would it be possible to add a checklist feature to the notes
> app? »*. Patrice : « prépare le plan pour la 3.5.0 avec tout ce qui doit contenir ».
>
> **Ce document est la spécification**, écrit AVANT le code pour survivre à une compaction. Rien n'est
> codé. Les six questions ont été **tranchées le 2026-11-14** (Patrice : « pour les choix 1, 2, 3, 4, 5,
> 6, fais ce qu'il y a de mieux ») — section « Décisions », avec la raison de chacune. Q1 n'a **pas**
> suivi la première recommandation (une icône dans la barre) : le code montre qu'elle écraserait le titre.

## Contenu

| Pièce | Origine | Statut |
|---|---|---|
| **D-046** Écran d'accueil : Toutes les notes / Boîte de réception | issue #59 | décidé par Patrice (11-11) |
| **D-047** Cocher une case en lecture ; tâche faite grisée | courriel du 11-14 | décidé (Q3) |
| **D-048** Insérer une liste à cocher, depuis le menu ⋮ | courriel du 11-14 | décidé (Q1) |
| **D-049** Entrée continue une liste, toutes les listes | courriel du 11-14 | décidé (Q2) |
| **C-1 à C-3** Petits correctifs (limites connues de la 3.4.0) | `15-VERSION-3.4.md` § Limites | retenus (Q4) |
| **D-050** Remerciement en bas de « À propos », mains jointes en gris | Patrice, 2026-11-17 | décidé |

**Aucune migration de schéma** (Room reste en version 11), aucune permission, aucun réseau, aucun
changement des formats d'export, de sauvegarde ni de note scellée : tout ce qui suit est du texte de note
ou un réglage. C'est voulu (voir « Écarté »).

## Faits relevés avant de concevoir (exploration du 2026-11-14)

**Les listes à cocher existent déjà à moitié.** `- [ ] Lait` / `- [x] Pain` sont lus par l'aperçu
(`MarkdownPreviewReader.kt:376-377`) et dessinés en cases nommées « Fait » / « À faire » pour un lecteur
d'écran (`ApercuMarkdown.kt:300-316`). Ces cases **ne réagissent pas** au toucher, et c'est écrit comme
une règle : « Shown, never toggled: the preview does not write into the note » (`PreviewDocument.kt:76`).
Ni la fiche store ni le README ne le disent : la personne qui demande la fonction ne peut pas le savoir.

**Positions.** Le texte donné à l'analyseur n'est **pas** celui de la note : `parse` passe d'abord par
`lineFeedsOnly` (`\r\n` et `\r` deviennent `\n`, `MarkdownPreviewReader.kt:141-142`), puis par
`WikiLinkMask` (chaque `[[Titre]]`, et chaque `U+E000`/`U+E001`, devient `U+E000` + un numéro + `U+E001`,
de longueur différente, `WikiLinkMask.kt:21-27`). Une position lue dans l'arbre de l'analyseur ne
désigne donc pas le même caractère dans la note dès qu'un lien `[[…]]` ou une fin de ligne Windows
précède la case. `ListItem` ne porte aujourd'hui aucune position (`PreviewDocument.kt:74`).

**Relecture.** `rememberLectureDeLApercu` est indexée sur le texte : un texte nouveau repart d'une lecture
« en cours », jamais des blocs précédents (`LectureDeLApercu.kt:100-123`). Et le commentaire le dit :
relire montre d'abord un élément « lecture » et la liste perd sa position. Cocher une case en passant par
ce chemin ferait clignoter tout l'aperçu et le ramènerait en haut **à chaque case**.

**Texte affiché et enregistrement.** L'aperçu lit `state.content.text`, le texte à l'écran (en avance de
500 ms sur l'enregistrement, `NoteEditorScreen.kt:595-599`). `onContentChange` ne programme
l'enregistrement que si le texte a changé (`NoteEditorViewModel.kt:808-825`) ; `insererAuCurseur` passe
par la fonction pure `InsertionDeTexte` (`NoteEditorViewModel.kt:827-838`, `InsertionDeTexte.kt`).
L'enregistrement chiffre une note de coffre, réécrit `updated_at`, l'index plein texte (trigger
`notes_au`) et les liens : passer par ce chemin les garde tous.

**Champ du corps.** C'est un `EditText` Android natif depuis la 3.2.0, synchronisé dans les deux sens
avec le `TextFieldValue` de l'état (`ChampDuCorps.kt:85-99`) : la frappe du clavier arrive sous forme de
`TextFieldValue` dans `onContentChange`.

**Barre d'écriture.** Flèche de retour, puis le **titre de la barre** (nom du dossier et état de
l'enregistrement, `NoteEditorScreen.kt:681-692`), puis ✓ (vert), micro, lien et ⋮
(`NoteEditorScreen.kt:693-781`). Le lien y est une icône parce que c'est « le geste d'écriture le plus
fréquent de cet écran » (`NoteEditorScreen.kt:770-772`). ⚠️ **La barre est pleine** : mesuré sur le S9,
cinq éléments d'action écrasaient le titre « à zéro pixel » — le nom du dossier et l'état de
l'enregistrement disparaissaient (`NoteEditorScreen.kt:717-722`). Le menu ⋮ est présent en écriture
comme en lecture (`NoteEditorScreen.kt:780-781`).

**Accueil.** Le dossier affiché est lu dans le `SavedStateHandle` (`HomeViewModel.kt:143`), donc `null`
(« Toutes les notes ») à chaque démarrage à froid. `onFolderSelected` écrit la clé, **`null` compris**
(`HomeViewModel.kt:218-223`). Une note créée depuis l'accueil va dans le dossier affiché, sinon dans la
Boîte de réception (`HomeViewModel.kt:295`).

**Réglages.** `AppSettings` (clés Dart nues dans `FlutterSharedPreferences`, `AppSettings.kt:202-213`) ;
modèle à recopier : `keyboardNoLearningNow` / `setKeyboardNoLearning` (`AppSettings.kt:125-129`). La
panique efface tout sauf trois clés (`PanicService.kt:691`) : un nouveau réglage y revient à son défaut
sans code de plus.

**Accessibilité d'une commande à état.** Piège déjà vécu : un `Switch` séparé de son libellé était
annoncé « interrupteur, activé » sans dire de quoi. L'idiome du projet est **un seul nœud**
`Modifier.toggleable(value, role, onValueChange)` qui porte libellé, état et rôle (`04-PIEGES.md`,
lignes 1891-1922).

**Corbeille.** `TrashScreen.kt` n'ouvre pas les notes : l'éditeur n'est pas atteignable sur une note
supprimée. *À confirmer en codant D-047.*

## D-046 — Écran d'accueil : Toutes les notes / Boîte de réception (issue #59)

Décidé par Patrice le 2026-11-11 (mémoire `portage-kotlin-etat`).

- Réglage énuméré `home_start_folder` : **« Toutes les notes »** (défaut, rien ne change pour les autres)
  ou **« Boîte de réception »**. Écran Réglages, section Affichage, deux boutons radio (idiome de la ligne
  cliquable entière, `onClick = null` sur le bouton).
- Lu **au démarrage à froid seulement** : quand le `SavedStateHandle` ne contient pas la clé
  `home.folderId`. Un état restauré l'emporte, y compris un « Toutes les notes » choisi exprès (la clé
  existe alors, valant `null`). *À prouver par un test* : une clé valant `null` survit à la mort du
  processus et se distingue d'une clé absente (`SavedStateHandle.contains`).
- La panique le remet au défaut, comme les autres réglages (aucune ligne à ajouter à
  `PREFERENCES_CONSERVEES`). Test.
- Rien d'autre ne bouge : le tiroir, une note déplacée qui sort de la boîte (`observeInFolder`), la
  création dans le dossier affiché, les raccourcis (D-041), le dépôt (D-042).
- **Écarté** (déjà dit le 11-11) : « rouvrir le dernier dossier vu » — pourrait rouvrir sur un coffre,
  moins prévisible.
- Textes : titre du réglage + deux choix, 5 langues.

## D-047 — Cocher une case dans la lecture

Le cœur de la demande : une liste de courses s'ouvre en lecture, on coche, on referme.

- **Toucher la case** bascule `[ ]` ↔ `[x]` (un `[X]` devient `[ ]`). La case seule, pas toute la ligne :
  le texte d'un élément peut porter des liens. Cible tactile de 48 dp (aujourd'hui une icône de 20 dp
  dans une boîte de 24).
- **Trois caractères remplacés par trois caractères.** Aucune autre position de la note ne bouge : les
  occurrences de « Rechercher dans la note », les liens, la position de défilement restent justes.
- **Position dans la NOTE, pas dans le texte analysé.** Chaque `ListItem` à case porte la position de son
  `[` dans le texte d'origine. `WikiLinkMask` et `lineFeedsOnly` fournissent la correspondance (position
  et longueur de chaque remplacement, `\r` retirés). Fonction pure, testée sur la JVM :
  - lien `[[…]]` avant la case, sur la même ligne et plus haut ;
  - note aux fins de ligne `\r\n`, et `\r` seuls ;
  - caractères `U+E000`/`U+E001` présents dans la note ;
  - `> - [ ]` (case dans une citation) ;
  - liste imbriquée ;
  - `- [ ]` dans un bloc de code : ce n'est pas une case, rien à toucher.
- **Garde à l'écriture.** La bascule ne s'applique que si la lecture a été faite sur le texte actuel
  (`state.content.text`), et si les trois caractères à cette position sont bien `[ ]`, `[x]` ou `[X]`.
  Sinon **rien n'est écrit** et la lecture est refaite. On n'écrit jamais ailleurs que sur ces trois
  caractères.
- **Même chemin que la frappe** : une méthode du ViewModel (`basculerCase(position)`) qui construit le
  nouveau `TextFieldValue` et passe par `onContentChange` (enregistrement différé de 500 ms, chiffrement
  d'une note de coffre, `updated_at`, index, liens).
- **La lecture est corrigée sur place, pas refaite.** La structure ne change pas : seule la marque de
  cet élément s'inverse. La nouvelle `Lue` reprend les éléments et ne change que celui-là. Pas de
  clignotement, pas de retour en haut. Test : après une bascule, la lecture corrigée est égale à une
  lecture complète du nouveau texte (sur un jeu de notes variées).
- **Pas de case cochable quand** :
  - la note est affichée telle quelle (trop longue ou trop complexe), ou l'élément est trop lourd
    (`asWritten`) : il n'y a pas de case ;
  - c'est une page légale : même lecteur, sans rappel de bascule, donc des icônes comme aujourd'hui ;
  - le coffre est fermé : pas de lecture ;
  - la note est en chargement, en erreur ou sous une panique en cours : même règle que la frappe, à
    vérifier en codant.
- **Accessibilité** : un seul nœud par case, `toggleable` avec `Role.Checkbox`, état « Fait » /
  « À faire » (chaînes `note_preview_task_done` / `note_preview_task_open` déjà traduites), libellé =
  texte de l'élément. Test de sémantique (un seul nœud basculable, il porte le libellé, l'état suit).
- **Effets assumés** :
  - cocher est une modification : `updated_at` change, et la note remonte dans le tri « Modifiées
    récemment » ;
  - une note « après lecture » (D-043) peut être cochée, et s'efface quand même à la fermeture ;
  - pas d'annulation : un second toucher remet la case.
- **Tâche faite grisée, pas barrée** (Q3). La case dit déjà l'état ; griser laisse voir d'un coup d'œil
  ce qui reste à faire dans une longue liste, et barrer rend le texte plus difficile à lire.
  - Le texte propre à l'élément (ses paragraphes) prend `onSurfaceVariant`. Un sous-élément garde sa
    propre case et son propre aspect. Les liens restent des liens.
  - ⚠️ **Contraste à mesurer** : au moins 4,5:1 sur chaque couleur de note (3.1.0), en thème clair et
    sombre. Si une couleur passe sous ce seuil, la teinte est choisie couleur par couleur (comme
    `SemanticColors`). Le contraste n'est jamais sacrifié : à défaut, le texte reste inchangé.
  - Rien ne change pour un lecteur d'écran : l'état est annoncé par la case.

## D-048 — Insérer une liste à cocher

Sur un clavier de téléphone, `[` et `]` sont sur la page des symboles : écrire `- [ ] ` coûte trois
changements de page par ligne.

- **Une entrée « Liste à cocher » dans le menu ⋮, en écriture seulement** (Q1). Pas une icône dans la
  barre : une cinquième action y écraserait le titre, déjà mesuré sur le S9 (voir « Faits »). Le menu
  suffit parce que, avec D-049, on ne s'en sert qu'**une fois par liste** : Entrée crée les cases
  suivantes. Le lien, lui, sert à chaque lien.
  - ~~Placée en tête du menu en écriture~~ → **juste après « Rechercher dans la note »** (fait le
    2026-11-17) : la recherche est en tête depuis la 3.2.0, et ce sont les deux seules entrées qui portent
    sur le texte ; les autres concernent la note entière.
  - La position du curseur vit dans l'état (`state.content.selection`), comme pour « Insérer un lien »,
    qui ouvre une feuille avant d'insérer. Après l'insertion, le corps reprend le focus et le clavier
    (`focaliserLeCorps`) : le menu les a pris. Test instrumenté.
- Fonction pure `ListeACocher.basculer(valeur): TextFieldValue`, testée sur la JVM, sur **la ligne du
  curseur** (pas au milieu d'un mot) :
  - ligne vide → `- [ ] ` ;
  - ligne de texte → `- [ ] ` devant ;
  - `- texte`, `* texte` ou `+ texte` → `- [ ] texte`, `* [ ] texte`, `+ [ ] texte` : **la puce est
    gardée** (écart avec la première rédaction, qui la changeait en `-`) — en CommonMark, une puce d'un
    autre caractère commence une nouvelle liste, et la liste de l'utilisateur aurait été coupée en deux ;
  - `- [ ] texte` ou `- [x] texte` → `texte` (second appui = retire la case) ;
  - indentation et `> ` d'une citation gardés ;
  - sélection sur plusieurs lignes → chaque ligne non vide ;
  - le curseur suit son texte (décalé de ce qui a été ajouté ou retiré), sélection bornée comme dans
    `InsertionDeTexte`.
- Une liste numérotée **garde son numéro** : `1. texte` → `1. [ ] texte` (valide en GFM). Le numéro est
  une information de l'utilisateur, on ne le remplace pas par `- `.
- Enregistrement par le même chemin que `insererAuCurseur`.
- Texte : libellé de l'entrée du menu, 5 langues.

## D-049 — Entrée continue une liste

- Entrée sur une ligne de liste ouvre la ligne suivante avec le même marqueur :
  - `- [ ] a` et `- [x] a` → `- [ ] ` (un élément neuf n'est jamais coché) ;
  - `- a`, `* a`, `+ a` → même marqueur ;
  - `1. a` → `2. `, `1) a` → `2) ` ;
  - indentation et `> ` gardés.
- **Entrée sur un élément vide** (`- [ ] ` seul) retire le marqueur et laisse une ligne vide : on sort de
  la liste. C'est le comportement d'Obsidian, de Joplin et de Google Keep.
  - **Écart mesuré sur le S9 (2026-11-17)** : le curseur passe à la ligne SUIVANT cette ligne vide. En
    CommonMark, une ligne tapée juste sous un élément lui appartient (« continuation paresseuse ») :
    `- [ ] pain` puis `fin` se lisait comme UN élément, « pain fin », grisé avec sa case. Pas de seconde
    ligne vide si la précédente l'est déjà, ni en tête de note ; sous une liste imbriquée, la suite
    revient dans l'élément parent, comme son indentation le dit.
- Entrée au milieu du texte d'un élément : le reste part sur la ligne suivante, après le marqueur.
- **Marqueurs CommonMark seulement** (marqueur + espace), cohérent avec D-045 : `1.Eat` n'est pas une
  liste, donc pas de suite.
- **Détection** : dans `onContentChange`, comparer l'ancienne et la nouvelle valeur. Il faut exactement
  UN `\n` inséré à l'ancienne position du curseur, et la sélection vide juste après. Ne déclenchent
  rien : un collage de plusieurs lignes, la dictée (`insererAuCurseur`), un remplacement du clavier.
  Fonction pure `SuiteDeListe.apres(avant, apres): TextFieldValue?`, testée sur la JVM.
- ⚠️ **Risque principal : le clavier.** Réécrire la valeur pendant que le clavier compose peut faire
  sauter le curseur ou dupliquer un mot (synchronisation de `ChampNatifDuCorps`). À mesurer avant de
  conclure, sur le clavier Samsung (S9 Android 10, S24 One UI 8), Gboard (émulateur) et SwiftKey si
  possible. Si le chemin `TextFieldValue` ne tient pas, passer par le champ natif lui-même
  (`TextWatcher`). Dans les deux cas, on ne réécrit qu'après le `\n`, jamais dans une composition en
  cours.
- **Toutes les listes** (Q2) : une suite pour les seules cases serait une règle qu'on ne devine pas. Et
  c'est ce que font Obsidian, Joplin et Google Keep.
- Pas de réglage pour la couper au départ : Entrée sur un élément vide en sort. À revoir si quelqu'un le
  demande.

## D-050 — Remerciement en bas de « À propos »

Patrice, 2026-11-17 : « ajouter aussi dans à propos un encart en bas avec l'icone mains jointe en gris de
remerciements comme pour sms tech ».

- **Modèle : SMS Tech** (`c7d6d36a`, `AboutScreen.kt:359-434`, testé par `TeinteMonochromeTest`), repris
  tel quel :
  - un cadre de 1 dp en `outlineVariant`, coins de 16 dp, contenu centré ;
  - l'émoji 🙏 (U+1F64F, 20 sp ; la bibliothèque d'icônes Material n'a pas de mains jointes) ramené à UNE
    teinte `onSurfaceVariant` par une matrice de luminance (`teinteMonochrome`) : gris, avec son relief ;
  - décoratif pour un lecteur d'écran (`clearAndSetSemantics {}`) : seul le texte est lu ;
  - la phrase en `bodySmall`, `onSurfaceVariant`, centrée.
- **Place** : tout en bas de l'écran, sous la carte « Mentions légales » (`AboutScreen.kt:155-170`) ; Notes
  n'a pas de ligne « Créée par ».
- **Texte `about_thanks_body`**, sans nom de contributeur, comme SMS Tech : personne n'a consenti à figurer
  dans l'appli, et une liste nominative ne serait jamais à jour. Les cinq phrases de SMS Tech, le nom
  changé. Le registre est le même (vouvoiement en fr, « Sie », « Lei », « usted » : relevé le 11-17).
- **Code en double avec SMS Tech** (`teinteMonochrome`) : assumé, les deux applis n'ont pas de bibliothèque
  commune.
- Test JVM de la matrice (repris de `TeinteMonochromeTest`) ; l'écran À propos dans `AProposTest` si la
  ligne y est atteignable.

## Petits correctifs — les limites connues de la 3.4.0 (`15-VERSION-3.4.md:234-240`)

Retenus (Q4). Chacun est petit, et il ferme un écart déjà consigné.

- **C-1 Partage reçu : lecture bornée dans le temps.** Le partage reçu (3.2.0) lit encore sans délai qui
  ferme le flux. Reprendre la garde du lecteur de note scellée de la 3.4.0 : une lecture à la fois, fil
  dédié, flux **fermé** passé le délai, toute `RuntimeException` du fournisseur rattrapée. Durcissement
  de sécurité : une appli tierce ne doit pas pouvoir bloquer la lecture.
- **C-2 Écran d'export : dire que les notes à durée de vie en sont exclues.** Le code les exclut
  (D-043), l'écran ne le dit pas. C'est un défaut « texte contre code ». Une phrase, 5 langues.
- **C-3 Collage de plus de 500 000 caractères : un message.** Aujourd'hui il est ignoré sans rien dire,
  donc le geste paraît sans effet. Une phrase, 5 langues. *Fait* : c'était le dépôt rapide (D-042,
  `DepotViewModel`), où le TITRE refusait aussi en silence au-delà de 200 caractères — le même message
  pour les deux, les deux limites dites.
- *C-1 fait* : le mécanisme du lecteur des notes scellées (fil à lui, une lecture à la fois, flux fermés au
  délai) est sorti dans `data/fournisseurs/LecteurDeFournisseur`, que les deux lecteurs utilisent. Trouvé
  en route : un flux qui répond 0 octet sans fin faisait tourner la boucle du partage sur un cœur
  (`read` rendant 0, le compte n'avançait plus) — refusé désormais, comme le lecteur scellé le faisait.
- Laissés : index partiel pour la purge (aucun coût mesuré), « Mes fichiers » Samsung qui n'ouvre pas
  `.ntnote` (décision de Samsung ; le menu « Ouvrir une note scellée » marche partout), brouillon du
  dépôt gardé en mémoire après Home (voulu).
- **Essai resté à faire depuis la 3.4.0** : « Ouvrir avec » d'un `.ntnote` depuis une vraie messagerie
  (`15-VERSION-3.4.md:283-285`). À faire pendant les essais de la 3.5.0.

## Écarté

- **Un type de note « checklist » séparé** (à la Google Keep, éléments stockés à part) : il faudrait un
  nouveau format, une migration, et tout doubler (chiffrement des coffres, export, import, sauvegarde,
  note scellée, recherche, panique, effacement sûr). Le Markdown `- [ ]` fait la même chose, reste
  lisible par Obsidian et Logseq, et ne touche à aucun de ces chemins.
- **Faire descendre les cases cochées en bas de la liste** : l'appli réécrirait l'ordre des lignes
  de l'utilisateur sans le dire.
- **Compteur « 3/7 » sur la carte** : il faudrait analyser le Markdown de chaque note de la liste, et une
  note de coffre fermé n'a pas de texte lisible.
- **Cocher depuis la liste ou un widget** : pas de widget, et ce serait un second chemin d'écriture.
- Rappels des refus précédents : « rouvrir le dernier dossier vu » (#59) ; titres de coffre dans la
  liste et historique des versions (#10, refus motivés publiquement).

## Décisions (2026-11-14 — Patrice : « fais ce qu'il y a de mieux »)

| # | Question | Retenu | Pourquoi |
|---|---|---|---|
| Q1 | Où mettre « Liste à cocher » ? | **menu ⋮, en écriture** | la barre est pleine : une 5e action écrase le titre (S9, `NoteEditorScreen.kt:717-722`) ; avec D-049, le geste sert une fois par liste |
| Q2 | Entrée continue : quelles listes ? | **toutes** | une règle pour les seules cases ne se devine pas ; usage des autres éditeurs |
| Q3 | Aspect d'une tâche faite | **grisée**, contraste ≥ 4,5:1 vérifié | se repère d'un coup d'œil ; barrer gêne la lecture |
| Q4 | Petits correctifs C-1 à C-3 | **oui** | petits, déjà consignés ; C-1 est un durcissement de sécurité, C-2 un texte qui ne dit pas ce que fait le code |
| Q5 | Export ZIP de la sélection, filtre par couleur | **non** (gardées pour plus tard) | une version courte, et donc relue à fond ; personne ne les a demandées |
| Q6 | Attendre que F-Droid serve la 3.4.0 avant de publier la 3.5.0 | **oui** | même règle que pour la 3.4.0 : jamais deux versions en attente chez F-Droid |

## Ordre de travail

Branche `feat/3.5.0`, à partir de `main` **après** la publication de la 3.4.0.

1. **D-046** (le plus petit, indépendant) : réglage, lecture au démarrage à froid, tests, textes.
2. **D-048 puis D-049** : fonctions pures et leurs tests JVM d'abord, puis le câblage. Ensuite la mesure
   sur les claviers, avant d'aller plus loin.
3. **D-047** (le plus gros) : positions dans la note, garde, bascule par le ViewModel, lecture corrigée
   sur place, sémantique.
4. C-1 à C-3, puis D-050 (indépendants).
5. Version, textes, documents, audits, essais, PR.

## Tests

- **JVM** :
  - correspondance des positions (D-047, tous les cas listés) ;
  - lecture corrigée = lecture complète ;
  - `ListeACocher`, `SuiteDeListe` (chaque règle et son contraire : collage multiligne, dictée, `1.Eat`,
    élément vide, citation, indentation) ;
  - choix du dossier au démarrage (clé absente / présente à `null` / présente à un id).
- **Instrumentés** (S9, puis CI API 29) :
  - toucher une case → texte de la note changé, enregistré, rouvert identique ;
  - note de coffre ouverte → enregistrée chiffrée ;
  - sémantique de la case ; contraste de la tâche grisée sur chaque couleur de note, thèmes clair et sombre ;
  - entrée « Liste à cocher » du menu (insertion, puis focus et clavier rendus au corps) ;
  - Entrée dans le champ natif ;
  - réglage d'accueil après redémarrage à froid et après la panique ;
  - `ReglagesTest` mis à jour (nouvelle ligne).
- **Contrôles négatifs** : chaque garde défaite fait échouer son test. Au minimum :
  - la garde de position (écrire sans vérifier les trois caractères) ;
  - la correspondance (positions de l'analyseur prises telles quelles, avec un `[[lien]]` avant la
    case).
- **À la main** : claviers Samsung (S9, S24 sur accord du moment), Gboard (émulateur sans fenêtre) ;
  TalkBack sur une liste à cocher ; liste de 1 000 éléments (temps d'une bascule sur le S9).
- Mise à jour 3.4.0 publiée → 3.5.0 signée, sur le S9.

## Textes et surfaces publiques

- `strings.xml` × 5 (en, fr, de, it, es) :
  - réglage d'accueil : titre et deux choix ;
  - libellé de l'entrée « Liste à cocher » du menu ;
  - remerciement d'« À propos » (D-050) ;
  - C-2 et C-3.
- Version **3.5.0, base 508** (5080-5083) ; journaux fastlane **5081-5083** × 5 langues, ≤ 500
  caractères (agent `android-i18n-strings-cleaner`).
- `full_description.txt` × 5 : « Markdown : titres, listes, liens » devient « titres, listes, **listes à
  cocher**, liens ». Même chose dans le README (§ Markdown editing), qui ne dit pas aujourd'hui que les
  cases existent.
- SECURITY et confidentialité : **rien à changer** (aucune permission, aucun réseau, aucune donnée
  nouvelle). À relire quand même si C-1 change ce qu'ils disent du partage reçu.
- Site files-tech (après la release) :
  - entrée en tête de `includes/apps.php` ;
  - journaux 5081 dans `content/<fr|en|de>/releases/notes-tech/3.5.0.txt` ;
  - page Notes Tech FR/EN/DE : une carte « Listes à cocher », la carte de l'accueil si elle décrit
    l'écran d'ouverture ;
  - méta-description et `og:description`.
- Réponses, **après** la release : au courriel (Patrice) ; sur #59 (rouverte par koad1x le 11-17), puis la
  fermer comme terminée.

## Publication

- Condition (Q6) : F-Droid sert la 3.4.0 (5071-5073), lu dans l'API `packages/com.filestech.notes_tech`.
- CI de `main` verte ; tag léger `v3.5.0` sur le commit de `main` ; build
  `assembleRelease -Pnotestech.replaceInstalledApp=true` ; signature `signer_notes_tech.py 3.5.0 <sortie>
  <universel 3.4.0 retéléchargé>` ; release (notes EN puis FR), SHA retéléchargés ; F-Droid par le tag
  seul, aucune MR.
- Audits avant la PR : 3 axes, cohérence, i18n, relecture externe du delta.

## État

> **Réalisé le 2026-11-17** (Patrice : « fais tout ce qui est nécessaire pour que tout soit parfait »),
> tests verts sur un émulateur API 29 identique à celui de la CI (AVD `ci-api29`, pixel_2, 4 Go). Les
> contrôles négatifs cités ont été faits : la garde défaite, le test tombe.

- [x] Questions Q1-Q6 tranchées (2026-11-14)
- [x] **D-046** écran d'accueil (`77edbbc`) — `dossierAuDemarrage` (pur) : un état sauvegardé l'emporte,
  même à `null` (« Toutes les notes » choisi exprès), et le choix est réécrit dans l'état. Le nom de la
  Boîte de réception est celui du tiroir (renommée ou non). Tests : `DossierAuDemarrageTest` (JVM),
  `AccueilAuDemarrageTest` (vrai ViewModel, vraie base ; contrôle négatif : réglage débranché → échec),
  `ReglagesHeritesTest`, `ReglagesTest`.
- [x] **D-048** et **D-049** (`9409ada`) — `ListesDeMarkdown.kt` : UNE lecture du début de ligne
  (`DebutsDeLigne`) pour les deux. Les gestes d'écriture regroupés en `GestesDEcriture` (seuil detekt de
  l'écran). Tests : `ListesDeMarkdownTest` (22, JVM), `EditeurTest`. **Reste à faire à la main** : la
  suite de liste avec le clavier Samsung (S9, S24) et SwiftKey — sur émulateur, clavier AOSP, la suite
  passe par le même `onContentChange` que les tests.
- [x] **D-047** case cochable (`a569221`) — positions ramenées au texte de la note
  (`WikiLinkMask.sourceOffset`, `collapsedReturns`), écriture gardée (`TaskBoxes.toggle`), lecture
  corrigée sur place (`lectureApresUneCase`, égale à une lecture complète sur 13 cases), un nœud
  `toggleable` par case (48 dp). Tâche faite en `onSurfaceVariant`, dont `CouleursDeNoteTest` mesure déjà le
  contraste (≥ 4,5:1) sur chaque couleur de note : aucune mesure de plus à faire. Contrôle négatif : sans
  la correspondance, 3 tests de positions et la comparaison CRLF tombent. Tests : `TaskBoxesTest`,
  `LectureApresUneCaseTest`, `MarkdownPreviewReaderTest` (JVM), `ApercuMarkdownTest`, `CasesDeLaNoteTest`
  (vrai ViewModel, coffre réel : enregistrée chiffrée).
- [x] **C-1** · [x] **C-2** · [x] **C-3** (`8c1f61d`) — tests `LecteurDeFournisseurTest`, `DepotViewModelTest` ;
  `FournisseurDUnPartageTest`, `NotesScelleesRecuesTest`, `FichierDeNoteScelleeTest` verts.
- [x] **D-050** remerciement d'« À propos » (`e3eb010`) — `TeinteMonochromeTest`, `EcranAProposEtLegalTest`.
- [x] Textes 5 langues · [x] version 3.5.0 (508) · [x] journaux 5081-5083 (398 à 479 caractères) ·
  [x] README (+ la ligne de version restée à v3.3.0 depuis la 3.4.0), fastlane, SECURITY
- [x] **Audits et relecture externe** (2026-11-17), tous appliqués :
  - **Relecture externe GPT-5.6 sol** (0,35 $) : 5 défauts réels.
    - **Lecteur de fournisseur** (`b85092c`) : une annulation de l'appelant laissait le verrou pris pour de
      bon ; un flux ouvert après le délai n'était jamais fermé. Défaut déjà présent dans le lecteur scellé
      de la 3.4.0.
    - **Suite de liste** (`f937324`) : elle supprimait les espaces après le curseur ; elle continuait une
      « liste » dans du code ; un `\r` seul ne terminait pas la ligne.
  - **Audit 3 axes** : ni critique ni élevé.
    - Recoupe GPT sur le lecteur, et ajoute un fil coincé qui bloquait tous les partages suivants : il est
      désormais remplacé, 4 bloqués au plus.
    - Un jeton empêche une lecture abandonnée de libérer la suivante.
    - Un élément trop lourd n'a plus de case touchable (`0fec451`).
    - Le test du `Bundle` (« Toutes les notes » à `null` après une mort du processus) a été ajouté.
  - **Cohérence** (92/100) : nom TalkBack des cases jamais vide, choix « case et pas ligne » et 48 dp
    écrits, ordre des paramètres, gestes tous en verbes, langue des commentaires (`08595fe`).
  - **Traductions** : aucun défaut ; « durata limitata » et « duración limitada » plus clairs.
  - Relecture Claude non lancée (Patrice : « pas besoin si c'est pas nécessaire »), GPT et l'audit
    3 axes se recoupant.
- [x] **D-050, icône** (Patrice, 2026-11-17 : « fond blanc avec un contour gris ») : le gris foncé de SMS
  Tech remplacé par des mains blanches au contour gris moyen (`d3eabe5`), vérifié sur captures clair et
  sombre. Patrice : « c mieux oui ». Le 2026-11-18 (Patrice : « ajouter une séparation entre les 2
  mains ») : la jointure des mains, effacée avec les autres traits intérieurs, redessinée par un trait de
  1 dp au milieu, du gris du contour, limité à la forme de l'émoji ; vérifié sur le S9 (émoji Samsung).
  Patrice : « oui très bien ».
- [x] CI de la PR #70 verte sur `2b72cf4` (604 cas instrumentés), PR fusionnée (`1540ed7`), CI de `main`
  et CodeQL verts
- [x] **À la main sur le S9** (clavier Samsung, Android 10, 2026-11-17) : suite de liste à puces et
  numérotée, fin de liste, entrée « Liste à cocher » dans les deux sens, case cochée en lecture (grisée,
  enregistrée, sans saut). **Trois défauts trouvés et corrigés** :
  - le clavier ne revenait pas après ⋮ → « Liste à cocher » (demandé pendant que le menu tenait le focus
    de la fenêtre : refusé, jamais redemandé), la frappe suivante se perdait. Le champ le redemande au
    retour du focus. Test `the_keyboard_comes_back_after_the_checklist_entry`, menu touché par l'entrée
    SYSTÈME (`input tap`) : avec `performClick` de Compose, il échouait même corrigé. Contrôle négatif sur
    l'émulateur de la CI : ancien champ → le test tombe ;
  - une ligne tapée juste après la fin d'une liste rejoignait son dernier élément (voir D-049, écart) ;
  - une tâche numérotée perdait son numéro en lecture, alors que « Liste à cocher » le garde dans le
    texte : numéro puis case, comme GitHub (`a_numbered_task_keeps_its_number_before_its_box`).
- [x] **À la main sur le S24** (One UI 8, Android 16, 2026-11-17), par Patrice : « tous les tests sont
  ok ». Un défaut vu : l'appui sur une case dessinait un carré gris de 48 dp, la case dans son coin haut,
  qui descendait sous le texte (« il dépasse largement la case »). Désormais un rond de 40 dp centré sur la
  case, celui d'une case à cocher Android, la cible de 48 dp inchangée (`9d82693`). Patrice : « c'est
  parfait ».
- [ ] Publication (après F-Droid 3.4.0, Q6)

**CI réparée en route** (hors 3.5.0, PR #67 puis #69, fusionnées) : l'émulateur API 29 de la CI démarrait
parfois zygote avant que `on boot` (`init.ranchu.rc`) pose `dalvik.vm.heapsize` → tas de 16 Mo → Argon2
en OutOfMemoryError. `tools/prepare-ci-emulator.sh` redémarre le framework par `su` une fois le démarrage
fini (pas `adb root`, qui faisait mourir l'émulateur), AVANT le PIN.
