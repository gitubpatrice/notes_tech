# K4 — angle DÉFENSES — verdict : VRAI POSITIF, MOYEN (reçu 2026-09-26)

VERDICT : VRAI POSITIF. Gravité retenue : MOYEN (inchangée). Aucune atténuation sur API 24-27.

**Ce qui décide** : `security/vault/AndroidVaultKeystore.kt:272-285` : `.setUserAuthenticationRequired(false)` puis `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { ... builder.setUnlockedDeviceRequired(true) ... }`. Sous API 28, la clé n'est liée ni à l'état de verrouillage ni à une authentification ; l'authentification (seule alternative en API 23-27 : clé liée au code de l'appareil avec fenêtre de validité) est désactivée sans condition (l. 272). Tout code sous l'UID peut se servir de la clé téléphone verrouillé (AFU).

**Gardes cherchées, aucune ne ferme le chemin**
- Plancher : `build.gradle.kts:82` `minSdk = 24` ; manifeste fusionné `android:minSdkVersion="24"` (l. 8) ; aucun `productFlavors`.
- Refus du mode code sous 28 : absent (`VaultSheets.kt:207-220`, `onChosen(VaultMode.PIN)` l. 220 ; `HomeRoute.kt:328`). Seuls `SDK_INT` du coffre : `AndroidVaultKeystore.kt:250/273/306/360`. `classerLEchecDeGeneration` (359-364) classe ; sous 28 la génération n'échoue pas. `assertHardwareBacked` (302-322) ne refuse qu'une clé logicielle (`isInsideSecureHardware`, l. 313).
- Chiffrement supplémentaire des colonnes : absent. Clé de la base sans authentification ni attribut quelle que soit l'API (`KeystoreSealedKekSource.kt:243-251`, source primaire `DatabaseModule.kt:43`) ; `vault_salt`, `vault_iv`, `vault_pin_blob`, `vault_pin_iv` simples BLOB (`FolderEntity.kt:74-101`).
- Dérivation : Argon2id(code, sel) sans poivre lié au Keystore (`VaultCrypto.kt:53-74`), t=2, 32 Mio (`VaultParams.kt:36-39`), 4-6 chiffres (`:84-87`) ; étiquette GCM = oracle (`VaultCrypto.kt:124-133`).
- Compteur de 5 essais tenu par l'app (`FolderVaultService.kt:256-267`, `:321`) : jamais traversé hors ligne.
- **Commentaire trompeur** : `VaultKeystore.kt:8-11` (« passer par l'API, et il n'a que cinq essais ») est une affirmation, pas une garde ; un seul appel au Keystore rend le scellé interne.

**Attaquant et gain** : C, saisie AFU, code sous l'UID (ou root), Android 7.0-8.1. Position = déjà toute la base hors coffre. Gain en plus : contenu des coffres à code et le code (scellé interne par un appel à `vault_pin_<id>`, puis ≤ 10^6 Argon2id à 32 Mio). Le projet nomme cette menace sans traiter l'API < 28 (`AndroidVaultKeystore.kt:274-282`, D-026 `docs/01-DECISIONS.md:839-845`, `docs/04-PIEGES.md:5131-5137`) : pas un risque accepté.

**MOYEN** : API 24-27 seulement (population réduite gardée volontairement, `build.gradle.kts:79-82`) ; coffre en mode code ; saisie AFU ; exploit ou outil forensique nécessaire (pas de sauvegarde, `allowBackup="false"` l. 47, `fullBackupContent="false"` l. 51 ; pas de `run-as`, `isDebuggable = false`, `build.gradle.kts:199`), plus accessible sur 7.x-8.x sans correctifs.

**Non vérifié** : comportement du Keystore sous API 28 (documenté, reflété par la garde l. 273) ; démarrage effectif de l'app sur ces appareils.
