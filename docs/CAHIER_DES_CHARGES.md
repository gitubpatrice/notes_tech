# Notes Tech — Cahier des charges

> Document de référence — version initiale.
> Repository (à créer) : `gitubpatrice/notes_tech`
> Licence : Apache 2.0
> Dossier projet : `J:\applications\notes_tech`

---

## 🎯 Positionnement

**"Vos notes restent dans votre poche. L'IA aussi."**

Cible : penseurs, thérapeutes, étudiants, chercheurs, écrivains, journalistes — toute personne qui prend des notes sensibles ou denses et veut les **comprendre**, pas juste les **stocker**.

Différenciateur unique vs Notesnook / Obsidian / Bear / Logseq :
> **L'IA tourne dans le téléphone, pas sur un serveur.**

---

## 🔒 Principes non-négociables (ADN Files Tech)

1. **100% offline** — pas de sync cloud, pas de compte, pas de serveur
2. **Zéro tracker, zéro analytics, zéro pub**
3. **Apache 2.0** sur `gitubpatrice/notes_tech`
4. **Vault chiffré** Argon2id+AES-GCM (réutilise Pass Tech v2)
5. **Pas de permission `INTERNET`** dans le manifest — preuve auditable

---

## ⭐ Fonctionnalités exclusives (le kill set)

### 1. RAG local sur toutes les notes (cœur du produit)
- Embeddings via modèle léger on-device (`all-MiniLM-L6-v2` quantifié, ~25 Mo)
- Index vectoriel local (sqlite-vec ou Hive + cosine maison)
- Recherche sémantique : *"où ai-je parlé d'attachement insécure ?"* → trouve même sans le mot exact
- Indexation incrémentale en arrière-plan, jamais bloquante

### 2. Q&A et synthèse via AI Tech (Gemma 3 1B int4)
- *"Résume mes notes de la semaine"*
- *"Quels patients ont mentionné des troubles du sommeil ?"*
- *"Compare ce que j'ai écrit sur X il y a 6 mois et aujourd'hui"*
- *"Réécris ce paragraphe en plus clair"*
- *"Sors-moi les actions à faire de cette semaine"*

### 3. Liens automatiques entre notes (à la Obsidian, sans config)
- Détection de concepts récurrents → suggestions `[[liens]]` automatiques
- Graphe de connaissance local (visualisation force-directed)
- *"Notes proches de celle-ci"* basé sur similarité d'embeddings

### 4. Tags intelligents générés localement
- L'IA propose 3-5 tags pertinents à la création de la note
- Acceptation / correction → l'app apprend le style
- Aucun tag envoyé nulle part

### 5. Mode "Journal thérapeute / praticien" (cible Altawayama)
- Template par patient/client avec champs personnalisés
- Recherche : *"qu'est-ce que Marie m'a dit sur sa mère ?"*
- Stats : fréquence des thèmes, évolution émotionnelle (analyse sentiment locale)
- Export PDF de la séance en un tap (via PDF Tech core)

### 6. Capture multimodale → texte
- Note vocale → transcription Whisper locale (synergie **Voice Tech**)
- Photo → OCR local (ML Kit, déjà utilisé dans PDF Tech)
- PDF importé → extraction texte + RAG (synergie **PDF Tech / Read Files Tech**)
- **Tout converge dans la même base recherchable.**

### 7. Markdown moderne sans friction
- WYSIWYG-like : rendu visible mais syntaxe `#` apparaît si curseur dessus (hybrid mode)
- Tables, code blocks avec coloration syntaxique, math LaTeX local (KaTeX)
- Slash commands `/todo`, `/quote`, `/code`, `/date`
- Backlinks automatiques (qui mentionne cette note ?)

### 8. Vault par dossier (granularité)
- Verrouillage par dossier (ex : "Patients" verrouillé, "Recettes" ouvert)
- Biométrie ou mot de passe par vault
- Mode "panique" : tap long sur l'icône → bascule sur un faux contenu vide

### 9. Historique versionné local
- Chaque note garde ses N dernières versions (git-like, sans git)
- Diff visuel entre versions
- Restauration en un tap
- Aucune perte possible même après mauvaise IA

### 10. Export portable et archivable
- Export complet : Markdown plain + ZIP chiffré du vault
- Compatible Obsidian (dossier Markdown standard) → départ libre, zéro lock-in
- Import : Notesnook, Obsidian, Bear (JSON), Apple Notes (HTML), Google Keep (Takeout)

---

## 🛠 Fonctionnalités "table stakes" (obligatoires)

- Carnets / dossiers / sous-dossiers
- Favoris, épingler, archiver, corbeille (30j)
- Recherche full-text instantanée (FTS5 SQLite)
- Mode sombre GitHub (cohérence suite Files Tech)
- Widget écran d'accueil : note rapide + dernières notes
- Partage (toujours user-initiated)
- Tri : modif / création / titre / longueur
- Multi-sélection : tag bulk, déplacer bulk, supprimer bulk
- Pièces jointes locales (images, PDF, audio)

---

