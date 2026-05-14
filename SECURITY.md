# Security policy — Notes Tech

**Version current : v1.1.0 — Mai 2026.**

## v1.1.0 — Audit expert post-v1.0.9 (2026-05-14)

Suite à un audit 3-agents (sécu / perf / UX) + audit cross-files,
23 corrections livrées (F1-F14, P1-P5, U1-U11). Aucun changement
de format DB ni de format coffre. `flutter analyze` 0 issue, 68/68
tests verts (+5 nouveaux `test/audit_v1_1_0_test.dart`).

### Sécurité

- **F1** — `note_editor._moveToFolder` : confirmation EXPLICITE
  (dialog destructif `cs.errorContainer` + Cancel autofocus) avant
  de sortir une note d'un coffre vers un dossier ordinaire. Avant :
  le contenu était décrypté + persisté en clair sans signal UI,
  irréversible. Si l'auto-lock du coffre tombait pendant la mutation,
  l'utilisateur croyait l'écran fermé alors que le flush plaintext
  passait silencieusement.
- **F2** — `NotesDao.findByTitleLike` : ajout du filtre SQL
  `encrypted_content IS NULL`. Avant : `BacklinksService.suggestTitles`
  (F3 v1.0.9) filtrait côté Dart, mais le DAO sous-jacent exposait
  toutes les notes locked à tout futur caller, et le `limit` SQL
  était consommé par les notes vault AVANT le filtre Dart → les
  suggestions s'amincissaient sur les gros coffres sans raison
  apparente. Defense-in-depth.
- **F3** — `IndexingService._indexAll` : skip explicite des notes
  vault AVANT `_encodeWith(embedder, note)`. Avant : si
  `knownHashes[n.id]` ne matchait pas pour une note locked
  (hash stale), MiniLM encodait son contenu en RAM côté worker
  ONNX, et l'embedding n'était écarté qu'APRÈS l'encoding via
  `live.encryptedContent != null`. Désormais aucun feed à l'embedder
  pour les notes locked, quel que soit le hash.
- **F4** — `NoteActions.copyMarkdown` : MethodChannel natif Kotlin
  `com.filestech.notes_tech/clipboard.copySensitive` qui pose
  `ClipDescription.EXTRA_IS_SENSITIVE` (Android 13+) + auto-clear
  60 s du presse-papier côté Dart (vérifie que la valeur courante
  est encore celle qu'on a posée avant clear, ne touche pas un
  autre secret copié entretemps). Avant : `Clipboard.setData` brut
  exposait le plaintext d'une note vault déchiffrée à TOUT clipboard
  manager tiers + Knox clipboard history sans expiration.
- **F5** — `ai_chat_screen._resolveSource` : suppression du
  `initialDirectory: '/storage/emulated/0/Download'`. Avant : path
  absolu nécessitant READ_EXTERNAL_STORAGE (sinon SAF picker vide
  silencieusement) ET ouvrait sur Downloads d'autres apps
  (Telegram, WhatsApp) ouvrant la voie à un `.task` malveillant
  non lié au flux SAF maître.
- **F6** — `VoiceService._isPresentAndPlausible` : TTL du cache de
  vérification SHA-256 réduit de 30 jours à 24 heures, et refus
  si `cached.mtimeMs > cached.verifiedAtMs` (file touché après
  notre dernière vérif réussie). Avant : un attaquant root pouvant
  écrire un Whisper trojanisé avec `touch -t` matchant (size, mtime)
  restait validé 30 jours sans rehash. Coût utilisateur : ~3-5 s
  de hash strict au premier `startRecording` post-24h.
- **F7** — `PanicService` : nouvelle étape `_wipeExportsCache` qui
  purge `getApplicationCacheDirectory()/exports/`. Avant : un ZIP
  d'export en cours de Share survivait à panic car
  `_purgeTempDirectory` ne couvrait que `getTemporaryDirectory()`.
