# K3 — angle ACCESSIBILITÉ — verdict : VRAI POSITIF, MOYEN (reçu 2026-09-26)

VERDICT : VRAI POSITIF. Gravité retenue : MOYEN, à sa borne basse. Toutes les lignes citées relues et exactes.

**Raison décisive** : `security\kek\KeystoreSealedKekSource.kt:243-251` : `setBlockModes(GCM)`, `setEncryptionPaddings(NONE)`, `setKeySize(256)`, `setRandomizedEncryptionRequired(true)`, `.build()` ; ni `setUnlockedDeviceRequired` ni `setUserAuthenticationRequired`. L'absence de la seconde est assumée (KDoc :42-45) ; la première n'est pas justifiée. La même app pose l'attribut sur ses clés de coffre (`AndroidVaultKeystore.kt:283`) et le justifie contre cet attaquant (:278-282, `docs\01-DECISIONS.md:839-845`).

**Attaquant et gain** : saisie AFU + code sous l'UID de l'app ; il se sert lui-même de la clé Keystore. Il déchiffre `db_kek_v1.blob` / `.nonce` (`notes_tech.kek`, :161-164 ; alias :280) et obtient la clé SQLCipher de toute la base (`NotesDatabaseFactory.kt:50-54`).

**Accessibilité : obstacle réel, pas une réfutation** : l'app ne fournit aucun moyen d'obtenir cette position (seule `MainActivity` exportée ; release non débogable, `build.gradle.kts:199` ; `allowBackup="false"`). Il faut une faille du système — exactement l'attaquant du modèle de menace du projet (`01-DECISIONS.md:841-842`). Aucune ligne ne ferme le chemin : recherche négative sur `createDeviceProtectedStorageContext`, `directBootAware`, `isDeviceLocked`, `isKeyguardLocked`.

**Les chemins**
1. Installation neuve : `KekRepository.kt:63-69` → `:339` → `KeystoreSealedKekSource.kt:192-196` (supprime l'alias) → `:200` → `:147` → `:241-253`.
2. Migration avant 2.0.4 : `KekRepository.kt:156` → `:227` → même `createKey` ; copie `flutter_secure_storage` en place.
3. Migration depuis 2.0.4+ : copie détruite seulement par `KekRepository.destroy()` (:256-268), seul appelant `PanicService.kt:386` ; source branchée en permanence (`DatabaseModule.kt:69`) ; clé RSA sans l'attribut d'après `FlutterSecureStorageFixture.kt:204-215` (transcription). **Aggravation non mentionnée** : si Android supprime la clé du pont au retrait du verrouillage (mesuré API 34, `docs\04-PIEGES.md:5131-5133`), `load()` rend `null` (`:62`), la copie répond, la promotion (`KekRepository.kt:156`) recrée la clé primaire par `createKey`, donc SANS l'attribut.
4. `docs\10-PASSERELLE-2.0.4.md:169-170` est faux : sur `SourceUnavailable` primaire, le parcours continue vers la copie (`KekRepository.kt:118-121`, `:189-194`).

**La clé de la passerelle porte-t-elle l'attribut ?** D'après la copie figée : oui à partir de l'API 28 (`docs\10:88-91`, `:166-167` ; `docs\02-SCHEMA-HERITE.md:216` ; `PariteKeystoreAvecFlutterTest.kt:227`). Non vérifié sur le code (hors copie figée) — [vérifié par moi dans `notes_tech` v2.0.9 : `KeystoreBridge.kt:241-242`]. Aucun test de la copie ne vérifie cet attribut : l'aide « comme la passerelle » de `KeystoreSealedKekSourceTest.kt:247-260` crée la clé par `store()`, donc SANS l'attribut. API 24-27 : l'attribut n'existe pas. La 3.0.0 garde la clé du pont tant qu'elle existe (`:147`), la remplace sans attribut dans le cas 3.

**MOYEN parce que** : saisie AFU + faille du système ; processus vivant = clé brute déjà en mémoire (`NotesDatabaseFactory.kt:79`, fermée seulement en panique ou test, `DatabaseProvider.kt:101-104`, `:130-133`) ; clés de coffre avec l'attribut ; pas une régression. Pas FAIBLE : toute la base hors coffres exposée.