## 🚫 Hors scope (assumé)

- ❌ Pas de sync cloud (export ZIP chiffré + Syncthing/USB en alternative)
- ❌ Pas de collaboration temps réel
- ❌ Pas de plugins tiers (sécurité, promesse offline)
- ❌ Pas de version desktop dans un premier temps

---

## 📦 Stack technique cible

```yaml
flutter: ^3.41
flutter_quill: ^10.x          # éditeur Markdown WYSIWYG
sqflite_sqlcipher: ^x.x       # FTS5 + chiffré
flutter_secure_storage: ^9.x  # KEK Keystore-bound
cryptography: ^2.x            # Argon2id / AES-GCM
local_auth: ^2.x              # biométrie

# IA on-device
flutter_gemma: ^x.x           # Gemma 3 1B int4
onnxruntime: ^1.x             # MiniLM embeddings

# Capture multimodale
google_mlkit_text_recognition # OCR
syncfusion_flutter_pdf        # extraction PDF

# Visu graphe
graphview: ^x.x               # force-directed graph

# Partagé
files_tech_core: ^x.x         # thème, vault crypto, update service
```

---

## 🏗 Architecture cible

```
notes_tech/
├── lib/
│   ├── main.dart
│   ├── app.dart                          # NotesTechApp (theme + routes)
│   ├── core/
│   │   ├── constants.dart                # versions, clés SharedPrefs
│   │   ├── exceptions.dart
│   │   └── extensions.dart
│   ├── data/
│   │   ├── models/
│   │   │   ├── note.dart                 # Note (id, title, content, tags, ...)
│   │   │   ├── folder.dart
│   │   │   ├── attachment.dart
│   │   │   ├── note_version.dart
│   │   │   └── embedding.dart
│   │   ├── db/
│   │   │   ├── database.dart             # ouverture SQLCipher + migrations
│   │   │   ├── notes_dao.dart            # CRUD + FTS5
│   │   │   ├── folders_dao.dart
│   │   │   ├── versions_dao.dart
│   │   │   └── embeddings_dao.dart
│   │   └── repositories/
│   │       ├── notes_repository.dart
│   │       ├── folders_repository.dart
│   │       └── search_repository.dart
│   ├── services/
│   │   ├── vault_service.dart            # déverrouillage, mode panique
│   │   ├── crypto_service.dart           # Argon2id + AES-GCM (réutilise core)
│   │   ├── embedding_service.dart        # MiniLM ONNX
│   │   ├── ai_service.dart               # Gemma 3 1B (résumé, Q&A, tags)
│   │   ├── ocr_service.dart              # ML Kit
│   │   ├── pdf_import_service.dart       # extraction texte
│   │   ├── voice_import_service.dart     # bridge Voice Tech
│   │   ├── export_service.dart           # MD / ZIP chiffré / PDF
│   │   ├── import_service.dart           # Obsidian / Notesnook / Bear / Keep
│   │   ├── backlinks_service.dart
│   │   ├── versioning_service.dart
│   │   └── update_service.dart           # vérif GitHub Releases
│   ├── ui/
│   │   ├── screens/
│   │   │   ├── home_screen.dart
│   │   │   ├── note_editor_screen.dart
│   │   │   ├── folder_screen.dart
│   │   │   ├── search_screen.dart
│   │   │   ├── ai_chat_screen.dart       # Q&A sur le corpus
│   │   │   ├── graph_screen.dart
│   │   │   ├── vault_unlock_screen.dart
│   │   │   ├── settings_screen.dart
│   │   │   └── about_screen.dart
│   │   ├── widgets/
│   │   │   ├── note_card.dart
│   │   │   ├── markdown_editor.dart      # hybrid WYSIWYG
│   │   │   ├── tag_chip.dart
│   │   │   ├── backlink_panel.dart
│   │   │   ├── version_diff_view.dart
│   │   │   └── empty_state.dart
│   │   └── theme/
│   │       └── app_theme.dart            # délègue à files_tech_core
│   └── utils/
│       ├── markdown_parser.dart
│       ├── cosine_similarity.dart
│       └── debouncer.dart
├── assets/
│   └── models/                           # téléchargés à la 1re ouverture
└── android/
    └── app/src/main/AndroidManifest.xml  # SANS android.permission.INTERNET
```

---

## 🔐 Sécurité — règles d'or

1. **Pas de `<uses-permission android:name="android.permission.INTERNET" />`** dans le manifest. Audit visuel possible par n'importe qui.
2. **Vault chiffré** : KEK dérivée par Argon2id (paramètres Pass Tech v2), stockée wrappée par Keystore Android. DEK AES-GCM aléatoire par note.
3. **AAD** sur chaque chiffrement : `note_id || version || timestamp` pour empêcher rejeu/swap.
4. **`allowBackup=false`** + `dataExtractionRules` pour bloquer adb backup.
5. **Pas de logs en release** (kReleaseMode → no-op).
6. **TextField** notes sensibles : `enableSuggestions:false`, `autocorrect:false` (anti-IME cloud).
7. **FLAG_SECURE** activable par l'utilisateur (anti-screenshot).
8. **Wipe mémoire** des Uint8List sensibles après usage (`fillRange(0, len, 0)`).
9. **Versionning** : chaque édition crée une version chiffrée distincte, retention configurable.
10. **Export ZIP** : toujours chiffré par défaut, password user obligatoire.

