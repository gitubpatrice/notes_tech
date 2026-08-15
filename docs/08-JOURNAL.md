# Journal du portage

> Une entrée par session de travail. On y écrit **ce qui a été fait, ce qui a été mesuré, et ce qui
> reste ouvert** — pas un résumé de code, que `git log` fait mieux.
>
> Ce qui compte ici : les décisions prises en cours de route, les fausses pistes, et les résultats
> de mesures qu'on ne pourra pas reproduire sans refaire tout le travail.

---

## 2026-08-13 — Fondation, phases 1 et 2

**Point de départ** : dossier `notes_files_tech` vide. Source : `notes_tech` en Flutter,
version 2.0.3 publiée (versionCode 51), commit `7180a2c`.

**Point d'arrivée** : socle complet, couche d'accès à la base héritée écrite, relue **quatre fois**
(trois relecteurs externes + un audit interne), et **prouvée sur appareil**. Le critère de sortie de
la phase 2 est atteint.

### Ce qui a été établi avant d'écrire une ligne

Quatre faits qui décidaient de la faisabilité, tous **relevés dans les sources**, aucun déduit :

| Fait | Valeur | Où |
|---|---|---|
| Emplacement de la base | `app_flutter/notes_tech.db`, **pas** `databases/` | `database.dart:75` |
| Format de clé SQLCipher | `x'<64 hex>'`, clé **brute**, 67 octets ASCII | `database.dart:463` |
| Artefact SQLCipher côté Flutter | `net.zetetic:sqlcipher-android:4.10.0` | `sqflite_sqlcipher/android/build.gradle:44` |
| Plancher d'API | `minSdk 24`, `targetSdk 36` | manifeste **fusionné** de la 2.0.3 |

Le dernier point a évité une régression silencieuse : le socle Kotlin du portefeuille est à
`minSdk 26`, et l'aligner dessus aurait **exclu les utilisateurs Android 7** d'une mise à jour.

### La décision qui a changé en cours de route

Le portage devait porter `applicationId = com.filestech.notes_tech` — donc **écraser** l'application
Flutter réelle dès la première build signée installée. Ce n'est pas acceptable tant que le portage
n'est pas prouvé.

D'où **D-008** : suffixe `.next` par défaut, prise de place sur `-Pnotestech.replaceInstalledApp=true`.
L'isolation devient une propriété du système d'exploitation — UID distinct, donc aucun accès
possible à la base, aux préférences ni aux clés Keystore de l'application réelle.

⚠️ **Contrepartie à ne pas oublier** : la build isolée ne peut pas exercer les couches ① et ② de
l'acquisition de la KEK. Une build isolée verte ne prouve **pas** la migration.

### Mesures — ce qui a réellement été vérifié

| Vérification | Résultat |
|---|---|
| Compilation `assembleDebug` | verte |
| Tests JVM | **37**, 0 échec |
| Tests instrumentés, Galaxy S9 (API 29) | **20**, 0 échec |
| Schéma Room vs DDL hérité, comparaison mécanique | **aucune divergence** |
| Manifeste fusionné release, permissions | aucune permission réseau |
| Garde-fou « zéro réseau », contrôles négatifs | **3/3 échouent correctement** |

Le test instrumentés exerce d'un bout à l'autre : format de clé brute, adoption par Room d'une base
sans `room_master_table`, validation de schéma contre le DDL réel, réponse de l'index FTS5,
masquage des notes verrouillées, et absence de changement de `rowid` sur mise à jour.

Deux contrôles ont une valeur particulière parce qu'ils **peuvent échouer** :

- ouvrir la base avec les 32 octets bruts au lieu de `x'<hex>'` doit **échouer** — sinon le format
  n'aurait aucune importance et le test nominal ne prouverait rien ;
- après avoir délibérément fait fuiter le titre d'une note verrouillée dans l'index, la recherche
  doit **quand même** ne rien rendre.

### Relectures

Quatre passes indépendantes : Gemini 3.1 Pro et GPT-5.2 sur le noyau, GPT-5.2 sur les DAO, puis un
audit `data-room` interne. Détail et tri dans [07-RELECTURES.md](07-RELECTURES.md).

**Bilan : 16 constats recevables, 15 corrigés, 1 réfuté par la mesure.**

