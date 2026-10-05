# Nutzungsbedingungen — Notes Tech

**Version 1.2.0 — Oktober 2026**

## Lizenz

Notes Tech ist freie Software, veröffentlicht unter der **Lizenz Apache 2.0**. Sie dürfen sie im Rahmen dieser Lizenz verwenden, verändern und weiterverbreiten. Der vollständige Text steht in der Datei `LICENSE` des Quellcode-Repositorys (https://github.com/gitubpatrice/notes_tech).

## Nutzung

Die App wird von einer Privatperson **kostenlos**, privat und nicht kommerziell zur Verfügung gestellt, **so, wie sie ist, ohne jegliche Gewährleistung** (Apache-Lizenz 2.0, Abschnitt 7). Wie Sie sie nutzen, liegt **allein in Ihrer Verantwortung**, ebenso wie der Inhalt Ihrer Notizen. Das Sprachdiktat beruht auf einem Modell zur automatischen Spracherkennung, das unvollkommene Transkriptionen liefern kann.

## Einschränkungen

- Notes Tech ersetzt **in keinem Fall** eine medizinische, rechtliche, finanzielle oder sonstige fachliche Beratung.
- Whisper kann falsch transkribieren, besonders in lauter Umgebung oder bei technischen Fachbegriffen.
- Die Leistung hängt von Ihrer Hardware und dem geladenen Modell ab.

## Panikmodus und Datenverlust

Der **Panikmodus** löscht Ihre Notizen, Ihren Verschlüsselungsschlüssel und Ihre Modelle endgültig und unwiderruflich. **Keine Wiederherstellung ist möglich** — das ist so gewollt. Exportieren Sie vor der Verwendung, was Sie behalten möchten, über `Einstellungen → Alle meine Notizen exportieren`.

Ebenso **macht eine vergessene Tresor-Passphrase dessen Notizen für immer unlesbar**: Die Passphrase wird nie gespeichert, sie dient nur dazu, den Schlüssel über Argon2id abzuleiten. Es gibt kein Wiederherstellungsverfahren.

Bei **PIN**-Tresoren **lösen 5 aufeinanderfolgende Fehlversuche ein automatisches Löschen aus** (Löschen des Keystore-Schlüssels). Dies entspricht dem Verhalten der üblichen Android-Displaysperre – erst ab Android 9: Davor kann Android diesen Schlüssel nicht an das Entsperren des Telefons binden, die App erstellt dort keinen PIN-Tresor, und ein zuvor erstellter PIN-Tresor hält diese Grenze von fünf Versuchen auf einem beschlagnahmten Telefon nicht ein.

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

Updates werden im offiziellen GitHub-Repository und auf F-Droid veröffentlicht. Die App aktualisiert sich nie selbst und prüft nie, ob es eine neue Version gibt: Eine neue Version zu installieren liegt bei Ihnen – oder bei Ihrem F-Droid-Client, wenn Sie es ihm erlauben. Eine über F-Droid installierte Kopie lässt sich nur über F-Droid aktualisieren, eine über GitHub installierte nur über GitHub: Beide sind nicht mit demselben Schlüssel signiert. Ein Wechsel erfordert die Deinstallation der App, die Ihre Notizen löscht – exportieren Sie sie vorher.

## Haftung

Der Herausgeber haftet nicht für direkte oder indirekte Schäden, die aus der Nutzung der App entstehen, soweit das französische Recht dies zulässt (Apache-Lizenz 2.0, Abschnitt 8). Insbesondere liegt **jeder Datenverlust infolge eines Panikmodus, einer vergessenen Passphrase, eines automatischen Löschens nach PIN-Fehlversuchen oder einer Deinstallation allein in der Verantwortung des Nutzers**. Nichts in diesen Bedingungen beschränkt eine Haftung, deren Beschränkung gesetzlich nicht zulässig ist.

## Anwendbares Recht

Diese Bedingungen unterliegen **französischem Recht**, unbeschadet der zwingenden Verbraucherschutzvorschriften Ihres Wohnsitzlandes. Soweit gesetzlich zulässig, sind bei Streitigkeiten die französischen Gerichte zuständig.

## Sprache

Diese Bedingungen wurden auf Französisch verfasst; die Fassungen in anderen Sprachen sind Übersetzungen. Bei Abweichungen ist **die französische Fassung maßgeblich**.

## Kontakt

**contact@files-tech.com**

---

Notes Tech gehört zur Suite **Files Tech**, herausgegeben von **Patrice Haltaya**.
