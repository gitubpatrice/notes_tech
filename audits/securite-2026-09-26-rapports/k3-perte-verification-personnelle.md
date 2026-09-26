# K3 — risque de PERTE de la base (vérification personnelle, hors panel) — 2026-09-26

Question : pour les utilisateurs passés par la passerelle, la clé de la base porte-t-elle
`setUnlockedDeviceRequired`, et le retrait du verrouillage d'écran (API 31+, mesuré API 34 le 25)
fait-il perdre la base ?

## Faits (lus, pas déduits)

1. La passerelle `sealDatabaseKek` est dans TOUTES les versions Flutter publiées de v2.0.4 à v2.0.9
   (`git grep -c sealDatabaseKek <tag> -- android lib` : 0 en v2.0.3, 6 dans chacune de v2.0.4 à v2.0.9).
   => concerne tous les utilisateurs ayant lancé une 2.0.4+ sur Android 9+, pas une poignée.
2. `notes_tech` v2.0.9, `android/app/src/main/kotlin/com/filestech/notes_tech/KeystoreBridge.kt` :
   `sealDatabaseKek` (l. 194) → `createKey(KEK_ALIAS)` (l. 202) → l. 241-242
   `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { b.setUnlockedDeviceRequired(true)`.
   => la clé primaire de ces utilisateurs PORTE l'attribut (API 28+).
3. Portage, `security/kek/KeystoreSealedKekSource.kt:62` : `val secretKey = existingKey() ?: return null`
   => clé supprimée par Android = « je n'ai rien », PAS un échec.
4. `security/kek/KekRepository.kt:118-157` : les sources sont lues dans l'ordre (primaire, puis
   `FlutterSecureStorageKekSource`, `di/DatabaseModule.kt:69`) ; la copie Flutter rend la clé ; comme
   aucune source n'a ÉCHOUÉ, `promoteToPrimary` (l. 156) → `primary.store(kek)` (l. 227) →
   `KeystoreSealedKekSource.kt:147` `existingKey() ?: createKey()` : clé NEUVE du portage, SANS
   l'attribut, et scellé réécrit (`commit`).

## Conclusion

- **Pas de perte aujourd'hui.** Retrait du verrouillage d'écran → clé de la passerelle supprimée →
  au lancement suivant, la base s'ouvre par la copie Flutter et se RÉPARE (rescellée sous une clé
  sans l'attribut). Confiance : haute par lecture ; la chaîne complète n'est pas mesurée.
- **Condition unique** : la copie `flutter_secure_storage` doit exister à ce moment. Rien ne la
  retire hors panique aujourd'hui. **Tout futur « ménage » de cette copie après migration ferait
  perdre la base** à ces utilisateurs s'ils retirent ensuite leur verrouillage d'écran (Android 12+)
  avant que la réparation ait eu lieu → à écrire en garde-fou (KDoc de la source Flutter + doc).
- Sur téléphone VERROUILLÉ (API 28+) : la primaire échoue (échec, pas absence), la copie Flutter
  ouvre la base, sans recopie. D'où le constat K3 (l'attribut ne protège rien tant que la copie
  existe) — laissé au panel.

## À proposer en correctif (après le rapport)

- Test instrumenté manquant : scellé écrit comme la passerelle, clé supprimée du Keystore →
  `load()` rend null (pas d'échec) ; puis `KekRepository` réel + copie Flutter → clé rendue ET
  primaire rescellée. Aujourd'hui seul `KekRepositoryTest.promotionVersLaSourcePrimaire` (faux).
- `docs/10-PASSERELLE-2.0.4.md` §5 faux deux fois : « toute ouverture de base sur un appareil
  verrouillé échouera » (la copie Flutter l'ouvre) et « sans rien détruire » (API 31+ : le retrait du
  verrouillage SUPPRIME la clé ; la base est sauvée par la copie Flutter).
- `KeystoreSealedKekSource.kt:57-58` : « changement d'écran de verrouillage » — imprécis : le
  CHANGEMENT garde les clés, le RETRAIT les supprime (mesuré le 25, D-026).
