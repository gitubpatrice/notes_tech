# Privacy policy — Notes Tech

**Version 1.0.0 — May 2026**

## In one sentence

Notes Tech does not collect, transmit or store any data on remote servers. Everything stays on your phone, and the database is encrypted at-rest.

## Detail

### Data processed

- **Your Markdown notes**: generated and kept exclusively on your phone, in a SQLite database encrypted by **SQLCipher** with a unique key generated locally (32-byte KEK) stored in the **Android Keystore** via `flutter_secure_storage`.
- **Per-folder vaults**: each vault you enable uses a distinct **passphrase** or **PIN**, derived through **Argon2id RFC 9106** (m=64MB, t=3 for passphrase; lighter for PIN, compensated by device-bound Keystore sealing). Locked note content is encrypted with **AES-256-GCM**, AAD bound to `note_id`.
- **Backlinks `[[Title]]`**: local inverted index, never transmitted.
- **Semantic search embeddings**: vectors computed locally (light encoder or optional quantized MiniLM-L6-v2), stored as BLOB in the same encrypted database.
- **AI models (Gemma `.task`, Whisper `.bin`)**: downloaded by your **system browser** from Kaggle / HuggingFace (Notes Tech merely fires an `ACTION_VIEW` intent), then imported manually. Notes Tech has no Internet permission and downloads nothing itself.
- **Audio captured during dictation**: transcribed and **immediately wiped**. Never persisted.
- **Settings (theme, sort, dictation enabled, vault auto-lock)**: stored in clear in local preferences (no sensitive data).

### Data NOT processed

- **No telemetry**, no analytics, no third-party crash reporter.
- **No advertising**, no tracker.
- **No user account**, no online service connection.

### Android permissions requested

Notes Tech requests **NO `INTERNET` permission**. The app is technically unable to communicate with a remote server. This absence can be verified in the source repo `AndroidManifest.xml` (`tools:node="remove"` on INTERNET and ACCESS_NETWORK_STATE).

Active permissions are strictly utilitarian:
- `READ_EXTERNAL_STORAGE` / Storage Access Framework (select `.task` and `.bin` model files).
- `RECORD_AUDIO` (Whisper dictation, audio never persisted).

### Panic mode

The **Settings → Panic mode** menu wipes in bulk and atomically:
- the encrypted SQLite database (all notes),
- the SQLCipher KEK (unrecoverable),
- the Keystore keys associated with PIN vaults,
- the per-folder vaults (passphrases and PINs),
- the Gemma and Whisper models installed in the sandbox,
- the preferences (except `db_encrypted_v1` and `secure_window_enabled` kept for restart consistency).

The wipe is atomic and resumable: if a crash occurs mid-wipe, the next start completes the remaining steps (`vault_wipe_pending_*`).

### Your rights

All data being strictly local, the GDPR applies between you and your phone. You may at any time:
- export your notes in Markdown or ZIP (`Settings → Export`),
- delete all data via panic mode,
- uninstall the app — Android will automatically delete all private data.

### Subprocessors

**None.** Notes Tech uses no third-party service at runtime.

### AI models

- **Gemma 3 1B int4** (~530 MB): Google's Gemma license. See https://ai.google.dev/gemma/terms
- **Whisper** (`.bin` models from `ggerganov/whisper.cpp`): MIT license.

The files you load stay on your phone. Notes Tech merely runs them locally (MediaPipe LLM Inference for Gemma, whisper.cpp via `files_tech_voice` for dictation).

### Contact

For any question: **contact@files-tech.com**

---

Notes Tech is published by a **French sole proprietorship** (SIRET available on request). Source code published under **Apache 2.0** license.
