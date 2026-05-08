# Politique de confidentialité — Notes Tech

**Version 1.0.0 — Mai 2026**

## En une phrase

Notes Tech ne collecte, ne transmet et ne stocke aucune donnée sur des serveurs distants. Tout reste sur votre téléphone, et la base de données est chiffrée at-rest.

## Détail

### Données traitées

- **Vos notes Markdown** : générées et conservées exclusivement sur votre téléphone, dans une base SQLite chiffrée par **SQLCipher** avec une clé unique générée localement (KEK 32 octets) stockée dans le **Android Keystore** via `flutter_secure_storage`.
- **Coffres par dossier** : chaque coffre que vous activez utilise une **passphrase** ou un **PIN** distinct, dérivé via **Argon2id RFC 9106** (m=64MB, t=3 pour passphrase ; allégé pour PIN, compensé par le scellage Keystore device-bound). Le contenu des notes verrouillées est chiffré **AES-256-GCM** avec AAD lié à `note_id`.
- **Backlinks `[[Titre]]`** : index inversé local, jamais transmis.
- **Embeddings de recherche sémantique** : vecteurs calculés localement (encodeur léger ou MiniLM-L6-v2 quantifié optionnel), stockés en BLOB dans la même base chiffrée.
- **Modèles IA (Gemma `.task`, Whisper `.bin`)** : téléchargés par votre **navigateur système** depuis Kaggle / HuggingFace (Notes Tech ouvre simplement un intent `ACTION_VIEW`), puis importés manuellement. Notes Tech n'a pas la permission Internet et ne télécharge rien lui-même.
- **Audio capturé pendant la dictée** : transcrit puis **immédiatement effacé**. Jamais persisté.
- **Préférences (thème, tri, dictée activée, auto-lock coffre)** : stockées en clair dans les préférences locales (pas de donnée sensible).

### Données NON traitées

- **Aucune télémétrie**, aucune analytics, aucun crash reporter tiers.
- **Aucune publicité**, aucun tracker.
- **Aucun compte utilisateur**, aucune connexion à un service en ligne.

### Permissions Android demandées

Notes Tech ne demande **AUCUNE permission `INTERNET`**. L'application est techniquement incapable de communiquer avec un serveur distant. Cette absence est vérifiable dans l'`AndroidManifest.xml` du dépôt source (`tools:node="remove"` sur INTERNET et ACCESS_NETWORK_STATE).

Les permissions actives sont strictement utilitaires :
- `READ_EXTERNAL_STORAGE` / Storage Access Framework (sélectionner les modèles `.task` et `.bin`).
- `RECORD_AUDIO` (dictée Whisper, audio jamais persisté).

### Mode panique

Le menu **Réglages → Mode panique** efface en bloc et de manière atomique :
- la base SQLite chiffrée (toutes les notes),
- la KEK SQLCipher (irrécupérable),
- les clés Keystore associées aux coffres PIN,
- les coffres par dossier (passphrases et PIN),
- les modèles Gemma et Whisper installés dans le sandbox,
- les préférences (sauf `db_encrypted_v1` et `secure_window_enabled` conservées pour cohérence du redémarrage).

Le wipe est atomique et reprenable : si un crash survient au milieu, le redémarrage suivant termine les étapes restantes (`vault_wipe_pending_*`).

### Vos droits

Toutes les données étant strictement locales, le règlement RGPD s'applique entre vous et votre téléphone. Vous pouvez à tout moment :
- exporter vos notes au format Markdown ou ZIP (`Réglages → Exporter`),
- supprimer toutes les données via le mode panique,
- désinstaller l'application — Android supprimera automatiquement toutes les données privées.

### Sous-traitants

**Aucun.** Notes Tech n'utilise aucun service tiers à l'exécution.

### Modèles d'IA

- **Gemma 3 1B int4** (~530 Mo) : licence Gemma de Google. Voir https://ai.google.dev/gemma/terms
- **Whisper** (modèles `.bin` `ggerganov/whisper.cpp`) : licence MIT.

Les fichiers que vous chargez restent sur votre téléphone. Notes Tech ne fait que les exécuter localement (MediaPipe LLM Inference pour Gemma, whisper.cpp via `files_tech_voice` pour la dictée).

### Contact

Pour toute question : **contact@files-tech.com**

---

Notes Tech est édité par une **micro-entreprise française** (SIRET disponible sur demande). Code source publié sous licence **Apache 2.0**.
