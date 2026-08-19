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

## §50 — Trois actions ne rentrent pas dans les deux emplacements d'un `AlertDialog`

« Supprimer le dossier ? » proposait trois actions : déplacer, annuler, supprimer définitivement. Les
deux dernières étaient empilées dans une `Column` posée en `dismissButton`.

**Mesuré sur le S9 le 2026-08-15** : la rangée d'actions d'un `AlertDialog` est **bornée en
hauteur**. La pile réclamait 288 px, elle en recevait 216. Le dernier bouton mesurait **72 px au lieu
de 144**, son libellé tronqué à mi-hauteur, coupé net à la limite du dialogue.

> ⚠️⚠️ **C'était « Supprimer définitivement » — l'action irréversible était celle qu'on ne voyait
> pas.** Un dialogue de confirmation qui cache l'option qui détruit tout est pire que pas de
> dialogue : il fait croire qu'on a choisi en connaissance de cause.

**Deux correctifs plausibles n'ont rien changé**, et c'est le plus instructif :

| Tenté | Résultat |
|---|---|
| Rendre le corps défilant | aucun effet — la borne ne venait pas du texte |
| Réduire le remplissage vertical des boutons | aucun effet — la borne ne vient pas du contenu |

La borne vient de **l'emplacement lui-même**. Material le dit d'ailleurs : au-delà de deux actions,
on présente une **liste de choix**, pas une rangée de boutons.

**Le correctif** : les deux choix descendent dans le corps du dialogue, en pleine largeur, et il ne
reste qu'« Annuler » comme action. Vérifié à l'écran : les trois libellés font 60 px, aucun n'est
tronqué.

> ⚠️ **Deux correctifs qui ne changent rien sont un diagnostic faux, pas un correctif insuffisant.**
> Continuer à ajuster des valeurs après le premier essai sans effet, c'est traiter un symptôme dont
> on n'a pas trouvé la cause. Le relevé `uiautomator` — 72 px contre 144 — disait dès le départ que
> la contrainte était structurelle.

## §51 — L'ordre d'une séquence de destruction se juge sur ce qui reste LISIBLE

Le mode panique détruisait la clé de la base à l'étape 4, puis effaçait les archives d'export à
l'étape 8 — derrière le fichier de base, derrière les modèles hérités (**plusieurs secondes sur
530 Mo**) et derrière les préférences.

Or, une fois la clé détruite, tout ce qui reste ailleurs est du **bruit**. Les archives d'export
sont les **seuls fichiers en clair** de l'application. Un processus tué entre l'étape 4 et l'étape 8
laissait donc une base illisible **et des notes parfaitement lisibles à côté**, coffres ouverts
compris.

> ⚠️ **On n'ordonne pas une destruction par taille ni par « importance » supposée, mais par ce qu'un
> arrêt brutal laisserait de lisible à cet instant.** La clé d'abord, parce qu'elle couvre tout d'un
> coup et pour un coût quasi nul ; le clair juste après, parce que plus rien ne le protège ; le reste
> ensuite, dans n'importe quel ordre.

**Ce qui a mis sur la piste** : le commentaire de classe, qui promettait « une interruption à
n'importe quel instant laisse l'état le plus sûr atteignable ». Les deux relectures externes l'ont
relevé, l'une par l'ordre, l'autre par la promesse. *Une garantie écrite dans un commentaire n'est
pas une garantie tenue par le code — mais elle sert de test, à qui la lit sérieusement.*

⚠️⚠️ **Le piège de la correction** : `PanicReportTest.sequenceFigee` fige l'ordre de l'**énumération**,
pas celui de l'exécution. Déplacer l'étape dans `executer()` sans la déplacer dans l'`enum` aurait
laissé un test **vert** dont le nom affirme « la séquence est exactement celle qu'on croit ». Les
deux ont été déplacées ensemble, et un test nomme désormais la contrainte réelle : le clair part
**immédiatement** après la clé.

## §52 — Un message d'échec doit décrire le RÉSIDU, pas le nombre d'étapes ratées

L'écran de fin de panique affichait, pour tout nettoyage raté, « des fichiers **illisibles** peuvent
subsister sur l'appareil ». C'est vrai de toutes les étapes sauf une : **les archives d'export sont
du clair**.

Si c'était précisément celle-là qui échouait, la phrase rassurante décrivait l'inverse de la
situation — à quelqu'un qui vient de déclencher une destruction sous contrainte et qui décide, sur
cette phrase, s'il peut se séparer de son appareil.

> ⚠️ **Compter les échecs ne dit rien de ce qu'ils laissent.** `failedSteps.size` est la même valeur
> pour un cache non purgé et pour une archive en clair intacte. Le message doit venir de la **nature**
> de l'étape ratée.

`PanicReport.clairPeutSubsister` porte la distinction, et une chaîne dédiée la dit. Ce n'est **pas**
une quatrième issue globale : la garantie minimale reste acquise, seule la nature du résidu change.

⚠️ La relecture qui a trouvé ça avait été précédée d'une autre concluant « rien trouvé » sur le même
axe. La seconde avait regardé la **logique des branches** — correcte — et pas le **texte** qu'elles
affichent. *Vérifier qu'un `when` choisit la bonne branche ne vérifie pas que la branche dit vrai.*

## §53 — Un état se REGARDE ; le journal des étapes ne le remplace pas

`clairPeutSubsister` répondait « du clair reste-t-il ? » en lisant l'issue d'une étape :
`EXPORTS_WIPE a-t-elle échoué ?`. Deux réponses fausses, en sens inverse :

- **faux positif** — l'étape échoue, puis `CACHE_PURGE` emporte quand même le répertoire (elle traite
  `exports` comme un artefact sensible). Plus rien de lisible, et l'écran alarme quand même ;
- **faux négatif** — l'étape réussit, mais **le presse-papiers**, lui, a échoué. Une note copiée y
  attend en clair, lisible par toute application au premier plan, et l'écran affiche « des fichiers
  illisibles peuvent subsister ».

La propriété mesure désormais le disque à la fin de la séquence, et prend l'issue de l'étape
seulement pour ce qui ne se mesure pas — le presse-papiers, qu'Android refuse de relire sans focus.

> ⚠️ **« Cette étape a échoué » n'est pas « cet état est vrai ».** Entre les deux, tout ce que les
> étapes suivantes ont pu faire.

⚠️ En cas de mesure impossible (`SecurityException`), la propriété vaut **`true`** : on n'annonce pas
une protection qu'on n'a pas constatée.

## §54 — Trois défauts de plus, tous nés du correctif de la veille au soir

Les cinq correctifs de l'audit export/panique ont été relus à leur tour. Résultat :

| Ce que le correctif introduisait | Ce que la relecture a trouvé |
|---|---|
| `effacerOuSignaler`, pour ne plus jeter le retour de `delete()` | `exists()` et `delete()` lèvent `SecurityException` — la fonction **masquait l'exception d'origine**, alors que son KDoc promettait « on ne lève pas ici » |
| `clairPeutSubsister`, pour ne plus mentir sur le résidu | **oubliait le presse-papiers**, et son KDoc affirmait que l'export était « la seule » source de clair |
| `folders.find` pour marquer une note claire d'un dossier coffre | **nouvel accès base hors du `try`** : un export d'une seule note qui marchait pouvait désormais échouer |

> 🔴 **Deux des trois défauts étaient des commentaires écrits vingt minutes plus tôt, dans le
> correctif même.** Écrire « on ne lève pas ici » ne fait pas que le code ne lève pas. La phrase qui
> décrit une garantie doit être vérifiée comme du code — c'est la troisième fois de la journée.

⚠️ **Un correctif de marquage a failli coûter un export.** Ajouter une lecture de base pour enrichir
un libellé, c'est ajouter un mode de panne à un chemin qui n'en avait pas. La lecture est désormais
enveloppée et retombe sur l'ancien critère : *perdre une mention vaut mieux que perdre l'export.*

---

## §55 — `withContext` vérifie l'annulation **au moment de rendre sa valeur**

Le 08-15, la boucle de capture a reçu un `ensureActive()` à chaque tour : une portée annulée devait
cesser d'écrire de la voix sur le disque, et le fichier partiel devait être effacé par le `catch`.

C'était juste, et insuffisant. Le contrôle couvre **la durée de la boucle, et rien après** :

```kotlin
return verrou.withLock { withContext(Dispatchers.IO) { capturer() } }
```

`capturer()` se termine normalement — dernier tampon écrit, en-tête corrigé, fichier rendu. Puis
`withContext`, en rendant la valeur, constate que le travail a été annulé entre-temps et lève une
`CancellationException` **à la place du fichier**. Le `try/catch` de `capturer()` est déjà refermé :
il n'a rien à effacer. L'appelant, qui devait transcrire puis supprimer, ne reçoit jamais le nom.

**Résultat : un WAV de voix en clair dans le cache, que plus personne ne connaît.** La fenêtre dure
quelques millisecondes et s'ouvre par le geste le plus banal qui soit — quitter l'écran au moment où
l'on relâche le bouton.

**La parade** est de retenir le fichier **hors** du `withContext`, et de l'effacer si le résultat ne
parvient pas à l'appelant :

```kotlin
val produit = AtomicReference<File?>(null)
try {
    return verrou.withLock { withContext(Dispatchers.IO) { capturer()?.also(produit::set) } }
} catch (e: Throwable) {
    produit.get()?.let(::effacerOuSignaler)
    throw traduire(e)
}
```

⚠️ **La leçon générale** : une garde posée *dans* une fonction ne protège pas la remise de son
résultat. Partout où le fait de rendre une valeur engage quelqu'un d'autre à faire le ménage, la
remise elle-même est un point de défaillance. Relevé par une relecture externe (GPT-5.5, 08-16), un
jour après que la même famille de défaut eut été corrigée un cran plus bas.

---

## §56 — Deux répertoires jumeaux, une seule ligne dans la liste : l'asymétrie ne se voit pas

`PanicStep.CACHE_PURGE` se termine par un contrôle : si un artefact **sensible** survit au balayage
du cache, l'étape échoue et l'écran de fin le dit. La liste était :

```kotlin
n == "exports" || n.endsWith(".zip") || n.endsWith(".md") || n.endsWith(".wav")
```

`captures/` n'y figurait pas. Deux répertoires voisins, tous deux porteurs de clair — le texte des
notes d'un côté, la voix qui les dicte de l'autre — et un seul surveillé. Un répertoire de captures
survivant passait **en silence**, sur le seul contrôle dont le rôle est de regarder ce que les étapes
ont laissé.

⚠️ Le test `.wav` ne rattrapait rien : `listFiles()` ne rend que le **premier niveau**, donc le nom
examiné est celui du répertoire, jamais celui des enregistrements qu'il contient. Une garde qui
ressemble à une couverture.

**Le correctif utile n'est pas d'ajouter `"captures"`**, c'est de **demander les deux noms à ceux qui
écrivent ces répertoires** :

```kotlin
n == NoteExporter.repertoireDExport(context).name.lowercase() ||
    n == VoiceCapture.repertoireDeCapture(context).name.lowercase() || …
```

La règle « une seule définition du répertoire » était déjà écrite pour l'**effacement**. Elle vaut
autant pour le **contrôle** : un littéral recopié ne suit pas celui qui écrit. Relevé par une
relecture externe (Gemini, 08-16).

---

## §57 — Un inventaire dans un commentaire se périme ; un critère, non

Le KDoc de `clairPeutSubsister` s'est trompé **deux fois, par la même faute** :

| Version | Affirmation | Ce qui manquait |
|---|---|---|
| 08-15 matin | « l'export est **la seule** » | le presse-papiers |
| 08-15 soir | « **deux choses seulement** sont du clair » | les enregistrements de dictée |

Les deux fois, la phrase était **exacte le jour où elle a été écrite**. Les deux fois, elle a été
prise pour un acquis par la relecture suivante. Et la seconde omission a été introduite dans le
correctif même de la première.

⚠️ Trois autres commentaires du même fichier disaient encore « les seuls fichiers en clair » à propos
des archives d'export, alors que le code mesurait déjà les captures. **Un dépôt ne contient pas un
commentaire menteur : il en contient une famille**, parce qu'ils ont été écrits ensemble.

**La parade tient en une distinction** : une *règle* (« ce qui est mesuré, ce sont les répertoires de
clair ») reste vraie ; un *inventaire* (« il y en a deux ») se périme au premier ajout. Quand les
deux se rédigent dans la même phrase, c'est l'inventaire qui la rend fausse.

⚠️ Corollaire vérifié le 08-16 sur l'énumération `PanicStep` : son en-tête énonçait une règle juste
— *une étape déclarée doit s'exécuter* — puis ajoutait « c'est pourquoi il n'y a ni `voiceCancel` ni
`voiceWipe` ici ». La règle a tenu ; la phrase qui la suivait est devenue fausse le jour même où on
l'a respectée.

---

## §58 — Ce que la source annonce n'engage personne, y compris quand elle annonce zéro

L'import du modèle rejette d'emblée un fichier dont la taille n'a aucun rapport avec le modèle visé :
cela évite de copier et de hacher trois gigaoctets pour découvrir que c'était une vidéo. La taille se
lit dans `OpenableColumns.SIZE`.

**Plusieurs fournisseurs rendent `0` ou `-1`** — stockage en nuage, documents virtuels — là où la
convention voudrait une colonne absente. Le contrôle traitait donc « je ne sais pas » comme « fichier
vide », et **rejetait un modèle parfaitement conforme avant même de le lire**, uniquement parce que
l'application avait cru une métadonnée tierce.

Deux corrections possibles, et la bonne n'est pas la plus radicale :

- ❌ *retirer la garde de taille* — elle a une vraie valeur, écarter une vidéo sans en copier un octet ;
- ✅ **la ramener à ce qu'elle sait faire** : juger une valeur qu'on a, jamais une absence.

⚠️ La borne dure de la copie, elle, se calcule **sur ce que le modèle attend**, jamais sur ce que la
source annonce. C'est ce qui reste quand la métadonnée est muette — et c'est la seule limite qui
n'ait jamais dépendu d'un tiers. Relevé par une relecture externe (GPT-5.5, 08-16).

---

## §59 — Une règle R8 sur une frontière JNI ne prouve rien tant que rien n'appelle

Le code natif cherche ses méthodes **par leur nom**, décoré depuis le paquet, la classe et la
méthode. R8 ne voit aucun appelant Java à une méthode `native` déclarée sans corps : il est donc
fondé à la renommer. Rien ne casse à la compilation ; l'échec arrive à l'exécution, **en release
seulement**, sous la forme d'un `UnsatisfiedLinkError` au premier usage.

D'où la règle habituelle :

```proguard
-keepclasseswithmembernames,includedescriptorclasses class …WhisperNatif {
    native <methods>;
}
```

⚠️⚠️ **Mesuré sur l'APK release du 08-16 : `WhisperNatif` est ABSENTE des dex.** Ce n'est pas un
défaut de la règle — `keepclasseswithmembernames` conserve les *noms*, il n'empêche pas la
**suppression** — mais la conséquence du fait qu'aucun écran n'appelle encore la dictée. R8 a retiré
la chaîne entière, jusqu'à la liaison Hilt.

Trois leçons, dans l'ordre d'importance :

1. **Une règle de conservation n'est éprouvée que par un appelant réel.** Écrire la règle et voir la
   build passer ne dit rien : il n'y avait rien à conserver.
2. **Les tests instrumentés ne couvrent pas ce risque** : ils tournent sur une build *debug*, non
   minifiée. Le contrôle se fait sur l'APK **release**, et sur lui seul — encore la règle du
   2026-08-14, *un contrôle qui ne regarde pas l'artefact publié n'en dit rien*.
3. ⚠️ **La bibliothèque native, elle, est empaquetée quand même** — 2,5 Mo par architecture. Le
   découpage des `jniLibs` ne passe pas par R8. Une release faite aujourd'hui embarquerait donc du
   code natif que rien ne peut atteindre : l'inverse exact du symptôme qu'on redoutait, et tout
   aussi invisible.

**La parade n'est pas de forcer un `-keep`** : ce serait retenir du code que personne n'utilise, et
masquer l'état réel. C'est de noter le contrôle à faire le jour où l'appelant arrive — ce que fait
`proguard-rules.pro`, à l'endroit où on le lira.

---

## §60 — Un `@Test` à corps d'expression fait sauter la classe ENTIÈRE, en silence

JUnit 4 exige que `@Test` et `@After` rendent `void`. En Kotlin, un corps d'expression rend le type
de sa dernière expression :

```kotlin
@After
fun nettoyer() = runBlocking {          // rend Boolean : deleteRecursively() est la dernière ligne
    moteur.dispose()
    repertoire.deleteRecursively()
}
```

JUnit **refuse alors la classe entière**, et la compte pour **un seul échec** nommé
`initializationError`. Sur cinq tests écrits, cinq n'ont jamais tourné, et le total de la suite
n'avait augmenté que de un — 125 → 126.

⚠️ **C'est le décompte qui l'a trahi, pas le message.** « 1 failed » sur une suite de 126 se lit
comme un test qui casse ; il fallait ouvrir le XML pour voir que le nom du cas n'était aucun de ceux
qu'on avait écrits. **Après avoir ajouté N tests, vérifier que le total a augmenté de N** — c'est
deux secondes, et c'est la seule chose qui distingue « un test échoue » de « la classe n'existe pas ».

⚠️ Le piège ne se déclenche que si la dernière expression n'est pas `Unit`. Les tests finissant par
un `assertThat(...)` passent — d'où une classe où *certaines* méthodes déclenchent le refus et
d'autres non, ce qui rend la cause encore moins lisible.

---

## §61 — Une permission sensible demandée pour une action qui ne peut pas aboutir

Le bouton micro de l'éditeur enchaînait : demander `RECORD_AUDIO`, puis vérifier qu'un modèle de
transcription est installé. Sur une installation neuve — c'est-à-dire chez **tout le monde**, au
premier essai — l'utilisateur voyait donc la boîte système du micro, l'accordait ou la refusait, et
recevait ensuite « aucun modèle installé ».

Ce n'est pas une maladresse d'ordonnancement, c'est un coût durable : une permission refusée l'est
**pour de bon**, et une fois « ne plus demander » coché, toute nouvelle demande est ignorée en
silence par Android. Demander le micro avant d'avoir de quoi s'en servir, c'est se le faire refuser
au moment où on en avait le moins besoin.

⚠️ **Ce défaut ne se voit pas à la relecture** : les deux contrôles existaient, tous deux corrects,
et rien dans le code ne dit lequel doit venir en premier. Il s'est vu en **enchaînant les écrans sur
l'appareil**, sur une installation qui n'avait pas encore de modèle.

**Règle générale** : avant de demander une permission, vérifier tout ce qui, sans elle, rendrait
déjà l'action impossible. L'ordre entre un contrôle gratuit et une demande coûteuse n'est jamais
indifférent.

---

## §62 — Du texte destiné à l'utilisateur dans une donnée de domaine

Le catalogue des modèles portait un champ `notes` :

```kotlin
SttModel(id = "whisper-base-q5_1", …, notes = "Conseille. Bonne qualite en francais…")
```

repris tel quel du catalogue Dart. L'écran l'affichait sous le nom du modèle. Deux défauts, dont le
second est le vrai :

1. il était **sans accents**, parce qu'écrit dans un fichier source parmi d'autres identifiants
   ASCII — visible à l'écran, à côté de chaînes correctement accentuées ;
2. il n'était **pas traduit du tout**. `SttModelCatalogue` vit dans `domain/`, qui ne connaît pas
   les ressources Android : la phrase française était servie telle quelle à un utilisateur
   anglophone.

⚠️ **À la relecture, rien ne détonne** : un champ rempli d'une phrase française, dans un fichier
dont les commentaires sont en français, à côté d'autres champs français. C'est l'écran qui l'a
montré — et il l'aurait montré à n'importe qui, sauf à nous, qui testons en français.

**La parade** n'est pas d'accentuer le champ : c'est de le **supprimer**. Un texte que l'utilisateur
lit appartient à `strings.xml`. La correspondance modèle → description se fait dans l'écran, seul
endroit qui connaisse à la fois le catalogue et les ressources.

⚠️ Le repli est `null`, pas une chaîne en dur : une entrée future sans description traduite
n'affichera **rien**, ce qui se remarque — là où un repli en français passerait inaperçu jusqu'à ce
qu'un anglophone le signale.

**Le motif, réutilisable** : chercher, dans les couches sans accès aux ressources, tout `String`
dont la valeur est une phrase. Un identifiant, un chemin, un code de langue n'y posent aucun
problème ; une phrase, si.

---

## §63 — Un piège déjà documenté ne protège pas : il faut le RELIRE avant d'ajouter

`NoteEditorScreen` porte, depuis le 2026-08-15, un commentaire de douze lignes expliquant que
« Terminé » a dû cesser d'être un bouton **libellé** pour redevenir une icône : à cinq éléments
d'action, la `TopAppBar` écrasait le titre **à zéro pixel**, faisant disparaître le nom du dossier et
l'état de l'enregistrement.

Le 2026-08-16, j'ai ajouté le bouton micro dans cette même barre. Six actions. **Le titre est retombé
à 24 pixels** — et c'est Patrice qui l'a vu, sur son appareil, en signalant « un bug d'affichage avec
le mot enregistrer ». Le mot en question était `note_editor_saved`, réduit à rien.

⚠️⚠️ **Le commentaire était juste, à quelques lignes du code que j'écrivais, et il n'a servi à rien.**
Un piège consigné protège de sa propre répétition **à condition d'être relu au moment d'ajouter** —
et rien, dans le geste « j'ajoute un bouton », ne conduit à relire le commentaire du bouton d'à côté.

