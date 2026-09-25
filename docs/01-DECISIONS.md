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

> ⚠️⚠️ **Rectification du 2026-08-15.** Cette décision affirmait au passé que la dictée « est isolée
> dès la phase 1 derrière une interface de domaine `SpeechToText` ». **Cette interface n'existait
> pas** : le paquet `domain/` ne contenait que `export`, `links`, `model` et `repository`. La
> décision décrivait une isolation que le code ne portait pas — du même genre qu'un commentaire qui
> ment, un étage au-dessus, et d'autant plus trompeur qu'une décision se lit comme un acquis.
>
> Le contrat est posé depuis, **avant** le moteur : `domain/voice/SpeechToText.kt` et
> `domain/voice/SttErrors.kt`, avec le test de la barrière de chemin de `SttModel.fileName`. C'est
> l'ordre que la décision annonçait ; il est simplement tenu six semaines plus tard.
>
> **La leçon vaut au-delà de ce cas** : une décision au passé (« est isolée ») se vérifie comme une
> affirmation de code. Au futur (« sera isolée »), elle n'aurait trompé personne.

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

## D-009 — Écrire une note et indexer ses liens est **une seule transaction**

**Prise le** 2026-08-13, en écrivant `NotesRepository`.

### Décision

Toute écriture de note réindexe ses liens `[[Titre]]` dans la **même** transaction Room. Il n'existe
aucun service d'indexation séparé, aucun flux d'événements, aucun délai de temporisation.

### Ce que fait l'application publiée, et ce que ça lui a coûté

Le repository Flutter émet un `NoteChangeEvent`. `BacklinksService` y est abonné, temporise une
demi-seconde, puis réindexe. Ce détour a demandé, dans l'ordre où les défauts sont apparus :

| Mécanisme ajouté | Pour corriger quoi |
|---|---|
| Cache de l'index titre→identifiant | une lecture complète de la base à chaque frappe |
| Durée de vie de 5 s sur ce cache | un cache jamais rafraîchi |
| Invalidation explicite au renommage | des rétroliens qui pointaient sur un titre périmé |
| Horloge **monotone** au lieu de l'heure système | un appareil dont on recule l'heure figeait le cache |
| Compteur de génération + trois tours | une course où le cache répondait sur un état antérieur |

Le même détour a produit, côté dossiers, une garde de sécurité qui répondait « ce n'est pas un
coffre » pour un coffre qui venait d'être créé — relevé en critique par deux relectures externes
successives, dont la seconde portait sur le correctif de la première.

### Ce que la transaction supprime

Toutes ces lignes. Pas parce que le portage serait plus habile, mais parce que la question ne se
pose plus : il n'existe aucun instant où la note est écrite et ses liens ne le sont pas encore, ni
aucun intervalle pendant lequel une réponse pourrait vieillir.

### Ce qui remplace le flux d'événements

L'invalidation de Room. Écrire dans `notes` réveille les flux qui l'observent, y compris ceux qui
lisent `note_links` par `observedEntities` — les liens étant dérivés des notes, c'est exact et non
un contournement.

### Conséquence : `NoteChange` n'est pas porté

Le modèle `NoteChangeEvent` de la version Flutter existait pour prévenir trois écouteurs. Room les
prévient déjà. Le porter aurait produit un chemin mort, et un chemin mort est une invitation à s'en
servir.

### Ce qu'on accepte

Une écriture de note qui contient des liens lit la table des titres. La projection ne ramène que
`(id, title)` des notes vivantes non verrouillées, et le raccourci « aucun `[[` dans le texte »
l'évite entièrement — l'application publiée mesure que quatre notes sur cinq sont dans ce cas.

### Écarté

**Garder le flux d'événements pour l'interface.** Il ferait doublon avec l'invalidation de Room,
avec deux sources de rafraîchissement pouvant se contredire.

---

## D-010 — Aucune écriture de ligne entière, **ni sur `notes`, ni sur `folders`**

**Prise le** 2026-08-13, en écrivant les DAO d'écriture.

### Décision

`NoteWriteDao` et `FolderDao` n'exposent que des écritures **ciblées**. Ni `@Update`, ni conversion
`domaine → entité` utilisable pour une mise à jour.

### Le cas des notes était connu ; celui des dossiers ne l'était pas

L'incident de production porte sur les notes : épingler une note de coffre ouverte réécrivait la
ligne entière depuis l'éphémère déchiffrée, effaçait le blob, et détruisait la protection sans
signal. Ce portage ne se contente pas de l'avertissement qu'a ajouté la version Flutter : le geste
n'est pas exprimable.

**Le même motif existe sur `folders`, et sa conséquence est pire.** L'application publiée renomme un
dossier par un `UPDATE` complet construit depuis un objet `Folder` en mémoire
(`folders_dao.dart:66`), et cet objet porte les sept colonnes de coffre. Renommer un coffre depuis
une instance incomplète ou périmée y écrirait `NULL` dans `vault_kek_wrapped`.

Une note dont on efface le blob est perdue. **Un coffre dont on efface la clé enveloppée emporte
toutes ses notes**, et aucune saisie de la bonne phrase secrète ne les rendra : le matériel qui
permettait de les déchiffrer n'existe plus. Rien ne le signalerait avant la prochaine ouverture.

Aucun défaut n'a été constaté dans l'application publiée — les objets `Folder` qu'elle manipule
viennent de la base et portent bien leurs colonnes. La fragilité est structurelle, pas actuelle :
c'était aussi le cas des notes, jusqu'au jour où ça ne l'a plus été.

### Écarté

**Un commentaire d'avertissement au-dessus d'un `@Update`.** C'est exactement ce qu'a fait la
version Flutter pour les notes. Un avertissement qu'il faut se rappeler à chaque appel est un défaut
en attente d'un nouvel appelant.

