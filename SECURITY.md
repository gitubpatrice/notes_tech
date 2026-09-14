# Security policy — Notes Tech

*Version française : [SECURITY.fr.md](SECURITY.fr.md)*

> **⚠️ Status as of 2026-08-07 — removal of on-device AI (v2.0.0).** Notes Tech
> no longer ships a Gemma inference engine or semantic search
> (MiniLM / ONNX). The history entries below that mention them describe
> fixes that **were actually applied at the time**: they are kept as they
> are, because rewriting a security log would be worse than letting it
> show its age. Only current-state statements have been updated.

**Current version: v2.0.9 — September 2026.**

## v2.0.0 — AI removal + vault/panic hardening (2026-08-07)

Removal of semantic search (MiniLM/ONNX) and of Gemma Q&A. The attack
surface shrinks accordingly: no more inference isolate, no more injected
prompt, no more plaintext embeddings cache to protect. arm64 APK
~127 MB → 26.9 MB.

### Vulnerabilities fixed

- **Irreversible data loss when deleting a vault folder.**
  `folders_drawer.dart` destroyed the PIN Keystore key **before** the
  database row. If the database deletion then failed, the folder and its
  encrypted notes remained while the key needed to unlock them was gone —
  vault permanently unreadable. The order is reversed: database first,
  key second, inside `try/catch`.
- **Panic mode misreported its result.** Three steps
  (`_prefsClearWithWhitelist`, `_wipeExportsCache`, `_purgeTempDirectory`)
  swallowed their individual failures, and the final screen displayed
  "wipe complete" while artifacts survived. They now count their failures
  and throw; `_wipeExportsCache` also purges the cache directory in
  addition to the temporary one. The `_estArtefactSensible` filter avoids
  the opposite pitfall — a temporary file unrelated to the app must not
  cause the panic to be declared incomplete.
- **Vault note title written in plaintext.** Sealing was the caller's job
  rather than the persistence layer's: any write path that forgot it left
  the title readable in the column. `NotesRepository` now refuses any
  plaintext write into a vault folder (`VaultPlaintextWriteException`)
  and does the sealing itself.
- **Clipboard not cleared after successive copies.** The clearing timer of
  one copy could reset the state while a more recent copy was pending,
  leaving that copy's content in the clipboard indefinitely. A per-copy
  token closes the race.
- **Schema migrations had never been exercised.** Tests created fresh
  databases: `onCreate` laid down the current schema and `onUpgrade` was
  never called. `integration_test/db_migration_test.dart` now builds real
  legacy databases (v1, v5, v7) and checks the actual migration up to v9,
  including the FTS purge of a locked note.

### Verification

GitHub Actions CI green end to end for the first time: analysis,
65 unit tests, build, and the 5 integration suites on an API 30
emulator. OSV dependency scan: no CVE.

## v1.1.6 — Logo & top bar reorganisation (2026-07-09)

UI change with no security impact: checkerboard logo in the AppBar,
secondary actions (Settings / About) grouped in a `⋮` overflow menu, and a
"Check for updates" button in About. This button **delegates** the opening
of the GitHub releases page **to the system browser** (same pattern as the
voice model download) — **no INTERNET permission added**: the zero-network
promise remains verifiable in the manifest (`tools:node="remove"`).

## v1.1.5 — Expert audit after v1.1.4 (2026-07-07)

4-agent audit (security / performance-quality / wiring / coherence-i18n)
over ~24.8k LOC. No CRITICAL/HIGH vulnerability. Security/robustness fixes
+ features against data loss. `flutter analyze` 0 issues, 84 tests passing.

### Security / robustness

- **Purge of PLAINTEXT WAL/SHM/journal sidecars** during the legacy
  plain → encrypted DB migration (`database.dart._migratePlainToEncrypted`).
  The `journal_mode = WAL` mode left `notes_tech.db-wal`/`-shm` containing
  plaintext note pages, which survived the migration (leak on a rooted
  device / physical extraction). They are now purged after
  `sqlcipher_export` + checkpoint, symmetrically with the wipe of
  `.plain.bak`.
- **Embeddings cache resilience** (`embeddings_dao.listByModel`): a
  poisoned row (blob of inconsistent size) no longer dooms the whole
  semantic cache load — tolerant per-row decoding.