**La mesure qui tranche**, et qui ne coûte rien :

```
adb shell uiautomator dump ; grep bounds
```

La largeur du titre est un **nombre**. 24 px se distingue de 312 px sans interprétation, là où un
coup d'œil sur l'écran voit « un titre un peu court ».

**Le correctif** : épingle et favori descendent dans le menu de débordement. Ce sont des gestes sur
la *fiche* de la note, occasionnels ; le micro et le lien sont des gestes d'*écriture*, faits pendant
qu'on compose. ⚠️ Écart assumé avec l'application publiée, qui garde les quatre icônes — elle peut se
le permettre parce que **son titre est l'indicateur d'enregistrement, sur une seule ligne étroite**,
là où le portage y a ajouté le nom du dossier. Deux barres qui se ressemblent n'ont pas le même
budget de largeur.

---

## §64 — Piloter l'interface pendant une suite instrumentée la fait échouer

Un `connectedAndroidTest` a rendu **91 tests sur 130, 1 échec** — un test de coffre sans rapport,
tombé sur `The component was not created. Check that you have added the HiltAndroidRule`.

La cause n'était pas le code : je pilotais l'application avec `uiautomator` et `am force-stop`
**pendant** que la suite tournait. Le processus de test a été perturbé, et le run s'est arrêté en
route.

⚠️ Le symptôme est trompeur : un échec Hilt dans un test qui n'y touche pas ressemble à une
régression d'injection. **Le décompte le démasque** — 91 au lieu de 130 veut dire que la suite ne
s'est pas terminée, donc que l'échec n'est pas celui qu'il prétend être. Relancée seule : 130/130.

**Règle** : une suite instrumentée a l'appareil pour elle. Ne rien lancer d'autre dessus tant qu'elle
tourne.

---

## §65 — Un événement qui AGIT et un événement qui PARLE n'ont pas la même exigence

La dictée émettait une seule `IssueDeDictee`, que l'écran consommait. Deux défauts opposés en sont
sortis, à un jour d'intervalle, et **chacun a été introduit en corrigeant l'autre** :

| Ordre choisi | Ce qui casse |
|---|---|
| afficher **puis** consommer | `showSnackbar` suspend plusieurs secondes ; une rotation annule l'effet avant la consommation, l'événement se rejoue, et **le texte dicté s'insère deux fois** |
| consommer **puis** afficher | la composition peut mourir avant que le message ne paraisse ; l'utilisateur appuie sur le micro, rien ne se passe, et **rien ne lui dit pourquoi** |

Il n'y a pas de bon ordre, parce que les deux moitiés n'ont pas la même exigence :

- l'**insertion** doit avoir lieu une fois, et **pas deux** ;
- le **message** doit avoir lieu une fois, et **pas zéro**.

**La parade est de les séparer** — deux flux, deux consommations. L'insertion se consomme dès
qu'elle est faite ; le message se consomme après son affichage, et une composition détruite
entre-temps le laisse en attente pour la suivante.

⚠️ **Le motif, réutilisable** : devant un événement à consommer une fois, demander *s'il agit ou
s'il informe*. Quand il fait les deux, aucun ordre unique ne convient, et le débat sur l'ordre est
le symptôme — pas le problème.

---

## §66 — Une relecture se VÉRIFIE, y compris quand elle a raison sur le fond

Les six constats du 08-16 sur les correctifs de la dictée ont été passés au crible avant
application. Résultat : **cinq confirmés, un partiellement faux**.

Le constat « trois appuis rapides sur *copier le lien* empilent trois messages » décrivait un
scénario **impossible dans le fichier cité** : le drapeau est déjà à `true`, l'état ne change pas,
le `StateFlow` ne réémet pas, aucun effet ne repart. Le **mécanisme**, lui, était réel — mais sur un
autre chemin, celui du refus de permission, où chaque appui produit bien un nouveau message.

Appliquer le correctif au fichier désigné n'aurait rien réparé, et aurait laissé le vrai défaut en
place tout en donnant le sentiment de l'avoir traité.

⚠️ **Deux constats des relectures précédentes avaient déjà été écartés** parce que le relecteur
n'avait pas les fichiers où la garantie était tenue. Un relecteur externe voit ce qu'on lui donne :
**son scénario est une hypothèse, pas une mesure.**

⚠️⚠️ Corollaire, mesuré le même jour : le gate qui passe après un correctif ne dit rien de ce que le
correctif a laissé derrière lui. `import kotlinx.coroutines.launch` est resté inutilisé — ktlint ne
l'a pas signalé, et la seule autre occurrence de `launch` dans le fichier était celle du sélecteur
d'activité, qui n'a rien à voir. **Relire le delta, pas seulement le voir compiler.**

## §67 — Une garde posée sur `onDismissRequest` ne bloque PAS le balayage d'une feuille

**Mesuré sur le S9 le 2026-08-16** par `FermetureDeFeuilleTest`, après deux relectures externes qui
se contredisaient. C'est le test qui a tranché, et il a donné tort à la plus assurée des deux.

`VaultSheets.kt` transpose un dialogue Flutter **volontairement bloquant** (`PopScope(canPop: false)`,
`barrierDismissible: false`). Pendant le chiffrement du contenu d'un coffre, le coffre est **déjà
créé** : fermer annule la coroutine, le dossier reste un coffre, ses notes restent **en clair**, et
la feuille disparaît sans rien dire. L'utilisateur croit avoir annulé une création qui a eu lieu.

Le portage posait la garde dans le seul `onDismissRequest`. Ce que la mesure montre :

| Geste | Ce qui arrive | La garde de `onDismissRequest` |
|---|---|---|
| **Balayage vers le bas** | l'état passe à `Hidden`, la feuille **quitte l'écran**, *puis* `onDismissRequest` est appelé | 🔴 **inutile** — elle s'exécute après le départ de la feuille |
| **Retour** | `onDismissRequest` est appelé **directement**, sans toucher à l'état | ✅ c'est elle, et elle seule, qui protège |

⚠️⚠️ **Donc deux mécanismes, pas un.** `confirmValueChange` refuse la **transition** vers `Hidden` ;
la garde de `onDismissRequest` refuse l'**action**. Aucun ne couvre le chemin de l'autre. La paire
est exhaustive parce qu'il n'existe que deux façons de faire disparaître une `ModalBottomSheet` :
demander `Hidden` à son état, ou appeler `onDismissRequest`. C'est ce qui permet d'affirmer que le
voile est couvert **sans l'avoir mesuré** — quel que soit celui des deux chemins qu'il emprunte.

🔴 Une relecture affirmait que `confirmValueChange` suffisait, « le bouton Retour est ignoré,
`onDismissRequest` ne sera jamais appelé ». La mesure dit l'inverse. La croire aurait conduit à
**retirer** la garde existante comme devenue redondante — c'est-à-dire à rouvrir le trou en croyant
le fermer. *Le danger d'une relecture assurée n'est pas qu'elle se trompe, c'est qu'elle donne envie
de simplifier.*

## §68 — `confirmValueChange` est une CLÉ du `rememberSaveable` : une lambda instable recrée l'état

Corollaire du §67, et il pouvait coûter plus cher que le défaut réparé.

`rememberModalBottomSheetState` construit son `SheetState` sous un `rememberSaveable` dont
`confirmValueChange` fait partie des clés. Mesuré : avec une lambda que le compilateur Compose ne
peut pas mémoriser, **six compositions ont donné six états**. Sur une feuille de saisie, où chaque
frappe recompose, la feuille se réinitialiserait sous les doigts de l'utilisateur.

✅ La forme retenue tient parce qu'elle ne capture qu'une **référence de méthode liée** :

```kotlin
private fun etatDeFeuilleDeCoffre(bloquer: () -> Boolean = { false }) = rememberModalBottomSheetState(
    skipPartiallyExpanded = true,
    confirmValueChange = { cible -> !(cible == SheetValue.Hidden && bloquer()) },
)
// appel : etatDeFeuilleDeCoffre(bloquer = viewModel::chiffrementEnCours)
```

L'égalité d'une référence liée porte sur le récepteur et la méthode : deux instances successives
sont **égales**, donc Compose mémorise la lambda et l'état survit. Mesuré à **un seul** état sur six
compositions.

⚠️ **Un booléen aurait cassé ça.** `etatDeFeuilleDeCoffre(bloquant)` avec `bloquant` lu à la
composition serait figé sur sa valeur initiale — le veto ne s'activerait jamais. Les deux relectures
ont signalé ce piège ; aucune n'avait vu l'autre moitié, celle où la lambda **change trop souvent**.

🔧 **Compter les identités, pas regarder l'écran.** Une feuille recréée puis ré-affichée ressemble à
s'y méprendre à une feuille intacte. C'est un `mutableSetOf<SheetState>()` alimenté en composition
qui rend la question décidable — et le test qui fige le piège vaut autant que celui qui fige le
correctif : sans le repoussoir, rien ne dit que la mesure sait distinguer les deux cas.

## §69 — 🔴🔴 `detect_language` ne veut pas dire « détecte la langue » : la dictée n'a JAMAIS transcrit

Le défaut le plus grave du portage, et il est resté invisible jusqu'au **2026-08-16**, jour où
Patrice a essayé la dictée pour la première fois : *« il me dit que rien n'a été entendu, aucun texte
inséré »*.

Le pont JNI posait :

```cpp
parametres.detect_language = codeLangue.empty();   // ❌
```

Or dans `whisper.cpp` (**ligne 6838** de la copie vendorisée), ce champ ne demande pas une détection,
il demande **de ne faire que ça** :

```c
if (params.detect_language) {
    return 0;          // succès — et AUCUN segment produit
}
```

`transcribeFile` est appelée **sans langue**, donc `codeLangue` était toujours vide, donc le drapeau
était toujours vrai. **La dictée n'a jamais produit un mot, depuis le tout premier commit du
moteur.** Elle rendait un code de succès à chaque fois.

⚠️ La détection automatique n'avait besoin d'aucun drapeau : `whisper_full` la fait déjà quand
`language` vaut `nullptr`, `""` ou `"auto"` (`whisper.cpp:6826`). La ligne n'ajoutait rien — elle
retirait tout.

### Pourquoi rien ne pouvait le voir avant

C'est un **échec qui a toutes les apparences d'un succès** :

| Ce qu'on pouvait observer | Ce que ça semblait dire |
|---|---|
| `whisper_full` rend **0** | tout s'est bien passé |
| la transcription dure **4,6 s** sur le S9 | un vrai calcul a eu lieu |
| **zéro segment** | l'utilisateur n'a rien dit |
| l'écran affiche « rien n'a été entendu » | le micro n'a pas capté |

Le message d'erreur accusait donc **le micro** pour un défaut du **moteur**, et il le faisait avec
une formulation parfaitement plausible. Aucune relecture ne l'a vu — ni les deux tours du 08-16, ni
les précédents — parce que lire `detect_language = langue.empty()` ne choque pas : ça se lit comme
« si aucune langue n'est donnée, détecte-la ».

🔧 **Ce qui l'a trouvé, et rien d'autre n'aurait pu** : faire transcrire au moteur un enregistrement
**dont on connaît le contenu**, sans micro et sans voix — `samples/jfk.wav` de whisper.cpp, normalisé
au format que l'application écrit. `TranscriptionSurAppareilTest`. Le test a échoué au premier essai,
exactement comme l'utilisateur.

⚠️⚠️ **La leçon, plus large que ce champ** : tout ce qui entourait la dictée était vérifié — capture,
import, empreintes, permissions, interface, panique, R8, jusqu'aux symboles JNI comptés un par un —
et **la seule chose jamais exercée était celle qui donne son nom à la fonction**. Une chaîne dont
chaque maillon est mesuré ne dit rien de ce qu'elle transporte. *Vérifier qu'un moteur se charge
n'est pas vérifier qu'il tourne.*

## §70 — Un décodeur volontairement étroit refuse aussi les fichiers légitimes

Corollaire mineur du §69, trouvé en route. `jfk.wav` porte un bloc `LIST` entre `fmt ` et `data` —
parfaitement conforme au format WAV. `WavPcm16` le refuse : *« disposition de blocs inattendue — ce
WAV ne vient pas de cette application »*.

C'est **voulu** et ça reste le bon choix : le décodeur ne lit que ce que l'application écrit
elle-même, et tout le reste est refusé plutôt qu'interprété. Mais il faut le savoir avant d'écrire un
test — l'échantillon a dû être **réécrit en en-tête canonique de 44 octets** pour entrer.

⚠️ À retenir si un jour l'import d'un audio extérieur est envisagé : ce ne serait pas une ligne à
assouplir, ce serait un décodeur à écrire, avec la question de sécurité qui va avec.

## §71 — 🔴🔴 `ExtendedFloatingActionButton` EFFACE la sémantique de son libellé : le bouton était muet

Relevé le 2026-08-17, en ouvrant la phase 8 par un relevé mécanique de l'accueil sur le S9.

Le bouton « Nouvelle note » était écrit de la façon la plus évidente qui soit :

```kotlin
ExtendedFloatingActionButton(
    onClick = onNewNote,
    icon = { Icon(Icons.Outlined.EditNote, contentDescription = null) },
    text = { Text(stringResource(R.string.home_new_note)) },   // le libellé est bien là
)
```

`contentDescription = null` sur l'icône est **la règle** pour une icône décorative : son sens est
déjà porté par le texte à côté. Sauf que le texte à côté n'existe pas pour un lecteur d'écran.

### Ce que la mesure a donné, et dans quel ordre

| Instrument | Résultat |
|---|---|
| `uiautomator dump` | nœud **cliquable**, `text=""`, `content-desc=""`, et **`NAF="true"`** |
| arbre de sémantique **fusionné** | **0** nœud portant « Nouvelle note » |
| arbre de sémantique **non fusionné** | **1** nœud, sous un ancêtre `ClearAndSetSemantics = 'true'` |
| nœud du bouton lui-même | `Role=Button`, `MergeDescendants=true`, **aucun nom** |

material3 1.4.0 enveloppe l'emplacement `text` de ce composant dans un `clearAndSetSemantics` — le
libellé est donc **dessiné** (261 × 60 px mesurés, à l'écran) et **absent** de l'arbre que lit un
lecteur d'écran. L'application publiée, elle, porte `label:` **et** `tooltip:`
(`home_screen.dart:391-396`) : c'est donc une **régression de parité**, pas un défaut hérité.

### Le correctif retenu — et pourquoi ce n'est PAS celui trouvé en premier

La première correction nommait l'**icône** (`contentDescription` sur le slot `icon`, qui est en
dehors du nœud effacé). Mesurée, elle fonctionnait. Les **deux** relectures externes du 2026-08-17
ont convergé pour la refuser, avec deux arguments distincts :

- **Gemini Pro** : le jour où material3 cesse d'effacer le slot `text`, le nœud portera la
  description **et** le texte — deux sources de libellé, donc un risque d'annonce en double.
- **GPT-5.2** : ajoute un second risque, distinct — si le `mergeDescendants` du composant change,
  une icône **nommée** peut devenir un arrêt de focus séparé et non cliquable.

Le nom accessible est donc posé sur le **bouton lui-même**, par
`modifier = Modifier.semantics { contentDescription = … }`, l'icône redevenant muette. La propriété
est alors sur la racine du composant : elle survit aux deux évolutions, et au mode réduit où le slot
`text` n'est plus composé du tout.

⚠️ `AccueilTest` exige **exactement une** description sur ce nœud — c'est ce qui interdit de renommer
l'icône « pour faire bonne mesure ». Et une seconde assertion, documentée comme **fil-piège** et non
comme exigence, échouera si le slot `text` réapparaît un jour dans l'arbre fusionné : ce sera l'ordre
de refaire la mesure d'annonce, pas le signe d'un défaut.

### ⚠️ Les trois choses à en retenir, dans l'ordre d'utilité

1. **`clearAndSetSemantics` n'est pas visible depuis le code appelant.** Rien, dans la signature du
   composant, ne laisse deviner que le slot qu'on remplit sera effacé. Aucune relecture de ce fichier
   — humaine ou externe — n'avait de raison de s'en méfier.
2. **`NAF="true"` est un signal, pas une conclusion.** uiautomator le pose lui-même sur tout nœud
   cliquable sans nom. Il vaut d'être cherché **systématiquement** dans un relevé, mais il demande à
   être confirmé sur l'arbre de sémantique : c'est ce dernier qui fait foi.
3. 🔴 **Un test écrit sur `onNodeWithText` serait resté VERT.** Par défaut les recherches Compose
   portent sur l'arbre **fusionné**, mais un test qui cherche le libellé du slot `text` a toutes les
   chances d'être écrit avec `useUnmergedTree = true` pour « le faire passer » — et il mesurerait
   alors exactement l'arbre où le défaut est invisible. Le test doit viser le **nom accessible**.

### 🔧 L'instrument qui généralise — et son témoin

Le cas particulier vaut moins que le balayage : *quels nœuds **cliquables** de cet écran n'ont ni
description ni texte dans l'arbre fusionné ?* `AccueilTest.aucun_element_cliquable_de_l_accueil_n_est_sans_nom`
pose la question à tout l'écran et échoue en rendant les **coordonnées** des muets.

⚠️⚠️ Il est doublé d'un témoin — `le_detecteur_de_cliquable_sans_nom_signale_bien_un_bouton_muet` —
qui pose **deux** boutons, l'un nommé l'autre muet, et exige exactement **un** signalement. Sans lui,
un filtre qui lirait la mauvaise propriété de sémantique rendrait une liste vide, et un écran entier
de boutons muets passerait pour sain. C'est la même faute qu'un `grep` ancré au mauvais endroit —
commise le matin même sur `llvm-nm`, où le motif `send(to|msg)?$` ne pouvait rien trouver puisque les
symboles portent un suffixe `@LIBC`.

## §72 — 🔴🔴 La suite instrumentée DÉTRUISAIT le modèle de 57 Mo, et ignorait en silence le test qui le prouve

Le 2026-08-16, une précaution avait été prise et écrite partout : **ne plus lancer la suite par
`connectedAndroidTest`**, parce qu'AGP désinstalle l'application à la fin, ce qui effacerait le modèle
de 57 Mo que l'utilisateur avait importé à la main. Le remplacement — `adb shell am instrument` — est
correct, et il ne protégeait de rien.

Mesuré le 2026-08-17, après un simple `am instrument` sur toute la suite :

| Constat | Mesure |
|---|---|
| `files/stt/` après la suite | **n'existe plus** |
| tests ignorés d'après « OK (144 tests) » | 0 |
| tests ignorés d'après les codes de statut | **1** — code `-4`, échec d'hypothèse |
| lequel | `TranscriptionSurAppareilTest.un_enregistrement_connu_produit_du_texte` |

La cause est **dans un test**, pas dans l'outil : `SttModelStoreTest` et `WhisperSttTest` faisaient
tous deux, en `@Before` **et** en `@After`,
`SttModelStore.repertoireDesModeles(context).deleteRecursively()` sur le **vrai** `filesDir`. Deux de
leurs cas exigent en outre que ce répertoire soit **vide** — la purge leur était donc nécessaire, ce
qui explique qu'elle ait été écrite ainsi et qu'elle n'ait choqué personne.

Comme `SttModelStoreTest` passe avant `TranscriptionSurAppareilTest` dans l'ordre d'exécution, ce
dernier trouvait `estPresent()` faux pour tous les modèles du catalogue et **s'ignorait lui-même**
par son `assumeTrue`.

### Ce qui rend ce défaut coûteux, dans l'ordre

1. **Perte de donnée de l'utilisateur.** Un fichier de 57 Mo qu'il a téléchargé sur un ordinateur,
   transféré, puis importé par le sélecteur — détruit par un lancement de tests.
2. 🔴 **Le seul test qui prouve que la dictée transcrit ne tournait jamais dans la suite.** Il n'a
   jamais été vert que lancé **seul**, ce qui est la façon dont il a été écrit et vérifié. La ligne
   « 137 tests, 0 échec, **0 ignoré** » de `REPRISE.md` était donc fausse sur son dernier tiers.
