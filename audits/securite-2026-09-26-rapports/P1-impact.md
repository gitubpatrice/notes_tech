# P1 — angle IMPACT — verdict : VRAI POSITIF, ramené à FAIBLE (reçu 2026-09-26)

VERDICT : VRAI POSITIF. Gravité ramenée de MOYEN à FAIBLE.

**1. Le résidu existe** : 2.0.9 → share_plus 10.1.4 (`pubspec.lock`) ; `Share.kt` copie chaque fichier partagé dans `cacheDir/share_plus/` (l. 187, 245-252) **avant** d'ouvrir le sélecteur (l. 158) ; vidé seulement au partage suivant (l. 95, 235-242). Le dernier partage 2.x reste, même annulé ; la 2.0.9 n'efface que son original (`settings_screen.dart:580-582`). Répertoire temporaire Flutter = cache Android (`path_provider_android-2.3.1/.../path_provider_android_real.dart:23-24`).

**2. La 3.0.0 ne le purge pas** : démarrage `NotesTechApplication.kt:61`, `:67` → `cache/exports` (`NoteExporter.kt:381-383`, `:360`) et `cache/captures` (`VoiceCapture.kt:535-544`, `:526`) seulement ; aucune autre suppression (grep) ; seule la panique vide le reste (`PanicService.kt:587-588`). Le `.next` par défaut (`build.gradle.kts:54`) ne ferme pas le chemin : l'APK publié prend la place de la 2.x (`ci.yml:93-99` ; versionCode supérieur imposé, `build.gradle.kts:305-308`) : même paquet, même cache.

**3. L'attaquant** : C root ou extraction forensique, AFU (cache en CE) ; n'écrit rien, lit un fichier produit sur un geste légitime.

**4. Gain réel, plus étroit**
- Notes hors coffre : rien de neuf (C ouvre déjà la base, `KeystoreSealedKekSource.kt:42`, `:241-254`) ; seules les notes supprimées depuis et les anciennes versions sont un gain.
- Notes de coffre : le vrai gain (clés PIN liées au déverrouillage contre « a seized locked phone », `AndroidVaultKeystore.kt:278-283` ; phrases secrètes sous Argon2id ; titre chiffré depuis le format 2, `NoteEnvelope.kt:8-13`, révélé par le nom `<titre> [unlocked].md`) — **seulement si le dernier partage 2.x en contenait** (archive faite coffre ouvert — les coffres fermés sont omis — ou note de coffre exportée seule).
- Un seul fichier (`Share.kt:95`), souvent une seule note.
- `.md` à la racine : effacé 30 s après (`note_editor_screen.dart:702`) ; survit seulement si le processus meurt dans ce délai.
- Voix de dictée : marginal (WAV effacé après transcription, `stt_session.dart:182-183` ; `stt_capture_*` purgés à chaque démarrage 2.x, `main.dart:103` → `voice_service.dart:135` → `stt_model_downloader.dart:163-177`).
- L'utilisateur avait déjà sorti ce contenu ; le résidu n'est la seule copie que si le partage a été annulé ou la copie de destination supprimée.

**5. Promesses** : aucun texte montré ne promet une durée de vie de l'export (2.0.9 `assets/legal/PRIVACY.fr.md:49` ; 3.0.0 `values-fr/strings.xml:285`) ; « Aucune archive n'attend dans le cache d'un jour sur l'autre » (`NoteExporter.kt:329-331`) = invariant de commentaire, violé ; `raw-fr/privacy.md:18` non tenu pour un WAV orphelin 2.x (improbable) ; panique tenue (`PanicService.kt:586-594`). Durée non bornée ; part des utilisateurs non mesurable (pas de télémétrie) : obstacle.

**6. FAIBLE et non MOYEN** : exploitation exigeante (root/forensique AFU) ; impact limité (un fichier, instantané choisi par l'utilisateur ; partie hors coffre déjà acquise ; coffres et supprimées seulement si le dernier partage les contenait) ; la 3.0.0 n'élargit pas l'exposition de la 2.x (le fichier persistait déjà jusqu'au partage suivant), elle ne la ferme pas ; la panique l'efface. Constat vrai : la 3.0.0 prend le bac à sable sans nettoyer `cache/share_plus/`, `*.md` à la racine, `stt_capture_*.wav`.
