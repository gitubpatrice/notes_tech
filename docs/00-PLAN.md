# Notes Tech — portage Flutter → Kotlin natif

> **État** : phases 1 à 5 **closes** (la 4 avec une réserve écrite, cf. plus bas). Phase 6 : trois
> lots sur quatre faits — reste l'interface des rétroliens. Dernière mise à jour : 2026-08-14.
>
> **🎯 Cible de release 3.0.0 : début septembre 2026** — fixée par Patrice le 2026-08-14.
>
> Ce fichier est le plan de référence. Il dit **où on en est** et **ce qui vient ensuite**.
> Les décisions déjà prises sont dans [01-DECISIONS.md](01-DECISIONS.md) — ne pas les rediscuter ici.

## 0. La contrainte de calendrier, et ce qu'elle change

> « prévoyons une release pour début septembre, ça laisse le temps de peaufiner l'application. »
> — Patrice, 2026-08-14

Trois semaines pour les phases 4 à 8. Le plan **ne change pas d'ordre** : la phase 4 reste le
verrou, et aucune interface ne se construit sur des coffres non prouvés.

Ce que la date change, c'est **où se situe l'arbitrage**. Les phases 4, 5, 6 et 8 sont du travail
borné : leur contenu est connu, mesuré, et il ne réserve pas de surprise de nature. La **phase 7
est la seule dont le coût n'est pas connu** — `whisper_ggml_plus` est un plugin FFI sans équivalent
Kotlin, et il faudra soit écrire un pont JNI vers whisper.cpp, soit s'en passer.

⚠️ **Le point à trancher, et le seul :** est-ce que la 3.0.0 sort **avec ou sans dictée vocale** ?
La question n'a pas à être tranchée maintenant — elle se pose à la fin de la phase 6, quand le
reste sera mesuré. Elle est notée ici pour ne pas être découverte le 1ᵉʳ septembre.

⚠️ **Ce qui ne bouge pas, quelle que soit la date** : le critère de sortie de la phase 4 (ouvrir un
coffre réellement créé par la version Flutter) et la règle du §4 — aucune phase ne démarre avant que
le critère de la précédente soit **observé**. Une date ne transforme pas une supposition en preuve.

⚠️ **La 2.0.4 Flutter n'est PAS concernée par cette date.** Elle attend la MR F-Droid !37885, sans
calendrier. Cf. [05-PARITE.md](05-PARITE.md).

---

## 1. Ce qu'on porte

Source : `j:\applications\notes_tech`, version publiée **2.0.3 (versionCode 51)**, commit `7180a2c`.

| Mesure | Valeur (relevée le 2026-08-13) |
|---|---|
| Code Dart `lib/` | 21 094 lignes, 63 fichiers |
| Tests Dart | 2 817 lignes, 13 fichiers |
| Écrans | 10 |
| Widgets | 16 |
| Clés i18n | **311** × {fr, en} |
| Tables | `folders`, `notes`, `note_links` + `notes_fts` (FTS5) + 3 triggers |
| Version de schéma | **9** |

Les cinq plus gros fichiers, qui concentrent l'essentiel de la difficulté :

| Fichier Dart | Lignes | Destination Kotlin |
|---|---|---|
| `services/security/folder_vault_service.dart` | 1 582 | `security/vault/` ✅ phase 4 |
| `ui/screens/note_editor_screen.dart` | 1 123 | `ui/editor/` (phase 5) |
| `data/db/database.dart` | 905 | `data/local/` (phase 2-3) |
| `ui/widgets/vault_pin_sheets.dart` | 857 | `ui/vault/` (phase 5) |
| `ui/screens/settings_screen.dart` | 802 | `ui/settings/` (phase 5) |

## 2. Le vrai risque n'est pas le volume

Traduire du Dart en Kotlin est mécanique, et Compose se porte assez directement depuis Flutter.
**Deux points seulement peuvent faire échouer le projet**, et ils sont indépendants du volume :