3. **« OK (N tests) » ne dit rien des ignorés.** C'est déjà écrit au §45, et le compte avait quand
   même été affirmé. Avec `am instrument` il n'y a pas de XML : le seul décompte fiable est
   `grep -c 'INSTRUMENTATION_STATUS_CODE: -4'` (échec d'hypothèse) et `-3` (ignoré).

### Correctif, et son témoin

Les deux classes travaillent désormais sur un `ContextWrapper` dont **`getFilesDir()` seul** est
détourné vers `cacheDir/faux-files` — `cacheDir` reste le vrai, parce qu'un des tests passe par le
`FileProvider` de l'application, qui n'expose que `cache/exports/`.

⚠️ Chacune porte un témoin, `le_magasin_de_test_n_ecrit_jamais_dans_le_repertoire_reel_de_l_application` :
il compare les deux chemins. Sans lui, une faute de frappe qui ferait retomber le détournement sur le
vrai répertoire laisserait **tous** les autres tests verts et détruirait de nouveau le fichier.

### ⚠️⚠️ La leçon, qui dépasse ce répertoire

**Un test qui écrit dans la zone privée réelle de l'application peut détruire des données de
l'utilisateur, et le fera d'autant plus sûrement qu'il « nettoie bien derrière lui ».** La précaution
prise la veille visait l'outil de lancement ; la destruction venait du code de test. *Se protéger
d'une cause connue ne dit rien des autres — et une précaution écrite en gros donne le sentiment que
la question est réglée.*

## §73 — 🔴 Le bouton ⋮ s'annonçait « Réglages », c'est-à-dire le nom d'UNE de ses deux entrées

Trouvé le 2026-08-17 par un test qui cherchait autre chose.

`AccueilTest.les_trois_sorties_de_la_barre_remontent_a_l_appelant` cliquait l'icône décrite
« Réglages » et attendait `onOpenSettings`. L'appel n'arrivait pas. Le câblage était pourtant
correct : cette icône ouvre un **menu déroulant**, dont la première entrée mène aux réglages et la
seconde à « À propos ».

Le défaut n'était donc pas dans l'action mais dans le **nom** :

| | Description annoncée | Ce que le bouton fait |
|---|---|---|
| Portage, avant | **« Réglages »** | ouvre un menu de deux entrées |
| Application publiée | `moreButtonTooltip` de la plateforme, soit « Plus d'options » | ouvre un menu de deux entrées |

C'est une **divergence de parité** et un défaut d'accessibilité : qui navigue au lecteur d'écran
entend « Réglages, bouton », active, et se retrouve devant un menu. Le portage nommait le bouton
d'après sa destination la plus probable, ce qui est exactement l'erreur qu'un tooltip de plateforme
évite.

Correctif : une chaîne `common_more_options`, ajoutée par `outils/arb_vers_strings.py` puisque
Compose n'expose pas l'équivalent public du `moreButtonTooltip` de Flutter.

### ⚠️⚠️ Et le garde-fou que cet ajout a révélé manquant

La valeur française « Plus d'options » est entrée dans le XML avec une **apostrophe nue**. Les 425
autres apostrophes du fichier sont échappées `\'`, et l'en-tête du fichier généré énonce la règle en
toutes lettres : *sans l'antislash, aapt tronque la chaîne*. En français seulement, silencieusement.

La cause : les blocs `AJOUTS_EN` / `AJOUTS_FR` du générateur sont recopiés **verbatim**, alors que les
chaînes venues de l'ARB passent par l'échappement. **Rien ne contrôlait les ajouts.** Et dans une
chaîne Python non brute, écrire `\'` produit `'` — il faut `\'`.

Le générateur porte désormais une assertion sur les valeurs des `AJOUTS`, vérifiée sur un cas
positif : avec une apostrophe nue, il **refuse** de produire le fichier.

⚠️ *Une règle écrite dans l'en-tête d'un fichier généré ne protège personne : c'est le générateur qui
doit refuser.*

## §74 — 🔴🔴 La carte de note s'annonçait comme du TEXTE, et sa version corbeille comme un bouton inerte

Deux défauts opposés sur le même composant, `ui/home/NoteCard.kt`, trouvés le 2026-08-17 en cochant
la ligne `trash_screen.dart` de `05-PARITE.md`.

### Le défaut visible en premier : un clic qui ne fait rien

La corbeille appelait `NoteCard(note = note, onClick = { })`. Une lambda vide **n'est pas** l'absence
de clic : `Modifier.clickable` pose alors une action `OnClick` dans l'arbre de sémantique et un effet
d'encre sous le doigt. Un lecteur d'écran annonçait donc « double-touchez pour activer » sur une
carte qui ne s'ouvre pas — une note en corbeille attend sa destruction, elle ne s'édite pas.

L'application publiée rend sa tuile en `ListTile` **sans `onTap`** (`trash_screen.dart:211`). Le
paramètre est donc devenu `onClick: (() -> Unit)?`, et `null` retire le modificateur.

### 🔴🔴 Le défaut que le TÉMOIN a trouvé, et qui était plus grave

Le test « la carte de corbeille n'est pas actionnable » se réduit à `assertHasNoClickAction()`. Une
assertion négative ne vaut rien sans positif connu : le témoin pose **la même** carte avec un
`onClick` réel et exige `assertHasClickAction()`.

**Le témoin a échoué.** Mesuré sur le S9 :

```
java.lang.AssertionError: Failed to assert the following: (OnClick is defined)
ContentDescription = '[Le titre de la note. corps de la note. 14 nov. 2023 · 23:13]'
MergeDescendants = 'true'
Has 1 child
```

La sémantique `semantics(mergeDescendants = true) { contentDescription = … }` était posée sur le
`Surface`, le `clickable` sur la `Column` fille. **Les actions d'un descendant ne remontent pas au
nœud fusionné, contrairement au texte et aux descriptions.** Donc, sur l'accueil comme dans la
recherche, la carte de note n'a jamais porté d'action : elle s'annonçait comme du **texte**, sans
dire qu'on peut l'ouvrir.

⚠️⚠️ **Et le geste fonctionnait quand même**, ce qui rendait le défaut indétectable autrement : un
double-appui de lecteur d'écran envoie un toucher au **centre du nœud focalisé**, qui atteint la
fille cliquable. *Ce que le code fait n'est pas ce que l'utilisateur entend.*

⚠️ **`performClick()` ne l'aurait jamais vu** : il injecte un toucher aux coordonnées du nœud et
**n'exige aucune action de sémantique**. C'est pourquoi `toucher_une_carte_ouvre_la_note_correspondante`
était vert depuis le premier jour, sur un nœud sans `OnClick`.

Correctif : les deux vivent sur le **même** nœud, celui de la `Column`.

```kotlin
Column(
    modifier = Modifier
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .semantics(mergeDescendants = true) { contentDescription = description }
        .padding(14.dp),
)
```

⚠️ **Pas sur le `Surface`.** Le `Surface` découpe son contenu à ses coins arrondis ; un `clickable`
posé sur son propre modificateur est **hors** de ce découpage, et l'effet d'encre déborderait en
rectangle. C'est un compromis mesurable, pas une préférence.

### 🔧 Ce que ce défaut apprend sur la façon de chercher les suivants

Le balayage « actionnable sans nom » (§71) ne pouvait **pas** le trouver : il cherche des nœuds qui
ont une action et pas de nom, et celui-ci avait un nom et pas d'action. **Le motif inverse existe et
demande son propre contrôle** : *un nœud qui porte un nom et se comporte comme activable
annonce-t-il son action ?*

## §75 — 🔴 La corbeille annonçait « vide » avant d'avoir lu la base

`stateIn` **rend obligatoirement une valeur initiale**. `TrashUiState()` valait donc « aucune note »
avant la première émission de Room, et l'écran n'avait qu'une branche : `if (notes.isEmpty())
EmptyState(…)`. Résultat, à chaque ouverture de la corbeille, « La corbeille est vide » — affiché
**et annoncé** — à quelqu'un dont elle ne l'est pas.

L'application publiée distingue les deux depuis toujours : son `items` vaut `null` tant que la
lecture n'a pas rendu, et elle montre alors un indicateur d'activité (`trash_screen.dart:230`).

Second effet, distinct : le bouton « vider la corbeille » dépend de `notes.isNotEmpty()`. Il
**surgissait** donc après la première réponse, sous un doigt déjà posé sur la barre. Il est
maintenant masqué tant que `loading` vaut `true`.

⚠️ **Cet état ne s'atteint pas en pilotant l'application.** Sur un téléphone la base répond en
quelques millisecondes, et ce qu'on voit passer ressemble à un scintillement d'affichage. Il n'est
observable que parce que l'écran a été rendu **sans état** — `TrashRoute` porte le ViewModel,
`TrashScreen` ne reçoit qu'un `TrashUiState`, exactement comme `HomeRoute` / `HomeScreen`.

⚠️ Le même raisonnement vaut pour **tout** écran dont l'état vient d'un `stateIn` : la valeur
initiale n'est pas une donnée, c'est une absence de donnée, et l'écran doit savoir les distinguer.
Reste à vérifier ligne par ligne sur les écrans qui suivent.

### ⚠️⚠️ Ce que les DEUX relectures externes ont ajouté à §74, et elles ont encore vu des choses disjointes

Relectures du 2026-08-17 (Gemini Pro, GPT-5.2) sur le lot déjà mesuré vert à 163 tests. **Sept
constats, zéro recoupement sur les trois qui comptaient.**

| Constat | Sort |
|---|---|
| 🔴 Les **étiquettes** n'étaient pas annoncées (Gemini) | **CONFIRMÉ, corrigé.** Un nœud fusionné qui porte une `contentDescription` explicite **remplace** la lecture de ses enfants : mesuré, le nœud de la carte ne porte **aucune** propriété `Text`. Tout ce qui n'est pas dans la chaîne construite n'existe pas. `#urgent` était affiché et tu. ⚠️ La garde `!verrouillee` a dû être reproduite, sinon le correctif d'accessibilité **ouvrait la fuite** que la carte ferme |
| `Role.Button` absent (les deux) | **CONFIRMÉ, corrigé.** Sans rôle, TalkBack annonce la description puis « double-touchez pour activer », sans nommer ce que c'est |
| Dialogue perdu à la **rotation** (Gemini) | **CONFIRMÉ, corrigé.** `remember` → `rememberSaveable`, et l'**identifiant** au lieu de la `Note` : le dialogue n'a besoin que de lui, et `Note` n'a pas à devenir `Parcelable` pour ça |
| 🔴🔴 Mon test de chargement était **vacant** (GPT) | **CONFIRMÉ, corrigé** — détaillé ci-dessous |
| Sélection par **indice** `[1]` dans les dialogues (les deux) | **CONFIRMÉ.** J'avais déjà remplacé l'indice par `hasAnyAncestor(isDialog())` avant la relecture — mais Gemini a vu ce que je n'avais pas vu : **le dialogue de vidange porte son libellé deux fois**, en titre *et* en bouton. Mon propre correctif désignait donc **deux** nœuds et aurait échoué. `hasClickAction()` ajouté |
| `tryEmit` perd le message si la rotation arrive pendant l'action (Gemini) | **PROBABLE, non corrigé** — décision documentée du dépôt, voir plus bas |
| `Box(fillMaxSize())` décentrerait l'indicateur (GPT) | **PROBABLE, non mesuré.** `HomeScreen` emploie le **même** motif depuis le début, sous un champ de recherche. Cosmétique ; le test vérifie que l'indicateur est **affiché**, pas sa position au pixel |

#### 🔴🔴 Le test vacant, parce que c'est la même faute deux fois dans la même journée

Mon test vérifiait que le bouton « vider la corbeille » est absent pendant le chargement, sur un état
`loading = true` **et une liste vide**. Or ce bouton dépend **aussi** de `notes.isNotEmpty()` : il est
absent quelle que soit la garde. *L'assertion passait avec `!state.loading` et sans.*

Le seul état qui discrimine est **des notes ET un chargement en cours**. C'est exactement la même
faute que le témoin de carte du même fichier, sous une autre forme : **une assertion négative posée
sur un état où le vrai et le faux donnent le même résultat.** Deux occurrences en une journée, dont
une trouvée par mon propre témoin et l'autre par une relecture.

🔧 **La question à se poser devant toute assertion négative** : *quel état rendrait cette assertion
fausse si le code était cassé ?* S'il n'y en a pas dans le test, il ne mesure rien.

#### ⚠️ Les deux constats laissés en l'état, et pourquoi

**`tryEmit` et `replay = 0`.** Un message de corbeille émis pendant une rotation n'a aucun abonné et
part au néant. Le KDoc du ViewModel **choisit** déjà `tryEmit` en le disant : cette portée ne doit pas
rester suspendue à attendre un collecteur. Un `Channel` conserverait l'événement. La fenêtre est celle
d'une rotation pendant une écriture Room, et le message redit ce qui se voit — la carte quitte la
liste. Écart connu, non corrigé, à trancher si un usage le rend sensible.

🔴 **Et une asymétrie entre jumeaux, relevée en vérifiant la réponse de GPT sur le chargement
infini.** `SearchUiState` porte un champ `failed` **parce qu'un flux `stateIn` non gardé avait emporté
l'application** — c'est écrit dans son propre KDoc. `TrashViewModel.state` est exposé exactement de la
même façon, sans `catch`. La corbeille ne se figerait donc pas sur son indicateur : elle **planterait**,
comme la recherche le faisait.

Non corrigé, et c'est un choix écrit : l'application **publiée** n'a pas davantage de filet sur cet
écran, la phase 8 juge la **parité**, et l'exposition n'est pas comparable — la recherche exécute du
FTS sur une saisie utilisateur, la corbeille lit un flux Room sans argument. À reprendre si la 3.0.0
gagne un filet d'erreur général.

## §76 — 🔴🔴 La recherche annonçait « Aucun résultat » PENDANT la recherche

Troisième ligne de parité, et le défaut était **déjà localisé** par le balayage du motif §75 sur les
quatre `stateIn` du portage — pas par l'usage de l'écran.

`SearchViewModel.state` est un `combine` de trois flux dont **deux ont des rythmes différents** : la
saisie, qui émet à chaque frappe, et les résultats, qui passent par un freinage de 250 ms **puis** par
une requête FTS. Entre les deux, l'état portait la **nouvelle** requête et l'**ancienne** issue.

Pour une première recherche, l'ancienne issue est vide. Le `when` de l'écran allait de
`query.isBlank()` à `failed` puis directement à `results.isEmpty()` : il n'avait aucune branche pour
« la requête est posée, la réponse n'est pas là ». L'écran affichait donc **« Aucun résultat. Essayez
un autre mot-clé »** — un message qui **accuse la saisie de l'utilisateur** pour une réponse qui
n'était pas encore arrivée.

L'application publiée ne fait pas cette faute : `search_screen.dart:109` rend un
`CircularProgressIndicator` tant que `snap.connectionState == ConnectionState.waiting`. C'était donc
une **régression du portage**, pas un écart hérité.

### 🔧 Le mécanisme : faire porter la question à la réponse

Il n'existe aucun moyen fiable de deviner si la réponse en main est celle de la question posée. Elle
doit donc la porter :

```kotlin
internal data class Issue(
    val pour: String? = null,          // la saisie a laquelle cette issue repond
    val resultats: List<Note> = emptyList(),
    val echec: Boolean = false,
)

val repondALaSaisie = issue.pour == texte
searching = texte.isNotBlank() && !repondALaSaisie
failed    = issue.echec && repondALaSaisie
```

⚠️ **`pour` est nullable, et pas vide par défaut.** `null` signifie « aucune réponse, pour aucune
requête » — l'état d'ouverture de l'écran, et celui d'après une rotation. Une chaîne vide serait
**égale** à une saisie vide, donc lue comme une réponse.

⚠️ **`failed` n'est retenu que si l'issue répond à la saisie courante.** L'échec d'une requête
abandonnée ne dit rien de la suivante, et l'afficher sous elle accuserait une panne passée d'un
problème présent. C'est ce qui rend `failed` et `searching` **exclusifs par construction** — l'écran
s'appuie sur cette exclusivité pour ordonner ses branches.

### ⚠️⚠️ La condition d'affichage a une seconde moitié, et elle n'est pas décorative

```kotlin
state.searching && state.results.isEmpty() -> indicateur
```

Sans `&& results.isEmpty()`, **chaque frappe** remplacerait la liste par un indicateur pendant 250 ms :
un clignotement à chaque lettre, là où le publié laisse les résultats de la requête précédente en place
le temps du freinage (il ne remplace son `_future` qu'à l'expiration du `Debouncer`). L'indicateur ne
paraît donc que lorsqu'il n'y a **rien** à montrer.

🔴 *Le correctif évident — « toujours l'indicateur dès que `searching` » — est plus simple à écrire et
introduit une régression d'usage à chaque frappe.* Un test le fige.

### 🔴 Et la leçon de la veille appliquée : deux niveaux de test, pas un

`RechercheTest` pose `searching` **à la main**. Il prouve donc ce que l'écran fait d'un état, et
**jamais que quelque chose produit cet état** — la forme exacte du test vacant relevé la veille.

D'où l'extraction de la transformation en fonction pure, `etatDeRecherche(texte, issue, noms)`, testée
sur la JVM (`RechercheEtatTest`, 7 cas dont un balayage d'exclusivité sur 24 combinaisons).
`SearchRepository` et `FoldersRepository` sont des classes concrètes bâties sur un `DatabaseProvider` :
le ViewModel entier n'est pas exerçable hors appareil, cette fonction l'est.

⚠️ **La valeur initiale de `stateIn` passe par la même fonction**, avec `Issue()`. Deux chemins vers le
même état demanderaient deux fois la même vérification — et c'est précisément par la valeur initiale
que §75 était entré.

## §77 — 🔴🔴 L'interrupteur de fenêtre protégée n'avait AUCUN nom accessible

Quatrième ligne d'écran, et le balayage de `ui/BalayageDAccessibilite.kt` a rendu **un** rectangle :

```
expected to be empty
but was: [Rect.fromLTRB(828.0, 1407.0, 984.0, 1503.0)]
```

156 × 96 px, soit exactement un `Switch` de 52 × 32 dp à 3× — identifié par l'arithmétique, pas par
l'œil. Un `Switch` posé en `trailingContent` d'un `ListItem` est un nœud **séparé** de celui qui porte
le texte : il détient l'action et l'état, la ligne détient le libellé, et **rien ne les relie**. Un
lecteur d'écran annonçait donc « interrupteur, activé » sans dire de quoi.

L'application publiée n'a pas ce défaut : elle emploie un **`SwitchListTile`**
(`settings_screen.dart:92`), qui rend **un seul** nœud portant le libellé, l'état et le rôle. C'était
donc une **régression du portage**.

### 🔧 Le correctif était déjà écrit dans le même fichier

`SettingsScreen.kt` applique depuis toujours l'idiome à ses boutons radio, avec le commentaire qui
l'explique : *« `onClick = null` sur le bouton radio : c'est la ligne entière qui est cliquable, et un
second point de contact ferait deux cibles pour un seul choix — l'une d'elles plus petite que le
minimum accessible »*. Il suffisait de l'appliquer à l'interrupteur :

```kotlin
trailingContent = { Switch(checked = state.secureWindow, onCheckedChange = null) },
modifier = Modifier.toggleable(
    value = state.secureWindow,
    role = Role.Switch,
    onValueChange = onSecureWindow,
),
```

⚠️ *Un idiome correct appliqué à un composant et pas à son voisin est plus difficile à voir qu'une
absence d'idiome* : le fichier avait l'air cohérent, et le commentaire du bon cas donnait l'impression
que la question était réglée partout.

Trois assertions le figent, chacune tombant seule si le correctif est défait : **un seul** nœud
basculable, ce nœud **porte le libellé** de la ligne, et son état suit l'état — le clic remontant
l'inverse.

### ⚠️⚠️ `clickable(enabled = false)` CONSERVE son action dans l'arbre de sémantique

Mesuré le même jour, sur un test à moi qui a échoué. La ligne du mode panique porte
`Modifier.clickable(enabled = !enCours)`, et j'avais écrit « pendant une panique, ce nœud n'a plus
d'action `OnClick` ». Faux : l'action **reste**, et Compose pose la propriété `Disabled` à côté.

C'est cohérent — un nœud désactivé doit rester annoncé, avec sa nature et son indisponibilité — mais
ça se mesure par **`assertIsNotEnabled`**, jamais par `assertDoesNotExist`.

🔧 **Conséquence pour le balayage** : il **voit** les actionnables désactivés, et c'est ce qu'on veut.
Un bouton grisé sans nom reste un bouton sans nom.

### ⚠️ Et deux autres échecs de mes tests, qui ne visaient pas le code

- **`LocalSecureWindow` n'a aucun défaut, exprès**, et mes deux tests du dialogue de panique ont levé
  son message : *« Aucun SecureWindowController fourni — cet écran croirait être protégé sans
  l'être. »* Le garde-fou a fait son travail : un contrôleur muet aurait laissé le test vert sur un
  écran non protégé. Le test fournit désormais un contrôleur **réel** — sa chaîne de construction ne
  demande qu'un `Context`, et `SecureWindowGuard` n'appelle que `force()`/`release()`, qui ne touchent
  qu'un compteur en mémoire.
  ⚠️ Ce qu'il ne mesure **pas** : que le dialogue pose bien `FLAG_SECURE`. `activeNow()` mêle le
  compteur au réglage de l'utilisateur, donc le vérifier demanderait d'**écrire dans les préférences
  réelles** de l'application — ce que la leçon §72 interdit à un test. Dit plutôt que contourné.
- **`home_sort_mode` sert DEUX fois sur cet écran**, au titre de section et à la ligne de réglage :
  `onNodeWithText` seul désignait deux nœuds. Même famille que le dialogue de vidange de §74 —
  *sur un écran de réglages, un libellé réutilisé est la règle, pas l'exception.*

### 🔧 Ce que le mode panique doit au découpage sans état

`SettingsScreen` ne reçoit qu'un booléen et un rappel. La confirmation du mode panique, son annulation,
le refus de confirmer sans le mot-clé et la désactivation de la ligne pendant l'effacement se mesurent
donc **sans rien détruire**. À travers le vrai `PanicViewModel`, ce test effacerait la base du S9 **et
le modèle vocal de 57 Mo** — le sinistre de §72, mais volontaire. C'est le seul moyen de mesurer la
seule protection du geste le plus destructeur de l'application : le mot à recopier.

⚠️ Le mot est saisi **en minuscules** dans le test, exprès : la comparaison ignore la casse, et c'est
un choix écrit (« quelqu'un sous stress tape sans majuscule »). Le vérifier en majuscules laisserait ce
choix non mesuré.

## §78 — 🔴🔴 Le second balayage a demandé TROIS versions, et le témoin a arrêté les deux premières

`05-PARITE.md` notait depuis §74 que *« le motif inverse demande son propre contrôle »* — un **nom sans
action**, là où `actionnablesSansNom` cherche une **action sans nom**. La phrase est restée écrite sans
instrument pendant deux lignes de parité. Le voici, et son écriture est plus instructive que lui.

### Les deux versions muettes

1. **« remonter au premier ancêtre fusionnant, soi-même inclus, et vérifier qu'il porte l'action »** —
   rendait **0 sur tout**, y compris sur la faute. Cause : **`Modifier.clickable` fusionne lui-même ses
   descendants**, donc le premier nœud fusionnant rencontré est toujours le nœud cliquable, qui porte
   l'action par construction. Le filtre ne pouvait structurellement rien signaler.
2. **« un nœud actionnable de l'arbre non fusionné, absent de l'arbre fusionné »** — **0 sur tout**
   aussi. Le nœud cliquable **existe** dans les deux arbres : le défaut §74 n'est pas une absorption,
   c'est **deux nœuds distincts**, l'un qui nomme et l'autre qui agit.

### La version retenue

Pour chaque nœud actionnable de l'arbre non fusionné, remonter à ses **ancêtres** — **en s'excluant
soi-même** — jusqu'au premier qui fusionne. S'il porte un **nom** et **aucune action**, c'est lui que
le lecteur d'écran focalise : nommé, et inerte.

### ⚠️⚠️ La leçon, et c'est la troisième fois dans la même journée

**Seul le témoin a dit que les deux premières versions étaient muettes.** Après le `grep` ancré par `$`
dont le témoin positif (`malloc`, attendu > 0, rendu 0) a révélé la faute, et l'assertion négative sur
la carte de corbeille dont le témoin a découvert §74 : *un filtre qui ne signale rien est indiscernable
d'un code sans défaut.*

Le témoin pose **trois** cibles dont une seule est fautive — la faute de §74, son correctif, et un
bouton ordinaire. Le troisième cas compte autant que le premier : un filtre qui signalerait tout bouton
de l'application deviendrait illisible, donc inutilisé.

### ✅ Et un contrôle positif sur le VRAI code, pas seulement sur un vecteur

Un filet qui passe ne prouve pas qu'il attraperait le défaut. Le défaut §74 a donc été **remis en place
dans `NoteCard.kt`** — sémantique sur le `Surface`, `clickable` sur la `Column` — le temps d'une mesure
sur le S9 :

```
1) une_carte_de_l_accueil_s_annonce_activable_sur_le_noeud_qui_porte_son_nom
   AssertionError: Failed to assert the following: (OnClick is defined)
2) aucune_action_de_l_accueil_n_est_perdue_a_la_fusion
   expected to be empty
   but was: [Rect.fromLTRB(36.0, 636.0, 1044.0, 930.0)]
```

Le rectangle est la carte de note. Fichier restauré par `git checkout --` — **pas** depuis une copie de
travail : le `cp` de sauvegarde s'était révélé douteux, et git est la seule source qui ne mente pas sur
ce qu'elle contient.

⚠️ Les quatre écrans mesurés passent ce second balayage. Il ne trouve donc **rien de neuf aujourd'hui** :
sa valeur est le filet de régression, et les cinq écrans qui restent.

## §79 — ⚠️ Le bouton micro de l'éditeur nommait le titre d'un autre écran

Relevé par un **balayage de cohérence** lancé sur tout `ui/`, sur le motif de §73 et §77 : *une clé qui
existe pour un usage précis, orpheline, remplacée par une clé d'un domaine voisin.*

`NoteEditorScreen.kt:342` portait `contentDescription = stringResource(R.string.voice_setup_title)` —
le titre de l'**écran d'installation du modèle**, que ce bouton n'ouvre pas : `dictee.demarrer` lance un
enregistrement. La chaîne dédiée `note_editor_tooltip_dictate` existe, traduite des deux côtés, et
n'était lue **nulle part**. L'application publiée l'emploie précisément ici
(`voice_record_button.dart:59`).

⚠️ **Aucun défaut audible aujourd'hui** : les deux valeurs coïncident en français comme en anglais
(« Dictée vocale » / « Voice dictation »). C'est un défaut **latent** — le jour où le titre de l'écran
de réglages se distingue de l'action, ce bouton annoncerait un titre d'écran sans rapport, en silence.

🔧 **Le discriminant réutilisable, déjà éprouvé en phase 6** : une chaîne traduite des deux côtés et lue
nulle part est un **signal**. Ici il a suffi de demander *son jumeau est-il utilisé dans l'application
publiée, et pour quoi ?* — et la réponse nommait le bouton exact.

## §80 — 🔴🔴 Les deux champs de l'éditeur n'avaient AUCUN nom accessible, note remplie

Cinquième ligne d'écran, et le défaut le plus discret de la série — parce qu'il **n'existe pas** dans
l'état sous lequel on relit un éditeur.

Le titre et le contenu étaient écrits de la façon la plus naturelle qui soit :

```kotlin
TextField(value = state.title, onValueChange = …, placeholder = { Text(…) })
```

### Ce que la mesure a donné, et dans quel ordre

Sonde jetable sur le S9, trois `TextField` Material3, arbre **fusionné** :

| Champ | `EditableText` | `Text`, c'est-à-dire le nom annoncé |
|---|---|---|
| `label` + contenu | `valeur-A` | **`[libelle-A]`** |
| `placeholder` + contenu | `valeur-B` | **`null`** |
| `placeholder` + contenu **vide** | `` | `[indice-C]` |

Un `placeholder` ne nomme donc le champ **que tant qu'il est vide**. Dès la première lettre il
disparaît de l'écran **et** de l'arbre : un lecteur d'écran annonçait, sur une note ouverte, deux
zones de saisie **anonymes** — le titre lu comme du texte, puis la note entière lue comme du texte,
sans que rien ne dise laquelle est laquelle ni ce qu'on est censé y écrire.

L'application publiée porte `labelText` sur les **deux** champs (`note_editor_screen.dart:1072` et
`:1102`), en plus de son `hintText`. C'était donc une **régression de parité**.

### 🔴 Pourquoi trois lignes de parité et deux balayages ne l'avaient pas vu

1. **L'état qui porte le défaut est la note REMPLIE.** Une note neuve — champs vierges, placeholders
   à l'écran — n'a pas le défaut. C'est l'état sous lequel un éditeur se relit, se capture et se
   démontre.
2. 🔴 **`actionnablesSansNom` exclut délibérément les nœuds portant un `EditableText`**, au motif
   qu'un champ vide n'est pas un défaut d'étiquetage. C'est **juste**, et ça laissait un motif entier
   hors de portée des cinq écrans mesurés.
3. `actionsPerduesALaFusion` ne regarde que les **actionnables** : un champ de saisie n'en est pas un.

D'où un **troisième instrument**, `champsDeSaisieSansNom()`, et son témoin à trois cibles — dont la
troisième est le **champ vide**, celui qui a caché le défaut : il ne doit **pas** être signalé, sans
quoi le filtre crierait sur tout formulaire vierge de l'application.

### ✅ Contrôle positif sur le VRAI code, comme §78

Un filet qui passe ne prouve pas qu'il attraperait le défaut. Les deux `label` ont donc été
**retirés** de `NoteEditorScreen.kt` le temps d'une mesure sur le S9 :

```
aucun_champ_de_saisie_de_l_editeur_n_est_sans_nom
  expected to be empty
  but was: [Rect.fromLTRB(0.0, 192.0, 1080.0, 384.0), Rect.fromLTRB(0.0, 384.0, 1080.0, 552.0)]
```

Deux rectangles : les deux champs. ⚠️ Le fichier a été restauré **par l'inverse exact de l'édition,
puis vérifié au SHA-256** contre l'empreinte relevée avant — `git checkout --` n'était pas utilisable
ici, contrairement à §78 : le fichier portait déjà tout le travail non commité de la session. *Une
technique de restauration se choisit d'après l'état du fichier, pas d'après l'habitude.*

### 🔧 Et la chaîne orpheline disait où poser le libellé

`note_editor_content` — « Tapez votre note (Markdown supporté) » — était traduite des deux côtés et
lue **nulle part**. Le publié en fait le `labelText` de ce champ exactement. C'est le même
discriminant qu'au §79, sur le même écran, le même jour : *une chaîne traduite des deux côtés et lue
nulle part est un signal*, et son jumeau publié dit où elle va.

⚠️ Pas de `placeholder` sur le **titre** : il vaudrait la même chaîne que son `label`, et Material3
affiche les deux sur un champ vide et focalisé. Le contenu garde le sien, qui dit autre chose que son
libellé (`[[Titre]] pour lier`).

## §81 — 🔴 Le titre n'était pas plafonné à la saisie, et un titre trop long gelait TOUS les enregistrements

`NotesRepository.saveEdits` refuse un titre de plus de 200 caractères — et il refuse **le titre et le
corps ensemble**, puisque c'est un seul appel. Rien, côté écran, n'empêchait d'y coller un paragraphe.

Conséquence, sur une note dont le titre dépasse : **chaque** enregistrement différé échoue,
indéfiniment. La bannière le dit tant qu'on est sur l'écran — c'est le champ `saveFailureReason`
ajouté en phase 5 — mais l'enregistrement **au départ** échoue lui aussi, et là plus personne n'est là
pour lire. Le texte tapé n'est écrit nulle part.

L'application publiée n'a pas ce trou : `LengthLimitingTextInputFormatter(AppConstants.noteTitleMaxLength)`
sur le champ (`note_editor_screen.dart:1080`) rend l'état **inatteignable**. Régression du portage.

### ⚠️⚠️ La règle a demandé QUATRE versions, et chacune a été arrêtée par une mesure ou une relecture

| Version | Ce qu'elle cassait |
|---|---|
| `take(200)` sec | **tronquait à 200** un titre hérité de 250 — détruit 50 caractères de l'utilisateur pour une règle qu'il n'a pas enfreinte |
| `take(max(200, longueur))` | rendait bien 250, mais **amputés du dernier** : `performTextInput` insère au curseur, donc en tête. Une frappe, un caractère perdu, en silence. **Arrêtée par l'appareil** |
| refus dès que le titre est au plafond, troncature sinon | 🔴 un titre de 180 et un collage de 50 **en tête** : troncature à 200, et les 30 derniers caractères **du titre existant** disparaissent. **Arrêtée par GPT-5.2** |
| `startsWith(actuel)` exigé pour tronquer | 🔴🔴 **tout sélectionner puis coller cessait de fonctionner**, en silence, alors que le même collage dans un champ vide passait. **Arrêtée par Gemini Pro, sur le correctif de la précédente** |

La règle retenue distingue une **insertion** d'un **remplacement** sans avoir la sélection sous la
main — le titre est une `String`, pas un `TextFieldValue` :

> si le **préfixe commun** et le **suffixe commun** de l'ancien et du nouveau texte couvrent à eux deux
> tout l'ancien, alors le nouveau est l'ancien **avec quelque chose d'inséré**, et l'endroit se lit
> dans le préfixe. Sinon l'utilisateur a **supprimé** du texte : c'est un remplacement.

Seule une insertion pure **ailleurs qu'à la fin** est refusée — le seul cas où rogner la fin détruirait
de l'existant. Tout le reste est tronqué au plafond, comme le publié. Un titre hérité trop long peut
donc être **raccourci**, ce qui est le seul chemin qui débloque l'enregistrement.

⚠️ L'application publiée a le défaut relevé par GPT : son `LengthLimitingTextInputFormatter` garde les
200 premiers caractères du nouveau texte **quelle que soit la position du curseur**. Écart assumé de
plus, dans le bon sens — c'est l'argument déjà retenu pour le nom de dossier dans `05-PARITE.md`.

⚠️ Une troncature ne coupe jamais une **paire de substituts** : `take` compte des unités UTF-16, et
couper un emoji en deux laisserait un demi-caractère que l'affichage rend en losange et que le stockage
garde tel quel. La **limite**, elle, reste comptée en unités UTF-16 — comme celle du dépôt et comme
celle du dépôt Dart publié : compter des graphèmes ferait passer des titres que `saveEdits` refuserait
ensuite, c'est-à-dire exactement le défaut que ce plafond ferme.

### 🔴 Le geste de mesure était VACANT, et c'est une mesure qui l'a dit

Sur un titre de 250 caractères, dans le harnais de test — un champ **contrôlé** dont l'état n'est
jamais réécrit, puisque le rappel se contente d'enregistrer ce qu'il reçoit :

| Geste | Candidat remonté au rappel |
|---|---|
| `performTextInput("x")` | **250** caractères |
| `performTextInput("x" × 300)` | **250** caractères |

Le candidat n'excède **jamais** la longueur du texte en place. Aucune saisie ne peut donc produire la
croissance que la garde refuse : le test d'écran passerait avec la garde **comme sans**.

⚠️ *Je n'ai pas d'explication du mécanisme, et je n'en écris donc pas.* Le fait mesuré suffit à la
décision : la moitié « refus » de la règle n'est pas mesurable à l'écran, elle l'est sur la JVM
(`PlafondDuTitreTest`, 7 cas dont un balayage de longueurs). C'est la leçon §76 sous une autre
forme — deux niveaux de test parce qu'un seul aurait été vacant — sauf qu'ici la raison est **mesurée**
et non pressentie.

Ce qui **reste** mesurable à l'écran, et qui l'est : le plafonnement d'un collage sur un champ vide
(200 caractères remontés, contre 300 sans garde), et le fait qu'un titre déjà trop long puisse encore
être vidé.

### ⚠️⚠️ Les deux relectures externes, et la seconde a rattrapé le correctif de la première

Deux tours (GPT-5.2, Gemini 3.1 Pro), **quatre constats retenus, aucun recoupement**. Encore une fois,
chacune a vu ce que l'autre manquait — et cette fois la seconde portait **sur le correctif** de la
première, ce qui est la règle du dépôt et n'avait jamais autant payé.

| Constat | Sort |
|---|---|
| 🔴 GPT — **une troncature au milieu détruit du texte existant** : titre de 180, collage de 50 en tête, la troncature à 200 emporte les 30 derniers caractères **du titre**, en silence | **CONFIRMÉ, corrigé.** Le portage n'a pas la sélection sous la main — le titre est une `String` — mais il n'en a pas besoin : la comparaison des deux chaînes suffit à reconnaître une insertion |
| 🔴🔴 Gemini — **le correctif de GPT interdisait tout remplacement** : `startsWith(actuel)` refuse « tout sélectionner puis coller », en silence, alors que le même collage dans un champ vide passe | **CONFIRMÉ, corrigé.** Un correctif de relecture est du code neuf. Discriminant final : *préfixe commun + suffixe commun couvrent-ils le texte en place ?* — si oui c'est une **insertion**, sinon un **remplacement**, et seule l'insertion ailleurs qu'à la fin est refusée |
| 🔴 Gemini — **l'angle mort de ma table JVM** : elle mesurait le collage en tête et à la fin, pas le remplacement. C'est cette absence qui a laissé passer le défaut ci-dessus | **CONFIRMÉ, corrigé** — deux cas ajoutés, dont l'insertion **au milieu**, que le seul `startsWith` laissait passer dans l'autre sens |
| 🔴 Gemini — **mon test d'écran partait d'un titre VIDE**, et un champ vide passe n'importe quelle garde qui regarde le texte en place | **CONFIRMÉ, corrigé** — un test de plus, sur un titre existant écrasé par `performTextReplacement`. *Le choix des données initiales d'un test peut désarmer la garde qu'il croit mesurer* |
| 🔴 GPT — le témoin du troisième balayage n'assertait que `hasSize(1)`, pas **lequel** est signalé | **CONFIRMÉ, corrigé.** Il compare désormais les **coordonnées** du champ fautif, relevées sur son étiquette de test. Le jour où le filtre signale le champ **vide** à la place, il tombe |
| GPT — `state.title` capturé par la lambda pourrait être **périmé** | **ÉCARTÉ, avec l'argument.** L'invariant qui compte est *un titre parti sous la limite n'y repasse jamais* : le plafond ne dépasse 200 que si `actuel` dépasse 200, et `actuel` est une valeur **déjà acceptée** de l'état. Une lecture périmée est donc une valeur antérieure, elle aussi sous la limite — la récurrence tient quelle que soit la fraîcheur |
| GPT — comptage en **graphèmes** plutôt qu'en unités UTF-16 | **ÉCARTÉ sur le comptage, RETENU sur la coupe.** La limite doit rester en unités UTF-16, comme celle du dépôt *et* comme celle du dépôt Dart publié : compter des graphèmes ferait passer des titres que `saveEdits` refuserait ensuite, c'est-à-dire exactement le défaut que ce plafond ferme. En revanche la **coupe** pouvait scinder une paire de substituts et laisser un demi-caractère : corrigé, avec son cas |

⚠️ **Ce que GPT n'a pas pu voir, et pourquoi** : `git diff HEAD` **ignore les fichiers non suivis**. Son
premier tour n'a donc jamais reçu `PlafondDuTitre.kt` — il l'a dit lui-même, deux fois, en refusant de
conclure. Corrigé par un `git add -N` sur les trois fichiers neufs avant le second tour. *Un relecteur
qui annonce qu'il lui manque un fichier a raison ; c'est le harnais qu'il faut corriger, pas son
constat.*

## §82 — 🔴🔴 Un fichier de test JUnit 4 dans un dépôt JUnit 5 ne tourne pas, et rien ne le dit

`PlafondDuTitreTest` a été écrit avec `import org.junit.Test`. Le gate est passé **vert** :
`ktlintCheck`, `detekt`, `testDebugUnitTest` — `BUILD SUCCESSFUL`.

Il n'avait tout simplement **pas tourné**. `app/build.gradle.kts:219` porte
`unitTests.all { it.useJUnitPlatform() }` : sans moteur vintage, une classe JUnit 4 est ignorée
**sans erreur, sans avertissement et sans ligne de rapport**.

Ce qui l'a dit : le **décompte**. 183 tests JVM avant, 183 après, alors que sept venaient d'être
ajoutés. Et le contrôle qui tranche, plus direct encore : `ls app/build/test-results/` ne portait
aucun fichier au nom de la classe.

| Contrôle | Ce qu'il disait |
|---|---|
| `BUILD SUCCESSFUL` | rien |
| `> Task :app:testDebugUnitTest` (exécutée, pas `UP-TO-DATE`) | rien |
| **compte des tests avant/après** | **le défaut** |
| **présence du XML de la classe** | **le défaut**, sans ambiguïté |

⚠️⚠️ C'est le jumeau exact de §72 côté JVM : *« OK (N tests) » ne dit rien de ce qui n'a pas tourné.*
Là-bas c'était un `assumeTrue` qui ignorait en silence, ici c'est un moteur qui ne reconnaît pas
l'annotation. **La forme du contrôle est la même dans les deux cas : compter, et comparer à ce qu'on
attendait.**

🔧 Le motif a été balayé sur tout le dépôt dans la foulée — `grep -rln "^import org.junit.Test$"
app/src/test/` : **aucun autre fichier**, et 20 classes de test pour 20 rapports XML. Un défaut nommé
se cherche partout où son motif existe.

## §83 — ✅ Le troisième balayage rétro-appliqué aux quatre écrans déjà cochés : rien de neuf, et c'est le résultat

`champsDeSaisieSansNom` est né au cinquième écran (§80). Les quatre premiers avaient donc été cochés
par **deux instruments aveugles à ce motif** — l'un excluant délibérément les nœuds portant un
`EditableText`, l'autre ne regardant que les actionnables. Les rouvrir n'était pas une précaution :
c'était la seule façon de savoir si leur case « Vérifié » disait la vérité.

**Verdict : elle la disait.** L'éditeur était le seul cas.

| Écran | Zones de saisie | Ce qui a été mesuré |
|---|---|---|
| Accueil | **1** — la recherche | balayage vert, requête **remplie** |
| Recherche | **1** — la requête | balayage vert, requête **remplie** |
| Réglages | **0** dans l'écran, **1** dans le dialogue de panique | balayage vert, champ **rempli** |
| Corbeille | **0** | fil-piège : l'écran ne porte **aucun** nœud éditable |

### 🔴 Trois pièges évités en écrivant ces quatre tests, et ils se ressemblent

1. **Un champ VIDE ne discrimine rien.** Son `placeholder` le nomme : le balayage serait vert avec le
   défaut §80 comme sans. Les trois états posés portent donc du texte saisi.
2. 🔴🔴 **Un balayage qui n'a rien trouvé À BALAYER est vert lui aussi.** C'est §78 appliqué au
   troisième instrument : si le dialogue de panique ne s'était pas ouvert, si l'écran de recherche
   n'avait pas composé son champ, l'assertion serait passée sans rien regarder. Chaque test **compte
   d'abord ses champs** — `assertThat(onAllNodes(CHAMP_DE_SAISIE)).hasSize(1)` — et le compte attendu
   est écrit par écran, jamais « au moins un ».
3. **Sur un écran sans aucun champ, appeler le balayage serait l'assertion creuse elle-même.** La
   corbeille affirme donc ce qui est vrai et vérifiable — *elle ne porte aucun nœud éditable* — et cette
   assertion échouera le jour où quelqu'un y ajoutera une recherche. Ce sera l'ordre de brancher le
   balayage, pas le signe d'un défaut. Même idiome que le fil-piège material3 d'`AccueilTest`.

⚠️ Le test des réglages saisit volontairement un mot **faux** : la mesure est identique et le bouton
qui efface les notes reste **désactivé** pendant tout le balayage, ce que le test vérifie avant
d'affirmer quoi que ce soit. *Un test qui arme un geste destructeur pour mesurer autre chose est un
test qu'on relit avec inquiétude.*

### ⚠️ Ce que le relevé statique disait, et pourquoi il ne suffisait pas

Un `grep` sur `TextField(` montrait que **tous** les autres champs du portage portent déjà un `label` —
y compris `ChampDePhraseSecrete` des feuilles de coffre, et `PinSheet` qui n'a aucun champ (des points
de saisie et un pavé). La conclusion était donc connue avant la mesure.

Elle ne dispensait pas de mesurer : un `label` présent dans le source ne dit pas ce que l'arbre
fusionné porte à l'exécution — c'est très exactement ce que §71 a établi pour
`ExtendedFloatingActionButton`, dont le libellé est **écrit dans le source** et **absent de l'arbre**.
*Un relevé statique rend des candidats ; seul l'appareil rend un verdict.*

### 🔧 Ce qui reste hors de portée, et qui le sera jusqu'à sa ligne

Les feuilles de coffre — `VaultSheets.kt`, où l'on saisit une phrase secrète — ne sont mesurées par
aucun test d'écran : `FermetureDeFeuilleTest` pose une feuille **synthétique** pour étudier son veto de
fermeture, pas la vraie. C'est l'endroit où un champ sans nom coûterait le plus, et il attend sa ligne
de parité.

## §84 — 🔴🔴 La feuille d'autocomplétion proposait de CRÉER une note avant d'avoir cherché si elle existe

Le défaut était **localisé et écrit** avant qu'on ouvre sa ligne de parité — comme celui de la
recherche à la sienne, et par le même motif : *une réponse qui ne dit pas à quelle question elle
répond.*

Les suggestions de `[[…]]` passent par un freinage de 120 ms, pendant lequel la liste est **vide**.
Elle est vidée exprès, et ce vidage est lui-même un correctif : garder la liste **périmée** laissait
toucher une proposition appartenant à la requête précédente, et insérer un lien vers une autre note
que celle cherchée.

Mais « vide parce que je n'ai pas encore cherché » et « vide parce qu'il n'y a rien » étaient
indiscernables, et la feuille en tirait **deux** conclusions fausses :

1. elle affichait **« Créer *Alpha* »** avant d'avoir regardé si « Alpha » existe ;
2. sa validation au clavier consulte cette même liste pour décider **lier ou créer**. Une liste vide
   la faisait toujours **créer**.

### 🔴 Ce que ça coûte, et pourquoi ce n'est pas une régression de parité

Le portage diverge **délibérément** de l'application publiée sur ce point : *si le titre tapé existe
déjà, on le lie au lieu d'en créer un second du même nom.* Le publié, lui, crée toujours
(`_onSubmit` ne consulte rien) et propose « Créer » dans la même fenêtre — son commentaire en fait
même une garantie d'interface.

**La divergence était donc annulée dans les 120 ms qui suivent une frappe**, c'est-à-dire au moment
précis où l'on appuie sur « Entrée ». Ce n'est pas un défaut hérité ni une régression : c'est une
amélioration **incomplètement efficace**, ce qui est plus dangereux, parce qu'elle est écrite comme
une garantie.

### 🔧 Le mécanisme, repris tel quel de §76

```kotlin
data class SuggestionsDeLien(val pour: String? = null, val titres: List<Note> = emptyList())

val repondALaSaisie = reponse.pour == saisie
val enAttente = requete.isNotEmpty() && !repondALaSaisie
```

⚠️ `pour` est **nullable**, comme au §76 — `null` veut dire « aucune réponse, pour aucune requête ».
⚠️ **Mais ici la chaîne vide est une vraie réponse**, celle d'une saisie vide, à laquelle on répond
sans chercher. C'est l'inverse du choix de §76, et pour une raison mesurable : sans cette émission, la
feuille resterait « en attente » sur un champ vierge, ce qui n'attend rien. *Un mécanisme se reprend,
pas ses valeurs limites.*

⚠️ La comparaison porte sur la saisie **brute**, celle qui a été transmise au ViewModel, et non sur sa
version élaguée : comparer deux chaînes qui n'ont pas fait le même chemin est le moyen le plus sûr de
croire périmée une réponse qui ne l'est pas.

### ⚠️⚠️ Et une validation ne se jette pas : elle se RETIENT

Trois issues étaient possibles pour « Entrée » pendant l'attente, et deux sont mauvaises :

| Faire | Ce que ça produit |
|---|---|
| créer quand même | le doublon d'origine |
| ignorer la touche | un geste sans effet, silencieux — ce que ce dépôt refuse |
| **retenir, puis appliquer** | la bonne décision, 120 ms plus tard |

D'où `DecisionDeValidation.Attendre` et un drapeau `remember` dans la feuille.

⚠️ **`remember` et non `rememberSaveable`** : une validation qui survivrait à une mort de processus
partirait au retour sans que personne n'ait rien demandé. La fenêtre couverte est de 120 ms.
⚠️ Le drapeau est remis à zéro **avant** d'agir — règle §65 : un événement qui **agit** doit avoir lieu
une fois et pas deux. Et **toute frappe l'annule** : il portait sur un autre titre que celui à l'écran.

### 🔴🔴 Ce que le balayage d'accessibilité a trouvé sur cette feuille — et qui n'est pas du portage

Première fois qu'un balayage tourne sur un `ModalBottomSheet`. Il a signalé **un** actionnable sans
nom, `Rect(492, 168, 588, 312)`. Mesuré : ce sont **deux nœuds distincts aux mêmes coordonnées**.

| Nœud | Actions | Nom |
|---|---|---|
| A | `OnLongClick` **seul** | **aucun** |
| B | `Collapse`, `Dismiss`, `OnClick` | « Poignée de déplacement » |

Le nœud A est posé par `BottomSheetDefaults.DragHandle`, que `ModalBottomSheet` pose **par défaut** :
il n'appartient pas au portage, il n'est pas nommable depuis l'appelant, et il paraîtra sur **toutes**
les feuilles de l'application — déplacement, dossiers, coffres.

⚠️⚠️ **L'instrument n'a pas été affaibli pour autant.** L'exception est nommée **dans le test**, ancrée
sur la poignée **mesurée** — le seul nœud de cette feuille portant une action `Dismiss` — et
l'assertion reste un `containsExactly` : tout autre actionnable muet la fait tomber, et elle tombera
aussi le jour où material3 nommera son nœud. *Une exception se pose là où elle se justifie, jamais dans
l'outil partagé.*

⚠️ L'extension du balayage à l'appui long venait des deux relectures externes du 2026-08-17. Ce constat
en est le premier effet de bord : *un filtre élargi voit aussi ce que les bibliothèques laissent
traîner* — ce qui est le prix, pas le défaut.

### ⚠️ Un échec INTERMITTENT observé une fois, non reproduit — écrit parce qu'il existe

Pendant ce lot, une exécution de la suite complète a rendu :

```
le_bouton_de_vidange_reste_cache_pendant_le_chargement_meme_avec_des_notes(CorbeilleTest)
Assert failed: The component with ContentDescription contains 'Le titre de la note' is not displayed!
```

Le nœud **existe** — c'est « pas affiché », pas « n'existe pas ». Non reproduit en trois exécutions
qui ont suivi : la classe **seule** (14 tests verts), la paire `AutocompletionTest` + `CorbeilleTest`
dans l'ordre de la suite (23 verts), et la suite **complète** (228 verts).

⚠️ Ce test n'a pas été touché par ce lot, et sa `poser` passe déjà par `runOnIdle` — la précaution que
`AccueilTest` documente précisément contre ce genre d'intermittence *« quand la suite grandit »*. Le
noter ici vaut mieux que de le redécouvrir à froid : **une suite de 228 tests instrumentés a désormais
un intermittent connu**, et c'est le premier.

### ⚠️⚠️ Les deux relectures se sont FRANCHEMENT contredites, et le code a départagé

Second tour (GPT-5.2, Gemini 3.1 Pro) visant en priorité le mécanisme de validation retenue, celui
dont je doutais. **Gemini n'a rien trouvé sur les quatre axes**, avec une démonstration point par
point ; **GPT en a rendu six**. Aucun recoupement, pour la troisième fois de la journée.

| Constat | Sort |
|---|---|
| 🔴 GPT — la garde anti-doublon ne consulte **que les suggestions affichées** : si l'homonyme exact n'y est pas, on le crée | **CONFIRMÉ, non corrigé, chiffré.** `suggestTitles` sur-échantillonne 32 candidats **triés par date de modification**, filtre, puis **tronque à 8** ⇒ il suffit de **huit** notes au titre commençant pareil et plus récentes. Pas réparable un cran plus bas : `LOWER()` de SQLite ignore les diacritiques, donc aucune requête exacte ne trouve « Impôts » depuis `impots`. **Le publié est strictement pire** — il ne consulte rien. Écart écrit dans le KDoc de `decisionDeValidation` |
| 🔴 GPT — le contrat `pour == saisie` (brute) n'est **défendu par rien** : un ViewModel qui élaguerait laisserait la feuille en attente **indéfiniment** | **VÉRIFIÉ, tenu aujourd'hui** — `chercherUnTitre` et `transformLatest` passent la saisie brute de bout en bout. Fragilité réelle mais latente, écrite |
| GPT — le drapeau ne mémorise pas **quelle** saisie a été validée | **ÉCARTÉ.** GPT le dit lui-même non déclenchable en l'état, et l'invariant « toute frappe annule » n'est pas une convention : il est **figé par un test**, `une_frappe_annule_une_validation_retenue`. Un refactor qui contournerait `onValueChange` le ferait tomber |
| GPT — les tests instrumentés **injectent** la réponse, donc le contrat du ViewModel n'est pas couvert | **PARTIELLEMENT VRAI, scénario FAUX.** Le trou existe. Mais l'exemple donné — « supprimer l'émission sur saisie vide ⇒ indicateur sur un champ vierge » — **ne se produit pas** : `enAttente` exige `requete.isNotEmpty()` |
| GPT — l'exception « poignée » ancrée sur un `Rect` ne distinguerait pas un second nœud muet aux mêmes coordonnées | **ÉCARTÉ.** `actionnablesSansNom` rend une **liste** de rectangles et `containsExactly` compte les doublons : deux nœuds muets superposés donnent `[r, r]`, l'assertion tombe |
| 🔴🔴 GPT — déclenchement double / zéro / sur un titre remplacé | **RIEN**, et Gemini le démontre indépendamment. Un test de plus a été ajouté pour le figer : **deux appuis sur « Entrée » ne font qu'une action** |

### 🔴🔴 Le plus instructif : un commentaire faux a fabriqué sa propre confirmation

En vérifiant le quatrième constat, je suis tombé sur **un commentaire que je venais d'écrire et qui
était faux** : *« sans cette émission, la feuille resterait en attente sur un champ vierge »*. Non —
`enAttente` exige `requete.isNotEmpty()`, donc une saisie vide n'attend jamais, et **mon propre test
JVM le prouvait déjà**.

⚠️⚠️ **Gemini a repris cette affirmation telle quelle** dans son rapport, comme argument de son
« aucun défaut trouvé ». *Un relecteur lit aussi les commentaires : un commentaire faux ne trompe pas
seulement le prochain lecteur, il fabrique la confirmation qu'on venait chercher.* C'est une raison de
plus de ne jamais écrire dans un commentaire un mécanisme qu'on n'a pas mesuré — et c'est le second
commentaire menteur trouvé dans ce dépôt, après celui du §-coffre auto-détruit.

## §85 — ✅ Les deux fragilités « écrites faute d'être mesurables » sont désormais mesurées

Le second tour de relecture avait laissé deux constats **vrais et non corrigés**, et la raison écrite
était la même dans les deux cas : *le ViewModel n'est pas exerçable hors appareil, et les tests
d'écran injectent la réponse*.

1. **le contrat `pour == saisie` porte sur la saisie BRUTE**, et rien ne le défendait. Le jour où
   quelqu'un élaguerait la requête avant de la réémettre, `repondALaSaisie` serait faux **pour
   toujours** : feuille en attente indéfinie, validation retenue qui ne part jamais ;
2. **l'ordre d'émission n'était couvert par aucun test.**

⚠️⚠️ *Une fragilité qu'on sait seulement écrire est une fragilité qu'on ne saura pas voir revenir.*
Un commentaire ne tombe pas quand le code change — c'est la leçon du commentaire menteur du §84, sous
une autre forme : là un commentaire affirmait un mécanisme faux, ici deux commentaires **corrects**
gardaient à eux seuls un contrat que personne ne vérifiait.

### La correction : rendre le contrat mesurable, pas le documenter mieux

`fluxDeSuggestions` est **sorti du ViewModel** — extension sur `Flow<String>`, paramétrée par le
freinage et par une lambda de recherche. Le ViewModel n'en garde que le câblage. Quatre cas JVM en
**temps virtuel** (`FluxDeSuggestionsTest`) :

| Cas | Ce qu'il fige |
|---|---|
| réponse à une saisie **`"Alpha "`** | `pour` vaut `"Alpha "`, espace compris — **et** `etatDAutocompletion` n'attend plus |
| `pour = null` | part à `currentTime == 0`, **avant** le freinage ; la réponse à `120` |
| saisie **vide** | répond à `currentTime == 0` et **n'appelle jamais** la recherche |
| frappe pendant le freinage | la recherche n'est appelée **qu'avec la dernière saisie** |

⚠️ Le premier test vérifie les **deux moitiés** du contrat ensemble — ce que le flux émet, et ce que
la fonction d'état en fait. Séparées, chacune peut dériver sans que l'autre le voie.

⚠️ Le quatrième asserte la **liste** des appels et non leur **nombre** : « une seule recherche » et
« la recherche de la dernière saisie » sont deux exigences, et un compte ne distingue pas la seconde.

### ✅ Contrôle positif : le test tombe bien sur la faute qu'il vise

Quatre tests verts du premier coup, donc suspects. Le contrat a été cassé **dans le vrai code**, de la
façon exacte que la relecture redoutait — `pour = texte.trim()` — et la mesure a rendu :

```
FluxDeSuggestionsTest > la reponse porte la saisie BRUTE, espace final compris FAILED
```

Fichier restauré par l'inverse exact de l'édition, **vérifié au SHA-256** contre l'empreinte relevée
avant. Troisième contrôle positif de la journée après §80 et §78 — *un filet qui passe ne prouve rien
tant qu'on ne l'a pas vu attraper.*

---

## §86 — 🔴🔴 Le coffre était détruit et **personne ne l'annonçait**

Mesuré sur le S9 le 2026-08-18, arbre sémantique des deux feuilles de coffre, **tous états posés** :

```
CODE/DEVERROUILLAGE régionsActives=[]
CODE/MAUVAIS        régionsActives=[]      (PIN incorrect. Tentatives restantes : 3)
CODE/EFFACE         régionsActives=[]      (Trop de tentatives — le coffre a été effacé.)
CODE/CHIFFREMENT    régionsActives=[]
CODE/PARTIEL        régionsActives=[]
PHRASE/CREATION     régionsActives=[]
```

**Aucun nœud, dans aucun état.** Cinq codes faux détruisent le contenu du dossier ; le message part à
l'écran et rien ne le dit à quelqu'un qui ne l'a pas. L'application publiée annonce **trois** choses
sur ces mêmes écrans : le témoin d'activité des deux feuilles (`Semantics(liveRegion: true)`), et
l'effacement (`SemanticsService.announce`, commenté « annonce critique »).

🔧 `MessageDEtat` devient une région **`Assertive`** — mais **seulement quand elle a quelque chose à
dire**. Posée en permanence, elle ferait annoncer le silence à chaque frappe sur le pavé, qui remet
l'erreur locale à zéro. Ce cas-là a son propre test : *sans lui, les trois autres passeraient tout
aussi bien avec une région toujours posée, qui est un défaut d'un autre genre.*

⚠️ Le dépôt savait déjà poser une région active — `PanicScreens.kt:229` en a une, `Assertive`, pour
la même raison. **L'idiome existait et n'avait pas traversé.** Ce n'est pas une ignorance, c'est un
oubli, et seul un balayage état par état pouvait le trouver.

---

## §87 — 🔴🔴 La garde anti-décalage était **dimensionnée sur la taille de texte de son auteur**

`MessageDEtat` réservait une ligne blanche, avec ce commentaire : *« Sans hauteur réservée,
l'apparition d'un message décale le clavier numérique. […] Une ligne vide, mais présente : c'est elle
qui empêche le décalage. »* L'application publiée réserve **deux** lignes suivant `textScaler`, après
un bug utilisateur — « on dirait que le clavier n'est pas tactile du tout » (S24 FE, 2026-08-07).

Mesuré, bornes de la touche « 5 » :

| `font_scale` | sans message | avec « PIN incorrect. Tentatives restantes : 3 » | écart |
|---|---|---|---|
| **1,0** | `y = 1032` | `y = 1032` | **0** |
| **2,0** | `y = 1334` | `y = 1430` | **96 px = 32 dp** |

32 dp, c'est **40 % du pas entre deux touches** (72 dp de touche + 8 dp d'écart), sous un doigt déjà
en route, sur un écran qui détruit le coffre au cinquième essai.

