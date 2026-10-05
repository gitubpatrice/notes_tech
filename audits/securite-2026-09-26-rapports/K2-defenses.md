# K2 — angle DÉFENSES — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26)

VERDICT : VRAI POSITIF. Gravité retenue : FAIBLE, comme annoncé. Aucune garde trouvée qui arrête le chemin.

**Le défaut, confirmé dans le code**
- `security/vault/FolderVaultService.kt:569-589` : `encryptAllNotesInFolder` appelle `lockNote(... plainTitle = "", ...)` (576-580), et rien d'autre : ni `unresolveByMismatch` ni `deleteLinksOf`.
- Les deux autres chemins de scellement appliquent la règle (`NotesRepository.kt`) : édition l. 378 ; déplacement l. 506-507. La règle : `resolveIncoming`, l. 782-788 (`val normalized = if (note.isLocked) "" else …` puis `unresolveByMismatch(noteId = note.id, newTitleNorm = normalized)`).
- Invariant contourné, écrit dans `NoteDao.kt:155-158` : « Résoudre un lien vers une note de coffre inscrirait son identifiant dans `note_links`, d'où une note non protégée afficherait son titre et un lien cliquable vers elle. »

**Défenses cherchées, toutes absentes**
- SQL : `NoteLinkDao.kt:41-49` (`SELECT … FROM note_links WHERE source_id = ? ORDER BY position ASC`), sans jointure ni filtre sur la cible. Les gardes `n.encrypted_content IS NULL` de `backlinks` (l. 83) et `dangling` (l. 121) portent sur la SOURCE.
- Déclencheurs : `UnmanagedSchema.kt:103-124`, trois déclencheurs FTS seulement ; clé étrangère `ON DELETE SET NULL` (l. 58), mais `lockNote` fait un `UPDATE` (`NoteWriteDao.kt:164-175`).
- Après conversion : `VaultViewModel.kt:358-372` ne touche pas aux liens ; `onSessionOpened` (`FolderVaultService.kt:692-695`) reprotège et migre seulement ; `NotesDatabase.kt:108-113` pragmas et boîte de réception.
- Modèle de vue et panneau : `NoteEditorViewModel.kt:245` sans filtre ; `LiensDeLaNote.kt:59-63` s'en remet à la base ; l. 138 `PuceDeNote(titre = lien.targetTitle, onClick = { onOuvrirNote(cible) })`.
- Tests : `NotesRepositoryTest.kt:681-698` (déplacement seulement) ; `FolderVaultServiceTest.kt:174-189` (conversion, ligne de la note seulement) ; `LiensDansUnCoffreTest` (liens internes).
- Atteignabilité : `CONVERT_TO_VAULT` offert sur un dossier existant (`FolderDialogs.kt:40`, `66-68`).

**Attaquant et gain** : A, app ouverte, sans le secret. Note ordinaire contenant `[[Codes]]` → puce résolue au lieu de fantôme → toucher envoie vers la note (`NoteEditorScreen.kt:129-133`), affichée « Note verrouillée » avec feuille de déverrouillage. Il apprend qu'une note de ce titre existe et dans quel coffre. Aucun autre chemin ne le dit (aperçu et puce fantôme via `resolveTitleFrom`, `NotesRepository.kt:194-199`, puis `titlesForLinking` qui écarte les chiffrées, `NoteDao.kt:173` ; autocomplétion `NoteDao.kt:258` ; rétroliens `LinksRepository.kt:61`).

**FAIBLE parce que** : aucun contenu ne sort (`NoteEditorViewModel.kt:796-806`) ; le libellé est le `target_title` de la note ordinaire (déjà visible) ; conditions : lien écrit avant la conversion, conversion d'un dossier (le déplacement est gardé), aucune des deux notes réenregistrée (l'édition répare, `NotesRepository.kt:365`, `378` ; une lecture n'enregistre rien, `NoteEditorViewModel.kt:856`).

**Hors constat** : même absence dans `reprotectPlaintextNotes` (`FolderVaultService.kt:717`) et `migrateLegacyEncryptedNotes` (l. 748). La conversion ne supprime pas non plus les liens SORTANTS de la note scellée : neutralisés pour les autres notes (`NoteLinkDao.kt:83`, `121`), mais ils réapparaissent dans le panneau de la note elle-même quand son coffre est ouvert, ce qui contredit `docs/04-PIEGES.md` §158-159 (« le panneau des liens d'une note de coffre reste vide »). A n'y gagne rien.