---

## 📅 Roadmap

| Version | Contenu | Effort |
|---------|---------|--------|
| **v0.1** | Éditeur Markdown + dossiers + FTS5 + thème + vault basique | ~1 semaine |
| **v0.2** | Embeddings MiniLM + recherche sémantique | +3-4 jours |
| **v0.3** | Intégration Gemma 3 1B (Q&A, résumé, tags auto) | +1 semaine |
| **v0.4** | Backlinks + graphe + versioning | +4-5 jours |
| **v0.5** | Mode "Journal praticien" + export PDF par séance | +3-4 jours |
| **v1.0** | Capture multimodale (Voice Tech / PDF Tech / OCR) + vault par dossier | +1 semaine |
| **v1.1** | Import Obsidian/Notesnook/Apple Notes + mode panique | +3-4 jours |

---

## 🪐 Synergie suite Files Tech

| Action | App qui agit | Résultat |
|---|---|---|
| Dicter une idée | **Voice Tech** | Crée une note dans Notes Tech |
| Scanner un livre | **PDF Tech** | OCR → note avec source |
| Lire un fichier reçu | **Read Files Tech** | "Sauver dans Notes Tech" |
| Mots de passe d'une note | **Pass Tech** | Référence chiffrée |
| Q&A sur tout le corpus | **AI Tech** | Réponses RAG cross-app |

Notes Tech est le **hub** où tout converge.

---

## 🎯 Pitch en 1 phrase

> *"Notes Tech : Obsidian + Notesnook + ChatGPT, sans serveur, sans compte, sans pub, sans même la permission internet."*

---

## ⚡ Performance — exigences chiffrées

| Mesure | Cible | Méthode |
|---|---|---|
| Cold start (S24) | < 800 ms jusqu'à 1ère frame | `--trace-startup`, lazy init des services lourds |
| Ouverture éditeur (note 10 ko) | < 150 ms | Pas de parse MD synchrone, render incrémental |
| Recherche FTS sur 10 000 notes | < 100 ms | Index FTS5 + LIMIT, pas de SELECT * |
| Recherche sémantique (RAG) | < 500 ms | Embeddings pré-calculés, cosine vectorisé `Float32List` |
| Encodage embedding (MiniLM) | < 50 ms / note | ONNX en `Isolate.run`, tokenizer en cache |
| Génération IA (Gemma 1B) | streaming visible < 500 ms TTFB | Modèle int4, contexte tronqué intelligemment |
| Frame rate UI | 120 fps soutenu (S24) | `RepaintBoundary` ciblés, `const` partout, `ListView.builder` |
| Mémoire au repos | < 150 Mo | Modèles déchargés après usage, dispose strict |
| APK arm64 | < 60 Mo (hors modèles) | `--split-per-abi`, `--obfuscate`, ProGuard agressif |
| Indexation 1 000 notes au démarrage | jamais sur main thread | Worker isolate, queue persistée, reprise après kill |

### Règles perf non-négociables

1. **Tout ce qui dépasse 16 ms part en isolate** (parsing MD volumineux, embeddings, exports, imports, OCR).
2. **Pas de `setState` dans une boucle** — batcher en fin de frame.
3. **DB** : transactions groupées pour les writes en lot, prepared statements partout.
4. **Images** : `cacheWidth/cacheHeight` systématique sur les pièces jointes, pas de décodage UI thread.
5. **ListView** : toujours `.builder`, jamais `Column` + `map` pour des listes longues.
6. **`const` widgets** partout où c'est possible (lint `prefer_const_constructors` en error).
7. **Lazy services** : `get_it` lazy ou providers `family` — Gemma chargé seulement à la 1re requête IA.
8. **OPcache mental** : éviter les rebuilds inutiles, `Selector` plutôt que `Consumer` global.
9. **Profilage avant release** : DevTools timeline + memory + frame chart, sur S24 réel.
10. **Bench unitaire** sur les hot paths (FTS, cosine, parser MD) — détecter régression CI.

---

## 📋 Standards de code (non-négociables)

- **Code propre, structuré, cohérent.**
- **Branchements parfaits** (DI explicite, pas de singletons cachés).
- **Logique parfaite** : pas de double exécution, pas de race conditions, pas de leaks.
- **Sécurité d'abord** : tout input externe est validé, tout chiffrement est AEAD avec AAD.
- **Maintenabilité** : un fichier = une responsabilité, fonctions < 50 lignes idéalement.
- **Pas de TODO laissés en release.** Pas de code mort.
- **Tests** sur les services critiques (crypto, embedding, parser MD, FTS).
- **CI GitHub Actions** : analyze + format + test + build APK sur push (calque Files Tech).
- **Conventions de commit** : `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`.