⚠️⚠️ **Le défaut était invisible parce que le commentaire était vrai à la taille par défaut.** Tous
les messages de cette feuille tiennent sur une ligne à 100 % ; il faut le réglage d'accessibilité —
c'est-à-dire exactement le public de cette passe — pour qu'ils en prennent deux. *Une garde peut être
réelle et dimensionnée sur le seul cas que son auteur avait sous les yeux.* Troisième forme du
« commentaire qui ment » : ni faux ni vrai, **incomplètement vrai**, comme la divergence du §84.

🔧 Hauteur **minimale** de deux lignes, mesurée en `sp` donc suivant le système. ⚠️ **Pas une hauteur
fixe** : le publié fige et tronque à l'ellipse, mais à 200 % « Trop de tentatives — le coffre a été
effacé. » prend trois lignes, et la figer amputerait la phrase qui annonce la destruction. Le pavé,
lui, n'est plus là à ce moment (§89).

⚠️⚠️ **Le test ne compare pas des positions de touche**, ce qui serait **vacant** à la taille par
défaut — rien ne bouge, avant comme après le correctif. Il mesure l'invariant : *la hauteur réservée à
vide vaut au moins deux fois celle d'une ligne de message*, la hauteur d'une ligne étant **mesurée** et
non écrite. ⚠️ Le témoin de cette mesure doit être **le message le plus court** que la feuille sache
produire : avec « PIN incorrect… », à 200 % il en prendrait deux, l'assertion en exigerait quatre, et
le test tomberait **à tort** sur le réglage qu'il défend.