- **`mounted` guards** added (`ai_chat` after the MiniLM isolate, `search`
  post-frame): no more `setState` after `dispose`.
- **Vault data-loss reporting**: vault notes whose last change was lost
  (vault locked during the save) are now flagged by a banner on opening —
  the `vault_lost_drafts` pref (F11 v1.1.0) was written but never read
  back.
- **Clipboard cleared in panic mode**: new step `PanicStep.clipboardClear`
  (early in the sequence, after `voiceCancel`) that calls
  `NoteActions.cancelAndClear()`. A copied note (`copyMarkdown`) stayed in
  plaintext in the clipboard until the 60 s auto-clear; in panic mode that
  delay is no longer waited for.

### Maintenance

- Purge of **99 orphan l10n keys** (never referenced) from the FR/EN ARB
  files — parity kept (380 keys each); `flutter analyze` 0 issues confirms
  that none of them was in use.

## v1.1.0 — Expert audit after v1.0.9 (2026-05-14)

Following a 3-agent audit (security / performance / UX) + cross-file
audit, 23 fixes shipped (F1-F14, P1-P5, U1-U11). No change to the DB
format or to the vault format. `flutter analyze` 0 issues, 68/68 tests
passing (+5 new in `test/audit_v1_1_0_test.dart`).

### Security

- **F1** — `note_editor._moveToFolder`: EXPLICIT confirmation
  (destructive dialog `cs.errorContainer` + Cancel autofocus) before
  moving a note out of a vault into a regular folder. Before: the content
  was decrypted + persisted in plaintext with no UI signal, irreversibly.
  If the vault auto-lock fired during the change, the user believed the
  screen was closed while the plaintext flush went through silently.
- **F2** — `NotesDao.findByTitleLike`: SQL filter
  `encrypted_content IS NULL` added. Before: `BacklinksService.suggestTitles`
  (F3 v1.0.9) filtered on the Dart side, but the underlying DAO exposed
  all locked notes to any future caller, and the SQL `limit` was consumed
  by vault notes BEFORE the Dart filter → suggestions thinned out on large
  vaults for no apparent reason. Defense in depth.
- **F3** — `IndexingService._indexAll`: explicit skip of vault notes
  BEFORE `_encodeWith(embedder, note)`. Before: if `knownHashes[n.id]` did
  not match for a locked note (stale hash), MiniLM encoded its content in
  RAM on the ONNX worker side, and the embedding was only discarded AFTER
  encoding, via `live.encryptedContent != null`. Now nothing is fed to the
  embedder for locked notes, whatever the hash.
- **F4** — `NoteActions.copyMarkdown`: native Kotlin MethodChannel
  `com.filestech.notes_tech/clipboard.copySensitive` that sets
  `ClipDescription.EXTRA_IS_SENSITIVE` (Android 13+) + 60 s clipboard
  auto-clear on the Dart side (checks that the current value is still the
  one we set before clearing; does not touch another secret copied in the
  meantime). Before: raw `Clipboard.setData` exposed the plaintext of a
  decrypted vault note to ANY third-party clipboard manager + Knox
  clipboard history, with no expiry.
- **F5** — `ai_chat_screen._resolveSource`: removal of
  `initialDirectory: '/storage/emulated/0/Download'`. Before: an absolute
  path requiring READ_EXTERNAL_STORAGE (otherwise the SAF picker was
  silently empty) AND opening on other apps' Downloads (Telegram,
  WhatsApp), paving the way for a malicious `.task` unrelated to the main
  SAF flow.
- **F6** — `VoiceService._isPresentAndPlausible`: TTL of the SHA-256
  verification cache reduced from 30 days to 24 hours, and refusal if
  `cached.mtimeMs > cached.verifiedAtMs` (file touched after our last
  successful check). Before: a root attacker able to write a trojanized
  Whisper model with a matching `touch -t` (size, mtime) stayed validated
  for 30 days without rehashing. User cost: ~3-5 s of strict hashing on
  the first `startRecording` after 24 h.
- **F7** — `PanicService`: new step `_wipeExportsCache` that purges
  `getApplicationCacheDirectory()/exports/`. Before: an export ZIP being
  shared survived panic because `_purgeTempDirectory` only covered
  `getTemporaryDirectory()`.
