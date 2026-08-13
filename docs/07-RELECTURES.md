# Relectures externes

> Ce que des relecteurs indépendants ont trouvé, ce qui a été retenu, et ce qui a été écarté.
>
> **Un rapport externe se vérifie avant d'être appliqué.** Deux recommandations d'audit ont déjà
> failli casser une chaîne de mise à jour dans ce portefeuille, et un correctif proposé de
> l'extérieur peut être moins bon que celui qu'on tenait. Ce fichier garde donc le **tri**, pas les
> rapports bruts — ceux-ci sont dans `audits/`.

---

## R-001 — Noyau sécurité et accès aux données · 2026-08-13

**Périmètre** : 11 fichiers — `SecretBytes`, `SqlCipherRawKey`, `LegacyDatabaseLocation`,
`NotesDatabase`, `NotesDatabaseFactory`, `UnmanagedSchema`, `FolderEntity`, `NoteEntity`,
`KekSource`, `KeystoreSealedKekSource`, `KekRepository`.

**Relecteurs** : Gemini 3.1 Pro et GPT-5.2, **en parallèle et sans se voir**. Directives
identiques : `audits/PROMPT-noyau-securite-donnees.md`.

**Score** : 5 constats recevables sur 6. Les deux relecteurs se recoupent peu — ce qui est le but.

### Retenus et corrigés

| # | Constat | Trouvé par | Correctif |
|---|---|---|---|
| 1 | **Génération concurrente de deux KEK différentes** à la première installation. Deux appels simultanés constatent chacun « pas de base, pas de clé », génèrent chacun une clé, et la dernière écriture écrase l'autre. Le perdant crée une base chiffrée par une clé persistée nulle part : **illisible au redémarrage suivant**. | GPT-5.2 | `KekRepository.acquire` devient `@Synchronized`. |
| 2 | **Course sur l'alias Keystore** dans `store()` : deux threads créent ou remplacent la clé sous le même alias, et les préférences finissent avec un scellé produit par une clé que le Keystore ne détient plus. | GPT-5.2 | `store` et `replaceKeyAndStore` deviennent `@Synchronized`. |
| 3 | **Clé Keystore morte après réinstallation** : l'alias peut survivre à une désinstallation ou à un effacement des données. S'il a été invalidé par le système, `store()` le réutilise, échoue, et échoue encore à chaque démarrage. Application **définitivement bloquée**, alors que l'utilisateur n'a aucune donnée à protéger. | Gemini | Nouvelle méthode `replaceKeyAndStore`, appelée **uniquement** sur le chemin « aucune base sur le disque ». |
| 4 | **KEK non effacée** si une exception non typée (`ProviderException`) sort de `store()` : le `catch (e: KekFailure)` ne s'applique pas. | GPT-5.2 | `catch (t: Throwable)` → effacement → relance. |
| 5 | **Lecture non atomique** du couple (scellé, nonce) : deux `getString` successifs peuvent encadrer une écriture et rendre un couple incohérent, donc un refus d'ouverture alors qu'une clé valide existe. | GPT-5.2 | Lecture unique via `prefs.all`, instantané pris sous le verrou interne. |

**Note sur le constat 1** : au moment de la relecture, l'unique appelant (`DatabaseProvider`)
sérialisait déjà les appels par un verrou — le défaut n'était donc pas atteignable. Il a quand même
été corrigé, parce qu'une classe dont la sûreté dépend d'une propriété d'une **autre** classe se
remet en défaut au premier appelant suivant, sans que rien ne le signale.

### Trouvé en vérifiant, par personne d'autre

**Un échec de source interrompait le parcours des sources suivantes.** `scanSources` s'arrêtait à
la première `SourceUnavailable`. Sans conséquence aujourd'hui — il n'y a qu'une source — mais faux
dès l'ajout de la couche ② : une couche ① définitivement cassée aurait empêché d'atteindre la
couche ② qui, elle, détient la clé. L'utilisateur se serait vu refuser l'ouverture alors que ses
notes étaient récupérables.

