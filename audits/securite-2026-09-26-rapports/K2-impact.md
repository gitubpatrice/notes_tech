# K2 — angle IMPACT — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26)

**VERDICT : VRAI POSITIF. Gravité : FAIBLE, maintenue (bas de l'échelle).**

## Chemin vérifié, rien ne l'arrête
1. `FolderVaultService.kt:569-589` (`encryptAllNotesInFolder`) : seul appel `lockNote(... plainTitle = "", tags = null, updatedAt = null)` (:576-586) ; aucun `resolveIncoming`, `unresolveByMismatch` ni `deleteLinksOf`. `lockNote` = `UPDATE` nu (`NoteWriteDao.kt:164-183`) ; aucun déclencheur sur `note_links` (`UnmanagedSchema.kt:103-124`). Appelé par `VaultViewModel.kt:363` à chaque création de coffre ; `reprotectPlaintextNotes` (:711-727) a le même trou. Les jumeaux détachent : `NotesRepository.kt:378` (édition), :506-507 (déplacement), :612 (sortie de coffre) ; `resolveIncoming` (:783-787) force la clé à `""` pour une note verrouillée.
2. Lecture sans filtre sur la cible : `NoteLinkDao.kt:44-45`, puis `LinksRepository.kt:34`, `NoteEditorViewModel.kt:245` ; seules `backlinks` (:83) et `dangling` (:121) filtrent, sur la SOURCE.
3. Affichage : `LiensDeLaNote.kt:136-138` puce normale cliquable ; sans le défaut, lien fantôme « Lien vers une note inexistante : X » (:140-156, `values-fr/strings.xml:143`).

## Attaquant
A, téléphone déverrouillé, app accessible, sans le secret (`NotesTechApplication.kt:99-103`). A n'écrit rien. Il ne peut pas sonder d'autres titres : un lien écrit aujourd'hui ne se résout jamais vers une note chiffrée (`NotesRepository.kt:740`, :769-770, `NoteDao.kt:170-177`). Seules les lignes antérieures à la conversion fuient.

## Ce que révèle le lien
- Le titre affiché ne vient pas du coffre : `lien.targetTitle` (`LiensDeLaNote.kt:138`) tiré du texte de O (`NotesRepository.kt:734`, :749).
- S'ajoutent un bit (« une note vivante de titre normalisé ≈X existe ») et un lieu (au toucher, l'éditeur de V s'ouvre sur « Note verrouillée » avec la feuille du coffre F, `NoteEditorViewModel.kt:796-806`, `NoteEditorScreen.kt:362-367`, :580-588). Le code correct dirait « note inexistante » : ambiguïté (jamais créée, supprimée, ou au coffre) que le défaut supprime.
- Aucune autre voie ne révèle ce bit : recherche masque les verrouillées (`UnmanagedSchema.kt:97-101`) ; carte en liste sans titre (`NoteCard.kt:76-77`) ; rétroliens non interrogés (`LinksRepository.kt:61`) ; toucher `[[X]]` dans l'aperçu passe par `resolveTitleFrom` qui exclut les notes de coffre (`NotesRepository.kt:194-198`) et crée une nouvelle note X ; `observeDangling` sans appelant ; listes : nombre, date, dossier seulement (`NoteCard.kt:76-80`, :194-199).
- Le clic n'ouvre rien de lisible (coffres refermés à l'arrière-plan, `NotesTechApplication.kt:105-110` ; branche `lockedVault` sans panneau ni infos, `NoteEditorScreen.kt:580-588`, `NoteInfo.kt:31`). Coffre ouvert : aucun gain.
- Issue #10 ne vise pas ce cas au sens strict (liste de titres, `NotesRepository.kt:209-210`). La règle violée est écrite : une note de coffre n'est jamais cible depuis l'extérieur, « a link cannot reveal, from a note that is not protected, that a vault holds a note of that title » (`NotesRepository.kt:170-172`, `NoteDao.kt:155-158`, `NoteEditorViewModel.kt:336-338`, `docs\01-DECISIONS.md:780`).

## Durée : constat incomplet, pas faux
Relire O ne referme rien (`NoteEditorViewModel.kt:856`). Se referme à l'édition de O, ou de V (:378 puis :783), à son déplacement (:507), mise en corbeille (:422) ou suppression (FK `SET NULL`, `UnmanagedSchema.kt:58`).

## FAIBLE parce que
Un bit d'existence et l'identité du coffre, sur un titre déjà en clair dans O ; conditions cumulées (lien écrit avant la conversion depuis un autre dossier ; dossier converti ; ni O ni V modifiés ; téléphone déverrouillé chez A).

## Hors constat
`encryptAllNotesInFolder` ne supprime pas les liens SORTANTS de V (l'édition le fait, `NotesRepository.kt:343`). Coffre fermé : sans effet visible. Coffre ouvert : le panneau de V réapparaît, contrairement à la décision « panneau absent » (`docs\01-DECISIONS.md:785`) ; ne fuit rien.
