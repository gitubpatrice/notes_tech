# Parité fonctionnelle 2.0.3 → 3.0.0

> Se remplit **au fil des phases 5-7**, pas à la fin. Une case cochée veut dire « vérifié sur
> appareil », pas « le code existe ».
>
> La bascule (phase 8) est bloquée tant qu'une ligne reste vide sans justification écrite.
>
> ## 🔴 Ce que la phase 8 a appris dès sa première ligne — 2026-08-17
>
> La ligne `home_screen.dart` a été cochée la première, et elle a coûté **trois** défauts et un
> garde-fou manquant : un bouton flottant sans nom accessible (§71), un bouton ⋮ nommé d'après une de
> ses entrées de menu (§73), une suite de tests qui **détruisait le modèle de 57 Mo** de l'utilisateur
> et ignorait en silence le seul test prouvant que la dictée transcrit (§72), et un générateur de
> chaînes qui laissait passer une apostrophe nue — donc une chaîne française tronquée par aapt.
>
> ⚠️ **Aucun des quatre n'était visible à la relecture.** Ce qui les a trouvés : un relevé
> `uiautomator` mécanique de l'écran, un test qui cherchait autre chose, et un décompte des tests
> **ignorés** au lieu de la lecture du « OK (N tests) ».
>
> ⚠️⚠️ **La méthode qui a payé, à reprendre pour les lignes suivantes** : ne pas demander « est-ce
> que l'écran marche ? » mais *« que reçoit un lecteur d'écran ? »*, *« quels états ne sait-on pas
> atteindre à la main ? »*, et *« combien de tests ont été ignorés ? »*. La première question ne
> trouve rien ; les trois autres ont tout trouvé.
>
> ## 🔴 La DEUXIÈME ligne en a coûté trois de plus — 2026-08-17
>
> `trash_screen.dart` a rendu : une carte de corbeille **cliquable pour rien**, une carte de note qui
> **n'annonçait pas du tout** qu'on peut l'ouvrir (§74), et une corbeille qui annonçait « vide »
> avant d'avoir lu la base (§75). Quatre cases cochées.
>
> ⚠️⚠️ **Le deuxième défaut a été trouvé par le TÉMOIN du premier, pas par le premier.** Le test
> « cette carte n'est pas actionnable » se réduit à une assertion négative, donc vacante par
> construction ; son témoin pose la même carte avec un vrai clic et exige l'inverse. Le témoin a
> échoué. *Écrire l'assertion négative sans son positif connu aurait fermé la ligne sur un défaut
> plus grave que celui qu'elle corrigeait.*
>
> ⚠️ **Et le balayage de §71 ne pouvait pas le voir** : il cherche une action sans nom, celui-ci était
> un nom sans action. Le motif inverse demande son propre contrôle — c'est le seul enseignement de
> cette ligne qui vaille pour les huit écrans suivants.
>
> ### ⚠️⚠️ Les deux relectures externes ont trouvé un CINQUIÈME défaut — dans mes tests
>
> Sept constats, aucun recoupement sur les trois qui comptaient. Le plus grave : mon test « le bouton
> de vidange reste caché pendant le chargement » était **vacant**, posé sur une liste vide alors que
> ce bouton dépend *aussi* de `notes.isNotEmpty()` — il passait avec la garde **et sans**. Deux
> assertions négatives vacantes dans la même journée, l'une trouvée par mon propre témoin, l'autre par
> une relecture.
>
> 🔧 *Devant toute assertion négative : quel état la rendrait fausse si le code était cassé ?* S'il
> n'est pas dans le test, le test ne mesure rien.
>
> Sont aussi entrés : les **étiquettes** de la carte, qui n'étaient annoncées à aucun lecteur d'écran —
> et dont le correctif a dû reproduire la garde `!verrouillee`, sinon il ouvrait la fuite que la carte
> ferme ; un `Role.Button` ; et le dialogue de suppression définitive, qui **disparaissait à la
> rotation**. Détail et constats écartés en `04-PIEGES.md` §74.
>
> ## ✅ La TROISIÈME ligne a été rapide, parce que son défaut était déjà trouvé — 2026-08-17
>
> `search_screen.dart` : le balayage du motif §75 sur les quatre `stateIn` avait **déjà localisé** sa
> régression avant qu'on ouvre la ligne. Une case de plus, **30 restantes**.
>
> ⚠️ *C'est le premier défaut du portage trouvé par un balayage de motif plutôt que par l'examen d'un
> écran.* La leçon n'est pas « la recherche était cassée » mais : **un défaut nommé se cherche ensuite
> partout où son motif existe**, et ça coûte quelques minutes contre une ligne de parité entière.
>
> ## 🔴 La QUATRIÈME ligne — les réglages, et un interrupteur muet — 2026-08-17
>
> `settings_screen.dart`, le plus gros des écrans. Le balayage d'accessibilité a rendu **un** rectangle
> de 156 × 96 px : l'interrupteur de fenêtre protégée, `Switch` posé en `trailingContent` d'un
> `ListItem`, donc un nœud **séparé** de celui qui porte le texte — annoncé « interrupteur, activé »
> sans dire de quoi. Le publié emploie un `SwitchListTile`, qui n'a pas ce défaut. §77. **29 restantes.**
>
> ⚠️⚠️ **Le correctif était déjà écrit dans le même fichier**, appliqué à ses boutons radio, commentaire
> compris. *Un idiome correct appliqué à un composant et pas à son voisin est plus difficile à voir
> qu'une absence d'idiome* — le fichier avait l'air cohérent.
>
> ⚠️ **Quatre de mes six premiers échecs ne visaient pas le code** : `clickable(enabled = false)`
> **conserve** son action `OnClick` (donc `assertIsNotEnabled`, jamais `assertDoesNotExist`),
> `LocalSecureWindow` a levé son garde-fou volontaire, et `home_sort_mode` sert deux fois sur cet écran.
> Sur un écran de réglages, **un libellé réutilisé est la règle**.
>
> ## 🔴 La CINQUIÈME ligne — l'éditeur, et deux champs anonymes — 2026-08-17
>
> `note_editor_screen.dart`, 1 123 lignes côté publié : l'écran où l'utilisateur passe son temps, et
> le seul qui **écrit** des notes. **27 restantes** — l'écran et son panneau de liens.
>
> ⚠️⚠️ **Les deux zones de saisie n'avaient AUCUN nom accessible dès qu'elles portaient du texte.**
> Elles n'avaient qu'un `placeholder`, et un placeholder disparaît de l'arbre de sémantique en même
> temps que de l'écran — mesuré. Sur une note **vide**, il est là et nomme le champ : c'est l'état
> sous lequel un éditeur se relit, se capture et se démontre, et c'est l'état où le défaut n'existe
> pas. Le publié porte `labelText` sur les deux. §80.
>
> 🔴 **Et aucun des deux balayages ne pouvait le voir** : `actionnablesSansNom` **exclut** délibérément
> les nœuds portant un `EditableText` — au motif, juste, qu'un champ vide n'est pas un défaut
> d'étiquetage — et `actionsPerduesALaFusion` ne regarde que les actionnables. *Une exclusion
> raisonnable dans un instrument est un angle mort dans tous les écrans qu'il a validés.* D'où le
> **troisième** instrument, `champsDeSaisieSansNom`, son témoin à trois cibles, et un contrôle positif
> sur le vrai code : les deux `label` retirés le temps d'une mesure rendent bien **deux** rectangles.
>
> 🔴 **Second défaut, du côté de la perte de données** : le titre n'était pas plafonné à la saisie
> alors que le publié le plafonne à 200 caractères. Au-delà, `saveEdits` refuse **le titre et le corps
> ensemble** — donc plus rien ne s'enregistre, et quitter l'écran emporte le texte en silence. §81.
>
> ⚠️ La règle du plafond a demandé **trois** versions, et deux ont été arrêtées par l'appareil : la
> troisième refuse la saisie plutôt que de rogner un titre hérité trop long. Sa moitié « refus » n'est
> **pas mesurable à l'écran** — mesuré : dans ce harnais, aucun geste ne produit un candidat plus long
> que le texte en place — d'où une table de cas JVM. *Un geste de test peut être vacant comme une
> assertion peut l'être, et ça se mesure de la même façon.*
>
> ### 🔴🔴 Et un fichier de test qui n'a jamais tourné, sous un gate vert — §82
>
> `PlafondDuTitreTest` importait JUnit 4 dans un dépôt qui tourne en **JUnit 5** : ignoré sans erreur,
> sans avertissement, sans ligne de rapport. `BUILD SUCCESSFUL`. Ce qui l'a dit est le **compte** —
> 183 tests avant, 183 après, sept ajoutés — et l'absence du XML de la classe. Jumeau exact de §72 :
> *une ligne verte ne dit rien de ce qui n'a pas tourné.* Motif balayé sur tout le dépôt : aucun autre
> cas, 20 classes pour 20 rapports.
>
> ### 🔧 Un défaut LOCALISÉ, non corrigé, pour la ligne `link_autocomplete_sheet.dart`
>
> Comme la recherche l'avait été à sa ligne : `suggestionsDeLien` vide sa liste **à chaque frappe** et
> ne la remplit qu'après 120 ms de calme — c'est voulu, et c'est même un correctif documenté. Mais
> pendant cette fenêtre, `proposerLaCreation` vaut **vrai** par construction, et la garde que le
> portage a ajoutée exprès — *« si le titre tapé existe déjà, on le lie au lieu d'en créer un second du
> même nom »* — consulte une liste **vide**. Valider au clavier dans les 120 ms crée donc le doublon
> que cette garde existe pour empêcher.
>
> ⚠️ **Ce n'est pas une régression** : le publié affiche « Créer … » dans la même fenêtre (`snap.data ??
> const []`) et sa `_onSubmit` crée **toujours**. C'est la divergence délibérée du portage qui est
> **incomplètement efficace**. Le mécanisme du correctif est déjà écrit et éprouvé — §76, faire porter
> à la réponse la question à laquelle elle répond. À traiter à sa propre ligne, avec ses tests.
>
> ## ✅ Balayage de cohérence sur tout `ui/` — 2026-08-17
>
> Lancé sur le motif des cinq défauts d'accessibilité de la journée. **Un** constat : le bouton micro de
> l'éditeur portait `voice_setup_title`, le titre d'un **autre écran**, alors que
> `note_editor_tooltip_dictate` existait et n'était lue nulle part — et que le publié l'emploie
> précisément là. Corrigé. ⚠️ **Aucun défaut audible** : les deux valeurs coïncident dans les deux
> langues, c'est un défaut **latent**. §79.
>
> Les sept autres répertoires (`folders`, `vault`, `voice`, `panic`, `about`, `splash`, `common`) sont
> revenus **sains** sur ce motif — un « rien trouvé » explicite, vérifié répertoire par répertoire.
>
> 🔧 **Et le second balayage promis depuis §74 existe enfin** : `actionsPerduesALaFusion()`, le motif
> **inverse** — un nom sans action. ⚠️⚠️ Il a demandé **trois** versions, et **le témoin a arrêté les
> deux premières**, muettes sur tout : *un filtre qui ne signale rien est indiscernable d'un code sans
> défaut.* Troisième fois de la journée. Vérifié par **contrôle positif sur le vrai code** — le défaut
> §74 remis en place le temps d'une mesure, puis restauré par `git checkout --`. §78.
>

---

## Écrans (10)

> ⚠️ **La colonne « Kotlin » a été remplie le 2026-08-16, fichier par fichier.** Elle nomme
> l'homologue, elle **ne coche rien** : « le fichier existe » et « vérifié sur appareil » restent
> deux questions différentes, et c'est la seconde que ce tableau pose. Deux lignes n'ont
> délibérément **aucun** homologue — elles le disent, plutôt que de rester vides.

| Écran Flutter | Lignes | Kotlin | Vérifié | Notes |
|---|---:|---|:---:|---|
| `splash_screen.dart` | 259 | `ui/splash/SplashScreen.kt` | ☐ | Signature Files Tech ; masque l'acquisition de la KEK |
| `home_screen.dart` | 564 | `ui/home/HomeScreen.kt` + `HomeRoute.kt` | ✅ | `AccueilTest` (**13** cas, S9, 2026-08-17) : bannière `vault_lost_drafts` présente **et** absente, quatre états exclusifs du corps, tri, recherche, ouverture de note, sorties de la barre, et **aucun actionnable sans nom**. 🔴 A trouvé **deux** défauts — §71 et §73 |
| `note_editor_screen.dart` | 1 123 | `ui/editor/NoteEditorScreen.kt` + `NoteEditorRoute` | ✅ | `EditeurTest` (**22** cas, S9, 2026-08-17) + `PlafondDuTitreTest` (**11** cas JVM) : les **trois** balayages d'accessibilité, les quatre issues de chargement, l'échec d'enregistrement et sa raison nommée, les deux sorties, le micro désactivé pendant une dictée, les six entrées de menu et l'inverse qu'elles remontent, le panneau de liens dans ses trois états. 🔴 A trouvé **deux** défauts — §80 et §81 |
| `search_screen.dart` | 142 | `ui/search/SearchScreen.kt` + `SearchRoute` | ✅ | `RechercheTest` (**9** cas, S9) + `RechercheEtatTest` (**7** cas JVM), 2026-08-17 : recherche en cours, accueil, échec périmé, résultats précédents maintenus, note scellée muette, ouverture, effacement, et **aucun actionnable sans nom**. 🔴 A trouvé **un** défaut — §76 |
| `trash_screen.dart` | 263 | `ui/trash/TrashScreen.kt` + `TrashRoute` | ✅ | `CorbeilleTest` (**12** cas, S9, 2026-08-17) : chargement, corbeille vide, liste, note de coffre restée scellée, les deux confirmations destructrices **et leur annulation**, la durée de rétention réelle, et **aucun actionnable sans nom**. 🔴 A trouvé **trois** défauts — §74 (deux) et §75 |
| `settings_screen.dart` | 802 | `ui/settings/SettingsScreen.kt` + `SettingsRoute` | ✅ | `ReglagesTest` (**13** cas, S9, 2026-08-17) : balayage d'accessibilité dans deux états, ordre langue/thème mesuré aux coordonnées, valeur courante de chaque choix, « jamais » ≠ « 0 minute », interrupteur, dialogues de thème et de tri, **le mot-clé du mode panique**, ligne désactivée pendant l'effacement, les trois sorties, et l'absence des mentions légales. 🔴 A trouvé **un** défaut — §77 |
| `about_screen.dart` | 622 | `ui/about/AboutScreen.kt` | ☐ | Version lue dynamiquement via `PackageInfo` |
| `mentions_legales_screen.dart` | 131 | `ui/about/LegalScreen.kt` | ☐ | Rend `PRIVACY.{fr,en}.md` / `TERMS.{fr,en}.md` |
| `voice_setup_screen.dart` | 465 | `ui/voice/VoiceSetupScreen.kt` | ☐ | Phase 7 |
| `panic_complete_screen.dart` | 143 | `ui/panic/PanicScreens.kt` → `PanicOverlay` | ☐ | Écran terminal du mode panique |

⚠️ `HomeScreen.kt` est **sans état** et `HomeRoute.kt` porte toute la colle. Le découpage n'a pas
d'équivalent Flutter : `home_screen.dart` fait les deux. Vérifier l'écran, c'est vérifier les deux.
`TrashScreen.kt`, `SearchScreen.kt`, `SettingsScreen.kt` et `NoteEditorScreen.kt` suivent le même
partage depuis le 2026-08-17. **Les écrans restants qui portent leur `hiltViewModel()` en propre — à
propos, mentions légales, installation de la dictée — demanderont le même découpage avant d'être
mesurables.**

🔴 **Sur l'éditeur, ce découpage rend atteignables SIX états d'un coup** : les quatre issues de
chargement (introuvable, dossier coffre disparu, contenu abîmé, coffre refermé pendant la frappe),
l'échec d'enregistrement et sa raison nommée. Aucun ne s'obtient sur un téléphone sans abîmer une
vraie base — et c'est le seul écran du portage où l'on ne peut pas simplement « essayer pour voir »,
puisque l'essai écrit.

🔴 **Sur les réglages, ce découpage ne sert pas seulement à atteindre des états rares : il rend
mesurable la seule protection du mode panique**, le mot à recopier. À travers le vrai `PanicViewModel`,
ce test effacerait la base du S9 **et le modèle vocal de 57 Mo** — cf. `04-PIEGES.md` §77.

### 🔴 Balayage du motif §75 sur TOUS les `stateIn` — fait le 2026-08-17, un défaut de plus localisé

La valeur initiale d'un `stateIn` n'est pas une donnée, c'est une **absence** de donnée. Quatre
`UiState` en dépendent ; le relevé les sépare nettement :

| État | Verdict |
|---|---|
| `SettingsUiState` | ✅ **rien à faire** — sa valeur initiale est **lue** (`settings.themeNow()` et consorts), pas supposée, et son commentaire dit déjà pourquoi |
| `TrashUiState` | ✅ corrigé, cf. §75 |
| `SearchUiState` | ✅ **corrigé le 2026-08-17**, cf. §76 — c'est ce balayage qui l'a trouvé |
| `FoldersUiState` | ✅ **rien à faire, vérifié le 2026-08-17** — voir ci-dessous |

✅ **Ce balayage a payé le jour même** : la recherche affichait « Aucun résultat. Essayez un autre
mot-clé » **pendant** la recherche. Son `when` allait de `query.isBlank()` à `failed` puis directement
à `results.isEmpty()`, sans branche pour « la requête est posée, la réponse n'est pas là ». Le message
**accusait la saisie de l'utilisateur** pour une réponse qui n'était pas encore arrivée, alors que le
publié rend un indicateur (`search_screen.dart:109`) : régression du portage, corrigée. Détail en
`04-PIEGES.md` §76.

⚠️ **Le mécanisme est réutilisable** : faire porter à la réponse **la question à laquelle elle
répond** (`Issue.pour`), et comparer. C'est le seul moyen fiable de distinguer « aucun résultat » de
« pas encore de réponse » quand un `combine` mêle deux flux de rythmes différents.

### ✅ Et le tiroir des dossiers ne porte PAS ce défaut — vérifié, pas supposé

`FoldersUiState` dépend bien d'une valeur initiale vide, mais deux mesures ferment la question :

1. **Rien n'agit sur l'absence de boîte de réception.** Les deux seuls usages de `state.inbox` dans
   tout le dépôt sont dans `FoldersDrawer.kt:119` et `:124`, et les deux la traitent **exprès** :
   le nom traduit sert de repli, et le bouton de renommage disparaît plutôt que de proposer de
   renommer ce qui n'existe pas. Les commentaires le disent depuis l'origine.
2. **La fenêtre n'est pas atteignable par le tiroir.** `HomeRoute` collecte cet état dès sa première
   composition, alors que le tiroir est fermé : quand l'utilisateur l'ouvre, la réponse est arrivée
   depuis longtemps.

⚠️ *Un balayage de motif rend des candidats, pas des défauts.* Trois des quatre `stateIn` du portage
n'avaient rien à corriger — `SettingsUiState` lit sa valeur initiale, celui-ci a un repli délibéré — et
le quatrième portait une régression réelle. Cocher les quatre au motif que le motif existe aurait fait
modifier du code correct.

## Composants (16)

> **`note_card.dart` et `empty_state.dart` sont cochés par `CorbeilleTest` et `AccueilTest`**, pas
> par des tests qui leur seraient propres, et c'est délibéré : ce que fait une carte hors de l'écran
> qui la pose n'intéresse personne. `NoteCard` y est mesurée dans ses **trois** états — ouvrable,
> inerte (corbeille), et verrouillée sans divulguer son titre — et `EmptyState` dans les deux écrans
> qui l'emploient, dont l'un ne propose pas d'action et l'autre si.
>
> 🔴 Cocher `note_card.dart` a coûté **deux** défauts, opposés l'un à l'autre : une carte cliquable
> pour rien, et une carte qui n'annonçait pas du tout qu'elle est ouvrable. Cf. `04-PIEGES.md` §74.
>
> **`backlinks_panel.dart` est coché par `EditeurTest`**, pour la même raison et de la même façon :
> le panneau vit **dans** la colonne défilante de l'éditeur, et ce qu'il ferait ailleurs n'intéresse
> personne. Il y est mesuré dans ses **trois** états — absent quand la note n'a aucun lien, un lien
> **résolu** qui ouvre sa cible, un lien **fantôme** qui propose de créer la note et **dit** qu'elle
> n'existe pas encore — plus les mentions. ⚠️ L'absence a son témoin : la même note avec des liens
> montre bien ses sections, sans quoi l'assertion négative passerait sur un panneau qui n'apparaîtrait
> jamais.

| Composant Flutter | Lignes | Kotlin | Vérifié |
|---|---:|---|:---:|
| `vault_pin_sheets.dart` | 857 | `ui/vault/VaultSheets.kt` → `PinSheet` | ✅ |
| `folders_drawer.dart` | 780 | `ui/folders/FoldersDrawer.kt` | ✅ |
| `voice_recording_overlay.dart` | 460 | `ui/voice/SuperpositionDeDictee.kt` | ✅ |
| `vault_passphrase_sheets.dart` | 382 | `ui/vault/VaultSheets.kt` → `PassphraseSheet` | ✅ |
| `folder_dialogs.dart` | 225 | `ui/folders/FolderDialogs.kt` | ✅ |
| `note_card.dart` | 222 | `ui/home/NoteCard.kt` | ✅ |
| `backlinks_panel.dart` | 208 | `ui/editor/LiensDeLaNote.kt` | ✅ |
| `link_autocomplete_sheet.dart` | 206 | `ui/editor/FeuilleDAutocompletion.kt` | ✅ |
| `panic_confirm_dialog.dart` | 167 | `ui/panic/PanicScreens.kt` → `PanicConfirmDialog` | ☐ |
| `move_to_folder_sheet.dart` | 165 | `ui/editor/FeuilleDeDeplacement.kt` | ☐ |
| `passphrase_text_field.dart` | 105 | `ui/vault/VaultSheets.kt` → `ChampDePhraseSecrete` | ✅ |
| `voice_record_button.dart` | 85 | `ui/voice/ControleurDeDictee.kt` + le bouton micro de `NoteEditorScreen.kt` | ✅ |
| `blocking_progress_dialog.dart` | 51 | **aucun composant commun** — voir ci-dessous | ☐ |
| `empty_state.dart` | 50 | `ui/common/EmptyState.kt` | ✅ |
| `vault_warning_banner.dart` | 43 | `ui/vault/VaultSheets.kt` → `BanniereDAvertissement` | ✅ |
| `sheet_handle.dart` | 27 | **sans objet** — voir ci-dessous | ☐ |

> **Les quatre lignes de coffre sont cochées par `FeuillesDeCoffreTest`** (18 cas, S9, 2026-08-18).
> Elles vivent dans **un seul** fichier Kotlin, `ui/vault/VaultSheets.kt`, et se mesurent ensemble :
>
> - `vault_pin_sheets.dart` → `FeuilleDeCode`, dans ses six états — déverrouillage, création,
>   confirmation, code faux, coffre effacé, conversion partielle ;
> - `vault_passphrase_sheets.dart` → `FeuilleDePhraseSecrete`, création et déverrouillage ;
> - `passphrase_text_field.dart` → `ChampDePhraseSecrete` : nom conservé une fois rempli, propriété
>   `Password` posée, `EditableText` réduit aux puces, `CopyText`/`CutText` absents — chacun avec son
>   témoin ;
> - `vault_warning_banner.dart` → `BanniereDAvertissement`, présente **aux deux étapes** de la
>   création (le publié avait le défaut inverse, et le pavé sautait d'une hauteur de touche entre les
>   deux) et absente au déverrouillage.
>
> ⚠️ **Les deux feuilles ont été rendues sans état pour ce fichier** — `PinSheet`/`PassphraseSheet`
> branchées à Hilt délèguent à `FeuilleDeCode`/`FeuilleDePhraseSecrete`. Sans ce découpage, quatre
> états ne s'atteignaient pas : coffre effacé, temporisation, conversion partielle, phase de
> chiffrement. `FermetureDeFeuilleTest` reste utile — c'est lui qui a départagé deux relectures qui se
> contredisaient — mais il mesurait une feuille **synthétique**, et ne disait rien de celles-ci.
>
> Défauts trouvés et corrigés : `04-PIEGES.md` **§86** (aucune annonce, dans aucun état), **§87** (la
> hauteur réservée au message valait une ligne, le pavé sautait de 32 dp à 200 % de taille de texte),
> **§89** (après l'effacement, le pavé et « Valider » restaient actifs). Écrit et **non corrigé** :
> **§88**, l'éligibilité du champ à l'autoremplissage, que le publié désactive exprès. Trois soupçons
> **réfutés** par la mesure : **§90**.
>
> ⚠️ **La promesse publique « Coffres par dossier » reste décochée**, et exprès : elle porte sur
> « Argon2id + AES-256-GCM, paramètres identiques », c'est-à-dire sur la crypto du service, pas sur
> ces feuilles. Rien de ce tour ne la mesure.
>
> ⚠️ **Divergence assumée, à ne pas re-découvrir** : le portage offre un **œil** pour révéler le code
> à quatre chiffres ; l'application publiée le refuse, et le dit — *« pas de visibility toggle (un PIN
> court visible = défense de l'épaule trop coûteuse à perdre) »* (`vault_pin_sheets.dart:5`). Le
> commentaire d'`arb_vers_strings.py` note l'ajout mais **pas le refus ni sa raison**. Le portage
> atténue (la visibilité retombe à chaque étape, `FLAG_SECURE` est forcé) ; l'argument du publié tient
> quand même. **Décision de Patrice**, pas un défaut à corriger de sa propre initiative.

> **`voice_recording_overlay.dart` et `voice_record_button.dart` sont cochés par
> `SuperpositionDeDicteeTest`** (10 cas, S9, 2026-08-18) et `GesteDuMicroTest` (5 cas JVM).
>
> La superposition était **déjà sans état** — elle ne reçoit qu'une étape et un niveau sonore — donc
> aucun découpage à faire. Ce qui est mesuré : les trois balayages avec le décompte des actionnables
> dans les trois états actifs ; les **régions actives**, titre et consigne, état par état ; le fait que
> l'étape inactive n'affiche **rien**, qui est le témoin de l'instrument ; les deux gestes de
> l'enregistrement, distingués par **le rappel qui part** ; et la hauteur égale des deux boutons.
>
> **Défauts trouvés et corrigés** : `04-PIEGES.md` **§93** — pendant l'enregistrement il n'existait
> **aucune sortie qui ne transcrive**, `abandonner` était câblé jusqu'au moteur natif et aucun bouton
> ne l'appelait ; **§94** — `régionsActives=[]` dans les trois états, si bien que le « Parlez » qui
> dit que le micro est ouvert n'était **jamais** annoncé ; **§95** — le micro sans modèle installé
> affichait un constat et n'allait nulle part, là où le publié ouvre l'écran d'installation.
>
> ⚠️⚠️ **Les trois balayages étaient verts avant comme après.** Aucun nœud anonyme, aucune action
> perdue à la fusion, aucun champ de saisie — et pourtant l'écran ne disait rien. *Un écran sans nœud
> anonyme peut être un écran qui ne dit rien.*
>
> **§96 — corrigé.** La borne de deux minutes de `VoiceCapture` s'appliquait **en silence** : rien
> ne distinguait « la limite est atteinte » de « l'utilisateur a appuyé sur Arrêter », si bien qu'on
> perdait la fin d'une dictée sans jamais l'apprendre. Le publié n'a aucune borne mais affiche un
> chronomètre. Deux réponses, à deux moments : un compteur « 1:37 / 2:00 » qui **nomme** la borne
> pendant qu'on parle, et un message qui constate après coup. `enregistrer()` rend désormais une
> `Capture(fichier, fin)` au lieu d'un `File?` — un `File` ne peut pas dire **pourquoi** la capture
> s'est arrêtée.
>
> ⚠️ Ce correctif touche `VoiceCapture`, qui appartient à la ligne `voice_service.dart` — **encore
> décochée**. Il en ferme un défaut, il ne la coche pas : le reste de cette ligne (le moteur, le
> magasin de modèles, l'empreinte) n'a pas été mesuré.
>
> ⚠️ Écarts écrits, assumés : le retour ne coupe pas la dictée (le publié l'annule, mais ici retour et
> appui à côté arrivent par le même rappel) ; le bouton micro du portage est **désarmé** pendant une
> dictée là où le publié change son icône selon l'état — sans effet visible, la superposition étant
> modale et couvrant la barre.

> **`folders_drawer.dart` et `folder_dialogs.dart` sont cochés par `TiroirDesDossiersTest`**
> (19 cas, S9, 2026-08-18) et `GesteDeDossierTest` (5 cas JVM).
>
> Le tiroir était **déjà sans état** : aucun découpage à faire. Ce qui est mesuré : les trois
> balayages avec le **décompte** des actionnables (9, mesuré) ; le fait qu'un `IconButton` posé dans
> le slot `badge` d'un `NavigationDrawerItem` reste atteignable, vérifié **par le rappel qui part** et
> non par la présence d'un nœud ; les trois états du menu de dossier, dont les entrées de coffre
> s'excluent ; le champ du dialogue de nom et son refus d'un nom blanc ; et les deux dialogues
> destructeurs — dont le fait que les **deux choix de suppression ont la même hauteur**, ce qui est la
> seule forme qui distingue un bouton entier d'un bouton tronqué.
>
> **Défaut trouvé et corrigé : `04-PIEGES.md` §91** — sur un coffre **fermé**, « supprimer en gardant
> les notes » ouvrait la feuille de déverrouillage **sans mémoriser l'intention**. L'utilisateur
> confirmait, tapait son secret, et le geste s'évaporait. Le retrait de protection, lui, mémorisait :
> jumeau asymétrique. Les deux passent désormais par un seul chemin, `GesteDeDossier`.
>
> **Trois écarts écrits et non corrigés — §92** : le portage n'a **aucun appui long** là où le publié
> en a un (assumé : non découvrable, et sans équivalent au lecteur d'écran) ; `FolderEvent.Deleted`
> porte un décompte de notes déplacées que **personne ne lit**, si bien que supprimer un dossier
> déplace ses notes en silence ; et les feuilles de coffre sont ouvertes par `HomeRoute` et non par le
> tiroir, contrairement au publié.
>
> ⚠️ **Le tiroir en cours de chargement affiche une boîte de réception de repli** — nom traduit au
> lieu du nom réel, pas de bouton de renommage — parce que la valeur initiale de `stateIn` est
> indiscernable d'une base sans dossiers (motif §75/§76). L'application publiée a la même faiblesse.
> **Figé par un test plutôt que corrigé.**



### 🔴 `link_autocomplete_sheet.dart` — cochée le 2026-08-17, et son défaut était écrit d'avance

`AutocompletionTest` (**9** cas, S9) + `EtatDAutocompletionTest` (**8** cas JVM). Le défaut avait été
**localisé et consigné** en cochant la ligne de l'éditeur, sans être corrigé — exactement la situation
qui avait rendu la ligne « recherche » rapide.

⚠️ Pendant les 120 ms de freinage, la liste des suggestions est **vide par construction**. La feuille en
tirait deux conclusions fausses : elle proposait **de créer** une note qui existe peut-être, et sa
validation au clavier **créait** un homonyme là où elle devait lier. **Ce n'est pas une régression de
parité** — le publié crée toujours — mais la divergence **délibérée** du portage qui était
incomplètement efficace, ce qui est plus dangereux : elle est écrite comme une garantie. §84.

🔧 Mécanisme repris de §76 : la réponse porte sa question. ⚠️ Mais **pas ses valeurs limites** — ici la
chaîne vide est une **vraie** réponse, celle d'une saisie vide, alors que §76 s'en méfiait.

⚠️⚠️ Une validation au clavier pendant l'attente n'est ni exécutée ni jetée : elle est **retenue**, puis
appliquée quand la réponse arrive. Créer aurait produit le doublon ; ignorer aurait fait de « Entrée »
un geste sans effet.

🔴 **Premier balayage d'accessibilité sur une feuille**, et il a trouvé un actionnable sans nom qui
**n'appartient pas au portage** : `BottomSheetDefaults.DragHandle` pose **deux** nœuds aux mêmes
coordonnées, dont un qui ne porte qu'un `OnLongClick` et aucun nom. Il paraîtra sur **toutes** les
feuilles restantes — déplacement, dossiers, coffres. L'exception est nommée **dans le test**, ancrée sur
la poignée mesurée, et l'assertion reste un `containsExactly`.

### Les deux lignes sans homologue, et pourquoi ce n'est pas la même chose

**`sheet_handle.dart` — sans objet, et c'est une bonne nouvelle.** Le composant Flutter extrayait à
la main le `Container` 36×4 dp que tous les sheets recopiaient. `ModalBottomSheet` de Material3 pose
cette poignée **par défaut** (`BottomSheetDefaults.DragHandle`, vérifié dans l'artefact 1.4.0). Il
n'y a rien à porter : la duplication que le composant corrigeait n'existe pas ici.

🔴 **`blocking_progress_dialog.dart` — pas d'homologue, et là il faut regarder.** Le composant
publié centralisait un dialogue **volontairement bloquant** (`PopScope(canPop: false)`) pour ses deux
appelants. Le portage les traite séparément, et la question « est-ce toujours bloquant ? » se pose
donc **deux fois** :

| Appelant publié | Portage | Bloquant ? |
|---|---|---|
| `settings_screen.dart:687` (panique) | `ui/panic/PanicScreens.kt` → `PanicOverlay` | ✅ recouvrement plein écran, `BackHandler` qui **avale** le geste tant que la séquence tourne |
| `folders_drawer.dart:659` (conversion) | `ui/vault/VaultSheets.kt` → `MessageDEtat` dans la feuille | 🔴 **ne l'était pas** — corrigé le 2026-08-16, voir ci-dessous |

🔴 **Le remplissage de cette colonne a trouvé un défaut, et c'est ce qu'on lui demandait.** La
feuille de conversion refusait de se fermer **uniquement** dans `onDismissRequest`. Mesuré sur le S9
(`FermetureDeFeuilleTest`) : un **balayage vers le bas** fait quitter l'écran à la feuille *avant*
d'appeler ce rappel — la garde arrivait après la bataille, et le chiffrement continuait sans rien à
l'écran pour le dire. Exactement ce que `PopScope(canPop: false)` interdit côté publié.

Correctif : un veto sur la transition vers `Hidden` (`confirmValueChange`), **en plus** de la garde
existante, qui reste la seule à couvrir le Retour. Détail et mesures en `04-PIEGES.md` §67-§68.

⚠️ *Une case « Vérifié » n'aurait rien vu ici* : l'écran se comporte normalement tant qu'on ne
balaie pas pendant les deux secondes du chiffrement. C'est la question « quel fichier joue ce
rôle ? » qui a mené au défaut, pas la question « est-ce que ça marche ? ».

## Services transverses

| Service | Lignes | Kotlin | Vérifié | Point de vigilance |
|---|---:|---|:---:|---|
| `folder_vault_service.dart` | 1 582 | `security/vault/FolderVaultService.kt` | ✅ | Ouvrir un coffre **créé par la version Flutter** — voir la note ci-dessous |
| `note_export_service.dart` | 519 | `data/export/NoteExporter.kt` + `domain/export/` | ☐ | Export `.md` d'une note de coffre — le corps ne doit pas être vide |
| `panic_service.dart` | 478 | `security/panic/PanicService.kt` | ☐ | Ordre des étapes ; un rapport ne doit jamais mentir sur un effacement |
| `voice_service.dart` | 450 | `data/voice/WhisperStt.kt` + `VoiceCapture.kt` + `SttModelStore.kt` | ☐ | Phase 7 |
| `backlinks_service.dart` | 401 | `data/repository/LinksRepository.kt` + `data/local/NoteLinkWriter.kt` + `domain/links/` | ☐ | Plafond de balayage 50 ko — ✅ **relevé le 08-16 : les deux plafonds concordent au chiffre près**, `WikiLinkParser.CONTENT_SCAN_LIMIT = 50_000` et `MAX_LINKS_PER_NOTE = 256` contre `noteContentBacklinksLimit = 50000` et `_maxLinksPerNote = 256`. Reste à vérifier le **comportement** sur une note qui dépasse |
| `keystore_bridge.dart` | 207 | `security/vault/AndroidVaultKeystore.kt` + `security/kek/KeystoreSealedKekSource.kt` | ☐ | Repris depuis `KeystoreBridge.kt`, sans MethodChannel |
| `secure_window_service.dart` | 103 | `ui/secure/SecureWindowController.kt` + `SecureWindowGuard.kt` | ☐ | `FLAG_SECURE` avec compteur de références |
| `settings_service.dart` | 116 | `data/prefs/AppSettings.kt` + `LegacyPreferences.kt` | ☐ | Lire les clés `flutter.*` existantes |

⚠️ Un service Dart se disperse souvent sur **plusieurs** fichiers Kotlin : la séparation
domaine / données que le portage impose n'a pas d'équivalent côté Flutter. Cocher la ligne veut dire
avoir vérifié le **comportement**, pas chacun des fichiers listés.

> **`folder_vault_service.dart` est coché le 2026-08-18**, et voici **exactement** ce qui a été
> comparé — parce qu'une case cochée sur 1 582 lignes doit dire ce qu'elle couvre.
>
> | Axe | Verdict |
> |---|---|
> | Dérivation Argon2id, déballage de KEK, vérificateur, enveloppe de note | **concordent octet pour octet** avec le Dart (`PariteCoffreAvecFlutterTest`, 10 cas, vecteurs recoupés contre le C de référence et OpenSSL) |
> | Ouvrir un coffre écrit par Flutter | mesuré (`la_cle_dun_coffre_ecrit_par_flutter_souvre_depuis_kotlin`) |
> | Freinage après échec | **identique** : 1/2/4/8/16/30 s, même plafond, horloge monotone des deux côtés. Une seule table au lieu de deux — équivalent, un dossier n'ayant qu'un mode |
> | Longueurs de secret | **identiques** : phrase ≥ 8, code 4–6, chiffres ASCII seulement |
> | Ordre de l'auto-effacement | **identique**, et amélioré sur deux points déjà documentés (échecs comptés, drapeau conservé si la reprise échoue) |
> | Report d'échéance sur consultation | **défaut trouvé et corrigé — `04-PIEGES.md` §99** |
> | Migration v1 → v2 | **aucun test des deux côtés — comblé, `04-PIEGES.md` §100** |
>
> ⚠️ **Ce qui n'a PAS été comparé ligne à ligne** : les trois traitements par lot
> (`encryptAll`/`decryptAll`/`removeVaultProtection`) au-delà des cas déjà couverts par
> `FolderVaultServiceTest`, et `dispose()`. Ils ont des tests ; ils n'ont pas eu de relecture
> croisée avec le Dart dans ce tour. *Le dire vaut mieux que de laisser lire la case comme
> « tout est vérifié ».*
>
> ⚠️ `encryptNoteLegacyV1`, côté publié, est un outil `@visibleForTesting` **que personne n'appelle**
> — y compris les tests pour lesquels il a été écrit. Cf. §100.

## Promesses publiques à ne pas casser

Ce ne sont pas des fonctionnalités mais des engagements affichés sur files-tech.com et dans les
métadonnées F-Droid. Une régression ici est publique.

| Promesse | Contrôle | Vérifié |
|---|---|:---:|
| Zéro permission Internet | **APK release** (`aapt2 dump xmltree`) : `RECORD_AUDIO` + la permission interne du receiver, **rien d'autre**. Zéro occurrence de `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | ✅ |
| 100 % local, aucune donnée ne sort | **0** bibliothèque réseau sur 940 lignes de `releaseRuntimeClasspath` ; les **4** `.so` de l'APK release n'ont **aucun** symbole de socket (`llvm-nm --dynamic --undefined-only`) | ✅ |
| Base chiffrée au repos | **Vraie base du S9** : en-tête ≠ `SQLite format 3`, et le **WAL** de 506 ko mesuré à **7,9991 bits/octet** d'entropie, 256 valeurs distinctes, **0** occurrence de `CREATE TABLE`/`notes`/`folder` | ✅ |
| Coffres par dossier | Argon2id + AES-256-GCM, paramètres identiques | ☐ |
| Dictée vocale sur l'appareil | `TranscriptionSurAppareilTest` — ✅ **et il tourne enfin dans la suite**, cf. `04-PIEGES.md` §72 | ✅ |

