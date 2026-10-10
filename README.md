# Notes Tech

> Your notes stay in your pocket. Encrypted, and offline.

🇫🇷 [Version française](README.fr.md)

**v3.1.0 — October 2026** · [Privacy policy](PRIVACY.md) · [Terms of use](TERMS.md) · [Security](SECURITY.md)

Encrypted Markdown note-taking app for Android, written in **Kotlin** with Jetpack Compose.
**100% local, no Internet permission.** Interface in **English, French, German, Italian and
Spanish**. Per-folder vaults (Argon2id passphrase or Keystore-bound PIN), an app lock with PIN and
strong biometrics, FTS5 full-text search, on-device Whisper voice dictation, `[[note]]` backlinks,
Markdown preview, multi-step panic mode.

For thinkers, therapists, students, researchers, writers and journalists who want to take
sensitive or dense notes without them ever leaving their phone.

**What sets it apart from Notesnook / Obsidian / Bear / Logseq: no Internet permission — the app is
technically unable to send anything, and you can check that in its manifest.**

---

## What's new in 3.1.0

- **Reading**: tapping a note opens it to be read, drawn as a card in its colour — its title, the
  rendered text, its date — with an **Edit** button; the ✓ tick saves and goes back to reading. A blank
  note opens to be written.
- **Long press on a note**: edit, move to trash (with Undo) or delete permanently. A vault note's
  actions are offered only once its vault is open.
- **Colours**: eight pastel colours per note, from the ⋮ menu, shown in the lists. A vault note shows its
  colour only while its vault is open; like its tags, the colour is stored outside the vault's own
  encryption (in the encrypted database) — the privacy policy says so.
- **"+" in a closed vault** creates the note once the vault is unlocked.
- One blue for the main buttons; legal pages dated and checked against the code; build toolchain moved
  to AGP 9. The database moves to schema 10 (one column added, every note kept).

## What's new in 3.0.0

**Notes Tech is rewritten in Kotlin.** Versions 1.x and 2.x were built with Flutter. The 3.0.0 keeps
your data: installed over a 2.0.x, it opens the same encrypted database and the same vaults —
measured over 2.0.3, 2.0.4 and the published 2.0.9, passphrase and PIN vaults included.

- **App lock**: a PIN asked when Notes Tech opens, plus fingerprint (or face, where the phone rates
  it as strong) if you wish; a relock delay; its content is hidden in the recent-apps screen.
- **German, Italian and Spanish**, on top of English and French.
- **A much smaller app**: the arm64 APK is about **8 MB**, down from 27 MB.
- **Hardening from a full security audit** (September 2026):
  - closing a vault clears the note open on screen;
  - deleted text, and text moved into a vault, is also erased from the search index;
  - copying from a vault note goes through the protected clipboard, and the keyboard is asked not
    to learn what you type in it;
  - other apps' text actions no longer appear in the selection menu, and no emoji font is requested
    from Google services;
  - a panic wipe that was interrupted completes at the next launch;
  - plain-text files left in the cache by 2.x are erased at the first launch.
- **Before Android 9, no new PIN vault**: Android cannot bind its key to the phone's unlock there.
  An existing PIN vault still opens and says so.

⚠️ **This update is one-way.** Android refuses to reinstall a 2.x over the 3.0.0, and uninstalling
erases your notes along with their key. Export what you want to keep before updating if in doubt.

⚠️ **F-Droid and GitHub copies do not update each other**: F-Droid signs Notes Tech with its own key.
A copy installed from F-Droid receives the 3.0.0 through F-Droid.

---

## Privacy promise