Le parcours continue désormais, en conservant le premier échec pour le relancer si aucune clé n'est
trouvée — poursuivre ne peut rien détruire, mais un échec ne doit pas **disparaître**, sans quoi
une source en panne se lirait comme une absence, et l'absence conduit à générer.

### Écarté

**« `Arrays.fill(bytes, 0)` ne compile pas »** (Gemini, présenté comme CONFIRMÉ). La justification
est douteuse : Kotlin type un littéral entier en `Byte` quand le type attendu l'est. Le code a
malgré tout été changé pour `bytes.fill(0)` — non pas parce que le constat était juste, mais parce
qu'utiliser `java.util.Arrays` là où Kotlin a sa propre extension n'est pas idiomatique.

⚠️ **La claim de non-compilation n'a jamais été vérifiée** : le changement a précédé la première
compilation. Elle reste sans statut.

### Les deux relecteurs se sont trompés de la même façon

Tous deux ont classé **PROBABLE** la question des valeurs par défaut, faute d'avoir le DDL réel :

> « Je ne peux pas confirmer sans voir le SQL réel ou un `PRAGMA table_info` d'une base
> utilisateur. » (GPT-5.2)

C'était une vraie limite — le prompt citait le schéma en prose sans joindre le DDL. **La leçon vaut
pour la prochaine fois : joindre les sources dont dépend la question, pas seulement le code jugé.**

La question a été tranchée autrement, et mieux : voir ci-dessous.

---

## R-002 — Couche DAO · 2026-08-13

**Périmètre** : `FolderDao`, `NoteDao`, `NoteSearchDao`, `UnmanagedSchema`, `DatabaseProvider`.
Ces fichiers avaient été écrits **après** le lancement de R-001 : personne ne les avait relus.

**Relecteur** : GPT-5.2. Directives : `audits/PROMPT-dao.md`.

### Retenu et corrigé

**La recherche dépendait entièrement des triggers de l'utilisateur.** La requête ne filtrait pas
`encrypted_content` : elle s'en remettait aux triggers pour que l'index ne contienne rien d'une note
verrouillée. Or ces triggers ont été écrits par une **autre application**, ils vivent dans la base
de l'utilisateur, et rien ici ne les recrée ni ne les vérifie à l'ouverture. Une base dont les
triggers seraient absents, anciens ou abîmés aurait fait ressortir le titre en clair d'une note de
coffre, puisque le `JOIN` rend `n.*`.

GPT le classait PROBABLE faute de pouvoir garantir l'état de toutes les bases en production. Le
raisonnement de probabilité n'a pas d'importance ici : **faire dépendre une promesse de
non-divulgation de données écrites par un autre programme n'est pas acceptable**, quelle que soit
la probabilité. `AND n.encrypted_content IS NULL` ajouté à la recherche et aux rétroliens — coût
nul, le `JOIN` touche déjà `notes`.

Le test instrumenté a été **inversé** en conséquence : il produit délibérément la fuite dans
l'index, vérifie que la fuite a bien eu lieu, puis exige que la recherche ne rende rien.

### Écarté — et réfuté par la mesure

**« `notes_fts` référencé alors que la table est aliasée `f` »** (PROBABLE, soupçon d'erreur de
syntaxe SQL). C'est **faux**, et le test sur appareil le prouve : FTS5 expose une **colonne cachée**
portant le nom de la table. `notes_fts MATCH ?` et `bm25(notes_fts)` résolvent donc comme des
colonnes de `f`, pas comme des tables. Les 11 tests passent sur SQLCipher réel.

C'est exactement le cas de figure où un rapport externe se vérifie avant d'être appliqué : la
« correction » aurait consisté à réécrire une requête qui fonctionne.

### Corrigé, mais dans la documentation

**Un commentaire mentait.** `NoteDao.observeActiveInFolder` affirmait que
`idx_notes_folder_active` « couvre la requête ». Faux : `pinned` n'est pas dans l'index, donc
SQLite trie en mémoire le sous-ensemble retenu. Ça ne se corrige pas dans le code — ajouter un
index modifierait le schéma et ferait échouer la validation de Room sur toutes les bases
existantes. Le commentaire dit maintenant ce qui est vrai, et pourquoi c'est accepté.

### Confirmé sans réserve