### 🔴 Trois de ces promesses tenaient déjà, la quatrième a demandé un détour

⚠️ **Le manifeste fusionné intermédiaire ne fait PAS foi** : celui de `build/intermediates/` datait du
2026-08-15 à 19:48, soit **avant** le moteur et l'interface, alors que les APK release sont du 08-16 à
19:14. Le lire aurait donné le bon résultat pour la mauvaise raison. C'est l'**artefact publié** qui
répond.

⚠️⚠️ **Et l'instrument s'est trompé une fois.** La première recherche de symboles réseau dans les
`.so` rendait « aucun » — avec un motif `send(to|msg)?$` ancré par `$`, alors que les symboles portent
un suffixe `@LIBC`. Le témoin positif (`malloc`) rendait **0** lui aussi, ce qui a révélé la faute.
Après correction : un seul résultat, `sendfile@LIBC` dans `libnotes_stt.so`, tiré par `<filesystem>`
de libc++ (`ggml-backend-reg.cpp:8`) — pas un chemin réseau, et sans `socket()` nulle part ni
permission `INTERNET`, il n'y a aucun descripteur où écrire.

⚠️ Le WAL a été tiré par `adb exec-out`, **pas** `adb shell` : ce dernier convertit les fins de ligne
et rendait 508 768 octets au lieu de 506 792. Une mesure d'entropie sur un binaire corrompu n'aurait
rien voulu dire. Le fichier a été effacé du poste après mesure.

