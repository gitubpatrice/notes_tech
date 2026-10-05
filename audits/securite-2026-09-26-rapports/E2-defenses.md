# E2 — angle DÉFENSES — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26)

VERDICT : VRAI POSITIF. Gravité retenue : FAIBLE (plancher). Références exactes ; aucune garde qui coupe le chemin.

**1. L'entrée est contrôlée par l'attaquant** : `AndroidManifest.xml:61-73` : `MainActivity` `exported="true"` (l. 63), `singleTop`, MAIN/LAUNCHER : n'importe quelle app B la lance sans permission, au moment qu'elle choisit. Aucun contrôle de l'appelant (`getReferrer`, `callingPackage`), aucun `onUserInteraction` (grep).

**2. Le délai se réarme à chaque cycle, sans plafond** : `RelockPolicy.kt:71-72` (retour avant l'échéance : pas de verrou) ; `:57` réarme à chaque `onStop`. Retour à `Locked` seulement par `AppLockManager.kt:195`, appelé seulement par `AppLockLifecycle.kt:46, 56, 82, 89`. `RelockPolicyTest.kt:30-40` (`delay_on_return`) fige ce réarmement comme voulu. Chemin : `MainActivity.kt:99-102` → `AppLockLifecycle.kt:54-57` ; `MainActivity.kt:113-116` → `AppLockLifecycle.kt:81` ; contenu affiché dès que non verrouillé (`MainActivity.kt:231-237`).

**3. Défenses cherchées, aucune efficace**
- Extinction de l'écran : récepteur `SCREEN_OFF` seulement pendant un sélecteur (`AppLockLifecycle.kt:69-74`) ; hors sélecteur `RelockPolicy.kt:85` rend `false`.
- `onNewIntent` : seulement si un passage de sélecteur est en cours (`PickerRelockPolicy.kt:86`).
- Plafond global : aucun (`RelockPolicy` n'a que `leftAt` et `delayMillis`, l. 32-33).
- Arrière-plan du processus : verrouille seulement les coffres (`NotesTechApplication.kt:108-110`).
- Mort du processus : reverrouille (`AppLockManager.kt:150`), mais les relances de B gardent le processus vivant.
- Restrictions de lancement en arrière-plan : plateforme, pas le dépôt ; `minSdk = 24` (`build.gradle.kts:82`) : Android 7-9, B lance librement ; Android 10+, exemption nécessaire (en pratique superposition accordée).

**Attaquant et gain** : B produit les cycles, que le code traite comme des retours de l'utilisateur (`RelockPolicy.kt:72`) ; A (proche, `docs/01-DECISIONS.md:648-650`) lit les notes hors coffre sans PIN au-delà du délai choisi ; B seul ne lit rien.

**FAIBLE** : fermé par défaut (délai « immédiat », `AppLockStore.kt:139-140`, remis à chaque activation `:144-149`, l'allonger exige le PIN `AppLockManager.kt:317-324`) — obstacle, pas réfutation ; coffres reverrouillés à chaque arrière-plan, y compris ceux provoqués par B ; chaque cycle affiche Notes Tech déverrouillée au premier plan (bruyant si le téléphone est utilisé) ; deux acteurs coopérants ; une app B avec superposition a déjà d'autres voies (aucune protection anti-superposition : ni `filterTouchesWhenObscured`, ni `setHideOverlayWindows`).

**Non confirmé** : si Android fait passer `MainActivity` par `onStart` quand elle est lancée derrière un écran de verrouillage sécurisé. Sinon, une veille plus longue que le délai casse la chaîne. « Indéfiniment » confirmé seulement écran allumé, téléphone déverrouillé.