---

## §88 — ✅ CLOS le 2026-08-18 : l'autoremplissage du champ de phrase secrète est **conservé**, contre le choix du publié

Relevé en mesurant autre chose. L'arbre sémantique du champ porte `ContentType` — la propriété qui le
rend visible du service d'autoremplissage Android (Samsung Pass, Google). L'application publiée s'en
retire **explicitement** : `autofillHints: const <String>[]`, décision **U1 v1.0.9**, commentée *« Le
service Autofill pourrait capturer / proposer la valeur cross-app. »*

Origine mesurée, trois variantes du même champ :

| Champ | `ContentType` | `Password` |
|---|---|---|
| transformation mot de passe **+ `KeyboardType.Password`** | **présent** | présent |
| transformation mot de passe + `KeyboardType.Text` | **absent** | présent |
| sans transformation + `KeyboardType.Password` | **présent** | absent |

⇒ `ContentType` vient du **type de clavier**, `Password` de la **transformation**. Deux propriétés,
deux origines — ne pas les confondre.

### ✅ Décision de Patrice, 2026-08-18 : on **garde** l'autoremplissage

Et la mesure qui a tranché n'était pas celle qu'on cherchait. Sondé sur le S9, sur le champ de phrase
secrète :

| Action de sémantique | Présente ? |
|---|:---:|
| `PasteText` | **oui**, champ vide comme rempli |
| `CopyText` / `CutText` | **non** — Compose les retire d'un champ à transformation mot de passe (§90) |
| `SetSelection` | oui, une fois rempli |

**Le champ laisse coller et pas copier.** Un gestionnaire de mots de passe peut donc y **déposer** un
secret, et personne ne peut en **extraire** un : c'est l'asymétrie qu'on veut, et elle tient déjà sans
rien couper.

Ce qui fait pencher la décision : un secret de coffre perdu, ce sont des **notes perdues pour
toujours** — il n'y a aucune récupération, par construction. Un gestionnaire de mots de passe est la
seule sauvegarde réaliste d'un tel secret, et le bloquer pousse vers un secret mémorisable, donc plus
faible, ou vers un secret noté sur papier.

⚠️ **Divergence assumée avec l'application publiée**, qui s'en retire explicitement
(`autofillHints: const <String>[]`, décision U1 v1.0.9, *« le service Autofill pourrait capturer /
proposer la valeur cross-app »*). Le risque qu'elle nomme est réel ; il est mis en balance avec une
perte définitive, et non écarté.

⚠️ **Ce qui n'a PAS changé** : rien. Aucune ligne de code n'a été touchée pour ce point — l'état
mesuré était déjà l'état voulu. Le levier qui aurait fermé l'autoremplissage restait par ailleurs
douteux : retirer `KeyboardType.Password` **échangerait** l'opt-out contre le comportement mot de passe
du clavier, et `importantForAutofill = NO_EXCLUDE_DESCENDANTS` prouverait qu'un drapeau est posé, pas
qu'un service l'honore. *Une garde qu'on ne sait pas mesurer est une garde qu'on ne sait pas défendre.*

