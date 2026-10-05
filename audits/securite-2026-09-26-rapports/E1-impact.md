# E1 — angle IMPACT — verdict : FAUX POSITIF (au plus FAIBLE, « informatif ») (reçu 2026-09-26)

## VERDICT : FAUX POSITIF (angle IMPACT)
Mécanisme réel, mais il n'atteint pas une opération dangereuse, et B n'y gagne rien que sa position ne donne déjà.

**Mécanisme confirmé** : `AndroidManifest.xml:61-73` (exported, l. 63) ; `MainActivity.kt:80-97` ; bytecode Navigation 2.9.8 : `onGraphCreated` → `checkDeepLinkHandled` → `handleDeepLink(Activity.getIntent())`, extras `deepLinkIds`/`deepLinkArgs`/`deepLinkExtras` ; identifiant `createRoute(route).hashCode()` ; `addInDefaultArgs` ne vérifie que le type ; `NoteEditorViewModel.kt:190` lève. B installée, au premier plan, écrit les extras.

**Gain réel**
1. Choisir l'écran et son état initial ne produit aucun effet : éditeur (`NoteEditorViewModel.kt:632-634` → `charger()` en lecture, `:757-829` ; UUID inconnu → `notFound` ; `saveNow` sort sur `courant.note ?: return`, `:848`) ; note de coffre : coffres fermés à l'arrière-plan (`NotesTechApplication.kt:106-109`), secret redemandé (`:796-806`) ; accueil : `home.folderId`, `home.query` (`HomeViewModel.kt:87-88`) → flux de lecture (`:215-223`, `:231-237`), création de note seulement sur geste (`:160-182`) ; purge de la corbeille (`HomeRoute.kt:123`) : a lieu aussi au lancement normal ; recherche (`SearchViewModel.kt:114`) : lecture ; dictée (`VoiceSetupViewModel.kt:79-97`) : lecture ; corbeille, réglages, à propos, mentions : ni `init` ni `SavedStateHandle` ; modèles liés à l'activité (panique, démarrage, verrou) : pas de `SavedStateHandle`. B ne voit pas l'écran.
2. Le verrou passe avant la navigation : `Locked(0)` (`AppLockManager.kt:150`) ; `resolveAtLaunch` avant `setContent` (`MainActivity.kt:87`), déverrouille seulement sans verrou (`:184-187`) ; `NavHost` composé seulement verrou levé (`LockedAppHost.kt:33-37`, `MainActivity.kt:234-237`) ; pendant un délai configuré (`RelockPolicy.kt:50-74`), un lancement par l'icône montre le même contenu.
3. Le plantage frappe l'instance que B vient d'ouvrir, sans travail de l'utilisateur : `onNewIntent` inerte pour la navigation (`MainActivity.kt:118-121`) ; la mort du processus referme les coffres et remet `Locked` ; perte limitée à la sauvegarde différée de 500 ms d'une autre instance (`:838-844`, `:969`), déjà terminée puisque B est au premier plan ; `saveNow` à la fermeture (`NoteEditorScreen.kt:313-315`) ; B obtient déjà une mort de processus (`killBackgroundProcesses` jusqu'à Android 13, pression mémoire) ; dialogue de plantage imitable.
4. Presse-papiers : un plantage empêcherait l'effacement à 60 s (`SensitiveClipboard.kt:103-106`, `:338`), mais B au premier plan lit déjà le presse-papiers.

**Non confirmé** : rien exécuté. Seul reste possible, Android 7-9 (`minSdk = 24`) : lancement d'arrière-plan `NEW_TASK|CLEAR_TASK` tuant une instance au premier plan (< 500 ms de frappe, à l'aveugle, visible) : négligeable.

Si retenu malgré tout : pas plus que FAIBLE ; informatif. Désassemblages dans le scratchpad (`nav-controller-2.9.8.javap.txt`, `nav-controller-impl-2.9.8.javap.txt`, `nav-destination-impl-2.9.8.javap.txt`).
