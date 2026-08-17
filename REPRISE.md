# Reprise — portage Kotlin de Notes Tech

> Écrit le 2026-08-15 au soir, **mis à jour le 2026-08-17**. À lire en premier, avant `docs/00-PLAN.md`.
> Ce fichier ne remplace pas les docs : il dit **où on en est** et **quoi faire ensuite**.

## État en trois lignes

- Dépôt : `j:\applications\notes_files_tech`, branche `master`, arbre **propre**, et **toujours aucun
  remote** — rien n'est poussé nulle part. ⚠️ Le compte de commits n'est plus écrit ici : il devenait
  faux au commit suivant. `git rev-list --count HEAD` le dit sans dériver.
- Gate **vert** au 2026-08-17 : ktlint, detekt, lint (`--rerun-tasks`), **176 tests JVM**,
  **152 tests instrumentés** (S9), 0 échec, **0 ignoré** — comptés par les codes de statut.
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