---

## D-011 — Le scelleur de coffres **refuse** tant que la phase 4 n'a rien livré

**Prise le** 2026-08-13.

### Décision

`VaultSealer` est une interface du domaine. Son implémentation actuelle, `UnavailableVaultSealer`,
lève systématiquement. Écrire une note dans un dossier coffre échoue **bruyamment**.

### Pourquoi pas un bouchon neutre

Un bouchon permissif — qui rendrait la note inchangée — aurait exactement le comportement qu'on
cherche à rendre impossible : écrire en clair les notes d'un coffre. Et il ne se verrait pas,
puisque rien n'échouerait. Une écriture refusée se remarque à la première tentative ; une écriture
en clair ne se remarque jamais.

C'est le sens général du repli dans ce projet : `FoldersRepository.isVaultFolder` répond `true` pour
un dossier inconnu, pour la même raison. Au pire une opération légitime est refusée bruyamment ;
jamais un secret écrit en silence.

### Ce que l'interface règle en plus

La version Flutter câble le scellement **après** construction, par un passeur nullable, parce que le
service de coffres dépend déjà du repository et que l'injecter en retour créerait un cycle. Une
interface casse le cycle sans câblage tardif : le repository dépend d'un contrat, pas d'un service.

### À la livraison de la phase 4

Seule la liaison d'injection change. Le contrat reste, et `UnavailableVaultSealer` reste utile aux
tests qui doivent vérifier qu'un refus n'écrit rien.

---

## D-012 — Un échec ne compte que s'il **prouve** que l'utilisateur s'est trompé

**Prise le 2026-08-14, phase 4.**

Un coffre à code se détruit au cinquième échec. La question « qu'est-ce qu'un échec ? » devient donc
une question de perte de données, et elle se tranche une fois pour toutes ici plutôt qu'à chaque
point d'appel.

**Seules deux sorties consomment une tentative** : un code effectivement faux (`WrongPinException`)
et, sans objet, la destruction elle-même. Tout le reste la reprend — Keystore muet, base abîmée,
colonne de mauvaise longueur, fournisseur cryptographique récalcitrant, **annulation de la
coroutine**.

### Ce qui a été écarté

| Écarté | Pourquoi |
|---|---|
| Liste **noire** des erreurs qui ne comptent pas | c'est ce que faisait l'application publiée avant sa v1.0.3. Une mise à jour du système exposant un sous-type inattendu suffisait à détruire un coffre |
| Ne pas incrémenter avant la tentative | il suffirait alors de tuer l'application entre l'échec et l'écriture pour disposer d'essais illimités |
| Reprendre l'incrément sans `NonCancellable` | la reprise serait annulée avec le reste, et l'annulation est le cas le plus fréquent de la liste |

### La conséquence structurelle

**L'incrément et sa reprise vivent dans la même fonction.** Séparés — ne serait-ce que par une
relecture du compteur, qui est un point de suspension — il existe un instant où l'un a eu lieu et
l'autre est devenu inatteignable.

## D-013 — Ce que le coffre EST se lit dans ses colonnes, jamais dans son étiquette

**Prise le 2026-08-14, phase 4.** Prolonge la règle déjà posée pour `vault_salt`.

`vault_mode` n'existe que depuis la 0.9 et a été **rétro-remplie** par une migration.
`vault_pin_blob`, lui, n'est écrit que par la création d'un coffre à code. Le mode d'ouverture se
déduit donc du matériel présent, pas de l'étiquette.

Ce n'est pas une élégance : un `vault_mode` perdu ou abîmé rendrait le coffre **inouvrable par les
deux chemins à la fois** — refusé côté code faute d'étiquette, refusé côté phrase secrète faute de
`vault_kek_wrapped`. L'écart avec l'application publiée ne peut qu'**ouvrir** des coffres qui
seraient restés fermés.

## D-014 — La propriété d'une clé en mémoire est explicite, et elle se transfère

**Prise le 2026-08-14, phase 4.**

Trois règles, qui tiennent ensemble :

1. `VaultSessions.open` **prend** la propriété du tableau qu'on lui donne. L'appelant ne l'efface
   plus — ce serait remplir de zéros la session qui vient de s'ouvrir.
2. `VaultSessions.sessionKey` rend une **copie**, dont l'appelant est propriétaire et qu'il efface.
3. `hasLiveSession` existe pour les prédicats d'affichage, afin qu'un simple « ce carnet est-il
   ouvert ? » ne matérialise pas un secret que personne n'effacera.

Le point 2 ferme une course qui n'existe pas côté Dart, mono-fil : rendre le tableau de la session
permettait à un verrouillage concurrent de le vider pendant qu'un chiffrement s'en servait. La note
serait partie en base **scellée sous une clé nulle** — présentée comme protégée, et
irrécupérable. Trente-deux octets recopiés par opération sont un prix négligeable pour ça.


## D-015 — Les réglages restent dans le fichier de préférences **de la version Flutter**

**Décidé le 2026-08-14.**

Le portage lit et écrit `shared_prefs/FlutterSharedPreferences.xml`, avec le préfixe `flutter.` et
les formats du greffon, plutôt que DataStore.

**Pourquoi** : à la bascule, les réglages de l'utilisateur doivent survivre. Écrire ailleurs
obligerait à une migration — c'est-à-dire à un chemin de code qui ne s'exécute qu'une fois, chez les
autres, et qu'aucun test ne rejoue jamais dans les conditions réelles. `VaultWipeJournal` lisait
déjà ce fichier depuis la phase 4 : deux magasins de préférences auraient fait deux sources de
vérité pour l'état d'une même application.