⚠️ Le manifeste à contrôler est le **fusionné**, jamais le manifeste source seul — une dépendance
peut y injecter une permission :
`app/build/intermediates/merged_manifest/release/…/AndroidManifest.xml`

## Migration des données

> **Relevé le 2026-08-15.** Ce tableau portait sept cases vides. Quatre étaient en réalité
> **prouvées depuis des semaines** par des tests qui tournent à chaque exécution du gate ; personne
> n'était revenu les cocher. Une case vide qui décrit du travail déjà fait est aussi trompeuse
> qu'une case cochée qui décrit du travail non fait — elle fait rouvrir un chantier clos, et elle
> noie les trois cases qui bloquent vraiment.
>
> Chaque ligne cochée nomme donc **le test qui la prouve**. Chaque ligne non cochée nomme **ce qui
> la bloque**. Aucune ne reste sans justification.
>
> Suite d'instrumentation vérifiée le 2026-08-15 sur le S9 : **118 tests, 0 échec, 0 ignoré** —
> compté, parce qu'ici six tests instrumentés ont déjà été *ignorés* pendant que l'instrumentation
> affichait `OK` (`04-PIEGES.md` §45).

| Cas | Vérifié | Par quoi, ou bloqué par quoi |
|---|:---:|---|
| 2.0.3 → 2.0.4 → 3.0.0 (chemin nominal, couche ①) | ◐ | **La passerelle EXISTE depuis le 2026-08-18** (`notes_tech`, `f216390`, 2.0.4+52) et ses deux moitiés sont mesurées **séparément** : elle écrit un scellé de la bonne forme — vérifié sur le S9, blob 48 octets, nonce 12, sans retour à la ligne — et `KeystoreSealedKekSourceTest` (8 cas, S9) relit un scellé **produit comme la passerelle le produit**, sans passer par `store()`. Ce qui manque : les deux **sur la même installation**, avec de vraies données. Cela demande des builds **signées** des deux côtés (`applicationIdSuffix = ".debug"` interdit la prise de place en debug), et **le portage n'a aucun `key.properties`** — cf. `10-PASSERELLE-2.0.4.md` §9. |
| 2.0.3 → 3.0.0 direct (couche ② de secours) | ◐ | **Mécanisme prouvé, bout-en-bout non.** `FlutterSecureStorageKekSourceTest` (12 cas) rejoue une valeur écrite **exactement comme la bibliothèque l'écrit**, vérifie que MGF1-SHA1 est imposé par la plateforme, que l'alias visé est celui de l'application publiée, et que la lecture **ne modifie pas** le stockage. Ce qui manque n'est pas le code : c'est la lecture du vrai stockage d'une vraie installation, qui n'arrive qu'à la bascule. |
| KEK introuvable, base présente → refus, **base intacte après** (couche ③) | ✅ | `LegacyDatabaseOpeningTest.sans_kek_et_avec_une_base_presente_l_ouverture_est_refusee_et_la_base_intacte`, doublé côté logique par `KekRepositoryTest.aucune cle et une base presente donne un refus, et AUCUNE ecriture`. **Le refus ET l'intégrité après refus sont tous deux vérifiés.** |
| Installation neuve | ✅ | `KekRepositoryTest.aucune cle et aucune base declenche une generation persistee AVANT d'etre rendue` — l'ordre compte : une clé rendue avant d'être persistée chiffrerait des notes sous une clé que le redémarrage suivant ne retrouverait pas. |
| Coffre passphrase créé en Flutter, ouvert en Kotlin | ✅ | `FolderVaultServiceTest.la_cle_dun_coffre_ecrit_par_flutter_souvre_depuis_kotlin` — les quatre colonnes `vault_*` **et** le blob de note viennent du vrai code Dart de la 2.0.3, recoupés contre OpenSSL et l'Argon2 de référence. ⚠️ Octets authentiques, mais **pas pris sur le téléphone d'un utilisateur**. |
| Coffre PIN créé en Flutter, ouvert en Kotlin | ◐ | **Irréproductible hors bascule, et ce n'est pas un manque de rigueur.** Le scellement extérieur d'un coffre à code est fait par une clé Keystore **liée à l'appareil et à l'UID** : aucun vecteur ne peut la rejouer. Ce qui **entre** dans le Keystore est vérifié octet pour octet (`PariteCoffreAvecFlutterTest.lesCouchesInternesDuCoffrePinConcordent`) ; l'enveloppe ne peut l'être qu'avec le vrai Keystore de la vraie installation. |
| Auto-effacement interrompu (`vault_wipe_pending_*`) repris au démarrage | ✅ | `FolderVaultServiceTest.un_effacement_interrompu_est_repris_au_demarrage` — le drapeau posé, l'application tuée, la reprise détruit bien les notes **et** retire le matériel de coffre. Complété par `le_drapeau_dun_dossier_disparu_est_retire`, qui vérifie que c'est le **seul** retrait légitime : retirer le drapeau sur échec permettrait de sauver un coffre condamné en provoquant un plantage. |