- **F8** — `RagService._sanitize` extended: Llama2 `<<SYS>>`, ChatML
  `<|im_start|>` / `<|im_end|>`, Alpaca/Vicuna `### Instruction:` /
  `### Response:`, Mistral `[ASSISTANT]` / `[USER]` brackets neutralized.
  Before: Gemma 3 1B (general-purpose decoder pre-trained on these
  formats) could switch to formal chat mode if an attacker inserted these
  markers into a RAG context note. F13 v1.0.3 listed these patterns as
  best-effort; they are now covered.
- **F10** — `FolderVaultService._unlockInProgress: Set<String>` guard on
  `unlock()` / `unlockWithPin()`. Before: Dart is single-threaded, but
  Argon2id `compute()` (600-900 ms on S24) yields the event loop between
  `await`s — a `Timer(_autoLockAfter)` could then fire during the unlock
  and wipe the freshly assigned `folder_kek` before it was consumed by
  `encryptNote`. `_autoLockSweep` now skips folderIds that are being
  unlocked.
- **F11** — `note_editor._flushFinalSave`: if the vault is locked during
  the final flush (dispose after auto-lock), the `id` is persisted in
  `prefs.vault_lost_drafts`. Before: silent "accepted loss"; the user
  believed auto-save was infallible. Can be consumed by a future
  "N changes lost on vault notes" screen at the next boot.
- **F14** — `AppDatabase._attachSql`: strict regex validation
  `^[A-Za-z0-9_./:\\-]+$` of the path BEFORE the `ATTACH`. Before: the
  path came from `getApplicationDocumentsDirectory()`, which can be
  hijacked via `LD_PRELOAD` / a root setup pointing to a path containing
  SQL metacharacters (`'; DROP --`). An extreme, root-only case, but it is
  the database's "single source of truth".

### Performance

- **P1** — `HomeScreen._reloadDebouncer` (250 ms) coalesces
  `notes.changes` events during continuous auto-save (1 event/500 ms per
  keystroke). Before: a full `listAllAlive` SELECT ran on EVERY event,
  i.e. ~50-200 ms of SQLCipher on 500 notes × typing frequency.
- **P2** — `isUniversalApk = false` in `build.gradle.kts`. Before:
  generated a 4th universal APK of ~294 MB bundling the native libs of all
  3 ABIs (sqlcipher + ONNX + Whisper + MediaPipe). Saves ~70 MB of GitHub
  Releases upload + user bandwidth.
- **P3** — `BacklinksService._buildTitleIndex`: 5 s TTL cache, explicitly
  invalidated on title change. Before: `listAllAlive()` re-run on EVERY
  note save (a burst of auto-saves = 1 SELECT/500 ms even without a title
  change).
- **P5** — `MentionsLegalesScreen._MarkdownAssetView._load`: process-wide
  `static final Map<String, String>` cache of the `.md` assets. Before:
  `rootBundle.loadString` re-run on EVERY TabBarView tab switch or locale
  change.

### UX / a11y

- **U1** — `HapticFeedback.selectionClick()` on Markdown copy +
  `HapticFeedback.heavyImpact()` on panic trigger. Before: 0
  `HapticFeedback` hits in all of `lib/` — no haptic feedback for critical
  actions (aligned with Pass Tech v2.4.4 U9 / AI Tech U4).
- **U2** — `SnackbarMessengerExt.showFloatingSnack` now accepts
  `foregroundColor`. 2 `folders_drawer` call sites updated:
  `cs.errorContainer` + `cs.onErrorContainer` (WCAG AA contrast ~13:1 in
  light mode vs ~3.5:1 measured with raw `cs.error` on light `textPri`).
- **U3** — Note title + content TextField: `textCapitalization:
  TextCapitalization.sentences`. Before: single-finger touch typing
  without auto-capitalization → titles starting with a lowercase letter.
- **U11** — AI composer TextField: `textCapitalization:
  TextCapitalization.sentences`.

---

## v1.0.9 — Expert audit after v1.0.8 (2026-05-13)

Following a 3-agent audit (security / performance / UX), 11 fixes
shipped. No change to the DB format (still v6) or to the vault format.
`flutter analyze` 0 issues, tests passing.