**Écarté** : DataStore, qui est l'API moderne et que le projet embarque déjà. Le coût de la
migration l'emporte sur le bénéfice, et la dépendance reste pour la phase 7.

⚠️ **Les formats ont été LUS dans le greffon** (`shared_preferences_android-2.4.26`, version figée
par `pubspec.lock`), pas supposés. Le piège cher : `setInt` écrit un **`putLong`**. Le délai
d'auto-verrouillage des coffres est un `int` ; `getInt` aurait levé `ClassCastException` au premier
démarrage après la bascule, sur le chemin des coffres, chez les seuls utilisateurs ayant changé le
réglage.

## D-016 — Routes de navigation écrites à la main, sans `kotlinx-serialization`

**Décidé le 2026-08-14.**

`Destination` est une hiérarchie scellée qui fabrique les chaînes de route. Aucune route n'est
écrite ailleurs.

**Pourquoi pas les routes typées de `navigation-compose`** : elles exigent le greffon
`kotlinx-serialization` et sa dépendance, pour sept écrans dont un seul porte un argument. Elles
portent en outre un piège connu — `launchSingleTop` ignore les arguments d'une route typée, si bien
que naviguer d'une note vers une autre ne recompose rien.

**Ce que la décision garantit** : un `navigate("editor/$id")` dispersé dans un écran compilerait
parfaitement et se casserait au premier renommage, sans que rien ne le signale avant l'exécution.


---

## D-017 — Le portage **n'a pas** de téléchargeur de modèle, et c'est une absence d'API

**2026-08-15 · acceptée**

**Contexte.** Le contrat Dart d'origine (`files_tech_voice`) expose un `SttModelDownloader` capable
de descendre un modèle Whisper depuis HuggingFace, empreinte SHA-256 vérifiée. L'application
publiée l'embarque — mais ne l'appelle **jamais** pour descendre quoi que ce soit : elle n'en
utilise que `fileFor`, `isInstalled`, `uninstall` et `purgeTempCaptures`. Le modèle s'obtient par
`SttModelImporter.importFromPath`, c'est-à-dire un fichier que l'utilisateur choisit lui-même.

C'est cohérent avec son manifeste, qui ne se contente pas d'omettre `INTERNET` : il l'**enlève**,
avec six autres permissions, par `tools:node="remove"`.

**Décision.** Le portage ne porte **aucune API de téléchargement**. `SttModel` n'a même pas de champ
`url`. L'acquisition d'un modèle se fait par import d'un fichier local, et par rien d'autre.

**Écarté.**

- *Porter le téléchargeur et ne pas l'appeler*, comme le publié. Une capacité présente finit par
  être utilisée : il a suffi d'un champ `url` dans le modèle pour que la question se pose ici. Ce
  qui était une **discipline d'appel** devient une **absence d'API**, qui ne se contourne pas par
  inadvertance.
- *Porter le téléchargeur derrière un drapeau*. Un drapeau se retourne ; le manifeste, lui, devrait
  alors déclarer `INTERNET`, et la promesse publique tomberait au moment du build.

**Ce que la décision garantit.** Ajouter un téléchargement demanderait d'écrire l'API, de déclarer
la permission **et** de retirer son refus explicite du manifeste. Trois gestes visibles en revue,
là où un appel oublié n'en est aucun.

⚠️ **La contrepartie est réelle et assumée** : l'utilisateur doit trouver le fichier du modèle
lui-même. C'est déjà l'expérience de la version publiée, et les chaînes de l'écran de configuration
— déjà traduites — sont écrites pour ça.

---

## D-018 — `RECORD_AUDIO` sera la première permission, et elle passe par l'outil de contrôle

**2026-08-15 · acceptée**

**Contexte.** Le manifeste fusionné du portage ne déclare aujourd'hui **aucune** permission hors
celle qu'androidx s'accorde à lui-même — vérifié par `tools/check-manifest-permissions.py`, qui
analyse le XML fusionné et non le source, parce qu'un `grep` y matche les **exemples commentés**.

La phase 7 introduira `RECORD_AUDIO`. L'application publiée la déclare déjà : la promesse tenue
n'est pas « aucune permission » mais « **aucune permission réseau** ».

**Décision.** `RECORD_AUDIO` s'ajoute à la **liste revue** de l'outil, dans le même commit que la
déclaration au manifeste, avec sa justification. L'outil doit continuer d'échouer sur toute
permission qu'il ne connaît pas.

**Écarté.** *Assouplir l'outil pour qu'il ignore les permissions non réseau.* Il deviendrait un
contrôle de deux permissions nommées au lieu d'un inventaire : c'est précisément ce qui laisse
passer la troisième.

**Ce que la décision garantit.** Une permission ajoutée sans décision fait **échouer le contrôle**,
au lieu d'entrer en silence dans un manifeste que personne ne relit ligne à ligne.

---

## D-019 — Pas de cache de vérification du modèle pour l'instant

**2026-08-16 · acceptée**

**Contexte.** L'application publiée met en cache le fait qu'un modèle a été vérifié — identifiant,
taille, date de modification, date de vérification — pour éviter de relire 57 Mo à chaque démarrage
à froid : environ une seconde et demie sur un S9. Le cache porte un contrôle subtil de plus
(`mtime <= verifiedAt`), destiné à un attaquant qui réécrirait le fichier avec une date antérieure.

**Décision.** L'import n'en pose **pas**. `SttModelStore.estInstalle` relit et rehache, à chaque
appel.

**Pourquoi.** Le portage n'a pas encore de chemin de démarrage vocal : il n'y a donc rien à
accélérer, et un cache sans appelant est du code non exercé portant une règle de sécurité. Il
entrera avec le moteur, qui est le premier à en avoir besoin — même règle que pour les étapes du
mode panique, qui n'entrent qu'avec le geste qu'elles décrivent.