**Reste donc trois lignes, et elles tiennent en une phrase** : toutes les trois attendent le seul
geste qui ne se simule pas — installer par-dessus une vraie installation, avec de vraies données. Ce
sont les trois cases du geste de bascule, pas des cases de développement.

⚠️ **La couche ① n'attend plus une décision de rédaction mais une clé.** La passerelle est écrite et
ses deux moitiés sont mesurées ; ce qui manque est une build **signée** du portage, donc un
`key.properties` pointant sur `notes_tech/android/notestech-release.jks`. Sans cette clé, la 3.0.0 ne
s'installera par-dessus Notes Tech pour personne — ce n'est pas un détail de publication, c'est une
condition de la migration.

⚠️ **Aucune de ces trois ne se coche depuis un poste de travail.** Elles demandent le S9, une
installation 2.0.3 réelle, et le drapeau `-Pnotestech.replaceInstalledApp=true` — cf.
`06-ISOLATION-PENDANT-LE-CHANTIER.md` §2. Les cocher autrement serait mentir sur la seule vérification
qui protège les notes d'un utilisateur installé.

## Écarts assumés avec l'application publiée, relevés en phase 3

> Un écart n'est acceptable que s'il est **choisi**, écrit, et justifié. Ceux-là le sont.

| Sujet | Application publiée | Ce portage | Pourquoi |
|---|---|---|---|
| Auto-lien `[[son propre titre]]` | résolu vers elle-même — le garde-fou est défait par `resolveDangling` | reste fantôme | l'intention du code d'origine est explicite ; `target_id` n'est pas partagé entre versions |
| Rétroliens depuis une note verrouillée | pas de garde SQL ; les liens sont purgés au verrouillage | garde SQL **en plus** | la non-divulgation ne doit dépendre d'aucune donnée écrite par un autre programme |
| Rétroliens : liens fantômes | inclus (`links_dao.dart:64`) | inclus | une première version de ce portage les manquait — corrigé |
| Casse Unicode : osage, adlam | inchangés (table de casse figée) | passent en minuscules | écart mesuré, borné, se répare à la réindexation. Cf. `09-VECTEURS-DE-PARITE.md` |
| Longueur du nom de dossier | non plafonnée | non plafonnée | plafonner refuserait de renommer un dossier existant plus long. À traiter au champ de saisie |
| Déplacer une note verrouillée | passe par le service de coffres | **refusé** avec une exception nommée | chaque coffre a sa clé ; le blob ne se transporte pas. Sera levé en phase 4 |
| Flux d'événements de changement | `NoteChangeEvent` + service temporisé | invalidation Room + transaction | cf. `01-DECISIONS.md` D-009 |