### Security

- **F1** — `FolderVaultService.unlock()` (passphrase mode) now gets the
  same monotonic exponential lockout as `unlockWithPin()` (M-05 v1.0.7):
  RAM counter `_passFailCount` + backoff `1/2/4/8/16/30 s` after 5
  attempts, throwing `VaultLockoutInProgressException`. Before: on an S24+
  flagship, Argon2id m=64 MB t=3 took ~600-900 ms → an ADB attacker with a
  10k-passphrase dictionary could test ~4 attempts/s without friction.
  Public API `passphraseLockoutRemaining()` exposed for a UI countdown
  symmetric with the PIN.
- **F3** — `BacklinksService.suggestTitles()` now filters `n.isLocked`.
  Before: `[[…]]` autocompletion in an alive note offered the titles of
  locked notes → leak by default since vaults were introduced. Aligned
  with M-01 v1.0.7 (`_indexByTitle`, `_handleSingleChange`, `_reindexAll`,
  which already skipped locked notes).
- **F7** — `RagService.composePrompt` now applies `_sanitize` to the
  `userPrompt` (source titles and bodies were already sanitized). Covers
  an injection arriving via voice dictation or auto-paste (`<|system|>`,
  zero-width, bidi).
- **F8** — `note_editor_screen` sets `FLAG_SECURE`
  (`_ensureSecureForced`) BEFORE `vault.decryptNote`. Before: a ~5-20 ms
  window (channel round-trip) during which a manual screenshot or
  MediaProjection could capture the plaintext between `decryptNote` and
  `_ensureSecureForced`.

### Performance

- **P1.2** — `note_editor_screen._changesSub` now filters events
  (`event.id != widget.noteId && !event.isBulk` → return). Before:
  `get(noteId)` re-triggered on EVERY event (including its own saves + all
  other open editors) → at least 1 SQLCipher SELECT/s during continuous
  auto-save (500 ms debounce).
- P1.1 (backlinks title cache) and P1.4 (notes_repository.save without
  systematic `findById`) postponed to v1.1 (deeper refactors).

### UX / a11y

- **U1+U2+U11** — `PassphraseTextField` (centralized) adds
  `autofillHints: const []` (disables Samsung Pass / Google Autofill),
  `keyboardType: TextInputType.visiblePassword` (neutralizes SwiftKey/Gboard
  auto-capitalization), and `enableInteractiveSelection: !_hidden` (blocks
  selection/copy while masked — against clipboard managers).
- **U3** — `confirmDialog` (centralized helper `app_dialogs.dart`): Cancel
  button `autofocus: true` when the dialog is destructive + confirm button
  via `cs.errorContainer/onErrorContainer` instead of raw `cs.error`.
- **U4** — `about_screen` icon `Image.asset` with `cacheWidth: 112` /
  `cacheHeight: 112` (before: 1024×1024 PNG decoded without bounds to
  display 56dp = ~12 MB of permanent RAM).
- **U9** — Home empty state: inline "New note" `FilledButton.tonalIcon` in
  addition to the FAB (more discoverable on first launch).

### Info-level lints cleaned up (analyze 0 issues)

9 `SemanticsService.announce` occurrences annotated
`// ignore: deprecated_member_use` (migration to Flutter 3.35
`sendAnnouncement` planned for v1.1), 2 `directives_ordering`
(home_screen / settings_screen imports sorted), 3 `prefer_const` in
`panic_service_test.dart`.

### Tests

All existing tests passing (64+ assertions). The e2e flow test
`unlock → wrong passphrase × 5 → lockout` is deliberately deferred to
instrumentation (a Keystore mock is non-trivial in pure Dart, see
`folder_vault_service_test.dart`).

---

**Previous version: v1.0.4 — May 2026.**

Notes Tech v1.0 introduces several security hardenings:
- Panic `prefs.clear()` with a **whitelist** (`db_encrypted_v1`,
  `secure_window_enabled` preserved), as stated in `assets/legal/PRIVACY.en.md`.
- `flutter_markdown` restricted to the local legal pages (assets), no
  rendering of remote Markdown. `flutter_markdown`, discontinued
  upstream, is replaced in v2.0.9 by `flutter_markdown_plus`, which also
  renders the note preview; no image is loaded there, and only `http`,
  `https` and `mailto` links are handed to the system handler.
