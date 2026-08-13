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
