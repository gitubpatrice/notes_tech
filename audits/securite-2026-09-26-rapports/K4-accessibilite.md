# K4 — angle ACCESSIBILITÉ — verdict : VRAI POSITIF, MOYEN (reçu 2026-09-26)

**VERDICT : VRAI POSITIF. Gravité retenue : MOYEN**, identique à l'annonce. Lignes relues dans ebee629, concordantes.

**Le chemin**
1. Plancher Android 7.0 : `app/build.gradle.kts:82` `minSdk = 24`.
2. Un coffre à code se crée sous l'API 24-27 : aucune garde de version (écran `ui/vault/VaultSheets.kt:207-220` ; `HomeRoute.kt:328-331`, `VaultViewModel.kt:206-207`, `FolderVaultService.kt:145-166` ; aucun `SDK_INT` sur ce chemin). Sous l'API 28, `specFor` saute tout le bloc (`AndroidVaultKeystore.kt:273`) : ni StrongBox ni appareil déverrouillé. `assertHardwareBacked` (`:302-322`) ne refuse que les clés logicielles ; `classerLEchecDeGeneration` (`:359-364`) ne fait que nommer. `KeyGenerationFailureTest.kt:32-35` : « below API 28 the unlocked-device requirement does not exist ».
3. La clé sert téléphone verrouillé : `AndroidVaultKeystore.kt:272` `.setUserAuthenticationRequired(false)` + garde `:273`.
4. La base se lit avec les mêmes droits : KEK sans authentification ni attribut (`KeystoreSealedKekSource.kt:241-251`) ; SQLCipher s'ouvre (`NotesDatabaseFactory.kt:50`) ; sel, nonce, `vault_pin_blob`, `vault_pin_iv` (`FolderDao.kt:112-119`).
5. Oracle hors ligne parfait : Argon2id(code, sel) sans poivre (`VaultCrypto.kt:53-74`), t=2, 32 Mio (`VaultParams.kt:36,39`) ; étiquette GCM avec l'identifiant du dossier en AAD (`FolderVaultService.kt:373-375`, `VaultCrypto.kt:118-133`) ; au plus 1 110 000 codes (`VaultParams.kt:84,87`) ; compteur seulement dans `FolderVaultService.kt:256-269`, `:312-339`, jamais appelé par l'attaquant.

**L'attaquant** : code sous l'UID de l'app sur téléphone saisi verrouillé ; l'app n'ouvre aucune porte (release non débogable, `build.gradle.kts:199` ; `allowBackup="false"`, `AndroidManifest.xml:47` ; seule l'activité de lancement exportée, `:63` ; FileProvider non exporté, `:95`). Il faut un exploit système : obstacle, pas réfutation. Le code nomme cet attaquant : `AndroidVaultKeystore.kt:274-276`, `:280-281` (« let a seized locked phone open the Keystore layer, then search the 4-6 digit PIN offline ») ; D-026 (`docs/01-DECISIONS.md:839-843`) ; l'écran de choix promet « Auto-wipe après 5 échecs. Sécurité device-bound (Keystore) » (`values-fr/strings.xml:250`).

**Le gain** : sans le défaut, seulement les notes hors coffre ; avec, tout le contenu des coffres à code, effacement après 5 échecs contourné.

**MOYEN parce que** : Android 7.0-8.1 seulement (population ancienne et réduite) ; mode code choisi ; Keystore matériel requis (`:318-320`) ; saisie AFU (`LegacyDatabaseLocation.kt:58-59`, pas `directBootAware`) ; exploit système. Pas FAIBLE : tous les coffres à code exposés, un code à 4 chiffres tombe vite.

**Non confirmé par exécution** : que le Keystore sert l'UID écran verrouillé sous l'API 24-27 (comportement documenté ; le code l'admet, `AndroidVaultKeystore.kt:354-355`) ; durée de la recherche (estimation).

**Hors verdict** : même en API 28+, la KEK du portage (`KeystoreSealedKekSource.kt:241-251`) n'a pas l'attribut, alors que la passerelle le pose (`docs/10-PASSERELLE-2.0.4.md:89-90,166-167`) — c'est K3.