**Conséquence à connaître.** `PanicStep.VOICE_MODEL_WIPE` efface aujourd'hui le répertoire des
modèles. Le jour où le cache existera, il devra partir **dans la même étape** : un cache qui
survivrait à la purge affirmerait qu'un fichier absent a été vérifié.

---

## D-020 — Le mode panique n'arrête pas l'`AudioRecord` depuis son propre fil

**2026-08-16 · acceptée**

**Contexte.** `VoiceCapture` lit le micro par un `AudioRecord.read` **bloquant**, qui ignore les
coroutines. Quand la panique démarre, l'étape `VOICE_CANCEL` pose une interdiction ; le `read` en
cours, lui, ne rend la main qu'au tampon suivant. Une relecture externe (GPT-5.5) proposait
d'appeler `micro.stop()` depuis le fil de la panique pour l'écourter.

**Décision.** Non. L'interdiction est **relue juste après le `read`**, et le tampon capté pendant le
déclenchement est **jeté** au lieu d'être écrit : la capture lève, son `catch` efface le fichier
immédiatement, et rien n'attend l'étape de purge.

**Pourquoi pas `stop()`.** Il croiserait le `release()` du `finally` de la capture : un appel natif
sur un objet en cours de libération, dans le **seul chemin du code qui n'a pas le droit de
planter**. Le gain se compte en fractions de seconde sur un fichier de toute façon effacé aussitôt.
*Le correctif d'une course ne doit pas coûter plus cher que la course.*

**⚠️ Ce que la décision impose ailleurs.** Le rejet du dernier tampon est conditionné à
`interdite`, **jamais** à `arretDemande` : un arrêt ordinaire doit écrire ce tampon, sinon le
dernier mot de chaque dictée manque. Deux arrêts, deux traitements — et c'est le genre de
distinction qu'un correctif pressé efface.

---

## D-021 — whisper.cpp est vendorisé dans le dépôt, et le pont est écrit ici

**2026-08-16 · acceptée par Patrice**

**Contexte.** La dictée hors ligne demande un moteur natif. L'application publiée l'obtient par le
greffon Flutter `whisper_ggml_plus`, qui embarque lui-même les sources de whisper.cpp. Le portage
Kotlin n'a pas d'équivalent prêt à l'emploi.

**Décision.** **87 fichiers, 3,8 Mo** de sources amont — whisper.cpp et ggml **1.8.3**, licence MIT
— sont copiés dans `app/src/main/cpp/vendor/whisper/`, et compilés par notre propre CMake.

**Ce qui n'a PAS été repris**, et c'est la moitié de la décision :

| Écarté | Poids | Raison |
|---|---|---|
| `main.cpp` / `main.h` | — | La couche FFI **Dart** : une fonction qui prend et rend du JSON. Le pont est réécrit en **JNI typé**. |
| `json/` | 888 Ko | N'existait que pour cette API. |
| `examples/dr_wav.h` | 356 Ko | Un analyseur WAV **généraliste**, en C, sur un fichier venu du disque. Le WAV est décodé en Kotlin par `WavPcm16`, qui n'accepte que le format que la capture produit. |
| `src/whisper.h` | 35 Ko | Doublon exact de `include/whisper.h`, vérifié par `diff`. |

Soit **1,3 Mo écartés**, un quart de ce qui était disponible — et, pour `dr_wav`, une surface
d'attaque en moins pour un besoin qu'on n'a pas.

**Obligations que la vendorisation crée, et qui sont tenues :**

- la notice MIT **voyage avec le binaire**, comme la licence l'exige : elle est reproduite dans
  `terms.md` (FR et EN), document affiché dans l'application. Les trois porteurs de copyright
  présents dans l'arbre y figurent, pas seulement le principal ;
- `PROVENANCE.md` dit ce qui a été copié, d'où, quand, ce qui a été écarté, et **ce qu'il faut
  revérifier à chaque mise à jour** — dont l'absence de dépendance réseau, mesurée ;
- le NDK est **épinglé** (`27.0.12077973`). SMS Tech a vu sa reproductibilité refusée par F-Droid
  parce que deux NDK produisent des `.so` différents à `classes.dex` identique.

**Écarté.** *`sherpa-onnx`.* Coût supérieur pour le même résultat, et le contrat `SpeechToText`
existe précisément pour qu'un changement de moteur ne touche pas les appelants.

**⚠️ Ce que la décision n'a pas encore prouvé.** Aucun test ne transcrit : il faudrait le modèle de
cinquante mégaoctets sur l'appareil de test. Ce qui **est** vérifié sur le S9, c'est que
`libnotes_stt.so` **se charge** — le seul contrôle qui distingue « le CMake a produit un fichier »
de « ce fichier est utilisable ici ».

## D-022 — versionCode : schéma F-Droid `base × 10 + ABI`, base 500

**2026-09-24 · décidée en séance (« fais tout ce qui est nécessaire », Patrice)** — commit `8715023`.

**Contexte.** Le portage produisait `rang × 1000 + 53` (1053/2053/3053), repris du `--split-per-abi`
de Flutter pour dépasser la 2.0.4. Pendant le mois où le portage est resté à l'arrêt, la Flutter a
publié 2.0.5 à 2.0.9, et F-Droid a **imposé** `base × 10 + ABI` (linsui, `!37885`, 2026-09-13) : la
2.0.9 est publiée en 4071/4072/4073. La 3.0.0 était devenue un **downgrade** pour tout le monde.

