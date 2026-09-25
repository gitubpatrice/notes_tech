# Conditions d'utilisation — Notes Tech

**Version 1.1.0 — Septembre 2026**

## Licence

Notes Tech est un logiciel libre publié sous **licence Apache 2.0**. Vous pouvez l'utiliser, le modifier et le redistribuer dans les conditions de cette licence. Le texte complet est disponible dans le fichier `LICENSE` du dépôt source (https://github.com/gitubpatrice/notes_tech).

## Usage

L'application est mise à disposition **gratuitement** par un particulier, à titre personnel et non commercial, et fournie **telle quelle, sans garantie d'aucune sorte** (licence Apache 2.0, section 7). L'usage que vous en faites relève de **votre seule responsabilité**, comme le contenu de vos notes. La dictée vocale repose sur un modèle de reconnaissance automatique de la parole, qui peut produire des transcriptions imparfaites.

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

## Composants tiers embarqués dans l'application

La dictée vocale s'exécute entièrement sur votre téléphone, au moyen d'un moteur de transcription
**inclus dans l'application** : `whisper.cpp` et `ggml`, version 1.8.3, publiés sous licence MIT.
Cette licence exige que sa notice accompagne toute copie du logiciel ; elle est donc reproduite
ci-dessous, et le code correspondant figure dans le dépôt source sous
`app/src/main/cpp/vendor/whisper/`.

> MIT License
>
> Copyright (c) 2023-2024 The ggml authors
> Copyright (C) 2024 Intel Corporation
> Copyright (c) 2023 Jeffrey Quesnelle and Bowen Peng
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
> associated documentation files (the "Software"), to deal in the Software without restriction,
> including without limitation the rights to use, copy, modify, merge, publish, distribute,
> sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or
> substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
> NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
> NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
> DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Données

Toutes vos notes sont stockées **localement et chiffrées** sur votre téléphone (voir la **Politique de confidentialité**). Notes Tech n'envoie rien sur Internet et n'a pas la permission technique de le faire (pas de permission `INTERNET` Android).

## Mises à jour

Les mises à jour sont distribuées via le dépôt GitHub officiel. Aucune mise à jour automatique : c'est à vous d'installer la nouvelle version.

## Responsabilité

L'éditeur ne pourra être tenu responsable de tout dommage direct ou indirect résultant de l'utilisation de l'application, dans la limite autorisée par la loi française (licence Apache 2.0, section 8). En particulier, **toute perte de données consécutive à un mode panique, à un oubli de passphrase, à un auto-wipe PIN ou à une désinstallation de l'application est de la seule responsabilité de l'utilisateur**.

## Loi applicable

Conditions soumises au **droit français**. Tribunaux français compétents en cas de litige.

## Langue

Ces conditions ont été rédigées en français ; les versions dans d'autres langues en sont des traductions. En cas de différence, **la version française fait foi**.

## Contact

**contact@files-tech.com**

---

Notes Tech fait partie de la suite **Files Tech**, éditée par **Patrice Haltaya**.
