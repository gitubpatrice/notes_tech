# K4 — angle IMPACT — verdict : VRAI POSITIF, MOYEN (reçu 2026-09-26)

**Verdict : VRAI POSITIF. Gravité retenue : MOYEN, identique.**

**Attaquant et point de confiance** : C, code sous l'UID sur téléphone saisi verrouillé. Confiance accordée en `AndroidVaultKeystore.kt:272` `.setUserAuthenticationRequired(false)` ; restriction de verrouillage seulement en API 28+ (`:273`, `:283`) ; le fichier l'admet (`:354-355` « Below API 28 the requirement does not exist »).

**Chaîne relue** : matériel du coffre en base (`FolderDao.kt:112-119`), base ouverte écran verrouillé (`KeystoreSealedKekSource.kt:243-251`) ; un appel au Keystore déchiffre le scellé (`FolderVaultService.kt:360`, `AndroidVaultKeystore.kt:97-101`) ; hors ligne : Argon2id(code, sel, t=2, 32 Mio) sans poivre (`VaultCrypto.kt:53-74`, `VaultParams.kt:36,39`), étiquette GCM oracle (`VaultCrypto.kt:118-135`, `FolderVaultService.kt:375`), ≤ 1,11 million de codes (`VaultParams.kt:84,87` ; chiffres seuls `FolderVaultService.kt:962`) ; compteur seulement dans le chemin de l'app (`FolderVaultService.kt:256,321,460-468`).

**Gain au-delà de la position** : le reste de la base et le matériel des coffres à phrase secrète (attaquable hors ligne par conception) sont déjà acquis. Gain propre : **le clair de tous les coffres à code**, sans autre voie (clés de session effacées à l'arrêt, `NotesTechApplication.kt:108-109`, `VaultSessions.kt:177-181` ; FTS masque les verrouillées, `UnmanagedSchema.kt:97-98`).

**Réponses**
- API 28+ : un implant persistant sur un téléphone que le propriétaire continue d'utiliser exerce la clé sur toute API — mais c'est une autre position (il capterait aussi le code à la saisie). Téléphone saisi gardé verrouillé : l'attribut arrête C (`AndroidVaultKeystore.kt:280-281`). En API 24-27, c'est l'état livré.
- Part du parc : non mesurable depuis le dépôt ; minorité sans correctifs ; gardée volontairement (`build.gradle.kts:79-82`, `docs/00-PLAN.md:92-94`). Obstacle, pas réfutation.
- **Présenté comme une protection contre un téléphone saisi : oui** — interface `res/values/strings.xml:297` « Auto-wipe after 5 failures. Device-bound security (Keystore). » ; confidentialité `res/raw/privacy.md:14` « lighter for PIN, compensated by device-bound Keystore sealing » ; conditions `res/raw/terms.md:25` « 5 successive failures trigger an auto-wipe … Aligned with the standard Android lock screen behaviour » ; `NotesTechApplication.kt:101-102` « la fouille ». Aucune réserve pour Android < 9 (interface, pages légales, doc, README).
- Phrase secrète recommandée : oui (`strings.xml:295` « Recommended… resistant to off-device bruteforce », placée en premier `VaultSheets.kt:200-206`) ; le mode code reste proposé sans condition (`VaultSheets.kt:207-220`, `HomeRoute.kt:327-331`).

**MOYEN** : Android 7.0-8.1 ; mode code choisi malgré la recommandation ; exécution de niveau forensique sous l'UID sur téléphone verrouillé ; gain limité aux coffres à code. Une fois réunies, divulgation totale (ordre de grandeur non mesuré : minutes pour 4 chiffres, heures pour 6).

**Contexte** : identique dans la Flutter 2.0.9 (`docs/02-SCHEMA-HERITE.md:216`) : parité, pas régression.

**Non confirmé** : comportement réel du Keystore en API 24-27 écran verrouillé ; part du parc ; temps de calcul.
