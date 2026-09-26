# V2 — angle IMPACT — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26)

**VERDICT : VRAI POSITIF. Gravité retenue : FAIBLE**, la même que celle annoncée (pas de cran plus bas).

**1. Chemin confirmé ligne à ligne.**
- Preuve gardée dans le ViewModel : `AppLockSettingsViewModel.kt:114` et `:241`, étape NEW `:262`.
- `forgetFlow()` (`:315-320`) appelée seulement par `openSheet` (`:230`), `close` (`:303`), `onSheetDismissed` (`:151`) ; aucun observateur de cycle de vie.
- Délai différé : `onStop` ne verrouille pas, seul `LOCK_NOW` verrouille (`AppLockLifecycle.kt:81-83`, `RelockPolicy.kt:56-59`) ; retour avant la fin du délai : `onStart` ne verrouille pas (`RelockPolicy.kt:72`). L'époque ne bouge pas.
- `isValid` (`AppLockManager.kt:374-377`) : non utilisée, même époque, moins de 2 min. Rien ne dit **qui** a prouvé. Suite `:287`, puis `:296` `store.replacePin(stored)`.

**2. L'attaquant.** A, tiers qui tient le téléphone déverrouillé — celui que vise D-023 (« un proche qui connaît le code du téléphone est exactement ce contre quoi un verrou de notes protège », `docs/01-DECISIONS.md:649-650`). Entrée : le nouveau PIN tapé aux étapes NEW et CONFIRM (`AppLockSettingsViewModel.kt:166-171`).

**3. Ce que A avait déjà sans preuve pendant la fenêtre (pas un gain)** : lecture des notes hors coffre ; export et partage (`SettingsScreen.kt:581-594` → `ExportViewModel.kt:48-53`) ; panique (`SettingsScreen.kt:645-660` → `PanicViewModel.kt:47`, mot de confirmation affiché, `PanicScreens.kt:123`, `:129`). « Propriétaire évincé » n'ajoute rien, la panique fait plus. Coffres protégés (verrouillés à l'arrière-plan, `NotesTechApplication.kt:108-110` ; export saute les notes scellées, `NoteExporter.kt:289`).

**4. Le gain réel.**
- Sans preuve, A ne peut ni désactiver le verrou, ni allonger le délai, ni activer la biométrie ; ouvrir une autre feuille efface la preuve (`:229-231`). Enrôler son empreinte ne donne rien (`BiometricUnlockKey.kt:84`). Aucune autre surface (une activité exportée, `allowBackup="false"`).
- Le PIN choisi est un **secret durable** : A rouvre l'app à volonté après reverrouillage (`AppLockManager.kt:204-248`) et débloque tous les changements soumis à preuve.
- **Avec biométrie active**, le propriétaire ne peut pas le révoquer sans tout effacer : le changement de PIN laisse la biométrie (`AppLockStore.kt:94`, `:151`) ; le déverrouillage biométrique ne délivre aucune preuve (`AppLockManager.kt:255-259`) ; tout changement exige une preuve, donc le PIN de A. Le propriétaire continue avec son empreinte sans rien remarquer ; A garde un accès discret aux notes hors coffre **écrites après**.
- **Sans biométrie**, gain quasi nul : au verrouillage suivant le propriétaire est bloqué et ne peut qu'effacer (`AppLockScreen.kt:242-266`). Exception : A désactive le verrou avec son PIN et le propriétaire ne remarque pas.

**5. FAIBLE parce que** : impact limité aux notes hors coffre futures, surtout avec biométrie ; exploitation exigeante : délai par défaut IMMEDIATELY (`AppLockStore.kt:139-140`, repli `:47-48`) qui ferme le chemin (mais réglage offert : obstacle, pas réfutation) ; le propriétaire doit abandonner le changement après avoir prouvé l'actuel ; A doit agir dans les 2 min (`AppLockManager.kt:472`) et avant la fin du délai ; le processus doit survivre (preuve en mémoire seulement, `AppLockSettingsViewModel.kt:113-114`).

Non confirmé (lecture seule) : que la feuille réapparaît bien à l'étape NEW au retour ; le code le soutient (`AppLockSettings.kt:140-142`, fermeture seulement sur geste `:251`, `:307`).

Hors constat : sans arrière-plan, même complétion possible si le propriétaire s'éloigne écran allumé dans les 2 min ; l'arrière-plan ne fait que prolonger la fenêtre de la durée du délai.