1. **Ouvrir la base des utilisateurs déjà installés.** Détail complet dans
   [03-KEK-ACQUISITION.md](03-KEK-ACQUISITION.md). Si ce point n'est pas résolu, tout le reste
   est du travail sur une application que personne ne peut installer sans perdre ses notes.
2. **Remplacer le moteur de dictée vocale.** `whisper_ggml_plus` est un plugin Flutter (FFI vers
   whisper.cpp). Il n'a **aucun équivalent Kotlin prêt à l'emploi**. Voir phase 7.

Le plan attaque donc le point 1 **avant** d'écrire une seule ligne d'interface.

## 3. Phases

Une phase n'est finie que quand son **critère de sortie** est atteint. Pas « le code est écrit » —
le critère est toujours une preuve observable.

### Phase 1 — Socle ✅ close le 2026-08-13

Squelette Gradle/Kotlin repris de `agenda_tech`, qui est le donneur le plus proche
(Kotlin natif, Room + SQLCipher, zéro réseau, mono-module).

- [x] Arborescence, wrapper Gradle, config detekt/ktlint
- [x] `libs.versions.toml`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`
- [x] Manifeste (sans `INTERNET`), `Application` Hilt, thème Material 3
- [x] CI reprise d'`agenda_tech` — **y compris le contrôle « zéro réseau »**, éprouvé par trois
      contrôles négatifs
- [x] `version.properties` (source unique de version)
- [x] Isolation `.next` (D-008) — le portage ne peut pas écraser l'application réelle

⚠️ **Écart assumé avec `agenda_tech`** : `minSdk 24` et non 26, `targetSdk 36` et non 35. Ces deux
valeurs sont **relevées** sur le manifeste fusionné de la 2.0.3 publiée, pas choisies. Monter le
plancher exclurait les utilisateurs Android 7 d'une mise à jour.

**Critère de sortie atteint** : `assembleDebug`, `testDebugUnitTest`, `lintDebug`, `detekt` et
`ktlintCheck` verts.

### Phase 2 — Ouvrir la base héritée ✅ close le 2026-08-13

- [x] Entités Room décalquées au caractère près
- [x] `SqlCipherRawKey` — format `x'<64 hex>'` (D-004)
- [x] Acquisition de la KEK, couches ① et ③ ; **couche ② conçue, pas écrite**
- [x] `notes_fts`, ses 3 triggers et `note_links` créés hors du graphe Room
- [x] Chemin de base `app_flutter/notes_tech.db` (D-003)
- [x] DAO dossiers, notes, recherche FTS5, liens

**Critère de sortie atteint** — mesuré, pas supposé :

| Vérification | Résultat |
|---|---|
| Tests instrumentés sur Galaxy S9 (API 29) | **20**, 0 échec |
| Tests JVM | **37**, 0 échec |
| Schéma Room vs DDL hérité (comparaison mécanique) | aucune divergence |

Deux de ces tests ont une valeur particulière parce qu'ils **peuvent échouer** : ouvrir la base
avec les 32 octets bruts au lieu de `x'<hex>'` doit échouer, et une note verrouillée délibérément
fuitée dans l'index ne doit **quand même** pas ressortir d'une recherche.

⚠️ **Ce qui n'est PAS prouvé** : l'acquisition réelle de la KEK chez un utilisateur qui migre. La
build isolée n'a accès ni aux préférences ni au Keystore de l'application d'origine. Cela ne se
vérifie qu'à la bascule — cf. [06-ISOLATION-PENDANT-LE-CHANTIER.md](06-ISOLATION-PENDANT-LE-CHANTIER.md) §2.

### Phase 3 — Domaine et données ✅ close le 2026-08-13

- [x] DAO dossiers, notes, recherche FTS5 (`@RawQuery(observedEntities = …)`), liens
- [x] `NoteLinkWriter` — écritures de liens, hors graphe Room
- [x] `normalizeTitle` et l'extraction `[[Titre]]` — **vérifiés contre le vrai code Dart**, pas
      supposés équivalents (`09-VECTEURS-DE-PARITE.md`)
- [x] Modèles de domaine : `Note`, `Folder`, `NoteSortMode`
- [x] Repositories transactionnels : notes, dossiers, liens
- [x] Séparation lectures / écritures des DAO (D-010)
- [x] Contrat `VaultSealer`, fermé par défaut (D-011)

**Deux modèles annoncés n'ont PAS été portés, et c'est une décision, pas un oubli :**

| Modèle | Pourquoi non |
|---|---|
| `NoteChange` | il prévenait trois écouteurs qu'une note avait changé ; l'invalidation de Room le fait déjà. Le porter aurait produit un chemin mort (D-009) |
| `NoteLink` | `NoteLinkRow` a exactement la forme voulue. Un jumeau de domaine identique serait un doublon sans contrepartie |

⚠️ Point relevé en écrivant `NoteLinkWriter` : la résolution des liens **ne peut pas se faire en
SQL**. SQLite ne sait pas dépouiller les diacritiques, donc `lower(title)` rend `réunion` là où la
normalisation rend `reunion`. La version Flutter apparie en mémoire
(`backlinks_service.dart:327`), et cette table d'appariement **exclut les notes verrouillées**.

**Critère de sortie atteint** — mesuré :

| Vérification | Résultat |
|---|---|
| Tests instrumentés sur Galaxy S9 (API 29) | **55**, 0 échec |
| Tests JVM | **45**, 0 échec |
| Schéma Room vs DDL hérité | aucune divergence |
| Vecteurs de parité rejoués depuis le vrai Dart | 92, dont 2 divergences **assumées et listées** |

Treize de ces tests portent sur des défauts **réellement trouvés** pendant la phase, dont sept
par deux relectures externes indépendantes. Le détail est dans `07-RELECTURES.md`, R-005 et
R-006.

⚠️ **La seconde passe a trouvé une fuite de clair dans un lot que la première venait de
déclarer exempt de fuite de clair.** Le trou n'était pas dans un chemin d'écriture de note mais
dans une réassignation de dossier, qui déplace N notes sans en toucher aucune individuellement.

### Phase 4 — Coffres ⚠️ close le 2026-08-14, **critère de sortie partiellement atteint**

Détail complet : [11-COFFRES.md](11-COFFRES.md).

- [x] Argon2id via BouncyCastle `Argon2BytesGenerator` — 16 vecteurs, deux jeux de paramètres
- [x] Enveloppe AES-GCM `nonce(12) ‖ ciphertext ‖ tag(16)`, AAD liante
- [x] `KeystoreBridge.kt` repris, débarrassé de sa couche MethodChannel
- [x] Auto-verrouillage, freinage exponentiel, compteur de tentatives, effacement à 5 échecs
- [x] Reprise des effacements interrompus, lue dans les préférences **héritées**
- [x] `VaultSealer` réel branché — le bouchon qui refuse a rempli son office jusqu'au bout

**Mesuré** :

| Vérification | Résultat |
|---|---|
| Tests JVM | **70** (48 avant), 0 échec |
| Tests instrumentés sur Galaxy S9 | **91** (67 avant), 0 échec |
| Argon2id / AES-GCM / HMAC contre une **tierce** implantation | 37 concordances, 0 divergence |
| Un coffre dont les colonnes viennent du Dart, ouvert depuis Kotlin | ✅ |

🔴 **Ce qui reste ouvert, et qu'il ne faut pas déclarer clos par habitude** : aucun test n'ouvre un
coffre pris sur le téléphone d'un utilisateur. Pour le mode à code c'est **structurellement
impossible** avant la bascule — la clé du Keystore est liée à l'UID, et la build de portage porte
un `applicationId` suffixé `.next`. Le contrôle appartient donc à la phase 8, sur base réelle.

**Six défauts trouvés pendant la phase**, dont quatre par deux relectures externes indépendantes et
deux par mes propres tests. Cinq sur six portaient sur le **classement des échecs**, pas sur la
cryptographie. Cf. [07-RELECTURES.md](07-RELECTURES.md), R-008.

### Phase 5 — Interface Compose ✅ close le 2026-08-14

`splash` → `home` + tiroir dossiers → `éditeur` → `recherche` → `coffres` (feuilles PIN et
passphrase) → `corbeille` → `réglages` → `à propos` → `mentions légales`

- [x] i18n : **311** clés ARB → `strings.xml` fr/en **par script** (`outils/arb_vers_strings.py`)
- [x] Contrôle **en sens inverse** : `outils/verif_i18n_retour_arriere.py` regénère l'ARB depuis le
      XML produit et le confronte à la source
- [x] Préférences héritées lues et écrites dans leur format d'origine (`LegacyPreferences`)
- [x] Les quatre gestes de coffre différés de la phase 4 (cf. [11-COFFRES.md](11-COFFRES.md) §7)
- [x] Navigation, thème, langue, `FLAG_SECURE`

⚠️ **Le décompte de clés annoncé ici était FAUX** : 436, alors que les deux ARB en portent 311. Le
chiffre traînait depuis le relevé initial et n'avait jamais été vérifié. Mesuré le 2026-08-14.

**Mesuré** :

| Vérification | Résultat |
|---|---|
| Segments i18n confrontés en sens inverse | **628**, 4 divergences expliquées, **0 inexpliquée** |
| Tests JVM | **70**, 0 échec |
| ktlint · detekt · lintDebug | verts, **aucune baseline ajoutée** |
| Boucle complète sur Galaxy S9 (API 29) | ✅ splash → accueil → création → éditeur → retour |

🔴 **Un défaut qu'aucun contrôle statique n'a vu** : `painterResource(R.mipmap.ic_launcher)` sur
l'écran de présentation. À partir de l'API 26, `ic_launcher` résout vers une icône **adaptative**,
que Compose refuse de charger. L'application se fermait **au premier lancement, et seulement au
premier**. Ni la compilation, ni ktlint, ni detekt, ni lint, ni les 70 tests JVM ne l'ont vu — et
sur un appareil API 24-25 il n'existe même pas. Trouvé en installant sur le S9.

**Deux défauts de l'application publiée sont REPRODUITS et consignés**, pas corrigés en silence.
Cf. [05-PARITE.md](05-PARITE.md).

**Ce qui n'est PAS dans cette phase, et c'est le plan qui le dit** : les rétroliens, l'export et le
mode panique appartiennent à la phase 6.

### Phase 6 — Services transverses

- [x] ~~`FLAG_SECURE` avec compteur de références~~ — commit `ca25568`, 8 tests JVM écrits **contre le
      second poseur**, celui qui n'existait pas quand le code de la phase 5 a été écrit
- [x] ~~Export Markdown / ZIP~~ — commit `1b48697`. Vecteurs de parité **relevés en exécutant le vrai
      code Dart**, archive écrite en flux et non en mémoire, 18 tests JVM
- [x] ~~Mode panique~~ — commit `4e8e259`. Clé détruite dans **toutes** les sources et **vérifiée**,
      base **scellée** et pas seulement fermée, clés `vault_pin_*` orphelines comprises. Vérifié de
      bout en bout sur le S9
- [x] ~~Backlinks `[[titre]]` — **interface seulement**~~ — commits `98c1338` (l'état porte le
      curseur), `f134b7b` (panneau), `a55deb6` (autocomplétion), `cab114a` (menu). La couche données
      était faite depuis la phase 2-3 et va plus loin que l'application publiée : l'indexation se
      fait dans la transaction qui écrit la note, pas dans un service séparé avec son propre débounce

  **Les deux manques assumés sont clos :**

  - ✅ ~~**Copier en Markdown**~~ — **fait le 2026-08-15**, commit `5edbc2b`, et sorti d'un lot
    d'interface exprès : c'est un service de sécurité, pas une entrée de menu. Marquage
    `EXTRA_IS_SENSITIVE` (Android 13+), effacement différé d'une minute qui **ne s'exécute que si le
    presse-papiers porte encore notre valeur**, et un **compteur de génération unique** partagé par
    les copies et les purges — sans lui, une minuterie périmée efface l'état d'une copie plus
    récente et le clair de celle-ci reste indéfiniment exposé.
    ⚠️ Trois défauts y ont été trouvés **par relecture externe croisée, dans le code du jour même** :
    génération incrémentée avant l'écriture, réarmement borné qui **oubliait** le clair resté dans le
    presse-papiers public, et un `null` confondant « illisible » et « pas du texte » — ce dernier
    rendu grave *par le correctif du premier*. Cf. `04-PIEGES.md` §43-§45.
  - ✅ ~~**Sortir une note d'un coffre**~~ — **fait le 2026-08-15**. `relocateLockedNote` déchiffre
    avec la clé d'origine, écrit le clair et déplace **dans une seule transaction** ; si la
    destination est un autre coffre, la note est **rescellée avec la clé de celui-là**, jamais
    transportée telle quelle. `moveToFolder` continue de refuser une note verrouillée : sortir d'un
    coffre est un geste **nommé**, et l'y router en silence ferait exactement ce que toute la couche
    s'emploie à rendre impossible. Le contrat d'ouverture (`VaultOpener`) est séparé de
    `VaultSealer` pour qu'une dépendance à « sceller » ne donne pas « déchiffrer » en prime.
    L'interface pose la confirmation dont les chaînes traînaient inutilisées
    (`note_editor_exit_vault_*`), et **demande le secret d'un coffre fermé choisi comme
    destination** — ce dernier cas échouait par une exception brute, y compris pour une note en
    clair. 6 tests instrumentés, dont celui de l'ouvreur négligent, qui **viderait** la note.

**Hors périmètre initial, fait quand même** — trois défauts de l'application **publiée** relevés en
portant cette phase, corrigés dans `notes_tech` sur demande de Patrice (`333aba1`, `24bc67e`).
⚠️ **Aucune publication 2.0.4 décidée.** Cf. `docs/05-PARITE.md`.
- [x] ~~Réglages (DataStore)~~ — **faits en phase 5**, et **pas** avec DataStore : le fichier de
      préférences hérité est conservé pour que les réglages survivent à la bascule (D-015)

### Phase 7 — Dictée vocale

Décision d'implémentation reportée, **pas** la fonctionnalité (cf. D-002). Isolée derrière
l'interface de domaine `SpeechToText` dès la phase 1 pour que son absence ne contamine rien.

### Phase 8 — Parité et bascule

- [ ] **Checklist de parité écran par écran** ([05-PARITE.md](05-PARITE.md)) — **39 cases vides** :
      10 écrans, 16 composants, 8 services, 5 promesses publiques. ⚠️ Une case cochée veut dire
      « vérifié **sur appareil** », pas « le code existe » — c'est ce qui rend ce point long, et
      c'est ce qui le rend utile. La colonne « Kotlin » est vide partout : aucune ligne ne nomme
      encore son homologue de portage
- [x] ~~Tests instrumentés sur le S9~~ — **118 tests, 0 échec, 0 ignoré**, relancés le 2026-08-15.
      ⚠️ Le compte d'ignorés est vérifié sur le code `-3` de l'instrumentation, pas sur le `OK`
      final : six tests ont déjà été ignorés ici pendant que la sortie affichait `OK` (`04-PIEGES.md`
      §45). ⚠️ **Jamais sur le S24 FE** — `connectedAndroidTest` efface les données de l'application
- [x] ~~Revue externe sur le delta complet~~ — passée le 2026-08-15, **deux relecteurs en parallèle
      et plusieurs tours**, y compris sur les correctifs issus des tours précédents. C'est là que
      les défauts les plus graves sont sortis, et **jamais les mêmes chez les deux**
      (`07-RELECTURES.md`)
- [ ] Release 3.0.0

⚠️ **Trois cases de migration restent, et aucune ne se coche depuis un poste de travail** : elles
demandent une installation 2.0.3 réelle sur le S9 et le drapeau `replaceInstalledApp`. Cf.
`05-PARITE.md` §Migration.

## 4. Règles de travail

- 🔒 **Le portage ne remplace rien tant qu'il n'est pas prouvé sûr.** L'`applicationId` par défaut
  porte le suffixe `.next` : la build Kotlin s'installe **à côté** de Notes Tech et ne peut pas
  voir ses données. Prendre sa place demande un geste explicite, et ne se fera qu'en phase 8.
  Voir [06-ISOLATION-PENDANT-LE-CHANTIER.md](06-ISOLATION-PENDANT-LE-CHANTIER.md).
- `notes_tech` (Flutter) est **gelé** pendant le chantier, hors correctif critique. Deux dépôts
  qui évoluent en parallèle, c'est comme ça qu'on perd la parité.
- Aucune phase ne démarre avant que le critère de sortie de la précédente soit **observé**, pas
  supposé.
- Toute décision structurante s'écrit dans [01-DECISIONS.md](01-DECISIONS.md) **au moment où elle
  est prise**, avec ce qui a été écarté et pourquoi.
- Les pièges connus sont dans [04-PIEGES.md](04-PIEGES.md). Le relire avant d'écrire un DAO.

### Phase 7 — état au 2026-08-15 (soir)

Trois étapes posées, la quatrième identifiée mais non écrite.

- [x] ~~**Le contrat de domaine**~~ — `domain/voice/SpeechToText.kt`, `SttErrors.kt`, `WavPcm16.kt`.
      Transposé du contrat Dart réel de `files_tech_voice`, **lu dans le cache pub**, pas deviné.
      ⚠️ D-002 affirmait au passé que cette interface existait depuis la phase 1 : elle n'existait
      pas. Cf. la rectification dans `01-DECISIONS.md`.
- [x] ~~**La capture audio**~~ — `data/voice/VoiceCapture.kt`. `AudioRecord` 16 kHz mono 16 bits,
      source `VOICE_RECOGNITION`, en-tête WAV corrigé en fin de capture, borne de deux minutes.
      Relue par deux relecteurs externes : **sept défauts**, dont deux qui laissaient de la voix sur
      le disque (annulation non coopérative, absence de troncature).
- [x] ~~**La purge du clair**~~ — au démarrage et par `PanicStep.VOICE_CAPTURES_WIPE`, placée juste
      après la clé avec les archives d'export.
- [ ] **L'import du modèle** — c'est la suite immédiate, et elle ne dépend d'aucune décision.
- [ ] **Le moteur** — whisper.cpp en JNI. ⚠️ **Bloqué sur une décision de Patrice** : vendoriser
      4,2 Mo de sources tierces (76 fichiers, licence MIT). Elles sont sur le disque et déjà
      compilées pour les quatre ABI par `whisper_ggml_plus`.
- [ ] **L'interface** — écran de configuration, bouton micro, superposition d'enregistrement.

#### ⚠️ Ce qu'il faut savoir avant d'écrire l'import — vérifié, pas supposé

1. **Le modèle vit dans `files/stt/`, PAS dans `files/models/`.** Les deux répertoires sont
   distincts et le second est réservé aux modèles hérités des versions qui embarquaient une IA.
   `legacy_model_files.dart` porte un avertissement explicite et **un test vérifie que `stt/`
   survit** à sa purge : l'utilisateur a dû télécharger puis importer ce fichier à la main, et se
   tromper de dossier le lui ferait recommencer sans explication.
2. **Le mode panique publié a DEUX étapes vocales**, et le portage n'en a qu'une :
   - `voiceCancel`, **très tôt** — juste après `forceSecureWindow`, avant même le presse-papiers :
     elle coupe l'exposition immédiate, c'est-à-dire le micro encore ouvert ;
   - `voiceWipe`, **après** `dbWipe` : le `.bin`, son cache de vérification et les WAV orphelins.
   Le portage a `VOICE_CAPTURES_WIPE` (les WAV) mais **rien pour le `.bin` ni pour couper une
   capture en cours**. Les deux entreront avec l'import, et pas avant — l'énumération refuse les
   étapes qui ne s'exécutent pas.
3. **Aucun téléchargement** — cf. D-017. L'import se fait depuis un fichier choisi par
   l'utilisateur, et le contrat n'expose même pas de champ `url`.
