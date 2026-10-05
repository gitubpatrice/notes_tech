# Notes Tech

> Vos notes restent dans votre poche. Chiffrées, et hors ligne.

🇬🇧 [English version](README.md)

**v3.0.0 — octobre 2026** · [Politique de confidentialité](PRIVACY.md) · [Conditions d'utilisation](TERMS.md) · [Sécurité](SECURITY.fr.md)

Application de prise de notes Markdown chiffrée pour Android, écrite en **Kotlin** avec Jetpack
Compose. **100 % locale, sans permission Internet.** Interface en **anglais, français, allemand,
italien et espagnol**. Coffres par dossier (phrase secrète Argon2id ou code lié au Keystore),
verrouillage de l'application par code et biométrie forte, recherche plein texte FTS5, dictée
Whisper sur l'appareil, rétroliens `[[note]]`, aperçu Markdown, mode panique en plusieurs étapes.

Pour les penseurs, thérapeutes, étudiants, chercheurs, auteurs et journalistes qui veulent prendre
des notes sensibles ou denses sans qu'elles quittent jamais leur téléphone.

**Ce qui la distingue de Notesnook / Obsidian / Bear / Logseq : aucune permission Internet —
l'application est techniquement incapable d'envoyer quoi que ce soit, et son manifeste permet de le
vérifier.**

---

## Nouveautés de la 3.0.0

**Notes Tech est réécrite en Kotlin.** Les versions 1.x et 2.x étaient construites avec Flutter. La
3.0.0 garde vos données : installée par-dessus une 2.0.x, elle ouvre la même base chiffrée et les
mêmes coffres — mesuré par-dessus la 2.0.3, la 2.0.4 et la 2.0.9 publiée, coffres à phrase secrète et
à code compris.

- **Verrouillage de l'application** : un code demandé à l'ouverture de Notes Tech, et l'empreinte (ou
  le visage, si le téléphone le juge sûr) si vous le souhaitez ; un délai avant reverrouillage ; son
  contenu est masqué dans l'écran des applications récentes.
- **Allemand, italien et espagnol**, en plus de l'anglais et du français.
- **Une application bien plus légère** : l'APK arm64 pèse environ **8 Mo**, contre 27 Mo.
- **Renforcements issus d'un audit de sécurité complet** (septembre 2026) :
  - refermer un coffre efface la note ouverte à l'écran ;
  - un texte supprimé, ou déplacé dans un coffre, est aussi effacé de l'index de recherche ;
  - une copie depuis une note de coffre passe par le presse-papiers protégé, et le clavier est prié
    de ne rien apprendre de ce que vous y tapez ;
  - les actions de texte d'autres applications n'apparaissent plus dans le menu de sélection, et
    aucune police d'émojis n'est demandée aux services Google ;
  - un effacement panique interrompu s'achève au lancement suivant ;
  - les fichiers en clair laissés dans le cache par la 2.x sont effacés au premier lancement.
- **Avant Android 9, plus de nouveau coffre à code** : Android ne sait pas y lier sa clé au
  déverrouillage du téléphone. Un coffre à code existant s'ouvre toujours, et le dit.

⚠️ **Cette mise à jour est sans retour.** Android refuse de réinstaller une 2.x par-dessus la 3.0.0,
et désinstaller efface vos notes avec leur clé. Dans le doute, exportez ce que vous voulez garder
avant de mettre à jour.

⚠️ **Les copies F-Droid et GitHub ne se mettent pas à jour l'une l'autre** : F-Droid signe Notes Tech
avec sa propre clé. Une copie installée depuis F-Droid reçoit la 3.0.0 par F-Droid.

---

## Promesse de confidentialité

