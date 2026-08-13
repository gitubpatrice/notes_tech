# Notes Tech — portage Flutter → Kotlin natif

> **État** : phase 1 (socle) en cours. Dernière mise à jour : 2026-08-13.
>
> Ce fichier est le plan de référence. Il dit **où on en est** et **ce qui vient ensuite**.
> Les décisions déjà prises sont dans [01-DECISIONS.md](01-DECISIONS.md) — ne pas les rediscuter ici.

---

## 1. Ce qu'on porte

Source : `j:\applications\notes_tech`, version publiée **2.0.3 (versionCode 51)**, commit `7180a2c`.

| Mesure | Valeur (relevée le 2026-08-13) |
|---|---|
| Code Dart `lib/` | 21 094 lignes, 63 fichiers |
| Tests Dart | 2 817 lignes, 13 fichiers |
| Écrans | 10 |
| Widgets | 16 |
| Clés i18n | 436 × {fr, en} |
| Tables | `folders`, `notes`, `note_links` + `notes_fts` (FTS5) + 3 triggers |
| Version de schéma | **9** |

Les cinq plus gros fichiers, qui concentrent l'essentiel de la difficulté :

| Fichier Dart | Lignes | Destination Kotlin |
|---|---|---|
| `services/security/folder_vault_service.dart` | 1 582 | `security/vault/` (phase 4) |
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
| Tests instrumentés sur Galaxy S9 (API 29) | **11**, 0 échec |
| Tests JVM | **46**, 0 échec |
| Schéma Room vs DDL hérité (comparaison mécanique) | aucune divergence |

Deux de ces tests ont une valeur particulière parce qu'ils **peuvent échouer** : ouvrir la base
avec les 32 octets bruts au lieu de `x'<hex>'` doit échouer, et une note verrouillée délibérément
fuitée dans l'index ne doit **quand même** pas ressortir d'une recherche.

⚠️ **Ce qui n'est PAS prouvé** : l'acquisition réelle de la KEK chez un utilisateur qui migre. La
build isolée n'a accès ni aux préférences ni au Keystore de l'application d'origine. Cela ne se
vérifie qu'à la bascule — cf. [06-ISOLATION-PENDANT-LE-CHANTIER.md](06-ISOLATION-PENDANT-LE-CHANTIER.md) §2.

### Phase 2 — Ouvrir la base héritée 🔴 point de risque n°1

Avant toute interface. Voir [02-SCHEMA-HERITE.md](02-SCHEMA-HERITE.md) et
[03-KEK-ACQUISITION.md](03-KEK-ACQUISITION.md).

- [ ] Entités Room décalquées **au caractère près** sur le schéma sqflite
- [ ] `SqlCipherRawKey` — format `x'<64 hex>'` (cf. décision D-004)
- [ ] Acquisition de la KEK en 3 couches (native → secours → refus honnête)
- [ ] `notes_fts` + 3 triggers créés hors du graphe Room, via `RoomDatabase.Callback`
- [ ] Chemin de base : `app_flutter/notes_tech.db`, **pas** `databases/` (cf. D-003)

**Critère de sortie** : un test instrumenté qui pousse une **vraie base 2.0.3** sur l'appareil et
prouve la lecture des notes, des dossiers, des liens, et une réponse FTS5 non vide.
Tant que ce test n'est pas vert, les phases 3+ sont bloquées.

### Phase 3 — Domaine et données ⏳ entamée

- [x] DAO dossiers, notes, recherche FTS5 (`@RawQuery(observedEntities = …)`), liens
- [x] `NoteLinkWriter` — écritures de liens, hors graphe Room
- [ ] 4 modèles de domaine (`Note`, `Folder`, `NoteLink`, `NoteChange`)
- [ ] Repositories, `Flow` Room en remplacement des streams `provider`
- [ ] `normalizeTitle` — **la même fonction** que côté Dart, sinon les liens ne s'apparient plus

⚠️ Point relevé en écrivant `NoteLinkWriter` : la résolution des liens **ne peut pas se faire en
SQL**. SQLite ne sait pas dépouiller les diacritiques, donc `lower(title)` rend `réunion` là où la
normalisation rend `reunion`. La version Flutter apparie en mémoire
(`backlinks_service.dart:327`), et cette table d'appariement **exclut les notes verrouillées**.

**Critère de sortie** : tests JVM sur les repositories + le test instrumenté de la phase 2 étendu
aux écritures (création, édition, corbeille, purge).

### Phase 4 — Coffres 🔴 point de risque n°2

Paramètres à recopier **à l'identique** — la moindre dérive rend les coffres existants
inouvrables. Valeurs exactes dans [02-SCHEMA-HERITE.md](02-SCHEMA-HERITE.md) §4.

- [ ] Argon2id via BouncyCastle `Argon2BytesGenerator`
- [ ] Enveloppe AES-GCM `nonce(12) || ciphertext || tag(16)`
- [ ] `KeystoreBridge.kt` repris du projet Flutter, débarrassé de sa couche MethodChannel
- [ ] Auto-verrouillage, compteur de tentatives, auto-effacement à 5 échecs

**Critère de sortie** : ouvrir depuis Kotlin un coffre **créé par la version Flutter**, sur base
réelle, dans les deux modes (passphrase et PIN). Pas sur vecteur synthétique — cf. la leçon
« un test peut passer sur un chemin qu'aucun appelant n'emprunte ».

### Phase 5 — Interface Compose

Dans l'ordre de dépendance, pas dans l'ordre du dossier :

`splash` → `home` + tiroir dossiers → `éditeur` → `recherche` → `coffres` (feuilles PIN et
passphrase) → `corbeille` → `réglages` → `à propos` → `mentions légales`

- [ ] i18n : 436 clés ARB → `strings.xml` fr/en **par script**, jamais à la main
      (⚠️ apostrophes : `\'` obligatoire dans une valeur)

### Phase 6 — Services transverses

- [ ] Mode panique (478 l) — séquence et ordre des étapes à préserver
- [ ] Export Markdown / ZIP (519 l)
- [ ] Backlinks `[[titre]]` (401 l)
- [ ] `FLAG_SECURE` avec compteur de références
- [ ] Réglages (DataStore)

### Phase 7 — Dictée vocale

Décision d'implémentation reportée, **pas** la fonctionnalité (cf. D-002). Isolée derrière
l'interface de domaine `SpeechToText` dès la phase 1 pour que son absence ne contamine rien.

### Phase 8 — Parité et bascule

- [ ] Checklist de parité écran par écran ([05-PARITE.md](05-PARITE.md))
- [ ] Tests instrumentés sur le S9 (⚠️ **jamais sur le S24 FE** — `connectedAndroidTest` efface
      les données de l'application)
- [ ] Revue externe sur le delta complet
- [ ] Release 3.0.0

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
