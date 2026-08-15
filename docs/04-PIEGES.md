# Pièges

> À relire **avant d'écrire un DAO, une migration ou du code de coffre**.
>
> Chaque entrée décrit un piège réel, pourquoi il est invisible à la relecture, et la contre-mesure
> **structurelle** — celle qui rend la faute impossible plutôt que celle qu'il faut se rappeler.
> Un avertissement qu'il faut se rappeler à chaque appel est un bug en attente d'un nouvel appelant.

---

## 1. 🔴 `INSERT OR REPLACE` détruit les liens et corrompt l'index FTS5

**Le piège.** `@Insert(onConflict = REPLACE)` est le défaut réflexe de Room. Sur ce schéma, c'est
une perte de données.

`REPLACE` en SQLite n'est pas un `UPDATE` : c'est un `DELETE` suivi d'un `INSERT`. Deux
conséquences en chaîne, et aucune n'est visible à la lecture du code :

1. `note_links.source_id` porte `ON DELETE CASCADE` vers `notes(id)`. Le `DELETE` interne
   **supprime tous les backlinks de la note** — silencieusement.
2. `notes_fts` est une table à contenu externe indexée sur `notes.rowid`. Un `REPLACE`
   **change le `rowid`**, et l'entrée d'index pointe alors dans le vide.

Pire : `PRAGMA recursive_triggers` vaut `OFF` par défaut, donc le trigger `notes_ad` **ne se
déclenche pas** sur le `DELETE` interne du `REPLACE`. L'index n'est même pas nettoyé.

**Contre-mesure structurelle.** Aucun DAO n'expose `onConflict = REPLACE`. Les écritures passent
par `@Insert(onConflict = ABORT)` et `@Update` explicites. La règle est vérifiée par un test
qui échouerait si un `REPLACE` réapparaissait.

## 1 bis. 🔴 Une écriture de ligne entière détruit la protection d'une note de coffre

**Le piège.** `@Update` sur une entité réécrit **toutes** les colonnes depuis l'objet fourni,
`content` **et** `encrypted_content` compris.

Or l'éditeur détient l'éphémère **déchiffrée** d'une note de coffre — `content` rempli,
`encryptedContent` à `null`. Écrire cet objet efface le blob et pose le contenu en clair dans la
base. La note perd sa protection **définitivement**, sans le moindre signal.

**Ce n'est pas une hypothèse.** L'incident a eu lieu dans l'application publiée, sur un simple tap
sur l'icône d'épinglage. Le code Flutter le documente à l'endroit du correctif
(`notes_tech/lib/data/db/notes_dao.dart:234-241`).

**Contre-mesure structurelle.** `NoteDao` n'expose **aucune** écriture de ligne entière. Chaque
écriture touche un groupe de colonnes et un seul :

| Méthode | Écrit | N'écrit jamais |
|---|---|---|
| `updateEditableFields` | titre, contenu, étiquettes | `encrypted_content` |
| `updateFlags` | épinglé, favori, archivé | contenu, blob |
| `replaceContentPayload` | contenu **et** blob | `updated_at` |
| `setTrashedAt` | corbeille | contenu, blob |
| `moveToFolder` | dossier | contenu, blob |

Et `updateEditableFields` porte en plus `AND encrypted_content IS NULL` : sur une note verrouillée
elle ne touche **rien** et rend `0`. **C'est la base qui refuse**, pas un commentaire qui prévient.

La version Flutter, elle, a gardé l'écriture générique sous un avertissement. C'est insuffisant :
l'avertissement a été perdu au moment du portage, et le chemin dangereux a été repris seul.

## 2. 🔴 `@Upsert` renvoie `-1` sur une mise à jour

**Le piège.** Room `@Upsert` renvoie le `rowid` sur insertion, mais **`-1` sur mise à jour**.
Un repository qui promet « renvoie l'identifiant » et relaie brut la valeur du DAO ment une fois
sur deux.

**Précédent réel dans le portefeuille.** Agenda Tech v0.4.1 : crash systématique
`FOREIGN KEY constraint failed` à l'enregistrement d'un événement modifié portant un rappel.
Le défaut a survécu à trois versions publiées parce qu'il exigeait une donnée que le jeu de test
ne contenait pas. Le DAO portait déjà un commentaire d'avertissement — il a tenu des mois.

**Contre-mesure structurelle.** Les identifiants de ce schéma sont des `TEXT` (UUID) générés par
le domaine, **jamais** par la base. Aucun code ne dépend d'un `rowid` renvoyé par un DAO.
Le `rowid` reste un détail interne à SQLite et à l'index FTS5.

## 3. 🟠 Room valide **tous** les index, y compris ceux qu'on n'utilise pas

**Le piège.** L'adoption de la base héritée repose sur `onValidateSchema` (cf. décision D-005).
Room compare l'**ensemble complet** des index trouvés à ceux déclarés. Un index présent dans la
base mais absent des `@Entity` fait échouer l'ouverture.

**Contre-mesure.** Les six index de [02-SCHEMA-HERITE.md](02-SCHEMA-HERITE.md) §3 sont déclarés,
avec **leurs noms d'origine** (`idx_folders_parent`, `idx_notes_folder_active`, `idx_notes_trashed`,
`idx_notes_updated`, `idx_links_source`, `idx_links_target`, `idx_links_target_norm`).

⚠️ `idx_notes_folder_active` est trié `updated_at DESC`. Room le rend par
`@Index(value = [...], orders = [ASC, ASC, ASC, DESC])`. Omettre `orders` produit un index
différent et fait échouer la validation.

Ce n'est pas une gêne, c'est le bénéfice : la validation est la **preuve vérifiée par la machine**
que les entités décrivent la base réelle.

## 4. 🟠 Une garde échantillonnée ne se retente jamais

**Le piège.** Une condition vérifiée *une fois* — au démarrage, à l'ouverture d'un écran — et
jamais réévaluée. Si la condition devient vraie une seconde plus tard, personne ne rappelle ce code.

**Où ça mord ici.** Verrouillage automatique des coffres, reprise d'un auto-effacement interrompu
(`vault_wipe_pending_*`), invalidation d'une clé Keystore.

