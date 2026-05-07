# Security policy — Notes Tech

## Reporting a vulnerability

If you believe you've found a security issue in Notes Tech, please
**do not open a public GitHub issue**. Instead, email :

📧 **contact@files-tech.com**

Subject : `[SECURITY] Notes Tech — <short summary>`

Include :
- A description of the issue and its potential impact.
- Steps to reproduce (or a proof-of-concept).
- Affected version (Réglages → À propos → Notes Tech vX.Y.Z).
- Your contact for follow-up.

You'll get an acknowledgement within **72 hours**. A coordinated
disclosure timeline will be agreed upon if the issue is confirmed.

## Scope

### In scope
- Notes Tech app code (`lib/`, `android/`)
- Module sibling `files_tech_voice` if relevant
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
**local-only** notes with strong cryptographic guarantees :

- **Confidentiality at rest** : AES-256-GCM via SQLCipher, key sealed
  by Android Keystore (hardware-backed on modern devices).
- **No network exposure** : the `INTERNET` permission is removed from
  the manifest. Data exfiltration through standard channels is
  technically impossible without re-installing a modified APK.
- **Panic mode** : a confirmed delete-everything action that destroys
  the master key and overwrites the database header. Designed to be
  fast and irrecoverable under coercion.

We accept the following limits :
- Forensic recovery from physical memory dumps of an unlocked,
  rooted device is partially possible (Dart heap GC eventually
  recycles strings).
- A determined nation-state attacker with custom kernel exploits is
  out of scope.
- Display privacy (FLAG_SECURE) is opt-in via Settings.

## Responsible disclosure

We follow a 90-day disclosure window by default :
1. **Day 0** : your report received.
2. **Day 0-7** : initial triage, severity assigned.
3. **Day 7-60** : fix developed, tested, audited.
4. **Day 60-90** : release with patched version, public CVE if
   applicable.
5. **Day 90+** : you're free to publish your write-up.

Critical issues (RCE, key extraction, full data exfiltration) may be
patched faster than 90 days.

## Security audits run on each release

Each release is checked via :
- `flutter analyze` (lints stricts)
- `flutter test`
- `bash j:\applications\health_check.sh notes_tech` :
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

**Code source** : https://github.com/gitubpatrice/notes_tech
**Licence** : Apache License 2.0

## Décisions de design

- **Wipe DB header** : plafonné à 16 Mo (la KEK destroy précédente garantit
  le secret ; l'écrasement complet n'apporte rien sur eMMC moderne avec
  wear-leveling).
- **`setUserAuthenticationRequired(false)`** sur la clé Keystore PIN : le
  PIN applicatif est l'unique facteur d'authentification ; le doubler avec
  biométrie l'exposerait à la contrainte (clé dérivée biométrique survit
  au reboot).
