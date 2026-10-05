# V2 — angle ACCESSIBILITÉ — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26)

**VERDICT : VRAI POSITIF. Gravité retenue : FAIBLE** (maintenue).

**Raison décisive** : seule garde `security\applock\AppLockManager.kt:376` (`!proof.isUsed && proof.epoch == lockEpoch.get() && age in 0..PROOF_VALIDITY_MILLIS`) ; époque changée seulement en `:195`, atteinte depuis `AppLockLifecycle.kt:81-82` sur `LOCK_NOW` seulement ; preuve oubliée seulement par `forgetFlow` (`AppLockSettingsViewModel.kt:315-320`), qu'aucun rappel de cycle de vie n'appelle.

**Les cinq vérifications passent (configuration avec délai)**
1. Feuille et modèle de vue survivent : modèle de vue de la destination Réglages (`AppLockSettings.kt:76`, `SettingsScreen.kt:121-123`, `NotesTechNavHost.kt:69-70`) ; preuve `AppLockSettingsViewModel.kt:114`, feuille dans `local` (93, 101) ; contenu composé tant que l'app n'est pas verrouillée (`LockedAppHost.kt:33-37`) ; `onDismiss` seulement par Annuler, Retour, voile (`AppLockSettings.kt:251`, `305-308`) ; ni `noHistory` ni `finishOnTaskLaunch` (`AndroidManifest.xml:61-68`) ; Material3 1.4.0 : chaînes de `ModalBottomSheetDialogWrapper` seulement (rappel Retour, `onTouchEvent`, rien d'un arrêt).
2. Époque inchangée : arrêt avec délai → `LOCK_LATER` (`RelockPolicy.kt:56-59`) ; retour dans le délai : rien (`RelockPolicy.kt:72`, `AppLockLifecycle.kt:56`) ; `onNewIntent` seulement avec sélecteur (`PickerRelockPolicy.kt:85-90`), idem écran éteint (`AppLockLifecycle.kt:68-80`) ; arrêt du processus = coffres seulement (`NotesTechApplication.kt:108-110`).
3. Preuve valide : non consommée au passage CURRENT → NEW (`:262`), seulement après écriture (`AppLockManager.kt:368`) ; 120 s (`:472`) sur `elapsedRealtime` (`DatabaseModule.kt:109`).
4. Aucun autre chemin ne l'oublie : `forgetFlow` depuis 151, 230, 303 seulement ; `abandonEnrolment` (224) = biométrie.
5. A peut saisir et confirmer : NEW → CONFIRM → `saveNewPin` (166-171) sans compteur ; `changePin` → `commitWithProof { store.replacePin }` (`AppLockManager.kt:287-296`) → `AppLockStore.kt:151`.

**Attaquant et gain** : A, téléphone déverrouillé (après extinction, il doit connaître le code de l'appareil) ; confiance en `AppLockManager.kt:376` sur une preuve écrite par le propriétaire (`:246`). Déjà acquis dans le délai : lecture et export (`SettingsScreen.kt:497-560`), panique par mot-clé (`PanicViewModel.kt:47-55`, `PanicScreens.kt:76-81`). Coffres hors d'atteinte. **Seul gain réel : accès durable** par un PIN connu de A, discret tant que le propriétaire déverrouille à l'empreinte (`replacePin` laisse la biométrie, `AppLockStore.kt:94`, `151` ; A ne peut pas ajouter son empreinte, `BiometricUnlockKey.kt:84`).

**FAIBLE parce que**
- Valeur par défaut qui ferme le chemin : `IMMEDIATELY` (`AppLockStore.kt:140`), remis par `enable()` (`:144-148`) ; allonger le délai exige le PIN (`AppLockManager.kt:316-326`) ; avec `IMMEDIATELY`, tout arrêt verrouille (`RelockPolicy.kt:55`). Lue strictement, la règle « une valeur par défaut qui ferme le chemin réfute » donnerait FAUX POSITIF ; traité comme obstacle (option offerte, trois valeurs sur quatre ouvrent le chemin). **À trancher par l'orchestrateur** → tranché : obstacle (même lecture que les deux autres vérificateurs).
- A ne peut ni créer ni provoquer la preuve (propriétaire parti en plein changement, sans Annuler, Retour ni voile).
- Fenêtre étroite : < 120 s après la vérification et dans le délai (15 s, 60 s, 5 min).
- Activité détruite en arrière-plan : preuve perdue.

Non confirmé : comportement de la fenêtre de dialogue à l'arrêt (chaînes du bytecode). L'agent a extrait `classes.jar` dans `scratchpad\m3\` (hors dépôt).
