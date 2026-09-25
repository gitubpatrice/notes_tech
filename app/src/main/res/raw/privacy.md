# Privacy policy — Notes Tech

**Version 1.2.0 — September 2026**

## In one sentence

Notes Tech does not collect, transmit or store any data on remote servers. Everything stays on your phone, and the database is encrypted at-rest.

## Detail

### Data processed

- **Your Markdown notes**: generated and kept exclusively on your phone, in a SQLite database encrypted by **SQLCipher** with a unique key generated locally (32-byte KEK) stored in the **Android Keystore**.
- **Per-folder vaults**: each vault you enable uses a distinct **passphrase** or **PIN**, derived through **Argon2id RFC 9106** (m=64MB, t=3 for passphrase; lighter for PIN, compensated by device-bound Keystore sealing). Locked note content is encrypted with **AES-256-GCM**, AAD bound to `note_id`.
- **App lock (optional)**: the PIN itself is never stored. What is kept is a value derived from it with **Argon2id** and bound by **HMAC-SHA256** to a key of the **Android Keystore** that never leaves the phone — so it cannot be checked anywhere else. After five wrong PINs, each new attempt waits longer (30 seconds, doubling up to one hour); wrong attempts never erase anything. **Fingerprint or face unlock** is handled entirely by Android: Notes Tech receives no biometric data, only the confirmation that a Keystore key was allowed to work, and only strong (Class 3) biometrics are accepted.
- **Backlinks `[[Title]]`**: local inverted index, never transmitted.
- **Voice dictation model (Whisper `.bin`)**: you obtain it yourself — the app shows the file name and its source — then import it through the Android document picker. Notes Tech has no Internet permission and **exposes no way to download anything**. Its SHA-256 is verified on import and before every load.
- **Audio captured during dictation**: written to a temporary file in the app's private storage — the transcription engine reads a file, there is no way around it — then **wiped as soon as the transcription is returned**. It is also wiped on app start and by panic mode, so an abrupt shutdown leaves nothing behind.
- **Settings (theme, sort, dictation enabled, vault auto-lock, app lock options)**: stored in clear in local preferences (no sensitive data).

### Data NOT processed

- **No telemetry**, no analytics, no third-party crash reporter.
- **No advertising**, no tracker.
- **No user account**, no online service connection.

### Android permissions requested

Notes Tech requests **NO `INTERNET` permission**. The app is technically unable to communicate with a remote server. This absence can be verified in the source repo `AndroidManifest.xml` (`tools:node="remove"` on INTERNET and ACCESS_NETWORK_STATE).

Active permissions are strictly utilitarian:
- `RECORD_AUDIO` (dictation: audio is written to a private temporary file, then wiped as soon as the transcription is returned).
- `USE_BIOMETRIC` and `USE_FINGERPRINT` (optional fingerprint or face unlock of the app lock; declared by the AndroidX biometric library — `USE_FINGERPRINT` is the name the same permission has on Android 8 and older).

These are the **only** permissions requested. Picking the model file goes through the Android document picker, which requires none.

### Panic mode

The **Settings → Panic mode** menu wipes in bulk:
- the encrypted SQLite database (all notes),
- the SQLCipher KEK (unrecoverable),
- the Keystore keys associated with PIN vaults,
- the Keystore keys of the app lock (PIN verification and biometric unlock),
- the per-folder vaults (passphrases and PINs),
- the clipboard, where a copied note sits in the clear,
- export archives and dictation recordings, the app's only cleartext files,
- the Whisper model installed in the sandbox,
- the preferences (except `db_encrypted_v1` and `secure_window_enabled` kept for restart consistency).

The wipe is **not atomic**, and the step order is designed around that: the encryption key is destroyed **before** the long erasures, then the cleartext files, then the rest. An abrupt shutdown at any instant therefore leaves the safest state reachable at that instant — at worst a database reduced to noise. The final screen states what failed, and **whether anything readable may remain**.

### Your rights

Your data stays on your phone, under your sole control, and the publisher has no way to access it, and therefore processes no personal data about you. This is what the CNIL, the French data protection authority, describes as "software simply made available to the user", to which the GDPR does not apply (recommendation on mobile apps, section 3.3). You remain in control of your data and may at any time:
- export your notes in Markdown or ZIP (`Settings → Export`),
- delete all data via panic mode,
- uninstall the app — Android will automatically delete all private data.

### Subprocessors

**None.** Notes Tech uses no third-party service at runtime.

### Voice dictation model

- **Whisper** (`.bin` models from `ggerganov/whisper.cpp`): MIT license.

The file you load stays on your phone. Notes Tech merely runs it locally, using the `whisper.cpp` engine **bundled in the app** — its MIT license is reproduced in the terms of use.

### Language

This policy was written in French; the versions in other languages are translations. Should they differ, **the French version prevails**.

### Contact

For any question: **contact@files-tech.com**

---

Notes Tech is published by **Patrice Haltaya**. Source code published under **Apache 2.0** license.
