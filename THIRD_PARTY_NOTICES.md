# Third-party notices — Notes Tech

Notes Tech utilise les paquets Dart/Flutter suivants. Cette liste est
maintenue à jour avec `pubspec.yaml`. Les licences sont consultables
sur pub.dev pour chaque paquet ou via `flutter pub deps --no-dev`.

---

## Dépendances directes

| Paquet | Version | Licence | Usage |
|---|---|---|---|
| `provider` | ^6.1.2 | MIT | DI / state management |
| `sqflite_sqlcipher` | ^3.1.0 | MIT | Base SQLite chiffrée AES-256 |
| `path` / `path_provider` | ^1.9 / ^2.1 | BSD-3-Clause | Résolution chemins |
| `shared_preferences` | ^2.3.2 | BSD-3-Clause | Préférences utilisateur |
| `file_picker` | ^10.3.3 | MIT | Import SAF (modèles, .bin) |
| `uuid` | ^4.5.1 | MIT | IDs notes / dossiers |
| `intl` | ^0.19.0 | BSD-3-Clause | Formatage dates FR |
| `crypto` | ^3.0.5 | BSD-3-Clause | SHA-256 streaming |
| `flutter_secure_storage` | ^9.2.2 | BSD-3-Clause | KEK Keystore Android |
| `url_launcher` | ^6.3.0 | BSD-3-Clause | Ouverture liens externes |
| `share_plus` | ^10.0.0 | BSD-3-Clause | Partage Intent Android |
| `archive` | ^4.0.0 | MIT | Génération ZIP export |

## Module sibling Files Tech

| Paquet | Version | Licence | Repo |
|---|---|---|---|
| `files_tech_voice` | path | Apache 2.0 | https://github.com/gitubpatrice/files_tech_voice |

Ce module dépend lui-même de :
- `whisper_ggml_plus` (MIT) — wrapper Whisper.cpp
- `record` (MIT) — capture audio PCM 16 kHz
- `permission_handler` (MIT) — permission RECORD_AUDIO

## Modèle de dictée vocale (NON bundlé)

Vous téléchargez vous-même le modèle Whisper depuis la source
officielles. Notes Tech ne les redistribue pas.

| Modèle | Source | Licence |
|---|---|---|
| `ggml-base-q5_1.bin` (~57 Mo) | https://huggingface.co/ggerganov/whisper.cpp | MIT |

---

Pour la liste à jour avec versions exactes : `flutter pub deps`.