- **No `INTERNET` permission.** The merged release manifest holds exactly four permissions:
  - `RECORD_AUDIO` — asked at run time, only if you turn on dictation;
  - `USE_BIOMETRIC` and `USE_FINGERPRINT` — added by AndroidX Biometric for the app lock; granted at
    install, they give access to no data;
  - `com.filestech.notes_tech.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — added by AndroidX,
    `signature` level, internal to the app.

  [`tools/check-manifest-permissions.py`](tools/check-manifest-permissions.py) checks the merged
  manifest, not the source one, which declares `INTERNET` only to remove it.
- No account, no sign-up, no tracker, no ads, no telemetry.
- Open source under Apache 2.0: the whole code can be inspected.
- `allowBackup=false` and full `dataExtractionRules`: nothing leaves through Android backup or a
  device-to-device transfer.
- The Whisper model is imported by you through the system file picker — never bundled, never
  downloaded by the app.

---

## Features

### Markdown editing
- Create, edit, autosave.
- **Reading and writing**: a note opens to be read — headings, lists, emphasis and links rendered —,
  and **Edit** writes it. A `[[Title]]` link
  opens the note it points to; an `http`, `https` or `mailto` link opens in the system app, any
  other scheme is ignored. Images are never loaded: their alternative text stands in.
- Pin, favourites, colours, archive, trash (30-day retention), sort order, light / dark / system theme.

### Per-folder vaults
- **Passphrase mode** — Argon2id (64 MiB, t = 3) and AES-256-GCM. The vault key (32 random bytes) is
  wrapped by the key derived from the passphrase and stored in the SQLCipher database.
- **PIN mode** — 4 to 6 digits, lighter Argon2id (32 MiB, t = 2) plus a dedicated Keystore key per
  vault; **5 failed attempts wipe the vault key**. Requires Android 9 or later.
- Authenticated encryption bound to its context (the folder for the key, the note for the content),
  and a constant-time verifier that detects a wrong secret without decrypting the notes.
- **Auto-lock** when the app goes to the background, and after a delay you choose (5, 15, 30 or 60
  minutes, or never; 15 by default).

### App lock
- PIN of 4 to 6 digits, verified through Argon2id and a Keystore-held HMAC key; growing delays after
  five wrong attempts.
- Optional strong (class 3) biometric unlock, backed by its own Keystore key.
- Relock immediately, or after 15 seconds, 1 minute or 5 minutes in the background.

### Search
- Instant **FTS5** full-text search (`unicode61` tokenizer, diacritics folded). Deleted text is
  erased from the index too (`secure-delete`).

### Whisper voice dictation
- **whisper.cpp 1.8.3**, compiled into the app from source and run on the device.
- Whisper Base q5_1 (57 MB) or Tiny q5_1 (32 MB), downloaded by your browser from the official source
  and imported; checked by SHA-256 before use.
- Recordings are deleted once transcribed.

### Backlinks
- `[[Title]]` links with autocompletion, a Mentions / outgoing links panel; dangling links resolve
  when the target note appears.

### Markdown export
- One note as `.md` with a frontmatter readable by Obsidian, Logseq, Bear, Foam or Dendron.
- Everything as a ZIP: one folder per notes folder, plus a README.

### Panic mode
- Settings → Panic mode, confirmed by typing the word it asks for (`WIPE` in English).
- An ordered sequence, where a failing step does not stop the next ones: secure window, dictation
  stopped and forbidden, clipboard cleared, vaults locked, vault PIN keys and app lock keys deleted,
  **database key destroyed**, exports and recordings erased, database header overwritten and files
  deleted, voice model, preferences and cache erased. Interrupted, it resumes at the next launch.

### Screen protection
- On by default: no screenshots and no preview in the recent-apps screen.

---

## Installation

1. **Published APK** — from [GitHub Releases](https://github.com/gitubpatrice/notes_tech/releases),
   the universal APK (`notes-tech-universel-3.1.0.apk`, any phone), or the lighter one for your device
   (`arm64-v8a` fits almost every phone since 2016). Check the SHA-256 published in the release notes.
2. **F-Droid** — [f-droid.org/packages/com.filestech.notes_tech](https://f-droid.org/packages/com.filestech.notes_tech/),
   built and signed by F-Droid.
3. **Local build** — next section.

No Play Store: no account is needed to install.

---

## Local build

Requirements: JDK 17, Android SDK 37, NDK `27.0.12077973` and CMake 3.22.1 (pinned in
`app/build.gradle.kts`). Gradle 9.8.1 comes with the wrapper.

```bash
./gradlew testDebugUnitTest lintDebug
./gradlew assembleRelease -Pnotestech.replaceInstalledApp=true
```

⚠️ Without `-Pnotestech.replaceInstalledApp=true`, the build is installed **beside** Notes Tech under
`com.filestech.notes_tech.next`, with its own empty storage: it cannot see, nor replace, an installed
copy. That is how the port was developed without ever touching real data.

The version lives in [`version.properties`](version.properties); each ABI split gets
`versionCode × 10 + ABI` (1 = armeabi-v7a, 2 = arm64-v8a, 3 = x86_64), the scheme F-Droid requires,
and the universal APK `versionCode × 10`.

## Stack

- Kotlin 2.4, Jetpack Compose (Material 3), Hilt, Room on **SQLCipher** 4.16, DataStore
- Bouncy Castle (Argon2id), Android Keystore, AES-256-GCM from the platform
- `org.jetbrains:markdown` (preview), whisper.cpp through JNI (dictation)
- **No network library**

Third-party components and their licences: [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Targets

- minSdk 24 (Android 7.0), targetSdk 36
- Tested on Samsung Galaxy S9 (Android 10) and S24 FE (Android 16)

## How it was built

The port's design notes, decisions, traps and security audit are in [`docs/`](docs/) and
[`audits/`](audits/), in French — start with [`docs/README-PORTAGE.md`](docs/README-PORTAGE.md).

---

## License

[Apache License 2.0](LICENSE) — see also [`NOTICE`](NOTICE) and
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Files Tech suite

Notes Tech is part of the [Files Tech](https://files-tech.com/en/) suite of privacy-focused Android
apps:
- [PDF Tech](https://github.com/gitubpatrice/PDF-TECH)
- [Read Files Tech](https://github.com/gitubpatrice/READ-FILES-TECH)
- [Pass Tech](https://github.com/gitubpatrice/pass_tech)
- [Agenda Tech](https://github.com/gitubpatrice/AGENDA-TECH)
- [SMS Tech](https://github.com/gitubpatrice/SMS-TECH)
- [App Manager Tech](https://github.com/gitubpatrice/APP-MANAGER-TECH)
