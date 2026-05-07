# Notes Tech

> Vos notes restent dans votre poche. L'IA aussi.

Application Android Flutter de prise de notes Markdown chiffrées,
**100 % locale, zéro permission Internet**. Coffres par dossier
(passphrase Argon2id ou PIN Keystore-bound), recherche sémantique
on-device, Q&A Gemma 3 1B, dictée Whisper, backlinks `[[note]]`,
mode panique multi-step.

Pour penseurs, thérapeutes, étudiants, chercheurs, écrivains et
journalistes qui veulent prendre des notes sensibles ou denses sans
qu'elles ne quittent jamais leur téléphone.

**Différenciateur unique vs Notesnook / Obsidian / Bear / Logseq :
l'IA tourne dans le téléphone, pas sur un serveur.**

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
- Modèles ML (Gemma, Whisper) importés via SAF — jamais bundlés,
  jamais téléchargés en réseau par l'app.

---

## Fonctionnalités

### Édition Markdown
- Création / édition / auto-save debounced
- Épingler / favoris / archives / corbeille (rétention 30 j)
- Mode clair / sombre / système (palette GitHub)
- Tri configurable (modifié, créé, titre)

### Coffres par dossier
- **Mode passphrase** — Argon2id (m=64 Mo, t=3) + AES-256-GCM, KEK
  enveloppée par la clé maître Keystore (hardware-backed).
- **Mode PIN** — 4 à 6 chiffres, dérivation Argon2id allégée (m=32 Mo,
  t=2) + clé Keystore-bound dédiée par coffre, **auto-wipe à 5
  tentatives échouées** (atomique, repris au boot si crash en cours).
- AAD partout : `folder_id` lié au wrap KEK, `note_id` lié au contenu
  chiffré — anti rejeu / anti substitution.
- HMAC verifier en temps constant pour détecter passphrase incorrecte
  sans déchiffrer toutes les notes.
- Auto-lock configurable (15 min par défaut, ou au pause).

### Recherche
- **FTS5** instantané (tokenizer `unicode61`, diacritiques normalisés).
- **Recherche sémantique on-device** *(opt-in)* via
  `all-MiniLM-L6-v2-quant.onnx` (~22 Mo, bundlé) — trouve des notes
  proches par le sens, cross-langue FR/EN.
- Repli automatique sur encodeur n-grammes local si MiniLM est désactivé.

### Q&A « Demander à mes notes »
- **Gemma 3 1B int4** (~530 Mo, importé via SAF), inférence MediaPipe.
- **RAG** : top-K sémantique injecté dans un prompt durci (délimiteurs
  `<note>` + sanitisation anti-injection).
- Streaming token-par-token, conversation effaçable, **aucun envoi
  réseau**.
- Vérification SHA-256 obligatoire à l'import (rejet d'un `.task` non
  vérifié, override explicite réservé aux utilisateurs avertis).

