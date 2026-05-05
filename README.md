# Notes Tech

> Vos notes restent dans votre poche. L'IA aussi.

Application Android Flutter de prise de notes **100% locale**, conçue pour les penseurs, thérapeutes, étudiants, chercheurs, écrivains et journalistes qui veulent prendre des notes sensibles ou denses sans qu'elles ne quittent jamais leur téléphone.

Différenciateur unique vs Notesnook / Obsidian / Bear / Logseq :
**l'IA tourne dans le téléphone, pas sur un serveur.**

---

## 🔒 Promesse de confidentialité

- Aucune permission `INTERNET` dans le manifeste — vérifiable à l'œil nu
- Aucun compte, aucune inscription
- Aucun tracker, aucune publicité
- Aucune télémétrie
- Open source Apache 2.0
- `allowBackup=false` + `dataExtractionRules` complet (pas d'exfiltration via Smart Switch ou Android Backup)

---

## ✨ Fonctionnalités v0.2

- Création / édition de notes Markdown
- Auto-save debounced 500 ms
- Recherche plein texte instantanée (SQLite FTS5, tokenizer `unicode61`, accents normalisés)
- **Recherche par similarité** (embeddings vectoriels locaux, cosine top-K)
  → trouve des notes proches même sans le mot exact (tolère fautes / conjugaisons / synonymes morphologiques)
- Indexation incrémentale en arrière-plan, idempotente, sans permission réseau
- Épingler / favoris / corbeille (rétention 30 jours)
- Mode clair / sombre / système (palette GitHub)
- Tri configurable (modifié, créé, titre)

## 🛣 Roadmap

| Version | Contenu |
|---------|---------|
| ✅ **v0.1** | Éditeur Markdown + FTS5 + thème + corbeille |
| ✅ **v0.2** | Recherche par similarité (encodeur local + cosine) |
| **v0.2.1** | Bascule encodeur MiniLM ONNX (vraie recherche sémantique cross-langue) |
| **v0.3** | Gemma 3 1B int4 (Q&A, résumé, tags auto) |
| **v0.4** | Backlinks + graphe + versioning + vault Argon2id+AES-GCM |
| **v0.5** | Mode "Journal praticien" + export PDF par séance |
| **v1.0** | Capture multimodale (Voice Tech / PDF Tech / OCR) + vault par dossier |
| **v1.1** | Import Obsidian / Notesnook / Apple Notes + mode panique |

---

## 🏗 Architecture

```
lib/
├── main.dart                       # init parallèle + DI Provider
├── app.dart                        # MaterialApp
├── core/                           # constants, exceptions, theme
├── data/
│   ├── models/                     # Note, Folder, NoteEmbedding
│   ├── db/                         # database (SQLite + FTS5), DAOs
│   └── repositories/               # façades + streams de changement
├── services/
│   ├── embedding/                  # EmbeddingProvider, LocalEmbedder
│   ├── indexing_service.dart       # worker incrémental, idempotent
│   ├── semantic_search_service.dart # top-K cosine, cache invalidé
│   └── settings_service.dart
├── ui/
│   ├── screens/                    # home, editor, search, settings, about
│   └── widgets/                    # NoteCard, EmptyState
└── utils/                          # debouncer, vector_math
```

## 🛠 Stack

- Flutter 3.41 / Dart 3.x
- `sqflite` + FTS5 (tri composite, index couvrants)
- `provider` pour l'injection de dépendances
- `shared_preferences` pour la configuration
- Aucune dépendance réseau

## 🚀 Build

```bash
flutter pub get
flutter analyze
flutter build apk --release --split-per-abi --obfuscate --split-debug-info=build/symbols
```

APK release arm64 actuel : ~17 Mo (R8 + obfuscation actifs).

## 📱 Cible

- Samsung Galaxy S24 (validé)
- minSdk 23 (Android 6+)
- Distribution side-load APK GitHub Releases — pas de Play Store

## 📜 Licence

Apache 2.0 — voir [LICENSE](LICENSE).

## 🪐 Suite Files Tech

Notes Tech fait partie de la suite Files Tech (toutes 100% locales) :
- [PDF Tech](https://github.com/gitubpatrice/PDF-TECH)
- [Read Files Tech](https://github.com/gitubpatrice/READ-FILES-TECH)
- [AI Tech](https://github.com/gitubpatrice/ai_tech)
- [Pass Tech](https://github.com/gitubpatrice/pass_tech)