**Décision.** Sortie = `base × 10 + rang`, rangs de Flutter (armeabi-v7a 1, arm64-v8a 2, x86_64 3),
base **500** → 5001/5002/5003. Palier franc au-dessus de toute la lignée 2.0.x, qui garde 408..499
pour d'éventuels correctifs Flutter pendant la transition. Deux gardes : rangs à un chiffre
(structurelle), base > 407 (**plancher, pas preuve** : la liste de contrôle de publication compare
au dernier tag Flutter publié).

**Écarté.** *408 (la suite immédiate)* : la Flutter a prévu d'utiliser 408 pour le panneau Infos ;
une collision rendrait l'une des deux impubliable. *Garder ×1000* : refusé par F-Droid, et
entrelace les codes de deux versions une fois triés par `rewritemeta`.

## D-023 — Verrouillage de l'application : PIN + biométrie forte, sur le modèle d'Agenda Tech

**2026-09-24 · demandé par Patrice (« la possibilité de vérouiller l'appli par pin ou biométrie,
empreinte ou face ID »)** — conçue puis **câblée le même jour** (cf. « Réalisation » en fin de
section) ; mesurée sur le S9 (API 29), l'émulateur (API 34) et le S24 (API 36, avec l'accord
explicite de Patrice, paquet `.next.debug` séparé de sa vraie Notes Tech).

**Contexte.** Aucune des deux versions n'avait de verrou d'application : la version Flutter n'a pas
`local_auth`. SMS Tech et Agenda Tech en ont un ; une étude de SMS Tech (rapport d'agent du
2026-09-24, résumé dans `REPRISE.md`) a relevé ses défauts, dont plusieurs corrigés par Agenda Tech.

**Décision — ce qui est repris, et d'où :**

| Point | Choix | Origine |
|---|---|---|
| Hôte d'interface | NavController + `rememberSaveableStateHolder` **au-dessus** du verrou ; le contenu **sort de la composition** tant que c'est verrouillé (ni dessiné, ni atteignable, ni lu par TalkBack, ses dialogues disparaissent avec lui) ; l'écran quitté revient intact | Agenda `LockedAppHost` |
| Décision de verrouiller | **synchrone** dans `MainActivity.onStop`, jamais dans une coroutine ; pas sur `isChangingConfigurations` (le changement de langue recrée l'activité) | Agenda F13 + ajout Notes Tech |
| Délai | immédiat (défaut), 15 s, 1 min, 5 min — les paliers de SMS Tech, **sans** son « au prochain lancement » ; mesuré par `elapsedRealtime` au `onStart` — l'horloge qui compte la veille (le `delay()` de SMS Tech ne la comptait pas, S2). Valeur inconnue sur le disque → **immédiat**, jamais plus long | ajout |
| Sélecteur ouvert par l'app | pas de PIN au retour (import du modèle vocal), dans les bornes d'Agenda : 3 s, écran éteint, 3 min | Agenda `PickerRelockPolicy` |
| Vérificateur du PIN | `HMAC_cléKeystore(libellé ‖ Argon2id(pin, sel))`, sel 16 o, Argon2id allégé (32 Mio, t=2). Clé **sans** authentification, **sans** `setUnlockedDeviceRequired`, **sans** exigence matérielle | nouveau — voir ci-dessous |
| Temporisation | 5 essais libres, puis 30 s doublés jusqu'à 1 h ; compteur persistant (écriture `commit`), horloge monotone (un redémarrage relance l'attente, ne la raccourcit jamais) ; **jamais d'effacement** | nouveau |
| Biométrie | `BIOMETRIC_STRONG` **seule** + `CryptoObject` sur une clé AES invalidée par un nouvel enrôlement ; créée **à l'activation**, jamais recréée en silence (clé absente = invalidation → biométrie désarmée, PIN) ; seulement par-dessus un PIN | SMS Tech 1.25.3 H2 + correction de S4 |
| PIN oublié | la seule sortie honnête : le **mode panique** existant (tout effacer) | nouveau |
| Récents | API 33+ : `setRecentsScreenshotEnabled(false)` quand un verrou existe ; en dessous : `FLAG_SECURE` imposé par le contrôleur (seul auteur du drapeau, cf. `SecureWindowController`) | nouveau |
| Panique | nouvelle étape qui efface les clés du verrou ; les préférences `app_lock_*` partent avec `PREFS_CLEAR` (liste blanche) | nouveau |
| Baisser une protection | désactiver, changer le PIN, allonger le délai, **activer** la biométrie : exigent le PIN courant | SMS Tech S7 |

**Pourquoi une clé Keystore pour un verrou qui ne chiffre rien.** Le PIN ne protège rien
cryptographiquement — la base s'ouvre sans lui — mais sa **valeur** mérite protection : on réutilise
ses PIN, et celui-ci est très probablement celui d'un coffre. Stocké en simple empreinte salée, il
tomberait hors ligne en secondes, et ouvrirait ensuite le coffre en un essai : le modèle des coffres
PIN repose précisément sur l'hypothèse inverse. **Pourquoi pas la recette des coffres** : elle exige
du matériel sécurisé et un appareil déverrouillable — elle interdirait le verrou sur un téléphone
**sans** verrouillage d'écran, c'est-à-dire à ceux qui en ont le plus besoin.

**Écarté.** *Code de l'appareil (DEVICE_CREDENTIAL) comme repli ou comme verrou* : il réadmet la
biométrie faible, et un proche qui connaît le code du téléphone est exactement ce contre quoi un
verrou de notes protège. *`BIOMETRIC_WEAK`* (visage 2D) : une photo suffisait (SMS Tech, retiré en
1.25.3) — ⚠️ conséquence à dire à Patrice : **le visage n'est proposé que là où il est de classe 3**
(Pixel récents), pas sur la plupart des Samsung. *Le verrou comme destination de navigation* (SMS
Tech) : perd l'écran en cours à chaque verrouillage. *Effacement après N échecs* : un enfant qui joue
avec le téléphone détruirait les notes.