**La question à se poser à chaque garde** : *« si la condition devient vraie une seconde plus tard,
qui rappelle ce code ? »* S'il n'y a pas de réponse, c'est un `Flow`, pas un `if`.

## 5. 🟠 Le jumeau asymétrique a deux faces

**Le piège.** Un correctif appliqué à un chemin et pas à son symétrique. Mais « symétrique » ne
veut pas seulement dire *l'autre branche du code* : ça veut aussi dire **ce que l'utilisateur voit**.
Un couple peut être aligné en comportement et muet côté retour visuel.

**Où ça mord ici.** Coffre passphrase ↔ coffre PIN — deux modes, deux feuilles d'interface, deux
chemins de déverrouillage, deux chemins d'erreur. Tout correctif sur l'un se relit sur l'autre,
**et sur son message**.

## 6. 🟡 Les apostrophes de `strings.xml`

Les 436 clés viennent de fichiers ARB français, pleins d'apostrophes. Dans `strings.xml`,
une apostrophe dans une **valeur** doit être échappée `\'` — sinon la ressource est tronquée
silencieusement.

Deux précisions qui ont déjà coûté du temps :

- l'échappement ne s'applique **pas** dans un commentaire XML ;
- un `heredoc` bash mange les antislashs — passer par une écriture de fichier, pas par `echo`.

La conversion ARB → `strings.xml` se fait **par script**, jamais à la main.

## 7. 🟡 `stateIn` et les tests vacants

**Le piège.** `stateIn(WhileSubscribed)` suivi d'un `.value` sans collecteur actif rend la valeur
initiale. Un test qui lit `.value` passe alors sans jamais exercer le flux : il est **vacant**.

Même famille : une horloge injectable d'un seul côté (on écrit avec l'horloge réelle, on lit avec
une horloge figée) produit des tests verts pour la mauvaise raison. **Injecter des deux côtés.**

## 8. 🟡 `runCatching` autour d'un `suspend` annulable

`runCatching` attrape `CancellationException` et la mange. Une coroutine annulée continue alors
comme si de rien n'était. Ne jamais l'utiliser autour d'un appel annulable — attraper les types
d'exception attendus explicitement.

## 9. 🟡 La clé SQLCipher doit survivre à toute la vie du pool

SQLCipher clé **chaque** connexion que son pool ouvre, pas seulement la première : chaque
connexion relit le mot de passe de sa configuration. Effacer le tableau d'octets après
`Room.build()` casse donc la connexion suivante — et, plus vicieux, laisse la première connexion
fonctionner, si bien que le défaut ne se voit qu'à la montée en charge.

**Contre-mesure.** SQLCipher reçoit **sa propre copie** ; celle de l'appelant est effacée dans un
`finally`. Et l'ouverture réelle est forcée dans le constructeur (`db.openHelper.writableDatabase`)
pour qu'un échec de clé soit attribuable là où il se produit, au lieu de ressortir plus tard en
plantage de DAO sans rapport.

*(Ces trois points viennent de `DatabaseFactory.kt` d'Agenda Tech, où ils ont été payés une fois.)*

## 10. 🔴 Aucun repli destructif dans le constructeur Room

`fallbackToDestructiveMigration*` n'apparaît **nulle part**, et cette absence est porteuse.

Précédent réel : Agenda Tech portait `.fallbackToDestructiveMigrationOnDowngrade(false)` sous un
commentaire affirmant l'inverse de ce que la ligne faisait. Room 2.7 a changé la signature — le
booléen est `dropAllTables`, pas un interrupteur. L'appel **armait** le chemin destructif quel que
soit l'argument, et `false` demandait seulement à Room de ne détruire que ses propres tables…
c'est-à-dire toutes.

Les défauts du constructeur (`requireMigration = true`,
`allowDestructiveMigrationOnDowngrade = false`) donnent l'exception visible qu'on veut.
**Ne rien dire est la bonne façon de le dire.**

## 11. 🔴 Effacer la clé enveloppée d'un coffre emporte **toutes** ses notes

Le piège 1 bis porte sur une note. Celui-ci porte sur un dossier, et sa conséquence est plus large.

La table `folders` a sept colonnes `vault_*`. Une écriture de ligne entière construite depuis un
objet `Folder` incomplet ou périmé y écrirait `NULL` dans `vault_kek_wrapped`. La bonne phrase
secrète ne rendrait plus rien : le matériel qui permettait de déchiffrer n'existe plus. Rien ne le
signalerait avant la prochaine ouverture du coffre.

L'application publiée renomme par `UPDATE` complet (`folders_dao.dart:66`). Aucun défaut n'a été
constaté — ses objets viennent de la base et portent bien leurs colonnes. C'était aussi vrai des
notes, jusqu'au jour où ça ne l'a plus été.

**Ce portage** : `FolderDao` n'a pas de `@Update`. Renommer, recolorer et déplacer sont trois
requêtes qui nomment leurs colonnes. Les colonnes de coffre ne s'écriront que par les méthodes de
provisionnement (phase 4). Cf. `01-DECISIONS.md` D-010.

---

## 12. 🟠 `vault_mode` ne dit pas si un dossier est un coffre — `vault_salt` le dit

Un défaut de ce portage, relevé le 2026-08-13 en écrivant la couche domaine : `FolderDao.findVaults`
sélectionnait `WHERE vault_mode IS NOT NULL`.

`vault_mode` n'existe que depuis la 0.9 et a été **rétro-remplie** par une migration
(`database.dart:761`). `vault_salt`, lui, est présent depuis le premier coffre, et c'est le critère
que retient l'application publiée (`folder.dart:100`).

L'écart aurait été indétectable en pratique — la migration a fait son travail. Ce n'est pas une
raison : faire dépendre l'identification d'un coffre du bon déroulement passé d'une migration, quand
la conséquence est d'écrire ou non du clair sur le disque, est un pari qu'on n'a aucune raison de
prendre.

Un test instrumenté vide `vault_mode` et vérifie que le dossier reste reconnu comme coffre.

---

## 13. 🟠 Un garde-fou peut être défait par la ligne suivante

L'indexation exclut délibérément les auto-références : une note qui écrit son propre titre entre
crochets produit un fantôme, pas un lien vers elle-même.

Puis `resolveDanglingTargets` passe, et rattache tous les fantômes visant ce titre à cette note —
**y compris celui qu'on venait d'écarter**. Le garde-fou est annulé une ligne plus loin, dans la
même transaction.

**L'application publiée a exactement ce défaut** (`backlinks_service.dart:338` écarte,
`resolveDangling` rétablit). Personne ne l'a vu parce que la conséquence est cosmétique : une note
qui figure dans ses propres rétroliens.

