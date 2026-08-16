# Reprise — portage Kotlin de Notes Tech

> Écrit le 2026-08-15 au soir, **mis à jour le 2026-08-16**. À lire en premier, avant `docs/00-PLAN.md`.
> Ce fichier ne remplace pas les docs : il dit **où on en est** et **quoi faire ensuite**.

## État en trois lignes

- Dépôt : `j:\applications\notes_files_tech`, branche par défaut, **`24a1340`, 69 commits**,
  arbre **propre**, et **toujours aucun remote** — rien n'est poussé nulle part.
- Gate **vert** : ktlint, detekt, lint, **149 tests JVM**, **118 tests instrumentés** (S9),
  0 échec, 0 ignoré.
- Application publiée `notes_tech` : `0307811` sur `fix/defauts-releves-pendant-le-portage`,
  **aucune publication décidée**. Ses trois répertoires non suivis (`.audit_tmp/`,
  `_audit_results/`, `prompts/`) ne doivent **jamais** entrer dans l'index — pas de `git add -A`.

## ✅ Fait le 2026-08-16 : l'import du modèle

Livré en un commit, `4fcd647`. Les quatre contraintes relevées la veille ont été tenues : le modèle
va bien dans `files/stt/`, les deux étapes de panique attendues sont entrées **avec** lui, il n'y a
aucun chemin de téléchargement, et le fichier définitif n'existe jamais à moitié.

Deux relectures externes ont rendu **sept constats disjoints** — encore une fois, chacune a vu ce
que l'autre manquait. Le plus grave n'était pas dans le code neuf mais dans la capture de la veille :
`withContext` vérifie l'annulation **au moment de rendre sa valeur**, si bien qu'une portée annulée
au mauvais instant laissait un WAV de voix orphelin que plus personne ne connaissait. La garde posée
le 08-15 couvrait la boucle, et rien après. Tout est dans `docs/04-PIEGES.md` §55-§58.

### ✅ Les tests instrumentés sont passés — et le premier jet en a raté un

Lancés sur le S9 le 08-16 : **125 tests, 0 échec, 0 ignoré**. Le premier passage en a signalé un, et
il n'accusait pas le code : mon test prétendait vérifier le **refus sur la taille annoncée** en
passant un `file://`, qui ne porte pas `OpenableColumns.SIZE`. La garde nommée ne pouvait donc pas
se déclencher, et le fichier était refusé un cran plus loin, par l'empreinte.

⚠️ *Un test qui se trompe de garde ne prouve rien de celle qu'il nomme* — même s'il est vert. Il a
été scindé en deux : l'un passe par un vrai `content://` du `FileProvider` (taille annoncée ⇒ refus
avant lecture), l'autre garde le `file://` pour figer le cas de la **source muette**, où l'empreinte
doit trancher seule. Le second correspond exactement au défaut relevé par GPT-5.5.

⚠️ Toujours `ANDROID_SERIAL=22dbb7390a057ece`, et **jamais sur le S24 FE** (`RZCY41EGKYL`) : AGP
désinstalle l'application à la fin.

### Ce qui est en place

| Fichier | Rôle |
|---|---|
| `domain/voice/SpeechToText.kt` | le contrat du moteur, `SttModel`, `SttTranscription` |
| `domain/voice/SttErrors.kt` | hiérarchie **scellée** d'erreurs, classée par ce que l'utilisateur peut faire |
| `domain/voice/WavPcm16.kt` | le format, en fonctions **pures** (10 tests JVM) |
| `data/voice/VoiceCapture.kt` | la capture micro, relue par deux relecteurs (7 défauts corrigés) |
| `PanicStep.VOICE_CAPTURES_WIPE` | purge des WAV, juste après la clé |
| `domain/voice/CopieVerifiee.kt` | copie **et** empreinte en un passage, bornée, annulable |
| `domain/voice/SttModelCatalogue.kt` | les empreintes attendues — **sans champ `url`** |
| `data/voice/SttModelStore.kt` | `files/stt/`, temporaire → empreinte → `fsync` → renommage |
| `PanicStep.VOICE_CANCEL` / `VOICE_MODEL_WIPE` | interdire la dictée ; effacer le modèle |

### 🔧 Les quatre choses qui ont guidé l'import — gardées ici, elles servent encore

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

## La suite, une fois les tests instrumentés passés

Il ne reste de la phase 7 que **le moteur** et **l'interface**, et le premier est bloqué. Voir
ci-dessous.

