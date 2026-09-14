# Politique de sécurité — Notes Tech

*English version: [SECURITY.md](SECURITY.md)*

> **⚠️ État au 2026-08-07 — retrait de l'IA embarquée (v2.0.0).** Notes Tech
> n'embarque plus ni moteur d'inférence Gemma, ni recherche sémantique
> (MiniLM / ONNX). Les entrées d'historique ci-dessous qui les mentionnent
> décrivent des correctifs **réellement appliqués à l'époque** : elles sont
> conservées telles quelles, réécrire un journal de sécurité serait pire que
> de le laisser dater. Seules les affirmations d'état courant ont été mises
> à jour.

**Version courante : v2.0.9 — Septembre 2026.**

## v2.0.0 — Retrait de l'IA + durcissement coffre/panique (2026-08-07)

Retrait de la recherche sémantique (MiniLM/ONNX) et du Q&A Gemma. Surface
d'attaque réduite d'autant : plus d'isolate d'inférence, plus de prompt
injecté, plus de cache d'embeddings en clair à protéger. APK arm64
~127 Mo → 26,9 Mo.

### Failles corrigées

- **Perte de données irréversible à la suppression d'un dossier coffre.**
  `folders_drawer.dart` détruisait la clé Keystore PIN **avant** la ligne
  en base. Si la suppression DB échouait ensuite, le dossier et ses notes
  chiffrées restaient présents alors que la clé nécessaire au
  déverrouillage avait disparu — coffre définitivement illisible. L'ordre
  est inversé : base d'abord, clé ensuite, sous `try/catch`.
- **Le mode panique mentait sur son résultat.** Trois étapes
  (`_prefsClearWithWhitelist`, `_wipeExportsCache`, `_purgeTempDirectory`)
  avalaient leurs échecs unitaires et l'écran final affichait « effacement
  terminé » alors que des artefacts survivaient. Elles comptent désormais
  leurs échecs et lèvent ; `_wipeExportsCache` purge en outre le répertoire
  de cache en plus du temporaire. Le filtre `_estArtefactSensible` évite
  l'écueil inverse — un fichier temporaire étranger à l'app ne doit pas
  faire déclarer la panique incomplète.
- **Titre d'une note de coffre écrit en clair.** Le scellement était porté
  par l'appelant et non par la couche de persistance : tout chemin
  d'écriture qui l'oubliait laissait le titre lisible dans la colonne.
  `NotesRepository` refuse maintenant toute écriture en clair dans un
  dossier coffre (`VaultPlaintextWriteException`) et scelle lui-même.
- **Presse-papiers non purgé après copies successives.** La minuterie
  d'effacement d'une copie pouvait remettre l'état à zéro alors qu'une
  copie plus récente était en cours, laissant son contenu indéfiniment
  dans le presse-papiers. Un jeton par copie ferme la course.
- **Les migrations de schéma n'avaient jamais été exercées.** Les tests
  créaient des bases neuves : `onCreate` posait le schéma courant et
  `onUpgrade` n'était jamais appelé. `integration_test/db_migration_test.dart`
  construit désormais de vraies bases héritées (v1, v5, v7) et vérifie la
  migration réelle jusqu'en v9, dont la purge FTS d'une note verrouillée.

### Vérification

CI GitHub Actions verte de bout en bout pour la première fois : analyse,
65 tests unitaires, build, et les 5 suites d'intégration sur émulateur
API 30. Scan OSV des dépendances : aucune CVE.

## v1.1.6 — Logo & réorganisation barre du haut (2026-07-09)

Changement UI sans impact sécurité : logo damier dans l'AppBar, actions
secondaires (Réglages / À propos) regroupées dans un overflow `⋮`, et bouton
« Vérifier les mises à jour » dans À propos. Ce bouton **délègue au navigateur
système** l'ouverture de la page GitHub releases (même pattern que le
téléchargement des modèles voix) — **aucune permission INTERNET ajoutée** : la
promesse zéro-réseau reste vérifiable dans le manifeste (`tools:node="remove"`).

## v1.1.5 — Audit expert post-v1.1.4 (2026-07-07)

Audit 4-agents (sécu / perf-qualité / câblage / cohérence-i18n) sur ~24,8k LOC.
Aucune faille CRITICAL/HIGH. Corrections sécu/robustesse + features anti-perte
de données. `flutter analyze` 0 issue, 84 tests verts.

### Sécurité / robustesse

- **Purge des sidecars WAL/SHM/journal EN CLAIR** lors de la migration DB
  héritée plain → chiffré (`database.dart._migratePlainToEncrypted`). Le mode
  `journal_mode = WAL` laissait `notes_tech.db-wal`/`-shm` contenant des pages
  de notes en clair, survivant à la migration (fuite sur device rooté /
  extraction physique). Désormais purgés après `sqlcipher_export` + checkpoint,
  symétriquement au wipe du `.plain.bak`.
- **Résilience du cache d'embeddings** (`embeddings_dao.listByModel`) : une
  ligne empoisonnée (blob de taille incohérente) ne condamne plus tout le
  chargement du cache sémantique — décodage tolérant par ligne.