⚠️ **La leçon la plus utile de la journée** : l'audit interne avait accès au **vrai code Flutter**,
ce que les relecteurs externes n'avaient pas. Il a trouvé cinq écarts de **parité** — dont un
critique — que personne d'autre ne pouvait voir, parce qu'ils sont invisibles à qui juge le code
Kotlin dans l'absolu. Sur un portage, le relecteur qui a la source d'origine sous les yeux voit une
classe de défauts entière que les autres manquent.

Le plus grave de tous : le portage n'exposait qu'une écriture de ligne entière, par laquelle une
note de coffre perd sa protection sur un tap d'épinglage. **L'incident avait déjà eu lieu dans
l'application publiée**, Flutter l'avait corrigé par des écritures ciblées, et le portage n'avait
repris que le chemin dangereux — l'avertissement s'était perdu en route.

Le plus sérieux — deux KEK générées en parallèle à la première installation, la seconde écrasant la
première, et une base chiffrée par une clé persistée nulle part — n'était **pas atteignable** au
moment du constat, l'unique appelant sérialisant déjà les appels. Corrigé quand même : une classe
dont la sûreté dépend d'une propriété d'une *autre* classe se remet en défaut au premier appelant
suivant, sans que rien ne le signale.

Un constat était **faux** : GPT soupçonnait `WHERE notes_fts MATCH ?` d'être une erreur de syntaxe,
la table étant aliasée `f`. FTS5 expose une colonne cachée portant le nom de la table ; le nom
résout comme colonne. Les tests le prouvent sur SQLCipher réel. La « correction » aurait
consisté à réécrire une requête qui fonctionne.

### Ce qui a résisté

**ktlint et detekt se disputaient le formatage.** Deux outils, le même travail, des exigences
contradictoires sur les mêmes fichiers : le style `ktlint_official` réécrivait 660 lignes de code
déjà relu, et detekt refusait ensuite le résultat.

Tranché en amont plutôt que rustiné : **ktlint possède le style** (`.editorconfig`, style
`intellij_idea`), **detekt ne fait plus que du fond** (jeu de règles `formatting` désactivé). Le
reformatage est retombé à 61 lignes.

⚠️ Le greffon `detekt-formatting` reste déclaré bien qu'inactif : detekt valide son fichier de
configuration contre les jeux de règles chargés, et sans le greffon la section `formatting:`
devient « propriété inexistante ». La tâche échouait avant même d'analyser quoi que ce soit.

Deux règles ktlint sont désactivées **délibérément**, et l'une n'est pas négociable :
`package-name` — le paquet `com.filestech.notes_tech` porte un tiret bas, et le renommer
déplacerait les données de chaque utilisateur.

### Ce qui reste ouvert

| Sujet | État |
|---|---|
| Couche ② de l'acquisition de la KEK (lecture `flutter_secure_storage`) | conçue et documentée, **pas écrite** |
| Release passerelle 2.0.4 côté Flutter | **pas écrite** — contrat de format dans `KeystoreSealedKekSource` |
| Crypto des coffres (Argon2id, AES-GCM, Keystore) | phase 4 |
| Interface Compose | phase 5, seul l'écran d'échec existe |
| Dictée vocale (whisper.cpp en JNI) | phase 7, le lot le plus incertain |

Un point mineur relevé par la relecture des DAO, à traiter quand l'interface du coffre existera :
le compteur de tentatives PIN s'incrémente correctement en base, mais la **relecture** qui suit est
une seconde requête — sous concurrence, elle peut afficher « tentative 2/5 » après le premier échec.
Pas permissif, seulement surprenant.

---

## 2026-08-13 — Phase 3 : le domaine, les repositories, et la clé qui apparie les liens

### Ce qui a été fait

La couche qui manquait entre les DAO et l'interface : modèles de domaine, repositories, et surtout
la fonction de normalisation dont dépend l'appariement des rétroliens entre les deux versions de
l'application.

### La décision qui structure tout le reste

**Écrire une note et réindexer ses liens sont une seule transaction** (D-009). L'application
publiée fait autrement — un événement, un service abonné, une demi-seconde de temporisation — et le
code Flutter porte les cicatrices de ce détour : un cache d'index avec durée de vie, une horloge
monotone contre un appareil dont on recule l'heure, une invalidation explicite au renommage, un
compteur de génération contre une course. Cinq mécanismes, dont trois ajoutés après coup pour
corriger des défauts que le précédent avait laissés.