⚠️ Un point à ne pas perdre, consigné en **D-019** : l'import ne pose **aucun cache de vérification**,
contrairement à l'application publiée. C'est délibéré — rien ne l'appellerait encore. Le jour où il
entrera avec le moteur, il devra partir dans **la même étape** que `VOICE_MODEL_WIPE` : un cache qui
survivrait à la purge affirmerait qu'un fichier absent a été vérifié.

## ✅ Fait aussi le 2026-08-16 : le moteur, et le texte public

**Les deux points qui attendaient une décision sont clos** (`0376482`) :

- whisper.cpp et ggml **1.8.3** vendorisés — 87 fichiers, 3,8 Mo, MIT — avec un pont **JNI écrit
  ici** et non la couche FFI Dart du greffon. 1,3 Mo écartés, dont `dr_wav.h` : le WAV est décodé en
  Kotlin. NDK **épinglé**. Cf. **D-021** et `vendor/whisper/PROVENANCE.md` ;
- `privacy.md` corrigé — et **deux autres affirmations fausses** y ont été trouvées au passage, plus
  graves que celle qui était signalée : le texte promettait un effacement « atomique et reprenable »
  alors que toute la conception repose sur l'inverse, et sa liste de ce que la panique efface
  **omettait tout le clair**. La notice MIT est désormais dans les CGU, FR et EN.

### 🔴 Les trois choses à savoir avant de continuer

1. **Aucune transcription n'est testée.** Il faudrait le modèle de 50 Mo sur le S9. Ce qui *est*
   prouvé sur l'appareil : `libnotes_stt.so` **se charge**. La qualité, la détection de langue et le
   découpage en segments restent vérifiés par l'usage.
2. ⚠️ **R8 a supprimé `WhisperNatif` et `WhisperStt` des dex release** — rien ne les appelle encore.
   La règle de conservation JNI est écrite mais **sans effet observable** ; le contrôle est à refaire
   sur l'APK **release** dès qu'un écran utilise la dictée. Cf. `04-PIEGES.md` §59.
3. La bibliothèque native est **empaquetée quand même**, 2,5 Mo par architecture.

## ✅ L'interface est faite — la phase 7 est close

Écran d'installation (`ui/voice/`), bouton micro dans l'éditeur, superposition d'enregistrement,
entrée dans les réglages. ⚠️ **Toute chaîne nouvelle passe par `outils/arb_vers_strings.py`**, jamais
directement dans le XML.

### 🔴 Ce que seul l'APPAREIL a montré

Quatre défauts, dont aucun n'aurait été vu à la relecture :

1. la permission du **micro était demandée avant** de savoir qu'aucun modèle n'est installé — une
   permission qu'on fait refuser durablement pour une action qui ne peut pas aboutir ;
2. la description des modèles était un champ du **catalogue**, donc du français **en dur** servi à un
   utilisateur anglophone ;
3. la superposition disait « Parlez » **avant** que le micro n'enregistre : le premier mot se perdait ;
4. 🔴 le bouton micro a porté la barre de l'éditeur à six actions et **écrasé le titre à 24 pixels** —
   c'est Patrice qui l'a vu. Le piège était **déjà documenté à quelques lignes de là**. Épingle et
   favori sont descendues dans le menu ; le titre est remonté à 312 px, mesuré.

⚠️ La mesure qui tranche : `adb shell uiautomator dump` puis lire `bounds`. Une largeur est un
nombre ; un coup d'œil voit « un titre un peu court ».

### ⚠️ Deux tours de relecture externe, et ce qu'ils ont appris

Le second tour portait **sur les correctifs du premier** — la règle du dépôt. Il a rendu six
constats : **cinq confirmés, un partiellement faux**, et deux du tour précédent avaient déjà été
écartés. *Un relecteur voit ce qu'on lui donne : son scénario est une hypothèse, pas une mesure.*

La leçon de fond, en `04-PIEGES.md` §65 : un événement qui **agit** et un événement qui **parle**
n'ont pas la même exigence — l'un doit avoir lieu une fois et pas deux, l'autre une fois et pas zéro.
Tant qu'un seul flux portait les deux, chaque correctif d'ordre introduisait le défaut inverse.

### ⚠️ Ce qui n'a jamais été exercé

**La dictée de bout en bout.** Il faut le modèle de 50 Mo sur le S9 : le télécharger sur un
ordinateur (`ggml-base-q5_1.bin`, `huggingface.co/ggerganov/whisper.cpp`), le transférer, puis
l'importer par l'écran. Tant que ce n'est pas fait, on sait que la bibliothèque native **se charge**,
et rien de plus sur la qualité, la détection de langue ou le découpage en segments.

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
