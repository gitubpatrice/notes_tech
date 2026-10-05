# Informativa sulla privacy — Notes Tech

**Versione 1.2.0 — settembre 2026**

## In una frase

Notes Tech non raccoglie, non trasmette e non conserva alcun dato su server remoti. Tutto resta sul suo telefono, e il database è cifrato a riposo.

## Nel dettaglio

### Dati trattati

- **Le sue note Markdown**: create e conservate esclusivamente sul suo telefono, in un database SQLite cifrato con **SQLCipher** tramite una chiave unica generata localmente (KEK di 32 byte) conservata nell'**Android Keystore**.
- **Casseforti per cartella**: ogni cassaforte che attiva usa una propria **passphrase** o un proprio **PIN**, derivati tramite **Argon2id RFC 9106** (m=64MB, t=3 per la passphrase; più leggero per il PIN, compensato da una sigillatura nel Keystore legata al dispositivo). Il contenuto delle note bloccate è cifrato con **AES-256-GCM**, con AAD legato a `note_id`. Prima di Android 9, Android non può legare questo sigillo allo sblocco del telefono: l'app offre allora solo una passphrase per una nuova cassaforte, e una cassaforte con PIN creata in precedenza non resiste più, su un telefono sequestrato, a una ricerca del suo codice.
- **Blocco dell'app (facoltativo)**: il PIN non viene mai memorizzato. Viene conservato un valore derivato da esso con **Argon2id** e legato tramite **HMAC-SHA256** a una chiave dell'**Android Keystore** che non lascia mai il telefono, quindi non può essere verificato altrove. Dopo cinque PIN errati, ogni nuovo tentativo attende più a lungo (30 secondi, raddoppiando fino a un'ora); i tentativi errati non cancellano mai nulla. Lo **sblocco con impronta digitale o volto** è gestito interamente da Android: Notes Tech non riceve alcun dato biometrico, solo la conferma che l'uso di una chiave del Keystore è stato consentito, e viene accettata solo la biometria forte (classe 3).
- **Collegamenti inversi `[[Titolo]]`**: indice invertito locale, mai trasmesso.
- **Modello di dettatura vocale (Whisper `.bin`)**: se lo procura lei stesso — l'app mostra il nome del file e la sua origine — e poi lo importa tramite il selettore di documenti di Android. Notes Tech non ha l'autorizzazione di accesso a Internet e **non offre alcun modo di scaricare nulla**. Il suo SHA-256 viene verificato all'importazione e prima di ogni caricamento.
- **Audio acquisito durante la dettatura**: scritto in un file temporaneo nello spazio privato dell'app — il motore di trascrizione legge un file, non c'è altro modo — e **cancellato appena arriva la trascrizione**. Viene cancellato anche all'avvio dell'app e dalla modalità panico, così una chiusura improvvisa non lascia nulla.
- **Impostazioni (tema, ordinamento, dettatura attivata, blocco automatico delle casseforti, opzioni del blocco dell'app)**: conservate in chiaro nelle preferenze locali (nessun dato sensibile).

### Dati NON trattati

- **Nessuna telemetria**, nessuna analisi, nessuna segnalazione di arresti anomali a terzi.
- **Nessuna pubblicità**, nessun tracker.
- **Nessun account utente**, nessuna connessione a un servizio online.

### Autorizzazioni Android richieste

Notes Tech **NON richiede l'autorizzazione `INTERNET`**. L'app è tecnicamente incapace di comunicare con un server remoto. Questa assenza è verificabile nell'`AndroidManifest.xml` del repository del codice sorgente (`tools:node="remove"` su INTERNET e ACCESS_NETWORK_STATE).

Le autorizzazioni attive sono strettamente funzionali:
- `RECORD_AUDIO` (dettatura: l'audio viene scritto in un file temporaneo privato, poi cancellato appena arriva la trascrizione).
- `USE_BIOMETRIC` e `USE_FINGERPRINT` (sblocco facoltativo del blocco dell'app con impronta digitale o volto; dichiarate dalla libreria biometrica di AndroidX — `USE_FINGERPRINT` è il nome della stessa autorizzazione su Android 8 e versioni precedenti).

Sono le **uniche** autorizzazioni richieste. La scelta del file del modello passa dal selettore di documenti di Android, che non ne richiede alcuna.

### Modalità panico

Il menu **Impostazioni → Modalità panico** cancella in blocco:
- il database SQLite cifrato (tutte le note),
- la KEK di SQLCipher (irrecuperabile),
- le chiavi del Keystore associate alle casseforti con PIN,
- le chiavi del Keystore del blocco dell'app (verifica del PIN e sblocco biometrico),
- le casseforti per cartella (passphrase e PIN),
- gli appunti, dove una nota copiata è in chiaro — ma non la cronologia che una tastiera può conservarne, fuori dalla portata di qualsiasi app,
- gli archivi di esportazione e le registrazioni di dettatura, gli unici file in chiaro dell'app,
- il modello Whisper installato nella sandbox,
- le preferenze (tranne `db_encrypted_v1` e `secure_window_enabled`, conservate per un riavvio coerente).

La cancellazione **non è atomica**, e l'ordine dei passaggi è pensato di conseguenza: la chiave di cifratura viene distrutta **prima** delle cancellazioni lunghe, poi i file in chiaro, poi il resto. Una chiusura improvvisa in qualsiasi istante lascia quindi lo stato più sicuro raggiungibile in quell'istante: nel peggiore dei casi, un database ridotto a rumore. La schermata finale indica che cosa non è riuscito e **se può restare qualcosa di leggibile**.

### I suoi diritti

I suoi dati restano sul suo telefono, sotto il suo esclusivo controllo, e l'editore non ha alcun modo di accedervi: non tratta quindi alcun dato personale che la riguardi. È il caso che la CNIL, l'autorità francese per la protezione dei dati, descrive come un «semplice software messo a disposizione dell'utente», al quale il GDPR non si applica (raccomandazione sulle applicazioni mobili, paragrafo 3.3). Mantiene il controllo dei suoi dati e in qualsiasi momento può:
- esportare le sue note in Markdown o ZIP (`Impostazioni → Esporta tutte le mie note`),
- cancellare tutti i dati con la modalità panico,
- disinstallare l'app: Android cancellerà automaticamente tutti i dati privati.

### Responsabili del trattamento

**Nessuno.** Notes Tech non usa alcun servizio di terzi durante l'esecuzione.

### Modello di dettatura vocale

- **Whisper** (modelli `.bin` di `ggerganov/whisper.cpp`): licenza MIT.

Il file che carica resta sul suo telefono. Notes Tech si limita a eseguirlo localmente con il motore `whisper.cpp` **incluso nell'app**, la cui licenza MIT è riprodotta nelle condizioni d'uso.

### Lingua

Questa informativa è stata redatta in francese; le versioni in altre lingue ne sono traduzioni. In caso di discordanza, **prevale la versione francese**.

### Contatti

Per qualsiasi domanda: **contact@files-tech.com**

---

Notes Tech è pubblicata da **Patrice Haltaya**. Codice sorgente pubblicato con licenza **Apache 2.0**.