Corrigé ici par `AND source_id != :noteId` dans la requête de résolution. Ce n'est pas une divergence
gênante : `target_id` n'est pas une clé d'appariement partagée, chaque réindexation le réécrit.

**La leçon générale, elle, dépasse le cas** : un garde-fou posé dans une étape n'engage que cette
étape. Quand plusieurs écritures se suivent dans une transaction, il faut se demander laquelle défait
ce que la précédente vient d'établir.

Relevé par un test qui **affirmait** le garde-fou. Aucune relecture ne l'avait vu — ni les miennes,
ni les externes.

---

## 14. 🟠 « Le titre n'a pas changé » ne veut pas dire « rien n'a changé »

Une première version de `saveEdits` ne réaccrochait les liens entrants que si le titre avait changé.
L'optimisation paraissait évidente et elle était fausse.

Une note verrouillée au **format 1** garde son titre en clair. Elle vient de partir au coffre, mais
son titre est identique — donc la réaccroche ne se déclenchait pas, donc les liens qui pointaient
vers elle restaient résolus. Une note non protégée continuait d'afficher un lien cliquable vers une
note désormais secrète.

Les deux opérations concernées sont idempotentes et portent sur une table minuscule. Les appeler à
chaque écriture coûte deux `UPDATE` et supprime la question.

**Le motif à retenir** : conditionner une opération de sécurité à un changement *observable* suppose
que le changement de sécurité s'y reflète. Ici il ne s'y reflétait pas.


---

## 15. 🔴 Une écriture en bloc contourne toutes les gardes posées « par note »

`deleteKeepingNotes` déplaçait les notes d'un dossier par un `UPDATE notes SET folder_id = …`
unique. Aucune note n'était touchée individuellement, donc **aucune garde « par note » ne
s'appliquait** : ni le scellement, ni le refus de déplacer une note verrouillée.

Résultat : vider un dossier ordinaire **vers un coffre** y déposait des notes en clair ; vider un
coffre **vers ailleurs** faisait survivre ses notes chiffrées à la clé qu'on supprimait avec le
dossier.

Corrigé par deux refus explicites. La règle générale, elle, vaut pour tout ce qui reste à écrire :

> **Chaque fois qu'une opération touche N lignes en une requête, se demander quelles gardes
> « par ligne » viennent d'être sautées.**

Relevé indépendamment par deux relectures externes, sur un lot que la passe précédente venait de
déclarer exempt de fuite de clair.

### ⚠️ Correction du 2026-08-13 : l'application publiée n'a PAS ce défaut

Cette section affirmait qu'elle l'avait aussi. **C'était faux, et je ne l'avais pas vérifié.** Sa
réassignation est bien le même `UPDATE` nu, mais ses appelants la protègent :

| Cas | Ce qui l'empêche côté Flutter |
|---|---|
| destination = coffre | la destination est **codée en dur sur la boîte de réception** (`folder_dialogs.dart:223`) |
| source = coffre | les notes sont **déchiffrées d'abord** (`folders_drawer.dart:134`), avec reprotection si ça casse en route |

Le refus côté Kotlin reste justifié — une garde tenue par un appelant se perd au premier appelant
suivant, et la placer dans la couche données la rend indépendante de l'interface. Mais le défaut
était **le mien**, pas le leur.

Deux leçons, et la seconde vaut pour toutes les relectures de ce projet :

1. **Vérifier avant d'affirmer**, même quand le constat arrange le récit.
2. **Une relecture externe qui n'a pas la source d'origine sous les yeux ne peut pas contredire une
   affirmation sur elle.** Les deux modèles ont décrit correctement mon code ; c'est moi qui ai
   ajouté, sans le vérifier, que l'original faisait pareil.

---

## 16. 🟠 Deux requêtes sur la même table, une seule gardée

`backlinks()` refusait les notes de coffre comme source. `dangling()` lisait la même table sans ce
filtre. Chacune, isolément, paraissait correcte.

`target_title` est un titre **écrit dans le texte** d'une note. Venant d'une note de coffre, il
laissait filtrer un fragment de ce qu'elle cite.

C'est le **jumeau asymétrique**. Le chercher activement : quand une garde existe quelque part, se
demander qui d'autre lit la même chose.

---

## 17. 🟠 Vérifier ce que rend une dépendance, pas seulement ce qu'on lui demande

`sealIfVault` appelait le scelleur et insérait ce qu'il rendait. Un scelleur qui chiffrerait
correctement mais oublierait de vider `content` aurait fait insérer le blob **et** le texte lisible.

La phase 4 n'étant pas écrite, le contrôle porte sur du code à venir — et c'est exactement le
moment de le poser, pendant que le contrat est encore une intention plutôt qu'une habitude.

`check(!carriesPlaintext(sealed))` annule la transaction. Un test avec un scelleur délibérément
négligent le prouve.

---

## 18. 🔴 Le motif « échec lu comme absence » revient, et il a des jumeaux

Le pire défaut possible de ce projet tient en une ligne :

```kotlin
val valeur = preferences[CLE] as? String ?: return null
```

`null` y signifie deux choses inconciliables : **la clé n'existe pas**, et **elle existe mais porte
autre chose**. La seconde est une corruption. La lire comme une absence conduit `KekRepository` à
conclure « aucune clé nulle part », puis à en générer une neuve si aucune base n'existe encore.

Ce motif est apparu **deux fois le même jour**, dans deux fichiers voisins :

