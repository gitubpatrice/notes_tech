# Modèles on-device

Ce dossier accueillera les modèles d'IA embarqués dans l'APK pour les versions
ultérieures. Aucun modèle n'est nécessaire pour la v0.2 (`LocalEmbedder` est
purement algorithmique).

## v0.2.1 — `all-MiniLM-L6-v2` (recherche sémantique cross-langue)

Fichiers attendus dans ce dossier :

- `all-MiniLM-L6-v2.onnx` (~25 Mo, version quantifiée int8 recommandée)
- `tokenizer.json` (~700 Ko, BERT WordPiece)

### Source recommandée

[Hugging Face — sentence-transformers/all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2)

Le fichier ONNX quantifié est généralement publié sous
`Xenova/all-MiniLM-L6-v2` au format `model_quantized.onnx`.

### Vérification d'intégrité

À ajouter dans `lib/services/embedding/minilm_embedder.dart` quand
le modèle sera intégré :

```dart
// SHA-256 attendu du modèle quantifié — à renseigner après téléchargement.
static const _expectedSha256 = '...';
```

## v0.3 — `gemma-3-1b-it-int4` (Q&A et résumé)

Fichier attendu : `gemma-3-1b-it-int4.task` (~750 Mo) ou variante TFLite.
Source : Google MediaPipe / Hugging Face.

## Règles

- ✅ Tous les modèles **dans l'APK** (assets) — jamais téléchargés à l'exécution.
- ✅ Promesse 100% offline non négociable : pas de permission `INTERNET`.
- ❌ Ne jamais ajouter de modèle externe au repo Git si > 50 Mo (utiliser Git LFS si vraiment nécessaire, sinon l'utilisateur télécharge à l'installation et place les fichiers).
