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
- Modèle Gemma importé via SAF (jamais bundlé, jamais téléchargé en réseau)

---

## ✨ Fonctionnalités v0.4.1

### Édition
- Notes Markdown — création / édition / auto-save debounced
- Épingler / favoris / archives / corbeille (rétention 30 j)
- Mode clair / sombre / système (palette GitHub)
- Tri configurable (modifié, créé, titre)

### Recherche
- **FTS5** instantané (tokenizer `unicode61`, diacritiques normalisés)
- **Recherche sémantique on-device** *(opt-in, paramètre dédié)* via `all-MiniLM-L6-v2` quantifié int8 (~22 Mo)
  → trouve des notes proches par le sens, même sans le mot exact (cross-langue FR/EN)
  → repli automatique sur encodeur n-grammes local si MiniLM est désactivé

### IA on-device
- **Q&A « Demander à mes notes »** via Gemma 3 1B int4 (~530 Mo, importé via SAF)
- **RAG** : top-K sémantique injecté dans un prompt durci (délimiteurs `<note>` + sanitisation anti-injection)
- Streaming token-par-token, conversation effaçable, **aucun envoi réseau**

### Backlinks
- Liens `[[Titre]]` dans n'importe quelle note
- Auto-complétion de titres existants
- Panneau « Mentions » (rétroliens) + liens sortants
- Réindexation **ciblée** : seule la note modifiée est retraitée (O(1) par save)
- Liens fantômes auto-résolus à la création / au renommage de la note cible

---

## 🛣 Roadmap

| Version | Contenu | État |
|---------|---------|------|
| **v0.1** | Éditeur Markdown + FTS5 + thème + corbeille | ✅ |
| **v0.2 / v0.2.1** | Recherche par similarité (LocalEmbedder + MiniLM ONNX int8) | ✅ |
| **v0.3 / v0.3.x** | Gemma 3 1B int4 — Q&A on-device + RAG + indexation throttlée | ✅ |
| **v0.4** | Backlinks `[[Titre]]` + auto-complétion + panneau mentions | ✅ |
| **v0.4.1** | Audit complet appliqué : réindex ciblé, anti-injection RAG, init order, SAF only, lints stricts | ✅ |
| **v0.5** | Vault Argon2id + AES-GCM scellé Keystore + FLAG_SECURE + versioning notes | ⏳ |
| **v1.0** | Capture multimodale (Voice / PDF / OCR) + vault par dossier + export PDF séance | ⏳ |
| **v1.1** | Import Obsidian / Notesnook / Apple Notes + mode panique | ⏳ |

---

## 🏗 Architecture

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
│   ├── ai/                            # GemmaService, RagService
│   ├── indexing_service.dart          # worker idempotent (hash diff)
│   ├── embedder_coordinator.dart      # swap Local ↔ MiniLM à chaud
│   ├── semantic_search_service.dart   # top-K cosine, cache invalidé
│   ├── backlinks_service.dart         # parsing [[]], résolution, suggestions
│   ├── note_actions.dart              # actions UI réutilisables
│   └── settings_service.dart
├── ui/
│   ├── screens/                       # home, editor, search, ai_chat,
│   │                                    settings, about
│   └── widgets/                       # NoteCard, BacklinksPanel,
│                                        LinkAutocompleteSheet, IndexingBanner,
│                                        EmptyState
└── utils/                             # debouncer, hash_utils, text_utils,
                                          vector_math
```

## 🛠 Stack

- Flutter 3.41 / Dart 3.x
- `sqflite_sqlcipher` (SQLite avec FTS5 garanti, prêt pour le chiffrement v0.5)
- `onnxruntime` (MiniLM L6 v2 quantifié)
- `flutter_gemma` (Gemma 3 1B int4)
- `provider` (DI)
- `shared_preferences` (settings)
- Aucune dépendance réseau

## 🚀 Build

```bash
flutter pub get
flutter analyze
flutter test
flutter build apk --release --split-per-abi --obfuscate --split-debug-info=build/symbols
```

APK release arm64 actuel : ~217 Mo (MiniLM ONNX 22 Mo + onnxruntime ~6 Mo + assets — Gemma téléchargé séparément, non bundlé).

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