## Reporté à la phase 5, avec l'interface

Ces éléments sont des **fonctions d'affichage** du modèle. Les porter avant l'écran qui les
consomme reviendrait à écrire du code qu'aucun test ne peut exercer.

| Élément | Source Dart | Piège connu |
|---|---|---|
| `Note.excerpt` | `note.dart:200` | cinq expressions régulières enchaînées, plafond à 200 caractères ; vide pour une note verrouillée |
| `Note.wordCount` | `note.dart:86` | ⚠️ rend **1** pour un contenu fait uniquement d'espaces — `"".split(\s+)` rend une liste d'un élément vide. Reproduire le quirk, ou le corriger sciemment |
| `Note.characterCount` | `note.dart:85` | compte les unités UTF-16, pas les caractères perçus |
| Libellés de `NoteSortMode` | `note.dart:230` | à localiser, pas à recopier en dur |

## Reporté à la phase 6

| Élément | Pourquoi il ne pouvait pas être fait en phase 3 |
|---|---|
| Réconciliation complète des liens au démarrage | l'indexation transactionnelle rend les incohérences impossibles **côté Kotlin**, mais la base vient de la version Flutter et peut en porter |
| Auto-complétion `[[…]]` dans l'éditeur | `NotesRepository.suggestTitles` existe et est testé ; il lui manque son écran |