| Point | Verdict |
|---|---|
| Aucun chemin vers `INSERT OR REPLACE` dans les DAO | ✅ |
| `bm25` trié ASC est le bon sens (score croissant vers le moins pertinent) | ✅ |
| Aucune saisie utilisateur ne produit d'erreur de syntaxe FTS5 | ✅ |
| La boîte de réception ne peut pas être supprimée (`id = :id AND id != 'inbox'`) | ✅ |
| L'incrément en base empêche les essais gratuits sur un coffre PIN | ✅ |
| `DatabaseProvider` n'ouvre qu'une instance | ✅ |

⚠️ Réserve utile de GPT sur le compteur de tentatives : l'incrément est sûr, mais la **relecture**
qui suit (seconde requête) peut rendre une valeur supérieure sous concurrence — « tentative 2/5 »
affiché après le premier échec. Pas permissif, mais surprenant. À traiter en phase 4, quand
l'interface du coffre existera.

---

## R-003 — Audit `data-room` interne · 2026-08-13

**Relecteur** : agent `android-code-quality-deep-dive`, axe `data-room`.

**Ce qui le distingue des deux autres, et ce qui a tout changé** : il avait accès à
`j:\applications\notes_tech` — **le vrai code Flutter**. Ni Gemini ni GPT ne l'avaient. Il a donc
pu trancher en CONFIRMÉ des points que la relecture GPT avait dû laisser en PROBABLE, et surtout
comparer le portage à ce que l'application publiée fait **réellement**.

Leçon à garder : sur un portage, le relecteur qui a la source d'origine sous les yeux voit une
classe de défauts que les autres ne peuvent pas voir. Les trois quarts des constats ci-dessous sont
des **écarts de parité**, invisibles à qui juge le code Kotlin dans l'absolu.

### 🔴 DR1 — La protection d'une note de coffre pouvait être effacée par un tap

Le portage n'exposait qu'une écriture générique `update(note)`, qui réécrit toute la ligne —
`content` **et** `encrypted_content`.

**Ce n'est pas théorique. L'incident a déjà eu lieu dans l'application publiée**, et le code Flutter
le documente (`notes_dao.dart:234-241`) :

> *« l'éditeur détient l'éphémère DÉCHIFFRÉE d'une note de coffre (`content` rempli,
> `encryptedContent == null`) : épingler une telle note réécrivait son contenu en clair et effaçait
> son blob chiffré — la note perdait sa protection définitivement, sans le moindre signal, sur un
> tap d'icône. »*

Flutter a répondu en ajoutant des écritures ciblées **et en gardant** l'écriture générique, sous un
avertissement. Le portage n'avait repris que le chemin dangereux — l'avertissement, lui, s'était
perdu en route.

**Correctif, plus fort que l'original** : l'écriture générique **n'existe pas**. `NoteDao` n'expose
que des écritures ciblées, et `updateEditableFields` porte une garde SQL
`AND encrypted_content IS NULL` qui la rend **inopérante** sur une note verrouillée. L'invariant est
tenu par la base, pas par la mémoire du prochain lecteur.

Deux tests instrumentés le prouvent : écrire en clair sur une note de coffre touche `0` ligne, et
l'épingler laisse son blob intact.

### 🟠 DR2 — La recherche ne filtrait pas les notes archivées

`notes_dao.dart:471` filtre `AND n.archived = 0`. Le portage ne le faisait pas : archiver une note
ne la retirait pas des résultats. Corrigé, plus le tri secondaire `n.updated_at DESC` qui manquait
aussi.

### 🟠 DR5 — L'expression FTS5 divergeait de l'originale

Le portage découpait sur **toute ponctuation** et préfixait **chaque** terme. Flutter découpe sur
les **espaces seuls** et ne préfixe que **le dernier**.

Conséquence : `l'été` devenait `"l"* "été"*` — deux termes au lieu d'un, dont `"l"*` qui rattrape
presque tout un corpus français. Pas une erreur visible : une recherche silencieusement fausse, et
seulement en français.

`FtsMatchExpression` est désormais une transposition à l'identique de `_buildFtsMatch`.

### 🟡 DR3 — « Récentes » n'était plus « récentes »

