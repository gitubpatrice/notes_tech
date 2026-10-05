# Security policy — Notes Tech

*Version française : [SECURITY.fr.md](SECURITY.fr.md)*

**Current version: v3.0.0 — October 2026.** Notes Tech 3.0 is a rewrite in Kotlin. The security log
of the Flutter versions 1.x and 2.x is kept as it was, in
[`SECURITY.md` at tag v2.0.9](https://github.com/gitubpatrice/notes_tech/blob/v2.0.9/SECURITY.md).

## v3.0.0 — Kotlin rewrite and full security audit (2026-09-26)

The port was audited before release by a researcher-and-adversarial-panel method: five researchers,
one component and one angle each, then three verifiers per candidate whose job was to refute it.
**No critical and no high finding. 3 medium, 13 low — every one fixed, each with a test and a
negative control** (the defect put back to check that the test catches it). The full report, in
French, is in [`audits/securite-2026-09-26.md`](audits/securite-2026-09-26.md).

The three medium findings:

- **V1** — closing a vault left the decrypted note on screen in the editor. The editor now clears it
  and asks for the secret again.
- **K1** — deleted text, and text moved into a vault, stayed in the full-text index. FTS5 now runs in
  `secure-delete` mode, SQLite with `secure_delete = ON`, and existing indexes are purged once.
- **K4** — before Android 9, a PIN vault's key cannot be bound to the phone's unlock, so the
  five-attempt limit does not hold against a seized phone. No new PIN vault is created there any more;
  an existing one still opens and says so.

## Supported versions

Only the **latest published release** receives security fixes: the app has no network access and
never updates itself, so a fix only ships in a new version.

## Reporting a vulnerability

If you believe you've found a security issue in Notes Tech, please **do not open a public GitHub
issue**. Use GitHub private reporting — the repository's *Security* tab, then *Report a
vulnerability* (https://github.com/gitubpatrice/notes_tech/security/advisories/new) — or email:

📧 **contact@files-tech.com**

Subject: `[SECURITY] Notes Tech — <short summary>`

Include:
- A description of the issue and its potential impact.
- Steps to reproduce (or a proof-of-concept).
- Affected version (Settings → About Notes Tech).
- Your contact for follow-up.

You'll get an acknowledgement within **72 hours**. A coordinated disclosure timeline will be agreed
upon if the issue is confirmed.

## Scope

### In scope
- The app code (`app/src/main/`), including the JNI bridge `app/src/main/cpp/notes_stt_jni.cpp`
- Crypto: database key handling, vault and app lock keys, panic mode irreversibility
- Permission handling (`RECORD_AUDIO`, biometrics)
- File handling: model import, exports, path traversal

### Out of scope
- Issues in third-party code — including the vendored whisper.cpp sources — unless the app's use of
  it creates the issue (please report upstream too).
- Issues requiring a rooted device or pre-existing malware with elevated privileges.
- Social engineering against the user.
- Denial of service through deliberately oversized notes or files.

## Threat model

Notes Tech is designed for people who want **local-only** notes with strong cryptographic guarantees.
Three adversary classes are considered.

### 1. Loss or theft of the phone
- **Confidentiality at rest**: SQLCipher (AES-256) for the database, AES-256-GCM for the notes of a
  vault. The database key is sealed by the Android Keystore (hardware-backed on modern devices).
- A vault adds a second factor (passphrase or PIN) on top of the phone's screen lock; the app lock
  adds one in front of the whole app.

### 2. Coercion (search, "give me your phone", border check)
- **Panic mode**: a confirmed wipe that runs an ordered, deterministic sequence (below).
- **PIN auto-wipe**: 5 failed PIN attempts on a vault delete that vault's key, atomically, resumed at
  the next launch if interrupted.
- **No biometric factor on vault keys** (`setUserAuthenticationRequired(false)`): the PIN is the only
  factor — a finger can be forced onto a sensor. Biometrics exist only for the **app lock**, and they
  are off by default.

### 3. Malware in another app on the same phone
- No `INTERNET` permission: a compromised dependency has no standard network path to send notes.
- No foreground service, no notification, no boot receiver: minimal attack surface.
- `FLAG_SECURE` (on by default) blocks screenshots and the recent-apps preview.
- `allowBackup=false` and `dataExtractionRules` block Android backup and device-transfer copies.
- In a vault note, copying goes through a clipboard entry marked sensitive, cleared after 60 seconds,
  and the keyboard is asked not to learn (`IME_FLAG_NO_PERSONALIZED_LEARNING`).

## Crypto building blocks

- **Argon2id** (RFC 9106, Bouncy Castle): `m = 64 MiB, t = 3, p = 1`, 32-byte output for a passphrase;
  `m = 32 MiB, t = 2` for a vault PIN and for the app lock PIN, whose Keystore keys and attempt limits
  are the main defence.
- **AES-256-GCM** for note content with **AAD = note id**, and for the vault key wrap with
  **AAD = folder id**: an encrypted blob cannot be replayed in another note or folder.
- **Constant-time verifier** to detect a wrong passphrase or PIN without trial-decrypting the notes.
- **Database key (KEK)**: 32 random bytes, sealed by an `AndroidKeyStore` AES-256-GCM key. SQLCipher 4
  (AES-256, HMAC-SHA512).
- **Vault PIN keys**: one `AndroidKeyStore` key per vault, bound to the phone's unlock
  (`setUnlockedDeviceRequired`, Android 9+).
- **App lock**: the PIN goes through Argon2id, then an HMAC-SHA256 key held by the Keystore; five free
  attempts, then delays doubling from 30 seconds up to one hour. The optional biometric unlock uses
  its own Keystore key, usable only through a strong (class 3) biometric in a `CryptoObject`.

## Panic mode — ordered, multi-step

Settings → Panic mode, confirmed by typing the word it asks for (`WIPE` in English). The sequence is deterministic and best-effort: a
failing step does not stop the next ones, it is recorded, and the final screen reports an incomplete
wipe. A journal written before the first step makes an interrupted sequence **resume at the next
launch**.

1. **Secure window** — `FLAG_SECURE` forced on
2. **Dictation** — recording forbidden and the current one abandoned, before anything else
3. **Clipboard** — cleared
4. **Vaults** — every open vault locked, keys erased from memory
5. **Vault PIN keys** — every `vault_pin_*` Keystore key deleted, orphans included
6. **App lock keys** — its PIN verifier key and its biometric key deleted
7. **Database key** — destroyed: from here on the database is noise
8. **Exports** — archives holding the full text of notes erased
9. **Recordings** — dictation recordings erased
10. **Database** — closed, header overwritten (16 MiB), file and sidecars deleted
11. **Voice model** — the imported model deleted
12. **Legacy models** — model files left by versions ≤ 1.1.6
13. **Preferences** — cleared, except `secure_window_enabled`, `db_encrypted_v1` and the panic journal
14. **Cache** — previews, temporary files, library leftovers

## Accepted limits

- **The database key is usable while the phone is locked**, by code running as the app. It is
  deliberately not bound to the phone's unlock: Android deletes such a key when the screen lock is
  removed, which would destroy the database with it. Vaults keep their own secret.
- **A copy of the database key kept by the 2.x versions** (`flutter_secure_storage`) is left in
  place: on Android 12 and later it is what recovers the database when Android has deleted the key
  the 2.x bridge created. Only panic mode removes it.
- **Removing the phone's screen lock deletes the keys of PIN vaults** (Android does this to keys bound
  to the unlock): their notes become unreadable. The mode chooser warns before a PIN vault is
  created; a passphrase vault does not depend on the screen lock.
- **A PIN vault created before Android 9** (by a 2.x version) does not hold its five-attempt limit
  against a seized phone. The app says so on that vault.
- A memory dump of an unlocked, rooted phone during use can reveal plaintext.
- A determined attacker with custom kernel exploits is out of scope.
- `FLAG_SECURE` is on by default but can be turned off in Settings.

## Responsible disclosure

We follow a 90-day disclosure window by default:
1. **Day 0**: your report received.
2. **Day 0-7**: initial triage, severity assigned.
3. **Day 7-60**: fix developed, tested, audited.
4. **Day 60-90**: release with patched version, public CVE if applicable.
5. **Day 90+**: you're free to publish your write-up.

Critical issues (key extraction, full data exfiltration) may be patched faster.

## Checks run before a release

- Unit tests (JVM) and instrumented tests on real phones (Android 10 and 16)
- Android lint, detekt, ktlint
- [`tools/check-manifest-permissions.py`](tools/check-manifest-permissions.py) on the **merged**
  release manifest: no network permission may appear
- Security audit with an adversarial panel for material changes, and external reviews

## Design decisions

- **Database header wipe capped at 16 MiB**: destroying the Keystore key just before already makes the
  whole database unreadable. Overwriting the full file brings nothing on flash storage with
  wear-leveling, where physical blocks no longer match logical ones.
- **No user authentication on vault PIN keys**: the in-app PIN is the vault's only factor; a biometric
  requirement would expose the user to a forced fingerprint, and a biometric-bound key survives a
  reboot.
- **AAD = folder id / note id**: no confusion is possible between distinct cryptographic contexts.
- **No unlocked-device binding on the database key** — see *Accepted limits*.

---

**Source code**: https://github.com/gitubpatrice/notes_tech
**License**: Apache License 2.0
