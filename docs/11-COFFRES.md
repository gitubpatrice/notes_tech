# Les coffres — phase 4

> Ce que le portage fait des coffres, ce qui est prouvé, ce qui ne l'est pas.
> Écrit le 2026-08-14, à la clôture de la phase 4.

---

## 1. Ce qui est en jeu

Un coffre est un dossier dont les notes sont chiffrées par une clé que l'utilisateur seul peut
faire réapparaître. Deux issues sont irréparables, et tout le reste s'y subordonne :

| Issue | Ce qui la produit |
|---|---|
| **Des notes définitivement illisibles** | un paramètre de dérivation qui dérive, une colonne écrasée, une clé de coffre effacée |
| **Des notes détruites alors que le code était bon** | un échec système compté comme une tentative ratée |

La seconde est la moins intuitive et la plus fréquente : elle ne vient pas d'une erreur de
cryptographie, mais d'une erreur de **classement des échecs**.

## 2. L'architecture, en trois couches

```
Coffre à phrase secrète — deux couches, aucune dépendance à l'appareil
  phrase ─Argon2id(t=3, m=64 Mo, p=1, 32 o)→ clé dérivée
  clé du coffre (32 o tirés au sort) ─AES-256-GCM(clé dérivée, nonce = vault_iv, AAD = folder_id)→ vault_kek_wrapped
  vault_verifier = HMAC-SHA-256(clé du coffre, "files-tech.notes_tech.vault.v1")

Coffre à code — trois couches, la dernière liée au matériel
  code ─Argon2id(t=2, m=32 Mo)→ clé du code
  clé du coffre ─AES-GCM(clé du code, AAD = folder_id)→ scellé interne
  scellé interne ─AES-GCM(clé AndroidKeyStore)→ vault_pin_blob + vault_pin_iv
  cinq codes faux ⇒ destruction du coffre

Note chiffrée — notes.encrypted_content
  blob  = nonce(12) ‖ chiffré ‖ étiquette(16),  AAD = note_id
  clair = uint32 gros-boutiste longueur du titre ‖ titre ‖ contenu     (enc_v = 2)
  clair = contenu seul, titre en clair dans sa colonne                 (enc_v = 1, hérité)
```

Le mode à code n'a pas plus de dix mille possibilités : **toute sa sécurité tient au fait que la
clé du Keystore ne sorte jamais du matériel**. Argon2id allégé n'y ralentit qu'un attaquant qui
aurait déjà contourné le compteur. En contrepartie, un coffre à code ne survit ni à une
réinstallation, ni à un changement d'appareil, ni à une réinitialisation de l'écran de
verrouillage — c'est le contrat, et c'est pourquoi le mode phrase secrète existe à côté.

## 3. Ce qui est prouvé, et par quoi

### Les vecteurs viennent du vrai Dart, et sont recoupés par une tierce implantation

Un harnais temporaire exécuté dans `j:\applications\notes_tech` appelle les vraies primitives du
paquet `cryptography` 2.9.0 avec les vrais paramètres, et écrit ses sorties. Ces sorties ont ensuite
été rejouées contre **une troisième implantation indépendante** :

| Famille | Vérifié contre | Résultat |
|---|---|---|
| Argon2id, deux jeux de paramètres | `argon2-cffi` — le C de référence de la RFC 9106 | **18 / 18** |
| AES-256-GCM, HMAC-SHA-256, enveloppes | `cryptography` Python — OpenSSL | **19 / 19** |

Ce recoupement n'est pas décoratif. Sans lui, un défaut du paquet Dart reproduit à l'identique côté
Kotlin passerait pour une réussite. Les trois implantations étant d'accord, c'est bien le standard
qui est implanté des deux côtés.

Les vecteurs sont dans `app/src/test/resources/parite/coffre_*.tsv`, tout champ textuel en
hexadécimal UTF-8 — aucune ressource ne porte de caractère non ASCII, donc aucun problème
d'encodage possible entre l'écriture, Git et le chemin de classe.

### Les mesures