`observeRecent` triait `pinned DESC, updated_at DESC`. `listRecent` côté Flutter trie sur
`updated_at DESC` seul : une note épinglée ancienne restait bloquée en tête d'un écran qui annonce
les modifications récentes. Corrigé.

### 🟡 DR6 — Les écritures ne signalaient pas un identifiant inconnu

Flutter lève systématiquement `NoteNotFoundException` quand `rows == 0`. Les écritures du portage
rendent maintenant le **nombre de lignes touchées** ; c'est au repository de trancher. Choix
délibéré : un DAO qui lève sur identifiant inconnu rend malcommode le cas nominal d'une suppression
concurrente.

### Confirmé sain — et vérifié empiriquement, pas par lecture

- aucun chemin `REPLACE`/`rowid` (bytecode Room **décompilé** : `@Insert`/`@Update` = `ABORT`) ;
- le masquage des notes verrouillées tient même si l'appelant oublie de vider le contenu ;
- `UnmanagedSchema` est identique au caractère près au `database.dart` réel ;
- la garde anti-suppression de `inbox` n'a pas de contournement.

Deux hypothèses ont été **testées et réfutées** : `VACUUM` renumérotant les `rowid` (non reproduit)
et une cascade de clé étrangère ne déclenchant pas le trigger de nettoyage (réfutée).

---

## R-004 — Relecture **des correctifs** de R-003 · 2026-08-13

**Pourquoi cette passe existe** : sur ce portefeuille, les correctifs d'audit contiennent des
défauts plus souvent que le code d'origine — 12 défauts introduits en corrigeant 53 constats sur
une application voisine, 8 en deux passes sur une autre. Un correctif non relu est un pari.

**Relecteurs** : Gemini 3.1 Pro et GPT-5.2, en parallèle, sur le **delta seul**.
Directives : `audits/PROMPT-correctifs-parite.md`.

**Bilan : 4 constats recevables, 4 corrigés.** Les deux relecteurs ont convergé, indépendamment,
sur le même trou principal — ce qui est le meilleur signal qu'on puisse avoir.

### 🔴 Le correctif de R-003 avait laissé le trou ouvert

Supprimer l'écriture générique ne suffisait pas : `replaceContentPayload` restait le seul chemin
écrivant le blob, et il acceptait **deux combinaisons destructrices** :

| Appel fautif | Conséquence |
|---|---|
| contenu clair **+** blob conservé | texte en clair au repos, note « verrouillée » à l'écran |
| contenu clair **+** blob à `null` | protection détruite définitivement |

C'est exactement le défaut que R-003 prétendait rendre impossible, déplacé d'une méthode à l'autre.

**Correctif** : deux méthodes qui disent ce qu'elles font, et dont aucune ne peut faire le travail
de l'autre.

- `lockNote` — `content = ''` **écrit en dur**, `encryptedContent` **non-nullable**. Verrouiller ne
  peut ni écrire de clair, ni effacer la protection : il n'y a aucun paramètre pour ça.
- `unlockNote` — `encrypted_content = NULL` **écrit en dur**. C'est le seul chemin du code qui
  retire une protection, et il porte ce nom.

### 🟠 Une capacité retirée sans le vouloir

La garde `AND encrypted_content IS NULL` de `updateEditableFields` rendait impossible
**l'étiquetage d'une note de coffre** — capacité que l'application publiée offre. Les étiquettes
d'une note verrouillée sont stockées en clair (le trigger les masque à l'index, il ne les chiffre
pas). Ajout d'un `updateTags` sans garde : écrire des étiquettes ne touche ni au contenu ni au blob.

### 🟠 Divergence Unicode sur le découpage des termes

`\s` en Java ne couvre que l'ASCII ; le `RegExp(r'\s+')` de Dart suit ECMAScript et englobe les
espaces Unicode. L'**espace fine insécable U+202F** — ce que produisent les claviers français
devant `: ; ! ?`, et qui voyage par copier-coller — n'était donc pas un séparateur côté Kotlin.
`réunion budget` serait resté **un seul terme** là où Flutter en fait deux.

La classe de caractères est désormais écrite en toutes lettres, celle d'ECMAScript.

