# Notes Tech

> Vos notes restent dans votre poche. Chiffrées, et hors ligne.

🇬🇧 [English version](README.md)

**v2.0.9 — Septembre 2026** · [Politique de confidentialité](assets/legal/PRIVACY.fr.md) · [CGU](assets/legal/TERMS.fr.md) · [Sécurité](SECURITY.fr.md)

Application Android Flutter de prise de notes Markdown chiffrées,
**100 % locale, zéro permission Internet**. Interface bilingue **FR / EN**.
Coffres par dossier (passphrase Argon2id ou PIN Keystore-bound), recherche
plein-texte FTS5, dictée vocale Whisper on-device, backlinks `[[note]]`,
aperçu Markdown, mode panique multi-step.

## Quoi de neuf en v2.0.9

- **Aperçu des notes** : une bascule Éditer / Aperçu affiche titres,
  listes, emphase et liens mis en forme. Un lien `[[Titre]]` ouvre la note
  visée.
- Le dossier par défaut était créé en français (« Boîte de réception »)
  sur toutes les installations. Il suit maintenant la langue de l'app, et
  un dossier renommé garde son nom.
- Noms des modèles de dictée, erreurs de micro et d'import, textes de
  partage et README de l'export sont traduits.
- Un coffre PIN impossible à créer dit maintenant pourquoi (pas de
  verrouillage d'écran, ou pas de stockage de clés matériel), et les
  messages lancés depuis la liste des dossiers ne sont plus cachés dessous.

## Ce qui a changé en v2.0.0

**L'IA embarquée est retirée.** La recherche sémantique (MiniLM) et le
Q&A « Demander à mes notes » (Gemma 3 1B) disparaissent. L'APK arm64
passe de ~127 Mo à **26,9 Mo**. La recherche reste plein-texte FTS5 +
bm25 : rapide et exacte, mais elle ne devine pas les synonymes.

Bump majeur et non mineur pour trois raisons :

- l'app perd ses deux fonctions phares, ce qu'un `1.2.0` ne signalerait
  pas à l'utilisateur ;
- l'APK est divisé par près de cinq, visible dès l'installation ;
- **la migration est à sens unique** : la base passe en schéma v9 et la
  v1.1.6 publiée n'a pas de `onDowngrade`, donc réinstaller une 1.x ne
  peut plus ouvrir la base.

Cette version inaugure aussi les **APK splits par ABI sans universel**.
Les deux familles ont des `versionCode` incompatibles — l'offset
`+1000 × index` de Flutter place tout universel sous n'importe quel
split de même version, et Android refusait alors l'installation en
downgrade. Transition à sens unique : ne pas réintroduire d'universel.
Depuis la v2.0.8, les splits portent `versionCode × 10 + ABI` (bloc de
`android/app/build.gradle.kts`) au lieu de l'offset de Flutter ; la règle
reste la même.

Corrections notables du même cycle :

- supprimer un dossier détruisait la clé Keystore **avant** la base, ce
  qui rendait un coffre définitivement illisible si la base échouait ;
- le mode panique annonçait « effacement terminé » même en cas d'échec ;
- **les migrations de schéma n'avaient jamais tourné** — les tests
  créaient des bases neuves, donc `onUpgrade` n'était jamais appelé ;
- le titre d'une note de coffre pouvait partir en clair.

Pour penseurs, thérapeutes, étudiants, chercheurs, écrivains et
journalistes qui veulent prendre des notes sensibles ou denses sans
qu'elles ne quittent jamais leur téléphone.

**Différenciateur unique vs Notesnook / Obsidian / Bear / Logseq :
aucune permission Internet — l'application est techniquement incapable
d'envoyer quoi que ce soit, et c'est vérifiable dans son manifeste.**

---

## Promesse de confidentialité

- **Aucune permission `INTERNET`** dans le manifeste — vérifiable à
  l'œil nu (`AndroidManifest.xml`). Les 7 permissions transitives
  (INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK, RECEIVE_BOOT_COMPLETED,
  FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS)
  sont neutralisées via `tools:node="remove"`.
- **Seule permission runtime** : `RECORD_AUDIO` si vous activez la dictée.
- Aucun compte, aucune inscription, aucun tracker, aucune publicité,
  aucune télémétrie.
- Open source Apache 2.0, code intégral vérifiable.
- `allowBackup=false` + `dataExtractionRules` complet (pas
  d'exfiltration via Smart Switch ou Android Backup).
- Modèle Whisper importé via SAF — jamais bundlé,
  jamais téléchargé en réseau par l'app.

---

## Fonctionnalités

### Édition Markdown
- Création / édition / auto-save debounced
- **Bascule Éditer / Aperçu** : l'aperçu rend le Markdown (titres,
  listes, emphase, liens). Un lien `[[Titre]]` ouvre la note visée ; un
  lien `http`, `https` ou `mailto` s'ouvre dans l'app système, tout autre
  schéma est ignoré. Les images ne sont jamais chargées : seul leur texte
  alternatif s'affiche.
- Épingler / favoris / archives / corbeille (rétention 30 j)
- Mode clair / sombre / système (palette GitHub)
- Tri configurable (modifié, créé, titre)

### Coffres par dossier
- **Mode passphrase** — Argon2id (m=64 Mo, t=3) + AES-256-GCM. La clé du
  coffre (32 octets aléatoires) est enveloppée par la clé dérivée de la
  passphrase, et stockée dans la base SQLCipher, elle-même chiffrée par la
  clé maître scellée par le Keystore (hardware-backed).
- **Mode PIN** — 4 à 6 chiffres, dérivation Argon2id allégée (m=32 Mo,
  t=2) + clé Keystore-bound dédiée par coffre, **auto-wipe à 5
  tentatives échouées** (atomique, repris au boot si crash en cours).
- AAD partout : `folder_id` lié au wrap KEK, `note_id` lié au contenu
  chiffré — anti rejeu / anti substitution.
- HMAC verifier en temps constant pour détecter passphrase incorrecte
  sans déchiffrer toutes les notes.
- **Verrouillage automatique** : tous les coffres se verrouillent dès que
  l'app passe en arrière-plan, et après un délai configurable (5, 15, 30
  ou 60 min, ou jamais ; 15 min par défaut).

