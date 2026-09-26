# K5 — angle DÉFENSES — verdict : VRAI POSITIF, FAIBLE (reçu 2026-09-26)

**VERDICT : VRAI POSITIF. Gravité retenue : FAIBLE, inchangée.** Les cinq pièces citées relues, exactes.

**Raison décisive** : `security\applock\AndroidAppLockKeystore.kt:27-29` : `KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).setDigests(KeyProperties.DIGEST_SHA256).build()` — ni `setUnlockedDeviceRequired`, ni authentification, ni `setMaxUsageCount`, ni StrongBox. Tout code sous l'UID calcule le MAC à volonté, téléphone verrouillé (l. 45-47).

**Attaquant** : C, code sous l'UID, téléphone saisi verrouillé AFU (préférences en stockage chiffré par identifiants). C fournit les PIN candidats.

**Défenses cherchées, aucune n'arrête le chemin**
1. Autre attribut ou limite : alias `app_lock_hmac_v1` seulement en `AndroidAppLockKeystore.kt:91` ; implémentation unique (`di\AppLockModule.kt:29`) ; `setUnlockedDeviceRequired` seulement pour la clé de coffre (`AndroidVaultKeystore.kt:283`) ; `setUserAuthenticationRequired(true)` seulement pour la clé biométrique ; aucun `setMaxUsageCount` ; le récepteur `ACTION_SCREEN_OFF` (`AppLockLifecycle.kt:43-47`) verrouille l'interface, pas la clé.
2. Chiffrement des préférences : aucun. Vérificateur `v1:<sel hex>:<tag hex>` (`AppLockPin.kt:24`) écrit tel quel (`AppLockStore.kt:148`), SharedPreferences `MODE_PRIVATE` (`data\prefs\LegacyPreferences.kt:58`).
3. Coût Argon2id : ne borne pas l'oracle. 32 Mio, t=2 (`AppLockPin.kt:62-63`), p=1 (`VaultParams.kt:44`), BouncyCastle dans le processus (`VaultCrypto.kt:53-66`) avant le MAC (`AppLockPin.kt:131-146`), sel en clair : précalcul hors appareil sur tout l'espace (≤ 1 110 000 PIN, `AppLockPin.kt:55-56,74`) ; une opération HMAC par candidat sur l'appareil.
4. Refus d'un Keystore logiciel : aucun, voulu (`AppLockKeystore.kt:23-27`).
5. Temporisation seulement dans la logique de l'app (`AppLockManager.kt:212-247`), contournée ; `AppLockStore.kt:118-120` admet que le compteur « can only be rewound, by someone with that access, on a device they already own ».
6. Documentation : D-023 (`docs\01-DECISIONS.md:632,644-646`) et `AppLockKeystore.kt:23-27` justifient l'absence de l'attribut par les téléphones SANS verrouillage d'écran ; n'examinent pas cet attaquant ; le projet le traite ailleurs comme une menace réelle (`AndroidVaultKeystore.kt:278-283` ; D-026 `docs\01-DECISIONS.md:839-845` ; `docs\04-PIEGES.md:5132-5136`). « Déjà propriétaire » est une affirmation, pas une garde.

**Gain, borné** : C lit déjà toute la base hors coffres (`KeystoreSealedKekSource.kt:243-251`) ; il n'a ni les coffres (`AndroidVaultKeystore.kt:283`) ni le code du téléphone. L'oracle livre le PIN du verrou d'app, qui ne vaut que réutilisé (« very likely », `AppLockKeystore.kt:10-11`) : s'il est le code du téléphone, C déverrouille sans la limitation d'essais du système (Gatekeeper), puis ouvre les coffres ; s'il est seulement celui d'un coffre, inutile tant que le téléphone reste verrouillé. Contredit « The app lock must not become the side door to it » (`AppLockKeystore.kt:15-16`).

**FAIBLE parce que** : chaîne d'exploitation nécessaire (`allowBackup="false"`, `AndroidManifest.xml:47` ; release non débogable) ; verrou optionnel (`AppLockManager.kt:186`) ; AFU seulement ; gain conditionné à une réutilisation de PIN.

Non mesuré : le débit réel de l'oracle HMAC du Keystore ; aucune limite de débit dans le dépôt.