La transaction ne les remplace pas par un mécanisme plus habile : elle rend la question sans objet.

Conséquence directe : `NoteChange` n'est pas porté, et `FoldersRepository.isVaultFolder` n'a pas de
cache. Le prédicat est lu **dans la transaction qui écrit** ; il ne peut pas être périmé.

### Le point le plus instructif : trois divergences Dart / Java, mesurées

`target_title_norm` est une **clé d'appariement partagée** — les deux versions écrivent dans la même
base. J'allais porter `normalizeTitle` en supposant que `lowercase()`, `trim()` et `\s` se
comportent pareil des deux côtés.

Le SDK Flutter était installé. J'ai exécuté le vrai code Dart sur un corpus de piégeage plutôt que
de parier :

| Ce que j'aurais écrit | Ce que Dart fait |
|---|---|
| `value.lowercase()` | casse **simple** : `ΟΔΟΣ` → `οδοσ`, pas `οδος` ; `İ` → un caractère, pas deux |
| `value.trim()` | élague `U+0085`, **pas** `U+001F` — l'inverse de Kotlin |
| `Regex("\s+")` | espaces Unicode, dont `U+202F`, omniprésente en français |

Les 92 vecteurs produits sont rejoués à chaque `testDebugUnitTest`. Deux d'entre eux divergent
sciemment — la table de casse de Dart ignore l'osage et l'adlam — et la liste des dispenses est
**fermée dans les deux sens** : une dispense qui cesserait d'être nécessaire fait échouer le test.

⚠️ `notes_tech` est resté intact : le fichier de test temporaire a été supprimé, `git status`
vérifié.

### Quatre défauts trouvés, et par quoi

- **Un test qui affirmait un garde-fou** a montré que l'anti-auto-lien était défait par la requête
  suivante, dans la même transaction. L'application publiée a exactement ce défaut. Le motif —
  *un garde-fou posé dans une étape n'engage que cette étape* — est dans `04-PIEGES.md` §13.
- **Écrire la couche domaine** a fait apparaître que `findVaults` identifiait un coffre par
  `vault_mode`, une colonne rétro-remplie par une migration, au lieu de `vault_salt`.
- **Comparer avec le Dart** a montré que `backlinks` manquait les liens fantômes : un lien écrit
  avant sa cible n'apparaissait pas dans ses rétroliens.
- **Relire mon propre correctif** a montré que conditionner la réaccroche des liens au changement de
  titre laissait passer le pire cas : une note verrouillée au format 1 garde son titre.

### Deux défauts trouvés par la relecture externe

Tous deux vérifiés avant d'être appliqués, tous deux réels :

1. `lockNote` perdait les étiquettes et la date de modification. La méthode servait deux appelants
   aux besoins opposés et n'en servait bien qu'un.
2. `deleteKeepingNotes` déplaçait les notes, constatait que la suppression était refusée, et
   **rendait `null`**. Rendre une valeur n'annule pas une transaction : la boîte de réception
   survivait vidée de tout son contenu.

Le second est le plus utile des deux, parce que son motif se généralise : *dans une transaction, un
refus qui se contente de rendre une valeur ne défait rien.*

### Ce que detekt a trouvé, et qui méritait mieux qu'un seuil relevé

27 méthodes dans `NoteDao`. Plutôt que d'assouplir la règle, les écritures sont parties dans
`NoteWriteDao`. Le bénéfice n'est pas cosmétique : c'est dans les écritures que se joue la protection
des coffres, et la surface entière tient maintenant dans un fichier court qui se vérifie d'un coup
d'œil.

Le même passage a fait apparaître que le motif de l'écriture pleine ligne existe aussi sur
`folders`, où la conséquence est pire : effacer `vault_kek_wrapped` rend **toutes** les notes d'un
coffre définitivement illisibles. `FolderDao` n'a plus de `@Update` (D-010).

### Mesures

| Vérification | Résultat |
|---|---|
| Tests instrumentés sur Galaxy S9 (API 29) | **48**, 0 échec |
| Tests JVM | **45**, 0 échec |
| `ktlintCheck`, `detekt`, `lintDebug` | verts |
| Schéma Room vs DDL hérité | aucune divergence |
| Contrôle « zéro réseau » | 1 permission, aucune réseau |

### Ce qui reste ouvert