| Fichier | États confondus avec l'absence |
|---|---|
| `FlutterSecureStorageKekSource` | valeur d'un type inattendu |
| `KeystoreSealedKekSource` | type inattendu **et** scellé incomplet (un champ écrit, l'autre pas) |

Le second est resté ouvert **deux heures après la correction du premier**, dans un fichier dont le
commentaire explique pourquoi le motif est mortel.

> **Corriger un motif quelque part, c'est s'engager à le chercher partout ailleurs.**
>
> C'est la troisième fois qu'un jumeau asymétrique se manifeste dans ce projet — après
> `backlinks()` / `dangling()` (§16) et l'écriture pleine ligne notes / dossiers (§11).

La forme correcte distingue **trois** états, jamais deux :

```kotlin
if (rien du tout) return null
if (présent mais inutilisable) throw KekFailure.SourceUnavailable(...)
// sinon : la valeur
```

---

## 19. 🟠 Une valeur bien formée n'est pas une valeur juste

`KekRepository` recopiait vers la source primaire toute clé trouvée ailleurs, dès qu'elle passait les
contrôles de forme — 32 octets, hexadécimal valide, tag GCM validé.

Ces contrôles prouvent que la valeur **est une clé**. Ils ne prouvent pas qu'elle **ouvre la base qui
est sur le disque**.

Si la source primaire était momentanément indisponible et qu'une source secondaire portait une clé
différente, la recopie écrasait le scellé primaire — donc la dernière copie persistée de la bonne
clé. Irréversible.

Correctif : ne recopier que si **aucune source n'a échoué** pendant le parcours. Une source qui n'a
pas pu être lue n'autorise plus à réécrire ce qu'elle contenait.

**Le motif général** : avant d'écrire une valeur par-dessus une autre, se demander ce qui prouve que
la nouvelle est meilleure. « Elle a le bon format » n'est pas une réponse.

---

## 20. 🟡 Une `String` contenant un secret ne peut pas être effacée

`String(clearText, Charsets.UTF_8)` pour décoder une clé hexadécimale en laisse une copie **lisible
et ineffaçable** dans le tas jusqu'au prochain ramasse-miettes. Les `String` Java sont immuables.

`SecretBytes.fromHexAscii(ByteArray)` décode directement depuis les octets. L'appelant garde la
maîtrise de l'effacement de son tableau.

Corollaire moins évident, relevé sur le même fichier : `fromHex` remplissait son tableau de sortie au
fur et à mesure et le laissait au ramasse-miettes si un caractère invalide survenait **vers la fin**.
Le chemin d'erreur est celui qu'on regarde le moins ; c'est aussi celui où un secret à demi décodé
traîne sans que personne ne l'ait voulu.

---

## §21 — Une garde échantillonnée a besoin de **quelqu'un pour la rappeler**

Le verrouillage automatique était vérifié à deux endroits : à la lecture d'une session, et par un
balayage. Le premier refuse de servir une clé périmée ; le second l'**efface**. Le premier ne libère
rien, le second ne protège rien entre deux passages : il en faut deux.

Sauf que le second n'avait **aucun appelant en production**. `sweep()` existait, était testé, et ne
tournait jamais. Une clé oubliée serait restée en mémoire jusqu'à la mort du processus, et rien ne
l'aurait signalé — le comportement observable, lui, était correct.

> **La question à poser devant toute garde échantillonnée : « si la condition devient vraie une
> seconde plus tard, qui rappelle ce code ? »** Si la réponse est « personne », la garde est
> décorative.

Corollaire trouvé dans la foulée : ce même balayage excluait de son **calcul d'échéance** les
sessions en cours d'ouverture, alors qu'il ne devait les exclure que du **verrouillage**. Seule
session ouverte, elle faisait annoncer « plus rien à surveiller » au moment précis où il allait y
avoir quelque chose à surveiller. Deux filtres qui se ressemblent n'ont pas forcément le même objet.

## §22 — Un geste et sa reprise doivent être dans la **même portée**

Le compteur de tentatives d'un coffre à code est incrémenté avant l'essai, et repris si l'essai ne
prouve rien. Les deux vivaient à deux endroits différents, séparés par une seule ligne — une
relecture du compteur, donc un point de suspension.

Cette ligne suffisait : une annulation qui tombe là emporte tout **sans passer par la reprise**.
La fenêtre fait quelques microsecondes, et le test écrit pour un autre défaut tombait dedans à tous
les coups, parce qu'il attend précisément que le compteur bouge pour annuler.

> **Séparés, un geste et sa reprise laissent toujours un instant où l'un a eu lieu et l'autre est
> devenu inatteignable.**

⚠️ Et la reprise elle-même doit être **`NonCancellable`** : sur un chemin annulable, rattraper une
annulation avec du code annulable ne rattrape rien.

## §23 — Ce que Dart ne peut pas vous apprendre : les tableaux d'octets partagés

Dart est mono-fil. Un `Uint8List` rendu par une méthode ne peut pas être vidé pendant que
l'appelant s'en sert, et le code d'origine s'appuie là-dessus sans le dire.

Kotlin n'a pas cette propriété. Rendre le tableau d'une session ouvrait ceci :

```
coroutine A : val cle = sessionKey(f)      // référence sur le tableau de la session
coroutine B : lock(f)                      // remplit CE MÊME tableau de zéros
coroutine A : VaultCrypto.seal(cle, …)     // chiffre sous une clé nulle
```

La note serait partie en base **scellée sous une clé de zéros** : présentée comme protégée,
définitivement illisible, et sans aucune erreur pour le signaler. Le verrouillage automatique est
justement conçu pour tomber sans prévenir — la fenêtre n'a rien de théorique.

> **Chaque fois qu'un portage rend un tableau mutable partagé, se demander ce qui se passerait si
> une autre coroutine le modifiait à cet instant.** L'original ne pouvait pas se poser la question.

## §24 — « Cet échec prouve-t-il quelque chose ? » est une question de perte de données

Cinq échecs détruisent un coffre à code. Un échec système classé comme échec utilisateur détruit
donc les notes de quelqu'un qui n'a rien fait de mal.

Cinq des six défauts de la phase 4 étaient là — **aucun dans la cryptographie**, que les vecteurs de
parité avaient déjà fermée. Le tableau de référence est dans
[11-COFFRES.md](11-COFFRES.md) §5, et la règle dans `01-DECISIONS.md` D-012.

> **Un vecteur de parité ne dira jamais qu'un coffre s'est détruit pour la mauvaise raison.**

## §25 — Un correctif de motif se propage, ou il crée le jumeau qu'il prétend fermer

Le 2026-08-14, une relecture externe fait corriger un point : le mode d'un coffre se déduit de ses
**colonnes**, pas de l'étiquette `vault_mode`. Le correctif est appliqué à `VaultMaterial`.

Quelques heures plus tard, un audit de cohérence trouve `FolderMapper.toDomain()` qui lit toujours
l'étiquette. Le KDoc de ce même fichier explique pourtant, sur huit lignes, pourquoi `vault_mode`
n'est pas fiable — la règle y était écrite, appliquée au prédicat « est-ce un coffre ? », et pas à
la question voisine « quel genre de coffre ? », deux lignes plus bas.

**Quatrième occurrence du jumeau asymétrique sur ce projet, et la première née d'un correctif de la
même journée.**

> **Après avoir corrigé un motif, passer l'outil qui cherche ce motif ailleurs.** L'engagement pris
> de mémoire ne suffit pas : la règle était connue, et le second site a quand même été oublié.

Le correctif durable n'est pas de rectifier le second site, c'est de faire en sorte qu'il n'y ait
plus deux sites : la règle vit désormais dans `VaultMode.fromMaterial`, seul endroit où la question
se tranche.

⚠️ Et son ancien mécanisme — `VaultMode.from(stored)`, le champ `stored` — a été **supprimé** avec
son dernier lecteur. Un mécanisme sans lecteur n'est pas de la documentation, c'est un chemin mort
qui invite à s'en resservir.

## §26 — `runCatching` autour d'un appel **annulable**, encore

Le §8 l'interdit déjà. Quatre sites l'enfreignaient quand même dans le code des coffres, dont
l'effacement d'un coffre à code — le chemin le plus destructeur du fichier. Et cela pendant que la
fonction voisine, écrite une heure plus tôt, appliquait la règle avec soin.

`runCatching` attrape `Throwable`, donc `CancellationException` : il transforme « cette coroutine
doit s'arrêter » en « cette opération a raté », et la boucle continue de tourner dans une coroutine
qui n'existe plus.

⚠️ **Mais la règle ne porte que sur les appels ANNULABLES.** Les `runCatching` qui entourent des
préférences ou le magasin de clés — `security/kek/`, `AndroidVaultKeystore` — sont corrects et
restent : aucune `CancellationException` ne peut y naître. Appliquer un piège en aveugle produit du
bruit, ce qui finit par le faire ignorer là où il compte.


## §27 — `painterResource(R.mipmap.ic_launcher)` fait planter l'application sur API 26+

**Trouvé le 2026-08-14, sur appareil, et par rien d'autre.**

À partir de l'API 26, `ic_launcher` résout vers `mipmap-anydpi-v26/ic_launcher.xml`, qui est un
conteneur `<adaptive-icon>` — pas une image. Compose lève :

```
java.lang.IllegalArgumentException: Only VectorDrawables and rasterized asset types are supported
```

**Ce qui rend ce défaut particulier**, et pourquoi il mérite une entrée :

| Contrôle | L'a-t-il vu ? |
|---|---|
| compilation Kotlin | non |
| ktlint, detekt | non |
| `lintDebug` | non |
| 70 tests JVM | non |
| appareil API 24-25 | **le défaut n'existe pas** — la résolution retombe sur le PNG |
| appareil API 26+ | plantage immédiat |

Il ne se manifestait qu'à la **première installation**, l'écran de présentation ne rejouant jamais
ensuite. Un essai sur un appareil déjà équipé ne l'aurait pas montré.

**Le remède** : `R.drawable.ic_launcher_foreground`, qui est un PNG.

**La leçon transférable** : une ressource dont la résolution dépend du palier d'API n'est pas une
constante. `R.mipmap.ic_launcher` désigne deux choses différentes selon l'appareil, et un seul des
deux est chargeable par Compose.

## §28 — Un `%` littéral dans une chaîne SANS argument fait échouer `lintDebug`

« 100 % hors-ligne » n'est pas une chaîne de format, mais aapt et lint la lisent comme telle et
voient `% h` comme une conversion inachevée.

⚠️ **La correction n'est PAS de doubler en `%%`.** `getString(int)` n'appelle jamais
`String.format` : le doublement s'afficherait tel quel. La seule réponse juste est
`formatted="false"` sur l'élément.

Le contrôle est légitime, pas un faux positif : la même chaîne avec un argument planterait à
l'exécution.

## §29 — Un commentaire juste rend un défaut PLUS difficile à voir qu'un commentaire absent

**Trouvé le 2026-08-14 par une relecture externe, sur du code relu deux fois.**

`PinSheet` portait ce commentaire :

> ⚠️ Le code saisi est vidé après CHAQUE tentative, réussie ou non.

Le code, lui, n'effaçait la saisie que sur **succès** : `ResultatDeTentative` n'appelait
`onConsumed` que pour `VaultAttempt.Success`. Après un code faux, les chiffres restaient à l'écran.

Conséquence : retaper par-dessus donne « 1234 » + « 5678 », valide une saisie de six chiffres qui
n'est celle de personne, et **consomme une seconde tentative**. Sur un coffre qui se détruit au
cinquième échec, deux frappes en perdent deux.

**Ce qui rend le piège spécifique** : à la relecture, l'œil lit le commentaire, le trouve correct,
et passe. Un commentaire absent aurait obligé à lire le code. Le commentaire juste a **protégé** le
défaut.

⇒ **Un commentaire qui décrit une garantie doit être relu comme une ASSERTION à vérifier**, pas
comme une explication à comprendre. « Le code fait X » se vérifie en cherchant où X est fait.

## §30 — Le chemin mort revient, et il revient toujours par le même angle

**Troisième occurrence dans ce portage.** À chaque fois, une fonction correcte, testée ou testable,
documentée comme câblée — et sans aucun appelant en production.

| Quand | Quoi | Conséquence si personne ne l'avait vu |
|---|---|---|
| phase 4 | `VaultSessions.sweep()` | la moitié active de l'auto-verrouillage n'existait pas |
| phase 5 | `encryptAllNotesInFolder` | un dossier converti en coffre gardait ses notes en clair |
| phase 5 | (côté i18n) chaînes sans consommateur | fonctionnalité annoncée, écran absent |

Les deux questions à poser sont **différentes** et il faut les poser toutes les deux :

1. *« Si la condition devient vraie une seconde plus tard, qui rappelle ce code ? »*
2. *« Qui appelle ce code ? »*

⚠️ **La documentation ne prouve rien.** `docs/11-COFFRES.md` §8 affirmait que les quatre gestes
étaient câblés, écrit le jour même où trois l'étaient. Un `grep` du nom de la fonction est le seul
contrôle qui vaille — et il coûte deux secondes.

## §31 — `NonCancellable` ne sauve rien si la PORTÉE est déjà annulée

**Trouvé le 2026-08-14 par une relecture externe, sur un correctif de la phase 4 réappliqué.**

L'enregistrement final de l'éditeur s'écrivait :

```kotlin
viewModelScope.launch { withContext(NonCancellable) { enregistrer() } }
```

`viewModelScope` est annulé à l'instant où l'écran disparaît — c'est-à-dire **exactement** quand ce
geste est demandé. Et `withContext(NonCancellable)` ne protège qu'une coroutine **déjà démarrée** :
sur une portée annulée, `launch` crée une coroutine qui n'exécute jamais son corps.

⚠️ **La leçon de la phase 4 était incomplète.** Elle disait « la reprise doit être
`NonCancellable` ». Elle est vraie et insuffisante : il faut aussi que la **portée survive au geste
qu'elle exécute**. Un geste de sauvetage appartient à une portée applicative, pas à celle de l'objet
en train de mourir.

⚠️ **Et l'état doit être capturé AVANT**, pas relu dans la coroutine : au moment où elle s'exécute,
l'objet dont elle lit l'état peut avoir été vidé.

## §32 — Comparer l'affiché à l'entité en base est faux dès qu'il y a du chiffrement

L'éditeur décidait s'il devait enregistrer ainsi :

```kotlin
if (courant.title == note.title && courant.content == note.content && !note.isLocked) return
```

Pour une note scellée, `note.title` vaut la **chaîne vide** — le titre vit dans le chiffré. La
question « le titre a-t-il changé ? » répondait donc **toujours oui**. Et le `!note.isLocked`
faisait sauter la garde pour les seules notes où elle comptait.

Résultat : **ouvrir une note de coffre pour la lire, puis revenir, la rechiffrait** et repoussait sa
date de modification. Elle remontait en tête de liste sans que personne n'y ait touché.

⇒ **Un état d'écran se compare à ce qu'il a CHARGÉ, jamais à la représentation persistée** — celle-ci
n'est pas la même donnée dès qu'une transformation s'intercale.

## §33 — `showSnackbar` suspend le collecteur qui l'appelle

Appelé directement dans un `collect`, il bloque la collecte pendant toute la durée d'affichage. Les
événements suivants s'empilent dans le tampon, et une fois celui-ci plein, `emit` bloque l'émetteur —
donc le ViewModel.

⇒ Toujours `portee.launch { snackbars.showSnackbar(...) }` depuis un collecteur.

## §34 — Le message d'une exception interne n'est pas un texte d'interface

`VaultValidationException.message` valait « saisie refusee : PASSPHRASE_TOO_SHORT ». Il remontait
jusqu'à l'écran, **en français comme en anglais**, alors que six des huit raisons avaient une chaîne
traduite qui existait depuis le début et n'était jamais utilisée.

⇒ Une exception destinée à l'interface transporte une **énumération**, pas un message. Un `when`
exhaustif côté affichage fait alors échouer **à la compilation** l'ajout d'un cas sans traduction.

## §35 — « Le geste s'exécute » n'est pas « le geste s'exécute EN DERNIER »

Annuler un travail différé (`Job.cancel()`) n'arrête pas une écriture de base **déjà engagée**. La
sauvegarde différée de l'éditeur pouvait donc se terminer **après** la sauvegarde finale et réécrire
une version plus ancienne.

Le correctif précédent avait garanti que la finale s'exécute — en la déplaçant dans une portée
applicative. Il n'avait rien garanti sur l'**ordre**. Les deux propriétés sont distinctes, et une
seule était traitée.

⇒ Deux écritures concurrentes sur la même donnée se **sérialisent** (`Mutex`), elles ne s'annulent
pas l'une l'autre.

## §36 — Un enchaînement de deux gestes ne doit pas laisser le second décider du message du premier

`créer le coffre` puis `chiffrer les notes existantes` étaient enchaînés dans une même tentative.
L'échec du second faisait afficher « création impossible » — alors que le coffre existait déjà en
base, avec ses notes en clair.

⇒ Quand un geste A a **déjà modifié la base**, aucun échec de B ne doit produire un message qui nie
A. Il faut une issue distincte qui dise les deux : *« le coffre est créé, son contenu n'a pas pu
être chiffré »*.

## §37 — Un compteur de références ne manque à personne tant qu'il n'y a qu'un poseur

`FLAG_SECURE` était posé depuis un seul endroit en phase 5 : le réglage utilisateur. Un booléen s'y
comportait exactement comme un compteur, et le défaut n'était pas observable — il **attendait le
second poseur**.

Dès que les feuilles de coffre et le mode panique posent le même drapeau, la fermeture de la feuille
appelle `clearFlags` et découvre l'éditeur d'une note de coffre resté ouvert derrière. Rien ne le
signale.

⚠️ **La borne à zéro n'est pas décorative.** Un `release()` de trop laisserait le compteur négatif, et
le `force()` **suivant** ne le ramènerait qu'à zéro : pas de protection au moment précis où quelqu'un
vient d'en demander une. Le déséquilibre reste un défaut ; la borne décide seulement de quel côté il
échoue.

**À se demander à chaque garde partagée** : « qui d'autre pose ceci, et que se passe-t-il quand le
premier des deux part ? »

## §38 — `EXTRA_STREAM` seul ne propage aucune permission d'URI

Le système ne propage une permission que pour ce qu'il **voit** dans l'intention : sa donnée
principale et ses `ClipData`. Un fichier passé par le seul `EXTRA_STREAM` est un extra parmi
d'autres, que rien n'inspecte.

Mesuré sur le S9 le 2026-08-14 :

```
SecurityException: Permission Denial: reading FileProvider uri … from uid=1000
ChooserActivity: extract fail
```

⚠️ **Le défaut est presque invisible** : le partage vers l'application choisie fonctionnait, seul
l'aperçu du sélecteur manquait, et l'exception restait dans le journal. Correctif :
`clipData = ClipData.newRawUri(null, uri)` **en plus** de `FLAG_GRANT_READ_URI_PERMISSION`.

## §39 — `edit().clear()` CRÉE le fichier de préférences s'il n'existe pas

Le mode panique faisait apparaître `FlutterSecureStorage.xml` et `FlutterSecureKeyStorage.xml` sur un
appareil qui n'avait **jamais** vu la version Flutter — deux fichiers vides, portant le mot
« SecureStorage », créés par le geste censé tout effacer, juste avant un écran qui annonce qu'il ne
reste rien.

Aucun secret n'y était. **Ce qui est faux d'un octet est faux** sur cet écran-là.

Correctif : contrôler l'existence du fichier, puis `deleteSharedPreferences`, puis **vérifier** qu'il
a disparu.

## §40 — Fermer une base ne l'empêche pas de se rouvrir toute seule

`DatabaseProvider.close()` oublie l'instance ; le prochain `get()` en ouvre une neuve. Pendant une
panique, ce `get()` **arrive** — un `Flow` de Room encore abonné, une portée applicative qui n'a pas
fini. Ne trouvant plus ni clé ni fichier, la fabrique en génère une paire NEUVE.

Résultat : un fichier de base recréé quelques millisecondes après l'effacement, sur un appareil dont
on vient d'annoncer qu'il n'en restait rien — et l'étape d'effacement, elle, avait vérifié la
disparition **avant** la recréation et s'était déclarée réussie.

⚠️ Le sceau qui l'empêche vit dans un objet unique du graphe d'injection, donc **aussi longtemps
que le processus**. L'écran de fin doit terminer le processus (`exitProcess`) et pas seulement
l'activité, sinon le lancement suivant trouve une base scellée et ne démarre plus, sans explication.

## §41 — Deux défilements verticaux imbriqués tuent l'application

L'écran de fin du mode panique portait son propre `verticalScroll` et était posé **dans** la colonne
défilante des réglages. Contraintes de hauteur infinies, `IllegalStateException`, processus tué.

⚠️ **La destruction, elle, s'était bien exécutée.** L'utilisateur se retrouvait sur son écran
d'accueil sans savoir si ses notes avaient été effacées. Sur cet écran-là, c'est le pire échec
possible — et aucun des quatre outils du gate ne l'a vu.

Un recouvrement plein écran se pose **en frère du `Scaffold`**, jamais dans son contenu.

## §42 — `git status` marque un fichier modifié sans qu'il le soit

Les trois fichiers l10n de `notes_tech` ont été protégés pendant tout le portage — « modifiés avant
mon intervention, ne jamais y toucher ». Vérification faite le 2026-08-14 : leur contenu est
**identique à `HEAD` au caractère près**, seules les fins de ligne diffèrent. `git diff` ne rend
rien, `git diff --numstat` non plus.

La précaution était juste, sa **prémisse était fausse** — et elle a bloqué pendant deux phases un
correctif d'i18n qui ne coûtait rien.

⚠️ Avant de bâtir une règle sur un « M » de `git status`, demander à `git diff` **ce qui** a
changé. Contrôle en une ligne :

```bash
diff <(git show HEAD:<fichier> | tr -d '
') <(tr -d '
' < <fichier>)
```

## §43 — Une garde qui protège d'une situation impossible est PIRE qu'absente

Le chargement de l'éditeur a porté, une heure durant le 2026-08-15, un
`catch (VaultPinWipedException)` accompagné d'un commentaire expliquant qu'il évitait de proposer un
déverrouillage sur un coffre auto-détruit. **`FolderVaultService.decrypt` ne lève jamais cette
exception** : les trois `throw` sont dans les chemins de déverrouillage, qui aboutissent ailleurs.

Troisième occurrence du motif dans ce portage, après `sweep()` et `encryptAllNotesInFolder`. Les deux
premières étaient héritées ; celle-ci a été écrite ici, quarante minutes après que la même erreur ait
été corrigée ailleurs.

⚠️ Une garde morte ne se contente pas d'être inutile : **elle fait croire que le cas est traité**, et
la prochaine personne qui cherche « que se passe-t-il si… » trouve une réponse rassurante et fausse.

Le contrôle qui l'a fait tomber tient en une question, à poser pour **chaque** `catch` ajouté :

> Cette exception, la fonction appelée peut-elle seulement la lever ?

Un `grep` du `throw` y répond.

## §44 — `catch` sur un `Flow` est TERMINAL

`SearchViewModel` a reçu un filet contre les erreurs de base :

```kotlin
combine(query, resultats, dossiers) { … }
    .catch { emit(EtatDEchec) }   // ❌ termine le flux
    .stateIn(…)
```

`catch` émet **puis complète le flux**. Le `combine` meurt avec lui : après la première erreur, plus
aucune frappe n'était servie, et la recherche restait figée **jusqu'à la destruction du ViewModel**.

*Le filet censé protéger d'une panne passagère la rendait définitive.*

Correctif : poser le `catch` **à l'intérieur** du `flatMapLatest`, sur le flux d'une seule requête.
La suivante repart d'un flux neuf.

```kotlin
flatMapLatest { texte ->
    search.observe(texte).map { Issue(it) }.catch { emit(Issue(echec = true)) }   // ✅
}
```

⚠️ Le symptôme est muet : le code compile, le premier échec s'affiche correctement, et c'est **la
suite** qui n'arrive jamais.

## §45 — Le presse-papiers ne se lit pas sans focus, et un test l'ignore en silence

Depuis Android 10, une application qui n'a pas le focus ne peut pas lire le presse-papiers :
`getPrimaryClip()` rend `null` quoi qu'on y ait mis.

Deux conséquences :

1. **L'effacement différé n'est fiable qu'au premier plan.** Il ne peut pas vérifier que le contenu
   est encore le sien, et il ne doit pas effacer à l'aveugle — un secret copié ailleurs entre-temps
   ne nous appartient pas.
2. 🔴 **Une suite instrumentée n'a pas de fenêtre.** Les six premiers tests de `SensitiveClipboard`
   levaient tous leur `assumeTrue`, et l'instrumentation affichait **`OK (6 tests)`** : une ligne
   verte couvrant zéro.

⚠️ Lancer l'application juste avant **ne suffit pas** : `am instrument` redémarre le processus. Il
faut une activité **résumée dans ce processus-ci** :

```kotlin
@HiltAndroidTest
class …Test {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @Before fun preparer() { hilt.inject(); scene = ActivityScenario.launch(MainActivity::class.java) }
}
```

⚠️ Corollaire de vérification : `clearPrimaryClip()` fait rendre `null` à la lecture **suivante**.
Traiter « illisible » comme un échec d'effacement ferait échouer **toutes** les purges réussies.

## §46 — Un hôte de messages posé dans un tiroir modal est invisible quand le tiroir est ouvert

`SnackbarHost` était placé dans le contenu de `ModalNavigationDrawer`, donc **sous** le panneau du
tiroir. Or c'est du tiroir que partent les gestes qui ont le plus besoin d'être confirmés :
conversion en coffre, retrait de protection, et le « N notes déchiffrées » qui apprend à quelqu'un
que son dossier n'est **plus** protégé.

Tous invisibles tant que le tiroir reste ouvert — **c'est-à-dire dans le cas normal**, puisque rien
ne le ferme après l'action.

Correctif : un `Box` autour du tiroir, l'hôte dessiné **après** lui.

⚠️ Aucune relecture statique ne pouvait le voir. Il fallait regarder l'écran.

## §47 — Une action libellée de plus dans une `TopAppBar` écrase le titre à zéro

Le bouton « Terminé » de l'éditeur, en `FilledTonalButton` portant son mot, faisait cinq éléments
d'action. Mesuré sur un S9 : le titre — nom du dossier **et** état de l'enregistrement — tombait à
**zéro pixel de large**. Il ne rétrécissait pas, il disparaissait.

L'application publiée peut se le permettre parce que son titre **est** l'indicateur, sur une seule
ligne étroite. Ici il en porte deux.

⚠️ Une icône avec sa `contentDescription` reste visible et découvrable, à la largeur des autres.

## §48 — `values/strings.xml` est GÉNÉRÉ : n'y supprimez pas d'orphelines

Les deux fichiers de chaînes sont transposés depuis l'ARB par `outils/arb_vers_strings.py`. Une
suppression y reviendrait au prochain passage.

Et surtout, une chaîne orpheline n'est pas toujours un déchet. Sur les 82 relevées le 2026-08-15,
**aucune** n'était morte des deux côtés — chacune correspondait à du comportement de l'application
publiée. Trois catégories seulement :

| Cas | Que faire |
|---|---|
| Fonctionnalité manquante ici | la porter — c'est une **régression**, pas une décision en attente |
| En attente d'une phase à venir | laisser, et le dire |
| Le portage fait **mieux** sans | laisser, et écrire pourquoi **à côté de ce qui la remplace** |

Exemple du troisième cas : `panic_announce_done` vaut exactement `panic_complete_title`, déjà annoncé
par une région active — laquelle dit la vérité dans les **deux** cas, y compris « la clé n'a PAS été
détruite ». La câbler ferait annoncer « effacement terminé » sur une clé survivante.

⚠️⚠️ **La réciproque est pire, et elle a mordu le même jour.** §49.

## §49 — Un générateur DÉTRUIT ce qu'on ajoute à la main dans sa sortie

Suite directe de §48 : `strings.xml` est généré, donc ce qu'on y écrit à la main **disparaît** au
passage suivant. Le 2026-08-15, une régénération a effacé **huit chaînes** ajoutées au fil des
phases, dont deux du jour même (`note_editor_copy_empty`, `trash_emptied`) — et le code qui les
référence aurait cessé de compiler.

Le mécanisme de conservation existait depuis la phase 1. Il ne portait que l'écran d'échec au
démarrage, et **personne n'y avait ajouté les suivantes**.

> ⚠️ **Un garde-fou qui existe ne protège que ce qu'on a pensé à lui confier.** Sa présence dans le
> dépôt ne dit rien de sa couverture. Trois des huit chaînes perdues avaient été écrites *après*
> lui, par quelqu'un qui l'avait forcément vu en haut du fichier.

**Ce qui a permis de le voir** : `git diff --stat` après avoir lancé le script. Il annonçait
`13 insertions, 50 deletions` pour l'ajout d'**une** chaîne. Un générateur dont la sortie perd des
lignes quand l'entrée en gagne se relit avant d'être committé.

**Le correctif** : `AJOUTS_EN` / `AJOUTS_FR`, un bloc par section d'écran, rendus **dans** la section
plutôt qu'en fin de fichier — c'est précisément l'isolement en fin de fichier qui les avait fait
oublier. Plus `REMPLACEES`, pour les clés de l'ARB dont le portage réécrit la valeur : elles sont
sautées à la transposition, ce qui évite un doublon que le contrôle d'intégrité aurait fait lever.

**Le contrôle qui manquait**, et qui est maintenant la règle : après toute exécution du générateur,
comparer les **ensembles de noms** de ressources avant/après. Un `git diff` se lit de travers ; un
`set(avant) - set(apres)` non vide ne se lit pas de travers. Puis relancer le script une seconde
fois : sa sortie doit être identique octet pour octet. **Un générateur qui n'est pas idempotent
perd quelque chose, et on ne sait pas encore quoi.**

⚠️ Au passage, le même script portait un marqueur « ⚠️ Aucun écran ne consomme encore ces chaînes —
phase 6 » sur les sections `panic` et `export`, câblées depuis la clôture de la phase 6. Le fichier
généré affirmait donc en tête de deux sections le contraire de ce que fait le code. Une marque
« pas encore câblé » se retire quand ça l'est, sinon elle apprend à ne plus lire les marques.