**Réalisation (2026-09-24) — ce que le code a précisé ou changé par rapport au tableau.**

- **La règle « baisser une protection exige le PIN » vit dans le gestionnaire**, pas dans l'écran :
  `AppLockManager` refuse toute baisse sans `PinProof` — émise par `attemptPin`, **à usage unique**,
  valable 2 min et **dans l'époque de verrouillage** où elle est née (un verrouillage l'annule). Un
  bouton ajouté plus tard qui oublierait de demander le PIN échoue donc au gestionnaire.
- **Époques de verrouillage** (`AppLockState.Locked(epoch)`) : un verrouillage survenu pendant la
  vérification d'un PIN — PIN tapé puis Accueil aussitôt — **l'emporte** ; la biométrie n'ouvre que
  l'époque pour laquelle son invite a été montrée ; et l'invite s'affiche une fois par époque (ni une
  seule fois par processus, ni à chaque recomposition, qui bouclerait sur un appareil dont l'invite
  met l'activité en pause).
- **Le verrou couvre le contenu, et lui seul** : ni l'écran d'ouverture, ni l'**écran d'échec au
  démarrage**, qui ne montrent rien de l'utilisateur — et dont le second doit rester lisible (« ne
  désinstallez pas, écrivez-nous » est ce qui sauve les notes quand la clé manque).
- **La politique de reverrouillage est à l'échelle du processus**, pas de l'activité : une activité
  détruite en arrière-plan (« ne pas conserver les activités ») aurait sinon oublié que l'app était
  partie. Agenda Tech a ce trou.
- **Écran éteint pendant un sélecteur** : le délai de l'utilisateur s'applique à partir de là (Agenda
  verrouille aussitôt, faute de délai). Un téléphone posé sur un sélecteur est traité comme un
  téléphone posé dans l'app.
- **Récents sous Android 13** : le verrou pose sa demande par un `SecureWindowGuard` au sommet de
  l'app (le contrôleur reste le seul auteur du drapeau) — et l'interrupteur « captures d'écran »
  **le dit** et devient inactif, au lieu d'afficher « désactivé » pendant que toute capture échoue.
  Mesuré sur l'émulateur API 34 (contrôle négatif compris) : verrou actif, carte des Récents neutre ;
  verrou désactivé, contenu visible.
- **Un seul recouvrement de panique, au sommet** (cf. `04-PIEGES.md` §139), et le verrou maintenu
  tant qu'une panique tourne ou a rendu son rapport.
- **Le pavé et les pastilles** sont sortis des feuilles de coffre vers `ui/common/PinPad.kt`, sans
  changement de comportement ; l'écran de verrouillage prend des touches de 64 dp pour tenir sur le
  S9 sans défiler (§137).
- **`androidx.biometric:1.1.0`** apporte `USE_BIOMETRIC` et `USE_FINGERPRINT` au manifeste fusionné :
  refusées d'abord par `tools/check-manifest-permissions.py`, puis admises **avec** leur ligne dans les
  deux `privacy.md` (qui passent en version 1.1.0).

**Limite connue, assumée.** Si la clé du verrou disparaît (défaut du Keystore, jamais un geste de
l'utilisateur) pendant que l'app est ouverte, le verrou ne peut plus être désactivé — aucun PIN n'est
vérifiable — et le verrouillage suivant ne laisse que l'effacement. Relâcher la preuve dans ce seul
cas a été écarté : il ne se produit qu'avec la perte d'une clé que l'utilisateur ne peut pas
provoquer, et c'est le cas même où la prudence doit l'emporter.

## D-024 — Aperçu Markdown : analyseur JetBrains, rendu Compose écrit ici

**2026-09-24 · parité 2.0.9 (A1/A2 de l'inventaire)** — décidée le 24, **réalisée le 2026-09-25**
(cf. « Réalisation » en fin de section).

**Contexte.** La 2.0.9 a ajouté un aperçu (bascule Éditer/Aperçu, `flutter_markdown_plus`, GFM,
`[[Titre]]` cliquable, images jamais chargées). Le portage n'a aucune bibliothèque Markdown — le
rendu minimal de `LegalScreen` ne couvre que quatre pages statiques (§125).

**Décision.** `org.jetbrains:markdown` (analyseur GFM pur Kotlin, Apache 2.0, Maven Central) → un
modèle de blocs testé **sur la JVM** → un rendu Compose écrit ici. Parité de rendu avec la 2.0.9 :
titres, listes, cases GFM **non interactives**, citations, code, tableaux, règles ; gras, italique,
barré, code en ligne, liens ; `[[Titre]]` par le **même motif** que l'indexeur (rendre public
`WikiLinkParser.LINK`), **pas** dans le code ; résolu à la demande, ouvert, ou **créé puis ouvert**
comme la 2.0.9 ; liens externes `http`/`https`/`mailto` seulement, vers une autre application ;
images **jamais chargées** (texte alternatif en italique) ; HTML affiché **littéralement** (écart
assumé : la 2.0.9 fait disparaître les blocs HTML).

**Écarté.** *`multiplatform-markdown-renderer`* : dépendances Compose Multiplatform, et le lien
`[[…]]` à cheval sur plusieurs jetons y est difficile à intercepter. *Markwon* : vues Android,
inactif depuis 2021. *`AnnotatedString.fromHtml`* : ni tableaux, ni cases, ni blocs de code.

**Réalisation (2026-09-25) — ce que le code a précisé ou changé.**

- **Dépendance** : `org.jetbrains:markdown` **0.7.14** (publiée le 2026-09-16, Apache 2.0, une seule
  dépendance : `kotlin-stdlib`). Seul l'**analyseur** sert : la partie HTML de la bibliothèque échappe
  le texte pour du HTML (`04-PIEGES.md` §146), le décodage est donc écrit ici.
- **Structure** : `domain/markdown/` (modèle `PreviewBlock`, lecteur `MarkdownPreviewReader`, masque
  `WikiLinkMask`) testé sur la JVM ; `ui/editor/ApercuMarkdown.kt` dessine, `LectureDeLApercu.kt`
  lit hors du fil principal. Le motif `[[…]]` est **celui de l'indexeur** (`WikiLinkParser.LINK`,
  rendu public), posé **avant** l'analyse : c'est ce qui reproduit la priorité de la 2.0.9.