- Complete `ProGuard` rules: `files_tech_voice`, `flutter_markdown`,
  sqflite. The MediaPipe / ONNX / flutter_gemma rules became moot with the
  removal of on-device AI (v2.0.0).

## Reporting a vulnerability

If you believe you've found a security issue in Notes Tech, please
**do not open a public GitHub issue**. Instead, email:

📧 **contact@files-tech.com**

Subject: `[SECURITY] Notes Tech — <short summary>`

Include:
- A description of the issue and its potential impact.
- Steps to reproduce (or a proof-of-concept).
- Affected version (Settings → About Notes Tech → Notes Tech vX.Y.Z).
- Your contact for follow-up.

You'll get an acknowledgement within **72 hours**. A coordinated
disclosure timeline will be agreed upon if the issue is confirmed.

## Scope

### In scope
- Notes Tech app code (`lib/`, `android/`)
- Sibling module `files_tech_voice` if relevant
- Crypto implementations (SQLCipher integration, KEK derivation /
  storage, panic mode irreversibility)
- Permission handling (`RECORD_AUDIO`)
- File handling / SAF imports / path traversal
- Dependency vulnerabilities surfaced by `health_check.sh`

### Out of scope
- Issues in third-party packages (file an issue upstream).
- Issues requiring a rooted device or pre-existing malware on the
  device.
- Social engineering against the user.
- Denial of service via deliberately oversized notes / payloads.

## Threat model

Notes Tech is designed for individuals and professionals who want
**local-only** notes with strong cryptographic guarantees. Three
adversary classes are considered:

### 1. Loss / theft (lost or stolen unlocked device)
- **Confidentiality at rest**: SQLCipher (AES-256) for the database,
  AES-256-GCM for per-folder vault notes. KEK sealed by Android
  Keystore (hardware-backed on modern devices).
- Per-folder vault adds a second factor (passphrase or PIN) on top of
  the device lockscreen.

### 2. Coercion (search, "give me your phone", border check)
- **Panic mode**: a confirmed delete-everything action that runs a
  deterministic ordered sequence (see below). Designed to be fast and
  irrecoverable under coercion.
- **PIN auto-wipe**: 5 failed PIN attempts on a vault wipes that
  vault's keys atomically (with crash-resume via prefs flag).
- **`setUserAuthenticationRequired(false)`** on PIN Keystore keys: the
  PIN is the sole factor — adding biometric would expose the user to
  forced fingerprint unlock (a biometric-derived key survives reboot).

### 3. Sandbox malware on the same device
- No `INTERNET` permission means a compromised dependency cannot
  exfiltrate notes via the standard network path. Data exfiltration
  through standard channels is technically impossible without
  re-installing a modified APK.
- No `FOREGROUND_SERVICE`, no `POST_NOTIFICATIONS`, no
  `RECEIVE_BOOT_COMPLETED` — minimal attack surface.
- `FLAG_SECURE` blocks Recents previews and screen recording.
- `allowBackup=false` + `dataExtractionRules` block Smart Switch /
  Android Backup exfiltration.

### Crypto building blocks
- **Argon2id RFC 9106** for passphrase derivation: `m=64 MiB, t=3,
  p=1, 32-byte output` (vault default). PIN mode uses lighter
  parameters `m=32 MiB, t=2` because the device-bound Keystore wrap is
  the primary defense and on-device rate-limiting prevents brute force.
- **AES-256-GCM** for note content with **AAD = `note_id`** (prevents
  ciphertext substitution between notes).
- **KEK wrap with AAD = `folder_id`** (prevents wrap reuse across
  folders).
- **HMAC verifier in constant time** to detect bad passphrase / PIN
  without trial-decrypting every note.
- SQLCipher 4 (AES-256-CBC + HMAC-SHA512), key stored via
  `flutter_secure_storage` 10.x: AES-GCM storage cipher, storage key
  wrapped with RSA-OAEP by an Android Keystore key.

### Panic mode — ordered multi-step
The panic sequence is deterministic and best-effort (a step that fails
does not abort the next ones; the failure is recorded in the panic report
and the final screen reports an incomplete wipe):

