# Notes Tech

> Your notes stay in your pocket. Encrypted, and offline.

🇫🇷 [Version française](README.fr.md)

**v2.0.9 — September 2026** · [Privacy policy](assets/legal/PRIVACY.en.md) · [Terms of use](assets/legal/TERMS.en.md) · [Security](SECURITY.md)

Encrypted Markdown note-taking app for Android, built with Flutter.
**100% local, no Internet permission.** Bilingual interface, **English /
French**. Per-folder vaults (Argon2id passphrase or Keystore-bound PIN),
FTS5 full-text search, on-device Whisper voice dictation, `[[note]]`
backlinks, Markdown preview, multi-step panic mode.

## What's new in v2.0.9

- **Note preview**: an Edit / Preview switch shows headings, lists,
  emphasis and links rendered. A `[[Title]]` link opens the note it points
  to.
- The default folder was created in French ("Boîte de réception") on
  every install. It now follows the app language, and a folder you renamed
  keeps its name.
- Voice model names, microphone and import errors, share texts and the
  export README are translated.
- A PIN vault that cannot be created now says why (no screen lock, or no
  hardware-backed key storage), and messages shown from the folder list
  are no longer hidden behind it.

## What changed in v2.0.0

**On-device AI is gone.** Semantic search (MiniLM) and the "Ask my notes"
Q&A (Gemma 3 1B) are removed. The arm64 APK drops from ~127 MB to
**26.9 MB**. Search remains FTS5 full-text + bm25: fast and exact, but it
does not guess synonyms.

A major bump rather than a minor one, for three reasons:

- the app loses its two flagship features, which a `1.2.0` would not
  signal to users;
- the APK is almost five times smaller, which shows at install time;
- **the migration is one-way**: the database moves to schema v9 and the
  published v1.1.6 has no `onDowngrade`, so reinstalling a 1.x can no
  longer open the database.

This version also introduces **per-ABI split APKs with no universal APK**.
The two families have incompatible `versionCode`s — Flutter's
`+1000 × index` offset puts any universal APK below every split of the
same version, and Android then refused the install as a downgrade. This
is a one-way transition: do not bring back a universal APK.
Since v2.0.8, splits carry `versionCode × 10 + ABI` (block in
`android/app/build.gradle.kts`) instead of Flutter's offset; the rule
still holds.

Notable fixes from the same cycle:

- deleting a folder destroyed the Keystore key **before** the database,
  which left a vault permanently unreadable if the database step failed;
- panic mode reported "wipe complete" even when it had failed;
- **schema migrations had never run** — tests created fresh databases,
  so `onUpgrade` was never called;
- the title of a vault note could be stored in plain text.

For thinkers, therapists, students, researchers, writers and journalists
who want to take sensitive or dense notes without them ever leaving their
phone.

**What sets it apart from Notesnook / Obsidian / Bear / Logseq: no
Internet permission — the app is technically unable to send anything,
and you can check that in its manifest.**

---

## Privacy promise

- **No `INTERNET` permission** in the manifest — you can check it by eye
  (`AndroidManifest.xml`). The 7 transitive permissions
  (INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK, RECEIVE_BOOT_COMPLETED,
  FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS)
  are stripped with `tools:node="remove"`.
- **Only runtime permission**: `RECORD_AUDIO`, if you turn on dictation.
- No account, no sign-up, no tracker, no ads, no telemetry.
- Open source under Apache 2.0, the whole code can be inspected.
- `allowBackup=false` + full `dataExtractionRules` (no exfiltration
  through Smart Switch or Android Backup).
- The Whisper model is imported through SAF — never bundled, never
  downloaded over the network by the app.

---

## Features

### Markdown editing
- Create / edit / debounced autosave
- **Edit / Preview switch**: the preview renders Markdown (headings,
  lists, emphasis, links). A `[[Title]]` link opens the note it points to;
  an `http`, `https` or `mailto` link opens in the system app, any other
  scheme is ignored. Images are never loaded: only their alt text is
  shown.
- Pin / favorites / archive / trash (30-day retention)
- Light / dark / system theme (GitHub palette)
- Configurable sort order (modified, created, title)

### Per-folder vaults
- **Passphrase mode** — Argon2id (m=64 MB, t=3) + AES-256-GCM. The vault
  key (32 random bytes) is wrapped by the key derived from the passphrase,
  and stored in the SQLCipher database, which is itself encrypted with the
  master key sealed by the Keystore (hardware-backed).