| Contrôle | Résultat |
|---|---|
| Tests JVM | **70** (48 avant la phase 4), 0 échec |
| Tests instrumentés sur Galaxy S9, API 29 | **91** (67 avant), 0 échec |
| ktlint, detekt, lint | verts, **aucune ligne de base ajoutée** |
| Un coffre dont les colonnes viennent du Dart, ouvert depuis Kotlin sur du vrai SQLCipher | ✅ `la_cle_dun_coffre_ecrit_par_flutter_souvre_depuis_kotlin` |

## 4. 🔴 Ce qui n'est PAS prouvé

**Qu'un utilisateur réel rouvre son coffre.** Les vecteurs figent un **format**, pas une migration.
Les octets sont authentiques, leur provenance ne l'est qu'à moitié : ils sortent d'un harnais, pas
du téléphone de quelqu'un.

Pour le mode à code, l'écart est **structurel et infranchissable pendant le chantier** : la clé du
Keystore est liée à l'UID, et la build de portage porte un `applicationId` suffixé `.next`. Elle ne
*peut pas* voir les clés de l'application publiée. Aucun test ne fermera ce point avant la bascule
elle-même — cf. [06-ISOLATION-PENDANT-LE-CHANTIER.md](06-ISOLATION-PENDANT-LE-CHANTIER.md).

Le critère de sortie de la phase 4 — *ouvrir un coffre réellement créé par la version Flutter, sur
base réelle, dans les deux modes* — reste donc **partiellement ouvert**, et c'est écrit ici pour
qu'on ne le déclare pas atteint par habitude.

## 5. Le classement des échecs, qui est le cœur du sujet

Un coffre à code s'auto-détruit au cinquième échec. Tout dépend donc de ce qu'on appelle un échec.

| Ce qui est levé | Compte une tentative ? | Pourquoi |
|---|---|---|
| `WrongPinException` | **oui** | le code était faux — c'est exactement ce qu'on compte |
| `WrongSecretException` | oui (freinage seulement) | idem, côté phrase secrète, sans compteur persistant |
| `MalformedVaultDataException` | **non** | la donnée est abîmée, pas le secret faux |
| `KeystoreUnavailableException` | **non** | on n'a pas pu regarder ; rien n'est prouvé |
| `KeystorePermanentlyInvalidatedException` | sans objet | le coffre est légitimement irrécupérable, on efface |
| `VaultValidationException` | **non** | saisie refusée avant toute tentative |
| annulation de la coroutine | **non** | l'utilisateur est revenu en arrière, rien d'autre |

C'est la même forme que le contrat à trois états des sources de clé maîtresse
([03-KEK-ACQUISITION.md](03-KEK-ACQUISITION.md)) : absent, présent, ou *je n'ai pas pu savoir*. Le
troisième état ne se replie jamais sur le premier.

⚠️ **La version publiée a introduit ce défaut puis l'a corrigé** en v1.0.3 (F5) : elle traitait
toute exception du Keystore comme un échec légitime, et a dû passer d'une liste noire à une liste
blanche. Le portage part de la version corrigée, et l'étend à trois cas qu'elle ne couvrait pas.

## 6. Les trois écarts délibérés avec l'application publiée

Aucun ne touche un format. Tous vont dans le même sens : refuser plutôt que détruire.

| Écart | Publiée | Ici | Pourquoi |
|---|---|---|---|
| Vérificateur incohérent | compte un échec | `MalformedVaultDataException`, sans compter | GCM a déjà prouvé le secret ; l'échec du vérificateur ne peut décrire qu'une base abîmée |
| Mode du coffre | lu dans `vault_mode` | **déduit des colonnes** | un `vault_mode` perdu rendrait le coffre inouvrable par les deux chemins à la fois |
| Horloge d'auto-verrouillage | `Stopwatch`, s'arrête en veille | `elapsedRealtime`, compte la veille | ne peut que verrouiller **plus tôt** — le bon sens de l'erreur pour une garde |

## 7. Ce que la phase 4 ne livre pas

Volontairement hors périmètre, et à câbler avec l'interface :

- **Chiffrer les notes déjà présentes** lors de la conversion d'un dossier en coffre, et
  **déchiffrer** lors du retrait de la protection. La garde d'écriture protège toute écriture
  *nouvelle* dès que le dossier porte un sel ; le rattrapage du contenu antérieur est un geste
  d'interface, avec sa barre de progression et son bilan honnête.
