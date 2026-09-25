# Politique de confidentialité — Notes Tech

**Version 1.2.0 — Septembre 2026**

## En une phrase

Notes Tech ne collecte, ne transmet et ne stocke aucune donnée sur des serveurs distants. Tout reste sur votre téléphone, et la base de données est chiffrée at-rest.

## Détail

### Données traitées

- **Vos notes Markdown** : générées et conservées exclusivement sur votre téléphone, dans une base SQLite chiffrée par **SQLCipher** avec une clé unique générée localement (KEK 32 octets) stockée dans le **Android Keystore**.
- **Coffres par dossier** : chaque coffre que vous activez utilise une **passphrase** ou un **PIN** distinct, dérivé via **Argon2id RFC 9106** (m=64MB, t=3 pour passphrase ; allégé pour PIN, compensé par le scellage Keystore device-bound). Le contenu des notes verrouillées est chiffré **AES-256-GCM** avec AAD lié à `note_id`.
- **Verrouillage de l'application (facultatif)** : le PIN lui-même n'est jamais conservé. Ce qui l'est, c'est une valeur dérivée par **Argon2id** et liée par **HMAC-SHA256** à une clé du **Keystore Android** qui ne quitte jamais le téléphone : elle ne peut donc être vérifiée nulle part ailleurs. Après cinq PIN erronés, chaque nouvel essai attend plus longtemps (30 secondes, doublées jusqu'à une heure) ; un essai raté n'efface jamais rien. Le **déverrouillage par empreinte ou par visage** est entièrement géré par Android : Notes Tech ne reçoit aucune donnée biométrique, seulement la confirmation qu'une clé du Keystore a été autorisée à servir, et seule la biométrie forte (classe 3) est acceptée.
- **Backlinks `[[Titre]]`** : index inversé local, jamais transmis.
- **Modèle de dictée vocale (Whisper `.bin`)** : vous vous le procurez vous-même — l'application affiche le nom du fichier et sa source — puis vous l'importez par le sélecteur de documents Android. Notes Tech n'a pas la permission Internet et **n'expose aucun moyen de télécharger quoi que ce soit**. Son empreinte SHA-256 est vérifiée à l'import et avant chaque chargement.
- **Audio capturé pendant la dictée** : écrit dans un fichier temporaire du stockage privé de l'application — le moteur de transcription lit un fichier, il ne peut pas en être autrement — puis **effacé dès la transcription obtenue**. Il l'est également au démarrage de l'application et par le mode panique, de sorte qu'un arrêt brutal ne laisse rien derrière lui.
- **Préférences (thème, tri, dictée activée, auto-lock coffre, options du verrouillage)** : stockées en clair dans les préférences locales (pas de donnée sensible).

### Données NON traitées

- **Aucune télémétrie**, aucune analytics, aucun crash reporter tiers.
- **Aucune publicité**, aucun tracker.
- **Aucun compte utilisateur**, aucune connexion à un service en ligne.

### Permissions Android demandées

Notes Tech ne demande **AUCUNE permission `INTERNET`**. L'application est techniquement incapable de communiquer avec un serveur distant. Cette absence est vérifiable dans l'`AndroidManifest.xml` du dépôt source (`tools:node="remove"` sur INTERNET et ACCESS_NETWORK_STATE).

Les permissions actives sont strictement utilitaires :
- `RECORD_AUDIO` (dictée : l'audio est écrit dans un fichier temporaire privé, puis effacé dès la transcription obtenue).
- `USE_BIOMETRIC` et `USE_FINGERPRINT` (déverrouillage facultatif de l'application par empreinte ou par visage ; déclarées par la bibliothèque biométrique AndroidX — `USE_FINGERPRINT` est le nom que porte la même permission sur Android 8 et antérieur).

Ce sont les **seules** permissions demandées. Le choix du fichier de modèle passe par le sélecteur de documents Android, qui n'en exige aucune.

### Mode panique

Le menu **Réglages → Mode panique** efface en bloc :
- la base SQLite chiffrée (toutes les notes),
- la KEK SQLCipher (irrécupérable),
- les clés Keystore associées aux coffres PIN,
- les clés Keystore du verrouillage de l'application (vérification du PIN et déverrouillage biométrique),
- les coffres par dossier (passphrases et PIN),
- le presse-papiers, où une note copiée attend en clair,
- les archives d'export et les enregistrements de dictée, seuls fichiers en clair de l'application,
- le modèle Whisper installé dans le sandbox,
- les préférences (sauf `db_encrypted_v1` et `secure_window_enabled` conservées pour cohérence du redémarrage).

L'effacement **n'est pas atomique**, et l'ordre des étapes est conçu pour cela : la clé de chiffrement est détruite **avant** les effacements longs, puis viennent les fichiers en clair, puis le reste. Un arrêt brutal à n'importe quel instant laisse donc l'état le plus sûr atteignable à cet instant — au pire une base réduite à du bruit. L'écran de fin indique ce qui a échoué, et **s'il peut subsister quelque chose de lisible**.

### Vos droits

Vos données restent sur votre téléphone, sous votre seul contrôle, et l'éditeur n'a aucun moyen d'y accéder : il ne traite donc aucune donnée personnelle vous concernant. C'est le cas que la CNIL décrit comme un « simple logiciel mis à disposition de l'utilisateur », auquel le RGPD ne s'applique pas (recommandation sur les applications mobiles, § 3.3). Vous gardez la main sur vos données et pouvez à tout moment :
- exporter vos notes au format Markdown ou ZIP (`Réglages → Exporter`),
- supprimer toutes les données via le mode panique,
- désinstaller l'application — Android supprimera automatiquement toutes les données privées.

### Sous-traitants

**Aucun.** Notes Tech n'utilise aucun service tiers à l'exécution.

### Modèle de dictée vocale

- **Whisper** (modèles `.bin` `ggerganov/whisper.cpp`) : licence MIT.

Le fichier que vous chargez reste sur votre téléphone. Notes Tech ne fait que l'exécuter localement, au moyen du moteur `whisper.cpp` **inclus dans l'application** — sa licence MIT est reproduite dans les conditions d'utilisation.

### Langue

Cette politique a été rédigée en français ; les versions dans d'autres langues en sont des traductions. En cas de différence, **la version française fait foi**.

### Contact

Pour toute question : **contact@files-tech.com**

---

Notes Tech est édité par **Patrice Haltaya**. Code source publié sous licence **Apache 2.0**.
