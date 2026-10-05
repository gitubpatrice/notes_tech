# V1 — angle ACCESSIBILITÉ — verdict : VRAI POSITIF, MOYEN (reçu 2026-09-26)

**VERDICT : VRAI POSITIF. Gravité retenue : MOYEN**, égale à celle annoncée. Toutes les lignes citées relues dans `ebee629` ; aucune garde entre la fermeture du coffre et l'affichage ou la copie du clair.

**Les préconditions, une à une**

1. **La fermeture du coffre a bien lieu, mais seulement sur les clés.**
   - `NotesTechApplication.kt:108-109` : `override fun onStop(owner: LifecycleOwner) { vaults.lockAll() }`, sans condition.
   - `VaultSessions.kt:177-181` efface les clés et publie `unlockedFolderIds`, rien d'autre.
   - Délai de 700 ms de `ProcessLifecycleOwner` : du framework, non vérifié dans le dépôt ; ne décide de rien.
2. **L'éditeur et son modèle de vue survivent au passage en arrière-plan.**
   - `MainActivity.kt:113-116` : `onStop` n'alimente que le verrou d'app. Aucun `finish`.
   - `AndroidManifest.xml:61-68` : ni `noHistory`, ni `clearTaskOnLaunch`, ni `excludeFromRecents` ; aucun `onUserLeaveHint`.
   - Seul crochet de sortie : `NoteEditorScreen.kt:313-315` `DisposableEffect(Unit) { onDispose { viewModel.saveNow() } }`, qui ne se déclenche pas à `onStop`.
   - Aucun observateur de cycle de vie dans `ui/editor`. `LockedAppHost.kt:31-36` garde le `NavController` au-dessus du verrou ; `:22-23` « the editor with its ViewModel ».
3. **L'écran rouvert est bien l'éditeur, avec son état.** Clair posé en `NoteEditorViewModel.kt:819-826` ; `lockedVault` écrit seulement en `:805` et `:952` ; `unlockedFolderIds` lu seulement par `HomeRoute.kt:113,216`, `FoldersDrawerViewModel.kt:101`, `NoteExporter.kt:108` (grep exhaustif) ; `StartupViewModel` ne repasse pas par `Opening`. Donc `NoteEditorScreen.kt:580` faux, `:631` affiche `value = state.content`.
4. **Aucun verrou d'application dans une installation par défaut.** `AppLockStore.kt:125` ; `AppLockManager.kt:186`, `:194` (`lockIfConfigured()` no-op) ; avec délai, retour dans le délai ne verrouille pas (`RelockPolicy.kt:56-60`, `:72`).
5. **La copie ne demande aucune session.** Menu visible si `note != null && state.lockedVault == null && state.loadError == null` (`NoteEditorScreen.kt:447`) ; `NoteEditorViewModel.kt:591` puis `:596` ; `SensitiveClipboard.kt:89-108` ne vérifie aucune session ; la barre de sélection du `TextField` n'est remplacée nulle part (aucun `TextToolbar`).

**Attaquant et gain** : A, tiers qui tient le téléphone déverrouillé (`NotesTechApplication.kt:99-103`, « le prêt du téléphone, et la fouille »). Le code ne fait pas confiance à A : `FolderVaultService.kt:518` `sessions.sessionKey(note.folderId) ?: throw VaultSessionClosedException(...)` ; toute autre note du coffre renvoie sur la feuille de déverrouillage (`NoteEditorViewModel.kt:797`, `:805`). Gain réel mais borné : titre et contenu de la note ouverte au départ, lisibles et exfiltrables par presse-papiers ou sélection. Chemin : Accueil / changement d'app / écran éteint, puis A rouvre (récents ou lanceur). `FLAG_SECURE` masque la vignette, pas l'écran rouvert.

**Ce qui borne ou ferme le chemin (MOYEN)** :
- Un verrou d'app « immédiat » ferme le chemin (délai par défaut une fois activé, `AppLockStore.kt:139-140`, `:144-149` ; décision `RelockPolicy.kt:55` ; `LockedAppHost.kt:33-34` retire le contenu).
- Le propriétaire doit être parti sans Retour (Retour dépile l'éditeur, `NoteEditorScreen.kt:320-324`).
- Activité ou processus détruit entre-temps → rechargement → demande du secret (`:800-806`) ; A ne peut pas provoquer ce cas.
- Une seule note exposée, celle à l'écran.

Variante hors vote : l'auto-verrouillage par inactivité, app au premier plan, laisse lui aussi le clair affiché.