## ✅ Phase 6 CLOSE le 2026-08-15 — plus aucun manque du publié

Les deux manques assumés qui restaient ont été comblés : **sortir une note d'un coffre** et
**copier en Markdown**. S'y sont ajoutés, tous découverts en cherchant *pourquoi* une chaîne traduite
n'était lue nulle part :

| Ce qui manquait | Nature |
|---|---|
| Vider la corbeille | **régression** — existe dans le publié |
| Progression et confirmation de la conversion en coffre | régression |
| Rattrapage immédiat des notes restées en clair après conversion | régression |
| Indicateur d'enregistrement dans l'éditeur | régression |
| Bouton « Terminé » explicite | régression |
| Annonces d'accessibilité (déverrouillage, enregistrement) | régression |
| Classement des erreurs de chargement d'une note | **défaut du portage** — tout tombait sur « demander le secret » |
| Raison d'un échec d'enregistrement | défaut du portage |
| Filet d'erreur sur la recherche | défaut du portage |

### La méthode, plus réutilisable que la liste

Une chaîne traduite **des deux côtés** et lue **nulle part** est un signal. Le tri se fait en une
question mécanique : *son jumeau est-il utilisé dans l'application publiée ?* Sur 82 orphelines,
**82 oui, 0 non**.

⚠️ Le discriminant est **nécessaire mais pas suffisant** : les huit `error_*` sont bien utilisées
côté Dart, mais le portage les a remplacées par un `Reason` typé — même comportement, autre
implémentation. Chaque groupe se relit ; le verdict brut ne s'applique pas.