- **Un seul chemin pour « ouvrir ou créer »** : toucher `[[Titre]]` dans l'aperçu ou un lien fantôme
  du panneau → `NotesRepository.resolveTitle` (la **même** table que l'indexeur : un titre ambigu mène
  à la même note dans les deux) → sinon création dans le dossier de la note → **ouverture**. Le panneau
  créait sans ouvrir, ce que la 2.0.9 n'a jamais fait (`04-PIEGES.md` §150).
- **Écarts assumés avec la 2.0.9, tous dans le même sens** — rien de ce que l'utilisateur a écrit ne
  disparaît, et aucun geste n'est sans effet :
  - HTML affiché tel quel (la 2.0.9 fait disparaître les blocs HTML) ;
  - un lien vers autre chose que `http`, `https` ou `mailto` est du **texte**, pas un lien qui ne
    fait rien quand on le touche ;
  - `www.…` s'ouvre en `https://` (la 2.0.9 : `http://`) ;
  - les cases de tâche sont **nommées** pour un lecteur d'écran (« Fait » / « À faire ») ;
  - le code se replie à la ligne au lieu de défiler de côté ;
  - « aucune application ne peut ouvrir ce lien » est dit, au lieu d'un appui sans effet.
- **Borné, quelle que soit la note** (`04-PIEGES.md` §147) : balayage linéaire avant l'analyse — plus
  de 500 000 caractères, plus de 8 marqueurs de conteneur en tête d'une ligne, plus de 300 `[` dans un
  bloc ou 3 000 dans la note (liens `[[…]]` et cases de tâche non comptés) : la note s'affiche **telle
  quelle**, sous un avis qui dit pourquoi. Tableaux de plus de 32 colonnes affichés tels quels ;
  imbrication suivie sur 12 niveaux ; l'analyse s'annule quand on quitte l'aperçu (§148) ; aucune
  exception ne remonte à l'éditeur, sauf l'annulation.
- **Paresseux** : l'aperçu est une `LazyColumn` — un élément par bloc, par élément d'une liste de
  premier niveau, par ligne d'un tableau de premier niveau — parcourue par UN `items(count)` sur une
  liste à plat préparée avec la lecture, hors du fil principal ; un élément ne dessine pas plus de 500
  blocs d'un coup (au-delà : tel quel).
