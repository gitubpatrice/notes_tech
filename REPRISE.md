# Reprise — portage Kotlin de Notes Tech

> Écrit le 2026-08-15 au soir, **mis à jour le 2026-08-18**. À lire en premier, avant `docs/00-PLAN.md`.
> Ce fichier ne remplace pas les docs : il dit **où on en est** et **quoi faire ensuite**.

## État en trois lignes

- Dépôt : `j:\applications\notes_files_tech`, branche `master`, arbre **propre**, et **toujours aucun
  remote** — rien n'est poussé nulle part. ⚠️ Le compte de commits n'est plus écrit ici : il devenait
  faux au commit suivant. `git rev-list --count HEAD` le dit sans dériver.
- Gate **vert** au 2026-08-18 : ktlint, detekt, lint (`--rerun-tasks`), **224 tests JVM**,
  **297 tests instrumentés** (S9), 0 échec, **0 ignoré** — comptés par les codes de statut.
- ⚠️⚠️ **Le compte JVM se vérifie AUSSI**, depuis le 2026-08-17 : le dépôt tourne en **JUnit 5**
  (`app/build.gradle.kts:219`), et une classe de test écrite en JUnit 4 est ignorée **sans un mot**,
  sous un `BUILD SUCCESSFUL`. Le décompte fiable est la somme des `tests=` des XML de
  `app/build/test-results/testDebugUnitTest/`, et le contrôle qui tranche est la **présence du XML de
  la classe**. Cf. `04-PIEGES.md` §82.
- 🔴 **Cette ligne était FAUSSE le 08-16**, et pas de peu : elle annonçait « 0 ignoré » alors que
  `TranscriptionSurAppareilTest` — le seul test qui prouve que la dictée transcrit — était **ignoré à
  chaque exécution de la suite**, parce que celle-ci **détruisait le modèle de 57 Mo** importé à la
  main. Cf. `04-PIEGES.md` §72 et la section datée du 08-17 en fin de fichier.
