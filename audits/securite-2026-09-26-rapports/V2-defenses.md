# V2 — angle DÉFENSES — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26)

**VERDICT : VRAI POSITIF. Gravité retenue : FAIBLE (inchangée).** Références exactes ; aucune garde qui ferme le chemin dès que le délai n'est pas « immédiat ».

**Défenses cherchées, aucune ne tient**
- Rien n'efface la preuve à l'arrière-plan : `forgetFlow()` n'a que trois appelants, `onSheetDismissed` (AppLockSettingsViewModel.kt:151), `openSheet` (:230), `close` (:303) ; pas d'`onCleared` ; AppLockSettings.kt sans `DisposableEffect` ni observateur de cycle de vie ; le seul `finally` qui efface la preuve (AppLockSettings.kt:110-114) concerne l'enrôlement biométrique.
- La feuille ne se ferme pas sur Accueil : bytecode Material3 1.4.0 lu dans le cache Gradle, `ModalBottomSheetDialogWrapper` ne référence `LifecycleOwner` que pour `ViewTreeLifecycleOwner` ; seuls retour, voile, glissement appellent `onDismissRequest`. Réserve : le BOM 2026.06.00 peut résoudre une autre version.
- `changePin` ne vérifie rien de plus : `isValid` (AppLockManager.kt:376) ; `commitWithProof` (:363-372) refait le même test, sans ré-authentification.
- L'époque ne bouge pas en LOCK_LATER : `lockEpoch` incrémentée seulement en AppLockManager.kt:195 ; `onStop` n'appelle `lockIfConfigured()` que sur LOCK_NOW (AppLockLifecycle.kt:81-83) ; LOCK_LATER note l'heure (RelockPolicy.kt:56-60) ; retour dans le délai ne verrouille pas (RelockPolicy.kt:68-74).
- `onNewIntent` ne verrouille qu'avec un sélecteur en attente (PickerRelockPolicy.kt:85-90).
- Validité 120 s (AppLockManager.kt:472) contre délais 15, 60, 300 s (AppLockStore.kt:33-35).
- D-023 (01-DECISIONS.md:659-660) « un verrouillage l'annule » — ce que le code fait ; rien sur l'arrière-plan sans verrouillage.

**Seul obstacle, pas une réfutation** : délai « immédiat » par défaut (AppLockStore.kt:140), remis à chaque activation (:147) → LOCK_NOW (RelockPolicy.kt:55), époque avance, preuve morte. Les 15 s, 1 min, 5 min sont trois des quatre options offertes (AppLockSettings.kt:126-137) : usage ordinaire.

**Chemin confirmé** : preuve conservée (:241), étape NEW (:262) ; départ par Accueil, extinction, appel (retour, Annuler, glissement effacent la preuve) ; A reprend dans le délai et tape deux fois son PIN (:166-171) ; `changePin` (:279) → `store.replacePin` (AppLockManager.kt:296, AppLockStore.kt:151). Seul le changement de PIN est exploitable : désactivation et allongement du délai consomment la preuve tout de suite (:261, :265) ; la biométrie exigerait celle de A.

**Attaquant et gain** : A, proche qui tient le téléphone déverrouillé (D-023, 01-DECISIONS.md:649-650). Il peut déjà lire, exporter, lancer la panique (SettingsScreen.kt:167, :645-649). Le défaut ajoute un PIN connu de A, contournement durable du verrou : `replacePin` laisse la biométrie (AppLockStore.kt:94, :151), un propriétaire au doigt peut ne jamais s'en apercevoir ; sans biométrie, le propriétaire est enfermé dehors, seule sortie la panique (01-DECISIONS.md:635). Coffres intacts (NotesTechApplication.kt:108-110).

**FAIBLE parce que** : impact limité au verrou d'app ; conditions cumulées (délai non immédiat ; changement de PIN abandonné juste après la saisie de l'ancien ; retour de A dans le délai et dans les 120 s ; A ne peut pas provoquer la situation).

Écart signalé par l'agent : il a extrait `classes.jar` de Material3 1.4.0 dans `scratchpad/m3-1.4.0` (hors dépôt) ; suppression refusée par les permissions, dossier toujours là.
