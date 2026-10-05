# K2 — angle ACCESSIBILITÉ — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26)

**VERDICT : VRAI POSITIF — gravité FAIBLE (inchangée).** Toutes les lignes citées relues, exactes.

**1. L'état périmé est bien écrit.**
- Avant conversion, N (dossier F ordinaire) est une cible légitime ; le lien de X vers N est résolu par `create` → `resolveIncoming` (NotesRepository.kt:311, :785) et `reindexLinks` → `linkTargets` (:740, :770).
- Conversion : `HomeRoute.kt:222` → `VaultViewModel.createPassphraseVault/createPinVault` (:200-209) → `chiffrerLExistant` → `encryptAllNotesInFolder` (:363) → seulement `lockNote(... plainTitle = "" ...)` (FolderVaultService.kt:576-586), sans `deleteLinksOf` ni `unresolveByMismatch`.
- `lockNote` = simple `UPDATE notes` (NoteWriteDao.kt:164-183) ; aucun déclencheur ne touche `note_links` (UnmanagedSchema.kt:103-124 ; schéma hérité LegacyDatabaseFixture.kt:84-138).
- Seul `NoteLinkWriter` réécrit `note_links` ; ses appelants (NotesRepository.kt:343, 421-422, 597, 731-742, 785-787) n'incluent aucune passe au démarrage ni après la conversion (HomeRoute.kt:345-378).
- Même trou sur `reprotectPlaintextNotes` (FolderVaultService.kt:711-727), appelé par `onSessionOpened` (:693), `rattraper` et `removeVaultProtection` (:673) : deuxième route vers le même état.

**2. Rien ne filtre entre la base et l'écran.**
- `outgoing()` : `SELECT … FROM note_links WHERE source_id = ?` (NoteLinkDao.kt:41-49), gardes seulement sur la SOURCE (`backlinks` :83, `dangling` :121).
- `LinksRepository.observeOutgoing` (:34) et `NoteEditorViewModel.liens` (:245-246) transmettent tel quel.
- Affichage dans les deux modes dès que le clavier est baissé (NoteEditorScreen.kt:617, 788-798) : `val cible = lien.targetId; if (cible != null) PuceDeNote(titre = lien.targetTitle, onClick = { onOuvrirNote(cible) })` (LiensDeLaNote.kt:136-138) : puce résolue (icône Notes), pas fantôme (AddLink, :140-160).
- L'ouverture de X ne répare rien : `enregistrer` sort tôt si le texte n'a pas changé (NoteEditorViewModel.kt:856).
- Contraste : l'aperçu est gardé (`resolveTitleFrom` → `linkTargets` exclut les chiffrées, NotesRepository.kt:194-199, NoteDao.kt:173). Asymétrie réelle.

**3. Au clic, l'écran confirme.** `ouvrirUneAutreNote` (NoteEditorScreen.kt:129-134) → NavHost sans garde (NotesTechNavHost.kt:50) → `charger()` → `VaultSessionClosedException` (FolderVaultService.kt:518) → `lockedVault = dossier` (NoteEditorViewModel.kt:805) → « Note verrouillée » (NoteEditorScreen.kt:580-588) et `UnlockVaultSheet(folder = dossier)` (:362-368), qui **nomme le coffre** (`nomDuDossier = folder.displayName()`, VaultSheets.kt:305 et :581).

**Attaquant et gain.** A : téléphone déverrouillé, app ouverte, sessions fermées, sans le secret. Règle du code : une note de coffre n'est jamais une cible de lien (NoteLinkWriter.kt:159-161, NoteDao.kt:155-158). A ne fournit aucune entrée, il lit. Gain borné : que le titre T désigne une note existante et cachée (puce résolue), et dans quel coffre (feuille). Sans le défaut, rien ne le dit (recherche, suggestions, aperçu, `titlesForLinking` excluent les chiffrées).

**FAIBLE parce que** : `[[T]]` écrit depuis une note hors de F avant la conversion ; l'état se répare dès que X est modifiée (`reindexLinks`), ou N modifiée, déplacée ou mise à la corbeille (NotesRepository.kt:378, :612, :422) ; A doit pouvoir ouvrir l'app (verrou d'app à activer, AppLockSettingsViewModel.kt:277) ; fuite limitée à l'existence et à l'emplacement d'une note, pour des titres déjà en clair dans X. Contredit LiensDeLaNote.kt:59 et NoteEditorViewModel.kt:227-231.
