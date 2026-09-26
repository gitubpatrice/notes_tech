# E2 — angle IMPACT — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26)

**VERDICT : VRAI POSITIF. Gravité maintenue à FAIBLE.** Lignes relues, exactes.

**Chemin confirmé** : `MainActivity` exportée, filtre LAUNCHER (`AndroidManifest.xml:62-71`), ne lit aucun intent ; B pilote le cycle de vie, relayé sans vérification d'origine (`MainActivity.kt:99-102`, `:113-116`). `RelockPolicy.kt:72` : échéance consommée à chaque `onStart` ; `:57` réarmée à chaque arrêt (`AppLockLifecycle.kt:81`) : le délai part du dernier départ, de l'utilisateur ou de B. `RelockPolicyTest.kt:43` (« a return consumes the delay ») documente ce choix ; aucun test d'un retour déclenché par une autre app. `lockIfConfigured` seulement depuis `AppLockLifecycle.kt:46, 56, 82, 89` ; `onNewIntent` seulement avec sélecteur (`PickerRelockPolicy.kt:85-90`) ; récepteur d'extinction idem (`AppLockLifecycle.kt:68-80`) ; aucun plafond absolu.

**Gain** : A lit, exporte, modifie les notes hors coffre sans PIN, des heures après le départ réel. L'écran promet le contraire : « Lock after leaving the app — After 5 minutes » (`values/strings.xml:394, 400-402` ; `values-fr/strings.xml:329, 336-339`).

**Pistes de réfutation examinées**
- « Fenêtre déjà accordée » : ne réfute pas ; l'attaque détache l'accès de A du dernier usage réel ; l'accès après l'échéance est le gain.
- « B a déjà plus fort » : non établi. Lancer des activités en arrière-plan : aucune permission en API 24-28 (`minSdk = 24`, `build.gradle.kts:82`). Ce pouvoir seul ne lit pas l'écran (MediaProjection exige l'accord ; sous API 33 `FLAG_SECURE` tant qu'un verrou est configuré, `AppLockRecents.kt:31-41`) ni ne superpose. Avec la superposition (API 29+), l'hameçonnage reste possible (ni `setHideOverlayWindows` ni `filterTouchesWhenObscured`), mais il faut tromper l'utilisateur au bon moment.
- Verrouillage de l'écran du téléphone : ne s'interpose pas dans le modèle (prêt, fouille, `NotesTechApplication.kt:99-103`) ; l'app ne se reverrouille pas à l'extinction hors sélecteur (`AppLockLifecycle.kt:68-80`).
- Coffres : fermés (`NotesTechApplication.kt:105-113` → `VaultSessions.kt:177-182`, à chaque arrière-plan, y compris ceux de B).
- Réglages : A ne peut abaisser aucune protection (désactiver, changer le PIN, allonger le délai, biométrie exigent un `PinProof`, `AppLockManager.kt:285-346`).
- Délai = choix explicite : défaut IMMEDIATELY (`AppLockStore.kt:140`), remis à chaque activation (`:144-149`), allonger exige le PIN (`AppLockManager.kt:316-325`) : obstacle, pas réfutation (promesse affichée rompue).

**FAIBLE parce que** : délai non immédiat choisi derrière le PIN ; B installée, capable de lancer en arrière-plan (toute app en API 24-28 ; superposition ou autre exemption en 29+) ; B tourne sans interruption et relance dans chaque intervalle (15 s à 5 min), Notes Tech apparaît au premier plan à chaque relance (visible écran allumé) ; mort du processus = `Locked` (`AppLockManager.kt:150, 184-187`) ; A de concert avec B (si A a installé B lors d'un accès antérieur, il pouvait installer mieux : gain marginal nul dans cette variante) ; notes hors coffre seulement.

**Non vérifié** : règles de lancement en arrière-plan selon l'API ; cycle de vie d'une activité lancée écran éteint (discrétion). La chaîne interne au dépôt est entièrement confirmée.
