# Parité fonctionnelle 2.0.3 → 3.0.0

> Se remplit **au fil des phases 5-7**, pas à la fin. Une case cochée veut dire « vérifié sur
> appareil », pas « le code existe ».
>
> La bascule (phase 8) est bloquée tant qu'une ligne reste vide sans justification écrite.

---

## Écrans (10)

| Écran Flutter | Lignes | Kotlin | Vérifié | Notes |
|---|---:|---|:---:|---|
| `splash_screen.dart` | 259 | | ☐ | Signature Files Tech ; masque l'acquisition de la KEK |
| `home_screen.dart` | 564 | | ☐ | Bannière brouillons perdus (`vault_lost_drafts`) |
| `note_editor_screen.dart` | 1 123 | | ☐ | Auto-sauvegarde 500 ms, backlinks, autocomplétion `[[…]]` |
| `search_screen.dart` | 142 | | ☐ | FTS5, anti-rebond 200 ms |
| `trash_screen.dart` | 263 | | ☐ | Rétention 30 jours |
| `settings_screen.dart` | 802 | | ☐ | Thème, tri, fenêtre sécurisée, langue, auto-verrouillage |
| `about_screen.dart` | 622 | | ☐ | Version lue dynamiquement via `PackageInfo` |
| `mentions_legales_screen.dart` | 131 | | ☐ | Rend `PRIVACY.{fr,en}.md` / `TERMS.{fr,en}.md` |
| `voice_setup_screen.dart` | 465 | | ☐ | Phase 7 |
| `panic_complete_screen.dart` | 143 | | ☐ | Écran terminal du mode panique |

## Composants (16)

| Composant Flutter | Lignes | Kotlin | Vérifié |
|---|---:|---|:---:|
| `vault_pin_sheets.dart` | 857 | | ☐ |
| `folders_drawer.dart` | 780 | | ☐ |
| `voice_recording_overlay.dart` | 460 | | ☐ |
| `vault_passphrase_sheets.dart` | 382 | | ☐ |
| `folder_dialogs.dart` | 225 | | ☐ |
| `note_card.dart` | 222 | | ☐ |
| `backlinks_panel.dart` | 208 | | ☐ |
| `link_autocomplete_sheet.dart` | 206 | | ☐ |
| `panic_confirm_dialog.dart` | 167 | | ☐ |
| `move_to_folder_sheet.dart` | 165 | | ☐ |
| `passphrase_text_field.dart` | 105 | | ☐ |
| `voice_record_button.dart` | 85 | | ☐ |
| `blocking_progress_dialog.dart` | 51 | | ☐ |
| `empty_state.dart` | 50 | | ☐ |
| `vault_warning_banner.dart` | 43 | | ☐ |
| `sheet_handle.dart` | 27 | | ☐ |

## Services transverses

| Service | Lignes | Vérifié | Point de vigilance |
|---|---:|:---:|---|
| `folder_vault_service.dart` | 1 582 | ☐ | Ouvrir un coffre **créé par la version Flutter** |
| `note_export_service.dart` | 519 | ☐ | Export `.md` d'une note de coffre — le corps ne doit pas être vide |
| `panic_service.dart` | 478 | ☐ | Ordre des étapes ; un rapport ne doit jamais mentir sur un effacement |
| `voice_service.dart` | 450 | ☐ | Phase 7 |
| `backlinks_service.dart` | 401 | ☐ | Plafond de balayage 50 ko |
| `keystore_bridge.dart` | 207 | ☐ | Repris depuis `KeystoreBridge.kt`, sans MethodChannel |
| `secure_window_service.dart` | 103 | ☐ | `FLAG_SECURE` avec compteur de références |
| `settings_service.dart` | 116 | ☐ | Lire les clés `flutter.*` existantes |

## Promesses publiques à ne pas casser

Ce ne sont pas des fonctionnalités mais des engagements affichés sur files-tech.com et dans les
métadonnées F-Droid. Une régression ici est publique.

