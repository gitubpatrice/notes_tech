# V1 — angle DÉFENSES — verdict : VRAI POSITIF, MOYEN (reçu 2026-09-26)

**Verdict : VRAI POSITIF. Gravité retenue : MOYEN**, égale à celle annoncée.

Je n'ai trouvé aucune défense sur le chemin. Toutes les lignes citées par le chercheur se vérifient dans la copie figée ebee629.

## Le chemin, relu dans le code

Racine du code : `...\audit-ebee629\app\src\main\java\com\filestech\notes_tech\`

**1. Le clair entre dans l'état de l'éditeur.**
- `ui/editor/NoteEditorViewModel.kt:797` appelle `vaults.decrypt(note)`.
- Les lignes 819-827 écrivent le clair dans l'état : `title = claire.title`, `content = TextFieldValue(claire.content)`.
- `charger()` n'est appelé que depuis `init` (632-634) et `retryAfterUnlock` (726-729).
- Le ViewModel n'a ni `onCleared` ni aucun abonnement à l'état des coffres.

**2. Fermer le coffre n'efface que les clés.**
- `NotesTechApplication.kt:108-110` : `onStop` appelle `vaults.lockAll()`.
- `security/vault/VaultSessions.kt:177-182` : `lockAll()` efface les clés, vide la table et publie les identifiants. Rien d'autre.

**3. Rien ne fait suivre la fermeture jusqu'à l'éditeur.** Recherche dans tout `app/src/main` :
- `unlockedFolderIds` n'est lu que par `FoldersDrawerViewModel.kt:101`, `NoteExporter.kt:108` et `HomeRoute.kt:113/216`. Ces deux dernières lignes ne servent qu'aux gestes sur les dossiers, et l'accueil n'est pas composé quand l'éditeur est au-dessus.
- `lockedVault` n'est posé qu'en trois endroits : `NoteEditorViewModel.kt:805` (chargement), `:952` (`signalerLaPerte`, uniquement sur un échec d'écriture) et `:727` (remis à nul).
- Aucun observateur de cycle de vie dans l'interface : `ProcessLifecycleOwner`, `LifecycleEventObserver`, `ON_STOP`, `LifecycleStartEffect` et `repeatOnLifecycle` n'apparaissent que dans `NotesTechApplication`.
- Aucun `finish`, `recreate`, `popUpTo` ni `killProcess` sur le chemin de l'arrière-plan (seulement panique `PanicScreens.kt:408-409` et changement de langue `SettingsScreen.kt:161`).

**4. Au retour, l'écran réaffiche le même état.**
- `ui/editor/NoteEditorScreen.kt:120` recollecte le même `StateFlow`.
- Ligne 580 : `lockedVault` est nul, donc la branche `else` s'applique (609-631), et la ligne 631 affiche `value = state.content`. L'aperçu (614) lit le même texte.
- Copie : `NoteEditorViewModel.kt:591` lit `_state.value.content.text`, puis `:596` appelle `clipboard.copier(texte)`. Aucune vérification de session entre les deux.

## Défenses examinées, aucune ne coupe le chemin

- **`FLAG_SECURE`** (`NoteEditorScreen.kt:308`, `SecureWindowGuard(active = state.isVaultNote)`) : bloque capture et vignette des récents ; n'empêche ni la lecture de l'écran ni la copie.
- **Verrou de l'application** : non configuré par défaut (`AppLockStore.kt:125`, `AppLockManager.kt:184-187` ; `MainActivity.kt:235` pose `locked = false`). Même configuré, `LockedAppHost.kt:20-23` rend l'éditeur « with its text, and the editor with its ViewModel » une fois le code saisi ; un délai de reverrouillage laisse une fenêtre sans code. `AppLockLifecycle.kt:24-28` : la protection des coffres « must not depend on whether an app lock is configured ».
- **Enregistrement au départ** (`NoteEditorScreen.kt:313-315`) : seulement si l'éditeur quitte la composition, ce qui n'arrive pas en arrière-plan sans verrou d'application.
- **Tâche et activité** : `singleTop`, `taskAffinity=""`, sans `clearTaskOnLaunch`, `finishOnTaskLaunch` ni `noHistory` ; une seule activité, le retour reprend la même pile et ses ViewModel.
- **Démarrage** : `StartupViewModel` ne repasse jamais à `Opening` au retour ; `DatabaseProvider.close()` sans appelant en arrière-plan.
- **Documentation** : rien dans `docs/04-PIEGES.md` ni `docs/05-PARITE.md` ne décrit de purge de l'éditeur ; « coffre refermé pendant la frappe » = seul cas `lostToVaultLock` (écriture ratée).
- **Seule défense réelle, hors cas par défaut** : processus tué → `charger()` échoue avec `VaultSessionClosedException`, l'écran redemande le secret (`NoteEditorViewModel.kt:800-806`).

## Attaquant et gain

- A, téléphone déverrouillé, sans le secret. `NotesTechApplication.kt:99-103` : le verrouillage au passage en arrière-plan couvre « le prêt du téléphone, et la fouille — quelqu'un qui voit l'appareil déverrouillé ne doit pas trouver un coffre ouvert ».
- Entrée : ramener l'app au premier plan (lanceur ou récents).
- Gain : titre et contenu en clair, lisibles et copiables ; aucun autre chemin sans le secret (export exige une session, `NoteEditorViewModel.kt:553-559` ; déplacement par `relocateLockedNote` ; une note liée rouvre un éditeur qui redemande le secret).

## Ce qui borne la gravité à MOYEN

- Portée : les notes dont l'éditeur est dans la pile au départ (la note lue, celles ouvertes par lien depuis elle).
- Le propriétaire doit avoir quitté l'app sur l'éditeur d'une note de coffre ; revenir à l'accueil détruit le ViewModel.
- Le processus doit avoir survécu.
- Aucune difficulté technique pour A ; installation par défaut sans verrou d'app.

Seconde voie non revendiquée : l'auto-verrouillage après 15 min d'inactivité (`VaultParams.kt:81`, `VaultAutoLocker.kt:54`) passe par la même fermeture de session, que l'éditeur n'observe pas davantage.
