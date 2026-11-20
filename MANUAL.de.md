# Qonecta Unit CORE · Benutzerhandbuch

**Sprachen:** [Español](MANUAL.md) · [Português](MANUAL.pt.md) · [English](MANUAL.en.md) · [Italiano](MANUAL.it.md) · **Deutsch**

Anleitung für Fahrer von **Qonecta Unit CORE** (`v1.0.0-core`), entwickelt von **[CharlysEV](https://github.com/CharlysEV)** ([CharlysEV/qonecta-unit-core](https://github.com/CharlysEV/qonecta-unit-core)).

> [!IMPORTANT]
> **Klassische / CORE-Edition — NUR Android Auto & Apple CarPlay:**
> Diese Version konzentriert sich ausschließlich auf **Android Auto** und **Apple CarPlay** für maximale Stabilität und Leichtgewichtigkeit. **Sie enthält KEIN erweitertes System** (kein Seitenpanel, keine Fahrzeugtelemetrie, keine Companion-App, keine Autostart-Dienste im Hintergrund).

> [!WARNING]
> Führe Installation und Einrichtung **nur im geparkten Fahrzeug** durch. Bediene Smartphone oder erweiterte Einstellungen niemals während der Fahrt.

---

## Inhaltsverzeichnis

1. [Was ist Qonecta Unit CORE & Voraussetzungen](#1-was-ist-qonecta-unit-core--voraussetzungen)
2. [Installation im Leapmotor C10](#2-installation-im-leapmotor-c10)
3. [Android Auto verbinden (WLAN 5 GHz, Hotspot oder USB)](#3-android-auto-verbinden-wlan-5-ghz-hotspot-oder-usb)
4. [Apple CarPlay verbinden (Wi-Fi Direct, Hotspot oder USB)](#4-apple-carplay-verbinden-wi-fi-direct-hotspot-oder-usb)
5. [Optionaler Zweitbildschirm (Kombiinstrument / Tacho)](#5-optionaler-zweitbildschirm-kombiinstrument--tacho)
6. [Nützliche Einstellungen & Sprache](#6-nützliche-einstellungen--sprache)
7. [Fehlerbehebung](#7-fehlerbehebung)
8. [Probleme & Feedback in GitHub Issues melden](#8-probleme--feedback-in-github-issues-melden)
9. [Projekt unterstützen & rechtlicher Hinweis](#9-projekt-unterstützen--rechtlicher-hinweis)

---

## 1. Was ist Qonecta Unit CORE & Voraussetzungen

**Qonecta Unit CORE** wird direkt auf dem Android-Display des **Leapmotor C10** installiert und macht das Display zu einem nativen Empfänger für **Android Auto** und **Apple CarPlay** — ganz ohne externen Dongle oder Spiegel-App.

| Eigenschaft | Details |
|---|---|
| **Protokolle** | **Android Auto** (Android-Smartphones) & **Apple CarPlay** (iPhone) |
| **Verbindungsarten** | Wi-Fi Direct 5 GHz · Hotspot · USB-Datenkabel |
| **Zweitbildschirm (Tacho)** | Optional (`Tacho: AUS` als sicherer Standard; mit 1 Tipp aktivierbar) |
| **CORE-Edition** | **Ohne erweitertes System**, ohne Mikrofon-Zugriff (`RECORD_AUDIO` entfernt), ohne Hintergrund-Autostart |
| **Menüsprachen** | Deutsch, Español, Português, English, Italiano (manuell in den Einstellungen wählbar) |

**Was du brauchst:**
- Einen **Leapmotor C10** (oder eine kompatible Android-Headunit), auf dem APKs installiert werden können.
- Einen **USB-Stick** (FAT32/exFAT) für die Erstinstallation bzw. Updates.
- Dein **Android-Smartphone** (mit aktueller *Android Auto*-App) oder dein **iPhone**.

---

## 2. Installation im Leapmotor C10

1. Lade `QonectaUnit_1.0.0-core.apk` unter **[GitHub Releases](https://github.com/CharlysEV/qonecta-unit-core/releases)** herunter.
2. Kopiere die APK auf einen USB-Stick und stecke ihn in den **USB-Datenanschluss** des Fahrzeugs.
3. Öffne den Dateimanager des Fahrzeugs (oder den Browser) und tippe zum Installieren auf die `.apk`-Datei.
4. Starte **Qonecta Unit CORE** und erteile die angeforderten Berechtigungen (**Geräte in der Nähe / Bluetooth** und **Standort** für Wi-Fi Direct).
5. Für spätere Updates reicht ein Tipp auf **«Dateien»** oben in der Leiste von Qonecta Unit CORE.

---

## 3. Android Auto verbinden (WLAN 5 GHz, Hotspot oder USB)

Auf dem Startbildschirm kannst du einen oder mehrere Modi gleichzeitig aktiv lassen:

### A. WLAN 5 GHz (empfohlen, kabellos)
1. Kopple dein Android-Smartphone per **Bluetooth** mit dem Fahrzeug und lasse das **Fahrzeug-WLAN eingeschaltet**.
2. Lasse die Kachel **«WLAN 5 GHz»** aktiviert.
3. Tippe auf **«Android Auto verbinden»** oder direkt auf dein Gerät unten unter **«Gekoppelte Smartphones»**.
4. Falls die Verbindung abbricht, deaktiviere in den WLAN-Einstellungen des Smartphones Optionen wie *«Zu mobilen Daten wechseln»* / *«Intelligentes WLAN»*.

### B. Mobiler Hotspot (Internet im Auto + Android Auto)
1. Aktiviere den mobilen Hotspot am Smartphone und verbinde das Fahrzeug-WLAN damit.
2. Aktiviere **«Mobiler Hotspot»** und tippe auf **«Android Auto verbinden»**.

### C. USB-Kabel (Direkt + Laden)
1. Lasse **«USB-Kabel»** aktiviert (funktioniert problemlos parallel zu **WLAN 5 GHz**).
2. Verbinde dein Smartphone mit einem **USB-Datenkabel** am **USB-Datenanschluss** des Fahrzeugs (nicht an reinen Ladeports).
3. Auf dem Startbildschirm erscheint sofort `🔌 USB bereit` und die Verbindung startet automatisch — oder tippe auf **«USB verbinden»**.
4. Steckst du das Kabel während einer laufenden WLAN-Sitzung an, fragt die App, ob auf USB gewechselt werden soll.

---

## 4. Apple CarPlay verbinden (Wi-Fi Direct, Hotspot oder USB)

Tippe auf dem Startbildschirm auf **«Apple CarPlay»** (oder stecke dein iPhone direkt per USB an):

### A. Kabelloses CarPlay (Wi-Fi Direct 5 GHz — empfohlen)
1. Kopple dein **iPhone per Bluetooth** mit dem Auto und lasse Bluetooth und WLAN am iPhone eingeschaltet.
2. Tippe auf dem CarPlay-Bildschirm unten unter **«Gekoppelte iPhones»** auf dein iPhone oder auf **«CarPlay verbinden»**.
3. Bestätige beim ersten Mal die **CarPlay**-Anfrage auf dem iPhone.

### B. Fahrzeug-Hotspot (Alternative)
Falls Wi-Fi Direct auf deiner Firmware instabil ist, wähle **«Fahrzeug-Hotspot»**, trage Name und Passwort des Fahrzeug-Hotspots ein und schalte den WLAN-Client oben in der C10-Leiste aus.

### C. CarPlay per USB-Kabel
1. Verbinde dein iPhone mit einem Datenkabel am **USB-Datenanschluss** des Fahrzeugs.
2. Tippe auf **«CarPlay USB»** (wird automatisch hervorgehoben, sobald ein iPhone erkannt wird).
3. Entsperre dein iPhone und tippe auf **«Vertrauen»** sowie **«CarPlay»**.
4. Beim ersten Start bittet Android ggf. um Erlaubnis für einen **lokalen VPN-Tunnel** (rein lokal zwischen USB-Port und Display, keinerlei Internet-Traffic).

> **Tipp in CarPlay:** Wische jederzeit mit **drei Fingern nach unten**, um die CarPlay-Einstellungen zu öffnen.

---

## 5. Optionaler Zweitbildschirm (Kombiinstrument / Tacho)

Für maximale Sicherheit ist der Zweitbildschirm **standardmäßig ausgeschaltet (`Tacho: AUS`)**.
- Tippe oben in der Leiste (in Android Auto oder CarPlay) auf **`Tacho: AUS`**, um auf **`Tacho: AN`** umzuschalten und die Live-Navigationskarte im Kombiinstrument des C10 anzuzeigen.
- Die Änderung wird ab der **nächsten Verbindung** aktiv.
- Rein ereignisgesteuert ohne 1-Hz-Polling oder OEM-Telemetrie.

---

## 6. Nützliche Einstellungen & Sprache

- **Sprache wählen:** unter **Einstellungen → Sprache** kannst du **Deutsch, Español, Português, English, Italiano** oder Systemsprache auswählen (gilt für Android Auto **und** Apple CarPlay).
- **Anzeigegröße / DPI:** einstellbar in den **Einstellungen** (Android Auto) bzw. unter **CarPlay-Einstellungen → Symbol- & Textgröße**.
- **Sauberes Beenden:** **«Schließen»** auf dem Startbildschirm beendet Android Auto, CarPlay und den Zweitbildschirm vollständig ohne Hintergrundprozesse.

---

## 7. Fehlerbehebung

- **USB-Kabel wird nicht erkannt:** stelle sicher, dass ein **Datenkabel** am **USB-Datenport** des Fahrzeugs genutzt wird.
- **WLAN 5 GHz weckt das Smartphone nicht automatisch:** tippe unten auf dein Gerät unter **«Gekoppelte Smartphones»** und deaktiviere automatischen Netzwechsel in den Smartphone-WLAN-Einstellungen.
- **CarPlay Wi-Fi Direct hängt:** öffne **CarPlay-Einstellungen → Hilfe & CarPlay-WLAN zurücksetzen → CarPlay-WLAN zurücksetzen**.
- **Kein Ton:** prüfe die Audioquelle im Fahrzeug (Bluetooth/Medien) und die Lautstärke am Smartphone.

---

## 8. Probleme & Feedback in GitHub Issues melden

Wenn du Fehler, Verbindungsprobleme oder Verbesserungsvorschläge findest, **melde sie bitte direkt im Bereich GitHub Issues**:

👉 **[https://github.com/CharlysEV/qonecta-unit-core/issues](https://github.com/CharlysEV/qonecta-unit-core/issues)**

*(Du kannst auch direkt in der App unter **Einstellungen → Problem melden** den Offline-QR-Code mit dem Smartphone scannen).*

Bitte gib dabei möglichst an:
1. Fahrzeugmodell & Firmware-Version (z. B. Leapmotor C10).
2. Smartphone-Modell & Android-/iOS-Version.
3. Ob **Android Auto** oder **Apple CarPlay** betroffen ist und welcher Modus (**WLAN 5 GHz**, **Hotspot** oder **USB**) genutzt wurde.
4. Falls möglich das exportierte Protokoll aus **Einstellungen → Debug → Protokolle exportieren** (Android Auto) oder **CarPlay-Einstellungen → Diagnosebericht speichern**.

---

## 9. Projekt unterstützen & rechtlicher Hinweis

**Qonecta Unit CORE** ist kostenlos, Open Source und werbefrei. Wenn dir die App gefällt (auch offline über **«☕ Unterstützen»** mit QR-Codes in der App erreichbar):

- ☕ **Ko-fi:** [https://ko-fi.com/charlysev](https://ko-fi.com/charlysev)
- 💙 **PayPal:** [https://www.paypal.me/CharlysEV](https://www.paypal.me/CharlysEV)

Open Source unter AGPL-3.0 / GPL-3.0. Unabhängiges Community-Projekt, nicht verbunden mit Leapmotor, Google oder Apple. Bitte immer sicher fahren!
