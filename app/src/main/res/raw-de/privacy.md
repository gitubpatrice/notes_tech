# Datenschutzerklärung — Notes Tech

**Version 1.2.0 — September 2026**

## In einem Satz

Notes Tech erhebt, überträgt und speichert keinerlei Daten auf entfernten Servern. Alles bleibt auf Ihrem Telefon, und die Datenbank ist im Ruhezustand verschlüsselt.

## Im Einzelnen

### Verarbeitete Daten

- **Ihre Markdown-Notizen**: ausschließlich auf Ihrem Telefon erstellt und gespeichert, in einer SQLite-Datenbank, die mit **SQLCipher** verschlüsselt ist — mit einem eindeutigen, lokal erzeugten Schlüssel (32-Byte-KEK), der im **Android Keystore** aufbewahrt wird.
- **Tresore pro Ordner**: Jeder Tresor, den Sie aktivieren, verwendet eine eigene **Passphrase** oder **PIN**, abgeleitet über **Argon2id RFC 9106** (m=64MB, t=3 für die Passphrase; leichter für die PIN, ausgeglichen durch eine an das Gerät gebundene Versiegelung im Keystore). Der Inhalt gesperrter Notizen wird mit **AES-256-GCM** verschlüsselt, AAD an `note_id` gebunden.
- **App-Sperre (optional)**: Die PIN selbst wird nie gespeichert. Aufbewahrt wird ein mit **Argon2id** daraus abgeleiteter Wert, der per **HMAC-SHA256** an einen Schlüssel des **Android Keystore** gebunden ist, der das Telefon nie verlässt — er kann also nirgendwo anders geprüft werden. Nach fünf falschen PINs wartet jeder neue Versuch länger (30 Sekunden, verdoppelt bis zu einer Stunde); falsche Versuche löschen nie etwas. **Entsperren per Fingerabdruck oder Gesicht** wird vollständig von Android übernommen: Notes Tech erhält keine biometrischen Daten, nur die Bestätigung, dass ein Keystore-Schlüssel verwendet werden durfte, und nur starke Biometrie (Klasse 3) wird akzeptiert.
- **Rückverweise `[[Titel]]`**: lokaler invertierter Index, nie übertragen.
- **Diktiermodell (Whisper `.bin`)**: Sie beschaffen es selbst — die App zeigt den Dateinamen und seine Quelle an — und importieren es dann über die Android-Dokumentauswahl. Notes Tech hat keine Internetberechtigung und **bietet keine Möglichkeit, etwas herunterzuladen**. Sein SHA-256 wird beim Import und vor jedem Laden geprüft.
- **Beim Diktat aufgenommenes Audio**: in eine temporäre Datei im privaten Speicher der App geschrieben — die Transkriptions-Engine liest eine Datei, anders geht es nicht — und **gelöscht, sobald die Transkription vorliegt**. Es wird außerdem beim Start der App und durch den Panikmodus gelöscht, sodass ein abruptes Beenden nichts zurücklässt.
- **Einstellungen (Design, Sortierung, aktiviertes Diktat, automatische Tresorsperre, Optionen der App-Sperre)**: unverschlüsselt in den lokalen Einstellungen gespeichert (keine sensiblen Daten).

### NICHT verarbeitete Daten

- **Keine Telemetrie**, keine Analyse, kein Absturzbericht an Dritte.
- **Keine Werbung**, kein Tracker.
- **Kein Benutzerkonto**, keine Verbindung zu einem Onlinedienst.

### Angeforderte Android-Berechtigungen

Notes Tech fordert **KEINE `INTERNET`-Berechtigung** an. Die App ist technisch nicht in der Lage, mit einem entfernten Server zu kommunizieren. Dieses Fehlen lässt sich in der `AndroidManifest.xml` des Quellcode-Repositorys überprüfen (`tools:node="remove"` für INTERNET und ACCESS_NETWORK_STATE).

Die aktiven Berechtigungen dienen ausschließlich der Funktion:
- `RECORD_AUDIO` (Diktat: Das Audio wird in eine private temporäre Datei geschrieben und gelöscht, sobald die Transkription vorliegt).
- `USE_BIOMETRIC` und `USE_FINGERPRINT` (optionales Entsperren der App-Sperre per Fingerabdruck oder Gesicht; von der AndroidX-Biometrie-Bibliothek deklariert — `USE_FINGERPRINT` ist der Name derselben Berechtigung unter Android 8 und älter).

Dies sind die **einzigen** angeforderten Berechtigungen. Die Auswahl der Modelldatei erfolgt über die Android-Dokumentauswahl, die keine benötigt.

### Panikmodus

Das Menü **Einstellungen → Panikmodus** löscht in einem Durchgang:
- die verschlüsselte SQLite-Datenbank (alle Notizen),
- den SQLCipher-KEK (nicht wiederherstellbar),
- die Keystore-Schlüssel der PIN-Tresore,
- die Keystore-Schlüssel der App-Sperre (PIN-Prüfung und biometrisches Entsperren),
- die Tresore pro Ordner (Passphrasen und PINs),
- die Zwischenablage, in der eine kopierte Notiz unverschlüsselt liegt,
- Exportarchive und Diktataufnahmen, die einzigen unverschlüsselten Dateien der App,
- das in der Sandbox installierte Whisper-Modell,
- die Einstellungen (außer `db_encrypted_v1` und `secure_window_enabled`, die für einen konsistenten Neustart erhalten bleiben).

Das Löschen ist **nicht atomar**, und die Reihenfolge der Schritte ist darauf ausgelegt: Der Verschlüsselungsschlüssel wird **vor** den langen Löschvorgängen zerstört, dann die unverschlüsselten Dateien, dann der Rest. Ein abruptes Beenden zu jedem beliebigen Zeitpunkt hinterlässt daher den sichersten Zustand, der zu diesem Zeitpunkt erreichbar ist — schlimmstenfalls eine auf Rauschen reduzierte Datenbank. Der letzte Bildschirm nennt, was fehlgeschlagen ist, und **ob etwas Lesbares übrig bleiben kann**.

### Ihre Rechte

Da alle Daten ausschließlich lokal sind, gilt die DSGVO zwischen Ihnen und Ihrem Telefon. Sie können jederzeit:
- Ihre Notizen als Markdown oder ZIP exportieren (`Einstellungen → Alle meine Notizen exportieren`),
- alle Daten über den Panikmodus löschen,
- die App deinstallieren — Android löscht dann automatisch alle privaten Daten.

### Auftragsverarbeiter

**Keine.** Notes Tech nutzt zur Laufzeit keinen Dienst eines Dritten.

### Diktiermodell

- **Whisper** (`.bin`-Modelle aus `ggerganov/whisper.cpp`): MIT-Lizenz.

Die Datei, die Sie laden, bleibt auf Ihrem Telefon. Notes Tech führt sie lediglich lokal aus, mit der **in die App integrierten** Engine `whisper.cpp` — deren MIT-Lizenz ist in den Nutzungsbedingungen wiedergegeben.

### Sprache

Diese Datenschutzerklärung wurde auf Französisch verfasst; die Fassungen in anderen Sprachen sind Übersetzungen. Bei Abweichungen ist **die französische Fassung maßgeblich**.

### Kontakt

Für alle Fragen: **contact@files-tech.com**

---

Notes Tech wird von **Patrice Haltaya** herausgegeben. Quellcode veröffentlicht unter der Lizenz **Apache 2.0**.
