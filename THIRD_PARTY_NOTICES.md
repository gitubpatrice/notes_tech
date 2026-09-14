# Third-party notices — Notes Tech

Notes Tech uses the following Dart/Flutter packages. This list is kept in
line with `pubspec.yaml`. The license of each package can be checked on
pub.dev or via `flutter pub deps --no-dev`.

---

## Direct dependencies

| Package | Version | License | Use |
|---|---|---|---|
| `provider` | ^6.1.2 | MIT | DI / state management |
| `sqflite_sqlcipher` | ^3.1.0 | MIT | AES-256 encrypted SQLite database |
| `path` / `path_provider` | ^1.9.0 / ^2.1.4 | BSD-3-Clause | Path resolution |
| `shared_preferences` | ^2.3.2 | BSD-3-Clause | User preferences |
| `file_picker` | ^10.3.3 | MIT | SAF import (voice model `.bin`) |
| `uuid` | ^4.5.1 | MIT | Note / folder IDs |
| `intl` | ^0.20.0 | BSD-3-Clause | Date formatting |
| `crypto` | ^3.0.5 | BSD-3-Clause | Streaming SHA-256 |
| `cryptography` | ^2.7.0 | Apache-2.0 | Argon2id and AES-GCM for per-folder vaults |
| `flutter_secure_storage` | ^10.0.0 | BSD-3-Clause | KEK storage backed by the Android Keystore |
| `url_launcher` | ^6.3.0 | BSD-3-Clause | Opening external links |
| `share_plus` | ^10.0.0 | BSD-3-Clause | Android share Intent |
| `archive` | ^4.0.0 | MIT | ZIP export generation |
| `flutter_markdown_plus` | ^1.0.12 | BSD-3-Clause | Markdown rendering (note preview, legal pages); successor of the discontinued `flutter_markdown` |
| `markdown` | ^7.3.1 | BSD-3-Clause | Markdown parsing; inline syntax for `[[Title]]` links |

## Files Tech sibling modules

| Package | Version | License | Repository |
|---|---|---|---|
| `files_tech_voice` | git, commit `dca1e1d` | Apache-2.0 | https://github.com/gitubpatrice/files_tech_voice |
| `files_tech_core` | git, commit `9d67344` | Apache-2.0 | https://github.com/gitubpatrice/files_tech_core |

`files_tech_voice` itself depends on:
- `whisper_ggml_plus` (MIT) — whisper.cpp wrapper
- `record` (BSD-3-Clause) — 16 kHz PCM audio capture
- `permission_handler` (MIT) — RECORD_AUDIO permission
- `http` (BSD-3-Clause)
- `crypto` and `path_provider` (listed above)

`files_tech_core` itself depends on:
- `http` (BSD-3-Clause)
- `flutter_markdown` (BSD-3-Clause) — discontinued upstream, still resolved
  as a transitive dependency through this module
- `shared_preferences`, `share_plus` and `url_launcher` (listed above)

## Bundled asset

| File | Origin | License |
|---|---|---|
| `ggml-silero-v6.2.0.bin` (~864 KB) — Silero VAD voice activity detection model | Asset declared by `whisper_ggml_plus` | MIT |

## Voice dictation model (NOT bundled)

You download the Whisper model yourself from the official source.
Notes Tech does not redistribute it.

| Model | Source | License |
|---|---|---|
| `ggml-base-q5_1.bin` (~57 MB) | https://huggingface.co/ggerganov/whisper.cpp | MIT |
| `ggml-tiny-q5_1.bin` (~32 MB) | https://huggingface.co/ggerganov/whisper.cpp | MIT |

---

For the up-to-date list with exact versions: `flutter pub deps`.