---

## §89 — 🔴 Après l'effacement, le portage laissait le pavé et « Valider »

Le garde qui retire les commandes quand l'action n'a plus de sens n'existait que pour `coffreExiste()`
— un coffre créé dont le contenu n'est pas entièrement chiffré. Sa raison était : *relancer l'action
échouerait, et ce refus écraserait le message qui compte.*

`VaultAttempt.Wiped` a **exactement** cette forme et n'était pas couvert. Après cinq codes faux, le
coffre est détruit et le portage laissait pavé, « Valider » et « Annuler » actifs. Retaper un code sur
un coffre qui n'existe plus fait remonter « ce dossier n'est pas un coffre » **par-dessus** la seule
phrase qui disait que les notes ont été effacées. Le publié retire son pavé sur ce chemin
(`vault_pin_sheets.dart:565`) et remplace « Annuler » par « Fermer ».

**Motif du jumeau asymétrique, troisième occurrence dans ce seul fichier** — après
`ChampDePhraseSecrete` et `chiffreesSiTermine`. 🔧 Un prédicat unique,
`plusRienAEssayer() = coffreExiste() || Wiped`, posé sur les **deux** feuilles bien que `Wiped` ne
puisse naître que d'`unlockWithPin` (vérifié : les trois `throw VaultPinWipedException` du service y
sont tous). *Un garde écrit sur une seule des deux feuilles est précisément ce qui a produit les deux
précédents.*

⚠️ Et son KDoc promettait « ni pavé, ni champ » alors que la feuille à phrase secrète **gardait ses
deux champs** affichés sous le bouton « Fermer », à côté du message annonçant que des notes sont
restées en clair. Le code a été mis d'accord avec le commentaire, des deux côtés — pastilles comprises.

---

## §90 — ✅ Ce que la mesure a **réfuté**, et qu'il ne faut pas re-chercher

Le résultat le plus utile de ce tour n'est pas une correction. Trois soupçons sérieux, tous fondés sur
une lecture du code publié, sont tombés à la mesure :

| Soupçon | Mesure | Verdict |
|---|---|---|
| Les champs perdent leur nom une fois remplis (§80) | `texte=[Passphrase]` avec 22 caractères saisis | **NON** — c'est un `label`, pas un `placeholder` |
| `EditableText` livre le secret à tout service d'accessibilité | `editable=••••••••••••••••••••••` | **NON** — la transformation s'applique |
| « Copier »/« Couper » exposent la phrase, comme le craignait le publié | `CopyText=false`, `CutText=false` | **NON** — Compose les retire déjà |

⚠️⚠️ **Le troisième a coûté trois instruments.** Un espion de `TextToolbar` n'a rien enregistré — mais
**pas davantage sur un champ ordinaire**, donc il ne prouvait rien : *un échec d'outillage n'est pas un
verdict négatif.* Le geste a été corrigé (l'appui long tombait au centre d'un champ large, loin après
la fin du texte), sans plus de succès. C'est la **sémantique** qui a tranché, avec son témoin :
`CopyText`/`CutText` présents sur un champ ordinaire, absents sur un champ à transformation mot de
passe. Le `contextMenuBuilder` du publié n'a donc pas d'équivalent à écrire ici.

⚠️ Deux autres pistes fermées : `error_vault_pin_wrong` et `error_vault_pin_wiped` sont orphelines
**dans l'application publiée aussi** — ce n'est pas le signal « chaîne traduite et jamais lue » de §79.
Et la `ModalBottomSheet` compose dans une fenêtre qui **repose ses propres `CompositionLocal`** : ni
`LocalDensity` ni `LocalTextToolbar` fournis au-dessus d'elle n'y entrent. Deux mesures à l'échelle ×2
rendaient des bornes identiques **au pixel près** — impossible, et c'est ce qui a dénoncé l'instrument.
La taille de texte se mesure par `adb shell settings put system font_scale`, pas autrement.

---

## §91 — 🔴🔴 Un geste destructeur accepté, un secret saisi, et **rien**

Deux gestes de dossier déchiffrent tout son contenu, donc exigent une session de coffre ouverte :
**retirer la protection** et **supprimer en gardant les notes**. Ils étaient écrits séparément dans
`HomeRoute`, et un seul mémorisait son intention avant d'ouvrir la feuille de déverrouillage.

| Geste | coffre fermé ⇒ | intention mémorisée |
|---|---|---|
| retirer la protection | feuille de déverrouillage | **oui** (`deprotectionEnAttente`) |
| supprimer en gardant les notes | feuille de déverrouillage | **NON** |

Le parcours réel : l'utilisateur choisit « Supprimer », confirme « Déchiffrer et déplacer », on lui
demande le secret du coffre, il le tape **correctement** — le coffre s'ouvre, le dossier est toujours
là, et rien ne dit que le geste a été abandonné. L'application publiée enchaîne les deux
(`folders_drawer.dart`, `_withVaultSession`).

⚠️⚠️ **Le KDoc du porteur décrivait exactement ce mal — pour l'autre geste** : *« Sans ce report,
l'utilisateur confirmait le geste le plus destructeur de l'application, saisissait sa phrase
secrète… et il ne se passait rien. »* Il était juste, il était au bon endroit, et il n'a pas empêché
le jumeau de naître à quinze lignes de là. *Un commentaire qui nomme un défaut ne protège que la
ligne qu'il commente.*

🔧 **La correction supprime le jumeau plutôt qu'elle n'ajoute la ligne manquante** : une interface
scellée `GesteDeDossier`, une décision unique `deverrouillageRequis(geste, coffresOuverts)`, un seul
chemin de départ `lancerLeGeste`, et un `when` exhaustif pour l'exécution — un troisième geste ne
pourra pas naître sans qu'on décide de sa reprise.

⚠️ **La reprise après déverrouillage ne repasse PAS par le contrôle.** L'ensemble des coffres ouverts
vient d'un flux, et il peut ne pas encore porter celui qu'on vient d'ouvrir : rouvrir la feuille en
boucle serait le remède pire que le mal. `onUnlocked` exécute directement, après avoir comparé
l'**identifiant** du dossier — la feuille peut avoir été ouverte pour un autre, le verrouillage
automatique en ouvre une lui aussi.

⚠️⚠️ **Ce que les tests couvrent, et ce qu'ils ne couvrent pas.** `GesteDeDossierTest` (5 cas JVM)
mesure que la **décision** est identique pour tous les gestes — c'est le jumeau supprimé. Il ne
mesure **pas** que `HomeRoute` passe par elle : cela tiendrait à un harnais Hilt + base chiffrée +
coffre réel, hors de proportion. Ce qui protège cette partie-là est structurel — un seul chemin, un
`when` exhaustif — et non mesuré. *Le dire vaut mieux que de laisser croire le contraire.*

🔧 Le filet contre la re-divergence n'est pas dans les cas de test mais dans une fonction `etiquette`
à `when` exhaustif : un troisième geste **fait échouer la compilation du fichier de test** tant que
personne n'a décidé de sa reprise. Contrôle positif fait : la clause `isVault` retirée du vrai code
fait tomber un cas ; restauration vérifiée au SHA-256.

---

## §92 — ✅ Le tiroir des dossiers : rien de cassé, et trois observations écrites

Quatorze mesures sur `FoldersDrawer`, cinq sur ses dialogues. **Aucun défaut d'annonce.** Le soupçon
principal est tombé :

⚠️ **Un `IconButton` posé dans le slot `badge` d'un `NavigationDrawerItem` reste atteignable.** C'est
un cliquable **dans** un cliquable qui fusionne ses descendants — la forme même de §74 — et le `⋮`
comme le bouton de renommage de la boîte auraient pu être absorbés. Mesuré par **comportement** et
non par présence : `onNodeWithContentDescription` aurait rendu la rangée fusionnée, dont le nom
contient bien « Options du dossier », et l'assertion `assertHasClickAction` serait passée pendant
qu'un appui sélectionnait le dossier. Le test appuie et regarde **quel rappel part**.

Trois écarts avec l'application publiée, **écrits et non corrigés** :

1. **Le portage n'a aucun appui long.** Le publié en pose un sur chaque dossier et sur la boîte de
   réception, en plus du bouton. Divergence assumée : le publié écrit lui-même que *« le long-press
   n'est pas découvrable »*, un geste long n'a pas d'équivalent au lecteur d'écran, et la boîte de
   réception — qui n'avait **que** l'appui long — n'était renommable par personne ici avant qu'on lui
   donne son bouton. ⚠️ Le KDoc du tiroir disait « l'appui long fait la même chose » : vrai du publié,
   **faux d'ici**. Corrigé.
2. **`FolderEvent.Deleted(movedNotes)` porte un décompte que personne ne lit.** Supprimer un dossier
   déplace ses notes vers la boîte de réception **en silence** ; le nombre est calculé puis jeté. Le
   publié ne dit rien non plus — c'est donc de la parité, mais la donnée est là et la phrase serait
   utile. Décision de Patrice.
3. **Les feuilles de coffre sont ouvertes par `HomeRoute`, pas par le tiroir.** Le publié les ouvre
   depuis `folders_drawer.dart`. Divergence d'architecture sans effet sur ce qui est annoncé — mais
   elle a fait tomber la prémisse avec laquelle ce tour avait été proposé.

⚠️ **Le tiroir en cours de chargement affirme quelque chose de faux, et c'est hérité.** La valeur
initiale de `stateIn` est `FoldersUiState()`, donc `inbox == null` — que le code traite comme « base
abîmée » : il affiche le **nom traduit de repli** au lieu du nom réel et **retire le bouton de
renommage**. Motif §75/§76. L'application publiée a exactement la même faiblesse
(`snap.data ?? const <Folder>[]` avec un dossier de repli fabriqué). **Figé par un test plutôt que
corrigé** : ce que le tiroir montre à ce moment-là ne changera plus par accident.

---

## §93 — 🔴🔴 Pendant qu'on parle, aucune sortie qui ne transcrive

La superposition de dictée proposait **une seule** action par étape. Mesuré sur le S9,
`onClick=1` dans les trois états actifs. Or « Arrêter » et « Annuler » ne diffèrent pas par le mot
mais par ce qu'ils font de la voix captée :

| Geste | la capture | le fichier WAV | la note |
|---|---|---|---|
| **Arrêter** | se termine | transcrit puis effacé | **le texte s'y insère** |
| **Annuler** | est coupée | effacé sans être lu | **rien** |

Pendant l'enregistrement, seul le premier existait. Un appui involontaire sur le micro, une phrase
dite à voix haute qu'on ne voulait pas garder, et il ne restait qu'à **laisser la dictée aller au
bout** puis effacer le texte inséré. Sur une note de coffre, cela veut dire faire transcrire ce
qu'on venait de décider de ne pas écrire.

⚠️⚠️ **Le commentaire de la feuille justifiait l'absence**, et c'est ce qui l'a fait tenir : il
tenait les deux boutons pour *« deux mots pour un même geste »*. Quatrième forme du commentaire qui
ment — non pas faux sur un détail, mais **argumentant en faveur du défaut**. Un relecteur qui le
lisait avait sa réponse et passait.

🔴 `abandonner` **existait** : posé sur le contrôleur, câblé au `ViewModel`, traversant jusqu'au
moteur natif via `WhisperStt`, et documenté. **Aucun bouton ne l'appelait dans cet état.** Chemin
mort classique — sauf que celui-ci était mort du côté où l'utilisateur en avait besoin.

🔧 Un `dismissButton` pendant l'enregistrement seulement, et **une seule définition** du bouton
d'annulation (`val annuler: @Composable () -> Unit`) posée tantôt dans l'emplacement de confirmation,
tantôt dans celui de rejet. Deux définitions auraient divergé au premier changement de libellé —
c'est le jumeau qu'on refuse de créer en réparant l'autre.

⚠️ **Écart assumé** : le publié annule aussi sur le **retour** (`PopScope`,
`onPopInvokedWithResult`). Ici le retour et l'appui à côté arrivent par le **même** rappel,
`onDismissRequest`, qui ne dit pas lequel des deux l'a déclenché — et l'appui à côté, lui, ne doit
surtout pas couper une dictée en cours. Le bouton couvre le besoin sans reprendre la fenêtre du
dialogue.

---

## §94 — 🔴🔴 Le changement d'étape n'était annoncé à personne, sur un écran aux balayages VERTS

Mesuré état par état, `régionsActives=[]` dans les **trois** états actifs. Un `AlertDialog` est
annoncé **à son ouverture** ; le texte qui change ensuite dans ses emplacements ne l'est pas. Le
parcours réel d'un utilisateur non voyant :

| Ce qui se passe | Ce qu'il entend |
|---|---|
| la superposition s'ouvre | « Initialisation du micro… Veuillez patienter… Annuler » |
| le micro s'ouvre, **il faut parler** | *rien* |
| la transcription commence | *rien* |
| le texte s'insère | le message de la barre, plus tard |

L'étape `INITIALISATION` avait justement été ajoutée en phase 7 parce que dire « Parlez » trop tôt
faisait perdre le premier mot — sa note de code le dit. **Au lecteur d'écran, « Parlez » n'était
jamais dit du tout.**

⚠️⚠️ **Et les trois balayages étaient verts avant comme après.** `actionnablesSansNom`,
`actionsPerduesALaFusion`, le décompte des champs : rien à signaler, aucun nœud anonyme, aucune
action perdue. *Un écran sans nœud anonyme peut être un écran qui ne dit rien.* C'est le troisième
fichier où la question « que reçoit un lecteur d'écran ? » n'a de réponse que si on la pose **état
par état**, après §86 (les feuilles de coffre) et l'idiome déjà présent dans `PanicScreens.kt:229`.

🔧 **Deux régions et non une**, parce que le titre et la consigne vivent dans deux emplacements
distincts d'`AlertDialog` et que l'un sans l'autre ment : le titre seul dirait « Dictée en cours »
sans dire de parler ; la consigne seule dirait « Veuillez patienter… » pendant l'initialisation
**comme** pendant la transcription, sans jamais nommer l'étape.

⚠️ **`Polite` et non `Assertive`** — seul point où cette feuille diverge de §86, et pour une raison
tenant au nombre : là-bas il y a **un** nœud à annoncer, ici **deux**. En `Assertive`, la seconde
annonce couperait la première et le titre serait perdu.

⚠️ La consigne **fusionne** ses descendants, sans quoi la région porterait sur un conteneur sans
texte propre et n'annoncerait rien — même construction qu'au §86. Et c'est le `clearAndSetSemantics`
du témoin de niveau sonore qui rend cette fusion tenable : sans lui, la région annoncerait à chaque
échantillon.

⚠️⚠️ **Le test lit l'arbre FUSIONNÉ, et rend une LISTE, pas une table.** L'arbre non fusionné rendrait
la consigne vide — la région y est posée sur le conteneur. Et une table indexée par le texte
effondrerait deux régions de même libellé en une seule clé : un troisième nœud annonceur passerait
inaperçu.

**Contrôle positif fait** : les deux défauts réintroduits dans le vrai code font tomber **4 cas sur
10** ; restauration vérifiée au SHA-256.

---

## §95 — 🔴 Le micro sans modèle était une impasse, et le publié n'en a pas

Sans modèle de transcription installé, l'appui sur le micro affichait « Aucun modèle de
transcription installé. » — un constat exact, et rien d'autre. L'écran d'installation **existait**,
mais n'était atteignable que depuis les réglages : quitter l'éditeur, ouvrir le menu, trouver la
bonne entrée, sans que rien ne l'indique. L'application publiée, elle, ouvre cet écran
**directement** depuis ce bouton (`voice_record_button.dart:33-38`).

🔧 La destination remplace le message : `onInstallerLaDictee` traverse `NoteEditorRoute` jusqu'à
`Destination.VoiceSetup`.

⚠️ Le message **reste**, pour l'autre chemin : le modèle peut disparaître entre le contrôle et
l'ouverture du micro, et `DictationViewModel` émet alors `ModeleAbsent`. Ce n'est plus le cas
ordinaire mais une course — et elle n'a pas de destination à proposer, l'utilisateur venant
peut-être de désinstaller le modèle lui-même. Ne pas prendre ce message pour un chemin mort.

🔧 **La décision est extraite** en `gesteDuMicro(modeleInstalle, permissionAccordee)` sur une
interface scellée à trois issues — même idiome que `GesteDeDossier` au §91. Elle était écrite en
`if` imbriqués dans un rappel Compose, donc **hors d'atteinte de toute mesure** : il aurait fallu
Hilt, un magasin de modèles et une permission réelle pour savoir laquelle partait.

⚠️ **L'ordre entre les deux contrôles est le fond du sujet**, et il a son cas à lui : sans modèle
**ni** permission, c'est l'installation qui gagne. Demander le micro pour une dictée qui ne peut pas
aboutir, c'est faire refuser durablement une permission dont on n'avait pas encore l'usage — et un
refus définitif ne se reprend que dans les réglages système.

⚠️ Les deux contrôles sont désormais évalués **avant** la décision, alors que l'ancien code
n'interrogeait la permission que dans la branche « modèle présent ». Sans effet :
`checkSelfPermission` **interroge**, il ne demande rien. C'est la *demande* qui doit attendre, et
elle attend toujours.

⚠️⚠️ **Limite écrite** : les 5 cas JVM mesurent la **décision**, pas le câblage — ni que le rappel
passe par elle, ni que `NotesTechNavHost` mène bien à `VoiceSetup`. Comme au §91, ce qui protège
cette partie est structurel (un seul appel, un `when` exhaustif) et non mesuré.

`signalerModeleAbsent()` n'ayant plus d'appelant, elle est **supprimée** plutôt que laissée en
réserve.

---

## §96 — 🔴🔴 Une borne qui s'applique en silence, et les deux moments où il faut le dire

`VoiceCapture` arrêtait la capture à `OCTETS_MAX`, soit **120 secondes**, et la boucle sortait alors
exactement comme si l'utilisateur avait appuyé sur « Arrêter » : même `File`, même transcription,
même insertion, **même message** « Texte inséré. ». Rien ne distinguait les deux fins.

Le parcours : on dicte trois minutes, on relit la note, il en manque une. Sans le savoir, et sans
moyen de le savoir.

⚠️ **L'application publiée n'a aucune borne** — vérifié dans `stt_session.dart` et
`voice_service.dart`, il n'y en a nulle part. Elle affiche en revanche un **chronomètre mm:ss** en
région active. Le portage n'avait ni l'un ni l'autre : ni la borne annoncée, ni le temps écoulé. La
borne elle-même est justifiée et documentée — un micro qui reste ouvert est un micro qui reste
ouvert ; ce n'était pas elle le défaut, c'était son silence.

### 🔧 Deux réponses, à deux moments, et aucune ne remplace l'autre

| Quand | Quoi | À quoi ça sert |
|---|---|---|
| **pendant** qu'on parle | le compteur « 1:37 / 2:00 » | ne pas y arriver |
| **après** l'insertion | « Texte inséré. Limite de 2:00 atteinte : la suite n'a pas été enregistrée. » | savoir qu'on y est arrivé |

⚠️ **Le compteur NOMME la borne**, il n'affiche pas le seul temps écoulé. On ne se méfie pas d'un
chiffre qui monte ; « 1:37 / 2:00 » dit qu'il y a une fin avant qu'elle n'arrive. Le publié, lui,
n'a qu'un temps écoulé — mais il n'a rien à annoncer.

⚠️ **La durée du message est celle de l'écran**, par la même constante et le même formateur
(`dureeMmSs`, `DUREE_MAX_SECONDES`). Écrire « 2 minutes » dans la traduction aurait créé un second
endroit où la borne est dite, et le jour où elle change, l'un des deux mentirait.

### ⚠️⚠️ Ce que la mesure a corrigé dans le correctif lui-même

Le compteur était d'abord **masqué par `clearAndSetSemantics`**, par analogie avec le témoin de
niveau sonore, et le raisonnement paraissait solide : la colonne est une région active, un texte qui
change chaque seconde y serait annoncé chaque seconde.

Sauf qu'**un nœud effacé disparaît des DEUX arbres**. Le compteur n'était plus lisible par personne,
même à l'exploration au doigt — on l'avait rendu invisible pour éviter qu'il ne crie. Le test l'a dit
en tombant, et la bonne construction est de le poser en **frère** de la région plutôt que dedans :
*ne pas crier n'oblige pas à se taire.* Le témoin de niveau, lui, reste effacé — il n'a rien à lire.

### 🔧 Ce qui a dû changer pour que la borne puisse parler

`enregistrer()` rendait un `File?`, qui **ne peut pas dire pourquoi** la capture s'est arrêtée. Il
rend désormais une `Capture(fichier, fin)` sur une énumération `FinDeCapture` à deux valeurs.

⚠️ **Un type de retour, et non un drapeau lu à côté** : un appelant ne peut pas oublier de regarder
ce qu'il reçoit, alors qu'il peut très bien ne jamais lire une propriété.

⚠️⚠️ **`fin` voyage AVEC le texte**, dans `IssueDeDictee.Texte`, plutôt que de faire un cas à part.
Un `TexteTronque` séparé aurait obligé à reprendre **deux** endroits — celui qui insère et celui qui
parle — et le premier l'aurait oublié : l'insertion se lit par `is IssueDeDictee.Texte`, et un type
neuf serait passé à côté **sans que rien ne le signale**. Le texte s'insère de la même façon dans les
deux cas ; seul ce qu'on dit ensuite change. C'est le jumeau qu'on refuse de créer en réparant.