### 🟡 Mon test était vacant, et les deux relecteurs l'ont vu

`search("l'été")` attendait un résultat vide. Mais le jeu d'essai ne contient ni « l » ni « été » :
l'assertion passait **aussi bien avec l'algorithme fautif**. Elle ne prouvait rien de la correction.

Remplacé par un test qui **discrimine** : `search("bud reu")` doit rendre **zéro** résultat. Sous
l'ancien algorithme — préfixe sur chaque terme — `"bud"* "reu"*` appariait « budget » et
« réunion ». Sous le nouveau, `"bud"` est une phrase exacte qu'aucun mot du corpus ne porte. Avec
son contrôle positif (`search("reunion bud")` → 1 résultat), la règle « seul le dernier terme est
préfixé » devient observable.

### Écarté

**« `COALESCE(:x, x)` mal lié par Room »** (Gemini). GPT a tranché l'inverse, et il a raison : les
paramètres sont `Boolean?`, Room lie bien `NULL`. Les 20 tests sur appareil le confirment.

⚠️ Gemini avait néanmoins raison sur le **fond** du risque : `title = COALESCE(:title, title)`
laissait croire qu'un `null` viderait la colonne, alors qu'il la conserve — un titre en clair
survivant à côté d'un blob de format 2. Le `COALESCE` sur le titre a disparu avec la scission :
`lockNote` exige un `plainTitle` non-nullable, donc le choix est explicite à chaque appel.

### Noté, pas corrigé

GPT signale que découper une écriture en plusieurs méthodes retire l'atomicité que
`update(note)` offrait — « déplacer et archiver » est maintenant deux instructions. C'est au
repository de les envelopper dans une transaction (phase 3). Écrit ici pour ne pas l'oublier.

---

## Vérification mécanique du schéma — sans appareil

`audits/verifier-schema-room-vs-flutter.py`

Room valide la base à l'ouverture en lisant `PRAGMA table_info`, `index_list`, `index_xinfo` et
`foreign_key_list`. Le script crée **les deux schémas** dans deux bases en mémoire — celui du DDL
Flutter, celui exporté par KSP — et compare exactement ce que Room comparera.

```
Aucune divergence.
Les entités Room décrivent exactement le schéma créé par la version Flutter :
colonnes, types, nullabilité, valeurs par défaut, clés primaires, clés étrangères,
index et ordres de tri.
```

Ce que ça règle définitivement : les `defaultValue` (`''`, `0`, `1`), les actions de clés
étrangères, l'ordre `DESC` de `idx_notes_folder_active`, et l'exhaustivité des index — le piège où
un index présent en base mais non déclaré fait échouer la validation.

⚠️ **Ce que ça ne prouve pas**, et qui reste à mesurer sur appareil :

- que SQLCipher ouvre le fichier avec la clé au format `x'<hex>'` ;
- que les données réelles sont lisibles ;
- que l'index FTS5 existant répond ;
- que les couches ① et ② de l'acquisition de la KEK fonctionnent — la build isolée **ne peut pas**
  les exercer (cf. [06-ISOLATION-PENDANT-LE-CHANTIER.md](06-ISOLATION-PENDANT-LE-CHANTIER.md) §2).

Le script est à relancer à chaque modification d'entité. Il coûte une seconde.

---

## Points confirmés par les deux relecteurs

Utile à consigner : ce sont les hypothèses sur lesquelles repose tout le portage.

1. **Format de clé brute.** SQLCipher reconnaît `x'<64 hex>'` dans le matériel de clé — longueur
   67, préfixe `x'`, corps hexadécimal — et l'utilise sans dérivation PBKDF2, quelle que soit la
   voie d'arrivée. ✅ *(nuance de GPT-5.2 : « sans dérivation » vaut pour la passphrase ; SQLCipher
   dérive toujours ses sous-clés HMAC en interne, ce qui est le comportement attendu.)*
2. **`clearPassphrase = false` est obligatoire.** À `true`, la bibliothèque met le tableau à zéro
   après la première ouverture, et la connexion suivante du pool échoue. La copie remise à la
   fabrique **doit** survivre à l'appel. ✅
