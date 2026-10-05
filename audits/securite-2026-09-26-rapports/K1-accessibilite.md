# K1 — angle ACCESSIBILITÉ — verdict : VRAI POSITIF, MOYEN (FTS5) ; note_links seule = FAIBLE (reçu 2026-09-26)

**VERDICT : VRAI POSITIF. Gravité retenue : MOYEN** pour la partie FTS5. La partie note_links, seule, ne vaudrait que FAIBLE. Lu dans ebee629, rien exécuté.

## 1. Le résidu FTS5 existe
- `VaultViewModel.kt:363` → `encryptAllNotesInFolder` ; `FolderVaultService.kt:572-587` scelle puis `lockNote` ; `UPDATE` (`NoteWriteDao.kt:164-175`, `content = ''` `:167`) → `notes_au` (`UnmanagedSchema.kt:117-122`) : `INSERT INTO notes_fts(notes_fts, rowid, title, content, tags) VALUES ('delete', old.rowid, ${ftsRow("old")})`. Pour une note en clair, `old.encrypted_content IS NULL` : le `'delete'` porte les jetons clairs ; la nouvelle ligne n'insère que `''`.
- Aucune défense : table FTS5 (`UnmanagedSchema.kt:79-88`) sans `detail=` (donc `full`) ni `secure-delete` ; fixture Flutter (`LegacyDatabaseFixture.kt:74-81`) idem ; aucun `optimize`, `merge`, `rebuild`, `secure-delete`, `automerge`, `VACUUM` dans `app/src/main/java`. Un `VACUUM` ne purgerait pas ce résidu logique.
- Non observé : qu'un `'delete'` n'écrive que des clés de suppression et que les entrées restent jusqu'à une fusion vient de la documentation SQLite (option `secure-delete`, 3.42+). Durée de vie non mesurée (fusions automatiques).
- Autres chemins au même résidu : `NotesRepository.kt:486-497` (`moveToFolder` vers un coffre) ; `FolderVaultService.kt:713-725` (`reprotectPlaintextNotes`).
- Pas de résidu pour une note créée dans un coffre (scellée avant insertion, `NotesRepository.kt:307-309`).
- Le résidu survit à l'auto-effacement : `FolderVaultService.kt:846` supprime la note → `notes_ad` (`UnmanagedSchema.kt:111-114`) avec `old` masqué à `''` : n'ajoute ni ne purge rien.

## 2. Le résidu note_links existe, plus borné qu'annoncé
- La conversion ne nettoie pas (`FolderVaultService.kt:569-589`, rien sur `linkWriter`) ; l'appelant `VaultViewModel.kt:358-372` ne compense pas. Nettoient : édition (`NotesRepository.kt:343`), corbeille (`:421`), déplacement d'une note verrouillée (`:597`, `:604`), `reindexLinks` (`:730-731`).
- Le code tient ces lignes pour une fuite (`NoteLinkDao.kt:102-106`) ; `NoteEditorViewModel.kt:227-229` affirme qu'elles sont supprimées au scellement : faux sur ce chemin.
- Borné : seuls les textes `[[…]]` et leurs positions ; lisibles seulement avec la clé de la base (lectures gardées `NoteLinkDao.kt:83`, `:121` ; panneau absent coffre fermé, `NoteEditorScreen.kt:580-588`) ; disparaissent à la première édition (`:343`) et à la suppression de la note (`ON DELETE CASCADE`, `UnmanagedSchema.kt:57` ; suppose les clés étrangères actives, déclarées `NoteEntity.kt:27-34`, code généré non vérifié).
- **« Survit à l'auto-effacement » est faux pour note_links**, vrai pour FTS5 seulement.

## 3. Attaquant et gain
- La clé de base est utilisable sans authentification ni appareil déverrouillé (`KeystoreSealedKekSource.kt:241-253`, choix assumé `:40-45`). Il faut du code sous l'UID ou root (forensique AFU, exploit) ; sauvegarde fermée (`AndroidManifest.xml:47-48`) ; release non débogable (`build.gradle.kts:199`). Le code range cet attaquant dans son modèle (`AndroidVaultKeystore.kt:274-283`).
- Sans résidu, cet attaquant n'obtient rien des coffres (code : clé inutilisable téléphone verrouillé, `AndroidVaultKeystore.kt:283` ; phrase secrète : Argon2id 64 Mo, `VaultParams.kt:23-26`). Avec : titre et contenu des notes converties ou déplacées, tokenisés (minuscules, sans accents, positions : texte reconstructible). Étiquettes déjà en clair (`NoteWriteDao.kt:118-120`).
- Réserve : appareil déverrouillé + coffre à code, l'attaquant pouvait déjà chercher le code hors ligne (≤ 10^6 essais).

## 4. MOYEN et pas plus : code sous l'UID ou root ; durée non mesurée ; notes en clair avant l'entrée au coffre seulement ; texte normalisé.

## 5. À côté
- Liens entrants (= K2).
- **Correctif : ne pas utiliser `rebuild`** : sur une table à contenu externe il relit les colonnes brutes et indexerait les titres au format 1 et les étiquettes des notes verrouillées (masquage dans les déclencheurs seulement). `secure-delete` est la bonne piste, mais rend la table illisible par FTS5 < 3.42, ce qui touche le retour vers Flutter (`NotesDatabase.kt:43-48`).
- Non vérifié : `PRAGMA secure_delete` non posé (`NotesDatabase.kt:132-142`) ; l'ancien `content` pourrait subsister dans les pages de `notes` selon la configuration de SQLCipher.
