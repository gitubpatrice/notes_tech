# Nutzungsbedingungen — Notes Tech

**Version 1.0.0 — Mai 2026**

## Lizenz

Notes Tech ist freie Software, veröffentlicht unter der **Lizenz Apache 2.0**. Sie dürfen sie im Rahmen dieser Lizenz verwenden, verändern und weiterverbreiten. Der vollständige Text steht in der Datei `LICENSE` des Quellcode-Repositorys (https://github.com/gitubpatrice/notes_tech).

## Nutzung

Die App wird **so, wie sie ist, ohne jegliche Gewährleistung** bereitgestellt. Das Sprachdiktat beruht auf einem Modell zur automatischen Spracherkennung, das unvollkommene Transkriptionen liefern kann. Für den Inhalt Ihrer Notizen bleiben allein Sie verantwortlich.

## Einschränkungen

- Notes Tech ersetzt **in keinem Fall** eine medizinische, rechtliche, finanzielle oder sonstige fachliche Beratung.
- Whisper kann falsch transkribieren, besonders in lauter Umgebung oder bei technischen Fachbegriffen.
- Die Leistung hängt von Ihrer Hardware und dem geladenen Modell ab.

## Panikmodus und Datenverlust

Der **Panikmodus** löscht Ihre Notizen, Ihren Verschlüsselungsschlüssel und Ihre Modelle endgültig und unwiderruflich. **Keine Wiederherstellung ist möglich** — das ist so gewollt. Exportieren Sie vor der Verwendung, was Sie behalten möchten, über `Einstellungen → Alle meine Notizen exportieren`.

Ebenso **macht eine vergessene Tresor-Passphrase dessen Notizen für immer unlesbar**: Die Passphrase wird nie gespeichert, sie dient nur dazu, den Schlüssel über Argon2id abzuleiten. Es gibt kein Wiederherstellungsverfahren.

Bei **PIN**-Tresoren **lösen 5 aufeinanderfolgende Fehlversuche ein automatisches Löschen aus** (Löschen des Keystore-Schlüssels). Dies entspricht dem Verhalten der üblichen Android-Displaysperre.

## Diktiermodell

Die App ist kompatibel mit:
- **Whisper**-Modellen im GGML-Format `.bin` — MIT-Lizenz, Quelle `ggerganov/whisper.cpp`

Sie sind dafür verantwortlich, diese Lizenzen einzuhalten.

## In die App integrierte Komponenten Dritter

Das Sprachdiktat läuft vollständig auf Ihrem Telefon, mit einer **in der App enthaltenen** Transkriptions-Engine: `whisper.cpp` und `ggml`, Version 1.8.3, veröffentlicht unter der MIT-Lizenz. Diese Lizenz verlangt, dass ihr Hinweis jeder Kopie der Software beiliegt; sie ist daher unten in ihrer englischen Originalfassung wiedergegeben, und der zugehörige Quellcode liegt im Quellcode-Repository unter `app/src/main/cpp/vendor/whisper/`.

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

## Daten

Alle Ihre Notizen werden **lokal und verschlüsselt** auf Ihrem Telefon gespeichert (siehe **Datenschutzerklärung**). Notes Tech sendet nichts über das Internet und hat dafür keine technische Berechtigung (keine Android-Berechtigung `INTERNET`).

## Updates

Updates werden über das offizielle GitHub-Repository verteilt. Keine automatische Aktualisierung: Es liegt bei Ihnen, die neue Version zu installieren.

## Haftung

Der Herausgeber haftet nicht für direkte oder indirekte Schäden, die aus der Nutzung der App entstehen, soweit das französische Recht dies zulässt. Insbesondere liegt **jeder Datenverlust infolge eines Panikmodus, einer vergessenen Passphrase, eines automatischen Löschens nach PIN-Fehlversuchen oder einer Deinstallation allein in der Verantwortung des Nutzers**.

## Anwendbares Recht

Diese Bedingungen unterliegen **französischem Recht**. Bei Streitigkeiten sind die französischen Gerichte zuständig.

## Kontakt

**contact@files-tech.com**

---

Notes Tech gehört zur Suite **Files Tech**, herausgegeben von **Patrice Haltaya**.
