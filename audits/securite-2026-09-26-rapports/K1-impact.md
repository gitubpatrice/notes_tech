# K1 — angle IMPACT — verdict : VRAI POSITIF, MOYEN (reçu 2026-09-26)

**VERDICT : VRAI POSITIF. Gravité retenue : MOYEN (inchangée).** FTS5 tient ; `note_links` réelle mais mineure, et fausse sur un point : elle ne survit pas à l'auto-effacement.

**Chemin**
1. `FolderVaultService.kt:576-586` → `lockNote` sans purge (idem `:717`, `:748`) ; `UPDATE` `NoteWriteDao.kt:164-175` ; `notes_au` (`UnmanagedSchema.kt:117-119`) avec `old.encrypted_content` encore NULL : le masquage (`:97-98`) laisse passer titre et contenu en clair. Pas d'option `detail=` (`:79-88`) : `detail=full`, positions stockées. FTS5 ajoute des clés de suppression ; les anciennes entrées restent dans `notes_fts_data` jusqu'à une fusion.
2. Aucune atténuation : aucun `optimize`, `rebuild`, `merge`, `secure-delete`, `VACUUM` (grep) ; `applyPragmas` (`NotesDatabase.kt:132-142`) : `synchronous`, `temp_store`, `cache_size`.
3. C dans le périmètre : KEK sans authentification ni déverrouillage (`KeystoreSealedKekSource.kt:243-251`, `:42-45`) ; clé de coffre PIN liée au déverrouillage (`AndroidVaultKeystore.kt:283`, commentaire `:278-282` « a seized locked phone ») ; coffre à phrase « résistante au bruteforce hors-device » (`values-fr/strings.xml:248` ; Argon2id t=3, 64 Mo, `VaultParams.kt:23-26`).

**Gain** : titre et contenu d'avant conversion de chaque note convertie, normalisés mais ordre reconstructible par les positions, sans le secret, là où le coffre est censé tenir. **Survit à l'auto-effacement** : `deletePermanently` (`FolderVaultService.kt:845-847`) → `notes_ad` (`UnmanagedSchema.kt:111-114`) avec valeurs masquées, n'ajoute ni ne retire rien ; or l'écran annonce les notes « définitivement perdues » (`strings.xml:149`). Contredit « une note verrouillée n'expose rien à l'index » (`UnmanagedSchema.kt:90-92`).

**Ce que C avait déjà** : le clair d'avant conversion ne sert à rien à un attaquant qui arrive après. Trames WAL : transitoires (réécrites après chaque remise à zéro ; éditeur sauvegarde toutes les 500 ms, `NotesDatabase.kt:133-136`). Pages libres : non vérifiable ; le résidu FTS est une donnée vivante que ni le réemploi de pages ni `secure_delete` ne touchent. Le coffre ne promet rien sur le passé d'une note convertie (irréversibilité dite seulement à la sortie, `strings.xml:94,159` ; à la conversion, « N note(s) chiffrée(s) », `:253`).

**MOYEN, ni ÉLEVÉ ni CRITIQUE** : attaquant très fort (code sous l'UID : forensique ou root) ; aucun gain pour un coffre PIN si C agit appareil déverrouillé ou en API 24-27 (oracle Keystore `:272`, ≤ 1,11 million de PIN, `VaultParams.kt:36-39,84-87`) ; seules les notes en clair avant l'entrée au coffre (conversion, `moveToFolder` `NotesRepository.kt:486-497` ; une note créée au coffre est scellée avant insertion, `:307-309`) et seulement leur version d'avant ; durée de vie non mesurée, sans borne dans le code.

**`note_links`** : exact (les gestes de masse ne suppriment pas, contrairement à l'édition `NotesRepository.kt:343` ; l'app le qualifie de fuite, `NoteLinkDao.kt:102-104`) ; ne fuit que le texte entre `[[…]]` (≤ 200 caractères) et son décalage (`WikiLinkParser.kt:60,76,81`) ; non affiché (`NoteLinkDao.kt:83,121`) ; supprimé par `ON DELETE CASCADE` (`UnmanagedSchema.kt:57`), clés étrangères attestées par `NoteLinkTest.kt:135-140` (non exécuté) : ne survit ni à l'auto-effacement ni à la suppression.

**Non confirmé** : durée réelle du résidu FTS5 ; `secure_delete` forcé par SQLCipher 4.16.0 ; résultats des tests.
