# E1 — angle ACCESSIBILITÉ — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26)

VERDICT : VRAI POSITIF. Gravité retenue : FAIBLE. Aucune garde entre l'intent de B et le plantage (copie ebee629 + bytecode Navigation 2.9.8 extrait hors dépôt dans `scratchpad\nav298-e1`).

**1. Entrée ouverte à B** : manifeste fusionné l. 65-72 (`exported="true"`, sans permission, `singleTop`), `targetSdkVersion="36"` (l. 9). La correspondance au filtre (Android 13+) ne défend pas : B pose MAIN + LAUNCHER sur son intent explicite, les extras n'entrent pas dans la correspondance. Lancé depuis l'activité de B sans `FLAG_ACTIVITY_NEW_TASK` : nouvelle instance dans la tâche de B, `NavController` neuf (`deepLinkHandled=false`), que Notes Tech tourne déjà ou non. `MainActivity.kt:80-97` ne touche pas l'intent (aucun `setIntent`, `replaceExtras`, `removeExtra`).

**2. Navigation 2.9.8 exécute l'ordre** : `LockedAppHost.kt:31` (`rememberNavController()`, `LocalContext.current` = l'activité) → `NotesTechNavHost.kt:28` → `NavHostController.setGraph` (NavHostKt, offset 950) → `NavControllerImpl.onGraphCreated` (643-667 : `if (_graph != null && backQueue.isEmpty()) checkDeepLinkHandled()`) → `checkDeepLinkHandled` (0-37 : `!deepLinkHandled && activity != null` → `handleDeepLink(activity.getIntent())`) → lit `deepLinkIds` (12-23), `deepLinkExtras` (326-373) ; sans `NEW_TASK`, branche 167-414 : navigue vers le dernier identifiant avec `popUpTo(graph)`. Aucune URI nécessaire. Aucun `LocalContext provides`. (2.10.1 en cache vient d'autres projets ; même structure.)

**3. B calcule les identifiants** : destination `id = ("android-app://androidx.navigation/" + route).hashCode()` (`NavDestinationImpl.setRoute` 47-173 ; `createRoute`) ; route `"editor/{noteId}"` (`Destination.kt:70-74`) ; graphe `route = null` → `id` 0 (`NavDestinationBuilder.build` 259-290) ; `findInvalidDestinationDisplayNameInDeepLink` exige `deepLink[0] == graph.getId()` : B envoie `[0, hash]`.

**4. Le plantage se produit** : `NotesTechNavHost.kt:41` sans défaut, non nullable ; `putDefaultValue` n'écrit rien ; `NavArgument.verify` vrai pour une clé absente (`StringNavType.get` rend `null`) ; `NoteEditorScreen.kt:119` `hiltViewModel()` → `NoteEditorViewModel.kt:190` `checkNotNull(...) { "l'editeur a ete ouvert sans identifiant de note" }` → `IllegalStateException` ; aucun gestionnaire global ; processus tué. Extras fusionnés aussi dans les arguments de chaque destination (820-834) : `HomeViewModel.kt:87-88`, `SearchViewModel.kt:114`.

**Gain** : tuer à volonté le processus, y compris une instance ouverte dans la tâche de l'utilisateur ; choisir l'écran d'ouverture (réglages, corbeille, dictée, pages légales…) ou le filtre et la recherche initiaux. Ni lecture, ni écriture, ni coffre (`noteId` inconnu → `notFound = true`, `NoteEditorViewModel.kt:759-761`).

**FAIBLE** : B installée et au premier plan (restrictions de lancement = plateforme) ; l'utilisateur voit l'app s'ouvrir puis planter ; avec verrou et processus froid, plantage seulement après le PIN (`LockedAppHost.kt:33-37`) ; non répétitif (le lanceur porte un intent propre) ; la mort du processus referme les coffres ; sur API 24-33, `KILL_BACKGROUND_PROCESSES` permettait déjà de tuer un processus d'arrière-plan (plateforme, non utilisé comme réfutation).

Non exécuté : plantage déduit du bytecode et du source.