### Recherche
- **FTS5** instantané (tokenizer `unicode61`, diacritiques normalisés).

### Dictée vocale Whisper
- **Whisper on-device** via le paquet `files_tech_voice` (dépendance git
  épinglée à un commit).
- Modèles Whisper Base q5_1 (57 Mo) ou Tiny q5_1 (32 Mo), importés via
  SAF (téléchargement par le navigateur système, pas par l'app).
- Vérification SHA-256 stricte avant chargement ; au démarrage, un cache
  de vérification de 24 h évite de recalculer l'empreinte à chaque fois.
- Audio jamais persisté (tmp + delete dans tous les chemins).

### Backlinks
- Liens `[[Titre]]`, auto-complétion, panneau Mentions / liens sortants.
- **Indexation ciblée** : à chaque sauvegarde, seule la note modifiée est
  retraitée ; les écritures en lot sont regroupées (500 ms). La passe
  complète de réconciliation au démarrage est différée de 2 s.
- Liens fantômes auto-résolus à la création / au renommage de la cible.

### Export Markdown
- Export d'une note : `.md` avec frontmatter YAML compatible Obsidian,
  Logseq, Bear, Foam, Dendron.
- Export ZIP global : arborescence par dossier + README d'export.
- Encodage en isolate (`compute()`), nom de fichier durci anti-path-
  traversal et anti-Unicode-bidi.

### Mode panique
- Réglages → Mode panique.
- Confirmation par mot tapé (`EFFACER`), qui active le bouton
  « Tout effacer ».
- Séquence **ordonnée et best-effort** (un step qui throw n'interrompt
  pas les suivants) :
  1. `FLAG_SECURE` forcé ON
  2. Capture micro coupée
  3. Presse-papiers vidé
  4. **`foldersLockAll`** — verrouille tous les coffres ouverts
  5. **`pinKeysWipe`** — supprime toutes les clés Keystore PIN
     (`deleteKeysWithPrefix` côté Kotlin)
  6. **`kekDestroy`** — détruit la clé maître Keystore (DB
     instantanément illisible)
  7. Pause des background workers
  8. **`dbWipe`** — écrase header SQLCipher 16 Mo + delete + sidecars
  9. Effacement Whisper (modèles, cache de vérification, WAV orphelins)
  10. Fichiers de modèles hérités des versions ≤ 1.1.6
  11. Préférences (sauf les deux clés nécessaires au redémarrage), exports,
      tmp

### FLAG_SECURE
- Activé par défaut : pas de capture d'écran ni d'aperçu dans les apps
  récentes.

---

## Sécurité

- **DB SQLCipher** chiffrée AES-256 (SQLCipher 4), clé maître scellée par
  AndroidKeystore (hardware-backed sur S24).
- **Clés CSPRNG de 32 octets** : la clé de la base est scellée par le
  Keystore ; celle de chaque coffre est enveloppée par une clé dérivée
  Argon2id de la passphrase, ou, en mode PIN, par une clé Argon2id allégée
  puis scellée par une clé Keystore dédiée.
- **AAD partout** : `folder_id` pour le wrap KEK, `note_id` pour le
  contenu — empêche la réutilisation d'un blob chiffré dans un autre
  contexte.
- **HMAC verifier en temps constant** pour détecter une mauvaise
  passphrase / un mauvais PIN sans test exhaustif des notes.
- **Mode PIN avec auto-wipe** : 5 tentatives, flag prefs atomique,
  reprise au boot si interruption.
- **Mode panique ordonné** : `foldersLockAll → pinKeysWipe → kekDestroy
  → dbWipe`, garantit que la KEK disparaît avant la base.
- **Wipe DB header 16 Mo** (la KEK destroy précédente garantit déjà le
  secret ; l'écrasement complet n'apporte rien sur eMMC moderne avec
  wear-leveling — décision de design, voir `SECURITY.md`).
- **`setUserAuthenticationRequired(false)`** sur la clé Keystore PIN :
  le PIN applicatif est l'unique facteur, le doubler avec biométrie
  l'exposerait à la contrainte (clé biométrique survit au reboot).
- **FLAG_SECURE** par défaut.
- `allowBackup=false`, `dataExtractionRules` durci.

Voir [`SECURITY.md`](SECURITY.fr.md) pour le modèle de menace complet et
la procédure de signalement de faille.

---

## Permissions Android

| Permission | État | Usage |
|---|---|---|
| `INTERNET` | **REMOVED** (`tools:node="remove"`) | aucun |
| `ACCESS_NETWORK_STATE` | REMOVED | aucun |
| `WAKE_LOCK` | REMOVED | aucun |
| `RECEIVE_BOOT_COMPLETED` | REMOVED | aucun |
| `FOREGROUND_SERVICE` | REMOVED | aucun |
| `FOREGROUND_SERVICE_DATA_SYNC` | REMOVED | aucun |
| `POST_NOTIFICATIONS` | REMOVED | aucun |
| `RECORD_AUDIO` | runtime, opt-in | uniquement si dictée Whisper activée |
| `com.filestech.notes_tech.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | ajoutée au manifeste fusionné par AndroidX, niveau `signature` | interne à l'app, jamais demandée à l'utilisateur |

À auditer sur chaque release via `aapt dump permissions`.

---

## Installation

Deux options :

1. **APK publiée** : récupérer le split correspondant à votre appareil
   sur [GitHub Releases](https://github.com/gitubpatrice/notes_tech/releases)
   — `arm64-v8a` couvre la quasi-totalité des téléphones depuis 2016, et
   il n'y a pas d'APK universel —
   vérifier le SHA-256 publié dans les notes de release, side-loader.
   Ces APK sont construites et signées par GitHub Actions
   (`.github/workflows/release.yml`), déclenchées au push d'un tag `v*`.
2. **Build local** (recommandé pour audit) — voir section suivante.

Pas de Play Store : distribution side-load uniquement (cohérent avec la
promesse de confidentialité — aucun compte requis pour installer).

---

## Build local

```bash
flutter pub get
flutter analyze
flutter test
flutter build apk --release --split-per-abi --obfuscate \
  --split-debug-info=build/symbols
```

Pour une release **strictement signée** (pas de fallback debug), créer
`android/key.properties` :

```
storeFile=/chemin/absolu/vers/votre.jks
storePassword=...
keyAlias=...
keyPassword=...
```

APK release arm64 : **~27 Mo** (SQLCipher + Whisper.cpp — le modèle de
dictée est téléchargé séparément, non bundlé). L'IA embarquée a été retirée
en v2.0.0 : elle pesait 100 Mo pour une fonctionnalité que presque personne
ne pouvait atteindre sans permission Internet.

Pré-requis :
- Flutter 3.47.2 (version exacte, épinglée dans `pubspec.yaml` ; Dart
  `^3.11.5`)
- Android SDK + NDK installés via Android Studio
- Aucun dépôt voisin à cloner : `files_tech_voice` et `files_tech_core`
  sont des dépendances git épinglées à un commit, récupérées par
  `flutter pub get`

---

## Architecture

```
lib/
├── main.dart                          # bootstrap parallèle + DI Provider
├── app.dart                           # MaterialApp
├── core/                              # constants, exceptions, theme, a11y
├── data/
│   ├── models/                        # Note, Folder, NoteLink,
│   │                                    NoteChangeEvent
│   ├── db/                            # SQLite (FTS5 + sqlcipher), DAOs
│   └── repositories/                  # façades + streams typés
├── l10n/                              # ARB FR / EN + classes générées
├── services/
│   ├── security/                      # VaultService (KEK Keystore),
│   │                                    FolderVaultService (passphrase/PIN),
│   │                                    KeystoreBridge, PanicService
│   ├── export/                        # NoteExportService (.md, ZIP)
│   ├── secure_window_service.dart     # FLAG_SECURE via MethodChannel
│   ├── voice/                         # VoiceService (Whisper, files_tech_voice)
│   ├── backlinks_service.dart         # parsing [[]], indexation ciblée
│   ├── note_actions.dart              # actions UI réutilisables
│   └── settings_service.dart
├── ui/
│   ├── screens/                       # home, editor, search, settings,
│   │                                    trash, voice_setup, about, ...
│   └── widgets/                       # NoteCard, BacklinksPanel,
│                                        NoteMarkdownPreview, ...
└── utils/                             # debouncer, text_utils, error_localize, ...
```

## Stack

- Flutter 3.47.2 / Dart `^3.11.5`
- `sqflite_sqlcipher` (SQLite chiffré AES-256 + FTS5)
- `flutter_secure_storage` (KEK scellée AndroidKeystore)
- `cryptography` (Argon2id RFC 9106 + AES-GCM, Dart pur)
- `crypto` (SHA-256 streaming pour vérification modèles)
- `files_tech_voice` (dépendance git, Whisper STT)
- `files_tech_core` (dépendance git, helpers crypto partagés)
- `flutter_markdown_plus` + `markdown` (aperçu des notes, pages légales)
- `provider`, `shared_preferences`, `file_picker`, `archive`,
  `share_plus`, `url_launcher`
- **Aucune dépendance réseau**

## Cible

- Samsung Galaxy S24 / S24 FE (validés)
- Samsung Galaxy S9 (Android 10), POCO C75
- minSdk 24 (Android 7.0+)

---

## Licence

[Apache License 2.0](LICENSE) — voir aussi [`NOTICE`](NOTICE) et
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Suite Files Tech

Notes Tech fait partie de la suite [Files Tech](https://files-tech.com/),
des applications Android orientées confidentialité :
- [PDF Tech](https://github.com/gitubpatrice/PDF-TECH)
- [Read Files Tech](https://github.com/gitubpatrice/READ-FILES-TECH)
- [AI Tech](https://github.com/gitubpatrice/ai_tech)
- [Pass Tech](https://github.com/gitubpatrice/pass_tech)