- **PIN mode** — 4 to 6 digits, lighter Argon2id derivation (m=32 MB,
  t=2) + a dedicated Keystore-bound key per vault, **auto-wipe after 5
  failed attempts** (atomic, resumed at boot if a crash interrupted it).
- AAD everywhere: `folder_id` bound to the KEK wrap, `note_id` bound to
  the encrypted content — prevents replay and substitution.
- Constant-time HMAC verifier to detect a wrong passphrase without
  decrypting every note.
- **Auto-lock**: all vaults lock as soon as the app goes to the
  background, and after a configurable delay (5, 15, 30 or 60 min, or
  never; 15 min by default).

### Search
- Instant **FTS5** (`unicode61` tokenizer, diacritics normalized).

### Whisper voice dictation
- **On-device Whisper** through the `files_tech_voice` package (git
  dependency pinned to a commit).
- Whisper Base q5_1 (57 MB) or Tiny q5_1 (32 MB) models, imported through
  SAF (downloaded by the system browser, not by the app).
- Strict SHA-256 check before loading; at startup, a 24-hour verification
  cache avoids recomputing the hash every time.
- Audio is never stored (temp file + delete on every code path).

### Backlinks
- `[[Title]]` links, autocomplete, Mentions / outgoing links panel.
- **Targeted indexing**: on each save, only the modified note is
  reprocessed; batch writes are grouped (500 ms). The full reconciliation
  pass at startup is delayed by 2 s.
- Dangling links resolve automatically when the target is created or
  renamed.

### Markdown export
- Single-note export: `.md` with YAML front matter compatible with
  Obsidian, Logseq, Bear, Foam and Dendron.
- Full ZIP export: one folder per notes folder + an export README.
- Encoding runs in an isolate (`compute()`); file names are hardened
  against path traversal and Unicode bidi characters.

### Panic mode
- Settings → Panic mode.
- Confirmation by typing a word (`WIPE`), which enables the
  "Wipe everything" button.
- **Ordered, best-effort** sequence (a step that throws does not stop the
  following ones):
  1. `FLAG_SECURE` forced ON
  2. Microphone capture stopped
  3. Clipboard cleared
  4. **`foldersLockAll`** — locks every open vault
  5. **`pinKeysWipe`** — deletes all PIN Keystore keys
     (`deleteKeysWithPrefix` on the Kotlin side)
  6. **`kekDestroy`** — destroys the Keystore master key (database
     instantly unreadable)
  7. Background workers paused
  8. **`dbWipe`** — overwrites the SQLCipher header (16 MB) + deletes the
     file and its sidecars
  9. Whisper wipe (models, verification cache, orphan WAV files)
  10. Model files left over from versions ≤ 1.1.6
  11. Preferences (except the two keys needed to restart), exports, temp
      files

### FLAG_SECURE
- On by default: no screenshots and no preview in the recent-apps screen.

---

## Security

- **SQLCipher database** encrypted with AES-256 (SQLCipher 4), master key
  sealed by AndroidKeystore (hardware-backed on the S24).
- **32-byte CSPRNG keys**: the database key is sealed by the Keystore;
  each vault key is wrapped by a key derived with Argon2id from the
  passphrase or, in PIN mode, by a lighter Argon2id key and then sealed by
  a dedicated Keystore key.
- **AAD everywhere**: `folder_id` for the KEK wrap, `note_id` for the
  content — an encrypted blob cannot be reused in another context.
- **Constant-time HMAC verifier** to detect a wrong passphrase or PIN
  without trying every note.
- **PIN mode with auto-wipe**: 5 attempts, atomic preference flag,
  resumed at boot if interrupted.
- **Ordered panic mode**: `foldersLockAll → pinKeysWipe → kekDestroy
  → dbWipe`, which guarantees the KEK is gone before the database.
- **16 MB database header wipe** (destroying the KEK beforehand already
  guarantees secrecy; a full overwrite achieves nothing on modern eMMC
  with wear-leveling — a design decision, see `SECURITY.md`).
- **`setUserAuthenticationRequired(false)`** on the PIN Keystore key:
  the app PIN is the only factor; adding biometrics on top would expose
  the user to coercion (a biometric key survives a reboot).
- **FLAG_SECURE** by default.
- `allowBackup=false`, hardened `dataExtractionRules`.

See [`SECURITY.md`](SECURITY.md) for the full threat
model and how to report a vulnerability.

---

## Android permissions

