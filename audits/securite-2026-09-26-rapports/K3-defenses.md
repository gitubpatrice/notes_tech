# K3 — angle DÉFENSES — verdict : VRAI POSITIF, MOYEN (reçu 2026-09-26)

**VERDICT : VRAI POSITIF. Gravité retenue : MOYEN**, égale à l'annonce.

## Raison décisive
La seule création de la clé `notes_tech.db.kek.v1` : `security\kek\KeystoreSealedKekSource.kt:241-254`. Spécification (243-251) arrêtée à `.setRandomizedEncryptionRequired(true)` : ni `setUnlockedDeviceRequired` ni `setUserAuthenticationRequired`.
- Installation neuve : `KekRepository.kt:339` → `KeystoreSealedKekSource.kt:200` → `:147` `existingKey() ?: createKey()`.
- Migration sans passerelle : `KekRepository.kt:156` → `:227` `primary.store(kek)` → `createKey()`.
- Utilisateurs 2.0.4+ : copie `flutter_secure_storage` en place. `FlutterSecureStorageKekSource.destroy()` atteinte seulement par `KekRepository.destroy()` (`:256-269`), appelée seulement depuis `security\panic\PanicService.kt:386`. Clé RSA de la bibliothèque (`J:\Pub\Cache\hosted\pub.dev\flutter_secure_storage-10.3.1\...\ciphers\KeyCipherImplementationRSAOAEP.java:36-43`, hors dépôt) : aucun attribut de déverrouillage ni d'authentification.

## Défenses cherchées, toutes absentes
1. Attribut posé ailleurs, rescellement, migration de clé : rien. `setUnlockedDeviceRequired` seulement en `AndroidVaultKeystore.kt:283` (coffres). `store` réutilise la clé existante (`:147`). **Effet de bord : un utilisateur 2.0.4 qui retire son verrouillage d'écran perd la clé liée au déverrouillage ; `load` rend `null` (`:62`), la promotion (`KekRepository.kt:156`) s'exécute, il retombe sur une clé sans attribut** (concorde avec ma vérification personnelle).
2. Suppression de la copie après la première ouverture réussie : aucune. La justification du maintien, « retour en arrière possible » (`KeystoreSealedKekSource.kt:37-38`), est caduque : `docs\12-PLAN-DE-BASCULE.md:15` constate qu'Android refuse le downgrade.
3. Refus du repli : aucun (`KekRepository.kt:118-133`, `loadOrNull` `:189-199`). Sans objet contre C, qui exécute son propre code.
4. KeyguardManager : aucun sur le chemin de la base (seul `AndroidVaultKeystore.kt:257`, création de coffre). N'arrêterait pas C.
5. Chiffrement supplémentaire des notes hors coffre : aucun (`NoteEntity.kt:58-67`) ; le PIN d'app « ne protège rien cryptographiquement — la base s'ouvre sans lui » (`docs\01-DECISIONS.md:640-641`).

## Attaquant et gain
- C : téléphone saisi verrouillé, code sous l'UID de l'app (chaîne forensique ou pont de débogage). Le projet nomme cet attaquant pour les coffres : `AndroidVaultKeystore.kt:273-282` ; D-026 (`docs\01-DECISIONS.md:839-843`).
- Le Keystore fait confiance à l'UID ; seule la spécification 243-251 pourrait conditionner l'usage à l'état verrouillé.
- Gain : sans clé, un fichier SQLCipher opaque ; avec, toutes les notes hors coffre, dossiers, étiquettes.

## Ce qui borne à MOYEN
- AFU obligatoire : ni `directBootAware` ni stockage protégé par l'appareil ; BFU protégé.
- Attaquant fort : exécuter du code sous l'UID sur un appareil verrouillé suppose une exploitation.
- Coffres intacts (attribut l. 283).
- Préexistant : Flutter ≤ 2.0.9 exposait déjà la clé par `flutter_secure_storage`.
- Gain marginal limité quand l'app tourne : clé brute en mémoire tant que le processus vit (`NotesDatabaseFactory.kt:74`, `rawKey.copyOf()`, `clearPassphrase=false` ; base fermée seulement en panique, `PanicService.kt:512`). L'attribut ne protégerait qu'une fois le processus mort.

## Réserves
- Correctif coûteux : D-026 mesure qu'en API 34 retirer le verrouillage supprime une clé avec l'attribut ; sur une installation neuve, perte totale des notes.
- Non vérifiable ici : l'attribut posé par la passerelle côté Flutter (hors copie figée) ; le verdict n'en dépend pas.
- Rien exécuté : usage en AFU d'une clé sans attribut = sémantique documentée du Keystore + mesure citée (`docs\04-PIEGES.md:5130-5136`).
