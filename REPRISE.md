# Reprise — portage Kotlin de Notes Tech

> Écrit le **2026-08-15 au soir**. À lire en premier le lendemain, avant `docs/00-PLAN.md`.
> Ce fichier ne remplace pas les docs : il dit **où on en est** et **quoi faire ensuite**.

## État en trois lignes

- Dépôt : `j:\applications\notes_files_tech`, branche par défaut, **`24a1340`, 69 commits**,
  arbre **propre**, et **toujours aucun remote** — rien n'est poussé nulle part.
- Gate **vert** : ktlint, detekt, lint, **149 tests JVM**, **118 tests instrumentés** (S9),
  0 échec, 0 ignoré.
- Application publiée `notes_tech` : `0307811` sur `fix/defauts-releves-pendant-le-portage`,
  **aucune publication décidée**. Ses trois répertoires non suivis (`.audit_tmp/`,
  `_audit_results/`, `prompts/`) ne doivent **jamais** entrer dans l'index — pas de `git add -A`.

## La prochaine tâche : l'import du modèle de dictée

C'est le dernier morceau de la phase 7 qui **ne dépend d'aucune décision**. Tout le reste du
contexte nécessaire a été vérifié dans le code publié et est consigné ci-dessous.

### Ce qui est déjà en place

| Fichier | Rôle |
|---|---|
| `domain/voice/SpeechToText.kt` | le contrat du moteur, `SttModel`, `SttTranscription` |
| `domain/voice/SttErrors.kt` | hiérarchie **scellée** d'erreurs, classée par ce que l'utilisateur peut faire |
| `domain/voice/WavPcm16.kt` | le format, en fonctions **pures** (10 tests JVM) |
| `data/voice/VoiceCapture.kt` | la capture micro, relue par deux relecteurs (7 défauts corrigés) |
| `PanicStep.VOICE_CAPTURES_WIPE` | purge des WAV, juste après la clé |

### 🔧 Les quatre choses à savoir avant d'écrire une ligne

1. **Le modèle vit dans `files/stt/`, PAS dans `files/models/`.** Les deux répertoires sont
   distincts, et **un test côté publié vérifie que `stt/` SURVIT** à la purge des modèles hérités :
   l'utilisateur a dû télécharger puis importer ce fichier à la main, et se tromper de dossier le
   lui ferait recommencer sans explication. Cf. `legacy_model_files.dart`, qui porte l'avertissement.

2. **La panique publiée a DEUX étapes vocales**, le portage n'en a qu'une :
   - `voiceCancel`, **très tôt** — juste après `forceSecureWindow`, **avant même le
     presse-papiers** : elle coupe l'exposition immédiate, c'est-à-dire le micro encore ouvert ;
   - `voiceWipe`, **après** `dbWipe` : le `.bin`, son cache de vérification, les WAV orphelins.

   Les deux entrent **avec** l'import, jamais avant : l'énumération `PanicStep` refuse les étapes
   qui ne s'exécutent pas. ⚠️ Et l'ordre de l'énumération **est** l'ordre d'exécution — les deux se
   déplacent ensemble, `sequenceFigee` ne fige que la première.

3. **Aucun téléchargement** — décision **D-017**. `SttModel` n'a pas même de champ `url`. Le publié
   embarque le téléchargeur du plugin et **ne l'appelle jamais** ; ici c'est une **absence d'API**,
   qui ne se contourne pas par inadvertance.

4. **Les règles du fichier en clair s'appliquent**, comme pour l'export et la capture : écrire dans
   un **fichier temporaire** puis renommer, vérifier le SHA-256 **pendant** la copie (un seul
   passage sur plusieurs centaines de Mo), effacer sur échec **et dire** si l'effacement rate.

### Où sont les sources de référence

- Contrat Dart : `J:/Pub/Cache/git/files_tech_voice-dca1e1d…/lib/src/`
  (`stt_model_importer.dart`, `stt_model_downloader.dart`)
- Service publié : `j:/applications/notes_tech/lib/services/voice/voice_service.dart`
- Écrans publiés : `lib/ui/screens/voice_setup_screen.dart`, `lib/ui/widgets/voice_record_*.dart`

## Ce qui attend une décision de Patrice

1. 🔴 **Vendoriser whisper.cpp** — 4,2 Mo, 76 fichiers, licence MIT. Les sources sont **déjà sur le
   disque** et **déjà compilées pour les quatre ABI** :
   `J:/Pub/Cache/hosted/pub.dev/whisper_ggml_plus-1.5.2/android/src/whisper/`. Sans cette décision,
   le moteur ne peut pas commencer. Le NDK est installé (3 versions) ; CMake se téléchargera.
2. ⚠️ **Une formulation légale** — `res/raw*/privacy.md` dit de l'audio : « transcrit puis
   immédiatement effacé. **Jamais persisté** ». Il est nécessairement écrit sur le disque (Whisper
   lit un fichier). « Immédiatement effacé » est exact, « jamais persisté » est **trop absolu**.
   Texte public : **non modifié** sans son accord.

## Après la phase 7

`docs/05-PARITE.md` porte **39 cases vides** — 10 écrans, 16 composants, 8 services, 5 promesses
publiques. « Vérifié » y veut dire **sur appareil**, pas « le code existe ». Plus **3 cases de
migration** qui ne se cochent que le jour de la bascule, sur le S9, avec une vraie 2.0.3 installée.

## ⚠️ Rappels qui ont coûté du temps aujourd'hui

- **`cmd | tail` rend le code de sortie de `tail`**, pas celui de Gradle. Mesurer avec
  `./gradlew … > /tmp/log 2>&1; echo $?`.
- **Commit `-F`, jamais `-m`** : des accents graves dans un `-m` ont été interprétés par bash et
  trois chemins ont disparu du message.
- **Lint plante parfois sur un cache périmé** (`Unexpected failure during lint analysis`) :
  `./gradlew lintDebug --rerun-tasks`.
- **Ne jamais lancer `connectedAndroidTest` sur le S24 FE** (`RZCY41EGKYL`) — c'est le téléphone
  réel de Patrice, et AGP désinstalle l'application à la fin. Appareil de test = **S9**,
  `22dbb7390a057ece`.
- **`strings.xml` est GÉNÉRÉ** par `outils/arb_vers_strings.py`. Toute chaîne du portage s'ajoute
  dans `AJOUTS_EN` / `AJOUTS_FR` du script, **jamais** dans le XML. Après génération : comparer les
  **ensembles de noms** avant/après, puis relancer — la sortie doit être identique octet pour octet.
- **Relire les correctifs de relecture.** Aujourd'hui encore, deux tours ont trouvé des défauts
  **plus graves** que le premier — dont une régression que j'avais introduite moi-même.