🔧 **La classification est extraite** en `VoiceCapture.finDeCapture(octetsEcrits)` — troisième
application de l'idiome après `GesteDeDossier` (§91) et `gesteDuMicro` (§95). Elle vivait en une
ligne dans une fonction **privée et suspendue exigeant un `AudioRecord` réel** : la règle la plus
facile à écrire de travers du fichier était la seule qu'aucun test ne pouvait atteindre.

⚠️ **`>=` et non `==`, et c'est le cas qui compte.** `micro.read` rend ce qu'il a, pas ce qu'on
aurait voulu : le dernier tampon fait presque toujours franchir la borne de quelques milliers
d'octets. Un `==` laisserait passer **tous** les cas réels tout en gardant au vert le cas exact —
d'où deux cas de test distincts, la borne pile et le dépassement.

⚠️ Les secondes affichées sont **dérivées des octets écrits**, pas d'une horloge : c'est le même
compteur qui déclenche la borne, si bien que ce que l'écran montre et ce qui coupe la capture ne
peuvent pas diverger. C'est précisément près de la borne qu'un écart compterait.

**Contrôle positif fait** : `>=` remis en `==` fait tomber le cas du dépassement et lui seul ; le
compteur remis **dans** la région active fait tomber les deux cas d'annonce. Restaurations vérifiées
au SHA-256.

---

## §97 — 🔴🔴 Supprimer un dossier ne disait rien, et c'est le seul des trois gestes dont la conséquence est invisible

`05-PARITE.md` portait un écart « à trancher » : *renommer ou supprimer un dossier n'affiche aucun
message*, avec pour justification *« le tiroir se met à jour sous les yeux de l'utilisateur »*.

**La justification est vraie pour deux des trois gestes, et fausse pour le troisième.** Le critère
n'est pas l'importance du geste mais **ce que l'utilisateur voit se produire** :

| Geste | Ce que l'écran montre | Message |
|---|---|:---:|
| renommer | le nouveau nom, dans le tiroir | **non** — le retour est l'écran lui-même |
| créer | la nouvelle ligne | **non** |
| supprimer en gardant les notes | la disparition du dossier ; **rien** sur les N notes déplacées | **oui** |
| supprimer avec les notes | la disparition du dossier ; **rien** sur les N notes détruites | **oui** |

Les notes n'étaient pas à l'écran : leur sort ne peut pas se lire. `FolderEvent.Deleted(movedNotes)`
portait déjà le décompte, **calculé puis jeté** — relevé au §92 et laissé ouvert.

### ⚠️⚠️ Le cas le plus injuste des quatre : les notes déjà en corbeille

Supprimer un dossier détruit **aussi ses notes en corbeille**. `folder_id` est une clé étrangère
`ON DELETE CASCADE`, et la mise en corbeille **conserve** ce lien : les notes que l'utilisateur avait
mises de côté, qu'il voyait encore depuis l'écran Corbeille et qu'il pouvait restaurer, disparaissent
avec le dossier. Rien ne le disait, et rien ne les listait au moment du choix.

🔧 Le nombre annoncé vient donc de `countAllInFolder` — **sans** le filtre `trashed_at IS NULL` — et
non de `countInFolder`, qui l'a. *Un message qui annonce trois notes quand cinq disparaissent est pire
qu'un message absent.*

⚠️ Compté **avant** la cascade, sinon il n'y a plus rien à compter.

### 🔴🔴 Le jumeau qu'on refuse de créer en réparant

Le réflexe est de brancher le message sur `movedNotes` : *zéro ⇒ suppression avec les notes, sinon
déplacement*. **C'est faux**, et d'une façon qui ne se voit pas : `0` veut dire aussi bien
« suppression avec les notes » que **« dossier vide dont on gardait les notes »**. Le message aurait
annoncé une destruction à quelqu'un qui venait de supprimer un dossier vide.

🔧 L'événement **porte le geste** : `Deleted(sort: SortDesNotes, notes: Int)`, énumération à deux
valeurs, `when` exhaustif chez le consommateur. Même leçon qu'au §96 — *un appelant ne peut pas
oublier de regarder ce qu'il reçoit, alors qu'il peut très bien mal interpréter un compteur.*

⚠️ **Zéro note n'est pas « zéro » mais « rien à dire de plus »** : `getQuantityString` avec `0` rend
« Dossier supprimé, 0 note déplacée » en français, la catégorie `one` couvrant zéro. Un dossier vide
n'a pas de sort de notes à annoncer, donc une phrase courte et vraie.

**Contrôle positif fait** : les deux sorts branchés sur le même pluriel font tomber
`les_deux_gestes_ne_disent_PAS_la_meme_chose`, et lui seul. Restauration vérifiée au SHA-256.

---

## §98 — ⚠️⚠️ La catégorie `many` du français : lint la réclame, l'appareil ne la sélectionne pas

`lintDebug` signalait `MissingQuantity` sur les **quatre** pluriels français : *« pour la locale "fr"
la quantité suivante devrait aussi être définie : many »*. Elle ne vaut que pour les multiples
**exacts** d'un million, et Android retombe sur `other` quand elle manque — le comportement était
donc correct, et l'avertissement passait pour un faux positif.

Ce n'en est pas un : la ressource était **incomplète pour sa locale**, et s'appuyer sur un repli
revient à confier une règle de grammaire à un mécanisme de secours. Les quatre formes sont écrites,
`MissingQuantity` est à **0**.

### 🔴 Mais elle ne sort pas sur le S9, et c'est la mesure qui l'a dit

Le premier test affirmait « 1 000 000 **de** notes » et a **échoué** : Android 10 rend
« 1000000 notes ». La catégorie `many` du français est entrée dans **CLDR 38 (2020)**, et l'ICU
embarquée par cette version du système ne la connaît pas. Sur un système plus récent, dont l'ICU est
mise à jour par les modules, elle est sélectionnée.

⚠️⚠️ **Le test accepte donc les deux formes et refuse tout le reste.** Exiger `many` le ferait échouer
sur les vieux appareils, exiger `other` sur les neufs : dans les deux cas il mesurerait la version
d'ICU de la machine de test et rien d'autre. Ce qui doit tenir partout, c'est que la phrase reste
grammaticale et porte le nombre. Son témoin est le cas à un million **moins un**, qui n'a droit qu'à
`other` sur toute version — sans lui, une ressource dont les deux formes seraient identiques
passerait.

*Une forme correcte qu'on déclare sans la mesurer est une forme dont on ignore si elle sort.*

### 🔧 Le garde-fou vaut mieux que la règle écrite

Deux `assert` dans `arb_vers_strings.py`, parce qu'il y a **deux** familles de pluriels :

1. ceux transposés de l'ARB — une table `MANY_FR` doit couvrir **exactement** l'ensemble des pluriels,
   dans les deux sens (aucun pluriel sans forme, aucune forme orpheline) ;
2. ceux écrits à la main dans `AJOUTS_FR` — un balayage exige `quantity="many"` dans chaque bloc.

⚠️ **Le second n'est pas du zèle** : sans lui, la moitié des pluriels du fichier échappait à la règle
tout en donnant l'impression d'être couverte. Il a d'ailleurs levé **immédiatement**, sur
`trash_emptied`, que la première version du garde ne voyait pas.

⚠️ **La forme est écrite À LA MAIN, cle par cle, et non dérivée de `other`.** La forme française
insère « de » après le nombre, ce qui suppose que le nombre soit suivi d'un nom : une règle mécanique
serait juste sur ces quatre chaînes et fausse dès la première qui dirait « %1$d sur 5 ».

⚠️ **Deux conventions d'échappement, deux endroits** : `MANY_FR` porte des apostrophes **brutes** et
passe par `echappe` ; `AJOUTS_*` est du XML **déjà échappé**. Les mélanger fait tronquer la chaîne par
aapt, sans erreur.

### 🔧 Au passage : `%1$d note(s)`

`folder_delete_decrypt_failed` écrivait « pour {n} note(s). », signalé `PluralsCandidate`. Le « (s) »
est le contournement qu'on emploie quand on n'a pas de pluriel — et Android en a un. Réécrite en
`<plurals>` via le mécanisme `REMPLACEES` déjà prévu pour ce cas.

---

## §99 — 🔴 Demander « ce coffre est-il ouvert ? » repoussait le verrouillage automatique

`VaultSessions.isUnlocked` déléguait à `hasLiveSession`, qui posait `lastActivityMillis = now`. Le
prédicat était donc **effectif** : consulter l'état d'un coffre reportait son échéance.

⚠️ **Aucun appelant d'alors n'en souffrait**, et c'est ce qui rend le cas intéressant. Les deux
appels de production — un choix de dossier de destination, un garde de navigation — sont des **gestes
de l'utilisateur**, où reporter est légitime. Le défaut était donc **posé et armé, pas déclenché**.

🔴 Ce qu'il attendait : que quelqu'un demande « ce dossier est-il ouvert ? » depuis une
recomposition, un flux ou un rendu de liste. À partir de là, **chaque redessin** repousse l'échéance
et le verrouillage automatique cesse — en silence, sans qu'aucun test ne le voie, parce qu'aucun test
n'observe un écran qui se redessine. *Une garde dont l'annulation ne se voit nulle part n'est pas une
garde.*

L'application publiée a raison sur ce point : son `isUnlocked` est un `containsKey`, et rien d'autre.

🔧 Le prédicat redevient pur ; le report est **explicite** (`touch`) ou la conséquence d'un vrai usage
de la clé (`sessionKey`). Deux cas JVM le disent — l'un que consulter ne reporte pas, l'autre que
`touch` reporte —, et **le second n'est pas décoratif** : sans lui, le premier serait indiscernable
d'une classe qui ne reporte jamais.

⚠️ **L'expiration paresseuse, elle, reste** : consulter une session périmée la ferme au passage. C'est
un point où ce portage est meilleur que le publié, qui s'en remet à son balayage périodique.

⚠️ **`touch` n'a aucun appelant en production, et ce n'est PAS un câblage oublié.** Vérifié des deux
côtés : le publié appelle son `touchActivity` depuis quatre endroits, **tous des chemins d'écriture**
— enregistrer, créer, ouvrir une note liée — et ces mêmes chemins passent ici par `sessionKey`, qui
reporte déjà. Les événements couverts sont les mêmes. C'est écrit sur la fonction, pour qu'on ne
« répare » pas une absence qui n'en est pas une.

**Contrôle positif fait** : le report remis dans le prédicat fait tomber le cas prévu, et lui seul.
Restauration vérifiée au SHA-256.

---

## §100 — 🔴🔴 La migration v1 → v2 n'était exercée par AUCUN test — des deux côtés

Le format v1 laisse le **titre en clair** dans sa colonne et ne chiffre que le contenu ; le v2 met les
deux dans le blob. La migration doit donc, en **une seule écriture**, poser le nouveau blob *et* vider
la colonne de titre. Une inversion, une écriture partielle, un `pack` qui oublie le titre, et le titre
disparaît des deux côtés — sans erreur, et sans retour possible.

### ⚠️⚠️ Le publié porte une fonction faite EXPRÈS pour rendre ce chemin vérifiable, et personne ne l'appelle

`folder_vault_service.dart` déclare `encryptNoteLegacyV1`, `@visibleForTesting`, avec ce commentaire :

> *« Existe uniquement pour que les tests de migration puissent fabriquer une note héritée
> authentique […] Sans ça, la migration v1 → v2 ne serait vérifiable que sur des données simulées,
> c'est-à-dire pas vérifiée du tout : c'est précisément le chemin où une erreur ferait perdre les
> titres des utilisateurs. »*

Mesuré : **zéro appelant**, tests compris. Et le seul test Dart qui mentionne la migration
(`audit_v1_1_7_test.dart`) **`grep` le code source** pour vérifier que l'appel `_migrateLegacyEncryptedNotes(`
y figure. Il mesure une chaîne de caractères, pas un comportement.

*Un outil écrit pour rendre un chemin vérifiable, et jamais employé, laisse ce chemin exactement aussi
peu vérifié que s'il n'existait pas — mais donne l'impression du contraire.* Côté portage, le même
chemin était câblé, documenté, et aussi peu exercé.

### 🔧 Ce qui le mesure maintenant

Deux cas instrumentés, sur une note v1 fabriquée avec **la vraie clé de session du coffre** — une clé
de laboratoire mesurerait la crypto, pas la migration. Le format du blob, lui, est déjà recoupé octet
pour octet contre le Dart (`PariteCoffreAvecFlutterTest.lesBlobsDeNoteSeRelisent`).

1. **La migration préserve le titre.** Témoin **avant** : `enc_v = 1` et le titre dans sa colonne —
   sans lui, trouver le titre dans le blob à la fin ne distinguerait pas « migré » de « n'a jamais
   été en v1 ». Après : `enc_v = 2`, colonne vidée, et le titre **et** le contenu relus intacts.
   ⚠️ Titre **accentué** : un `pack` qui compterait des caractères et non des octets passerait sur
   « abc ».
2. **Une note v1 illisible est laissée INTACTE.** C'est la propriété qui sépare une migration d'une
   perte de données : la tentation est d'écrire « ce qu'on a pu lire », et le titre encore présent
   dans la colonne claire partirait avec. ⚠️ Le témoin est la **note saine du même dossier**, migrée
   dans le même passage — sans elle, un test où rien ne bouge passerait aussi bien sur une requête
   cassée qui ne trouve aucune note.

**Contrôles positifs faits** : écrire malgré l'échec de déchiffrement fait tomber le cas de la note
abîmée ; garder le titre dans sa colonne fait tomber celui de la migration. Chacun le sien, et lui
seul. Restaurations vérifiées au SHA-256.

⚠️ Le câblage, lui, était **déjà juste** : `onSessionOpened` est appelé depuis un point unique
partagé par les deux chemins de déverrouillage, avec le raisonnement anti-jumeau écrit sur place
(§25). Ce n'est pas ce qui manquait.

## §101 — 🔴🔴 Mode panique : le code avait été corrigé quatre fois, la phrase que l'utilisateur lit jamais

Le 2026-08-19, la ligne `panic_service.dart` de `docs/05-PARITE.md` a rendu **quatre** défauts, tous
dans les deux écrans du mode panique, et tous du même genre : *l'écran ne disait pas ce que le
service faisait.*

| # | Ce que le code faisait | Ce que l'écran disait |
|---|---|---|
| 1 | `VOICE_MODEL_WIPE` efface `files/stt/` depuis le 2026-08-16 | Le dialogue de **consentement** n'en disait rien |
| 2 | Idem | Le bilan de fin n'en disait rien non plus |
| 3 | `clairPeutSubsister` couvre **trois** sources : export, dictée, presse-papiers | La phrase ne nommait que les archives d'export |
| 4 | Ce résidu se **mesure** ; il vaut vrai avec **zéro** étape en échec | « **0 étape(s)** de nettoyage ont échoué », juste avant d'avertir qu'il reste du clair |

Les deux premiers venaient d'une ligne masquée exprès, avec son motif écrit à côté : *« la dictée
arrive en phase 7 »*. C'était vrai le jour où ça a été écrit. Ça a cessé de l'être le 2026-08-16, et
le commentaire est resté. ⚠️ Il figurait même au registre des chaînes orphelines de `05-PARITE.md`
(« 2 — puces du mode panique nommant le modèle vocal, **omises exprès et commentées** ») : le
registre disait donc la vérité de la veille, et servait de preuve qu'il n'y avait rien à voir.

> **Une justification datée ne se périme pas toute seule.** Elle reste lisible, plausible, et rend
> le défaut invisible à qui relit le fichier — d'autant mieux qu'elle est bien écrite.

Le troisième est un **inventaire incomplet**, et le KDoc du prédicat raconte lui-même qu'il s'est
trompé **deux fois par omission**, corrigé les deux fois. La phrase affichée, jamais. Conséquence
exacte : le cas « seul le presse-papiers a résisté » envoyait quelqu'un fouiller des fichiers qui
n'existent pas, pour en conclure qu'il est tiré d'affaire — pendant qu'une note reste lisible par
toute application au premier plan.

Le quatrième se contredisait **dans sa propre phrase**, au seul moment où elle doit être crue.

### ⚠️⚠️ Pourquoi une suite verte ne pouvait pas les voir

`PanicReportTest` compte quinze cas, tous justes, et **ne rend aucun écran**. Il prouve les
*branches* ; sur cet écran-ci, le produit est la *phrase* : quelqu'un décide, en la lisant, s'il peut
se séparer de son appareil. Aucun `androidTest` ne composait `PanicOverlay` ni le dialogue de
confirmation avant le 2026-08-19 — c'est exactement le périmètre où étaient les quatre.

Correctif : `PanicEcransTest`, **9 cas**, qui rendent les deux écrans. Il a trouvé le cinquième
défaut à son premier lancement (§102).

## §102 — 🔴🔴 Un repli de sûreté que l'affichage écrasait — et c'est le test neuf qui l'a trouvé

`PanicEcransTest.le_message_de_clair_n_annonce_AUCUN_compte_d_etapes` a échoué au **premier
lancement sur le S9**. Ce n'était pas le cas qui avait tort.

Le résumé des étapes et l'état du résidu étaient **deux branches d'un même `when`**, donc
exclusives, et `report.isComplete` passait avant `report.clairPeutSubsister`. Une séquence
entièrement réussie effaçait donc l'avertissement — celui-là même que les défauts 3 et 4 venaient de
corriger.

Et l'état « complet **et** du clair » n'a rien de théorique :

```kotlin
val clairRestant = try { … } catch (e: SecurityException) { true }   // PanicService
```

`clairSurLeDisque` se replie sur `true` quand la **mesure** échoue — *« on n'annonce pas une
protection qu'on n'a pas constatée »*, dit son commentaire. Ce repli se produit **sans une seule
étape en échec**. L'écran répondait alors par quatre puces rassurantes.

> ⚠️⚠️ **Un repli de sûreté n'en est pas un si l'affichage l'écrase.** Le service prenait la
> précaution, l'interface l'annulait, et rien entre les deux ne l'a signalé.

⚠️ `PanicReportTest.clairRestantEstSignale` construit **exactement** cet état, affirme `isComplete`
*et* `clairPeutSubsister`, et est vert depuis le premier jour. Son intitulé dit même « même sans
étape ratée ». Le modèle était connu ; l'écran, jamais interrogé.

### Le correctif, et pourquoi il n'est pas un réordonnancement

Remonter la branche dans le `when` aurait fait disparaître les quatre puces — le bilan mentirait
dans l'autre sens, en taisant treize effacements qui ont bien eu lieu. Les deux informations sont
**distinctes** : ce que la séquence a accompli, et ce qu'elle a laissé de lisible. Le résumé reste
donc dans le `when`, l'avertissement devient un **bloc indépendant**, et le `when` perd son `else` —
quand du clair subsiste, `panic_incomplete` (« des fichiers **illisibles** ») ne doit pas précéder la
phrase qui dit l'inverse.

⚠️ Le cas `une_sequence_complete_qui_laisse_du_clair_dit_les_DEUX` exige les **deux** à la fois,
précisément pour qu'un « correctif » par réordonnancement échoue lui aussi.

## §103 — 🔴🔴 « Du clair », défini deux fois dans le même fichier, et les deux définitions divergeaient

Relevé le 2026-08-19 par les **deux** relectures externes, chacune par un chemin différent : l'une en
partant de ce qui fait échouer la purge, l'autre en comparant les deux inventaires.

La mesure finale ne regardait que deux répertoires — `exports/` et `captures/`. Or
`estUnArtefactSensible`, vingt lignes plus bas, déclare que **toute** archive, tout `.md` et tout
`.wav` du cache portent du clair, et c'est sur cette base que `CACHE_PURGE` **échoue**.

Conséquence exacte : un `.md` resté à la racine du cache faisait échouer la purge sans qu'aucun des
deux répertoires n'existe. `clairPeutSubsister` valait alors `false`, et l'écran annonçait « des
fichiers **illisibles** peuvent subsister » — devant une note parfaitement lisible.

> *La duplication n'était pas du code recopié : c'était une **notion** définie deux fois.* Un
> `code-duplication-hunter` ne la voit pas ; seule la question « qui d'autre, dans ce fichier, décide
> de ce mot ? » la trouve.

⚠️ La mesure est désormais **récursive** là où l'effacement ne l'est pas : `viderLeCache` n'examine
que le premier niveau, donc un `note.md` rangé dans un sous-répertoire au nom anodin survit à une
purge qui se déclare réussie. Le dernier regard porté sur le disque n'a aucune raison d'être le plus
myope des deux.

## §104 — ✅ Ce que la panique fait vraiment, mesuré deux fois sur le S9

