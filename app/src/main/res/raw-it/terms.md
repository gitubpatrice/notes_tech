# Condizioni d'uso — Notes Tech

**Versione 1.2.0 — ottobre 2026**

## Licenza

Notes Tech è software libero pubblicato con **licenza Apache 2.0**. Può usarlo, modificarlo e ridistribuirlo nei termini di tale licenza. Il testo completo è disponibile nel file `LICENSE` del repository del codice sorgente (https://github.com/gitubpatrice/notes_tech).

## Uso

L'app è messa a disposizione **gratuitamente** da un privato, a titolo personale e non commerciale, ed è fornita **così com'è, senza garanzie di alcun tipo** (licenza Apache 2.0, sezione 7). L'uso che ne fa è di **sua esclusiva responsabilità**, come il contenuto delle sue note. La dettatura vocale si basa su un modello di riconoscimento automatico del parlato, che può produrre trascrizioni imperfette.

## Limitazioni

- Notes Tech **non sostituisce in alcun caso** una consulenza medica, legale, finanziaria o professionale.
- Whisper può trascrivere in modo errato, soprattutto in ambienti rumorosi o con termini tecnici specialistici.
- Le prestazioni dipendono dal suo hardware e dal modello caricato.

## Modalità panico e perdita di dati

La **modalità panico** cancella in modo definitivo e irreversibile le sue note, la sua chiave di cifratura e i suoi modelli. **Nessun recupero è possibile**: è voluto. Prima di usarla, esporti ciò che vuole conservare con `Impostazioni → Esporta tutte le mie note`.

Allo stesso modo, **dimenticare la passphrase di una cassaforte rende le sue note illeggibili per sempre**: la passphrase non viene mai memorizzata, serve solo a derivare la chiave tramite Argon2id. Non esiste alcuna procedura di recupero.

Per le casseforti con **PIN**, **5 errori consecutivi attivano una cancellazione automatica** (eliminazione della chiave del Keystore). È lo stesso comportamento del normale blocco schermo di Android, ma solo da Android 9: prima, Android non può legare questa chiave allo sblocco del telefono, l'app non vi crea casseforti con PIN, e una cassaforte con PIN creata in precedenza non rispetta questo limite di cinque tentativi su un telefono sequestrato.

## Modello di dettatura vocale

L'app è compatibile con:
- i modelli **Whisper** GGML `.bin`: licenza MIT, origine `ggerganov/whisper.cpp`

È sua responsabilità rispettare tali licenze.

## Componenti di terzi inclusi nell'app

La dettatura vocale funziona interamente sul suo telefono, con un motore di trascrizione **incluso nell'app**: `whisper.cpp` e `ggml`, versione 1.8.3, pubblicati con licenza MIT. Tale licenza richiede che la sua nota accompagni ogni copia del software; è quindi riprodotta qui sotto nella sua versione originale in inglese, e il codice sorgente corrispondente si trova nel repository del codice sorgente, in `app/src/main/cpp/vendor/whisper/`.

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

## Dati

Tutte le sue note sono conservate **localmente e cifrate** sul suo telefono (veda l'**Informativa sulla privacy**). Notes Tech non invia nulla tramite Internet e non ha alcuna autorizzazione tecnica per farlo (nessuna autorizzazione Android `INTERNET`).

## Aggiornamenti

Gli aggiornamenti sono pubblicati sul repository GitHub ufficiale e su F-Droid. L'app non si aggiorna mai da sola e non verifica mai la disponibilità di aggiornamenti: installare una nuova versione spetta a lei, o al suo client F-Droid se lo autorizza. Una copia installata da F-Droid si aggiorna solo da F-Droid, e una installata da GitHub solo da GitHub: non sono firmate con la stessa chiave. Passare dall'una all'altra richiede di disinstallare l'app, il che cancella le sue note: le esporti prima.

## Responsabilità

L'editore non può essere ritenuto responsabile di alcun danno diretto o indiretto derivante dall'uso dell'app, nei limiti consentiti dalla legge francese (licenza Apache 2.0, sezione 8). In particolare, **qualsiasi perdita di dati conseguente a una modalità panico, a una passphrase dimenticata, a una cancellazione automatica per errori di PIN o a una disinstallazione è di esclusiva responsabilità dell'utente**. Nulla in queste condizioni limita una responsabilità che la legge non consente di limitare.

## Legge applicabile

Condizioni regolate dalla **legge francese**, fatte salve le norme imperative a tutela dei consumatori del suo paese di residenza. Ove la legge lo consenta, in caso di controversia sono competenti i tribunali francesi.

## Lingua

Queste condizioni sono state redatte in francese; le versioni in altre lingue ne sono traduzioni. In caso di discordanza, **prevale la versione francese**.

## Contatti

**contact@files-tech.com**

---

Notes Tech fa parte della suite **Files Tech**, pubblicata da **Patrice Haltaya**.