- **F8** — `RagService._sanitize` étendu : Llama2 `<<SYS>>`,
  ChatML `<|im_start|>` / `<|im_end|>`, Alpaca/Vicuna
  `### Instruction:` / `### Response:`, Mistral `[ASSISTANT]` /
  `[USER]` brackets neutralisés. Avant : Gemma 3 1B (decoder
  generaliste pré-entraîné sur ces formats) pouvait basculer en
  mode chat formel si un attaquant insérait ces marqueurs dans une
  note contexte RAG. F13 v1.0.3 listait ces patterns comme
  best-effort, désormais couverts.
- **F10** — `FolderVaultService._unlockInProgress: Set<String>`
  guard sur `unlock()` / `unlockWithPin()`. Avant : Dart est
  mono-thread mais Argon2id `compute()` (600-900 ms sur S24)
  cède l'event-loop entre `await` — un `Timer(_autoLockAfter)`
  pouvait alors firer pendant le unlock et wiper la `folder_kek`
  fraîchement assignée avant qu'elle ne soit consommée par
  `encryptNote`. `_autoLockSweep` skip désormais les folderIds
  en cours de unlock.
- **F11** — `note_editor._flushFinalSave` : si le coffre est
  verrouillé pendant le flush final (dispose post-auto-lock), on
  persiste l'`id` dans `prefs.vault_lost_drafts`. Avant : « perte
  acceptée » silencieuse, l'utilisateur croyait l'auto-save
  infaillible. Consommable par un futur écran « N modifications
  perdues sur des notes vault » au prochain boot.
- **F14** — `AppDatabase._attachSql` : validation regex stricte
  `^[A-Za-z0-9_./:\\-]+$` du path AVANT l'`ATTACH`. Avant : le
  path provenait de `getApplicationDocumentsDirectory()`, qui peut
  être détourné via `LD_PRELOAD` / root setup pointant vers un
  chemin contenant des méta-SQL (`'; DROP --`). Cas extrême
  root-only mais c'est la « source unique de vérité » de la DB.

### Performance

- **P1** — `HomeScreen._reloadDebouncer` (250 ms) coalesce les
  events `notes.changes` pendant l'auto-save continu (1 event/500
  ms par frappe). Avant : un SELECT complet `listAllAlive` exécuté
  à CHAQUE event, soit ~50-200 ms SQLCipher sur 500 notes ×
  fréquence de frappe.
- **P2** — `isUniversalApk = false` dans `build.gradle.kts`.
  Avant : générait un 4ᵉ APK universel ~294 Mo embarquant les libs
  natives des 3 ABIs (sqlcipher + ONNX + Whisper + MediaPipe).
  Économie ~70 Mo upload GitHub Releases + bandwidth user.
- **P3** — `BacklinksService._buildTitleIndex` : cache TTL 5 s
  invalidé explicitement sur changement de titre. Avant :
  `listAllAlive()` re-exécuté à CHAQUE save d'une note (rafale
  d'auto-saves = 1 SELECT/500ms même sans mutation de titre).
- **P5** — `MentionsLegalesScreen._MarkdownAssetView._load` :
  cache `static final Map<String, String>` process-wide des
  assets `.md`. Avant : `rootBundle.loadString` re-exécuté à
  CHAQUE switch d'onglet TabBarView ou de locale.

### UX / a11y

- **U1** — `HapticFeedback.selectionClick()` sur copy Markdown
  + `HapticFeedback.heavyImpact()` sur déclenchement panique.
  Avant : 0 hit `HapticFeedback` dans tout `lib/` — aucun
  feedback tactile pour les actions critiques (alignement avec
  Pass Tech v2.4.4 U9 / AI Tech U4).
- **U2** — `SnackbarMessengerExt.showFloatingSnack` accepte
  désormais `foregroundColor`. 2 sites `folders_drawer` mis à
  jour : `cs.errorContainer` + `cs.onErrorContainer` (contraste
  WCAG AA ~13:1 en light mode vs ~3.5:1 mesuré avec `cs.error`
  brut sur `textPri` clair).
- **U3** — TextField titre + contenu note : `textCapitalization:
  TextCapitalization.sentences`. Avant : saisie tactile à doigt
  unique sans capitalisation auto → titres avec minuscules
  initiales.
- **U11** — TextField composer AI : `textCapitalization:
  TextCapitalization.sentences`.

---

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