- **Mise en page de la 2.0.9, pour l'éditeur entier** (§153, §154) : en-tête fixe (titre, bascule),
  corps qui remplit l'espace et défile lui-même, panneau des liens sous le corps — borné au tiers de
  l'écran, masqué tant que le clavier est ouvert. Elle corrige un **plantage préexistant** : une note
  de plus de ~3 600 lignes faisait planter l'éditeur à l'ouverture (contrainte de hauteur que Compose
  ne sait pas représenter), depuis le 2026-08-15.
  - **Écart assumé : un plancher** (§155, relecture Gemini). Le corps ne descend jamais sous 120 dp ;
    si la fenêtre est trop courte, en-tête, corps et panneau défilent ensemble. Sans lui, mesuré sur
    le S9 en paysage : corps de 40 dp, et **0 dp clavier ouvert** — la 2.0.9 a le même défaut, que
    l'ancienne mise en page du portage n'avait pas. Avec : 120 dp, et 74 dp visibles clavier ouvert
    (toute la place entre la barre d'application et le clavier).
- **Position** : l'aperçu revient où on l'a laissé après un passage en Édition sans frappe (lecture
  conservée tant que le texte ne change pas). En Édition, pas de position gardée, comme la 2.0.9.
- **Relecture GPT-5.6 sol** (0,39 $, §152) : 5 constats réels corrigés — liens automatiques `<mailto:…>`,
  adresses plafonnées à 8 192 caractères, poids des éléments paresseux, liste à plat, position.
- **Relecture Gemini 3.1 Pro des seuls correctifs** (0,44 $, §155) : 3 constats — le « critique »
  réfuté (code et tests), le corps écrasé en paysage (plancher ci-dessus), une copie de la note à
  chaque frappe (`DartTextSemantics.isBlank`).
- **Accessibilité mesurée sur l'arbre réel d'Android** (§149) : chaque lien arrive au lecteur d'écran
  comme un `AccessibilityClickableSpan` sur ses mots.

- **Pages légales au même rendu** (Patrice, 2026-09-25 ; §157) : `LegalScreen` dessine ses quatre
  fichiers comme l'aperçu dessine une note, **sélectionnables** comme dans la 2.0.9. Le passage a
  révélé que le lecteur ignorait les fins de ligne **CRLF** (notes importées de Windows comprises) et
  qu'une ligne faite d'un `\r` ouvrait une faille dans la garde anti-notes-hostiles : corrigé.
- **Message** : l'échec de création d'une note liée dit « Impossible de créer la note liée », et non plus
  « Création du coffre échouée » comme la 2.0.9, pour n'importe quelle note (Patrice, 2026-09-25).

- **Écart assumé : les liens dans un coffre** (solution B, Patrice, 2026-09-25 ; §158). La 2.0.9 ne
  résout aucune note chiffrée : toucher `[[X]]` depuis un coffre créait une nouvelle « X » à chaque fois.
  Depuis une note de coffre, les notes de **ce** coffre ouvert passent d'abord, puis les notes hors de
  tout coffre ; une note d'un autre coffre n'est jamais cible, ni une note de coffre depuis l'extérieur.

- **Suites de B, tranchées par Claude sur délégation de Patrice** (« fais ce qui est le mieux »,
  2026-09-25 au soir ; §159), à la règle de la réponse publique à l'issue #10 — **aucune liste de titres
  de coffre à l'écran** :
  - le **panneau des liens** d'une note de coffre reste **absent**, comme dans la 2.0.9 : ses
    rétroliens seraient une liste de titres du coffre ;
  - la **feuille `[[`** d'une note de coffre reconnaît la note de ce coffre dont le titre est tapé
    **en entier** — la note que le lien ouvrira — et Entrée la **lie** au lieu de créer un doublon
    (défaut trouvé en examinant la question : elle ne voyait aucune note de coffre) ; tapé en partie,
    rien du coffre ;
  - une recherche qui **échoue** (coffre refermé pendant que la feuille est ouverte, note illisible) le
    dit, ne propose rien et ne crée rien — elle faisait tomber l'application, puis, rattrapée en liste
    vide, proposait de créer (relecture GPT-5.6, 0,38 $).

**Limites connues**, préexistantes et identiques dans la 2.0.9 : deux notes ordinaires homonymes
peuvent être montrées alors que le lien ouvre la moins récente ; une note exacte au-delà des huit
suggestions n'empêche pas Entrée de créer un homonyme (§84, « au mieux »).

## D-025 — Allemand, espagnol, italien : un ajout hors parité

**2026-09-25 · demandé par Patrice** (« ce serait bien d'ajouter les langues allemand, espagnol et
italien à l'appli »). La 2.0.9 ne parle qu'anglais et français : c'est un **ajout** du portage, comme le
verrouillage (D-023) et le panneau Infos.

**Décisions** :
- **Ton** : vouvoiement dans les trois langues (Sie, usted, Lei), comme SMS Tech et comme le « vous » de
  Notes Tech en français. ⚠️ Le portefeuille n'est pas cohérent : Pass Tech tutoie en espagnol et en
  italien. Patrice, le 2026-09-25 : « il faudra passer pass_tech au vouvoiement » — plus tard, tâche
  consignée dans la mémoire de Pass Tech ; rien changé ici.
- **Vocabulaire** partagé avec SMS Tech et Pass Tech : Tresor / caja fuerte / cassaforte ; Passphrase /
  frase de contraseña / passphrase ; Panikmodus / modo pánico / modalità panico. Boîte de réception :
  Eingang, Bandeja de entrada, In arrivo.
- **Source des chaînes** : `values-de|es|it/strings.xml` écrits directement — pas d'ARB à transposer —
  et tenus par `LanguesDeLApplicationTest` (mêmes noms, mêmes arguments, formes de pluriel `one`/`other`,
  plus `many` en espagnol et en italien). Une chaîne ajoutée en anglais exige ses trois traductions.
- **Pages légales** traduites (confidentialité, conditions), sans clause disant quelle version fait foi
  — comme Pass Tech ; décision juridique laissée à Patrice. La licence MIT reste en anglais.
- **Magasins** : les textes fastlane (de-DE, es-ES, it-IT) viendront à la publication (phase 3 du plan
  de bascule) ; le portage n'en a pas encore.

**Réalisation** : commit `5466624`, §161. Restes : REPRISE, section « nuit » du 25.

## D-026 — Clé de coffre perdue : dire ce qu'on sait, n'effacer que sur preuve

**2026-09-25 · demandé par Patrice** (« corrige le avec ce qu'il y a de mieux », sur le message d'une
clé invalidée). Détail et mesures : §162.

**Décisions** :
- **Clé introuvable** — le cas réel : sur API 34, retirer le verrouillage d'écran supprime la clé
  (mesuré). On le **dit** (« introuvable », la cause connue donnée comme générale, « aucun PIN sans
  elle »), on ne **compte** pas d'essai, on n'**efface** pas, et la feuille ne propose plus que
  « Fermer ». Écartés : effacer comme pour l'invalidation — une absence ne prouve pas sa cause, et un
  mauvais alias, défaut de l'application, la produirait aussi ; garder « réessayez », qui ne change
  rien. Supprimer le dossier reste possible sans déverrouiller.
- **Clé invalidée** : l'effacement reste (liste blanche de la v1.0.3, parité), mais sa **raison**
  voyage jusqu'à l'écran, qui ne dit plus « trop de tentatives ».
- **Prévenir au choix du mode**, sur l'option code, au conditionnel. Pas dans la feuille de création :
  elle débordait l'écran du S9, et son pavé bougeait (§162).
- **Non fait, à trancher par Patrice** : retirer `setUnlockedDeviceRequired(true)` de la clé du coffre.
  Mesuré sur API 34 : sans l'attribut, la clé **survit** au retrait du verrouillage. Mais c'est un recul
  de sécurité — la clé deviendrait utilisable téléphone verrouillé, donc par qui exécute du code en tant
  que l'application sur un téléphone saisi, qui chercherait alors le code hors ligne — et les coffres
  existants garderaient leur clé, sauf à la resceller au prochain déverrouillage. **Recommandation :
  garder l'attribut** ; qui veut un coffre indépendant du téléphone a la phrase secrète.
