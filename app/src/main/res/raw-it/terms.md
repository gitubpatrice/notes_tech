# Condizioni d'uso — Notes Tech

**Versione 1.0.0 — maggio 2026**

## Licenza

Notes Tech è software libero pubblicato con **licenza Apache 2.0**. Può usarlo, modificarlo e ridistribuirlo nei termini di tale licenza. Il testo completo è disponibile nel file `LICENSE` del repository del codice sorgente (https://github.com/gitubpatrice/notes_tech).

## Uso

L'app è fornita **così com'è, senza garanzie di alcun tipo**. La dettatura vocale si basa su un modello di riconoscimento automatico del parlato, che può produrre trascrizioni imperfette. Lei resta l'unico responsabile del contenuto delle sue note.

## Limitazioni

- Notes Tech **non sostituisce in alcun caso** una consulenza medica, legale, finanziaria o professionale.
- Whisper può trascrivere in modo errato, soprattutto in ambienti rumorosi o con termini tecnici specialistici.
- Le prestazioni dipendono dal suo hardware e dal modello caricato.

## Modalità panico e perdita di dati

La **modalità panico** cancella in modo definitivo e irreversibile le sue note, la sua chiave di cifratura e i suoi modelli. **Nessun recupero è possibile**: è voluto. Prima di usarla, esporti ciò che vuole conservare con `Impostazioni → Esporta tutte le mie note`.

Allo stesso modo, **dimenticare la passphrase di una cassaforte rende le sue note illeggibili per sempre**: la passphrase non viene mai memorizzata, serve solo a derivare la chiave tramite Argon2id. Non esiste alcuna procedura di recupero.

Per le casseforti con **PIN**, **5 errori consecutivi attivano una cancellazione automatica** (eliminazione della chiave del Keystore). È lo stesso comportamento del normale blocco schermo di Android.

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

Gli aggiornamenti sono distribuiti tramite il repository GitHub ufficiale. Nessun aggiornamento automatico: spetta a lei installare la nuova versione.

## Responsabilità

L'editore non può essere ritenuto responsabile di alcun danno diretto o indiretto derivante dall'uso dell'app, nei limiti consentiti dalla legge francese. In particolare, **qualsiasi perdita di dati conseguente a una modalità panico, a una passphrase dimenticata, a una cancellazione automatica per errori di PIN o a una disinstallazione è di esclusiva responsabilità dell'utente**.

## Legge applicabile

Condizioni regolate dalla **legge francese**. In caso di controversia sono competenti i tribunali francesi.

## Contatti

**contact@files-tech.com**

---

Notes Tech fa parte della suite **Files Tech**, pubblicata da **Patrice Haltaya**.
