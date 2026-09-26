# P3 — vérificateur unique, trois angles (Sonnet) — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26 au soir)

**Gravité** FAIBLE. Confiance élevée sur FTS5 et les déclencheurs ; modérée sur le réglage SQLCipher.

**Accessibilité** : cinq gestes ordinaires — corbeille vidée (NoteWriteDao.kt:269-270), suppression
définitive (:241-242), purge à 30 j (:250-251), dossier supprimé (FolderDao.kt:233-234, cascade
NoteEntity.kt:28-33), coffre à code effacé (FolderVaultService.kt:831-859, `clearVault` FolderDao.kt:189-199)
— tous par un `DELETE FROM notes` → `notes_ad` (UnmanagedSchema.kt:111-115), marqueur `'delete'` seulement.
Aucun `optimize`/`merge`/`rebuild` FTS5 ni `VACUUM` dans le code.

**Impact** : le détenteur de la clé a déjà les notes vivantes ; gain différentiel = termes et positions de
notes explicitement détruites, dans les segments FTS5 non fusionnés. Même logique que K1, plus large.

**Défenses** : pas de `PRAGMA secure_delete` (NotesDatabase.kt:132-142). Le binaire ne montre que le nom
du pragma — valeur par défaut compilée non confirmée (la cellule 3 la déduisait absente). Même activé, il
ne touche que les pages libérées, pas les segments FTS5 que seul un `merge`/`optimize` ou l'option
`secure-delete` de FTS5 réécrit.

**Sous-point `deleteKey`** : confirmé. `FolderVaultService.kt:835` jette le résultat ; `resumePendingWipes`
(:876-887) ne rejoue que si le journal reste posé, or il est effacé (:855) sur `restantes == 0` seul : un
échec de `deleteKey` échappe définitivement à la reprise. Rare (échec Keystore), combinatoire.