La vérification précédente datait du 2026-08-14 et **précédait trois des treize étapes**. Rejouée
entièrement le 2026-08-19, à travers l'interface réelle (`Réglages → Mode panique → EFFACER → Tout
effacer`), sur un état réel.

**Passage 1 — nominal.** Treize étapes, **zéro échec**, `garantie minimale : true`.

| Avant | Après |
|---|---|
| `app_flutter/notes_tech.db` 81 920 o (+ `-shm`, `-wal`) | **absents** |
| `files/stt/whisper-base-q5_1.bin` 59 707 625 o | **répertoire supprimé** |
| `cache/exports/note-secrete.md`, `cache/captures/dictee-….wav` | **partis** |
| `cache/` — 4 répertoires de fixtures | **vide** |
| `shared_prefs/notes_tech.kek.xml`, `FlutterSecureStorage.xml`, `FlutterSecureKeyStorage.xml` | **partis** |
| `FlutterSharedPreferences.xml` 130 o | **65 o** — liste blanche respectée |

L'écran de fin affiche les **quatre** puces, « Modèle de dictée vocale : désinstallé » comprise, et
le dialogue de consentement affichait bien ses **trois** items. Défauts 1 et 2 mesurés corrigés
ailleurs que dans un test.

**Passage 2 — résidu de clair forcé.** `cache/exports/` rendu non supprimable (`chmod 500`) avec un
`note.md` dedans. `EXPORTS_WIPE` **et** `CACHE_PURGE` échouent, comme prévu, et l'écran affiche
exactement une phrase :

> « Clé détruite : la base n'est plus déchiffrable. En revanche, du contenu LISIBLE peut subsister
> sur cet appareil — archive d'export, enregistrement de dictée, ou note copiée dans le
> presse-papiers. Ne vous en séparez pas sans vérifier. »

Les quatre puces sont absentes, `panic_incomplete` aussi — le `when` sans `else` fait ce qu'on
attend de lui. Le résidu est bien resté sur le disque : la phrase ne ment pas.

⚠️ Position mesurée : `[72,798][1008,1230]` sur un écran de 2220 — **entièrement lisible sans
défiler**. La question s'était posée parce qu'un nœud Compose « existe mais n'est pas affiché » se
confond avec un nœud rogné en bas d'écran ; ici, ni l'un ni l'autre.

⚠️ Après relance : « Aucune note ». Le pied de page — *« Notes Tech repartira sur une base
vierge »* — est donc mesuré lui aussi, et pas seulement promis.

⚠️⚠️ **Le modèle de 57 Mo est détruit par une panique, et c'est voulu.** Il a été copié hors de
l'appareil avant le premier passage (`adb exec-out run-as … cat`) et restauré après, SHA-256 vérifié
identique des deux côtés. *Une vérification qui détruit une donnée de l'utilisateur doit emporter sa
restauration avec elle, sinon elle n'est faisable qu'une fois.*

## §105 — ⚠️⚠️ Deux sessions Claude sur le même dépôt et le même téléphone : les mesures se détruisent en silence

Le 2026-08-19, deux suites instrumentées complètes se sont arrêtées à mi-parcours — 209 tests sur
306, puis 27 — en rendant `INSTRUMENTATION_RESULT: shortMsg=Process crashed.` **Aucun test n'avait
échoué** : le processus était tué.

La cause n'était pas dans le code. Un second processus `claude.exe`, survivant d'une session
précédente, exécutait **la même suite sur le même appareil** et avait même réinstallé l'APK en plein
milieu (`lastUpdateTime` à la seconde près du décrochage). Deux `am instrument` sur le même paquet
s'entretuent, et deux Gradle écrivent le même `build/`.

Ce qui rend le piège coûteux, c'est qu'aucun symptôme ne pointe vers la vraie cause :

| Ce qu'on voit | Ce qu'on en conclut à tort |
|---|---|
| « Process crashed » à un test différent à chaque fois | Un test instable, ou une fuite mémoire |
| Un fichier source modifié qu'on n'a pas écrit | Une erreur de sa propre part |
| Un APK reconstruit sans qu'on l'ait demandé | Gradle qui invalide son cache |

**Le diagnostic tient en une commande** — lister les processus et lire leur ligne de commande :

```powershell
Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'am instrument' }
```

⚠️ Contrôle à faire **avant** toute campagne de mesure sur appareil : un seul `claude.exe` doit avoir
des processus enfants. Les autres sont des onglets au repos et ne gênent pas.

⚠️⚠️ Avant de tuer quoi que ce soit, **sauvegarder l'arbre de travail** : le travail de l'autre
session est réel et non commité. Ici, sa refonte de `clairSurLeDisque()` (§103) était bonne, et la
perdre aurait coûté plus cher que les mesures détruites.

## §106 — 🔴🔴 La relecture du correctif a trouvé un défaut que le correctif venait de CRÉER

Le diff de §102 a été soumis à une relecture externe (GPT-5.2, 2026-08-19) avec une consigne
précise : *énumérer les combinaisons atteignables de `(protege, isComplete, clairPeutSubsister,
failedSteps)` et dire laquelle produit un écran muet, contradictoire ou faussement rassurant.* Elle
a rendu trois constats réels sur cinq axes.

### 1. 🔴🔴 « Clé détruite » sur un écran qui annonce que la clé a SURVÉCU

Sortir l'avertissement du `when` lui a fait perdre l'exclusivité qui le protégeait du cas
`!protege`. Or `panic_incomplete_plaintext` **commence par « Clé détruite »**. Avec
`KEK_DESTROY` en échec et un résidu de clair — deux conditions parfaitement compatibles — l'écran
affichait en même temps :

> « Vos notes restent déchiffrables sur cet appareil. »
> « **Clé détruite** : la base n'est plus déchiffrable. »

Pas bruyant : **faux**, sur le seul écran où quelqu'un décide de se séparer d'un appareil sous
contrainte. Correctif : `if (protege && report.clairPeutSubsister)`. Rien n'est perdu —
`panic_key_survived` est strictement plus grave et dit déjà de ne pas s'en séparer.

> ⚠️⚠️ **Le correctif est du code neuf.** Il ne bénéficie d'aucune des relectures qui ont validé
> ce qu'il remplace, et il hérite d'un angle mort : on relit ce qu'on répare, pas ce qu'on casse
> en réparant. Huit cas instrumentés écrits le matin même ne l'ont pas vu.

### 2. 🔴 « Toutes les données ont été effacées », juste au-dessus de « du lisible peut subsister »

`panic_complete_body` est une phrase **absolue**. La laisser coexister avec l'avertissement, en
comptant sur la hiérarchie visuelle — le rouge, plus bas — pour départager, c'est parier que celle
du haut ne sera pas la seule lue. Elle est désormais conditionnée à `!clairPeutSubsister`.

⚠️ **Les quatre puces, elles, restent affichées** : ce sont des affirmations précises, chacune
adossée à une étape qui a réussi. C'est l'énoncé général qui était faux, pas le détail.

### 3. 🔴 `walkTopDown` ignore en silence ce qu'il ne peut pas lister

La mesure récursive de §103 rendait `false` sur un cache partiellement illisible : par défaut,
`FileTreeWalk` **passe** un répertoire dont le listage échoue, et l'échec n'est pas une
`SecurityException`. Faux négatif exactement là où le repli existe. Corrigé par
`.onFail { _, e -> throw e }` et un `catch (e: Exception)` — *ne pas pouvoir regarder n'est pas une
réponse*.

### Ce qui reste ouvert, et pourquoi il n'est pas refermé à la hâte

⚠️ **L'avertissement de clair n'est pas une `liveRegion`.** Seul le titre l'est, en `Assertive`, et
dans l'état « complet + clair » ce titre dit « Effacement terminé ». Un lecteur d'écran entend donc
la phrase rassurante et pas l'avertissement. Poser une seconde région assertive sans la **mesurer**
sous TalkBack reviendrait à deviner — deux régions assertives simultanées s'interrompent. À faire
avec un appareil et le lecteur d'écran allumé, pas au jugé.

⚠️ `assertDoesNotExist` sur `panic_incomplete` formaté avec `0` est contournable : réintroduire le
compteur en **deux nœuds** le laisserait passer. Le garde-fou qui tient, lui, est l'assertion sur la
**ressource** — elle ne contient aucun `%`, dans les deux langues, et ne dépend d'aucun rendu.

## §107 — ⚠️⚠️ 22 cas d'export, tous justes, et pas un seul sur la couche qui écrit

Ligne `note_export_service.dart` de `docs/05-PARITE.md`, critère écrit : *« export `.md` d'une note
de coffre — le corps ne doit pas être vide »*. C'est un **vrai défaut de l'application publiée**,
corrigé chez elle sous le nom C1 (`note_editor_screen.dart:583`) : une note de coffre a `content`
vide en base, et exporter la ligne brute produisait un `.md` au frontmatter impeccable et au corps
vide. Perte de données silencieuse, puisque l'export « réussissait ».

### Le partage qui laissait ce défaut hors de portée

| Couche | Lignes | Ce qu'elle fait | Tests |
|---|---|---|---|
| `NoteMarkdown` + `NoteArchive` | ~23 Ko | Fonctions **pures** : nom de fichier, frontmatter, entrée d'archive | **30** cas JVM |
| `NoteExporter` | 400 | **Déchiffre**, décide de l'origine coffre, écrit le fichier, le retire quand ça rate | **0** |

Et le partage n'est pas fortuit : rendre un Markdown correct à partir d'une note vide **est** le
comportement attendu de `NoteMarkdown`. Aucun de ses 22 cas ne pouvait voir le corps vide, parce que
le défaut ne vit pas là — il vit là où quelqu'un décide s'il faut déchiffrer **avant** d'appeler.

> C'est le motif de §100 et de §101, une fois de plus : *la couche testée n'était pas celle qui
> pouvait avoir tort.*

### Ce que la mesure a rendu

`NoteExporteurTest`, **6 cas instrumentés** contre du vrai SQLCipher et un vrai `FileProvider` : le
clair d'une note de coffre ressort bien dans le fichier, le suffixe ` [unlocked]` et la mention YAML
y sont, une note **en clair dans un dossier coffre** les porte aussi, un coffre refermé fait échouer
l'export **sans laisser un octet** sur le disque, et l'archive omet une note scellée en la comptant
— vérifié sur les **octets du ZIP**, pas sur le compte.

**Le portage n'a pas le défaut du publié.** C'est un résultat, pas une absence de résultat : il
n'était établi par rien avant ces six cas.

### 🔴🔴 En revanche, deux de ces cas étaient faux — et l'un passait au VERT

L'horloge est injectée, donc **figée** dans les tests. Or le nom d'une archive est
`notes-tech-export-<millis>.zip` : deux exports d'un même cas portent le **même nom de fichier**,
dans deux sous-répertoires différents — `preparerRepertoire` sépare les répertoires, pas les noms.

L'aide qui retrouvait le fichier faisait `walkTopDown().first { it.name == nom }`. Elle rendait donc
l'archive de **référence** — celle prise avant de créer quoi que ce soit. Conséquence selon la forme
des assertions :

| Cas | Assertions | Ce qui s'est passé |
|---|---|---|
| « une note scellée est omise » | `doesNotContain` × 2 | **VERT** sur le mauvais fichier — l'archive de référence ne contenait évidemment pas la note |
| « un coffre ouvert entre en clair » | `contains` | rouge, et c'est lui qui a révélé les deux |

> ⚠️⚠️ **Un cas dont toutes les assertions sont des absences ne distingue pas « c'est absent » de
> « je regarde ailleurs ».** Il faut au moins une assertion de présence sur le même objet, sinon le
> cas est vert quel que soit l'objet qu'on lui donne — y compris un fichier vide.

Correctif en deux temps : `single` au lieu de `first`, pour qu'une **ambiguïté fasse échouer le cas
au lieu d'être tranchée en silence** ; et purge de la racine juste après la mesure de référence.

⚠️ Deux comptes écrits en dur étaient faux eux aussi, mais bruyamment : la base d'essai est semée de
deux notes, dont une scellée. Les cas mesurent désormais un **écart** contre une référence prise sur
place — un compte figé redeviendrait faux le jour où la semence change.

### Ce qui reste hors de portée, et pourquoi on ne l'a pas simulé

Le repli de `dossierEstUnCoffre` — *« une base indisponible ne doit pas faire échouer un export »* —
n'est **pas** exercé. `DatabaseProvider.close()` remet l'instance à zéro et la base se rouvre au
premier accès : un cas écrit ainsi n'atteindrait jamais le `catch` et serait un test vacant de plus.
Le forcer demanderait de sceller la base comme le fait le mode panique. C'est écrit dans le KDoc de
la classe. *Le dire vaut mieux que de laisser croire que la ligne est couverte.*

## §108 — ⚠️⚠️ Sept cas prouvaient que le Keystore se relit LUI-MÊME, aucun qu'il relit l'autre

Ligne `keystore_bridge.dart` de `docs/05-PARITE.md`, critère écrit : *« repris depuis
`KeystoreBridge.kt`, sans MethodChannel »*.

`AndroidVaultKeystoreTest` compte sept cas, tous justes : il scelle, il descelle, deux scellés du
même clair diffèrent, un scellé abîmé est signalé comme malformé, une clé absente ne conclut rien,
supprimer est idempotent, la clé est retenue par le matériel sécurisé. **Aucun ne dit quoi que ce
soit du seul risque qui compte à la bascule 3.0.0** : un coffre à code créé par la 2.0.x doit
s'ouvrir sous la 3.0.0, sur le même téléphone.

### Pourquoi ce trou-là est particulièrement méchant

Une divergence de paramètre ne casse rien à l'exécution. Elle produit **une autre clé**.
`createKey` la crée, le scellement marche, le descellement marche, tous les tests passent — et le
coffre de l'utilisateur ne s'ouvre plus jamais. Il n'y a pas de message d'erreur pour ça.

⚠️ L'alias est le plus silencieux des paramètres : s'il diverge, le portage ne **trouve** simplement
pas la clé, en crée une neuve, et l'ancienne reste dans le Keystore à côté d'un coffre devenu
inouvrable.

### Le sens de lecture, qui est tout le sujet

`PariteKeystoreAvecFlutterTest`, **6 cas**, ne recopie rien du portage : chaque valeur est
transcrite de `KeystoreBridge.kt` — spécification aux lignes 216-238, `wrap` à 290-299, `unwrap` à
301-307, alias documenté ligne 24 — puis **comparée** à ce que le portage produit.

| Cas | Ce qu'il mesure |
|---|---|
| Un scellé produit **comme le pont publié** s'ouvre par le portage | le sens qui décide si un coffre existant survit |
| Un scellé du portage s'ouvre **comme le pont publié l'ouvre** | une bascule n'est pas toujours définitive |
| Un scellé fait sous une **autre clé** est refusé | le contrôle négatif |
| L'alias vaut exactement `vault_pin_<folder_id>` | le paramètre muet |
| Les deux clés ont la **même spécification** | lue par `KeyInfo`, donc **par le Keystore**, pas par une constante |
| Le nonce fait 12 octets **des deux côtés** | il vient du Keystore, jamais du code |

> ⚠️⚠️ **Un test qui lirait `VaultParams` des deux côtés serait circulaire** : il passerait après un
> changement de paramètre, qui est exactement le défaut qu'on cherche. C'est pourquoi la comparaison
> de spécification passe par `KeyInfo` — on demande au **matériel** ce qu'il a fabriqué.

### Contrôle positif, et il a servi

Six cas verts ne disent rien tant qu'on n'a pas vu l'instrument tomber. Deux paramètres du **côté
publié** ont été faussés dans le test — `setKeySize(128)` et `GCMParameterSpec(96, …)` — puis la
suite relancée sur le S9 :

| Attendu | Mesuré |
|---|---|
| la comparaison de spécification tombe | ✅ |
| le descellement « comme le pont publié » tombe | ✅ |
| les quatre autres restent verts | ✅ |

Exactement les deux visés, et eux seuls. Paramètres restaurés, suite entière verte.

### ⚠️ Ce que ces cas ne prouvent pas, et qui reste vrai

Qu'un coffre du téléphone de quelqu'un s'ouvre. La clé Keystore est liée à l'**UID**, et la build de
portage porte un `applicationId` suffixé `.next` : elle ne PEUT pas voir les clés de l'application
publiée (`docs/06-ISOLATION-PENDANT-LE-CHANTIER.md`). Ce qui est mesuré ici est la seule chose
mesurable avant la bascule — que les deux codes, sur le même Keystore, produisent et consomment le
même matériel.

## §109 — ✅ La promesse « paramètres de coffre identiques » : elle tenait, et personne n'avait vérifié l'instrument

Dernière case non cochée du tableau des **promesses publiques** de `docs/05-PARITE.md` :
*« Coffres par dossier — Argon2id + AES-256-GCM, paramètres identiques »*.

Elle était en réalité **déjà mesurée**, et bien : `PariteCoffreAvecFlutterTest` compte **9 cas** qui
rejouent des vecteurs produits en **exécutant** le Dart de la 2.0.3, puis recoupés contre
`argon2-cffi` — le C de référence de la RFC 9106 — et `cryptography` Python, c'est-à-dire OpenSSL.
37 concordances, zéro divergence. Le recoupement écarte le scénario où un défaut du paquet Dart
serait reproduit à l'identique côté Kotlin et passerait pour une réussite.

Ce qui manquait n'était pas la mesure : c'était **la vérification de l'instrument**.

### Le contrôle positif, et ce qu'il a appris

`VaultParams.PASSPHRASE_ITERATIONS` passé de 3 à 4, suite JVM relancée :

| Cas | Tombe ? |
|---|---|
| « les raccourcis passphrase et PIN portent bien les paramètres de leur mode » | ✅ |
| « un coffre passphrase entier s'ouvre à partir de la seule passphrase » | ✅ |
| **« Argon2id rend exactement les clés du Dart »** | ❌ **non** |

⚠️⚠️ **Et c'est correct, mais il faut le savoir.** Le cas qui porte le nom le plus rassurant ne lit
pas `VaultParams` : il passe les paramètres **en clair**, ce qui est le bon choix — un vecteur doit
être figé indépendamment des constantes qu'il sert à vérifier. La protection tient donc en **deux
maillons** :

1. les vecteurs ↔ des paramètres explicites (`argon2idConcordeAvecLeDart`) ;
2. ces paramètres ↔ `VaultParams` (`lesRaccourcisPortentLesBonsParametres`).

> Retirer le second laisserait le premier vert pendant que l'application dériverait ses clés avec
> d'autres paramètres. *Le test au nom le plus rassurant n'est pas celui qui protège la constante.*

⚠️ La limite reste celle que le KDoc du fichier énonce déjà : ces cas figent un **format**, ils ne
prouvent pas qu'un utilisateur réel rouvre son coffre. Le critère de sortie de la phase 4 —
ouvrir un coffre réellement créé par la version Flutter — est inchangé.

## §110 — ⚠️⚠️ Trois conventions de `shared_preferences`, et il suffit d'en manquer une pour tout perdre

Ligne `settings_service.dart`, critère écrit : *« lire les clés `flutter.*` existantes »*.

Le greffon n'écrit pas où l'on croit :

| Convention | Valeur |
|---|---|
| Fichier | `FlutterSharedPreferences.xml`, jamais celui du paquet |
| Préfixe | **`flutter.`** devant chaque clé |
| Type d'un `int` Dart | **`Long`**, pas `Integer` |

En manquer une suffit pour que l'application neuve ne trouve **rien**. Et elle ne s'en plaint pas :
elle applique ses valeurs par défaut. Après mise à jour, quelqu'un retrouverait sa langue et son
thème remis à zéro — et surtout **le délai de verrouillage automatique des coffres**, réglage de
sécurité : mis à une minute, il repasserait à quinze sans un mot.

### Le trou : deux classes traversées par tout le monde, testées par personne

`AppSettings` et `LegacyPreferences` apparaissaient dans cinq fichiers de test — `ReglagesTest`,
`FolderVaultServiceTest`, `PanicEcransTest`, `NoteExporteurTest`, `SecureWindowControllerTest` —
**comme collaborateurs**. Aucun ne vérifiait ce qu'ils lisent : ils les traversent, et c'est tout.

> Le même motif qu'au §107, sous une autre forme : *être utilisé partout n'est pas être vérifié*.

### `ReglagesHeritesTest`, 6 cas, et un contrôle négatif qui porte le fichier

Les préférences sont posées **à la main dans le fichier du greffon**, avec son préfixe et ses
types ; c'est `AppSettings` qui relit. Puis le sens inverse : ce que le portage écrit doit rester
lisible par une 2.0.x réinstallée — clé préfixée, entier en `Long`.

⚠️ Le cas des **six clés de tri** n'existait nulle part. Le Dart écrit `updatedDesc`, `titleAsc`… à
la main plutôt que par `mode.name`, parce que la release passe par `--obfuscate` ; le portage a le
même `when` exhaustif, et **rien ne vérifiait que les douze chaînes concordent**. Une seule qui
divergerait ne casserait rien : le tri retomberait sur le défaut, et l'utilisateur croirait l'avoir
mal réglé. Le cas porte aussi son témoin — la table doit couvrir **tous** les modes, donc un mode
ajouté sans clé décidée le fait tomber.

### ⚠️⚠️ Le contrôle positif, et ce qu'il apprend sur les contrôles négatifs

`FLUTTER_KEY_PREFIX` passé de `flutter.` à `flutter_`, suite relancée sur le S9 :

| Cas | Tombe ? |
|---|---|
| les cinq réglages du greffon sont relus | ✅ |
| les six clés de tri | ✅ |
| le délai en `Int` comme en `Long` | ✅ |
| ce que le portage écrit est au format du greffon | ✅ |
| les clés rendues sont nues | ✅ |
| **le contrôle négatif « sans préfixe, rien n'est vu »** | ❌ **non** |

Cinq sur six, et le sixième **devait** rester vert : un contrôle négatif ne détecte pas un préfixe
faux, il détecte qu'on lit *quelque chose* qu'on ne devrait pas lire.

> ⚠️⚠️ **Les deux sortes de contrôle ne se remplacent pas.** Le négatif seul laisserait passer un
> portage qui se trompe de préfixe **des deux côtés** — il écrirait et relirait ses propres clés,
> parfaitement cohérent avec lui-même, et aveugle à tout ce que l'utilisateur avait réglé.