3. **L'existence du fichier suffit** à distinguer première installation et migration. Aucun état
   normal où « la base existe » et « générer une clé » seraient tous deux corrects. ✅
4. **`INSERT OR IGNORE` dans `onOpen` est sûr** sous WAL : atomique, idempotent, sans l'effet
   destructif de `REPLACE`. ✅

---

# R-005 — Phase 3 : domaine, repositories, normalisation

> 2026-08-13. Lot relu : 16 fichiers, 2 356 lignes — repositories, DAO, convertisseurs, modèles de
> domaine, sémantique de texte, contrat de scellement.

## Ce qui a trouvé quoi

Cette passe est instructive par sa **répartition** : chaque catégorie de vérification a trouvé une
classe de défauts que les autres ne pouvaient pas voir.

| Trouvé par | Constat | Gravité |
|---|---|---|
| **Exécution du vrai code Dart** | trois divergences de sémantique de chaînes entre Dart et Java | invisible autrement |
| **Un test qui affirmait un garde-fou** | l'anti-auto-lien est défait par la requête suivante | 🟠 |
| **Écriture de la couche domaine** | `findVaults` utilisait `vault_mode` au lieu de `vault_salt` | 🟠 |
| **Comparaison avec le Dart d'origine** | `backlinks` manquait les liens fantômes | 🟠 |
| **Relecture externe (Gemini 3.1 Pro)** | `lockNote` perdait étiquettes et date de modification | 🔴 |
| **Relecture externe (Gemini 3.1 Pro)** | une suppression refusée vidait quand même le dossier | 🔴 |
| **Relecture de mon propre correctif** | « le titre n'a pas changé » masquait un verrouillage | 🔴 |
| **detekt** | le DAO `notes` mêlait 16 lectures et 11 écritures dans un fichier | 🟡 |

## Les trois divergences Dart / Java

Mesurées, pas déduites, en exécutant `BacklinksService` de l'application publiée sur un corpus de
piégeage. Détail et procédure de regénération : `09-VECTEURS-DE-PARITE.md`.

Elles portent toutes sur `target_title_norm`, la clé par laquelle un lien retrouve sa note. **Aucune
n'aurait été trouvée par relecture** : rien dans la documentation des deux langages ne signale que
`String.lowercase()` de Java applique la règle du sigma final, ni que son `trim()` élague `U+001F`.

## Les deux constats externes, vérifiés avant d'être appliqués

**`lockNote` perdait les étiquettes et la date.** Confirmé par lecture : la requête n'avait de
paramètre ni pour l'un ni pour l'autre. La méthode servait deux appelants aux besoins opposés — une
édition, qui doit faire remonter la note ; une reprotection d'arrière-plan, qui ne doit pas
réordonner l'écran — et ne servait bien que le second. Corrigé par deux paramètres nullables
**sans valeur par défaut** : chaque appelant tranche.

**Une suppression refusée vidait quand même le dossier.** Confirmé : `deleteKeepingNotes` déplaçait
les notes, constatait que la suppression n'avait rien fait, et **rendait `null`**. Rendre une valeur
ne défait rien : Room validait la transaction. Appliqué à la boîte de réception, protégée dans sa
requête de suppression, le dossier survivait vidé de tout son contenu. Corrigé par une levée, qui
annule.

Le motif dépasse le cas : **dans une transaction, un refus qui se contente de rendre une valeur ne
défait rien.**

## Ce que la relecture externe a explicitement écarté

> « Aucun chemin permettant d'écrire le contenu d'un coffre en clair sur le disque n'a été trouvé. »

Et, en point de vigilance, une remarque juste sur `observeBacklinks` : la garde reposait sur une
**propriété distante** — aucune ligne de `note_links` ne porte de clé normalisée vide, parce que
l'extraction les écarte. C'était vrai. Ça cesserait de l'être si une telle ligne entrait par un
autre chemin. Rendu explicite : une note verrouillée court-circuite la requête.

## Ce qui reste ouvert

- Relecture GPT-5.2 : le service a rendu `503` deux fois. À relancer.
- La couche ② de la KEK et la release passerelle 2.0.4 restent à écrire.