- **Gardes `mounted`** ajoutées (`ai_chat` après l'isolate MiniLM, `search`
  post-frame) : plus de `setState` après `dispose`.
- **Signalement anti-perte vault** : les notes de coffre dont la dernière
  modification a été perdue (coffre verrouillé pendant la sauvegarde) sont
  désormais signalées par un banner à l'ouverture — la pref `vault_lost_drafts`
  (F11 v1.1.0) était écrite mais jamais relue.
- **Vidage du presse-papiers en mode panique** : nouveau step
  `PanicStep.clipboardClear` (tôt dans la séquence, après `voiceCancel`) qui
  appelle `NoteActions.cancelAndClear()`. Une note copiée
  (`copyMarkdown`) restait en clair dans le presse-papiers jusqu'à
  l'auto-clear 60 s ; en panique on n'attend plus ce délai.

### Maintenance

- Purge de **99 clés l10n orphelines** (jamais référencées) des ARB FR/EN —
  parité conservée (380 clés chacune), `flutter analyze` 0 issue confirme
  qu'aucune n'était utilisée.

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
  `secure_window_enabled` préservés) conformément à `assets/legal/PRIVACY.fr.md`.
- `flutter_markdown` cantonné aux pages légales locales (assets), aucun
  rendu de markdown distant. `flutter_markdown`, abandonné en amont, est
  remplacé en v2.0.9 par `flutter_markdown_plus`, qui rend aussi l'aperçu
  des notes ; aucune image n'y est chargée, et seuls les liens `http`,
  `https` et `mailto` sont transmis au gestionnaire du système.
- `ProGuard` rules complètes : `files_tech_voice`, `flutter_markdown`,
  sqflite. Les règles MediaPipe / ONNX / flutter_gemma sont devenues
  sans objet avec le retrait de l'IA embarquée (v2.0.0).

## Signaler une vulnérabilité

Si vous pensez avoir trouvé un problème de sécurité dans Notes Tech,
merci de **ne pas ouvrir d'issue GitHub publique**. Écrivez plutôt à :

📧 **contact@files-tech.com**

Objet : `[SECURITY] Notes Tech — <résumé court>`

Indiquez :
- Une description du problème et de son impact potentiel.
- Les étapes pour le reproduire (ou une preuve de concept).
- La version concernée (Réglages → À propos de Notes Tech → Notes Tech vX.Y.Z).
- Un contact pour le suivi.

Vous recevrez un accusé de réception sous **72 heures**. Un calendrier
de divulgation coordonnée sera convenu si le problème est confirmé.

## Périmètre

### Inclus
- Code de l'application Notes Tech (`lib/`, `android/`)
- Module frère `files_tech_voice`, le cas échéant
- Implémentations cryptographiques (intégration SQLCipher, dérivation /
  stockage de la KEK, irréversibilité du mode panique)
- Gestion des permissions (`RECORD_AUDIO`)
- Gestion des fichiers / imports SAF / path traversal
- Vulnérabilités de dépendances remontées par `health_check.sh`

### Exclus
- Problèmes dans des paquets tiers (à signaler en amont).
- Problèmes nécessitant un appareil rooté ou un malware déjà présent sur
  l'appareil.
- Ingénierie sociale visant l'utilisateur.
- Déni de service par des notes / charges volontairement surdimensionnées.

## Modèle de menace

Notes Tech est conçu pour les particuliers et les professionnels qui
veulent des notes **exclusivement locales** avec de fortes garanties
cryptographiques. Trois classes d'adversaires sont prises en compte :

### 1. Perte / vol (appareil déverrouillé perdu ou volé)
- **Confidentialité au repos** : SQLCipher (AES-256) pour la base de
  données, AES-256-GCM pour les notes des coffres par dossier. KEK scellée
  par l'Android Keystore (adossé au matériel sur les appareils récents).
- Le coffre par dossier ajoute un second facteur (passphrase ou PIN) en
  plus de l'écran de verrouillage de l'appareil.

### 2. Contrainte (fouille, « donnez-moi votre téléphone », contrôle aux frontières)
- **Mode panique** : une action confirmée de suppression totale qui
  exécute une séquence ordonnée déterministe (voir plus bas). Conçue pour
  être rapide et irrécupérable sous la contrainte.
- **Auto-wipe PIN** : 5 tentatives de PIN ratées sur un coffre effacent
  atomiquement les clés de ce coffre (avec reprise après crash via un flag
  dans les préférences).
- **`setUserAuthenticationRequired(false)`** sur les clés Keystore PIN :
  le PIN est l'unique facteur — ajouter la biométrie exposerait
  l'utilisateur au déverrouillage forcé par empreinte (une clé dérivée de
  la biométrie survit au redémarrage).

### 3. Malware sandboxé sur le même appareil
- L'absence de permission `INTERNET` signifie qu'une dépendance compromise
  ne peut pas exfiltrer les notes par le chemin réseau standard.
  L'exfiltration de données par les canaux standard est techniquement
  impossible sans réinstaller un APK modifié.
- Pas de `FOREGROUND_SERVICE`, pas de `POST_NOTIFICATIONS`, pas de
  `RECEIVE_BOOT_COMPLETED` — surface d'attaque minimale.