### État final des orphelines

| Compte | Sort |
|---|---|
| **45** | dictée vocale — phase 7 |
| **2** | puces du mode panique nommant le modèle vocal — phase 7, **omises exprès et commentées** |
| **14** | délibérées : raisons typées, substitutions assumées, ou chaînes que le portage fait **mieux** sans |

Aucune n'est supprimée : cf. `04-PIEGES.md` §48.

## Phase 8 — deuxième instrument : le relevé des GESTES

La revue des chaînes ne voit que ce qui a du texte. **Un appui long n'en a pas, un balayage non
plus.** D'où un second relevé, tout aussi mécanique :

```bash
# côté publié
grep -rEoh "onLongPress|Dismissible|onDoubleTap|GestureDetector|onReorder" lib --include=*.dart | sort | uniq -c
# côté portage
grep -rEoh "combinedClickable|onLongClick|SwipeToDismiss|detectDragGestures|pointerInput" app/src/main/java --include=*.kt | sort | uniq -c
```

⚠️ Lire les occurrences, pas les compter : les 9 « Dismissible » du publié étaient des
`barrierDismissible` et des `isDismissible` — **aucun balayage** dans l'application publiée.

| Geste du publié | Sort dans le portage |
|---|---|
| Appui long sur la **boîte de réception** → renommer | 🔴 **manquait entièrement** — corrigé par un bouton visible (`769be09`) |
| Appui long sur un **dossier** → menu | 🟠 non porté : `NavigationDrawerItem` ne le prend pas, et le bouton ⋮ couvre le besoin. **Écart assumé** |
| `NoteCard.onLongPress` | paramètre **mort des deux côtés** — retiré ici |

⚠️ Le premier n'était signalé par **aucune** chaîne orpheline : `folder_rename_title` et
`folder_rename_field` servent déjà aux autres dossiers. Un manque peut être parfaitement invisible
au relevé des chaînes.

## ⚠️ Correctifs appliqués à l'application Flutter le 2026-08-13

> Branche `fix/defauts-releves-pendant-le-portage` dans `notes_tech`, commit `ca72f2c`.
> **Non fusionnée, non publiée** — version et `versionCode` inchangés.

Le portage a servi de relecture ligne à ligne de l'original. Trois défauts en sont sortis, corrigés
côté Flutter puisque c'est **la version publiée**, celle que les utilisateurs font tourner.

| Défaut | Conséquence | Correctif |
|---|---|---|
| `resolveDangling` annulait le garde-fou anti-auto-lien | une note figurait dans ses **propres** rétroliens | `AND source_id <> ?` |
| `listPlaintextInFolder` ne voyait pas un titre en clair **sans corps** | une note de coffre héritée restait lisible au repos, indéfiniment | critère élargi, strictement additif |
| `wordCount` rendait **1** pour un contenu fait d'espaces | compteur faux | élaguer avant de tester la vacuité |

Renforcement : `backlinkSources` exclut désormais les notes verrouillées comme **source**.

### Ce qui n'a **pas** été corrigé, et pourquoi

**Une étiquette contenant une virgule serait coupée en deux.** Vérifié : **aucun chemin de
l'application n'écrit jamais d'étiquette**. La colonne `tags` est dormante — seules la lecture et
l'export existent. Le défaut est inatteignable, et corriger le codage changerait un format de
données partagé entre les deux versions.

⚠️ J'avais annoncé ce point comme « de la perte de donnée visible par l'utilisateur ». C'était faux :
je n'avais pas vérifié qu'il existait un chemin de saisie.

**`folders_dao.update` écrit la ligne entière, colonnes de coffre comprises.** Effacer
`vault_kek_wrapped` rendrait toutes les notes du coffre définitivement illisibles. Mais aucun défaut
aujourd'hui — les objets viennent de la base — et modifier un chemin d'écriture qui fonctionne dans
une application **publiée** coûte plus qu'il ne rapporte. Le portage Kotlin, lui, ne l'expose pas
(D-010).

### Ce que les tests couvrent, et ce qu'ils ne couvrent pas

`wordCount` est testé (5 cas). Les deux correctifs SQL ne le sont pas : la suite Flutter n'a **aucun
harnais de base de données**, un choix que ses auteurs documentent explicitement dans
`folder_vault_service_test.dart`.

Le portage Kotlin, lui, exerce la même sémantique contre du vrai SQLite —
`une_note_qui_se_cite_elle_meme_ne_produit_pas_de_lien_vers_elle_meme` et
`une_note_de_coffre_au_titre_en_clair_et_au_corps_vide_est_detectee`. C'est un contrôle croisé, pas
une couverture de la version Flutter.

**70 tests verts côté Flutter (65 avant), `flutter analyze` sans avertissement.**

### Quand publier ces correctifs — **décision de Patrice**, 2026-08-13

> « pas de publication 2.0.4 pour le moment ». Les correctifs restent sur leur branche.

**Attendre que la MR F-Droid !37885 soit tranchée.** Elle est ouverte, à **2.0.3 / versionCode 51**,
son blocage Play Core est résolu et documenté, et elle est en `waiting-for-upstream` — la balle est
chez le mainteneur.

| Raison | Détail |
|---|---|
| Ne pas bouger la cible | la MR vient d'être débloquée après 21 commentaires ; taguer 2.0.4 obligerait à la mettre à jour en plein examen, ou à la laisser en retard |
| Publier après coûte **moins** | `AutoUpdateMode: Version` + `UpdateCheckMode: Tags` ⇒ une fois fusionnée, **un tag suffit** |
| Exposition étroite | notes de coffre créées **avant la 2.0.0**, titre rempli, corps vide ; `_sealIfVault` protège les écritures depuis |

**Déclencheur** : MR fusionnée ou fermée. **Borne** : quatre à six semaines, après quoi publier
quand même — un correctif de confidentialité qui existe ne doit pas attendre indéfiniment un tiers.

#### ⚠️ La 2.0.4 Flutter n'a **PAS** de date — précisé par Patrice le 2026-08-14

> « pour Flutter on verra quand F-Droid aura validé l'appli. »

Le déclencheur reste **exactement** celui écrit ci-dessus : la MR !37885 tranchée. Aucune date de
calendrier ne s'y substitue.

⚠️ **Ne pas confondre avec la cible de début septembre**, qui porte sur la **release 3.0.0 du
portage Kotlin** et sur elle seule — cf. [00-PLAN.md](00-PLAN.md), phase 8. Les deux dépôts ont des
horloges séparées, et je les avais confondues en écrivant cette section une première fois.

⚠️ Le bump touche `pubspec.yaml` **et** `AppConstants.appVersion`, plus fastlane FR+EN, les trois
surfaces du site, et le `.yml` F-Droid.


