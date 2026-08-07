# Conditions d'utilisation — Notes Tech

**Version 1.0.0 — Mai 2026**

## Licence

Notes Tech est un logiciel libre publié sous **licence Apache 2.0**. Vous pouvez l'utiliser, le modifier et le redistribuer dans les conditions de cette licence. Le texte complet est disponible dans le fichier `LICENSE` du dépôt source (https://github.com/gitubpatrice/notes_tech).

## Usage

L'application est fournie **telle quelle, sans garantie d'aucune sorte**. La dictée vocale repose sur un modèle de reconnaissance automatique de la parole, qui peut produire des transcriptions imparfaites. Vous restez seul responsable du contenu de vos notes.

## Limitations

- Notes Tech ne se substitue **en aucun cas** à un avis médical, juridique, financier ou professionnel.
- Whisper peut transcrire incorrectement, surtout en environnement bruyant ou avec des termes techniques spécialisés.
- Les performances dépendent de votre matériel et du modèle chargé.

## Mode panique et perte de données

Le **mode panique** efface définitivement et irréversiblement vos notes, votre clé de chiffrement et vos modèles. **Aucune récupération n'est possible** — c'est par conception. Avant de l'utiliser, exportez ce que vous voulez conserver via `Réglages → Exporter`.

De même, **oublier la passphrase d'un coffre rend ses notes illisibles à jamais** : la passphrase n'est jamais stockée, elle ne sert qu'à dériver la clé via Argon2id. Aucune procédure de récupération n'existe.

Pour les coffres en mode **PIN**, **5 échecs successifs déclenchent un auto-wipe** (suppression de la clé Keystore). Aligné sur le comportement standard d'un écran de verrouillage Android.

## Modèle de dictée vocale

L'application est compatible avec :
- **Whisper** modèles GGML `.bin` — licence MIT, source `ggerganov/whisper.cpp`

Vous êtes responsable du respect de ces licences.

## Données

Toutes vos notes sont stockées **localement et chiffrées** sur votre téléphone (voir la **Politique de confidentialité**). Notes Tech n'envoie rien sur Internet et n'a pas la permission technique de le faire (pas de permission `INTERNET` Android).

## Mises à jour

Les mises à jour sont distribuées via le dépôt GitHub officiel. Aucune mise à jour automatique : c'est à vous d'installer la nouvelle version.

## Responsabilité

L'éditeur ne pourra être tenu responsable de tout dommage direct ou indirect résultant de l'utilisation de l'application, dans la limite autorisée par la loi française. En particulier, **toute perte de données consécutive à un mode panique, à un oubli de passphrase, à un auto-wipe PIN ou à une désinstallation de l'application est de la seule responsabilité de l'utilisateur**.

## Loi applicable

Conditions soumises au **droit français**. Tribunaux français compétents en cas de litige.

## Contact

**contact@files-tech.com**

---

Notes Tech fait partie de la suite **Files Tech**, éditée par une micro-entreprise française.
