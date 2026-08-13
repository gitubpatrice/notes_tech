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
| Tests instrumentés, Galaxy S9 (API 29) | **19**, 0 échec |
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

**Bilan : 12 constats recevables, 11 corrigés, 1 réfuté par la mesure.**

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