| Sujet | État |
|---|---|
| Relecture GPT-5.2 du lot | le service a rendu `503` — **à relancer** |
| Couche ② de l'acquisition de la KEK | conçue et documentée, pas écrite |
| Release passerelle 2.0.4 côté Flutter | pas écrite |
| Crypto des coffres | phase 4 — `VaultSealer` refuse tout en attendant, délibérément |
| `excerpt`, `wordCount` et les libellés de tri | reportés en phase 5 avec l'écran qui les consomme |

### Seconde passe de relecture, le même jour

**Le lot venait d'être déclaré exempt de fuite de clair. La passe suivante en a trouvé une**, et les
deux relecteurs l'ont trouvée indépendamment : `deleteKeepingNotes` réassignait les notes d'un
dossier par un `UPDATE` en bloc, qui ne passe **ni** par le scellement, **ni** par la garde des
notes verrouillées. Vider un dossier ordinaire vers un coffre y déposait des notes en clair ; vider
un coffre ailleurs faisait survivre ses notes chiffrées à la clé supprimée avec le dossier.

La première passe n'avait pas tort : tous les chemins d'écriture **de note** étaient sûrs. Le trou
était dans un chemin d'écriture **de dossier**. Une conclusion de non-divulgation vaut pour la
surface explorée, jamais au-delà — c'est le piège §15.

GPT-5.5 a aussi trouvé le **jumeau asymétrique** `backlinks()` / `dangling()` : deux requêtes sur la
même table, une gardée contre les sources verrouillées, l'autre non.

Et il a posé la meilleure question de la journée, sans pouvoir y répondre : *les transactions
imbriquées de `NoteLinkWriter` sont-elles vraiment atomiques ?* Toute la décision D-009 en dépendait
et **rien ne l'avait vérifié**. Un test le mesure désormais. La réponse est oui — mais elle est
vérifiée, et elle le restera si Room ou le pilote changent.

Total de la phase : **13 défauts corrigés**, 55 tests instrumentés, 45 tests JVM.

---

## 2026-08-13 — Couche ② de la KEK : le format de `flutter_secure_storage`, lu et éprouvé

### Ce qui a décidé la méthode

Le S9 porte `com.filestech.notes_tech` en **2.0.1**, installée le 2026-08-07. Deux constats en ont
découlé, tous deux vérifiés et non supposés :

- `flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]` — **pas de `DEBUGGABLE`**, donc `run-as` est exclu et
  son stockage privé est illisible. Impossible de mesurer sur l'installation réelle.
- La version installée n'est pas la 2.0.3 publiée. Un utilisateur qui met à jour rarement passera
  directement de sa version à la 3.0.0 : la couche ② n'est pas un cas d'école.

La méthode retenue : lire les **sources Java de `flutter_secure_storage 10.3.1`**, présentes sur le
disque, et éprouver le portage contre une fixture qui écrit **exactement comme la bibliothèque** —
écrite elle aussi depuis ces sources, pas depuis mon lecteur. Sinon le test vérifierait que ma
transcription est cohérente avec elle-même.

### La constante qui justifiait de ne rien écrire de mémoire

`OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, …)` — condensat principal SHA-256,
MGF1 en **SHA-1**. Personne n'écrirait ça spontanément.

Le contre-test devait sceller avec MGF1-SHA256 et vérifier que la lecture échouait. **Il n'a même
pas pu sceller** : l'`AndroidKeyStore` refuse la combinaison à l'initialisation — *« Unsupported
MGF1 digest: SHA-256. Only SHA-1 supported »*.

Résultat plus fort que prévu : ce n'est pas un choix de la bibliothèque qu'on recopie, c'est la
seule valeur que la plateforme accepte. Une erreur y échouerait bruyamment, jamais en silence.

### Ce que la lecture des sources a apporté en plus

La bibliothèque **enregistre les algorithmes qu'elle a employés** (`FlutterSecureSAlgorithmKey`,
`FlutterSecureSAlgorithmStorage`). La couche ② les lit et **refuse** ce qu'elle ne sait pas traiter,
au lieu d'essayer une combinaison puis une autre. Un déchiffrement à l'aveugle pourrait rendre des
octets arbitraires qui passeraient le contrôle de longueur — et ouvriraient la base avec une clé
fausse.

### La promotion, qui fait de la couche ② un vrai secours

Une clé trouvée par la couche ② est **recopiée** dans la couche ①. Dès le premier démarrage réussi,
la transcription d'un format tiers sort du chemin critique. L'échec de la recopie ne fait jamais
échouer l'acquisition : la clé est déjà en main.

