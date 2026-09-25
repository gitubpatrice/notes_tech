# Terms of use — Notes Tech

**Version 1.1.0 — September 2026**

## License

Notes Tech is free software published under the **Apache 2.0 license**. You may use, modify and redistribute it under the terms of that license. The full text is available in the `LICENSE` file of the source repository (https://github.com/gitubpatrice/notes_tech).

## Usage

The app is made available **free of charge** by an individual, on a personal and non-commercial basis, and is provided **as is, without warranty of any kind** (Apache 2.0 license, section 7). How you use it is **your sole responsibility**, as is the content of your notes. Voice dictation relies on an automatic speech recognition model, which may produce imperfect transcriptions.

## Limitations

- Notes Tech is **in no case** a substitute for medical, legal, financial or professional advice.
- Whisper may transcribe incorrectly, especially in noisy environments or with specialized technical terms.
- Performance depends on your hardware and the loaded model.

## Panic mode and data loss

The **panic mode** permanently and irreversibly wipes your notes, encryption key, and models. **No recovery is possible** — it is by design. Before using it, export what you want to keep via `Settings → Export`.

Likewise, **forgetting a vault passphrase makes its notes unreadable forever**: the passphrase is never stored, it only derives the key via Argon2id. No recovery procedure exists.

For **PIN** vaults, **5 successive failures trigger an auto-wipe** (Keystore key deletion). Aligned with the standard Android lock screen behaviour.

## Voice dictation model

The app is compatible with:
- **Whisper** GGML `.bin` models — MIT license, source `ggerganov/whisper.cpp`

You are responsible for complying with those licenses.

## Third-party components bundled in the app

Voice dictation runs entirely on your phone, using a transcription engine **included in the app**:
`whisper.cpp` and `ggml`, version 1.8.3, released under the MIT license. That license requires its
notice to accompany every copy of the software; it is therefore reproduced below, and the matching
source code sits in the source repository under `app/src/main/cpp/vendor/whisper/`.

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

## Data

All your notes are stored **locally and encrypted** on your phone (see the **Privacy policy**). Notes Tech sends nothing over the Internet and has no technical permission to do so (no Android `INTERNET` permission).

## Updates

Updates are distributed via the official GitHub repository. No auto-update: it is up to you to install the new version.

## Liability

The publisher cannot be held liable for any direct or indirect damage resulting from the use of the app, within the limits authorized by French law (Apache 2.0 license, section 8). In particular, **any data loss consequent to a panic mode, a forgotten passphrase, a PIN auto-wipe or an uninstall is the sole responsibility of the user**.

## Governing law

Terms governed by **French law**. French courts have jurisdiction in case of dispute.

## Language

These terms were written in French; the versions in other languages are translations. Should they differ, **the French version prevails**.

## Contact

**contact@files-tech.com**

---

Notes Tech is part of the **Files Tech** suite, published by **Patrice Haltaya**.
