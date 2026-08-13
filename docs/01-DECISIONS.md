# Décisions

> Une décision structurante s'écrit **au moment où elle est prise**, avec ce qui a été écarté et
> pourquoi. Une décision non écrite se re-discute tous les mois et finit par se retourner.
>
> Format : contexte → décision → ce qui a été écarté → conséquences.
> Une décision ne se modifie pas : on en écrit une nouvelle qui remplace l'ancienne.

---

## D-001 — Réécriture complète, publiée comme mise à jour de Notes Tech

**2026-08-13 · acceptée**

**Contexte.** Notes Tech 2.0.3 est publiée et installée. Le portage Kotlin doit atterrir chez les
utilisateurs existants sans leur faire perdre leurs notes.

**Décision.** Réécriture complète dans `notes_files_tech`, en conservant
`applicationId = com.filestech.notes_tech` et **le même keystore de signature**. Publication comme
mise à jour (3.0.0) quand la parité est atteinte.

**Écarté.**

- *Application séparée* (nouvel `applicationId`) : fragmente les utilisateurs entre deux apps,
  perd la continuité de la MR F-Droid `!37885`, et laisse deux bases de code à maintenir.
- *Migration incrémentale hybride* (add-to-app, écrans Kotlin injectés dans la coque Flutter) :
  les deux runtimes dans l'APK, et un pont `MethodChannel` à maintenir pour chaque état partagé.
  Le pire des deux mondes.

**Conséquences.**

- Le keystore de `notes_tech` est **obligatoire** pour publier. Changer de clé de signature
  romprait le chemin de mise à jour de toutes les installations existantes.
- `notes_tech` (Flutter) est gelé pendant le chantier, hors correctif critique.

---

## D-002 — La dictée vocale reste dans le périmètre de la 3.0.0

**2026-08-13 · acceptée**

**Contexte.** `files_tech_voice` repose sur `whisper_ggml_plus` (FFI vers whisper.cpp), plugin
Flutter sans équivalent Kotlin prêt à l'emploi. C'est le deuxième point dur du portage.

**Décision.** La fonctionnalité **reste dans le périmètre** : la retirer serait une régression
fonctionnelle vis-à-vis de la 2.0.3. Son *implémentation* est reportée en phase 7, et elle est
isolée dès la phase 1 derrière une interface de domaine `SpeechToText`, pour que son absence
temporaire ne contamine aucune autre couche.

**Écarté.**

- *`android.speech.SpeechRecognizer` en mode hors-ligne* : délègue au service de reconnaissance de
  Google. Casse la promesse publique « 100 % local, zéro permission Internet », qui est le cœur de
  l'identité de l'application. Non négociable.
- *Retirer la dictée en 3.0.0 et la réintroduire ensuite* : une mise à jour qui **enlève** une
  fonctionnalité annoncée n'est pas une mise à jour acceptable.

**Conséquences.** whisper.cpp devra être intégré en JNI/NDK (CMake + ABI splits). C'est le lot le
plus incertain du projet en charge de travail ; il est donc placé après la parité fonctionnelle du
reste, pas avant.

---

## D-003 — La base reste dans `app_flutter/`

**2026-08-13 · acceptée**

**Contexte.** La base héritée est dans `app_flutter/notes_tech.db` et non dans le répertoire
`databases/` où Room la place par défaut (cf. [02-SCHEMA-HERITE.md](02-SCHEMA-HERITE.md) §1).
Le nom du répertoire est un vestige de Flutter.

**Décision.** **Ne pas déplacer le fichier.** Room reçoit le chemin absolu.
`Context.getDatabasePath(name)` renvoie `new File(name)` quand `name` est absolu — le mécanisme
est officiel, pas un contournement.

**Écarté.** *Déplacer vers `databases/` au premier lancement* : suppose de fermer proprement la
base, de rabattre le WAL, puis de déplacer trois fichiers (`.db`, `-wal`, `-shm`) de façon
atomique, et de survivre à une interruption entre les deux. Le bénéfice est **cosmétique** ; le
risque est la perte totale des notes.

**Conséquences.** Un répertoire nommé `app_flutter` subsiste dans une application sans Flutter.
C'est laid et c'est assumé — la constante qui porte ce chemin explique pourquoi, pour qu'un
lecteur futur ne « nettoie » pas ça un jour de rangement.

Un déplacement reste possible plus tard, comme lot dédié et testé. Il est **différé, pas oublié**.

---

## D-004 — La clé SQLCipher se transmet en `x'<hex>'`, jamais en octets bruts

**2026-08-13 · acceptée**

**Contexte.** Côté Flutter, `database.dart:463` compose `x'<64 hex>'` et le passe en `String`.
SQLCipher détecte ce motif dans le matériel de clé (longueur 67, préfixe `x'`, corps hexadécimal)
et l'utilise comme **clé brute** — aucune dérivation.

**Décision.** Côté Kotlin, `SupportOpenHelperFactory` reçoit les **67 octets ASCII** de cette même
chaîne.

**Écarté.** *Passer les 32 octets bruts de la KEK.* SQLCipher les traiterait comme une passphrase
et les ferait passer par PBKDF2-HMAC-SHA512 (256 000 itérations en compatibilité 4). La clé
obtenue n'aurait rien à voir avec celle du fichier : **la base ne s'ouvrirait pas**.