### Mesures

| Vérification | Résultat |
|---|---|
| Tests instrumentés sur Galaxy S9 (API 29) | **66**, 0 échec |
| Tests JVM | **47**, 0 échec |
| `ktlintCheck`, `detekt`, `lintDebug` | verts |

### ⚠️ Ce que ces tests ne prouvent PAS

Qu'un utilisateur récupérera sa clé. Ils prouvent que **le format se lit**. L'`AndroidKeyStore`
étant cloisonné par UID, la build isolée ne verra jamais le matériel de l'application publiée : cela
ne se vérifiera qu'à la bascule. Les deux affirmations sont différentes et une seule est démontrée.

### La passerelle 2.0.4, conçue et non appliquée

`docs/10-PASSERELLE-2.0.4.md`. `KeystoreBridge.kt` existe déjà côté Flutter et fait l'essentiel ; il
manque une méthode qui enchaîne création de clé, scellement et écriture des préférences.

🔴 **Le piège qui la rendrait inopérante sans que rien ne se voie** : `shared_preferences` préfixe
ses clés par `flutter.` et écrit dans son propre fichier. Une écriture Dart de `db_kek_v1.blob`
atterrirait en `flutter.db_kek_v1.blob` ailleurs, la couche ① ne trouverait rien, et la 2.0.4
aurait l'air d'avoir fonctionné. L'écriture doit être **native**.

Second piège : le clair scellé doit être les **32 octets bruts**, alors que `flutter_secure_storage`
contient les **64 caractères hexadécimaux**.

`notes_tech` n'a **pas** été modifié : publier une 2.0.4 demande la clé de signature et une décision.

## 2026-08-14 — Phase 4 : les coffres

**Cible de release fixée** : Kotlin 3.0.0 début septembre 2026. La 2.0.4 Flutter, elle, n'a **pas**
de date — elle attend la MR F-Droid !37885. Les deux horloges avaient été confondues dans une
première rédaction, corrigée le jour même.

**Écrit** : `security/vault/` (neuf fichiers), le provisionnement de coffre dans `FolderDao`, la
projection `VaultMaterial`, le branchement du scelleur réel, `VaultAutoLocker` et le verrouillage au
passage en arrière-plan.

**Prouvé** : 37 concordances contre une tierce implantation d'Argon2id et d'AES-GCM ; un coffre dont
les colonnes viennent du vrai Dart ouvert depuis Kotlin sur du vrai SQLCipher ; 70 tests JVM et 91
instrumentés sur le S9, gate qualité vert sans ligne de base.

**Pas prouvé, et écrit comme tel** : qu'un utilisateur réel rouvre son coffre. Pour le mode à code
c'est structurellement impossible avant la bascule — la clé du Keystore est liée à l'UID.

**Six défauts**, dont quatre relevés par deux relectures externes indépendantes et deux par mes
propres tests. **Cinq portaient sur le classement des échecs, aucun sur la cryptographie.**
Détail dans `07-RELECTURES.md` R-008, règles dans `01-DECISIONS.md` D-012 à D-014, motifs dans
`04-PIEGES.md` §21 à §24.

**Ce que la phase a appris** : le portage a deux natures de risque, et elles ne se relisent pas
pareil. Le **format** se ferme par des vecteurs pris sur le vrai Dart. Le **jugement** — ce que le
code conclut d'un échec — ne se ferme que par une relecture adversariale et par des tests qui
vérifient qu'il ne se passe *rien*. Un vecteur ne dira jamais qu'un coffre s'est détruit pour la
mauvaise raison.

## 2026-08-14 — phase 5, l'interface Compose

Deux commits : `db3682b` (i18n, préférences héritées, gestes de coffre différés) et `6751d17`
(les neuf écrans).

**Ce que la journée a appris, et qui vaut au-delà de cette phase :**

🔴 **Un contrôle vert n'est pas une preuve de fonctionnement.** ktlint, detekt, `lintDebug` et
70 tests JVM étaient verts sur une application qui se fermait au premier lancement. Le défaut —
`painterResource` sur une icône adaptative — n'existe même pas sur un appareil API 24-25, et ne se
manifeste qu'à la **première** installation. C'est la énième fois que ce sont les essais sur
appareil qui trouvent les vrais défauts, et la première où le gate complet était vert.

