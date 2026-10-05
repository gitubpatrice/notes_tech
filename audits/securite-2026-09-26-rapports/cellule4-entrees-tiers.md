# Cellule 4 — « entrée reçue d'un tiers » — rapport du chercheur (reçu 2026-09-26)

Deux constats, tous deux FAIBLES. Aucun chemin ne permet à une autre application ou à un contenu hostile de lire une note, d'obtenir un fichier, d'ouvrir un coffre, de charger une ressource distante ou d'exécuter du code natif.

Code : `...\audit-ebee629\app\src\main\java\com\filestech\notes_tech\` (noté `…\`).

## E1 (Constat 1) — L'activité exportée exécute les ordres de navigation de n'importe quelle application — FAIBLE, confiance MOYENNE, CWE-926
- Opération : `…\ui\NotesTechNavHost.kt:28` `NavHost(navController = navController, startDestination = Destination.Home.route) {` ; contrôleur `…\ui\LockedAppHost.kt:31` `val navController = rememberNavController()`.
1. Source : B lance `MainActivity` par intent explicite avec `android-support-nav:controller:deepLinkIds` (int[] : graphe puis destination) et `android-support-nav:controller:deepLinkExtras` (Bundle). Activité `exported="true"` sans permission (manifeste fusionné l. 65-78).
2. Aucun filtre : `MainActivity.onCreate` (`MainActivity.kt:80-97`) et `onNewIntent` (l. 118-121) ne lisent ni ne nettoient `intent`.
3. `ContenuPrincipal` → `LockedAppHost` (MainActivity.kt:234-237) → contrôleur (LockedAppHost.kt:31) ; aucun `LocalContext` remplacé : le `NavController` retrouve `MainActivity`.
4. Lu à l'installation du graphe (NotesTechNavHost.kt:28) : tout de suite sans verrou, juste après le PIN sinon (LockedAppHost.kt:33-37). Navigation 2.9.8 lit `activity.getIntent()` (bytecode `navigation-runtime-android` 2.9.8, `E:\.gradle\caches\8.13\transforms\c6e784acf22bc0e216b09d24ab923a4e\transformed\navigation-runtime-release\jars\classes.jar`, classe `NavController` : `checkDeepLinkHandled`, `getIntent`, clés `deepLinkIds`/`deepLinkArgs`/`deepLinkExtras`, `TaskStackBuilder.addNextIntentWithParentStack`/`startActivities` ; aucune chaîne de contrôle de l'émetteur).
5. Arguments de B dans les ViewModels via `SavedStateHandle` : `NoteEditorViewModel.kt:190` `checkNotNull(savedState[Destination.ARG_NOTE_ID])` ; `HomeViewModel.kt:87-88` (`home.folderId`, `home.query`) ; `SearchViewModel.kt:114` (`search.query`).
- Gain : faire planter Notes Tech à la demande (`editor/{noteId}` sans `noteId` ou mal typé → `checkNotNull` lève à la construction du ViewModel, processus mort) ; choisir l'écran d'ouverture et son état initial. Pas de lecture (UUID aléatoires, B ne voit pas l'écran), pas d'ouverture de coffre (`charger()` exige la clé), pas d'écriture (aucune destination n'agit à l'arrivée). Perte de saisie bornée par l'autosauvegarde à 500 ms.
- Identifiants de destination dérivés de `"android-app://androidx.navigation/<route>"` (préfixe dans `navigation-common` 2.9.8).
- Recommandation : un intent externe ne pilote aucune navigation ; l'éditeur affiche « note introuvable » au lieu de planter ; important le jour où une destination agira à son arrivée.

## E2 (Constat 2) — Une autre application peut repousser indéfiniment le reverrouillage — FAIBLE, confiance FAIBLE, CWE-613
- Opération : `…\security\applock\RelockPolicy.kt:71-72` (`leftAt = null` puis `val delayElapsed = left != null && now() - left >= delayMillis`) ; réarmement `RelockPolicy.kt:57` `leftAt = now()`.
1. Verrou avec délai ≠ `IMMEDIATELY` (défaut, `AppLockStore.kt:31-35`, 140) ; l'utilisateur quitte : `decideStop` pose `leftAt = now()` (:57).
2. B relance `MainActivity` avant l'échéance (instance existante au premier plan, singleTop, ou nouvelle instance dans la tâche de B).
3. `MainActivity.onStart` (MainActivity.kt:99-102) → `AppLockLifecycle.onStart` (AppLockLifecycle.kt:54-57) → `RelockPolicy.onStart` (68-74) : délai non écoulé, pas de verrou, échéance consommée (:71).
4. Retour par intent traité comme entrée extérieure seulement pendant le sélecteur de document (RelockPolicy.kt:98, PickerRelockPolicy.kt:85-90).
5. B repasse devant : `onStop` → `decideStop` (AppLockLifecycle.kt:81) → `leftAt = now()` (:57). Répété : le verrou ne s'enclenche jamais.
- Attaquant : B sans permission, mais capable de lancer une activité à l'insu de l'utilisateur (API 24-28 sans restriction de lancement en arrière-plan ; ou superposition accordée) ; sinon Notes Tech apparaît à l'écran. Gain en combinaison avec A qui tient plus tard le téléphone déverrouillé : notes hors coffre sans PIN. Coffres fermés (`NotesTechApplication.kt:105-113`).
- Confiance FAIBLE : la variante silencieuse suppose qu'une activité lancée écran éteint passe par `onStart` puis `onStop` ; non vérifié.
- Recommandation : échéance comptée depuis la dernière interaction réelle ; un retour au premier plan non initié par l'utilisateur ne la consomme ni ne la réarme.

## Hors angle, sans attaquant (notes, pas des candidats)
- **Relais d'annulation vers le natif inopérant** (`…\data\voice\WhisperStt.kt:143-150`) : `invokeOnCompletion` sans `onCancelling` ne se déclenche qu'à l'achèvement du job (après `whisper_full`) ; `abonnement.dispose()` (l. 149) retire le rappel avant. `demanderArret` n'atteint jamais le calcul : une transcription annulée (écran ou panique) va au bout, résultat jeté (l. 156). WAV lu entièrement avant le calcul (l. 106, 203). Aucun test. Confiance MOYENNE.
- **Requête réseau indirecte au démarrage** : manifeste fusionné `androidx.emoji2.text.EmojiCompatInitializer` (l. 118-120) : demande une police d'émojis au fournisseur de polices des services Google, qui peut la télécharger. Contenu des notes non concerné. Confiance FAIBLE.

## Cherché sans rien trouver
- Intents entrants : hors Navigation, aucun code ne lit `intent` ; `onNewIntent` ne peut que verrouiller (AppLockLifecycle.kt:86-91) ; aucune création de note, sortie de coffre, export ou panique par intent ; FileProvider, InitializationProvider, MultiInstanceInvalidationService non exportés ; ProfileInstallReceiver protégé par DUMP ; récepteur SCREEN_OFF `RECEIVER_NOT_EXPORTED` + permission signature, ne fait que verrouiller ; `taskAffinity=""` empêche le détournement de tâche ; redirection via `TaskStackBuilder` relance la même activité, `selector` ignoré.
- FileProvider : un seul chemin `cache-path exports/` (`res\xml\file_paths.xml:15`) ; noms assainis (`…\domain\export\NoteMarkdown.kt:173-185`) ; permission accordée seulement par le partage (`…\ui\common\Partage.kt:44-51`) ; occupation de l'autorité = échec d'installation, rien de détourné.
- Aperçu Markdown : seuls `http`, `https`, `mailto` deviennent des liens (`…\domain\markdown\MarkdownPreviewReader.kt:282-286`, 316-317) ; contrôle et > 8 192 caractères refusés ; `intent:`, `javascript:`, `file:`, `content:` restent du texte ; intent d'ouverture sans extra ni drapeau (`…\ui\common\LienExterne.kt:26`) ; images jamais chargées (618-632) ; HTML brut affiché comme code ; ni WebView, ni `fromHtml`, ni `LinkAnnotation.Url` ; pas de faux `[[…]]` par entités (`WikiLinkMask.split`) ; résolution d'un `[[Titre]]` limitée au coffre ouvert et aux notes hors coffre (`NotesRepository.kt:194-199`) ; adresses « À propos » et pages légales constantes.
- Modèle de dictée et natif : SHA-256 inscrit (`…\domain\voice\SttModelCatalogue.kt:51`, 61) ; copie bornée et hachée en un passage (`…\domain\voice\CopieVerifiee.kt:94-148`) ; revérifié avant chaque chargement (`WhisperStt.kt:88-93`) ; identifiant validé `^[a-z0-9_-]+$`, chemin interne, WAV décodé en Kotlin ; JNI (`app\src\main\cpp\notes_stt_jni.cpp`) contrôle poignée et indices, libère sur tous les chemins, attrape les exceptions C++ (196-201), octets bruts ; TOCTOU exige d'écrire dans `files/stt/` (root) ; un `.bin` piégé n'atteint jamais ggml.
- Presse-papiers : `SensitiveClipboard.lire()` lit `.text` seulement ; une app qui écrit en boucle peut faire échouer CLIPBOARD_CLEAR de la panique, étapes indépendantes (`PanicService.kt:269-275`, 478-486) : rapport pessimiste seulement ; ni `contentReceiver` ni glisser-déposer.
- Recherche : termes entre guillemets pour FTS5 (`…\data\local\FtsMatchExpression.kt:72-88`).
- PROCESS_TEXT : `<queries>` expose la sélection (y compris note de coffre) aux apps tierces, réponse remplaçant la sélection ; exige un toucher de l'utilisateur sur l'entrée tierce : non retenu ; affichage Compose non vérifié.
- Partage : `EXTRA_SUBJECT` = nom de fichier (titre), geste volontaire.
- Réseau : aucune permission INTERNET ; aucun contenu de note ne déclenche de chargement distant.
