# Cellule 3 — « suppression et purge : ce qui reste après » — rapport du chercheur (reçu 2026-09-26)

Racines : `<ROOT>` = `...\scratchpad\audit-ebee629` ; `<K>` = `<ROOT>\app\src\main\java\com\filestech\notes_tech`.
Lu hors copie figée, en lecture seule : `J:\applications\notes_tech` (Flutter 2.0.9, HEAD e3ee1d6) ; `J:\Pub\Cache\hosted\pub.dev\share_plus-10.1.4` (épinglé par son pubspec.lock) ; `J:\Pub\Cache\git\files_tech_voice-dca1e1dadd97b8f5dff4351eabdff768fe8b05c3` ; options de compilation de `E:\.gradle\caches\8.13\transforms\8cada0c8d0464b560d11ebc9ca35889e\transformed\sqlcipher-android-4.16.0\jni\arm64-v8a\libsqlcipher.so` (grep -a). Rien écrit ni exécuté.

**Bilan** : un MOYEN, trois FAIBLE (renommés P1-P4 par l'orchestrateur ; C1-C4 dans l'original). Le cœur de la panique tient. Ce qui fuit : surtout ce que la 3.0.0 hérite du bac à sable Flutter et ne purge jamais hors panique.

## P1 (C1) — MOYEN — Le clair laissé par la version Flutter n'est jamais purgé par la 3.0.0 hors panique : le dernier export partagé survit à l'effacement de coffre, à la corbeille et à la suppression de dossier — CWE-459
- Ligne fautive : `<K>\data\export\NoteExporter.kt:382` (`purgerLesArchives`) `repertoireDExport(context).deleteRecursively()`, ne vise que `cache/exports` (`:360`). Jumeau : `<K>\data\voice\VoiceCapture.kt:541` (`purgerLesCaptures`), ne vise que `cache/captures` (`:526`). Seuls appels au démarrage : `<K>\NotesTechApplication.kt:61`, `:67`.
1. Production par la version remplacée (même applicationId, même bac à sable, `docs\12-PLAN-DE-BASCULE.md`) :
   - Export complet : `J:\applications\notes_tech\lib\ui\screens\settings_screen.dart:572-574` `Share.shareXFiles(...)` sur `cache/exports/notes-tech-export-<ts>.zip` (`:540-551`), toutes les notes, coffres ouverts compris.
   - Export unitaire : `lib\ui\screens\note_editor_screen.dart:675-677` écrit `<titre>[ [unlocked]].md` à la RACINE du cache ; partage (`:682-686`) ; effacé 30 s plus tard sans attendre (`:700-709`), effacement perdu si le processus meurt.
   - Copie par share_plus 10.1.4 : chaque partage copié dans `cache/share_plus/` (`Share.kt:28-29`, `:187` `file = copyToShareCacheFolder(file)`, `:245-252`) ; vidé seulement au début du partage suivant (`:95`) ; l'app Flutter n'efface que ses originaux ; sa purge de démarrage ne vise que `exports/` (`lib\main.dart:234-243`).
   - Dictée : `files_tech_voice\lib\src\stt_session.dart:244-246` `stt_capture_$ts.wav` à la racine du cache ; Flutter purgeait ces orphelins au démarrage (`voice_service.dart:132-135`, `stt_model_downloader.dart:157-176`).