- **Aucune permission `INTERNET`.** Le manifeste fusionné de la version publiée en porte exactement
  quatre :
  - `RECORD_AUDIO` — demandée à l'usage, seulement si vous activez la dictée ;
  - `USE_BIOMETRIC` et `USE_FINGERPRINT` — ajoutées par AndroidX Biometric pour le verrouillage ;
    accordées à l'installation, elles ne donnent accès à aucune donnée ;
  - `com.filestech.notes_tech.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — ajoutée par AndroidX,
    niveau `signature`, interne à l'application.

  [`tools/check-manifest-permissions.py`](tools/check-manifest-permissions.py) contrôle le manifeste
  fusionné, et non le manifeste source, qui ne déclare `INTERNET` que pour la retirer.
- Aucun compte, aucune inscription, aucun traceur, aucune publicité, aucune télémétrie.
- Libre sous licence Apache 2.0 : tout le code peut être inspecté.
- `allowBackup=false` et `dataExtractionRules` complètes : rien ne sort par la sauvegarde Android ni
  par un transfert d'appareil à appareil.
- Le modèle Whisper est importé par vous, via le sélecteur de fichiers du système — jamais embarqué,
  jamais téléchargé par l'application.

---

## Fonctions

### Édition Markdown
- Création, édition, enregistrement automatique.
- **Éditer / Aperçu** : l'aperçu affiche titres, listes, emphase et liens. Un lien `[[Titre]]` ouvre la
  note qu'il désigne ; un lien `http`, `https` ou `mailto` s'ouvre dans l'application du système, tout
  autre schéma est ignoré. Les images ne sont jamais chargées : leur texte de remplacement s'affiche.
- Épingler, favoris, archives, corbeille (30 jours), ordre de tri, thème clair / sombre / système.

### Coffres par dossier
- **Mode phrase secrète** — Argon2id (64 Mio, t = 3) et AES-256-GCM. La clé du coffre (32 octets
  aléatoires) est enveloppée par la clé dérivée de la phrase secrète et rangée dans la base SQLCipher.
- **Mode code** — 4 à 6 chiffres, Argon2id allégé (32 Mio, t = 2) et une clé Keystore propre à chaque
  coffre ; **5 échecs effacent la clé du coffre**. Demande Android 9 ou plus récent.
- Chiffrement authentifié lié à son contexte (le dossier pour la clé, la note pour le contenu), et un
  vérificateur à temps constant qui détecte un mauvais secret sans déchiffrer les notes.
- **Verrouillage automatique** dès que l'application passe en arrière-plan, et après un délai choisi
  (5, 15, 30 ou 60 minutes, ou jamais ; 15 par défaut).

### Verrouillage de l'application
- Code de 4 à 6 chiffres, vérifié par Argon2id et une clé HMAC gardée par le Keystore ; délais
  croissants après cinq erreurs.
- Déverrouillage biométrique fort (classe 3) facultatif, adossé à sa propre clé Keystore.
- Reverrouillage immédiat, ou après 15 secondes, 1 minute ou 5 minutes en arrière-plan.

### Recherche
- Recherche plein texte **FTS5** instantanée (analyseur `unicode61`, accents ignorés). Le texte
  supprimé est aussi effacé de l'index (`secure-delete`).

### Dictée Whisper
- **whisper.cpp 1.8.3**, compilé dans l'application à partir de ses sources et exécuté sur l'appareil.
- Whisper Base q5_1 (57 Mo) ou Tiny q5_1 (32 Mo), téléchargé par votre navigateur
  depuis la source officielle puis importé ; contrôlé par SHA-256 avant usage.
- Les enregistrements sont supprimés une fois transcrits.

### Rétroliens
- Liens `[[Titre]]` avec autocomplétion, panneau Mentions / liens sortants ; un lien vers une note
  inexistante se rattache dès que celle-ci apparaît.

### Export Markdown
- Une note en `.md`, avec un en-tête lisible par Obsidian, Logseq, Bear, Foam ou Dendron.
- Tout en ZIP : un dossier par dossier de notes, plus un README.

### Mode panique
- Réglages → Mode panique, confirmé en tapant `WIPE`.
- Une séquence ordonnée, où une étape en échec n'arrête pas les suivantes : fenêtre protégée, dictée
  arrêtée et interdite, presse-papiers vidé, coffres verrouillés, clés des coffres à code et du
  verrouillage supprimées, **clé de la base détruite**, exports et enregistrements effacés, en-tête de
  la base écrasé et fichiers supprimés, modèle vocal, préférences et cache effacés. Interrompue, elle
  reprend au lancement suivant.

### Protection de l'écran
- Activée par défaut : ni capture d'écran, ni aperçu dans l'écran des applications récentes.

---

## Installation

1. **APK publié** — depuis les [Releases GitHub](https://github.com/gitubpatrice/notes_tech/releases),
   l'APK universel (`notes-tech-universel-3.0.0.apk`, tous téléphones), ou le fichier plus léger de
   votre appareil (`arm64-v8a` convient à presque tous les téléphones depuis 2016). Vérifiez l'empreinte SHA-256 publiée dans les notes de version.
2. **F-Droid** — [f-droid.org/packages/com.filestech.notes_tech](https://f-droid.org/packages/com.filestech.notes_tech/),
   construite et signée par F-Droid.
3. **Construction locale** — section suivante.

Pas de Play Store : aucun compte n'est nécessaire pour installer.

---

## Construction locale

Prérequis : JDK 17, SDK Android 36, NDK `27.0.12077973` et CMake 3.22.1 (épinglés dans
`app/build.gradle.kts`). Gradle 8.13 arrive avec le wrapper.

```bash
./gradlew testDebugUnitTest lintDebug
./gradlew assembleRelease -Pnotestech.replaceInstalledApp=true
```

⚠️ Sans `-Pnotestech.replaceInstalledApp=true`, la construction s'installe **à côté** de Notes Tech,
sous `com.filestech.notes_tech.next`, avec un stockage à elle et vide : elle ne peut ni voir ni
remplacer une copie installée. C'est ainsi que le portage a été développé sans jamais toucher à des
données réelles.

La version se trouve dans [`version.properties`](version.properties) ; chaque APK par ABI porte
`versionCode × 10 + ABI` (1 = armeabi-v7a, 2 = arm64-v8a, 3 = x86_64), le schéma qu'exige F-Droid,
et l'APK universel `versionCode × 10`.

## Technique

- Kotlin 2.3, Jetpack Compose (Material 3), Hilt, Room sur **SQLCipher** 4.16, DataStore
- Bouncy Castle (Argon2id), Keystore Android, AES-256-GCM de la plateforme
- `org.jetbrains:markdown` (aperçu), whisper.cpp par JNI (dictée)
- **Aucune bibliothèque réseau**

Composants tiers et leurs licences : [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Cibles

- minSdk 24 (Android 7.0), targetSdk 36
- Testée sur Samsung Galaxy S9 (Android 10) et S24 FE (Android 16)

## Comment elle a été construite

Les notes de conception, décisions, pièges et l'audit de sécurité du portage sont dans
[`docs/`](docs/) et [`audits/`](audits/) — commencer par [`docs/README-PORTAGE.md`](docs/README-PORTAGE.md).

---

## Licence

[Apache License 2.0](LICENSE) — voir aussi [`NOTICE`](NOTICE) et
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## La suite Files Tech

Notes Tech fait partie de la suite [Files Tech](https://files-tech.com/), des applications Android
respectueuses de la vie privée :
- [PDF Tech](https://github.com/gitubpatrice/PDF-TECH)
- [Read Files Tech](https://github.com/gitubpatrice/READ-FILES-TECH)
- [Pass Tech](https://github.com/gitubpatrice/pass_tech)
- [Agenda Tech](https://github.com/gitubpatrice/AGENDA-TECH)
- [SMS Tech](https://github.com/gitubpatrice/SMS-TECH)
- [App Manager Tech](https://github.com/gitubpatrice/APP-MANAGER-TECH)