| Permission | Status | Use |
|---|---|---|
| `INTERNET` | **REMOVED** (`tools:node="remove"`) | none |
| `ACCESS_NETWORK_STATE` | REMOVED | none |
| `WAKE_LOCK` | REMOVED | none |
| `RECEIVE_BOOT_COMPLETED` | REMOVED | none |
| `FOREGROUND_SERVICE` | REMOVED | none |
| `FOREGROUND_SERVICE_DATA_SYNC` | REMOVED | none |
| `POST_NOTIFICATIONS` | REMOVED | none |
| `RECORD_AUDIO` | runtime, opt-in | only if Whisper dictation is turned on |
| `com.filestech.notes_tech.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | added to the merged manifest by AndroidX, `signature` level | internal to the app, never requested from the user |

To be audited on every release with `aapt dump permissions`.

---

## Installation

Two options:

1. **Published APK**: download the split matching your device from
   [GitHub Releases](https://github.com/gitubpatrice/notes_tech/releases)
   — `arm64-v8a` fits almost every phone since 2016, and there is no
   universal APK —
   check the SHA-256 published in the release notes, then sideload.
   These APKs are built and signed by GitHub Actions
   (`.github/workflows/release.yml`), triggered by pushing a `v*` tag.
2. **Local build** (recommended for auditing) — see the next section.

No Play Store: sideload distribution only (consistent with the privacy
promise — no account needed to install).

---

## Local build

```bash
flutter pub get
flutter analyze
flutter test
flutter build apk --release --split-per-abi --obfuscate \
  --split-debug-info=build/symbols
```

For a **strictly signed** release (no debug fallback), create
`android/key.properties`:

```
storeFile=/absolute/path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

arm64 release APK: **~27 MB** (SQLCipher + Whisper.cpp — the dictation
model is downloaded separately, not bundled). On-device AI was removed in
v2.0.0: it weighed 100 MB for a feature almost nobody could reach without
the Internet permission.

Requirements:
- Flutter 3.47.2 (exact version, pinned in `pubspec.yaml`; Dart
  `^3.11.5`)
- Android SDK + NDK installed through Android Studio
- No neighbouring repository to clone: `files_tech_voice` and
  `files_tech_core` are git dependencies pinned to a commit, fetched by
  `flutter pub get`

---

## Architecture

```
lib/
├── main.dart                          # parallel bootstrap + Provider DI
├── app.dart                           # MaterialApp
├── core/                              # constants, exceptions, theme, a11y
├── data/
│   ├── models/                        # Note, Folder, NoteLink,
│   │                                    NoteChangeEvent
│   ├── db/                            # SQLite (FTS5 + sqlcipher), DAOs
│   └── repositories/                  # facades + typed streams
├── l10n/                              # FR / EN ARB files + generated classes
├── services/
│   ├── security/                      # VaultService (Keystore KEK),
│   │                                    FolderVaultService (passphrase/PIN),
│   │                                    KeystoreBridge, PanicService
│   ├── export/                        # NoteExportService (.md, ZIP)
│   ├── secure_window_service.dart     # FLAG_SECURE via MethodChannel
│   ├── voice/                         # VoiceService (Whisper, files_tech_voice)
│   ├── backlinks_service.dart         # [[]] parsing, targeted indexing
│   ├── note_actions.dart              # reusable UI actions
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
- `sqflite_sqlcipher` (AES-256 encrypted SQLite + FTS5)
- `flutter_secure_storage` (KEK sealed by AndroidKeystore)
- `cryptography` (Argon2id RFC 9106 + AES-GCM, pure Dart)
- `crypto` (streaming SHA-256 for model verification)
- `files_tech_voice` (git dependency, Whisper STT)
- `files_tech_core` (git dependency, shared crypto helpers)
- `flutter_markdown_plus` + `markdown` (note preview, legal pages)
- `provider`, `shared_preferences`, `file_picker`, `archive`,
  `share_plus`, `url_launcher`
- **No network dependency**

## Targets

- Samsung Galaxy S24 / S24 FE (validated)
- Samsung Galaxy S9 (Android 10), POCO C75
- minSdk 24 (Android 7.0+)

---

## License

[Apache License 2.0](LICENSE) — see also [`NOTICE`](NOTICE) and
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Files Tech suite

Notes Tech is part of the [Files Tech](https://files-tech.com/en/) suite
of privacy-focused Android apps:
- [PDF Tech](https://github.com/gitubpatrice/PDF-TECH)
- [Read Files Tech](https://github.com/gitubpatrice/READ-FILES-TECH)
- [AI Tech](https://github.com/gitubpatrice/ai_tech)
- [Pass Tech](https://github.com/gitubpatrice/pass_tech)