- `FLAG_SECURE` bloque les aperçus des apps récentes et l'enregistrement
  d'écran.
- `allowBackup=false` + `dataExtractionRules` bloquent l'exfiltration via
  Smart Switch / Android Backup.

### Briques cryptographiques
- **Argon2id RFC 9106** pour la dérivation de la passphrase : `m=64 MiB,
  t=3, p=1, sortie de 32 octets` (défaut des coffres). Le mode PIN utilise
  des paramètres plus légers `m=32 MiB, t=2`, car le wrap Keystore lié à
  l'appareil est la défense principale et la limitation des tentatives sur
  l'appareil empêche la force brute.
- **AES-256-GCM** pour le contenu des notes avec **AAD = `note_id`**
  (empêche la substitution de chiffrés entre notes).
- **Wrap de la KEK avec AAD = `folder_id`** (empêche la réutilisation d'un
  wrap d'un dossier à l'autre).
- **Verifier HMAC comparé en temps constant** pour détecter une mauvaise
  passphrase / un mauvais PIN sans tenter de déchiffrer chaque note.
- SQLCipher 4 (AES-256-CBC + HMAC-SHA512), clé stockée via
  `flutter_secure_storage` 10.x : chiffrement de stockage AES-GCM, clé de
  stockage enveloppée en RSA-OAEP par une clé de l'Android Keystore.

### Mode panique — séquence ordonnée en plusieurs étapes
La séquence panique est déterministe et best-effort (une étape qui échoue
n'interrompt pas les suivantes ; l'échec est consigné dans le rapport de
panique et l'écran final signale un effacement incomplet) :

1. **`forceSecureWindow`** — `FLAG_SECURE` forcé à ON
2. **`voiceCancel`** — capture micro arrêtée (le WAV temporaire est supprimé)
3. **`clipboardClear`** — presse-papiers vidé
4. **`foldersLockAll`** — verrouille tous les coffres ouverts, efface la
   folderKek de la RAM
5. **`pinKeysWipe`** — `deleteKeysWithPrefix("vault_pin_")` (Kotlin)
6. **`kekDestroy`** — détruit la clé maître Keystore (base instantanément
   illisible)
7. **`pauseBackgroundWork`** — workers d'arrière-plan mis en pause (backlinks)
8. **`dbWipe`** — écrase le header SQLCipher (plafond 16 Mio) puis supprime
   `.db`, `.db-journal`, `.db-wal`, `.db-shm`
9. **`voiceWipe`** — modèle Whisper, cache de vérification et WAV orphelins
   supprimés
10. **`legacyModelsWipe`** — purge `<appSupport>/models/` (fichiers de modèles
    laissés par les versions ≤ 1.1.6, qui embarquaient une IA)
11. **`prefsClear`** — préférences effacées, sauf `db_encrypted_v1` et
    `secure_window_enabled`
12. **`exportsWipe`** — dossiers `exports/` purgés (répertoires temporaire et
    cache)
13. **`tmpPurge`** — répertoire temporaire purgé

### Limites acceptées
- La récupération forensique à partir d'un dump de la mémoire physique d'un
  appareil déverrouillé et rooté est partiellement possible (le GC du tas
  Dart finit par recycler les chaînes, mais un instantané pris pendant
  l'utilisation peut laisser fuir du texte en clair).
- Un attaquant étatique déterminé disposant d'exploits noyau sur mesure
  est hors périmètre.
- La confidentialité de l'affichage (`FLAG_SECURE`) est activée par défaut,
  mais peut être désactivée dans les Réglages.

## Divulgation responsable

Nous appliquons par défaut une fenêtre de divulgation de 90 jours :
1. **Jour 0** : réception de votre signalement.
2. **Jours 0-7** : tri initial, sévérité attribuée.
3. **Jours 7-60** : correctif développé, testé, audité.
4. **Jours 60-90** : publication de la version corrigée, CVE publique le
   cas échéant.
5. **Jour 90+** : vous êtes libre de publier votre analyse.

Les problèmes critiques (RCE, extraction de clé, exfiltration complète des
données) peuvent être corrigés en moins de 90 jours.

## Audits de sécurité exécutés à chaque release

Chaque release est vérifiée via :
- `flutter analyze` (lints stricts)
- `flutter test`
- `bash j:\applications\health_check.sh notes_tech` :
  - OSV-Scanner (CVE dans les dépendances)
  - gitleaks (secrets dans l'historique git)
  - Durcissement du manifeste (pas de `debuggable`, pas de
    `cleartextTraffic`, pas de permissions excessives)
  - Configuration de signature (R8 activé, pas de repli debug pour la
    release)
  - FileProvider (pas de `<root-path>`, pas d'exposition globale du
    stockage privé de l'app)
  - Motifs crypto (pas de MD5/SHA-1 à usage de sécurité, PBKDF2 ≥ 100k
    itérations)
  - Motifs Kotlin (`canonicalFile`, PendingIntent `FLAG_IMMUTABLE`)
- Audit à 4 agents (architecture / sécurité / performance / cohérence)
  pour les fonctionnalités importantes.

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