| Promesse | Contrôle | Vérifié |
|---|---|:---:|
| Zéro permission Internet | Contrôle CI sur le manifeste **fusionné** | ☐ |
| 100 % local, aucune donnée ne sort | Absence de toute dépendance réseau | ☐ |
| Base chiffrée au repos | SQLCipher, clé scellée par le Keystore | ☐ |
| Coffres par dossier | Argon2id + AES-256-GCM, paramètres identiques | ☐ |
| Dictée vocale sur l'appareil | Phase 7 | ☐ |

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
| 2.0.3 → 2.0.4 → 3.0.0 (chemin nominal, couche ①) | ☐ | **La passerelle 2.0.4 n'existe pas.** Sa publication demande la clé de signature et une décision de Patrice, et `notes_tech` est gelé — cf. `10-PASSERELLE-2.0.4.md` §7. Rien à tester tant qu'elle n'est pas écrite. |
| 2.0.3 → 3.0.0 direct (couche ② de secours) | ◐ | **Mécanisme prouvé, bout-en-bout non.** `FlutterSecureStorageKekSourceTest` (12 cas) rejoue une valeur écrite **exactement comme la bibliothèque l'écrit**, vérifie que MGF1-SHA1 est imposé par la plateforme, que l'alias visé est celui de l'application publiée, et que la lecture **ne modifie pas** le stockage. Ce qui manque n'est pas le code : c'est la lecture du vrai stockage d'une vraie installation, qui n'arrive qu'à la bascule. |
| KEK introuvable, base présente → refus, **base intacte après** (couche ③) | ✅ | `LegacyDatabaseOpeningTest.sans_kek_et_avec_une_base_presente_l_ouverture_est_refusee_et_la_base_intacte`, doublé côté logique par `KekRepositoryTest.aucune cle et une base presente donne un refus, et AUCUNE ecriture`. **Le refus ET l'intégrité après refus sont tous deux vérifiés.** |
| Installation neuve | ✅ | `KekRepositoryTest.aucune cle et aucune base declenche une generation persistee AVANT d'etre rendue` — l'ordre compte : une clé rendue avant d'être persistée chiffrerait des notes sous une clé que le redémarrage suivant ne retrouverait pas. |
| Coffre passphrase créé en Flutter, ouvert en Kotlin | ✅ | `FolderVaultServiceTest.la_cle_dun_coffre_ecrit_par_flutter_souvre_depuis_kotlin` — les quatre colonnes `vault_*` **et** le blob de note viennent du vrai code Dart de la 2.0.3, recoupés contre OpenSSL et l'Argon2 de référence. ⚠️ Octets authentiques, mais **pas pris sur le téléphone d'un utilisateur**. |
| Coffre PIN créé en Flutter, ouvert en Kotlin | ◐ | **Irréproductible hors bascule, et ce n'est pas un manque de rigueur.** Le scellement extérieur d'un coffre à code est fait par une clé Keystore **liée à l'appareil et à l'UID** : aucun vecteur ne peut la rejouer. Ce qui **entre** dans le Keystore est vérifié octet pour octet (`PariteCoffreAvecFlutterTest.lesCouchesInternesDuCoffrePinConcordent`) ; l'enveloppe ne peut l'être qu'avec le vrai Keystore de la vraie installation. |
| Auto-effacement interrompu (`vault_wipe_pending_*`) repris au démarrage | ✅ | `FolderVaultServiceTest.un_effacement_interrompu_est_repris_au_demarrage` — le drapeau posé, l'application tuée, la reprise détruit bien les notes **et** retire le matériel de coffre. Complété par `le_drapeau_dun_dossier_disparu_est_retire`, qui vérifie que c'est le **seul** retrait légitime : retirer le drapeau sur échec permettrait de sauver un coffre condamné en provoquant un plantage. |

**Reste donc trois lignes, et elles tiennent en une phrase** : la couche ① attend une décision de
publication, les couches ② et PIN attendent le seul geste qui ne se simule pas — installer par-dessus
une vraie installation. Ce sont les trois cases du geste de bascule, pas des cases de développement.

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


## Écarts relevés dans l'application publiée — REPRODUITS, à trancher par Patrice

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

### 2. 🟢 « Toutes les notes » inclut les archives, un dossier non

`notes_dao.dart:156` filtre sur `trashed_at IS NULL` seul ; `listByFolder` (ligne 59) ajoute
`archived = 0`. Une note archivée disparaît donc de son dossier et reste dans « toutes les notes ».

**Invisible aujourd'hui** : aucun écran de la 2.0.3 ne permet d'archiver une note — le seul
`archive` de `lib/ui/` est une icône d'export. La colonne vaut `0` partout.

### 3. 🟢 Renommer ou supprimer un dossier n'affiche aucun message

Et c'est défendable : le tiroir se met à jour sous les yeux de l'utilisateur. Noté parce que la
tentation d'ajouter « Dossier renommé » était forte, et qu'y céder aurait créé deux clés i18n que la
version Flutter n'a pas.

### 4. ℹ️ Pluriel français : la catégorie CLDR `many` n'est pas définie

`lintDebug` la signale sur les trois `<plurals>`. Elle ne vaut qu'à partir d'un million, et Android
retombe sur `other` quand elle manque — le comportement est donc correct. La définir demanderait une
forme grammaticale (« un million **de** notes ») qui ne sera jamais atteinte.

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