## ✅ Écarts relevés dans l'application publiée — les QUATRE sont tranchés

*Relevés pendant la phase 5, le 2026-08-14.*

Chacun est **reproduit à l'identique** dans le portage. La raison est toujours la même : la parité
est le critère de sortie de la phase 8, et un correctif silencieux est indiscernable d'un défaut de
portage le jour de la comparaison. Les corriger est une décision, pas une évidence.

### 1. ✅ CLOS le 2026-08-14 — le menu de tri affichait deux fois le même libellé

`settings_screen.dart:169-175` faisait correspondre `createdDesc` au libellé de `updatedDesc`, et
`createdAsc` à celui de `updatedAsc`. Le menu proposait donc **six entrées dont quatre portaient deux
libellés**, sans que rien ne distingue le tri par date de modification de celui par date de
création — la position du bouton radio était le seul indice de ce qu'on avait choisi.

**Corrigé des deux côtés, sur décision de Patrice** : `notes_tech` commit `24bc67e`, portage commit
`665da44`. Les deux clés existantes ont changé de **valeur** plutôt que d'être doublées par deux
nouvelles — « Plus récent d'abord » à côté de « Créée — plus récente d'abord » aurait laissé
**deviner** que la première parle de modification.

⚠️ **Le blocage n'existait pas.** Ce point est resté ouvert deux phases au motif que corriger
demandait de toucher à l'ARB de `notes_tech`, dont trois fichiers l10n étaient « modifiés avant mon
intervention ». Vérification faite : ces trois fichiers étaient **identiques à `HEAD`**, aux fins de
ligne près. Cf. `docs/04-PIEGES.md` §42.

### 2. ✅ CLOS le 2026-08-18 — « Toutes les notes » inclut les archives, un dossier non : **ce n'est pas un écart, c'est la définition d'une archive**

`notes_dao.dart:156` filtre sur `trashed_at IS NULL` seul ; `listByFolder` (ligne 59) ajoute
`archived = 0`. Une note archivée disparaît donc de son dossier et reste dans « toutes les notes ».

**Sortie de sa liste, toujours atteignable ailleurs** — c'est exactement ce qu'on attend d'une
archive, et l'asymétrie est donc juste. Le « correctif » évident — filtrer des deux côtés — rendrait
la note **invisible partout**, sans aucun écran pour la retrouver, alors qu'aucun geste ne permet de
la désarchiver : il aurait perdu des notes.

🔴 **Ce qui la rendait fragile n'était pas l'asymétrie mais le fait que rien ne l'exerçait.**
`NotesRepository.setArchived` n'a **aucun appelant** — comme dans la 2.0.3, dont aucun écran n'archive
non plus, le seul `archive` de `lib/ui/` étant une icône d'export : la colonne vaut `0` partout, et
l'asymétrie est **inobservable**. Une règle que rien n'exerce est une règle qu'un refactor déplace
sans que personne ne le voie.

🔧 **Trois cas de `NotesRepositoryTest`** l'épinglent désormais — l'archivage, le retour en arrière, et
le paramètre `includeArchived` — avec leur témoin **avant** archivage, sans quoi un test qui ne trouve
la note nulle part passerait pour une preuve. Le jour où l'archivage sera câblé, il héritera d'un
comportement **décidé**. ⚠️ Le chemin d'écriture n'est **pas** du code mort à supprimer : c'est la
surface que l'application publiée expose aussi.

### 3. ✅ CLOS le 2026-08-18 — renommer ne dit rien, **supprimer le dit** : cf. `04-PIEGES.md` §97

L'argument d'origine — *« le tiroir se met à jour sous les yeux de l'utilisateur »* — est vrai pour le
renommage et la création, et **faux pour la suppression**. Le critère n'est pas l'importance du geste
mais **ce que l'utilisateur voit se produire** : un renommage montre le nouveau nom, une création la
nouvelle ligne ; une suppression ne montre que la disparition du dossier, et tait le sort de ses notes,
qui n'étaient pas à l'écran.

🔧 Renommer et créer restent **muets**, exprès. Supprimer annonce le nombre de notes **et leur sort** —
déplacées vers la boîte de réception, ou détruites. `FolderEvent.Deleted` portait déjà le décompte,
calculé puis jeté (§92).

⚠️⚠️ **Le cas le plus injuste** : supprimer un dossier détruit aussi ses notes **en corbeille**
(`ON DELETE CASCADE`, et la mise en corbeille conserve le `folder_id`) — des notes encore visibles
depuis l'écran Corbeille et encore restaurables. Le nombre annoncé vient donc de `countAllInFolder`,
**sans** le filtre `trashed_at IS NULL`.

⚠️ Deux clés i18n ont bien été ajoutées, que la version Flutter n'a pas. C'était la crainte d'origine,
et elle ne tient pas face à un geste destructeur dont la conséquence est invisible.

### 4. ✅ CLOS le 2026-08-18 — la catégorie CLDR `many` est définie, et **elle ne sort pas sur le S9** : cf. `04-PIEGES.md` §98

`lintDebug` la signalait sur les **quatre** `<plurals>` français (et non trois). Les quatre formes sont
écrites, `MissingQuantity` est à **0**. S'appuyer sur le repli vers `other` revenait à confier une règle
de grammaire à un mécanisme de secours.

🔴 **Mesuré, pas supposé** : la forme `many` **n'est pas sélectionnée** sur le S9 — l'ICU d'Android 10
ignore cette catégorie, entrée dans CLDR 38 (2020), et retombe sur `other`. Le premier test, qui
exigeait « 1 000 000 **de** notes », a échoué. Il accepte désormais les deux formes et refuse tout le
reste : exiger l'une ou l'autre reviendrait à mesurer la version d'ICU de la machine de test.

🔧 Deux `assert` dans `arb_vers_strings.py` — un par famille de pluriels, transposés et écrits à la
main — parce qu'un garde qui n'en couvre qu'une donne l'impression de couvrir les deux. Le second a
levé immédiatement, sur `trash_emptied`.

🔧 Au passage, `folder_delete_decrypt_failed` passe de « %1$d note(s) » à un vrai `<plurals>`
(`PluralsCandidate`).

## Défauts de l'application publiée **corrigés dans `notes_tech`**

*Relevés en portant la phase 6, corrigés le 2026-08-14 sur demande de Patrice, branche
`fix/defauts-releves-pendant-le-portage`. ⚠️ **Aucune publication 2.0.4 décidée** — le code est
corrigé, la release ne l'est pas.*

### `destroyKek()` ne vérifiait pas son résultat — commit `333aba1`

**La seule étape de la panique dans ce cas, et celle dont dépend la garantie minimale.** `dbWipe`,
`prefsClear`, `exportsWipe` et `tmpPurge` ont toutes été corrigées pour lever si quelque chose
survit — deux relectures externes s'en sont chargées — mais pas celle-là. `hasKek()` existait déjà,
dix lignes plus bas.

`_storage.delete` ne rend aucun statut : un échec côté plateforme est indiscernable d'un succès. Le
rapport portait alors « kekDestroy OK », l'écran de fin annonçait des notes irrécupérables, et
quelqu'un se séparait de son téléphone en le croyant.

⚠️ Le contrôle porte sur la **présence** d'une valeur, pas sur `hasKek()`, qui exige en plus la
bonne longueur : une KEK survivante mais corrompue passerait pour une absence.

### Après un effacement INCOMPLET, la panique n'était plus rejouable — commit `333aba1`

`_running` passait à `true` au déclenchement et n'était jamais remis à `false` sur ce chemin. Il
désactive la tuile et y laisse un indicateur d'activité : l'utilisateur lisait « effacement
INCOMPLET, vérifiez avant de vous séparer de l'appareil » devant un bouton devenu inerte, qui tourne
indéfiniment. **Le seul moment où il voudrait réessayer, et le seul où il ne pouvait pas.**

Sur le chemin nominal la question ne se posait pas : l'écran disparaît.

### Le menu de tri — commit `24bc67e`

Cf. la section précédente, point 1.

## Divergence assumée, et dans le bon sens

**La branche `=1` des pluriels.** ICU `=1` ne vaut que pour 1 ; la catégorie CLDR `one` du français
couvre aussi zéro. Le « 1 » écrit en dur dans l'ARB devient donc l'argument : le portage affiche
« 0 note a perdu… », singulier avec zéro, ce qui est la règle française. La version Flutter y
affiche « 0 notes ont perdu… ». **L'anglais est rigoureusement identique** dans les deux, `one` n'y
valant que pour 1.
