# Privacy policy — Notes Tech

**Version 1.0.0 — May 2026**

## In one sentence

Notes Tech does not collect, transmit or store any data on remote servers. Everything stays on your phone, and the database is encrypted at-rest.

## Detail

### Data processed

- **Your Markdown notes**: generated and kept exclusively on your phone, in a SQLite database encrypted by **SQLCipher** with a unique key generated locally (32-byte KEK) stored in the **Android Keystore**.
- **Per-folder vaults**: each vault you enable uses a distinct **passphrase** or **PIN**, derived through **Argon2id RFC 9106** (m=64MB, t=3 for passphrase; lighter for PIN, compensated by device-bound Keystore sealing). Locked note content is encrypted with **AES-256-GCM**, AAD bound to `note_id`.
- **Backlinks `[[Title]]`**: local inverted index, never transmitted.
- **Voice dictation model (Whisper `.bin`)**: you obtain it yourself — the app shows the file name and its source — then import it through the Android document picker. Notes Tech has no Internet permission and **exposes no way to download anything**. Its SHA-256 is verified on import and before every load.
- **Audio captured during dictation**: written to a temporary file in the app's private storage — the transcription engine reads a file, there is no way around it — then **wiped as soon as the transcription is returned**. It is also wiped on app start and by panic mode, so an abrupt shutdown leaves nothing behind.
- **Settings (theme, sort, dictation enabled, vault auto-lock)**: stored in clear in local preferences (no sensitive data).

### Data NOT processed

- **No telemetry**, no analytics, no third-party crash reporter.
- **No advertising**, no tracker.
- **No user account**, no online service connection.

### Android permissions requested

Notes Tech requests **NO `INTERNET` permission**. The app is technically unable to communicate with a remote server. This absence can be verified in the source repo `AndroidManifest.xml` (`tools:node="remove"` on INTERNET and ACCESS_NETWORK_STATE).

Active permissions are strictly utilitarian:
- `RECORD_AUDIO` (dictation: audio is written to a private temporary file, then wiped as soon as the transcription is returned).

This is the **only** permission requested. Picking the model file goes through the Android document picker, which requires none.

### Panic mode

The **Settings → Panic mode** menu wipes in bulk:
- the encrypted SQLite database (all notes),
- the SQLCipher KEK (unrecoverable),
- the Keystore keys associated with PIN vaults,
- the per-folder vaults (passphrases and PINs),
- the clipboard, where a copied note sits in the clear,
- export archives and dictation recordings, the app's only cleartext files,
- the Whisper model installed in the sandbox,
- the preferences (except `db_encrypted_v1` and `secure_window_enabled` kept for restart consistency).

The wipe is **not atomic**, and the step order is designed around that: the encryption key is destroyed **before** the long erasures, then the cleartext files, then the rest. An abrupt shutdown at any instant therefore leaves the safest state reachable at that instant — at worst a database reduced to noise. The final screen states what failed, and **whether anything readable may remain**.

### Your rights

All data being strictly local, the GDPR applies between you and your phone. You may at any time:
- export your notes in Markdown or ZIP (`Settings → Export`),
- delete all data via panic mode,
- uninstall the app — Android will automatically delete all private data.

### Subprocessors

**None.** Notes Tech uses no third-party service at runtime.

### Voice dictation model

- **Whisper** (`.bin` models from `ggerganov/whisper.cpp`): MIT license.

The file you load stays on your phone. Notes Tech merely runs it locally, using the `whisper.cpp` engine **bundled in the app** — its MIT license is reproduced in the terms of use.

### Contact

For any question: **contact@files-tech.com**

---

Notes Tech is published by **Patrice Haltaya**. Source code published under **Apache 2.0** license.
