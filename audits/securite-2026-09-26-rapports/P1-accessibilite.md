# P1 — angle ACCESSIBILITÉ — verdict : VRAI POSITIF, MOYEN (reçu 2026-09-26)

VERDICT : VRAI POSITIF. Gravité retenue : MOYEN. Flutter lue au tag v2.0.9 (e3ee1d6), cache pub dans `J:\Pub\Cache`. Chaque fichier:ligne relu.

**1. Le résidu existe sur un téléphone 2.0.x**
- v2.0.0 à v2.0.9 épinglent share_plus 10.1.4 (`pubspec.lock:817-824` au tag v2.0.9).
- Export complet et unitaire passent par `Share.shareXFiles` avec un chemin non vide ; pas de copie côté Dart (`method_channel_share.dart:129-130`).
- Le natif copie chaque fichier partagé : `share_plus-10.1.4\...\Share.kt:29` `File(getContext().cacheDir, "share_plus")`, `:187` `file = copyToShareCacheFolder(file)`, `:250-251` `File(folder, file.name)` puis `copyTo` — **avant `startActivity`, donc même si l'utilisateur annule le partage**.
- Vidé seulement au partage suivant (`Share.kt:95`, `:235-241`) ; rien d'autre (ni au rattachement, `SharePlusPlugin.kt:15-21`).
- La 2.0.9 ne purge au démarrage que `exports/` (`lib/main.dart:234-243`) et les `stt_capture_*.wav` (`voice_service.dart:133-135`, `stt_model_downloader.dart:164-178`) ; seule sa panique vide tout le cache (`panic_service.dart:422-441`).
- Le ZIP complet contient le clair des coffres ouverts (`note_export_service.dart:358-371`).

**2. Il survit à la bascule** : build publiée = prise de place (`.github\workflows\ci.yml:93-99`, `-Pnotestech.replaceInstalledApp=true`) ; installations par-dessus la 2.0.x (`docs\12-PLAN-DE-BASCULE.md:31`) ; le `.next` par défaut (`build.gradle.kts:50-54`) est une isolation de chantier ; Android ne vide que `code_cache` à la mise à jour, pas `cache/` (plateforme, non re-mesuré).

**3. La 3.0.0 ne le purge jamais hors panique** : démarrage = `NoteExporter.purgerLesArchives` (`NotesTechApplication.kt:61` → `cacheDir/exports`, `NoteExporter.kt:360`, `:382`) et `VoiceCapture.purgerLesCaptures` (`:67` → `cacheDir/captures`, `VoiceCapture.kt:526`, `:541`) ; relevé de toutes les suppressions : rien sur `share_plus/`, `.md` à la racine, `stt_capture_*.wav` ; seule `viderLeCache` de la panique (`PanicService.kt:586-594`, appelée `:452`) ; aucune migration ni premier lancement ne touche le cache ; `share_plus` seulement dans un commentaire (`Partage.kt:63`). Promesse fausse pour l'archive héritée : `NoteExporter.kt:329-331`.

**4. Qui lit, quel gain** : résidu écrit par la 2.x sur geste de l'utilisateur ; l'attaquant lit `/data/user/0/com.filestech.notes_tech/cache/`. Ni sauvegarde (`allowBackup="false"`, cache exclu) ni autre app (le fournisseur de share_plus disparaît ; celui de la 3.0.0 n'expose que `exports/`, `file_paths.xml:15`). C = root ou forensique AFU (modèle déclaré, `AndroidVaultKeystore.kt:280-281`). Copie hors ligne : texte intégral du dernier export 2.x, alors que la base reste scellée par le Keystore. Root sur l'appareil vivant : peut déjà ouvrir la base (`KeystoreSealedKekSource.kt:42`, `:243-251`) ; gagne les coffres ouverts au moment de l'export, les notes et coffres supprimés depuis.

**MOYEN** : au moins un export en 2.x sans panique depuis ; seul le dernier partage survit, figé à sa date ; attaquant privilégié. Sous-affirmations étroites : WAV (processus 2.x tué pendant la dictée, et toute relance 2.x purge) ; `.md` racine (mort du processus dans les 30 s, `note_editor_screen.dart:701-709` ; la copie `share_plus/` existe de toute façon) ; ZIP `exports/` purgé par la 3.0.0 (même répertoire, `path_provider_android_real.dart:23-24`, `:49-50`) — seule la copie `share_plus/` reste.

Non confirmé par exécution : présence effective de `cache/share_plus/` sur un appareil basculé.
