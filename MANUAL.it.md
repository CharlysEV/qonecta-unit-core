# Qonecta Unit CORE · Manuale utente

**Lingue:** [Español](MANUAL.md) · [Português](MANUAL.pt.md) · [English](MANUAL.en.md) · **Italiano** · [Deutsch](MANUAL.de.md)

Guida per conducenti di **Qonecta Unit CORE** (`v1.0.0-core`), sviluppata da **[CharlysEV](https://github.com/CharlysEV)** ([CharlysEV/qonecta-unit-core](https://github.com/CharlysEV/qonecta-unit-core)).

> [!IMPORTANT]
> **Edizione classica / CORE — SOLO Android Auto e Apple CarPlay:**
> Questa versione è dedicata esclusivamente a **Android Auto** e **Apple CarPlay** per garantire la massima stabilità e leggerezza. **Non include il sistema Esteso** (nessun pannello laterale, nessuna app Companion, nessun servizio in background all'avvio).

> [!WARNING]
> Esegui tutta l'installazione e la configurazione **ad auto parcheggiata**. Non toccare lo smartphone o le impostazioni avanzate durante la guida.

---

## Indice

1. [Cos'è Qonecta Unit CORE e requisiti](#1-cosè-qonecta-unit-core-e-requisiti)
2. [Installazione su Leapmotor C10](#2-installazione-su-leapmotor-c10)
3. [Connettere Android Auto (Wi-Fi 5 GHz, Hotspot o USB)](#3-connettere-android-auto-wi-fi-5-ghz-hotspot-o-usb)
4. [Connettere Apple CarPlay (Wi-Fi Direct, Hotspot o USB)](#4-connettere-apple-carplay-wi-fi-direct-hotspot-o-usb)
5. [Secondo schermo opzionale (Quadro strumenti)](#5-secondo-schermo-opzionale-quadro-strumenti)
6. [Impostazioni utili e lingua](#6-impostazioni-utili-e-lingua)
7. [Risoluzione dei problemi](#7-risoluzione-dei-problemi)
8. [Segnalare problemi su GitHub Issues](#8-segnalare-problemi-su-github-issues)
9. [Supportare il progetto e note legali](#9-supportare-il-progetto-e-note-legali)

---

## 1. Cos'è Qonecta Unit CORE e requisiti

**Qonecta Unit CORE** si installa direttamente sullo schermo Android della **Leapmotor C10** e trasforma l'unità in un ricevitore nativo per **Android Auto** e **Apple CarPlay**, senza dongle esterni né app di mirroring.

| Caratteristica | Dettaglio |
|---|---|
| **Protocolli** | **Android Auto** (smartphone Android) e **Apple CarPlay** (iPhone) |
| **Modalità di connessione** | Wi-Fi Direct 5 GHz · Hotspot · Cavo USB dati |
| **Secondo schermo (Quadro)** | Opzionale (`Quadro: OFF` di default per sicurezza; attivabile con 1 tocco) |
| **Edizione CORE** | **Senza sistema Esteso**, nessuna cattura microfono (`RECORD_AUDIO` rimosso), nessun servizio all'avvio |
| **Lingue dei menu** | Italiano, Español, Português, English, Deutsch (selezionabile anche nelle Impostazioni) |

**Cosa serve:**
- Una **Leapmotor C10** (o unità Android compatibile) abilitata all'installazione di APK.
- Una **chiavetta USB** (FAT32/exFAT) per la prima installazione o gli aggiornamenti.
- Il tuo smartphone **Android** (con *Android Auto* aggiornato) o il tuo **iPhone**.

---

## 2. Installazione su Leapmotor C10

1. Scarica `QonectaUnit_1.0.0-core.apk` da **[GitHub Releases](https://github.com/CharlysEV/qonecta-unit-core/releases)**.
2. Copia il file APK su una chiavetta USB e inseriscila nella **porta USB dati** dell'auto.
3. Apri il file manager dell'auto (o il browser) e tocca il file `.apk` per installarlo.
4. Avvia **Qonecta Unit CORE** e concedi i permessi richiesti (**Dispositivi vicini / Bluetooth** e **Posizione** per Wi-Fi Direct).
5. Per futuri aggiornamenti tocca direttamente il pulsante **«File»** nella barra superiore dell'app.

---

## 3. Connettere Android Auto (Wi-Fi 5 GHz, Hotspot o USB)

Nella schermata principale puoi attivare una o più modalità contemporaneamente:

### A. Wi-Fi 5 GHz (consigliato, senza fili)
1. Associa il telefono via **Bluetooth** all'auto e tieni acceso il **Wi-Fi dell'auto**.
2. Lascia selezionata la scheda **«Wi-Fi 5 GHz»**.
3. Tocca **«Connetti Android Auto»** oppure tocca direttamente il tuo telefono nella fila **«Telefoni associati»**.
4. In caso di disconnessioni, disattiva *«Passa a dati mobili»* nelle impostazioni Wi-Fi del telefono.

### B. Hotspot mobile (Internet in auto + Android Auto)
1. Attiva l'Hotspot sullo smartphone e collega il Wi-Fi dell'auto a quella rete.
2. Attiva **«Hotspot mobile»** e tocca **«Connetti Android Auto»**.

### C. Cavo USB (Diretto + Ricarica)
1. Lascia attiva la scheda **«Cavo USB»** (compatibile anche con **Wi-Fi 5 GHz** attivo).
2. Collega il telefono alla **porta USB dati** dell'auto con un **cavo USB dati**.
3. Lo schermo mostra subito `🔌 USB pronto` e si connette automaticamente — oppure tocca **«Connetti USB»**.

---

## 4. Connettere Apple CarPlay (Wi-Fi Direct, Hotspot o USB)

Tocca **«Apple CarPlay»** nella schermata iniziale (o collega l'iPhone via USB):

### A. CarPlay wireless (Wi-Fi Direct 5 GHz — consigliato)
1. Associa prima l'**iPhone via Bluetooth** all'auto e tieni attivi Bluetooth e Wi-Fi sull'iPhone.
2. Nella schermata CarPlay tocca il tuo iPhone in **«iPhone associati»** oppure tocca **«Connetti CarPlay»**.
3. Consenti **CarPlay** sull'iPhone al primo avvio.

### B. Hotspot auto (alternativa)
Se Wi-Fi Direct risulta instabile, scegli **«Hotspot auto»**, inserisci nome e password dell'Hotspot dell'auto e spegni il client Wi-Fi nella barra superiore della C10.

### C. CarPlay via Cavo USB
1. Collega l'iPhone alla **porta USB dati** dell'auto con un cavo dati.
2. Tocca **«CarPlay USB»** (si evidenzia automaticamente al rilevamento dell'iPhone).
3. Sblocca l'iPhone e accetta **«Autorizza»** e **«CarPlay»**.
4. Al primo utilizzo Android potrebbe richiedere un **tunnel VPN locale** (esclusivamente locale tra porta USB e schermo, nessun traffico verso Internet).

> **Suggerimento in CarPlay:** scorri verso il basso con **tre dita** per aprire le impostazioni di CarPlay.

---

## 5. Secondo schermo opzionale (Quadro strumenti)

Per la massima sicurezza il secondo schermo è **spento di default (`Quadro: OFF`)**.
- Tocca **`Quadro: OFF`** nella barra superiore (sia in Android Auto sia in CarPlay) per impostare **`Quadro: ON`** e vedere la mappa di navigazione sul quadro strumenti della C10.
- Si applica alla **prossima connessione**.

---

## 6. Impostazioni utili e lingua

- **Lingua:** apri **Impostazioni → Lingua** per scegliere **Italiano, Español, Português, English, Deutsch** o la lingua di sistema (valido per Android Auto e CarPlay).
- **Dimensione schermo / DPI:** configurabile in **Impostazioni** (Android Auto) o **Impostazioni CarPlay → Dimensione icone e testo**.
- **Chiusura pulita:** il pulsante **«Chiudi»** arresta completamente Android Auto, CarPlay e quadro senza lasciare nulla in background.

---

## 7. Risoluzione dei problemi

- **Cavo USB non rilevato:** verifica di usare un **cavo dati** collegato alla **porta USB dati** dell'auto.
- **Wi-Fi 5 GHz non si avvia da solo:** tocca il tuo telefono in **«Telefoni associati»** e disattiva il cambio rete automatico nelle impostazioni Wi-Fi dello smartphone.
- **CarPlay Wi-Fi Direct bloccato:** vai in **Impostazioni CarPlay → Guida e reset Wi-Fi CarPlay → Reimposta Wi-Fi CarPlay**.

---

## 8. Segnalare problemi su GitHub Issues

Se riscontri errori, problemi di connessione o hai suggerimenti, apri una segnalazione su **GitHub Issues**:

👉 **[https://github.com/CharlysEV/qonecta-unit-core/issues](https://github.com/CharlysEV/qonecta-unit-core/issues)**

*(Puoi anche aprire **Impostazioni → Segnala un problema** nell'app e scansionare il codice QR con il telefono).*

Indica: modello auto e firmware, modello smartphone e versione Android/iOS, protocollo (**Android Auto** o **CarPlay**), modalità (**Wi-Fi 5 GHz**, **Hotspot** o **USB**) e allega se possibile il log esportato.

---

## 9. Supportare il progetto e note legali

**Qonecta Unit CORE** è gratuito, open source e senza pubblicità (accessibile anche dal tasto **«☕ Supporta»** con QR offline):

- ☕ **Ko-fi:** [https://ko-fi.com/charlysev](https://ko-fi.com/charlysev)
- 💙 **PayPal:** [https://www.paypal.me/CharlysEV](https://www.paypal.me/CharlysEV)

Licenza AGPL-3.0 / GPL-3.0. Progetto comunitario indipendente non affiliato a Leapmotor, Google o Apple. Guida sempre in sicurezza.
