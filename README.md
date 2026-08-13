# Notes Tech — portage Kotlin natif

Réécriture en Kotlin natif de [Notes Tech](https://github.com/gitubpatrice/notes_tech), aujourd'hui
en Flutter et publiée en **2.0.3** (versionCode 51).

> 🔒 **Ce dépôt ne remplace pas l'application installée.**
> L'`applicationId` par défaut porte le suffixe `.next` : la build s'installe **à côté** de Notes
> Tech et, le bac à sable étant distinct, ne *peut pas* voir ses données. Prendre sa place demande
> un geste explicite, réservé à la phase 8.
> Voir [docs/06-ISOLATION-PENDANT-LE-CHANTIER.md](docs/06-ISOLATION-PENDANT-LE-CHANTIER.md).

## Par où commencer

| Vous voulez… | Lire |
|---|---|
| savoir où en est le chantier | [docs/00-PLAN.md](docs/00-PLAN.md) |
| comprendre pourquoi c'est fait comme ça | [docs/01-DECISIONS.md](docs/01-DECISIONS.md) |
| toucher à la base de données | [docs/02-SCHEMA-HERITE.md](docs/02-SCHEMA-HERITE.md) **et** [docs/04-PIEGES.md](docs/04-PIEGES.md) |
| toucher à la clé de chiffrement | [docs/03-KEK-ACQUISITION.md](docs/03-KEK-ACQUISITION.md) |
| savoir ce qui a déjà été relu | [docs/07-RELECTURES.md](docs/07-RELECTURES.md) |
| reprendre le travail après une pause | [docs/08-JOURNAL.md](docs/08-JOURNAL.md) |

## L'enjeu, en trois phrases

Cette application doit ouvrir **la base de données déjà présente chez les utilisateurs** : SQLite
chiffrée par SQLCipher, clé scellée dans le Keystore de l'appareil, **aucune sauvegarde**
(`allowBackup=false`). Une clé introuvable ou, pire, une clé neuve générée par-dessus, et les notes
sont perdues définitivement.

Tout le reste du portage est de la traduction ; ce point-là est le projet.

## Commandes

```bash
# Gate complet — ce que la CI exécute
./gradlew :app:assembleDebug testDebugUnitTest :app:lintDebug detekt ktlintCheck

# Les entités décrivent-elles toujours la base héritée ?
python audits/verifier-schema-room-vs-flutter.py

# La promesse « zéro réseau » tient-elle sur l'APK qui sera publié ?
./gradlew :app:processReleaseMainManifest -Pnotestech.replaceInstalledApp=true
python tools/check-manifest-permissions.py

# Tests sur appareil — TOUJOURS en fixant la cible
ANDROID_SERIAL=<numéro de série> ./gradlew :app:connectedDebugAndroidTest
```

⚠️ `connectedAndroidTest` cible **tous** les appareils branchés et **efface les données** de
l'application testée. Fixer `ANDROID_SERIAL` n'est pas une commodité, c'est la précaution.

## État vérifié

Au 2026-08-13 — mesuré, pas estimé :

| | |
|---|---|
| Tests JVM | 37, 0 échec |
| Tests instrumentés (Galaxy S9, API 29) | 20, 0 échec |
| Schéma Room vs DDL hérité | aucune divergence |
| Permissions du manifeste fusionné release | aucune permission réseau |
| Relectures indépendantes | 6 — dont une passe **sur les correctifs eux-mêmes** |

Phases 1 et 2 closes. La suite est dans [docs/00-PLAN.md](docs/00-PLAN.md).

## Licence

Apache 2.0, comme le dépôt d'origine.
