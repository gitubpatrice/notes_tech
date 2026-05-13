# Security policy — Notes Tech

**Version current : v1.0.9 — Mai 2026.**

## v1.0.9 — Audit expert post-v1.0.8 (2026-05-13)

Suite à un audit 3-agents (sécu / perf / UX), 11 corrections livrées.
Aucun changement de format DB (toujours v6) ni de format coffre.
`flutter analyze` 0 issue, tests verts.

### Sécurité

- **F1** — `FolderVaultService.unlock()` (mode passphrase) bénéficie
  désormais du même lockout exponentiel monotonique que `unlockWithPin()`
  (M-05 v1.0.7) : compteur RAM `_passFailCount` + backoff
  `1/2/4/8/16/30 s` après 5 essais, levant `VaultLockoutInProgressException`.
  Avant : sur S24+ flagship Argon2id m=64Mo t=3 prenait ~600-900 ms →
  un attaquant ADB + dictionnaire 10k passphrases pouvait tester ~4
  essais/s sans friction. API publique `passphraseLockoutRemaining()`
  exposée pour countdown UI symétrique au PIN.
- **F3** — `BacklinksService.suggestTitles()` filtre maintenant
  `n.isLocked`. Avant : l'auto-complétion `[[…]]` dans une note alive
  proposait les titres des notes verrouillées → fuite par défaut depuis
  la création des coffres. Aligne sur M-01 v1.0.7 (`_indexByTitle`,
  `_handleSingleChange`, `_reindexAll` qui skippaient déjà locked).
- **F7** — `RagService.composePrompt` applique désormais `_sanitize`
  au `userPrompt` (les titres et bodies des sources étaient déjà
  sanitizés). Couvre une injection arrivant via dictée vocale ou
  auto-paste (`<|system|>`, zero-width, bidi).
- **F8** — `note_editor_screen` pose `FLAG_SECURE` (`_ensureSecureForced`)
  AVANT `vault.decryptNote`. Avant : fenêtre ~5-20 ms (channel
  round-trip) pendant laquelle un screenshot manuel ou MediaProjection
  pouvait capter le plaintext entre `decryptNote` et `_ensureSecureForced`.

### Performance

- **P1.2** — `note_editor_screen._changesSub` filtre maintenant les
  événements (`event.id != widget.noteId && !event.isBulk` → return).
  Avant : `get(noteId)` re-déclenché sur CHAQUE event (y compris ses
  propres saves + tous les autres éditeurs ouverts) → 1 SELECT
  SQLCipher/s minimum en auto-save continu (debounce 500 ms).
- P1.1 (backlinks title cache) et P1.4 (notes_repository.save without
  systematic `findById`) reportés à v1.1 (refactors plus profonds).

### UX / a11y

- **U1+U2+U11** — `PassphraseTextField` (centralisé) ajoute
  `autofillHints: const []` (désactive Samsung Pass / Google Autofill),
  `keyboardType: TextInputType.visiblePassword` (neutralise
  SwiftKey/Gboard auto-cap), et `enableInteractiveSelection: !_hidden`
  (bloque sélection/copie quand masqué — anti clipboard manager).
- **U3** — `confirmDialog` (helper centralisé `app_dialogs.dart`) :
  bouton Annuler `autofocus: true` quand le dialog est destructif +
  bouton confirme via `cs.errorContainer/onErrorContainer` au lieu
  de `cs.error` brut.
- **U4** — `about_screen` icône `Image.asset` avec `cacheWidth: 112`
  / `cacheHeight: 112` (avant : PNG 1024×1024 décodé sans borne pour
  afficher 56dp = ~12 Mo RAM permanent).
- **U9** — Empty state home : `FilledButton.tonalIcon` "Nouvelle note"
  inline en plus du FAB (plus découvrable au premier lancement).

### Info-only nettoyés (analyze 0 issue)

9 occurrences `SemanticsService.announce` annotées
`// ignore: deprecated_member_use` (migration Flutter 3.35
`sendAnnouncement` prévue v1.1), 2 `directives_ordering`
(home_screen / settings_screen imports triés), 3 `prefer_const`
dans `panic_service_test.dart`.

### Tests

Tests existants tous verts (64+ assertions). Le test e2e flow
`unlock → wrong passphrase × 5 → lockout` est volontairement déféré
à l'instrumentation (Keystore mock non-trivial en pure Dart, cf.
`folder_vault_service_test.dart`).

---

**Version précédente : v1.0.4 — Mai 2026.**

Notes Tech v1.0 introduit plusieurs durcissements sécurité :
- `prefs.clear()` panique avec **whitelist** (`db_encrypted_v1`,
  `secure_window_enabled` préservés) conformément à `PRIVACY.md`.