⚠️ **Un chiffre non vérifié survit à toutes les relectures.** Le plan annonçait 436 clés i18n
depuis le relevé initial. Il y en a 311. Personne ne l'avait mesuré, et le nombre a été recopié dans
trois documents.

⚠️ **Un format d'interopérabilité se LIT, il ne se devine pas.** `shared_preferences` écrit les
entiers Dart en `putLong`. Une lecture par `getInt` aurait fait planter le premier démarrage après
la bascule, sur le chemin des coffres, chez les seuls utilisateurs ayant changé le réglage
d'auto-verrouillage. La réponse était dans le source du greffon, à quinze lignes de lecture.

⚠️ **Vérifier une transposition dans le sens inverse.** Le script i18n produit du XML depuis l'ARB ;
un second script refait le chemin XML → ARB et confronte à la source. 628 segments, 4 divergences
expliquées, 0 inexpliquée. Relire le XML produit n'aurait rien prouvé — c'est le même raisonnement
qui l'a écrit.

⚠️ **Reproduire un défaut de l'application publiée est parfois le bon geste.** Deux écarts relevés
(menu de tri à libellés dupliqués, archives asymétriques) sont reproduits et consignés dans
`05-PARITE.md`. La parité est le critère de sortie de la phase 8 : un correctif silencieux est
indiscernable d'un défaut de portage le jour de la comparaison.

## 2026-08-15 — clôture de la phase 6 : neuf lots, et ce qu'ils ont appris

Neuf commits, `f71ad73` → `f1482b5`. Corbeille vidable, copier en Markdown, conversion en coffre qui
parle, classement des erreurs, tests, et deux passes de relecture externe.

⚠️⚠️ **Une chaîne traduite et jamais lue est un signal, pas un déchet.** Sur 82 orphelines, **82**
avaient un jumeau qui tourne dans l'application publiée, **zéro** n'était morte des deux côtés. Ce
que le journal appelait « décisions produit en attente » était du **comportement existant non
reporté** — dont une régression complète : « Vider la corbeille ».

⚠️⚠️ **Deux relecteurs, jamais un.** Sur quatre lots d'affilée, aucun des deux n'a tout vu, et sur
un axe donné l'un écrivait « RIEN TROUVÉ » là où l'autre trouvait un critique. Pire : **un correctif
issu de l'un a aggravé un cas vu par l'autre** — la relance sans borne du presse-papiers, bénigne
tant que « illisible » et « pas du texte » étaient confondus, devenait une boucle infinie gardant le
clair en mémoire une fois cette confusion levée.

⚠️⚠️ **Relire le DELTA ENTIER après avoir relu chaque lot.** Les cinq lots avaient été relus
séparément ; la passe sur le delta complet a trouvé **quatre défauts de plus**, dont deux qu'un seul
des deux relecteurs a vus.

⚠️ **Le raisonnement fautif vaut mieux que le correctif.** Le pire défaut du jour venait d'une phrase
plausible : « insister maintiendrait le texte en mémoire ici, ce qu'on cherche à éviter ». La mémoire
de l'application est isolée par le bac à sable ; le presse-papiers est **public**. *Une purge de la
copie sécurisée avait été préférée à une purge de la copie exposée.*

⚠️ **Un contrôle qui ne regarde pas l'artefact n'en dit rien.** Deux fois le même jour : une purge de
presse-papiers déclarée réussie sur la seule absence d'exception, et une vérification qui comparait à
un instantané parfois absent — donc qui passait **toujours**.

⚠️ **Trois défauts n'étaient visibles qu'à l'écran** : l'hôte de messages sous le tiroir, le titre
écrasé à zéro par une action de trop, et une confirmation qui ne s'affichait jamais. Aucune relecture
statique, aucun test du gate ne pouvait les voir.

Détail de chaque piège dans `04-PIEGES.md` §43 à §48 ; état des orphelines dans `05-PARITE.md`.

---

## 2026-08-15 (soir) — Consolidation : reprendre les constats non tranchés

Les deux rapports de relecture du delta contenaient encore des points non clos. Les reprendre un par
un a produit trois défauts réels, dont **deux que personne n'avait signalés** — ils sont apparus en
vérifiant les autres.

**Six constats repris.** Quatre étaient déjà corrigés dans le code (dont les deux CONFIRMÉS du
presse-papiers). Deux étaient réels :

