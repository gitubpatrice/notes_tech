# K5 — angle ACCESSIBILITÉ — verdict : VRAI POSITIF, FAIBLE, gain à corriger (reçu 2026-09-26)

VERDICT : VRAI POSITIF, gravité FAIBLE (plancher). Chemin exact ; **gain à corriger : la partie « coffre » ne tient pas pour C.**

**Attaquant et ligne qui décide** : C, code sous l'UID, AFU. Seule voie sur la build publiée : root ou exploit (release non débogable `build.gradle.kts:199` ; `allowBackup="false"` `AndroidManifest.xml:47` ; seul MainActivity exporté `:63` ; FileProvider `exported="false"` `:95`). Position prévue par l'app pour ses coffres (`AndroidVaultKeystore.kt:274-283`). Ligne qui décide : `AndroidAppLockKeystore.kt:27-29` (ni `setUnlockedDeviceRequired`, ni authentification, ni `setMaxUsageCount`) ; site unique de génération, alias constant (:91).

**Chemin** : vérificateur écrit par `AppLockStore.kt:148` au format `v1:<sel hex>:<tag hex>` (`AppLockPin.kt:24`), SharedPreferences `MODE_PRIVATE` (`LegacyPreferences.kt:61-62`), lisible AFU. Coût : Argon2id t=2, 32 Mio, p=1 (`AppLockPin.kt:62-63`, `VaultCrypto.kt:55-61`, `VaultParams.kt:44`), sel lu → précalcul hors appareil ; sur l'appareil, un HMAC Keystore par candidat (`AndroidAppLockKeystore.kt:45-47`) ; ≤ 1 110 000 candidats (`AppLockPin.kt:55-56`, `:74`) ; durée non mesurée. Garde : temporisation seulement sur la route de l'écran (`AppLockManager.kt:212-247`), compteur dans le même fichier réécrivable par C (`AppLockStore.kt:167-189`) ; `AppLockPinVerifier.matches` (`AppLockPin.kt:120-129`) sans compteur.

**Gain réel, plus étroit**
- Gain « coffre » (D-023, `01-DECISIONS.md:640-644`) **nul pour C** : téléphone verrouillé, la clé du coffre à code exige un appareil déverrouillé (`AndroidVaultKeystore.kt:283`) ; téléphone déverrouillé, C ouvre la couche Keystore sans PIN (`FolderVaultService.kt:360`) puis cherche hors ligne (`AndroidVaultKeystore.kt:278-282`) ; sous API 28, le coffre tombe déjà sans K5 (`:273`).
- Notes hors coffre déjà à C (`KeystoreSealedKekSource.kt:243-251` ; le verrou ne chiffre rien, `AppLockKeystore.kt:8-9`).
- Seul gain : la valeur du PIN, utile seulement hors de l'app en cas de réutilisation — surtout comme code de l'écran de verrouillage (sinon freiné par le matériel, Gatekeeper/Weaver ; le connaître rouvrirait tout, coffres compris), ou code de carte. L'app pose cette réutilisation comme probable (`AppLockKeystore.kt:10-11`).

**FAIBLE (obstacles, pas réfutations)** : exploit root AFU ; verrou activé par l'utilisateur, absent par défaut, clé créée à l'activation (`AppLockManager.kt:270-283`, `AppLockPin.kt:109`) ; BFU fermé (stockage par identifiant, pas `directBootAware`) ; impact entièrement conditionné à une réutilisation hors de l'app, non confirmable.

**Pour l'orchestrateur** : retirer « celui d'un coffre » du gain, garder la réutilisation comme code d'écran de verrouillage. Non confirmé : temps réel d'un HMAC Keystore ; fréquence réelle de la réutilisation.
