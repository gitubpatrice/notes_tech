# K1 — angle DÉFENSES — verdict : VRAI POSITIF, MOYEN (reçu 2026-09-26)

**VERDICT : VRAI POSITIF. Gravité retenue : MOYEN (inchangée).** Aucune garde entre la mise au coffre et la persistance du clair, ni dans FTS5, ni dans `note_links`.

**Chemin, relu ligne à ligne**
- Déclenchement par défaut : tout dossier ordinaire propose « Enable vault » (`ui/folders/FolderDialogs.kt:62-68`) → `VaultViewModel.kt:200-202` → `chiffrerLExistant` → `encryptAllNotesInFolder` (`VaultViewModel.kt:363`).
- Sélection : `FolderVaultService.kt:572` prend toutes les notes en clair du dossier, corbeille comprise (`NoteDao.kt:211-217`) ; boucle 575-586 = seulement `lockNote`, simple `UPDATE` (`NoteWriteDao.kt:164-175`).
- FTS5 : `notes_au` (`UnmanagedSchema.kt:117-119`) passe `('delete', old.rowid, …)` avec `masked()` (97-98) ; l'ancienne ligne a `encrypted_content IS NULL`, donc les vrais jetons partent dans la commande `delete` — correct pour un index à contenu externe, mais sans `secure-delete` FTS5 n'efface rien : il ajoute des clés de suppression ; les anciennes entrées restent dans `notes_fts_data` jusqu'à une fusion.
- Options : `UnmanagedSchema.kt:79-88`, ni `detail=` (positions complètes, texte reconstructible), ni `secure-delete`.

**Défenses cherchées, toutes absentes**
- Aucune purge : aucun `optimize`, `merge`, `rebuild`, `secure-delete`, `automerge`, `VACUUM` ni `secure_delete` dans `app/src/main` (seules occurrences : whisper.cpp).
- Ouverture : `onOpen` ne pose que `synchronous`, `temp_store`, `cache_size` (`NotesDatabase.kt:132-142`) ; hook SQLCipher : `cipher_compatibility` seulement (`NotesDatabaseFactory.kt:128-134`).
- Déverrouillage : `onSessionOpened` (`FolderVaultService.kt:692-695`) → `reprotectPlaintextNotes`, `migrateLegacyEncryptedNotes` ; aucune réindexation FTS, aucun nettoyage des liens.
- `note_links` : aucun déclencheur ; FK seulement au `DELETE` (`UnmanagedSchema.kt:57-58`) ; ni `encryptAllNotesInFolder` (576) ni `reprotectPlaintextNotes` (717) n'appellent `deleteLinksOf`.
- Jumeaux protégés : `saveEdits` (`NotesRepository.kt:343`), `moveToFolder` → `reindexLinks` (506), `relocateLockedNote` → `deleteLinksOf` (597). Jumeau asymétrique.
- **Écart avec la version publiée** : `docs/05-PARITE.md:562` indique que l'app Flutter purge les liens au verrouillage.
- Gardes de lecture, pas de stockage : `NoteLinkDao.kt:83`, `:121` ; panneau absent pour une note verrouillée (`NoteEditorScreen.kt:580-588`).

**Attaquant et gain**
- C détient la base et sa clé SQLCipher ; avec `allowBackup="false"` (`AndroidManifest.xml:47-49`), il faut une compromission (root ou code sous l'UID).
- C est dans le modèle de menace du coffre : `res/values/strings.xml:295` « resistant to off-device bruteforce » ; `docs/11-COFFRES.md:10` clé « que l'utilisateur seul peut faire réapparaître ».
- Gain sans la phrase secrète : jetons (titre et contenu) des notes converties tant que les segments n'ont pas fusionné ; titres `[[…]]` tant que la note n'est ni modifiée ni mise à la corbeille.
- Dans les deux sens (déduit, non tracé ligne à ligne) : pas de `resolveIncoming` à la conversion, les liens entrants gardent `target_id` sur la note du coffre, ce qui associe son identifiant à son titre, pourtant chiffré au format 2.

**Ce qui borne** : clé de la base nécessaire ; seules les notes déjà en clair sont concernées (un attaquant présent avant la conversion avait déjà leur contenu ; l'app reconnaît ce type d'exposition sur le chemin inverse seulement, `strings.xml:107`, `:113`, `:174`, rien à la conversion) ; durée FTS inconnue ; résidu `note_links` réduit aux titres `[[…]]`.

**Réserves sur le constat**
- `:748` : `migrateLegacyEncryptedNotes` porte sur des notes déjà verrouillées (colonnes masquées à `''`, `UnmanagedSchema.kt:97-98`) : pas de résidu FTS nouveau.
- Résidu FTS plus large que la conversion : tout passage du clair au coffre, dont `moveToFolder` vers un coffre (`NotesRepository.kt:487`).
- Non vérifié : une configuration `secure-delete` persistée dans les bases Flutter (`docs/02-SCHEMA-HERITE.md:150-163` n'en décrit aucune).