1. **`forceSecureWindow`** — `FLAG_SECURE` forced ON
2. **`voiceCancel`** — microphone capture stopped (the temporary WAV is
   deleted)
3. **`clipboardClear`** — clipboard cleared
4. **`foldersLockAll`** — lock every open vault, wipe folderKek from RAM
5. **`pinKeysWipe`** — `deleteKeysWithPrefix("vault_pin_")` (Kotlin)
6. **`kekDestroy`** — destroy the master Keystore key (DB instantly
   unreadable)
7. **`pauseBackgroundWork`** — background workers paused (backlinks)
8. **`dbWipe`** — overwrite SQLCipher header (16 MiB cap) + delete
   `.db`, `.db-journal`, `.db-wal`, `.db-shm`
9. **`voiceWipe`** — Whisper model, verification cache and orphan WAV
   files deleted
10. **`legacyModelsWipe`** — purge `<appSupport>/models/` (model files
    left by versions ≤ 1.1.6, which shipped on-device AI)
11. **`prefsClear`** — preferences cleared, except `db_encrypted_v1` and
    `secure_window_enabled`
12. **`exportsWipe`** — `exports/` folders purged (temporary and cache
    directories)
13. **`tmpPurge`** — temporary directory purged

### Accepted limits
- Forensic recovery from a physical memory dump of an unlocked, rooted
  device is partially possible (Dart heap GC eventually recycles
  strings, but a snapshot during use can leak plaintext).
- A determined nation-state attacker with custom kernel exploits is
  out of scope.
- Display privacy (`FLAG_SECURE`) is on by default but opt-out is
  possible in Settings.

## Responsible disclosure

We follow a 90-day disclosure window by default:
1. **Day 0**: your report received.
2. **Day 0-7**: initial triage, severity assigned.
3. **Day 7-60**: fix developed, tested, audited.
4. **Day 60-90**: release with patched version, public CVE if
   applicable.
5. **Day 90+**: you're free to publish your write-up.

Critical issues (RCE, key extraction, full data exfiltration) may be
patched faster than 90 days.

## Security audits run on each release

Each release is checked via:
- `flutter analyze` (strict lints)
- `flutter test`
- `bash j:\applications\health_check.sh notes_tech`:
  - OSV-Scanner (CVE in dependencies)
  - gitleaks (secrets in git history)
  - Manifest hardening (no `debuggable`, no `cleartextTraffic`,
    no excessive permissions)
  - Signing config (R8 enabled, no debug fallback for release)
  - FileProvider (no `<root-path>`, no global app-private exposure)
  - Crypto patterns (no MD5/SHA-1 for security, PBKDF2 ≥ 100k iter)
  - Kotlin patterns (`canonicalFile`, `FLAG_IMMUTABLE` PendingIntent)
- 4-agent audit (architecture / security / performance / coherence)
  for material features.

---

**Source code**: https://github.com/gitubpatrice/notes_tech
**License**: Apache License 2.0

## Design decisions

- **DB header wipe capped at 16 MiB**: the preceding `kekDestroy`
  already guarantees cryptographic secrecy (the whole database becomes
  unreadable without the destroyed Keystore key). Overwriting the full
  file brings nothing on modern eMMC / UFS with wear-leveling: physical
  blocks no longer map to logical blocks. 16 MiB is enough to neutralize
  the SQLCipher header and a reasonable prefix. Marginal benefit vs panic
  mode latency → 16 MiB.
- **`setUserAuthenticationRequired(false)` on PIN Keystore keys**
  (added in v0.9.4): the in-app PIN is the vault's only authentication
  factor. Doubling it with a biometric requirement would expose the user
  to physical coercion (an attacker can force a finger onto the sensor,
  and a biometric-derived key survives reboot). The PIN alone, combined
  with device-bound Keystore sealing and the 5-attempt auto-wipe, offers a
  better trade-off for the "coercion" threat model.
- **AAD = `folder_id` / `note_id`**: prevents a local attacker from
  extracting an encrypted blob and replaying it in the context of another
  folder or another note (no possible confusion between distinct
  cryptographic contexts).
- **Backlinks reindex deferred by 2 s** (v0.9.3): avoids quadratic cost
  during active typing, while guaranteeing index consistency before any
  close / lock of the vault.