- le `when` de `CreateVaultSheet` absorbait `VaultMode.UNKNOWN` par un `else` — un mode ajouté demain
  serait routé **en silence** vers la feuille de phrase secrète ; énuméré, il fait échouer le build ;
- quatre branches d'`IssueDUneAction` consommaient **avant** d'afficher, à rebours de la règle du
  dépôt. Elles fonctionnaient — la portée du message ne dépend pas de la clé de l'effet. C'est ce qui
  les rendait dangereuses : **une forme qui contredit la règle sans rien casser** attend le jour où
  un `await` s'intercale.

**Un constat écarté par l'analyse d'atteignabilité, pas par confort.** Les `null` muets de
`TrashViewModel` violent en apparence « un geste sans effet se signale ». Leur seule cause
atteignable est le double appui, où le premier a déjà parlé : annoncer un échec afficherait
« impossible de restaurer » **par-dessus** la restauration qui vient de réussir. Le silence est le
seul comportement qui ne ment pas — c'est écrit à côté du code, désormais.

**Le défaut le plus sérieux n'était dans aucun rapport tel quel.** En vérifiant une course que GPT
classait PROBABLE, j'ai trouvé qu'elle est réelle : annuler une conversion à l'instant de l'écriture
laisse un dossier **coffre**, sans session, tout son contenu en clair, et **rien ne le dit**. Le
correctif « propre » a été refusé après lecture du cycle de vie de la clé : il effacerait la clé
d'une session vivante. Cf. `11-COFFRES.md` §10.

> ⚠️ **Le correctif d'une course ne doit pas coûter plus cher que la course.** Échanger une fuite
> réparable contre une perte irréversible n'est pas un correctif.

**Et le plus instructif, trouvé par accident.** Ajouter une chaîne a demandé de relancer le
générateur d'i18n. Son `git diff` annonçait `13 insertions, 50 deletions` pour **une** chaîne ajoutée :
il venait d'effacer huit chaînes écrites à la main au fil des phases, dont deux du jour même. Le
mécanisme de conservation existait depuis la phase 1 et ne portait que l'écran de démarrage.

> ⚠️⚠️ **Un garde-fou qui existe ne protège que ce qu'on a pensé à lui confier.** Trois des huit
> chaînes perdues avaient été écrites *après* lui, par quelqu'un qui l'avait forcément vu.

Le générateur est maintenant **idempotent** — vérifié en le relançant, sortie identique octet pour
octet — et le contrôle est désormais de comparer les ensembles de noms de ressources avant/après, pas
de lire un diff. Cf. `04-PIEGES.md` §49.

⚠️ **Un outil qui rend 0 n'a pas forcément travaillé.** Les deux relectures externes de ce lot ont
« réussi » sans écrire un seul rapport : `--diff` attend une référence git, pas un chemin de fichier.
Même leçon que `cmd | tail`, qui rend le code de sortie de `tail`.

**Le lot a été relu deux fois, et le second tour a payé le plus.** Les deux relecteurs ont rendu des
constats disjoints au premier tour — l'un voyait un avertissement qui crie au loup, l'autre un
avertissement jeté. Au second tour, sur mes correctifs, **les deux ont confirmé le même défaut,
plus grave que les deux premiers** : j'avais déplacé `tache.cancel()` sous un tri, et les
déverrouillages n'étaient plus annulés du tout.

> 🔴 **Un correctif de relecture est du code neuf, et il vise mal ce qu'il ne regarde pas.** Celui-ci
> ne concernait que la création ; il a cassé le déverrouillage, qui traversait la même fonction.

⚠️ **Un constat externe se vérifie, même CONFIRMÉ.** Gemini annonçait une erreur de compilation sur
`echoue` — lambda non-`suspend` appelée avec des fonctions suspendues. C'est faux : la fonction est
`inline`, ce qui l'autorise, et le gate compile depuis toujours. **Le build est l'arbitre, pas le
rapport.**

⚠️ **Et un défaut trouvé sans relecteur** : `constat = viewModelScope.launch { … }` assigne le champ
*après* que le corps a commencé sur `Main.immediate`. Si le `finally` remet à zéro avant
l'affectation, le champ ne redescend plus jamais. Un booléen posé **avant** le `launch` supprime la
question. *Poser le garde après avoir ouvert la porte n'est pas poser un garde.*

