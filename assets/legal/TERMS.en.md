# Terms of use — Notes Tech

**Version 1.0.0 — May 2026**

## License

Notes Tech is free software published under the **Apache 2.0 license**. You may use, modify and redistribute it under the terms of that license. The full text is available in the `LICENSE` file of the source repository (https://github.com/gitubpatrice/notes_tech).

## Usage

The app is provided **as is, without warranty of any kind**. The AI features (questions/answers on your notes via Gemma, Whisper dictation) are powered by AI that may produce errors, inaccurate information, or imperfect transcriptions. You alone remain responsible for the content and the use you make of the generated suggestions.

## Limitations

- Notes Tech is **in no case** a substitute for medical, legal, financial or professional advice.
- The Gemma model may **hallucinate** (invent facts with confidence); always verify critical answers.
- Whisper may transcribe incorrectly, especially in noisy environments or with specialized technical terms.
- Performance depends on your hardware and the loaded model.

## Panic mode and data loss

The **panic mode** permanently and irreversibly wipes your notes, encryption key, and models. **No recovery is possible** — it is by design. Before using it, export what you want to keep via `Settings → Export`.

Likewise, **forgetting a vault passphrase makes its notes unreadable forever**: the passphrase is never stored, it only derives the key via Argon2id. No recovery procedure exists.

For **PIN** vaults, **5 successive failures trigger an auto-wipe** (Keystore key deletion). Aligned with the standard Android lock screen behaviour.

## AI models

The app is compatible with:
- **Gemma 3 1B int4** in MediaPipe `.task` format — Google's Gemma license: https://ai.google.dev/gemma/terms
- **Whisper** GGML `.bin` models — MIT license, source `ggerganov/whisper.cpp`

You are responsible for complying with those licenses.

## Data

All your notes are stored **locally and encrypted** on your phone (see the **Privacy policy**). Notes Tech sends nothing over the Internet and has no technical permission to do so (no Android `INTERNET` permission).

## Updates

Updates are distributed via the official GitHub repository. No auto-update: it is up to you to install the new version.

## Liability

The publisher cannot be held liable for any direct or indirect damage resulting from the use of the app, within the limits authorized by French law. In particular, **any data loss consequent to a panic mode, a forgotten passphrase, a PIN auto-wipe or an uninstall is the sole responsibility of the user**.

## Governing law

Terms governed by **French law**. French courts have jurisdiction in case of dispute.

## Contact

**contact@files-tech.com**

---

Notes Tech is part of the **Files Tech** suite, published by a French sole proprietorship.