**Conséquences.** `SqlCipherRawKey` est le seul endroit du code qui compose ce format, et il est
couvert par un test JVM sur la longueur (67) et l'encodage. Le test instrumenté de la phase 2 est
ce qui le prouve pour de bon.

---

## D-005 — Room adopte la base héritée par validation de schéma

**2026-08-13 · acceptée**

**Contexte.** La base a `user_version = 9` mais **pas** de `room_master_table` — elle a été créée
par sqflite. Room a besoin de son empreinte d'identité pour s'ouvrir.

**Décision.** `@Database(version = 9)`, entités décalquées au caractère près. Room emprunte alors
son chemin « base pré-empaquetée » : pas de `room_master_table` ⇒ il appelle `onValidateSchema`,
et s'il valide, il écrit lui-même son empreinte.

**Écarté.** *Déclarer la version 10 avec une migration 9→10 vide.* Fonctionne aussi, mais ajoute
un palier de migration fictif que personne ne saura interpréter dans deux ans.

**Conséquences — et c'est le bénéfice principal.** La validation de schéma de Room devient une
**preuve vérifiée par la machine** que les entités Kotlin décrivent exactement la base réelle.
Une divergence de type, de nullabilité, de valeur par défaut, d'index ou de clé étrangère fait
échouer l'ouverture avec un message `Expected: … Found: …`.

⚠️ Corollaire : **tous** les index doivent être déclarés, y compris ceux qu'on n'utilise pas.
Room compare l'ensemble complet et un index non déclaré fait échouer la validation.

---

## D-006 — Acquisition de la KEK en trois couches, jamais de repli silencieux

**2026-08-13 · acceptée**

Détail complet dans [03-KEK-ACQUISITION.md](03-KEK-ACQUISITION.md).

**Décision.** Trois couches, dans cet ordre : ① alias natif du Keystore écrit par la release
passerelle 2.0.4 ; ② lecture directe du format `flutter_secure_storage` (secours) ; ③ **refus
d'ouvrir**, avec un écran qui explique quoi faire.

**Règle dure.** Si aucune couche ne rend une KEK **alors que `notes_tech.db` existe sur le
disque**, l'application refuse de démarrer. Elle ne génère **jamais** une KEK fraîche.

**Écarté.** *Générer une KEK neuve quand aucune n'est trouvée.* C'est ce que fait naturellement un
`getOrCreate`, et ce serait la destruction silencieuse et définitive de toutes les notes : la base
resterait sur le disque, chiffrée par une clé que plus personne ne possède. Le code Dart mettait
déjà en garde contre exactement ça (`vault_service.dart:53-60`, `resetOnError: false`).

---

## D-008 — Le portage ne remplace rien tant qu'il n'est pas prouvé sûr

**2026-08-13 · acceptée · précise D-001**

**Contexte.** D-001 fixe l'objectif : publier le portage comme mise à jour de Notes Tech. Mais
`applicationId = com.filestech.notes_tech` a un effet **immédiat** : dès la première build
signée installée, l'application Flutter réelle est écrasée et le portage ouvre les vraies notes.
Pendant tout le développement, c'est exactement ce qu'il ne faut pas.

**Décision.** L'`applicationId` par défaut porte le suffixe `.next`. Prendre la place de
l'application installée demande un geste explicite :
`-Pnotestech.replaceInstalledApp=true`.

Détail et protocole dans
[06-ISOLATION-PENDANT-LE-CHANTIER.md](06-ISOLATION-PENDANT-LE-CHANTIER.md).

**Écarté.**

- *Se contenter de la discipline* (« on n'installe pas la release sur le téléphone réel »). Une
  règle qu'un `./gradlew installRelease` distrait suffit à violer n'est pas une protection.
- *Un dépôt séparé pour la version de développement.* Deux bases de code à synchroniser, pour un
  résultat qu'une propriété Gradle obtient.

**Conséquences — et il faut les regarder en face.**

- ✅ L'isolation devient une propriété du système : UID distinct, donc **aucun accès possible** à
  la base, aux `SharedPreferences` ni aux clés Keystore de l'application réelle.
- ⚠️ **En contrepartie, la build isolée ne peut pas exercer les couches ① et ② de l'acquisition de
  la KEK** — elle n'a pas accès à ce qu'elles doivent lire. Ces deux couches ne se vérifient qu'à
  la bascule, sur le S9, avec le protocole de
  [03-KEK-ACQUISITION.md](03-KEK-ACQUISITION.md) §4. Ne pas laisser croire qu'une build isolée
  verte prouve la migration : elle prouve tout le reste.
- Les deux applications cohabitent dans le lanceur avec des libellés distincts. Deux entrées
  identiques mènent tôt ou tard à tester la mauvaise.

---

## D-007 — Le paquet Kotlin reste `com.filestech.notes_tech`

**2026-08-13 · acceptée**

**Contexte.** Le dossier de travail s'appelle `notes_files_tech`.

**Décision.** `namespace` = `applicationId` = **`com.filestech.notes_tech`**. Le nom du dossier
n'a aucune incidence.

**Écarté.** *Renommer le paquet en `com.filestech.notes_files_tech`* : déplacerait
`/data/data/<paquet>/`, donc la base, les `SharedPreferences` et les alias Keystore des coffres.
Équivalent à une désinstallation.

**Conséquences.** Le paquet garde un tiret bas, contraire à la convention Kotlin. C'est déjà le
cas d'`agenda_tech` et de la coque Flutter existante : la cohérence du portefeuille et la
survie des données priment sur la convention de nommage.