**Bordures des actions de dialogue, et ce qu'elles ont révélé.** Demande de Patrice : un contour de
la couleur du texte sur chaque action proposée. Fait par un composant partagé, `ActionDeDialogue` —
douze boutons, pas douze copies — qui garde la largeur d'un `TextButton` pour qu'un changement
d'apparence ne devienne pas un changement de mise en page.

Le contour a rendu visible un défaut qui préexistait : dans « Supprimer le dossier ? », la troisième
action était **écrasée de moitié et coupée**. Et c'était l'irréversible. Cf. `04-PIEGES.md` §50.

⚠️ **J'ai proposé deux correctifs qui n'ont rien changé** — corps défilant, remplissage réduit —
avant de mesurer que la contrainte venait de l'emplacement de boutons lui-même. *Deux essais sans
effet ne demandent pas un troisième réglage, ils demandent un autre diagnostic.*

⚠️ **Un défaut d'affichage se constate à l'écran, mais se DIAGNOSTIQUE au relevé.** `uiautomator`
donnait `h=72` contre `h=144` dès le premier coup d'œil : la réponse était là avant les deux essais.

**Balayage des branchements, à la demande.** Les 21 sites d'appel de dialogue sont atteignables et
leur confirmation branchée ; zéro rappel vide, zéro `TODO`, aucune fonction ni propriété publique de
ViewModel sans lecteur, et sur huit `catch` sans sortie utilisateur, sept sont justifiés et documentés
— le huitième, `enArrierePlan`, est une ligne de partage assumée et écrite.

⚠️ **Mon premier détecteur de code mort a rendu 32 faux positifs** : il excluait les appels précédés
d'un point, c'est-à-dire la forme normale `viewModel.methode()`. Corrigé, puis **validé sur un témoin
vivant et un témoin inexistant** avant d'en tirer la moindre conclusion. Un instrument se calibre
avant de servir de preuve.

---

## 2026-08-15 (nuit) — Audit de l'export et du mode panique

Deux zones jamais auditées de la journée, choisies pour ce qu'elles risquent : l'export fait
**sortir** des données, la panique en **détruit**. Relecture externe croisée, puis vérification de
chaque constat dans le code.

**Cinq défauts réels, dont deux sur le chemin le plus sensible de l'application :**

1. **L'ordre de la séquence de panique** — le clair attendait derrière l'illisible. Cf. §51.
2. **Le message de fin mentait sur la nature du résidu** — « fichiers illisibles » là où il pouvait
   s'agir de notes lisibles. Cf. §52.
3. **Troisième jumeau asymétrique** entre `safeFileName` et `safeFolderName` : la troncature. Après
   la liste des noms réservés, puis le prédicat qui l'applique. *Deux fonctions qui doivent produire
   des noms sûrs se relisent ensemble — le commentaire de chaque correction précédente affirmait que
   la question était close.*
4. **`safeFolderName` jugeait une forme et en rendait une autre** : `estUnNomDeDossierUtilisable`
   ignorait les points finaux pour décider, la fonction renvoyait `Secret.` tel quel — refusé par
   Windows. *Valider une chaîne et en renvoyer une autre, c'est valider ce qu'on n'a pas contrôlé.*
5. **`File.delete()` dont le retour était jeté**, sur les deux chemins de rattrapage de l'export —
   c'est-à-dire précisément là où le rôle du code est de ne pas laisser de clair. Et
   `getUriForFile` **hors** du `try` : son échec laissait une archive complète et orpheline.

**Trois constats écartés après vérification** — et c'est aussi le travail :

- « la panique efface la mauvaise base » (le mot *Legacy* désignait l'emplacement hérité de Flutter,
  pas une base abandonnée) : `NotesDatabaseFactory` ouvre **exactement** ce fichier ;
- « le commentaire de `NoteArchive` ment sur le pic mémoire » : il dit que le publié garde le clair
  **en double** et qu'ici il ne l'est qu'une fois — c'est exact ;
- « le `catch (CancellationException)` de `etape` est un chemin mort » : il l'est, et il est
  documenté comme garde-fou volontaire.

> ⚠️ **Un audit externe se vérifie, y compris quand il est classé CONFIRMÉ.** Trois sur huit ne
> tenaient pas. Les appliquer sans lire aurait ajouté du bruit, et l'un d'eux aurait fait chercher un
> défaut de migration inexistant.
