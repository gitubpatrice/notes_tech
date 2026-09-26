# V1 — angle IMPACT — verdict : VRAI POSITIF, MOYEN (reçu 2026-09-26)

VERDICT : VRAI POSITIF. Gravité retenue : MOYEN, inchangée.

**Raison décisive.** Une fois le coffre fermé, il ne reste qu'une copie du clair accessible par l'interface : l'état de l'éditeur. Rien ne l'efface.
- `NoteEditorViewModel.kt:819-822` pose `title = claire.title` et `content = TextFieldValue(claire.content)`.
- `VaultSessions.kt:179-180` (`sessions.values.forEach { it.key.wipe() }` / `sessions.clear()`) n'efface que les clés.
- `lockedVault` n'est posé qu'à deux endroits : `NoteEditorViewModel.kt:805` (chargement) et `:952` (échec d'écriture).
- `unlockedFolderIds` n'est lu que par `HomeRoute.kt:113/216`, `FoldersDrawerViewModel.kt:101` et `NoteExporter.kt:108`.
- Aucun `LifecycleEventEffect` ni `ON_START` ou `ON_RESUME` dans `ui/`. Seul observateur : `NotesTechApplication.kt:106`.
- Au retour, `NoteEditorScreen.kt:120` recollecte le même état : la condition `:580` est fausse et `:609-641` affichent le texte.

**Attaquant.** A, téléphone déverrouillé, sans le secret du coffre. `NotesTechApplication.kt:101-103` (« quelqu'un qui voit l'appareil déverrouillé ne doit pas trouver un coffre ouvert », geste `:109`) ; `AppLockLifecycle.kt:24-28` (« the vaults' protection must not depend on whether an app lock is configured »).

**Aucune atténuation par défaut.** Verrou d'app désactivé tant qu'aucun PIN (`AppLockStore.kt:125`, `AppLockManager.kt:186`). `SecureWindowGuard` (`NoteEditorScreen.kt:308`) ne masque que récents et captures. `launchMode="singleTop"` ramène la même activité et le même état de navigation.

**Gain réel.** Sans le défaut, A n'obtient rien : rouvrir la note depuis l'accueil lève `VaultSessionClosedException` (`FolderVaultService.kt:518`) et affiche la feuille de déverrouillage (`NoteEditorViewModel.kt:800-806`). Avec le défaut, A lit, fait défiler, prévisualise. L'objection « le propriétaire lisait déjà cette note » ne tient pas : le passage en arrière-plan est la frontière choisie par le code ; cas typique : un tiers qui connaît le code du téléphone mais pas celui du coffre.

**Presse-papiers.** Gain modeste mais réel : captures bloquées, donc copier-coller = moyen pratique de sortir le texte ; marquage sensible (`SensitiveClipboard.kt:269-273`) et effacement à 60 s (`:338`) n'empêchent pas un collage immédiat ; `copierEnMarkdown` (`NoteEditorViewModel.kt:591/596`) n'est pas la seule voie : le menu de sélection du champ permet aussi de copier.

**Ce qui borne l'impact :**
- Combien de notes : celles dont l'éditeur est dans la pile (en général une ; plusieurs par liens `[[…]]`, `NotesTechNavHost.kt:50` empile une entrée par note ; Retour réaffiche chacune).
- Ce qui reste fermé : export (`NoteExporter.kt:191` → `vaults.decrypt`) ; déplacement hors coffre ; une modification déclenche un enregistrement qui échoue et bascule en « verrouillé » (`NoteEditorViewModel.kt:894-897`) ; les autres notes du coffre.
- Combien de temps : aucun minuteur côté éditeur ; jusqu'à destruction de l'activité, mort du processus ou retour arrière. Non mesuré.

**Promesse.** Écrite pour les développeurs seulement : `NotesTechApplication.kt:96-104`, `docs/07-RELECTURES.md:665-666`. Aucune chaîne visible ni texte légal ne promet le verrouillage en arrière-plan ; seul le réglage d'inactivité est affiché (`values-fr/strings.xml:277`). Cela ne retire rien à l'impact : le contrôle existe et il est mis en échec pour les notes ouvertes.

**Pourquoi MOYEN et pas FAIBLE.** Conditions cumulées (téléphone déverrouillé chez A ; propriétaire parti depuis une note de coffre ; processus vivant ; pas de verrou d'app — défaut — ou délai non écoulé), mais exploitation triviale : rouvrir l'app.