2. 3.0.0 : aucune référence à `share_plus`, `stt_capture_` ni à la racine du cache hors panique (grep) ; le démarrage ne purge que `exports/` et `captures/`.
3. Gestes destructeurs sans effet sur le cache : `autoWipePinVault` (`FolderVaultService.kt:831-859`, message « le coffre a été effacé », `values-fr\strings.xml:242`) ; `emptyTrash`, `deletePermanently` (`NotesRepository.kt:461`, `:451`, « effacée définitivement », `:209`) ; `deleteWithNotes` (`FoldersDrawerViewModel.kt:154-164`).
4. Seule garde : `CACHE_PURGE` (`PanicService.kt:586-594`), panique seulement.
5. Promesses contredites : `NoteExporter.kt:329-331` (« Aucune archive n'attend dans le cache d'un jour sur l'autre ») ; `res\raw-fr\privacy.md:18` (audio effacé au démarrage, « un arrêt brutal ne laisse rien derrière lui »).
- Attaquant C (copie du bac à sable, forensique AFU ou root), sans clé SQLCipher ni secret : texte intégral des notes au dernier export sous 2.x, notes des coffres alors ouverts comprises ; titres dans le nom (« [unlocked] ») ; notes supprimées depuis ; voix de dictée éventuelle. Condition : au moins un partage sous 2.x (l'app recommande l'export avant la panique, `terms.md:21`), cache non vidé par le système.
- Gravité MOYEN ; confiance HAUTE sur le mécanisme (trois bases de code lues) ; présence réelle selon l'historique de l'appareil.
- Recommandation : dès le premier démarrage de la 3.x puis à chaque démarrage, plus aucun clair hérité dans le cache (`share_plus/`, `.md` et `.zip` à la racine, `stt_capture_*.wav`), avec la définition d'`estUnArtefactSensible`, suppression vérifiée.

## P2 (C2) — FAIBLE — Dans la panique, le clair hors `exports/` et `captures/` part en dernier, derrière les étapes longues ; une interruption le laisse et rien ne le reprend — CWE-696
- Ligne fautive : `<K>\security\panic\PanicService.kt:452` `issues += etape(PanicStep.CACHE_PURGE) { viderLeCache() }`.
1. La panique définit le clair comme tout `.zip`, `.md`, `.wav` du cache (`estUnArtefactSensible`, `:649-669`, `:666-668`) mais ses étapes de clair (rangs 6, 7) ne visent que deux répertoires (`:412`, `:423-427`).
2. Le reste attend l'étape 12, derrière `DB_WIPE` (`:430`, 16 Mio + `sync`), `VOICE_MODEL_WIPE` (`:437-439`), `LEGACY_MODELS_WIPE` (`:443`, « plusieurs secondes sur 530 Mo », `:397-398`), `PREFS_CLEAR` (`:446-449`) — contre l'ordre annoncé (`:256-267`, « le clair part immédiatement après la clé » ; `privacy.md:50`).
3. Interruption : pas d'écran de fin ; la purge de démarrage ne voit que `exports/` et `captures/`. Entre `DB_WIPE` et `CACHE_PURGE` : base neuve générée (`KekRepository.kt:59-69`), app vide, rien n'indique l'échec sans verrou d'app. Entre `KEK_DESTROY` et `DB_WIPE` : `MISSING_KEY` (`StartupViewModel.kt:94`) → `StartupFailureScreen` qui n'offre que « Réessayer » (`MainActivity.kt:240-244`) et affirme « Vos notes sont toujours sur cet appareil et n'ont pas été modifiées » et « n'effacez pas ses données » (`values-fr\strings.xml:545-546`) ; l'écran de verrou (et son effacement) n'existe qu'en état `Ready` (`MainActivity.kt:231-237`) : la panique ne peut plus s'achever depuis l'app, et le seul geste qui l'achèverait est déconseillé.
- A interrompt (cas que l'ordre prétend couvrir, `PanicService.kt:243-245`) ; C lit ensuite le clair hérité de P1.
- Gravité FAIBLE (quelques secondes ; fichiers hérités nécessaires) ; confiance MOYENNE (durées non mesurées).
- Recommandation : tout ce qu'`estUnArtefactSensible` qualifie de clair part au rang du clair ; une panique interrompue après la clé doit pouvoir s'achever au lancement suivant au lieu d'annoncer des notes intactes.

## P3 (C3) — FAIBLE — Extension de K1 : vider la corbeille, supprimer définitivement, supprimer un dossier ou effacer un coffre ne purgent ni l'index FTS5 ni les pages libérées — CWE-226
- Ligne fautive : `<K>\data\local\UnmanagedSchema.kt:110-115` (`notes_ad`) `INSERT INTO notes_fts(notes_fts, rowid, title, content, tags) VALUES ('delete', old.rowid, …)`.
- Corbeille : `TrashScreen.kt:222-232` → `TrashViewModel.kt:111-114` → `NotesRepository.kt:461` → `NoteWriteDao.kt:269-270` `DELETE FROM notes WHERE trashed_at IS NOT NULL` ; marqueur `'delete'` seulement ; termes et positions restent dans `notes_fts_data` jusqu'à une fusion ; aucune commande `optimize`, `merge`, `secure-delete` ; pas de `PRAGMA secure_delete` (`NotesDatabase.kt:132-142`) ; **libsqlcipher 4.16.0 compilé sans `SECURE_DELETE`** ; les lignes supprimées restent aussi dans les pages libérées et les trames WAL.
- Mêmes chemins : `deletePermanently` (`TrashViewModel.kt:100-102` → `NotesRepository.kt:451` → `NoteWriteDao.kt:241-242`) ; purge à 30 jours (`NoteWriteDao.kt:250-251`) ; `deleteWithNotes` (`FoldersDrawerViewModel.kt:161` → `FolderDao.kt:233-234` → cascade `NoteEntity.kt:28-33`) ; `autoWipePinVault` (`FolderVaultService.kt:845-848`) : notes scellées sans rien dans l'index, mais le clair d'avant conversion (K1) reste ; `clearVault` (`FolderDao.kt:189-199`) réécrit sel, scellé, IV sur place, anciennes valeurs dans la page et le WAL.
- Sous-point : l'effacement d'un coffre ne vérifie pas la disparition de `vault_pin_<id>` : `FolderVaultService.kt:835` `echoue { keystore.deleteKey(...) }` (résultat jeté) ; `AndroidVaultKeystore.kt:124-131` ne relit pas (contrairement à `:178-197`) ; journal effacé quand même (`:855`).
- Messages : « effacée définitivement » (`values-fr\strings.xml:209`), « irréversible » (`:212`), « le coffre a été effacé » (`:242`).
- Attaquant : quiconque ouvre la base avec sa clé (K3, root) ; A ne peut pas passer par l'interface (recherche jointe sur la ligne vivante, `NoteSearchDao.kt:79-88`). Gain : texte de notes supprimées « définitivement » ; pour un coffre à code effacé dont la clé Keystore a résisté en silence : sel et scellé dans les pages libres, recherche hors ligne du code, déchiffrement des notes supprimées.
- Gravité FAIBLE ; confiance MOYENNE (rétention non mesurée ; `secure_delete` déduit des options de compilation).
- Recommandation : un geste annoncé définitif ne laisse pas le texte récupérable par qui détient la clé de la base ; l'effacement d'un coffre ne se déclare terminé qu'après avoir constaté la disparition de sa clé Keystore.

## P4 (C4) — FAIBLE — Après une panique « complète », une note copiée peut rester dans l'historique du presse-papiers du clavier ou du constructeur, alors que l'écran affirme « Toutes les données ont été effacées » — CWE-451
- Ligne fautive : `<K>\security\clipboard\SensitiveClipboard.kt:330` (`vider`) `presse.clearPrimaryClip()`.
1. Deux voies de copie : bouton Copier (`NoteEditorViewModel.kt:590-597` → `copier` `:89-108` → `ecrire` `:264-281`), `EXTRA_IS_SENSITIVE` seulement API 33+ (`:269-273`), minSdk 24, S9 sous Android 10 ; sélection de texte native, hors SensitiveClipboard.
2. Historiques (clavier Samsung, Gboard) conservent le texte.
3. Panique : `PanicService.kt:354` → `annulerEtEffacer` (`:127-152`) → `viderEtVerifier` (`:255-259`) → `vider` : seul le clip principal ; étape réussie, `clairPeutSubsister` faux (`PanicService.kt:228-230`) ; `PanicScreens.kt:267-269` affiche « Toutes les données ont été effacées » (`values-fr:368`), après une confirmation promettant « aucune récupération forensique possible » (`:379`).
- A, téléphone déverrouillé après la panique : ouvre le presse-papiers du clavier et lit la note.
- Gravité FAIBLE ; confiance MOYENNE (hors de l'app, dépend du clavier/constructeur).
- Recommandation : l'écran de fin ne revendique pas ce que l'app ne peut pas effacer ; sous Android 13, copier une note de coffre prévient que rien n'empêche son historisation.

## Cherché sans rien trouver
- Destruction de la clé de la base : source Keystore (préférences, `.xml.bak`, alias) ; source flutter_secure_storage (trois fichiers, alias RSA, chaque geste tenté) ; relecture finale de toutes les sources.
- Base : `sealForPanic` empêche la réouverture ; `-journal`, `-wal`, `-shm` traités ; écrasement `RandomAccessFile` + `sync`, sans troncature, existence revérifiée.
- Préférences : liste blanche par `commit` ; verrou d'app dans le même fichier ; `vault_lost_drafts` = identifiants ; la version Flutter n'y écrivait aucun contenu.
- Clés Keystore : `vault_pin_*` énumérées, supprimées, relues ; HMAC et biométrique supprimées, relues.
- Dictée : interdiction avant tout, contrôlée après le verrou ; dernier tampon non écrit en panique ; WAV supprimé dans un `finally` (DictationViewModel) ; whisper lit en mémoire.
- Export : noms neutralisés ; FileProvider limité à `exports/`, non exporté ; ZIP en flux, fichier partiel supprimé ; vie de l'archive jusqu'au lancement suivant = compromis documenté.
- Panique : second déclenchement rejoint le premier ; `NonCancellable` ; retour avalé ; `finishAndRemoveTask` + `exitProcess` ; mesure finale récursive, prudente.
- Corbeille : une instruction ; notes de coffre restent scellées ; recherche exclut corbeille et lignes mortes.
- Suppression de dossier : décompte avec corbeille ; clé à code supprimée après la base ; « garder les notes » refuse un coffre ; retrait de protection rescelle en cas d'échec, utilisateur averti.
- Effacement d'un coffre à code : journal posé avant le Keystore ; reprise au démarrage ; notes en clair d'un dossier coffre survivent, conforme au texte (`values-fr:237`).
- Journaux : pas d'arbre Timber en release. Sauvegarde Android : fermée. État sauvegardé : identifiant seulement.

## Notes hors constat
- Drapeau de reprise périmé `vault_wipe_pending_<id>` : effacerait au démarrage un nouveau coffre recréé dans le même dossier ; double panne ; robustesse.
- KDoc inexact : `SttModelStore.kt:40-41` (« le démarrage purge `files/models/` une fois ») ; le code ne le fait qu'en panique ; pas de données utilisateur.
- Reliquat de migration Flutter v0.5 : `notes_tech.db.plain.bak` et `.enc`, traités ni à l'ouverture ni par la panique ; passage direct 0.4.x → 3.0.0 avec migration interrompue : théorique.
- Apprentissage du clavier (= F4).
