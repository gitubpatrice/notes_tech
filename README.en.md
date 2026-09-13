# Notes Tech

> Your notes stay in your pocket. Encrypted, and offline.

🇫🇷 [Version française](README.md)

Encrypted Markdown note-taking app for Android, built with Flutter.
**100% local, no Internet permission.** The interface is available in
English and French.

For thinkers, therapists, students, researchers, writers and journalists
who want to take sensitive notes without them ever leaving their phone.

---

## Privacy

- **No `INTERNET` permission.** It is removed in the
  [manifest](android/app/src/main/AndroidManifest.xml), together with six
  other permissions that dependencies try to add. You can check any
  published APK with `aapt dump permissions`.
- The only runtime permission is `RECORD_AUDIO`, requested the first time
  you use voice dictation.
- No account, no tracker, no ads, no telemetry.
- Android backup is disabled (`allowBackup=false`, `dataExtractionRules`).

## Features

- **Markdown notes** with autosave, pinning, favorites, archive and trash.
- **Encrypted database** (SQLCipher), its key sealed by the Android Keystore.
- **Per-folder vaults**: passphrase (Argon2id) or PIN, each note encrypted
  with AES-256-GCM. Configurable auto-lock, 15 minutes by default. Too many
  wrong PIN attempts wipe the vault.
- **Full-text search** (SQLite FTS5).
- **`[[Title]]` backlinks** with autocomplete and a panel of notes linking
  to the current one.
- **On-device voice dictation** (Whisper). The model is downloaded by your
  browser and imported by hand: the app itself never goes online. Audio is
  never stored.
- **Markdown export**, one note or everything as a ZIP, with YAML front
  matter compatible with Obsidian and Logseq.
- **Panic mode**: wipes the keys, the database and the dictation model.
- Screenshots and the recent-apps preview are blocked by default
  (`FLAG_SECURE`).

## Install

Download the APK matching your device from
[GitHub Releases](https://github.com/gitubpatrice/notes_tech/releases).
`arm64-v8a` fits almost every phone sold since 2016. There is no universal
APK.

The APKs are built and signed by GitHub Actions when a `v*` tag is pushed
([`release.yml`](.github/workflows/release.yml)). Notes Tech is not on
Google Play.

## Build from source

Requirements: Flutter 3.47.2 (pinned in `pubspec.yaml`), Android SDK and NDK.

```bash
flutter pub get
flutter analyze
flutter test
flutter build apk --release --split-per-abi --obfuscate \
  --split-debug-info=build/symbols
```

A release build needs your own signing key, declared in
`android/key.properties`:

```
storeFile=/absolute/path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

The `files_tech_voice` and `files_tech_core` packages are fetched from
GitHub by `flutter pub get`, pinned to a commit.

## Documentation

- [Privacy policy](assets/legal/PRIVACY.en.md) ·
  [Terms of use](assets/legal/TERMS.en.md)
- [Security policy and threat model](SECURITY.md), in French
- Changelog and design notes: [French README](README.md)

## License

[Apache License 2.0](LICENSE), see also [`NOTICE`](NOTICE) and
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Notes Tech is part of [Files Tech](https://files-tech.com/en/), a suite of
privacy-focused Android apps.
