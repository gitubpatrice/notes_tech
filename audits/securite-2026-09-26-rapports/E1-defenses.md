# E1 — angle DÉFENSES — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26)

**VERDICT : VRAI POSITIF — gravité FAIBLE (inchangée).** Aucune garde.

**Attaquant et confiance accordée** : B, toute app installée sans permission. Manifeste fusionné l. 68 `exported="true"` sans `android:permission` (bloc 65-78). Bytecode `navigation-runtime-android 2.9.8` : `NavController.checkDeepLinkHandled` appelle `activity.getIntent()` puis `handleDeepLink(Intent)` ; `NavControllerImpl.onGraphCreated` (offsets 643-667) l'invoque à chaque `setGraph` sur pile vide. `rememberNavController` prend `LocalContext.current` (l'activité), jamais remplacé dans `app/src`.

**Défenses vérifiées**
1. Nettoyage de l'intent : absent (ni `setIntent`, `replaceExtras`, `removeExtra` ; `MainActivity.kt:80-97`, `:118-121` ; `onNewIntent` → `appLockLifecycle.onNewIntent()` seulement).
2. Navigation 2.9.8 : aucun contrôle de l'émetteur. `handleDeepLink(Intent)` lit `…:deepLinkIds`, `…:deepLinkArgs`, `…:deepLinkExtras`, fusionnés ; ne vérifie ni appelant, ni paquet, ni tâche. Contrôles : tableau non vide ; `findInvalidDestinationDisplayNameInDeepLink` exige des identifiants du graphe — déterministes (graphe à 0 ; éditeur `"android-app://androidx.navigation/editor/{noteId}".hashCode()`). `NEW_TASK` sans `CLEAR_TASK` relance l'activité. Même logique en 2.10.1.
3. Valeur par défaut : absente (`NotesTechNavHost.kt:41` `navArgument(Destination.ARG_NOTE_ID) { type = NavType.StringType }`, sans `defaultValue` ni `nullable`) ; `NavArgument.verify` ne rejette pas une clé absente ; navigation vers l'éditeur sans `noteId` acceptée.
4. Gestionnaire d'exception global : aucun (`NotesTechApplication.kt:40-94`).
5. Verrou d'app : ne fait que différer (optionnel, `AppLockManager.kt:186` ; configuré, `LockedAppHost.kt:31` crée le NavController au-dessus du verrou, le NavHost se compose au déverrouillage l. 33-36 avec le même `getIntent()` : navigation forcée puis plantage juste après le PIN).
6. singleTop : protège seulement une instance au sommet (intent vers `onNewIntent`, non relu) ; B peut créer une nouvelle instance (lancement depuis sa tâche sans `NEW_TASK`, ou `NEW_TASK|CLEAR_TASK`) → `onCreate`.
7. Filtrage des intents explicites (Android 13+) : contourné (B pose `MAIN`/`LAUNCHER` ; extras non filtrés ; `intentMatchingFlags` absent).

**Opération atteinte** : `NoteEditorViewModel.kt:190` `checkNotNull(savedState[Destination.ARG_NOTE_ID])`, via `hiltViewModel()` (`NoteEditorScreen.kt:119`) : `IllegalStateException` en composition, processus tué. **Aussi** : dans `handleDeepLink(Intent)`, `deepLinkArgs.get(i)` (offsets 842-846) sans borne, hors du seul `try` (11-30) : un `deepLinkArgs` plus court que `deepLinkIds` lève dans Navigation même. **La correction doit porter sur l'intent : retirer les extras `android-support-nav:controller:*` avant `setContent` ; corriger la ligne 190 ne suffit pas.**

**FAIBLE** : plantage à volonté et choix de l'écran interne ; non persistant (le lanceur porte un intent propre) ; rien ne remonte à B (ni `setResult`, ni écran) ; aucune garde contournée (verrou autour du NavHost ; coffres fermés à l'arrière-plan) ; aucun écran n'agit à l'arrivée (seul `VoiceSetupViewModel` relit l'état installé) ; B doit être au premier plan ; perte de données théorique (autosave 500 ms, `NoteEditorViewModel.kt:969`).

Rien exécuté. L'agent a extrait `classes.jar` et sorties `javap` dans `scratchpad\e1-nav\` (hors dépôt).