- **La reprotection à l'ouverture de session** (`_reprotectPlaintextNotes`) et **la migration du
  format 1 vers le format 2** (`_migrateLegacyEncryptedNotes`).
- **Le réglage du délai d'auto-verrouillage**, à lire depuis les préférences héritées
  (`flutter.vault_auto_lock_minutes`, cf. [02-SCHEMA-HERITE.md](02-SCHEMA-HERITE.md) §5).

⚠️ Ces trois points sont des **gestes de sécurité**, pas des agréments. Ils sont listés ici pour
qu'ils entrent dans le périmètre d'une phase, et non dans les oublis d'une bascule.


## §8 — Les quatre gestes différés sont ÉCRITS (phase 5, 2026-08-14)

Le §7 les listait comme hors périmètre de la phase 4. Ils sont entrés avec l'interface qui les
appelle — un geste de sécurité qui n'appartient à aucune phase finit dans les oublis d'une bascule.

| Geste | Où |
|---|---|
| `encryptAllNotesInFolder` | conversion d'un dossier en coffre |
| `decryptAllNotesInFolder` | retrait de protection |
| `reprotectPlaintextNotes` | réparation à l'ouverture de session |
| `migrateLegacyEncryptedNotes` | format 1 → 2, le titre entre dans le chiffré |

🔴 **`removeVaultProtection` rescelle sur TOUTE sortie qui n'a pas effacé le coffre.** Il y en a
trois — l'échec rapporté, l'exception, l'annulation — et l'application publiée n'en couvrait que
deux, après deux relectures externes successives. Le troisième chemin n'y était pas couvert du tout.

⚠️ **La réparation est `NonCancellable`.** Rattraper une annulation avec du code annulable ne
rattrape rien : la première suspension relèverait aussitôt, et le clair resterait au repos dans un
dossier qui arbore toujours son cadenas.

⚠️ **Les réparations sont déclenchées depuis `openVerifiedSession`**, seul point commun aux deux
chemins de déverrouillage, et **pas** à la création d'un coffre — où les notes doivent être
chiffrées par un geste visible, avec son décompte et ses échecs. Les accrocher aux deux appelants
marcherait aujourd'hui et produirait le jumeau asymétrique au premier chemin ajouté.

## §9 — Le délai d'auto-verrouillage est enfin appliqué

`VaultSessions.autoLockMillis` était figé à 15 minutes et rien ne le mettait à jour. Le réglage
existait dans le fichier hérité, l'écran l'écrivait, et le verrouillage continuait d'appliquer sa
valeur par défaut.

⚠️ Le relire **une seule fois au démarrage** aurait fermé la moitié du trou : le réglage aurait pris
effet au redémarrage suivant, c'est-à-dire pas au moment où on le change — qui est précisément
celui où on en a besoin. `VaultAutoLocker` le collecte donc en continu.

## §10 — La course de l'annulation tardive : **constatée, pas empêchée** (2026-08-15)

### Ce qui peut arriver

`VaultViewModel.cancelAttempt` décide sur `PhaseDeCoffre`. La phase ne bascule en `CHIFFREMENT`
qu'au **retour** de `createXVault`, et entre l'écriture du matériel en base et ce retour il y a une
reprise de coroutine — Room rend la main sur son exécuteur, puis reprend sur le fil principal. Un
appui sur « Annuler » déjà présent dans la file d'événements est traité **avant** cette reprise :
la phase vaut encore `DERIVATION`, la garde laisse passer, le travail est coupé, **et le dossier est
déjà un coffre**.

L'état qui en résulte, vérifié ligne à ligne dans `FolderVaultService.createPassphraseVault` :

| | |
|---|---|
| Matériel du coffre | **écrit** (`provisionPassphraseVault` a commité) |
| Session | **jamais ouverte** — `sessions.open` suit le `check` et ne s'exécute pas |
| Clé de dossier | **effacée** par le `catch (Throwable)` |
| Notes | **toutes en clair au repos** |

Le chemin PIN est identique, avec en plus une clé Keystore créée.

### 🔴 Pourquoi le correctif structurel a été REFUSÉ

