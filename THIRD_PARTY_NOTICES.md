# Third-party notices — Notes Tech

Notes Tech 3.0 (`com.filestech.notes_tech`) is written in Kotlin with Jetpack Compose and is licensed
under the Apache License 2.0 ([LICENSE](LICENSE)). Everything it ships that it did not write is listed
here. The list was read on 2026-10-05 from the resolved `releaseRuntimeClasspath` of the `:app` module
at 3.0.0, and each licence from the POM that the library publishes — not from memory.

**No Google Play Services, no ML Kit, no Firebase, no analytics SDK, no crash reporter, no network
library.** The app does not hold the `INTERNET` permission.

## Shipped in the APK — Java/Kotlin libraries

| Library | Version | Publisher | Licence |
|---|---|---|---|
| AndroidX: Core, Activity, AppCompat, Lifecycle, Navigation, SavedState, Fragment, Emoji2, Window, Startup, Tracing, Profile Installer, DocumentFile, Biometric, Transition and their support modules | various | Google / AOSP | Apache 2.0 |
| Jetpack Compose: UI, Foundation, Animation, Runtime, Material 3, Material Icons | BOM 2026.06.00 (UI 1.11.3, Material 3 1.4.0) | Google / AOSP | Apache 2.0 |
| Room (`androidx.room`) and `androidx.sqlite` | 2.8.4 / 2.6.2 | Google / AOSP | Apache 2.0 |
| DataStore Preferences | 1.1.1 | Google / AOSP | Apache 2.0 |
| Hilt and Dagger (`com.google.dagger`, `androidx.hilt`) | 2.57.2 / 1.3.0 | Google | Apache 2.0 |
| Guava `listenablefuture`, JSR-305 annotations | 1.0 / 3.0.2 | Google | Apache 2.0 |
| Kotlin standard library | 2.3.20 | JetBrains | Apache 2.0 |
| kotlinx.coroutines | 1.11.0 | JetBrains | Apache 2.0 |
| `org.jetbrains:markdown` — the Markdown parser behind the preview | 0.7.14 | JetBrains | Apache 2.0 |
| JetBrains annotations, JSpecify | 23.0.0 / 1.0.0 | JetBrains / JSpecify | Apache 2.0 |
| `javax.inject`, `jakarta.inject-api` | 1 / 2.0.1 | JSR-330 / Eclipse Foundation | Apache 2.0 |
| Okio (pulled in by DataStore) | 3.4.0 | Square | Apache 2.0 |
| Timber | 5.0.1 | Jake Wharton | Apache 2.0 |
| **Bouncy Castle** `bcprov-jdk18on` — **Argon2id**, the key derivation of the vaults | 1.81 | The Legion of the Bouncy Castle | **MIT** — full text below |
| **SQLCipher for Android** — the encrypted database | 4.16.0 | Zetetic LLC | **BSD-3-Clause** — full text below |

SQLCipher bundles **SQLite** and **LibTomCrypt**, both released into the public domain by their
authors.

## Shipped in the APK — native code built from source

| Component | Version | Licence | Where |
|---|---|---|---|
| **whisper.cpp** and **ggml** — on-device speech recognition for voice dictation | 1.8.3 | **MIT** — full text below | source in [`app/src/main/cpp/vendor/whisper/`](app/src/main/cpp/vendor/whisper/), provenance in its `PROVENANCE.md` |

They are compiled from that source by the Android NDK at build time; the repository holds no
prebuilt library.

## Not shipped: the dictation model

The speech model (Whisper, GGML `.bin`, MIT) is **not** in the APK. You download it yourself from its
official source and import it into the app, which then keeps a private copy.

## Licence texts

### Apache License 2.0

Every library marked "Apache 2.0" above is distributed under the same text as Notes Tech itself:
[LICENSE](LICENSE), also at <https://www.apache.org/licenses/LICENSE-2.0>. Of all these libraries,
only `jakarta.inject-api` ships a `NOTICE` file; its attribution is reproduced below.

### Eclipse Jakarta Dependency Injection — NOTICE (Apache 2.0)

> This content is produced and maintained by the Eclipse Jakarta Dependency Injection project.
> Project home: https://projects.eclipse.org/projects/cdi.batch
>
> Jakarta Dependency Injection is a trademark of the Eclipse Foundation.
>
> All content is the property of the respective authors or their employers. For more information
> regarding authorship of content, please consult the listed source code repository logs.
>
> Source code: https://github.com/eclipse-ee4j/injection-api

### whisper.cpp and ggml — MIT

```
MIT License

Copyright (c) 2023-2024 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to
deal in the Software without restriction, including without limitation the
rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
IN THE SOFTWARE.
```

The app also shows this notice in its terms of use (About → Terms).

### Bouncy Castle — MIT

```
Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software
and associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
```

### SQLCipher for Android — BSD-3-Clause

```
Copyright (c) 2008-2023, ZETETIC LLC
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name of the ZETETIC LLC nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY ZETETIC LLC ''AS IS'' AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL ZETETIC LLC BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Build and test tools (not shipped in the APK)

Gradle, the Android Gradle Plugin, the Android NDK and CMake, KSP, the Room and Hilt compilers, R8,
ktlint, detekt, JUnit, MockK, Turbine, Truth and the AndroidX test libraries are used to build
and test Notes Tech. They are not part of the APK.