- ⚠️ **La suite instrumentée se lance par `adb shell am instrument`, plus par Gradle** :
  `connectedAndroidTest` désinstalle l'application à la fin. ⚠️⚠️ Cette précaution ne suffisait
  **pas** — la destruction venait d'un test, pas de l'outil. Et avec `am instrument` il n'y a pas de
  XML : le décompte des ignorés se lit par `grep -c 'INSTRUMENTATION_STATUS_CODE: -4'` (échec
  d'hypothèse) et `-3` (ignoré), **jamais** dans le « OK (N tests) ».
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

1. ✅ **CLOS le 2026-08-16 — la transcription est exercée, et elle ne l'avait jamais été.** Le modèle
   est sur le S9, `TranscriptionSurAppareilTest` fait transcrire un enregistrement au contenu connu,
   et Patrice a validé la dictée à la voix. ⚠️ C'est en posant cette question pour la première fois
   qu'on a trouvé qu'elle ne transcrivait **rien** — cf. §69 et la section datée en fin de fichier.
2. ✅ **CLOS le 2026-08-16 — la règle de conservation JNI a désormais un effet mesuré.** Elle était
   écrite mais sans effet observable, faute d'appelant. Contrôlé sur l'APK **release** une fois
   l'interface en place, et **des deux côtés de la frontière** :
   - dex (`dexdump` sur `classes.dex`) : classe `WhisperNatif` **au nom conservé**, ses **neuf**
     méthodes natives présentes avec leurs signatures exactes ;
   - `.so` (`llvm-nm --dynamic --defined-only`) : les **neuf** symboles
     `Java_com_filestech_notes_1tech_data_voice_WhisperNatif_*` exportés en `T`.

   ⚠️ `WhisperStt` est renommée en `v5.n` — c'est normal et voulu : Hilt l'atteint, aucun `-keep`
   ne la vise. ⚠️ Un `grep` du nom de méthode dans le dex **ne prouve rien** : `ouvrir` peut
   appartenir à une autre classe. Il faut `dexdump`, et il faut lire les **signatures**.
3. La bibliothèque native est **empaquetée quand même**, 2,26 Mo en arm64 (vérifié dans l'APK).

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

### ✅ 2026-08-16 : la colonne « Kotlin » est remplie, et elle a servi tout de suite

Les 34 lignes nomment maintenant leur homologue, vérifié fichier par fichier. La colonne « Vérifié »
n'a **pas** bougé : nommer n'est pas vérifier. Deux lignes n'ont délibérément aucun homologue, et
elles le disent — `sheet_handle.dart` parce que Material3 fournit la poignée, et
`blocking_progress_dialog.dart` parce que le portage traite ses deux appelants séparément.

🔴 **C'est cette seconde ligne qui a payé le remplissage.** Le composant publié était
*volontairement bloquant* ; en cherchant qui joue ce rôle côté Kotlin, on a trouvé que la feuille de
conversion ne bloquait **pas** le balayage. Mesuré, corrigé, testé — `04-PIEGES.md` §67-§68.

⚠️ La leçon vaut pour les 39 cases restantes : la question *« quel fichier joue ce rôle ? »* trouve
des défauts que la question *« est-ce que ça marche ? »* laisse passer, parce qu'elle oblige à
relire l'intention du publié et pas seulement le comportement du portage.

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

## 🔴 2026-08-16 — la dictée fonctionne, et ce qui reste ouvert sur sa qualité

**Elle n'avait jamais transcrit un mot.** `detect_language` ne demande pas une détection mais
**« ne fais QUE ça »** : whisper rendait 0, sans un seul segment, et l'écran traduisait ça en
« rien n'a été entendu » — un message qui accusait le micro pour un défaut du moteur.
`04-PIEGES.md` §69. Corrigé, vérifié sur appareil par Patrice.

### Ce que « beaucoup de fautes » a donné à la mesure

Patrice a ensuite signalé des fautes à l'usage. Mesuré sur le S9, **même échantillon** — une phrase
française de 10,6 s, synthétisée, donc au texte connu :

| Réglage | Langue détectée | Durée | Texte |
|---|---|---|---|
| auto (actuel) | `fr` ✅ | **8 938 ms** | identique |
| `fr` forcé | `fr` | **4 739 ms** | identique |

⚠️ **La langue n'est donc PAS la cause** : la détection ne se trompe pas, et le modèle Base
transcrit ce son quasi parfaitement, accents et ponctuation compris. Elle coûte en revanche **le
double de temps** — whisper fait une passe d'encodage entière rien que pour identifier la langue.

Les fautes viennent de la **vraie parole** face à un modèle de 57 Mo : débit, accent, liaisons, bruit
— là où une voix de synthèse est artificiellement facile. ⚠️ Ne pas conclure d'un bon résultat sur
un échantillon propre que le moteur est bon ; ce test mesure le **câblage**, pas la robustesse.

### Les trois leviers, non retenus le 2026-08-16 — **décision de Patrice, « rien pour l'instant »**

1. **Un modèle plus grand** — `ggml-small-q5_1.bin`, **190 085 487 octets** (vérifié en ligne), soit
   3,2× le Base. Meilleur gain attendu en français, ~3× plus lent. Demanderait son SHA-256 dans
   `SttModelCatalogue` — le catalogue est une liste d'**empreintes**, rien n'entre sans la sienne.
2. **Forcer la langue** de l'interface : deux fois plus rapide, texte identique. ⚠️ Réserve : mal
   servir qui dicte dans une autre langue que celle de son application.
3. **`no_context = false`** — relevé par Gemini. Ne joue qu'au-delà de 30 s de dictée : le moteur
   garde alors le contexte d'une fenêtre à la suivante, ce qui améliore la continuité **et** favorise
   les boucles de répétition. Compromis, pas correctif.

🔧 **Refaire la mesure** — le test de diagnostic a été retiré (un test qui n'affirme rien est un test
vacant), mais la procédure tient en deux commandes. Synthèse d'un échantillon au texte connu :

```powershell
Add-Type -AssemblyName System.Speech
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$s.SelectVoice('Microsoft Hortense Desktop')
$f = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, 'Sixteen', 'Mono')
$s.SetOutputToWaveFile('fr_test.wav', $f); $s.Speak('...'); $s.Dispose()
```

⚠️ Puis le **réécrire en en-tête canonique de 44 octets** : `WavPcm16` refuse — volontairement — les
WAV qu'il n'a pas écrits, blocs `LIST` compris. Cf. `04-PIEGES.md` §70.

## 🔴 2026-08-17 — la phase 8 s'ouvre, et sa PREMIÈRE ligne coûte trois défauts

La méthode annoncée la veille — *« quel fichier joue ce rôle ? » trouve ce que « est-ce que ça
marche ? » laisse passer* — a été appliquée aux **promesses publiques** et à la **première ligne
d'écran**. Les deux ont payé.

### ✅ Trois promesses publiques sur cinq sont désormais mesurées

| Promesse | Ce qui la prouve |
|---|---|
| Zéro permission Internet | **APK release** : `RECORD_AUDIO` + la permission interne du receiver, rien d'autre |
| 100 % local | **0** bibliothèque réseau sur 940 lignes de classpath ; **0** symbole de socket dans les 4 `.so` |
| Base chiffrée au repos | WAL réel du S9 : **7,9991 bits/octet**, 256 valeurs distinctes, 0 mot-clé de schéma |

⚠️ **Le manifeste fusionné de `build/intermediates/` ne fait pas foi** — le sien datait d'avant le
moteur et l'interface. C'est l'artefact **publié** qui répond.

⚠️⚠️ **Un instrument s'est trompé, et son témoin l'a dit.** La recherche de symboles réseau rendait
« aucun » avec un motif ancré par `$`, alors que les symboles portent `@LIBC`. Le témoin positif
(`malloc`) rendait **0** lui aussi — c'est ce qui a révélé la faute. Après correction, un seul
résultat : `sendfile@LIBC`, tiré par `<filesystem>` de libc++, sans aucun `socket()` nulle part.

### 🔴🔴 §72 — la suite instrumentée DÉTRUISAIT le modèle de 57 Mo, et ignorait le test qui compte

Le plus grave de la journée, et il ne concerne pas l'interface.

`SttModelStoreTest` et `WhisperSttTest` purgeaient le **vrai** `filesDir` en `@Before` et `@After`.
Donc : le fichier importé à la main par l'utilisateur détruit à chaque exécution de la suite, et
`TranscriptionSurAppareilTest` — qui passe après, par ordre alphabétique — **ignoré en silence** par
son `assumeTrue`, sous un « OK (144 tests) » parfaitement rassurant.

**La ligne « 137 tests, 0 échec, 0 ignoré » de ce fichier était donc fausse sur son dernier tiers**, et
le test qui prouve que la dictée transcrit n'avait jamais été vert autrement que lancé **seul**.

| | Avant | Après |
|---|---|---|
| `files/stt/` après la suite | **effacé** | intact, empreinte revérifiée |
| ignorés (codes `-3` / `-4`) | **1** | **0** |
| total | 144 | **152** |

Correctif : les deux classes travaillent sur un `ContextWrapper` dont `getFilesDir()` seul est
détourné, chacune avec un **témoin** qui compare les deux chemins. Le modèle a été restauré depuis
`J:/tmp/claude/modeles/ggml-base-q5_1.bin`, droits `700`, empreinte identique au catalogue.

⚠️ La précaution du 08-16 visait `connectedAndroidTest`. Elle était juste et **ne protégeait de
rien** ici : *se protéger d'une cause connue ne dit rien des autres.*

### 🔴 §71 et §73 — deux boutons de l'accueil mal annoncés

- **Le bouton flottant n'avait aucun nom accessible.** `ExtendedFloatingActionButton` de material3
  1.4.0 enveloppe son slot `text` dans un `clearAndSetSemantics` : le libellé est **dessiné** et
  **absent** de l'arbre fusionné. Trouvé par un relevé `uiautomator` (`NAF="true"`), confirmé sur
  l'arbre de sémantique. L'application publiée porte `label` **et** `tooltip` : c'était une régression.
- **Le bouton ⋮ s'annonçait « Réglages »**, soit le nom d'**une** de ses deux entrées de menu. Le
  publié y met le `moreButtonTooltip` de la plateforme. Trouvé par un test qui cherchait autre chose.

⚠️ **Les deux relectures externes ont convergé** pour refuser mon premier correctif du bouton flottant
(nommer l'icône) au profit du nom posé sur **le bouton**, avec deux arguments distincts : annonce en
double si material3 cesse d'effacer le slot, et arrêt de focus parasite si son `mergeDescendants`
change. Elles ont aussi trouvé, toutes les deux, que mon test de badge concluait par un **compte** qui
vaut 1 aussi bien quand tout va bien que quand deux défauts s'annulent.

### ⚠️ Un garde-fou manquait dans le générateur de chaînes

La chaîne française ajoutée pour le ⋮ est entrée dans le XML avec une **apostrophe nue** — les 425
autres du fichier sont échappées, et l'en-tête du fichier généré énonce la règle. Les blocs `AJOUTS`
sont recopiés **verbatim**, donc rien ne les contrôlait ; et dans une chaîne Python non brute, `\'`
produit `'`, il faut `\'`. Le générateur **refuse** désormais, vérifié sur un cas positif.

⚠️ *Une règle écrite dans l'en-tête d'un fichier généré ne protège personne : c'est le générateur qui
doit refuser.*

### 🔧 Les trois questions à reprendre pour les 35 cases restantes

Ce ne sont pas « est-ce que l'écran marche ? » — celle-là n'a rien trouvé :

1. **Que reçoit un lecteur d'écran ?** L'arbre **fusionné** fait foi, pas l'arbre non fusionné où les
   défauts d'étiquetage sont invisibles. Le balayage mécanique est
   `AccueilTest.aucun_element_actionnable_de_l_accueil_n_est_sans_nom`, à recopier par écran.
2. **Quels états ne sait-on pas atteindre à la main ?** Bannière de brouillons perdus, échec de
   chargement, état vide de recherche. Les composables sans état les rendent accessibles en une ligne.
3. **Combien de tests ont été ignorés ?** Jamais depuis le « OK (N tests) ».

## 🔴 2026-08-17, seconde ligne de parité : la CORBEILLE, et trois défauts de plus

Quatre cases cochées — `trash_screen.dart`, `note_card.dart`, `empty_state.dart`, et le composant de
carte dans ses trois états. **31 restantes.** Suite instrumentée : **165 tests, 0 échec, 0 ignoré**,
modèle de 57 Mo intact et empreinte revérifiée après la suite (leçon §72).

### Les trois défauts, et lequel compte

1. **§75 — la corbeille annonçait « vide » avant d'avoir lu la base.** `stateIn` rend
   obligatoirement une valeur initiale ; l'écran n'avait qu'une branche `if (notes.isEmpty())`. Le
   bouton « vider » surgissait au même instant, puisqu'il dépend de `notes.isNotEmpty()`.
2. **§74a — la carte de corbeille était cliquable pour rien** (`onClick = { }`). Le publié rend sa
   tuile **sans `onTap`**.
3. 🔴🔴 **§74b — la carte de note n'annonçait pas du tout qu'on peut l'ouvrir.** La sémantique était
   sur le `Surface`, le `clickable` sur la `Column` fille : **les actions d'un descendant ne
   remontent pas au nœud fusionné**, contrairement au texte. Sur l'accueil **et** dans la recherche,
   la carte s'annonçait comme du texte.

### ⚠️⚠️ Ce que ce troisième défaut apprend, et c'est la seule chose à retenir

**Il a été trouvé par le TÉMOIN du deuxième, pas par le deuxième.** Le test « cette carte n'est pas
actionnable » se réduit à `assertHasNoClickAction()` — vacant par construction. Son témoin pose la
**même** carte avec un clic réel et exige l'inverse. Le témoin a échoué.

⚠️ **`performClick()` ne l'aurait jamais vu** : il injecte un toucher aux coordonnées du nœud et
n'exige aucune action de sémantique. C'est pourquoi `toucher_une_carte_ouvre_la_note_correspondante`
était vert depuis le premier jour sur un nœud sans `OnClick`. Et le geste marchait aussi pour un
lecteur d'écran, dont le double-appui envoie un toucher au centre du nœud focalisé — *ce que le code
fait n'est pas ce que l'utilisateur entend*.

⚠️ **Le balayage de §71 ne pouvait pas le voir** : il cherche une action **sans nom**, celui-ci était
un nom **sans action**. Le motif inverse demande son propre contrôle, et il vaut pour les huit écrans
suivants : *un nœud qui porte un nom et se comporte comme activable annonce-t-il son action ?*

### 🔧 L'outillage est désormais partagé, à réutiliser tel quel

- `app/src/androidTest/…/ui/BalayageDAccessibilite.kt` — le filtre « actionnable sans nom », extrait
  d'`AccueilTest`. Son **témoin** vit dans `BalayageDAccessibiliteTest`, à part : il valide l'outil,
  pas un écran.
- `TrashScreen` est scindé en `TrashRoute` (Hilt) + `TrashScreen` **sans état**, comme
  `HomeRoute`/`HomeScreen`. **C'est ce découpage qui rend l'état de chargement atteignable** — sur un
  téléphone, la base répond en quelques millisecondes et le défaut ressemble à un scintillement.
- ⚠️ Les écrans restants (`SearchRoute`, `SettingsRoute`, `AboutRoute`, `LegalRoute`) portent encore
  leur `hiltViewModel()` en propre : chacun demandera le même découpage avant d'être mesurable.

⚠️ **Le motif `stateIn` est à vérifier sur chaque écran qui suit** : la valeur initiale n'est pas une
donnée, c'est une **absence** de donnée, et l'écran doit savoir les distinguer. `HomeUiState` le fait
(`loading = true` par défaut), `TrashUiState` ne le faisait pas.

### ⚠️⚠️ Les deux relectures externes ont trouvé un CINQUIÈME défaut — dans MES tests

Sept constats (Gemini Pro, GPT-5.2), **aucun recoupement sur les trois qui comptaient**. Le plus
grave : mon test « le bouton de vidange reste caché pendant le chargement » était **vacant** — posé
sur une liste vide, alors que ce bouton dépend *aussi* de `notes.isNotEmpty()`. Il passait avec la
garde **et sans**.

**Deux assertions négatives vacantes dans la même journée** : l'une trouvée par mon propre témoin,
l'autre par une relecture. 🔧 *Devant toute assertion négative : quel état la rendrait fausse si le
code était cassé ?* S'il n'est pas dans le test, le test ne mesure rien.

Sont aussi entrés, tous mesurés :

- les **étiquettes** de la carte n'étaient annoncées à **aucun** lecteur d'écran — un nœud fusionné
  qui porte une `contentDescription` explicite **remplace** la lecture de ses enfants, donc tout ce
  qui n'est pas dans la chaîne construite n'existe pas. ⚠️ Le correctif a dû reproduire la garde
  `!verrouillee`, sinon il **ouvrait** la fuite que la carte ferme : « Note verrouillée, #médical,
  #divorce » n'a rien protégé. Deux assertions le figent ;
- un `Role.Button`, sans quoi TalkBack ne nomme pas ce que c'est ;
- le dialogue de suppression définitive **disparaissait à la rotation** — `rememberSaveable`, et
  l'**identifiant** au lieu de la `Note`, qui n'a pas à devenir `Parcelable` pour ça ;
- ⚠️ mon propre durcissement était faux : j'avais remplacé l'indice `[1]` par
  `hasAnyAncestor(isDialog())` **avant** la relecture, et Gemini a vu ce que je n'avais pas vu — le
  dialogue de vidange porte son libellé **deux fois**, en titre *et* en bouton. Mon sélecteur
  désignait donc deux nœuds. `hasClickAction()` ajouté.

**Deux constats laissés en l'état, par écrit** : `tryEmit` perd un message si la rotation tombe
pendant l'action (le KDoc du ViewModel choisit déjà ce compromis), et `TrashViewModel.state` n'a pas
de `catch` là où `SearchUiState` a gagné un `failed` **parce qu'un flux non gardé avait emporté
l'application** — asymétrie entre jumeaux, assumée : l'application publiée n'a pas de filet ici non
plus, et la corbeille ne lit aucune saisie utilisateur. Détail en `04-PIEGES.md` §74.

## 🔧 La suite immédiate : `search_screen.dart`

Le défaut §75 y est **déjà localisé et confirmé par lecture des deux côtés**, non corrigé : le `when`
de la recherche passe de `query.isBlank()` à `failed` puis à `results.isEmpty()`, sans branche pour
« la requête est posée, la réponse n'est pas là ». Avec l'anti-rebond de 200 ms, « Aucun résultat.
Essayez un autre mot-clé » paraît à chaque salve de frappe — et **accuse la saisie** pour une réponse
qui n'est pas encore arrivée. Le publié rend un indicateur (`search_screen.dart:109`) : c'est une
**régression du portage**.

Le corriger demande le même découpage sans état que la corbeille — d'où le fait de le laisser à sa
propre ligne plutôt que de le traiter à part sans test.

## ✅ 2026-08-17, ligne 3 : la RECHERCHE — le premier défaut trouvé par balayage de motif

Une case de plus, **30 restantes**. 183 tests JVM + 174 instrumentés, 0 échec, 0 ignoré, modèle
intact.

**Le défaut était déjà localisé avant d'ouvrir la ligne**, par le balayage du motif §75 sur les quatre
`stateIn` du portage : la recherche affichait « Aucun résultat. Essayez un autre mot-clé » **pendant**
la recherche. Le `combine` mêle deux flux de rythmes différents — la saisie émet à chaque frappe, les
résultats passent par un freinage de 250 ms puis par une requête — donc l'état portait la **nouvelle**
requête et l'**ancienne** issue. Le message accusait la saisie de l'utilisateur. Le publié rend un
indicateur (`search_screen.dart:109`) : régression du portage.

⚠️ *Premier défaut du portage trouvé par un motif plutôt que par l'examen d'un écran.* La leçon
réutilisable : **un défaut nommé se cherche ensuite partout où son motif existe.**

### 🔧 Le mécanisme, réutilisable tel quel

Faire porter à la réponse **la question à laquelle elle répond** :
`Issue(pour: String?, resultats, echec)`, puis `repondALaSaisie = issue.pour == texte`.

- ⚠️ `pour` est **nullable**, pas vide par défaut : `null` veut dire « aucune réponse pour aucune
  requête », alors qu'une chaîne vide serait **égale** à une saisie vide, donc lue comme une réponse.
- ⚠️ `failed` n'est retenu que si l'issue répond à la saisie courante — l'échec d'une requête
  abandonnée n'accuse pas la suivante. `failed` et `searching` sont donc **exclusifs par construction**,
  et l'écran s'appuie sur cette exclusivité pour ordonner ses branches.
- ⚠️⚠️ La condition d'affichage est `searching && results.isEmpty()`, **et la seconde moitié compte** :
  sans elle, chaque frappe remplacerait la liste par un indicateur pendant 250 ms. Le correctif évident
  est plus simple à écrire et introduit un clignotement à chaque lettre. Un test le fige.

### 🔴 Deux niveaux de test, parce qu'un seul aurait été vacant

`RechercheTest` pose `searching` **à la main** : il prouve ce que l'écran fait d'un état, jamais que
quelque chose produit cet état. D'où l'extraction de la transformation en **fonction pure**
`etatDeRecherche(texte, issue, noms)`, testée sur la JVM (`RechercheEtatTest`, 7 cas dont un balayage
d'exclusivité sur 24 combinaisons). `SearchRepository` et `FoldersRepository` sont des classes
concrètes bâties sur un `DatabaseProvider` — le ViewModel entier n'est pas exerçable hors appareil,
cette fonction l'est.

⚠️ **La valeur initiale de `stateIn` passe par la même fonction**, avec `Issue()` : c'est par la valeur
initiale que §75 était entré, et deux chemins vers le même état demanderaient deux vérifications.

### ✅ Le balayage `stateIn` est CLOS — et trois candidats sur quatre n'étaient pas des défauts

| État | Verdict mesuré |
|---|---|
| `SettingsUiState` | sa valeur initiale est **lue** (`settings.themeNow()`), pas supposée |
| `TrashUiState` | 🔴 défaut réel, corrigé (§75) |
| `SearchUiState` | 🔴 défaut réel, corrigé (§76) |
| `FoldersUiState` | rien à faire : les **deux seuls** usages de `state.inbox` du dépôt (`FoldersDrawer.kt:119` et `:124`) traitent son absence **exprès**, avec un repli documenté — et `HomeRoute` collecte cet état dès sa première composition, tiroir fermé, donc la fenêtre n'est pas atteignable par le tiroir |

⚠️ *Un balayage de motif rend des candidats, pas des défauts.* Corriger les quatre au motif que le
motif existe aurait fait modifier du code correct — dont un repli délibéré, commenté comme tel.

## 🔴 2026-08-17, ligne 4 : les RÉGLAGES — un interrupteur muet, et quatre défauts dans mes tests

Une case de plus, **29 restantes**. 183 JVM + **187 instrumentés**, 0 échec, 0 ignoré, modèle intact.

**Le balayage d'accessibilité a rendu un rectangle de 156 × 96 px** — soit exactement un `Switch` de
52 × 32 dp à 3×, identifié par l'arithmétique. Un `Switch` posé en `trailingContent` d'un `ListItem`
est un nœud **séparé** de celui qui porte le texte : il détient l'action et l'état, la ligne détient le
libellé, rien ne les relie. Annoncé « interrupteur, activé », sans dire de quoi. Le publié emploie un
`SwitchListTile`, qui rend un seul nœud ⇒ **régression du portage**. §77.

⚠️⚠️ **Le correctif était déjà écrit dans le même fichier**, appliqué à ses boutons radio, avec le
commentaire qui l'explique. *Un idiome correct appliqué à un composant et pas à son voisin est plus
difficile à voir qu'une absence d'idiome* — le fichier avait l'air cohérent.

### ⚠️ Quatre de mes six premiers échecs ne visaient pas le code — à retenir

1. 🔧 **`clickable(enabled = false)` CONSERVE son action `OnClick`** et pose `Disabled` à côté. Se
   mesure par **`assertIsNotEnabled`**, jamais par `assertDoesNotExist`. ⇒ Le balayage **voit** les
   actionnables désactivés, et c'est voulu : un bouton grisé sans nom reste un bouton sans nom.
2. **`LocalSecureWindow` n'a aucun défaut, exprès**, et il a levé son message sur mes deux tests du
   dialogue de panique. Le garde-fou a fait son travail — un contrôleur muet aurait laissé le test vert
   sur un écran non protégé. Le test fournit un contrôleur **réel** (sa chaîne ne demande qu'un
   `Context`, et `SecureWindowGuard` ne touche qu'un compteur en mémoire).
   ⚠️ Non mesuré, et dit plutôt que contourné : que le dialogue pose bien `FLAG_SECURE`. `activeNow()`
   mêle le compteur au réglage utilisateur ⇒ le vérifier demanderait d'écrire dans les préférences
   réelles, ce que §72 interdit à un test.
3. **`home_sort_mode` sert DEUX fois sur cet écran** (titre de section + ligne) ⇒ `onNodeWithText` seul
   désignait deux nœuds. *Sur un écran de réglages, un libellé réutilisé est la règle.*

### 🔴 Ce que le mode panique doit au découpage sans état

`SettingsScreen` ne reçoit qu'un booléen et un rappel, donc la confirmation, son annulation, **le refus
de confirmer sans le mot-clé** et la désactivation de la ligne pendant l'effacement se mesurent **sans
rien détruire**. À travers le vrai `PanicViewModel`, ce test effacerait la base du S9 **et le modèle
vocal de 57 Mo** — le sinistre de §72, mais volontaire.

⚠️ Le mot est saisi **en minuscules** exprès : la comparaison ignore la casse, et c'est un choix écrit
(« quelqu'un sous stress tape sans majuscule »). Le vérifier en majuscules laisserait ce choix non
mesuré.

⚠️ Restent dans la `Route`, et ne descendront pas : l'annonce du changement de langue avec la recréation
de l'activité (`LocalActivity`, `LocalView`) et le recouvrement de panique, qui appelle `exitProcess`.

## ✅ 2026-08-17, balayage de cohérence sur tout `ui/` + le second instrument

**192 tests instrumentés** (187 avant), 183 JVM, 0 échec, 0 ignoré, modèle intact. 29 cases de parité.

### Un constat, sur le motif des cinq défauts de la journée

Le bouton micro de l'éditeur portait `voice_setup_title` — le titre d'un **autre écran**, que ce bouton
n'ouvre pas — alors que `note_editor_tooltip_dictate` existait, traduite des deux côtés, et n'était lue
**nulle part**. Le publié l'emploie précisément là (`voice_record_button.dart:59`). Corrigé.

⚠️ **Aucun défaut audible** : les deux valeurs coïncident dans les deux langues. Défaut **latent**, §79.

🔧 Le discriminant, déjà éprouvé en phase 6 : *une chaîne traduite des deux côtés et lue nulle part est
un signal* — puis *son jumeau est-il utilisé dans le publié, et pour quoi ?*

Les sept autres répertoires (`folders`, `vault`, `voice`, `panic`, `about`, `splash`, `common`) sont
revenus **sains** sur ce motif, vérifiés un par un.

### 🔴🔴 Le second balayage existe enfin — et il a demandé TROIS versions

`actionsPerduesALaFusion()` dans `ui/BalayageDAccessibilite.kt` : le motif **inverse** de
`actionnablesSansNom`, c'est-à-dire un **nom sans action**. `05-PARITE.md` le promettait depuis §74 et
il n'existait pas.

**Les deux premières versions rendaient 0 sur tout**, y compris sur la faute :

1. « remonter au premier ancêtre fusionnant, **soi-même inclus** » — **`Modifier.clickable` fusionne
   lui-même ses descendants**, donc le premier nœud fusionnant est toujours le nœud cliquable ;
2. « un actionnable de l'arbre non fusionné **absent** de l'arbre fusionné » — le nœud cliquable
   **existe** dans les deux. §74 n'est pas une absorption, c'est **deux nœuds distincts**.

⚠️⚠️ **Seul le témoin l'a dit** — troisième fois de la journée après le `grep` ancré par `$` et
l'assertion négative sur la carte de corbeille : *un filtre qui ne signale rien est indiscernable d'un
code sans défaut.*

### ✅ Contrôle positif sur le VRAI code, pas seulement sur un vecteur

Le défaut §74 a été **remis en place dans `NoteCard.kt`** le temps d'une mesure sur le S9. Les deux
tests ont échoué comme attendu, le second en rendant `Rect(36, 636, 1044, 930)` — la carte de note.
Fichier restauré par **`git checkout --`** : le `cp` de sauvegarde s'était révélé douteux, et git est la
seule source qui ne mente pas sur ce qu'elle contient.

⚠️ Les quatre écrans mesurés passent ce second balayage : il ne trouve **rien de neuf aujourd'hui**. Sa
valeur est le filet de régression, et les cinq écrans restants.

## 🔴 2026-08-17, ligne 5 : l'ÉDITEUR — deux champs anonymes, et un test qui n'a jamais tourné

Deux cases de plus — `note_editor_screen.dart` et `backlinks_panel.dart` —, **27 restantes**.
**194 tests JVM** (183 avant) et **215 instrumentés** (192 avant), 0 échec, **0 ignoré**, modèle de
57 Mo intact et empreinte revérifiée après la suite.

`NoteEditorScreen` est le cinquième écran scindé en `NoteEditorRoute` + composable **sans état**. Ici
le découpage ne rend pas atteignables une ou deux fenêtres rares mais **six états** : les quatre
issues de chargement, l'échec d'enregistrement, et sa raison nommée. C'est aussi le seul écran du
portage où « essayer pour voir » n'est pas neutre — l'essai écrit.

### 🔴🔴 §80 — les deux zones de saisie n'avaient AUCUN nom accessible

Elles n'avaient qu'un `placeholder`. Mesuré sur le S9, arbre fusionné, trois champs :

| Champ | `EditableText` | nom annoncé |
|---|---|---|
| `label` + contenu | `valeur-A` | **`[libelle-A]`** |
| `placeholder` + contenu | `valeur-B` | **`null`** |
| `placeholder` + **vide** | `` | `[indice-C]` |

Un placeholder ne nomme le champ **que tant qu'il est vide** — c'est-à-dire exactement l'état sous
lequel un éditeur se relit. Sur une note ouverte, un lecteur d'écran annonçait deux zones **anonymes**.
Le publié porte `labelText` sur les deux : régression de parité.

⚠️⚠️ **Aucun des deux balayages ne pouvait le voir.** `actionnablesSansNom` **exclut** les nœuds
portant un `EditableText` — au motif, juste, qu'un champ vide n'est pas un défaut d'étiquetage — et
`actionsPerduesALaFusion` ne regarde que les actionnables. *Une exclusion raisonnable dans un
instrument est un angle mort dans tous les écrans qu'il a validés.*

D'où `champsDeSaisieSansNom()`, troisième instrument, son témoin à trois cibles — la troisième étant
le champ **vide**, qui ne doit **pas** être signalé — et un **contrôle positif sur le vrai code** : les
deux `label` retirés le temps d'une mesure rendent bien deux rectangles, ceux des deux champs.

⚠️ Restauration **par l'inverse exact de l'édition, vérifiée au SHA-256**, et non par `git checkout --`
comme au §78 : le fichier portait tout le travail non commité de la session. *Une technique de
restauration se choisit d'après l'état du fichier, pas d'après l'habitude.*

🔧 Et c'est une **chaîne orpheline** qui disait où poser le libellé : `note_editor_content`, traduite
des deux côtés, lue nulle part, employée par le publié comme `labelText` de ce champ exactement. Même
discriminant qu'au §79, même écran, même jour.

### 🔴 §81 — le titre n'était pas plafonné à la saisie

`saveEdits` refuse au-delà de 200 caractères, et refuse **le titre et le corps ensemble**. Un
paragraphe collé dans le titre gelait donc **tous** les enregistrements de la note ; quitter l'écran
emportait le texte en silence, l'enregistrement au départ échouant lui aussi. Le publié pose un
`LengthLimitingTextInputFormatter` et rend l'état inatteignable.

La règle a demandé **quatre** versions : deux arrêtées par l'appareil, **deux par les relectures
externes** — dont la dernière portait sur le correctif de l'avant-dernière. Elle distingue une
**insertion** d'un **remplacement** par le préfixe et le suffixe communs, et ne refuse que l'insertion
faite ailleurs qu'à la fin, seul cas où rogner la fin détruirait de l'existant.

⚠️ **Le geste de mesure était vacant, et c'est une mesure qui l'a dit** : dans ce harnais, sur un titre
de 250 caractères, ni une frappe ni un collage de 300 caractères ne produisent un candidat plus long
que le texte en place. Aucune saisie ne peut donc produire la croissance que la garde refuse. D'où la
table JVM `PlafondDuTitreTest`. *Un geste de test peut être vacant comme une assertion peut l'être.*

### 🔴🔴 §82 — un fichier de test JUnit 4 dans un dépôt JUnit 5 ne tourne pas, sous un gate vert

`PlafondDuTitreTest` avait `import org.junit.Test`. `app/build.gradle.kts:219` porte
`useJUnitPlatform()` : la classe a été ignorée **sans erreur, sans avertissement, sans rapport**, et
`BUILD SUCCESSFUL` s'est affiché.

Ce qui l'a dit : **183 tests avant, 183 après**, sept ajoutés. Et l'absence du XML de la classe dans
`app/build/test-results/`. Jumeau exact de §72 côté JVM — *une ligne verte ne dit rien de ce qui n'a
pas tourné* — et **la forme du contrôle est la même : compter, et comparer à ce qu'on attendait**.

Motif balayé sur tout le dépôt dans la foulée : aucun autre fichier, 20 classes pour 20 rapports.

### 🔧 Un défaut LOCALISÉ pour la ligne suivante, non corrigé

`link_autocomplete_sheet.dart` : `suggestionsDeLien` vide sa liste à chaque frappe et ne la remplit
qu'après 120 ms — c'est voulu, et documenté. Mais pendant cette fenêtre, `proposerLaCreation` vaut
**vrai** par construction, et la garde que le portage a ajoutée exprès — *« si le titre tapé existe
déjà, on le lie »* — consulte une liste **vide**. Valider au clavier dans les 120 ms crée le doublon
que cette garde existe pour empêcher.

⚠️ **Pas une régression** : le publié affiche « Créer … » dans la même fenêtre et sa `_onSubmit` crée
toujours. C'est la divergence délibérée du portage qui est **incomplètement efficace**. Le mécanisme du
correctif est déjà écrit et éprouvé (§76 : faire porter à la réponse la question à laquelle elle
répond). À traiter à sa propre ligne.

### ⚠️⚠️ Les deux relectures externes, et la seconde a rattrapé le correctif de la première

Deux tours (GPT-5.2, Gemini 3.1 Pro), **quatre constats retenus, aucun recoupement**. Encore une fois,
chacune a vu ce que l'autre manquait — et cette fois la seconde portait **sur le correctif** de la
première, ce qui est la règle du dépôt et n'avait jamais autant payé.

| Constat | Sort |
|---|---|
| 🔴 GPT — **une troncature au milieu détruit du texte existant** : titre de 180, collage de 50 en tête, la troncature à 200 emporte les 30 derniers caractères **du titre**, en silence | **CONFIRMÉ, corrigé.** Le portage n'a pas la sélection sous la main — le titre est une `String` — mais il n'en a pas besoin : la comparaison des deux chaînes suffit à reconnaître une insertion |
| 🔴🔴 Gemini — **le correctif de GPT interdisait tout remplacement** : `startsWith(actuel)` refuse « tout sélectionner puis coller », en silence, alors que le même collage dans un champ vide passe | **CONFIRMÉ, corrigé.** Un correctif de relecture est du code neuf. Discriminant final : *préfixe commun + suffixe commun couvrent-ils le texte en place ?* — si oui c'est une **insertion**, sinon un **remplacement**, et seule l'insertion ailleurs qu'à la fin est refusée |
| 🔴 Gemini — **l'angle mort de ma table JVM** : elle mesurait le collage en tête et à la fin, pas le remplacement. C'est cette absence qui a laissé passer le défaut ci-dessus | **CONFIRMÉ, corrigé** — deux cas ajoutés, dont l'insertion **au milieu**, que le seul `startsWith` laissait passer dans l'autre sens |
| 🔴 Gemini — **mon test d'écran partait d'un titre VIDE**, et un champ vide passe n'importe quelle garde qui regarde le texte en place | **CONFIRMÉ, corrigé** — un test de plus, sur un titre existant écrasé par `performTextReplacement`. *Le choix des données initiales d'un test peut désarmer la garde qu'il croit mesurer* |
| 🔴 GPT — le témoin du troisième balayage n'assertait que `hasSize(1)`, pas **lequel** est signalé | **CONFIRMÉ, corrigé.** Il compare désormais les **coordonnées** du champ fautif, relevées sur son étiquette de test. Le jour où le filtre signale le champ **vide** à la place, il tombe |
| GPT — `state.title` capturé par la lambda pourrait être **périmé** | **ÉCARTÉ, avec l'argument.** L'invariant qui compte est *un titre parti sous la limite n'y repasse jamais* : le plafond ne dépasse 200 que si `actuel` dépasse 200, et `actuel` est une valeur **déjà acceptée** de l'état. Une lecture périmée est donc une valeur antérieure, elle aussi sous la limite — la récurrence tient quelle que soit la fraîcheur |
| GPT — comptage en **graphèmes** plutôt qu'en unités UTF-16 | **ÉCARTÉ sur le comptage, RETENU sur la coupe.** La limite doit rester en unités UTF-16, comme celle du dépôt *et* comme celle du dépôt Dart publié : compter des graphèmes ferait passer des titres que `saveEdits` refuserait ensuite, c'est-à-dire exactement le défaut que ce plafond ferme. En revanche la **coupe** pouvait scinder une paire de substituts et laisser un demi-caractère : corrigé, avec son cas |

⚠️ **Ce que GPT n'a pas pu voir, et pourquoi** : `git diff HEAD` **ignore les fichiers non suivis**. Son
premier tour n'a donc jamais reçu `PlafondDuTitre.kt` — il l'a dit lui-même, deux fois, en refusant de
conclure. Corrigé par un `git add -N` sur les trois fichiers neufs avant le second tour. *Un relecteur
qui annonce qu'il lui manque un fichier a raison ; c'est le harnais qu'il faut corriger, pas son
constat.*

### ✅ Le troisième balayage rétro-appliqué aux quatre écrans déjà cochés — rien de neuf, et c'est le résultat

`champsDeSaisieSansNom` est né au cinquième écran : les quatre premiers avaient donc été cochés par
**deux instruments aveugles à ce motif**. Les rouvrir était la seule façon de savoir si leur case
« Vérifié » disait la vérité. **Elle la disait** — l'éditeur était le seul cas. 219 instrumentés.

| Écran | Champs | Mesure |
|---|---|---|
| Accueil | 1, la recherche | vert, requête **remplie** |
| Recherche | 1, la requête | vert, requête **remplie** |
| Réglages | 0 dans l'écran, 1 dans le dialogue de panique | vert, champ **rempli** |
| Corbeille | **0** | fil-piège : aucun nœud éditable |

🔴🔴 **Chaque test compte d'abord ses champs.** Un balayage qui n'a rien trouvé **à balayer** est vert
lui aussi — §78 appliqué au troisième instrument. Le sélecteur `CHAMP_DE_SAISIE` est donc exposé à
côté des balayages, et le compte attendu est écrit **par écran**, jamais « au moins un ».

⚠️ Un champ **vide** ne discrimine rien (son placeholder le nomme) ; sur un écran **sans** champ,
appeler le balayage serait l'assertion creuse elle-même. Détail en `04-PIEGES.md` §83.

⚠️ Les feuilles de coffre — là où l'on saisit une phrase secrète — ne sont mesurées par **aucun** test
d'écran : `FermetureDeFeuilleTest` pose une feuille **synthétique**. C'est l'endroit où un champ sans
nom coûterait le plus, et il attend sa ligne de parité.

## ✅ 2026-08-17, ligne 6 : la FEUILLE D'AUTOCOMPLÉTION — son défaut était écrit d'avance

Une case de plus — `link_autocomplete_sheet.dart` —, **26 restantes**. **202 JVM + 228 instrumentés**,
0 échec, 0 ignoré, modèle intact.

🔴🔴 **La feuille proposait de CRÉER une note avant d'avoir cherché si elle existe**, et sa validation
au clavier créait l'homonyme que le portage refuse **exprès**. Pendant les 120 ms de freinage la liste
est vide par construction, et « vide parce que je n'ai pas cherché » était indiscernable de « vide
parce qu'il n'y a rien ».

⚠️ **Pas une régression de parité** — le publié crée toujours. C'est la divergence **délibérée** du
portage qui était incomplètement efficace, ce qui est plus dangereux : elle est écrite comme une
garantie. §84.

🔧 Mécanisme de §76, la réponse porte sa question — mais **pas ses valeurs limites** : ici la chaîne
vide est une **vraie** réponse. ⚠️⚠️ Et une validation au clavier pendant l'attente est **retenue**,
ni exécutée ni jetée.

🔴 **Premier balayage sur une feuille** : il signale un actionnable muet posé par
`BottomSheetDefaults.DragHandle` — **deux** nœuds aux mêmes coordonnées, dont un sans nom avec un
`OnLongClick` seul. Pas du portage, pas nommable depuis l'appelant, et il paraîtra sur **toutes** les
feuilles restantes. Exception nommée **dans le test**, jamais dans l'instrument partagé.

⚠️ **Un intermittent connu, le premier de la suite** : une exécution sur quatre a rendu
`le_bouton_de_vidange_reste_cache_pendant_le_chargement_meme_avec_des_notes` en échec
(« is not displayed »), non reproduit seul, en paire, ni en suite complète. Test non touché par ce lot.
Écrit pour ne pas être redécouvert à froid.

### ✅ Les deux fragilités de la relecture sont MESURÉES, pas mieux documentées — §85

Elles étaient vraies et non corrigées, pour la même raison écrite deux fois : le ViewModel n'est pas
exerçable hors appareil, et les tests d'écran **injectent** la réponse.

`fluxDeSuggestions` est donc **sorti du ViewModel** — extension sur `Flow<String>` — et quatre cas JVM
en **temps virtuel** figent le contrat : la réponse porte la saisie **brute** (espace final compris),
`pour = null` part **avant** le freinage, une saisie vide répond sans chercher, et une frappe pendant
le freinage annule la recherche en cours.

✅ **Contrôle positif** : `pour = texte.trim()` posé dans le vrai code fait bien tomber le test du
contrat. Restauration vérifiée au SHA-256. ⚠️⚠️ *Une fragilité qu'on sait seulement écrire est une
fragilité qu'on ne saura pas voir revenir — un commentaire ne tombe pas quand le code change.*

---

## 🔴 2026-08-18, lignes 7 à 10 : les FEUILLES DE COFFRE — trois défauts, et trois soupçons réfutés

Quatre lignes de `docs/05-PARITE.md` d'un coup, parce qu'elles vivent dans **un seul** fichier Kotlin,
`ui/vault/VaultSheets.kt` : `vault_pin_sheets.dart`, `vault_passphrase_sheets.dart`,
`passphrase_text_field.dart`, `vault_warning_banner.dart`. **23 cases vides** restantes (27 avant).

Gate : ktlint, detekt, lint `--rerun-tasks`, **206 tests JVM**, **248 tests instrumentés** (S9),
0 échec, **0 ignoré**, modèle de 57 Mo intact et empreinte revérifiée après la suite.

### Le découpage, et pourquoi il était obligatoire

`FermetureDeFeuilleTest` était le seul test de ce fichier — et il mesurait une feuille **synthétique**,
quatre lignes recopiées à la main. Il a rendu un vrai service le 08-16 (il a départagé deux relectures
qui se contredisaient), mais il ne disait **rien** des vraies feuilles. Quatre de leurs états ne
s'atteignent pas au doigt : coffre effacé, temporisation, conversion partielle, phase de chiffrement.

`PinSheet`/`PassphraseSheet` restent branchées à Hilt et délèguent à `FeuilleDeCode`/
`FeuilleDePhraseSecrete`, qui ne reçoivent qu'un `VaultSheetState` et des rappels. Même découpage que
`HomeRoute`/`HomeScreen`. Effet de bord utile : les trois chemins de sortie (Retour, balayage,
« Annuler ») faisaient **deux gestes différents pour un effet identique** — un jumeau de moins.

### Les trois défauts

- **§86 — la destruction du coffre n'était annoncée à personne.** Aucune région active sur ces deux
  feuilles, dans **aucun** état. Le publié en a trois. `PanicScreens.kt` savait déjà le faire :
  *l'idiome existait et n'avait pas traversé.*
- **§87 — la garde anti-décalage réservait UNE ligne.** À `font_scale 2,0` la touche « 5 » descend de
  **96 px (32 dp)** quand un message apparaît, soit 40 % du pas entre deux touches. À 100 %, zéro —
  d'où l'invisibilité. *Une garde peut être réelle et dimensionnée sur le seul cas que son auteur avait
  sous les yeux.*
- **§89 — après l'effacement, le pavé et « Valider » restaient actifs.** Retaper un code sur un coffre
  détruit fait remonter un refus qui écrase la phrase annonçant la destruction. Le garde existait pour
  la conversion partielle et pas pour l'effacement : **troisième jumeau asymétrique du même fichier.**

### 🔴 Ce qui a été RÉFUTÉ — §90, et c'est le résultat le plus utile

Trois soupçons sérieux, tous fondés sur une lecture du code publié, sont tombés à la mesure : les
champs **gardent** leur nom une fois remplis, `EditableText` ne porte que des **puces**, et Compose
**retire déjà** `CopyText`/`CutText` d'un champ à transformation mot de passe. Le `contextMenuBuilder`
du publié n'a donc pas d'équivalent à écrire.

⚠️⚠️ Le troisième a coûté **trois instruments**. Un espion de `TextToolbar` n'a rien vu — **ni sur le
témoin**, donc il ne prouvait rien. C'est la sémantique qui a tranché, avec son témoin. *Un échec
d'outillage n'est pas un verdict négatif*, et ce dépôt vient de le repayer.

⚠️ Deuxième instrument vacant du jour : une `ModalBottomSheet` compose dans une fenêtre qui **repose
ses propres `CompositionLocal`**. `LocalDensity` fourni au-dessus d'elle n'y entre pas — d'où deux
mesures à l'échelle ×2 **identiques au pixel près**, ce qui est impossible et a dénoncé l'instrument.
La taille de texte se change par `adb shell settings put system font_scale`, et se **restaure**.

### Contrôle positif

Les trois défauts ont été **remis dans le vrai code** en même temps : exactement les **six** tests
attendus sont tombés, et aucun autre. Fichier restauré par l'édition inverse, **vérifié au SHA-256**.

### Points ouverts, à décider et non à redécouvrir

- **§88 — le champ de phrase secrète est éligible à l'autoremplissage.** Mesuré : `ContentType` vient
  de `KeyboardType.Password`. Le publié s'en retire exprès (décision U1 v1.0.9). Le seul levier interne
  à Compose échangerait l'opt-out contre le comportement mot de passe du clavier — un moins bon marché.
  Le levier plateforme existe mais son **effet** n'est pas mesurable ici. **Écrit, non corrigé.**
- **L'œil du code à quatre chiffres** est un ajout du portage ; le publié le **refuse** et donne sa
  raison (défense contre le regard par-dessus l'épaule). Le commentaire d'`arb_vers_strings.py` note
  l'ajout mais pas le refus. Décision de Patrice.
- **La promesse publique « Coffres par dossier » reste décochée** : elle porte sur la crypto du
  service, pas sur ces feuilles. Rien de ce tour ne la mesure.

### Deux commentaires de plus qui mentaient — les miens, attrapés avant le commit

Écrits dans ce lot même, et corrigés : le KDoc de `FeuilleDePhraseSecrete` annonçait « cinq états »
dont « un coffre effacé », alors que `VaultPinWipedException` n'est levée que par `unlockWithPin`
(vérifié : ses trois sites y sont tous) ; et le KDoc de `plusRienAEssayer` promettait « ni pavé, ni
champ » alors que la feuille à phrase secrète gardait ses deux champs. Le second a été corrigé **dans
le code**, pas dans le commentaire.

⚠️ Un relecteur externe a par ailleurs rendu un rapport entier sur **des fichiers absents du diff**
(`NoteEditorScreen.kt`, `PlafondDuTitre` — le lot de la veille), avec des numéros de ligne préfixés de
`~`. Rapport jeté. *Un constat qui ne cite pas une ligne réelle du diff n'en est pas un.*

---

## 🔴 2026-08-18, lignes 11 et 12 : le TIROIR DES DOSSIERS — un geste destructeur qui s'évaporait

`folders_drawer.dart` et `folder_dialogs.dart`. **21 cases vides** restantes (23 avant).

Gate : ktlint, detekt, lint `--rerun-tasks`, **211 tests JVM**, **267 tests instrumentés** (S9),
0 échec, **0 ignoré**, modèle de 57 Mo intact.

### La prémisse du tour était fausse, et il a fallu vingt minutes pour le voir

`PROMPT-REPRISE.md` proposait ce tour parce que « c'est le tiroir qui appelle les feuilles de coffre,
et rien ne mesure ce qu'il fait des issues qu'elles remontent ». **C'est faux** : dans le portage,
`HomeRoute` et `NoteEditorScreen` les appellent. Le publié, lui, les ouvre bien depuis son tiroir.
*Une proposition écrite la veille se vérifie comme le reste.*

### §91 — le défaut, sur le chemin le plus destructeur de l'application

Deux gestes déchiffrent tout un dossier, donc exigent une session ouverte : **retirer la protection**
et **supprimer en gardant les notes**. Un seul mémorisait son intention avant d'ouvrir la feuille de
déverrouillage. Le parcours réel de l'autre : confirmer, saisir le secret, le coffre s'ouvre… et le
dossier est toujours là, sans un mot.

⚠️⚠️ **Le KDoc du porteur décrivait exactement ce mal — pour l'autre geste**, quinze lignes plus haut :
*« Sans ce report, l'utilisateur confirmait le geste le plus destructeur de l'application, saisissait
sa phrase secrète… et il ne se passait rien. »* Juste, au bon endroit, et sans effet. *Un commentaire
qui nomme un défaut ne protège que la ligne qu'il commente.*

🔧 Correction par **suppression du jumeau** : `GesteDeDossier` (interface scellée), une décision
unique `deverrouillageRequis`, un seul chemin `lancerLeGeste`, un `when` exhaustif à l'exécution.

⚠️ Et une limite écrite plutôt que masquée : les 5 cas JVM mesurent la **décision**, pas le câblage.
Prouver le câblage demanderait Hilt + base chiffrée + coffre réel. Ce qui protège cette partie est
**structurel** — un seul chemin, un `when` exhaustif — et non mesuré.

### §92 — le reste du tiroir : rien de cassé

Le soupçon principal est tombé : un `IconButton` posé dans le slot `badge` d'un
`NavigationDrawerItem` — un cliquable **dans** un cliquable qui fusionne — **reste atteignable**.
Mesuré par **le rappel qui part**, pas par la présence d'un nœud : `onNodeWithContentDescription`
aurait rendu la rangée fusionnée, dont le nom contient bien « Options du dossier », et
`assertHasClickAction` serait passé pendant qu'un appui sélectionnait le dossier.

Trois écarts écrits, non corrigés : **aucun appui long** dans le portage (assumé, trois raisons) ;
`FolderEvent.Deleted` porte un décompte que **personne ne lit** ⇒ supprimer un dossier déplace ses
notes en silence ; les feuilles de coffre ouvertes par `HomeRoute` et non par le tiroir. Et le tiroir
**en cours de chargement** affiche une boîte de réception de repli — motif §75/§76, hérité du publié,
**figé par un test plutôt que corrigé**.

⚠️ Un commentaire de plus corrigé : le KDoc du tiroir disait « l'appui long fait la même chose » —
vrai du publié, **faux d'ici**. Sixième de la série.

### Contrôles

Décompte des actionnables **mesuré** (9) et posé **avant** le balayage — §83. Contrôle positif sur
`deverrouillageRequis` : la clause `isVault` retirée du vrai code fait tomber un cas ; restauration
vérifiée au **SHA-256**. Compte JVM vérifié classe par classe : 206 → **211**, XML de
`GesteDeDossierTest` présent — le piège §82 ne s'est pas refermé.

---

## 🔴 2026-08-18, lignes 13 et 14 : LA DICTÉE — un écran aux balayages verts qui ne disait rien

`voice_recording_overlay.dart` et `voice_record_button.dart`. **19 cases vides** restantes (21 avant).

Gate : ktlint, detekt, lint `--rerun-tasks`, **221 tests JVM**, **280 tests instrumentés** (S9),
0 échec, **0 ignoré**, modèle de 57 Mo intact, `font_scale` restauré à 1,0.

### Ce que ce tour apprend, et qui vaut au-delà de la dictée

**Les trois balayages étaient verts avant comme après.** Aucun nœud anonyme, aucune action perdue à
la fusion, aucun champ de saisie — et pourtant l'écran ne disait rien du tout à un lecteur d'écran
une fois ouvert. *Un écran sans nœud anonyme peut être un écran qui ne dit rien.* La question « que
reçoit un lecteur d'écran ? » n'a de réponse que posée **état par état**, et c'est le troisième
fichier de suite où c'est elle, et non les balayages, qui trouve le défaut.

### §93 — pendant qu'on parle, aucune sortie qui ne transcrive

« Arrêter » transcrit et insère ; « Annuler » jette. Seul le premier existait pendant
l'enregistrement. `abandonner` était posé sur le contrôleur, câblé au `ViewModel`, traversait
jusqu'au moteur natif — et **aucun bouton ne l'appelait dans cet état**.

⚠️⚠️ **Le commentaire de la feuille argumentait en faveur du défaut** : il tenait les deux boutons
pour « deux mots pour un même geste ». Quatrième forme du commentaire qui ment, et la plus coûteuse —
un relecteur qui le lisait avait sa réponse et passait.

### §94 — le changement d'étape n'était annoncé à personne

`régionsActives=[]` dans les trois états. Un `AlertDialog` est annoncé à son ouverture ; ce qui
change ensuite dans ses emplacements ne l'est pas. Le « Parlez » qui dit que le micro est ouvert
n'était **jamais** dit — alors que l'étape `INITIALISATION` avait été ajoutée en phase 7 précisément
pour ne pas le dire trop tôt.

🔧 Deux régions, `Polite` : le titre et la consigne vivent dans deux emplacements distincts, et l'un
sans l'autre ment. `Assertive` couperait la première annonce par la seconde.

### §95 — le micro sans modèle était une impasse

Il affichait « Aucun modèle de transcription installé. » et n'allait nulle part ; l'écran
d'installation n'était atteignable que depuis les réglages. Le publié l'ouvre directement.
🔧 La décision est extraite en `gesteDuMicro`, interface scellée à trois issues — idiome du §91 —
donc mesurable en JVM là où elle était hors d'atteinte.

### §96 — la borne de deux minutes s'appliquait en silence

Rien ne distinguait « la limite est atteinte » de « l'utilisateur a appuyé sur Arrêter » : on dictait
trois minutes, il en manquait une, sans moyen de le savoir. Le publié n'a **aucune** borne mais
affiche un chronomètre.

🔧 Deux réponses, à deux moments : le compteur « 1:37 / 2:00 » **nomme** la borne pendant qu'on
parle, le message la constate après coup. Aucune ne remplace l'autre — la première sert à ne pas y
arriver, la seconde à savoir qu'on y est arrivé. `enregistrer()` rend une `Capture(fichier, fin)` au
lieu d'un `File?`, parce qu'un `File` ne peut pas dire **pourquoi** la capture s'est arrêtée.

⚠️⚠️ **Le correctif a eu son propre défaut, et le test l'a dit.** Le compteur était masqué par
`clearAndSetSemantics` — raisonnement solide, la colonne est une région active — mais **un nœud
effacé disparaît des deux arbres** : il n'était plus lisible par personne, même à l'exploration. Il
est posé en **frère** de la région. *Ne pas crier n'oblige pas à se taire.*

⚠️ Ce correctif touche `VoiceCapture`, donc la ligne `voice_service.dart`, qui **reste décochée** :
il en ferme un défaut, il ne la mesure pas.

### Contrôles

Décompte des actionnables mesuré et posé **avant** les balayages. Contrôle positif : les deux
défauts réintroduits dans le vrai code font tomber **4 cas sur 10** ; restauration vérifiée au
**SHA-256**. Deux autres contrôles pour §96 : `>=` remis en `==` fait tomber le cas du dépassement
et lui seul ; le compteur remis **dans** la région active fait tomber les deux cas d'annonce —
restaurations vérifiées au SHA-256. Suite rejouée **à `font_scale 2,0`** — les deux boutons
restent entiers — puis échelle restaurée. Compte JVM vérifié classe par classe : 211 → **221**,
XML de `GesteDuMicroTest` et `BorneDeDureeTest` présents (§82).

---

## ✅ 2026-08-18, fin de journée : les quatre écarts du publié, §88, la passerelle 2.0.4, et le COFFRE

**42 cases cochées / 17 restantes.** Gate : ktlint, detekt, lint `--rerun-tasks`, **224 tests JVM**,
**297 tests instrumentés** (S9), 0 échec, **0 ignoré**, modèle de 57 Mo intact.

### Les commits

| Dépôt | Commit | Ce qu'il ferme |
|---|---|---|
| portage | `cc1d36d` | les **quatre** écarts du publié + §88 |
| portage | `816c207` | la couche ① de la migration n'avait **aucun** test |
| portage | `92eb02f` | la clé de signature, vérifiée contre l'APK publié |
| portage | `84707a4` | **§99 + §100** — le coffre |
| `notes_tech` | `f216390` | la **passerelle 2.0.4** (branche `fix/defauts-releves-pendant-le-portage`) |

### ✅ §88 — l'autoremplissage est CONSERVÉ, décision de Patrice

Et la mesure qui a tranché n'était pas celle qu'on cherchait : le champ expose **`PasteText` et pas
`CopyText`**. Un gestionnaire de mots de passe peut donc y **déposer** un secret, personne ne peut en
**extraire** un. Un secret de coffre perdu, ce sont des notes perdues pour toujours. **Aucune ligne de
code touchée** — l'état mesuré était déjà l'état voulu.

### ✅ Les quatre écarts du publié sont tranchés

1. **Archives** — ce n'est pas un écart, c'est la définition d'une archive. Le « correctif » évident
   aurait rendu des notes **invisibles partout**. Épinglé par 3 cas.
2. **Messages de dossier — §97** : renommer se voit, **supprimer non**. Le plus injuste : supprimer un
   dossier détruit **aussi ses notes en corbeille**, encore restaurables. D'où `countAllInFolder`.
3. **Pluriel `many` — §98** : les 4 formes écrites, `MissingQuantity` à **0** — mais **elle ne sort
   pas sur le S9**, l'ICU d'Android 10 l'ignore. Le test accepte les deux formes plutôt que de mesurer
   la version d'ICU de la machine.
4. **`%1$d note(s)`** converti en vrai `<plurals>`.

### 🔴 Le coffre — §99 et §100

- **§99** : `isUnlocked` **repoussait** l'échéance du verrouillage automatique. Défaut *posé et armé,
  pas déclenché* — il attendait un appelant venu d'une recomposition.
- **§100** : la migration **v1 → v2** n'était exercée par **aucun test, des deux côtés**. Le publié
  porte `encryptNoteLegacyV1`, écrite exprès pour la rendre vérifiable, et **personne ne l'appelle**.

⚠️ **Réfutés, ne pas rechercher** : freinage identique au chiffre près, longueurs de secret
identiques, ordre de l'auto-effacement identique.

### ✅ La passerelle 2.0.4 — **PUBLIÉE le 2026-08-18**

`notes_tech` **`be6fe0d`** sur `main`, tag **`v2.0.4`**, versionCode **2052**, trois APK par ABI,
les trois workflows verts. Vérifié sur le S9 avant publication : `notes_tech.kek.xml` écrit, **blob
48 octets** (32 + tag GCM), **nonce 12**, aucun retour à la ligne. La couche ① la relit — 8 cas, dont
un scellé produit *comme la passerelle le produit*.

**Vérifié sur l'artefact PUBLIÉ, et non sur le dépôt** : certificat **identique à la 2.0.3**
(`ddb385de…42e9` — le chemin de mise à jour tient), `versionCode` 2052 / `versionName` 2.0.4, et
`sealDatabaseKek` + les trois valeurs du contrat **présents dans le dex**.

⚠️⚠️ **Le changelog fastlane a une règle qu'aucun document ne portait** : les quatre derniers sont en
**ASCII pur**, 471–492 octets. Le cap F-Droid compte des **caractères** ; en ASCII octets =
caractères, donc `wc -c` suffit à le vérifier, et un accent casse cette égalité. `52.txt` : **487**
(FR), **435** (EN), zéro non-ASCII.

⚠️ Site `files-tech.com` bumpé sur ses **cinq** surfaces (`939de98`) et **vérifié en ligne** — pas
seulement déployé, la leçon de mai.

⚠️ **La MR F-Droid `!37885` n'a pas été touchée**, exprès.

⚠️ **Un contrôle m'avait échappé** : `flutter analyze lib/` au lieu de `flutter analyze`, donc un
import inutile de mon propre test est passé. *Restreindre le périmètre d'un contrôle, c'est se
garantir qu'il ne dira rien de ce qu'on a laissé dehors.* Corrigé avant la fusion.

⚠️ **Deux écarts assumés** avec la procédure écrite : octets bruts plutôt qu'hexadécimal (une `String`
portant la KEK est ineffaçable en Dart), et idempotence sur les préférences **et** l'alias.

⚠️ **Une affirmation de la doc était fausse** : `Base64.DEFAULT` ne fait **pas** échouer le décodage,
le décodeur d'Android tolère les retours à la ligne. Mesuré, et corrigé dans le document.

### 🔴 Ce qui bloque la bascule, et ce n'est pas une décision de rédaction

**Le portage n'a aucun `key.properties`.** La 3.0.0 doit être signée avec
`notes_tech/android/notestech-release.jks` — dont l'empreinte a été **vérifiée contre l'APK
réellement publié** (`ddb385de…42e9` des deux côtés). Sans cette clé : ni vérification de bout en
bout, ni bascule.

⚠️ **F-Droid ne s'y oppose pas** : la MR `!37885` est épinglée sur 2.0.3/51 et porte
`AutoUpdateMode: Version` + `UpdateCheckMode: Tags` — une fois fusionnée, le bot suit les tags seul.
Publier une 2.0.4 ne la dérange donc pas. ⚠️ Le sens du label `waiting-for-upstream` reste **inconnu**.
