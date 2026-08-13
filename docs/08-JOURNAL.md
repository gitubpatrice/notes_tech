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