- `flutter_markdown` cantonné aux pages légales locales (assets), aucun
  rendu de markdown distant.
- `ProGuard` rules complètes : `files_tech_voice`, `flutter_markdown`,
  MediaPipe, ONNX, sqflite, flutter_gemma.

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
**local-only** notes with strong cryptographic guarantees. Three
adversary classes are considered :

### 1. Loss / theft (lost or stolen unlocked device)
- **Confidentiality at rest** : SQLCipher (AES-256) for the database,
  AES-256-GCM for per-folder vault notes. KEK sealed by Android
  Keystore (hardware-backed on modern devices).
- Per-folder vault adds a second factor (passphrase or PIN) on top of
  the device lockscreen.

### 2. Coercion (search, "give me your phone", border check)
- **Panic mode** : a confirmed delete-everything action that runs a
  deterministic ordered sequence (see below). Designed to be fast and
  irrecoverable under coercion.
- **PIN auto-wipe** : 5 failed PIN attempts on a vault wipes that
  vault's keys atomically (with crash-resume via prefs flag).
- **`setUserAuthenticationRequired(false)`** on PIN Keystore keys : the
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
- **Argon2id RFC 9106** for passphrase derivation : `m=64 MiB, t=3,
  p=1, 32-byte output` (vault default). PIN mode uses lighter
  parameters `m=32 MiB, t=2` because the device-bound Keystore wrap is
  the primary defense and on-device rate-limiting prevents brute force.
- **AES-256-GCM** for note content with **AAD = `note_id`** (prevents
  ciphertext substitution between notes).
- **KEK wrap with AAD = `folder_id`** (prevents wrap reuse across
  folders).
- **HMAC verifier in constant time** to detect bad passphrase / PIN
  without trial-decrypting every note.
- SQLCipher 4 (AES-256-CBC + HMAC-SHA512), key sealed via
  `flutter_secure_storage` → `EncryptedSharedPreferences` → Keystore.

### Panic mode — ordered multi-step
The panic sequence is deterministic and best-effort (a step that
throws does not abort the next ones) :

1. `FLAG_SECURE` forced ON, microphone capture stopped
2. **`foldersLockAll`** — lock every open vault, wipe folderKek from RAM
3. **`pinKeysWipe`** — `deleteKeysWithPrefix("vault_pin_")` (Kotlin)
4. **`kekDestroy`** — destroy the master Keystore key (DB instantly
   unreadable)
5. Background workers paused (coordinator / indexing / backlinks)
6. **`dbWipe`** — overwrite SQLCipher header (16 MiB cap) + delete
   `.db`, `.db-wal`, `.db-shm`
7. Whisper / Gemma model files deleted, all prefs cleared, tmp purged

### Accepted limits
- Forensic recovery from a physical memory dump of an unlocked, rooted
  device is partially possible (Dart heap GC eventually recycles
  strings, but a snapshot during use can leak plaintext).
- A determined nation-state attacker with custom kernel exploits is
  out of scope.
- Display privacy (`FLAG_SECURE`) is on by default but opt-out is
  possible in Settings.

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

- **Wipe DB header plafonné à 16 Mo** : la `kekDestroy` précédente
  garantit déjà le secret cryptographique (la base entière devient
  illisible sans la clé Keystore détruite). Écraser le fichier complet
  n'apporte rien sur eMMC / UFS moderne avec wear-leveling : les
  blocs physiques ne correspondent plus aux blocs logiques. 16 Mo
  suffisent pour neutraliser le header SQLCipher et un préfixe
  raisonnable. Bénéfice marginal vs latence du panic mode → 16 Mo.
- **`setUserAuthenticationRequired(false)` sur les clés Keystore PIN**
  (ajouté en v0.9.4) : le PIN applicatif est l'unique facteur
  d'authentification du coffre. Le doubler par une exigence biométrique
  exposerait l'utilisateur à la contrainte physique (un attaquant peut
  forcer un doigt sur le capteur, et une clé dérivée biométrique
  survit au reboot). Le PIN seul, combiné au scellage Keystore
  device-bound et à l'auto-wipe à 5 essais, offre un meilleur compromis
  pour le modèle de menace « contrainte ».
- **AAD = `folder_id` / `note_id`** : empêche un attaquant local
  d'extraire un blob chiffré et de le rejouer dans le contexte d'un
  autre dossier ou d'une autre note (aucune confusion possible entre
  contextes cryptographiques distincts).
- **Reindex backlinks différé 2 s** (v0.9.3) : évite le coût quadratique
  sur saisie active, tout en garantissant la cohérence de l'index avant
  toute fermeture / lock du coffre.