Fermer la course demanderait de rendre insécables l'écriture et l'ouverture de session — un
`withContext(NonCancellable)` autour des deux. Or le `catch (Throwable)` qui les entoure efface la
clé de dossier, et cette clé, `sessions.open` vient de la confier à la session. Sous
`NonCancellable`, la sortie du bloc lève la `CancellationException` du parent, ce `catch`
s'exécute, et **il efface la clé d'une session vivante**. Les notes chiffrées ensuite le seraient
sous une clé nulle.

C'est le défaut du tableau d'octets partagé entre deux propriétaires, déjà consigné dans
`04-PIEGES.md`. Il **détruit** des données ; la course, elle, en laisse en clair — et la réparation
existe (`reprotectPlaintextNotes` au prochain déverrouillage).

> ⚠️ **Le correctif d'une course ne doit pas coûter plus cher que la course.** Ici le « propre »
> échangeait une fuite réparable contre une perte irréversible.

### Ce qui a été fait à la place

`cancelAttempt` **constate** : il retient le dossier en cours de conversion, attend la fin de la
tentative annulée (`join`), relit la base (`FolderVaultService.isVault`, qui ne demande rien à la
session — c'est tout son intérêt), et publie le constat. `HomeRoute` l'annonce, parce que la feuille
vient de se fermer.

> **Ce qui rendait cette course grave n'est pas qu'elle existe, c'est qu'elle était MUETTE.** Un
> dossier portait un cadenas, tout son contenu était lisible au repos, et rien nulle part ne le
> disait — exactement le défaut que `onConversionIncomplete` avait déjà fermé une fois.

⚠️ **Ce qui n'est pas couvert par un test** : la course elle-même ne se provoque pas depuis un test.
Ce qui est testé, c'est le prédicat — `isVault` répond « oui » sur un dossier dont **aucune session
n'est ouverte**, là où `isUnlocked` répondrait « non » et laisserait croire à une annulation propre.

### Deux tours de relecture sur ce seul correctif, et ce qu'ils ont trouvé

**Premier tour** — deux défauts, un par relecteur, **disjoints** :

- *(Gemini)* le constat pouvait crier au loup : `cancelAttempt` rendait la main avant que la
  tentative annulée soit dénouée, donc une **seconde** conversion du même dossier pouvait écrire son
  matériel, et le constat de la première lisait `isVault == true` à cause d'elle. Un avertissement
  faux sur ce chemin-là apprend à ne plus le lire.
- *(GPT-5.2)* le constat pouvait être **jeté** : il partait dans un `SharedFlow` sans `replay`, donc
  perdu si l'écran d'accueil n'était pas composé à cet instant — c'est-à-dire précisément quand
  l'utilisateur a enchaîné sur autre chose. Retour au silence, par un autre chemin.

**Second tour, sur les correctifs du premier** — et c'est là que ça compte :

> 🔴 **Le correctif du premier défaut en portait un pire, et les DEUX relecteurs l'ont vu.** Le
> `tache.cancel()` était passé **sous** le tri « création ou déverrouillage ». Un déverrouillage
> sortait donc par le `return` **sans être annulé** : la dérivation continuait, la session s'ouvrait,
> et le coffre s'ouvrait une seconde après l'annulation. C'est mot pour mot le défaut que
> `cancelAttempt` existe pour empêcher, réintroduit par un correctif qui ne visait pas ce chemin.

GPT a ajouté deux points que Gemini n'a pas vus : `tenter` ne testait que `busy`, donc une tentative
fantôme pouvait rouvrir la fenêtre en le remettant à `false` — le garde est maintenant explicite ; et
`join()` sans borne pouvait figer **toutes** les feuilles de coffre pour la vie du ViewModel.

⚠️⚠️ **La borne d'attente va dans le sens INVERSE de celle du presse-papiers**, et c'est ce qui se
retient : là-bas, borner faisait *abandonner du clair exposé* — donc borne interdite. Ici, borner ne
coûte qu'un constat manqué, c'est-à-dire le silence d'avant, contre un blocage certain. **Une borne
n'est ni bonne ni mauvaise : elle se juge sur ce qu'elle laisse tomber.**