### Dictée vocale Whisper
- **Whisper on-device** via le module sibling `files_tech_voice`.
- Modèles Whisper Base q5_1 (57 Mo) ou Tiny q5_1 (32 Mo), importés via
  SAF (téléchargement par le navigateur système, pas par l'app).
- Vérification SHA-256 systématique avant chargement, cache TTL 30 j.
- Audio jamais persisté (tmp + delete dans tous les chemins).
- **MlMemoryGuard** : mutex sériel Gemma ↔ Whisper anti-OOM sur 4 Go RAM.

### Backlinks
- Liens `[[Titre]]`, auto-complétion, panneau Mentions / liens sortants.
- **Réindexation différée 2 s** : seule la note modifiée est retraitée
  (O(1) par save, batching transparent).
- Liens fantômes auto-résolus à la création / au renommage de la cible.

### Export Markdown
- Export d'une note : `.md` avec frontmatter YAML compatible Obsidian,
  Logseq, Bear, Foam, Dendron.
- Export ZIP global : arborescence par dossier + README d'export.
- Encodage en isolate (`compute()`), nom de fichier durci anti-path-
  traversal et anti-Unicode-bidi.

### Mode panique
- Réglages → Mode panique → Tout effacer maintenant.
- Confirmation par mot tapé (`EFFACER`).
- Séquence **ordonnée et best-effort** (un step qui throw n'interrompt
  pas les suivants) :
  1. `FLAG_SECURE` forcé ON
  2. Capture micro coupée
  3. **`foldersLockAll`** — verrouille tous les coffres ouverts
  4. **`pinKeysWipe`** — supprime toutes les clés Keystore PIN
     (`deleteKeysWithPrefix` côté Kotlin)
  5. **`kekDestroy`** — détruit la clé maître Keystore (DB
     instantanément illisible)
  6. Pause des background workers
  7. **`dbWipe`** — écrase header SQLCipher 16 Mo + delete + sidecars
  8. Effacement Whisper, Gemma, préférences, tmp

### FLAG_SECURE
- Activé par défaut : pas de capture d'écran ni d'aperçu dans les apps
  récentes.

---

## Sécurité

- **DB SQLCipher** chiffrée AES-256-GCM, clé maître scellée par
  AndroidKeystore (hardware-backed sur S24).
- **KEK Keystore-bound CSPRNG 32 octets**, dérivation Argon2id côté
  passphrase, scellage Keystore direct côté PIN.
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

Voir [`SECURITY.md`](SECURITY.md) pour le modèle de menace complet et
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

À auditer sur chaque release via `aapt dump permissions`.

---

## Installation

Ce dépôt **n'a pas de pipeline de release CI automatique**. Deux options :

1. **Build local** (recommandé pour audit) — voir section suivante.
2. **APK release manuelle** : récupérer le dernier `.apk` publié sur
   [GitHub Releases](https://github.com/gitubpatrice/notes_tech/releases)
   (v0.9.4), vérifier la signature, side-loader.

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

APK release arm64 : ~327 Mo (MiniLM ONNX bundlé, runtimes ML, SQLCipher
— Gemma et Whisper téléchargés séparément, non bundlés).

Pré-requis :
- Flutter 3.x (Dart `^3.11.5`)
- Android SDK + NDK installés via Android Studio
- Module sibling `files_tech_voice` à `../files_tech_voice` (clone
  [le repo](https://github.com/gitubpatrice/files_tech_voice) à côté
  de `notes_tech/`)

---

## Architecture

```
lib/
├── main.dart                          # bootstrap parallèle + DI Provider
├── app.dart                           # MaterialApp
├── core/                              # constants, exceptions, theme
├── data/
│   ├── models/                        # Note, Folder, NoteEmbedding,
│   │                                    NoteLink, NoteChangeEvent
│   ├── db/                            # SQLite (FTS5 + sqlcipher), DAOs
│   └── repositories/                  # façades + streams typés
├── services/
│   ├── embedding/                     # EmbeddingProvider, LocalEmbedder,
│   │                                    MiniLmEmbedder, BertTokenizer
│   ├── ai/                            # GemmaService (SHA-256), RagService
│   ├── security/                      # VaultService (KEK Keystore +
│   │                                    passphrase/PIN), PanicService
│   ├── secure_window_service.dart     # FLAG_SECURE via MethodChannel
│   ├── indexing_service.dart          # worker idempotent (hash diff)
│   ├── embedder_coordinator.dart      # swap Local ↔ MiniLM à chaud
│   ├── semantic_search_service.dart   # top-K cosine, cache invalidé
│   ├── backlinks_service.dart         # parsing [[]], reindex différé 2s
│   ├── note_actions.dart              # actions UI réutilisables
│   └── settings_service.dart
├── ui/
│   ├── screens/                       # home, editor, search, ai_chat,
│   │                                    settings, about, vault_unlock
│   └── widgets/                       # NoteCard, BacklinksPanel, ...
└── utils/                             # debouncer, hash_utils, vector_math
```

## Stack

- Flutter 3.x / Dart `^3.11.5`
- `sqflite_sqlcipher` (SQLite chiffré AES-256 + FTS5)
- `flutter_secure_storage` (KEK scellée AndroidKeystore)
- `cryptography` (Argon2id RFC 9106 + AES-GCM, Dart pur)
- `crypto` (SHA-256 streaming pour vérification modèles)
- `onnxruntime` (MiniLM L6 v2 quantifié)
- `flutter_gemma` (Gemma 3 1B int4)
- `files_tech_voice` (sibling, Whisper STT)
- `provider`, `shared_preferences`, `archive`, `share_plus`,
  `url_launcher`
- **Aucune dépendance réseau**

## Cible

- Samsung Galaxy S24 / S24 FE (validés)
- Samsung S9, POCO C75 (validés en mode dégradé)
- minSdk 23 (Android 6+)

---

## Licence

[Apache License 2.0](LICENSE) — voir aussi [`NOTICE`](NOTICE) et
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Suite Files Tech

Notes Tech fait partie de la suite Files Tech (toutes 100 % locales) :
- [PDF Tech](https://github.com/gitubpatrice/PDF-TECH)
- [Read Files Tech](https://github.com/gitubpatrice/READ-FILES-TECH)
- [AI Tech](https://github.com/gitubpatrice/ai_tech)
- [Pass Tech](https://github.com/gitubpatrice/pass_tech)
