# 🚗📱 Schritt-für-Schritt-Anleitung · Qonecta Unit & Qonecta Companion (`v0.1.18`)

<p align="center">
  🇩🇪 <b>Deutsch</b> ·
  <a href="MANUAL_INSTALLATION_QONECTA_UNIT_C10.en.md">🇬🇧 English</a> ·
  <a href="MANUAL_INSTALACION_QONECTA_UNIT_C10.md">🇪🇸 Español</a>
</p>

Verständliche Schritt-für-Schritt-Anleitung zur Installation, Kopplung und Aktualisierung von **Qonecta Unit** (die App direkt auf dem Display des **Leapmotor C10**) und **Qonecta Companion** (die Begleit-App auf deinem **Android-Smartphone**), entwickelt von **[CharlysEV](https://github.com/CharlysEV)**.

> **⚠️ Sicherheitshinweis:** Führe die Installation und die erste Einrichtung ausschließlich im geparkten Zustand (**Fahrstufe `P`**) durch.

---

## 🧩 Was machen die beiden Apps?

1. **🚗 `Qonecta Unit` (`QonectaUnit_0.1.18-unit.apk` · Installation IM FAHRZEUG):**
   Verwandelt den Originalbildschirm des Leapmotor C10 in einen nativen Empfänger für **Android Auto** (kabellos oder per USB-Kabel) und **Apple CarPlay** – inklusive **Erweitertem C10-Seitenpanel** (Akkustand, Reichweite, Leistung, automatisches Fahrtenbuch, BEV/REEV-Verbrauchs- & Kostenrechner sowie **EV-Routenplaner mit Ladestopps**), frei an- und abwählbaren Verbindungsoptionen (**echter Nur-Bluetooth-Modus ohne 5-GHz-WLAN**) und 100 % störungsfreiem Betrieb mit dem originalen FM/DAB-Radio und den Lenkradtasten.
2. **📱 `Qonecta Companion` (`QonectaUnitCompanion_0.1.18.apk` · Installation AUF DEINEM ANDROID-SMARTPHONE):**
   Verbindet und synchronisiert sich beim Einsteigen automatisch in Echtzeit mit `Qonecta Unit`:
   - Führt dich bei der Erstinstallation mit dem **🎓 Interaktiven 5-Schritte-Live-Tutorial** sicher ans Ziel.
   - Verbindet sich auf Wunsch **rein über Bluetooth** (WLAN abwählbar, sodass kein 5-GHz-WLAN gestartet wird) oder über schnelles WLAN.
   - Empfängt am Fahrzeugdisplay geplante Routen inklusive Ladestopps und **öffnet sie automatisch in Google Maps**.
   - **Stellt Internet per Bluetooth bereit**, damit der Routenplaner im Auto weder dein 2-GB-Leapmotor-Datenvolumen verbraucht noch einen WLAN-Hotspot benötigt.
   - **Aktualisiert die Fahrzeug-App zukünftig mit 1 Fingertipp** direkt vom Handy (nie wieder USB-Stick oder Browser nötig).

---

## ⚠️ Wichtige Vorbereitung

- **Falls du bisher `Qonecta+` oder `HeadQLink` auf dem Smartphone installiert hattest:** deaktiviere dort die automatische Verbindung, damit nicht beide Apps gleichzeitig versuchen, Android Auto zu starten.
- **Vivaldi im Auto installieren:** Öffne auf dem Bildschirm deines Leapmotor C10 den offiziellen **`LeapAppStore`** und installiere den kostenlosen Browser **Vivaldi** (falls noch nicht vorhanden).

---

## 📱 TEIL 1 · `Qonecta Companion` auf dem Android-Smartphone installieren

1. Lade **`QonectaUnitCompanion_0.1.18.apk`** unter [GitHub Releases](https://github.com/CharlysEV/qonecta-unit-companion/releases/latest) herunter und installiere sie auf deinem Android-Smartphone.
2. Öffne **Qonecta Companion** und erteile auf der obersten Karte die empfohlenen Berechtigungen, damit später alles vollautomatisch in der Hosentasche läuft:
   - **Geräte in der Nähe (Bluetooth):** zum automatischen Erkennen deines C10.
   - **Benachrichtigungen:** hält den Hintergrunddienst zuverlässig aktiv.
   - **Über anderen Apps einblenden (Google Maps automatisch öffnen):** von Android benötigt, um vom Auto gesendete Routen auch bei gesperrtem Handy direkt in Google Maps zu starten.
   - **Keine Akku-Optimierung:** verhindert, dass Android die Verbindung während der Fahrt beendet.

---

## 🎓 TEIL 2 · Erstinstallation von `Qonecta Unit` im Leapmotor C10 (nur 1× nötig · ca. 3 Minuten)

Da Vivaldi im Leapmotor C10 direkte `.apk`-Downloads aus Sicherheitsgründen blockiert, erfolgt die **einmalige Erstinstallation** über einen **USB‑C-Stick** und das lokale Webportal deines Smartphones (**komplett ohne Proxy und ohne DNS-Änderung**).

Tippe in **Qonecta Companion** ganz oben auf **`🎓 Modo Tutorial paso a paso (C10)`** und folge den **5 live synchronisierten Schritten**:

### Schritt 1 · `Qonecta Unit` auf das Smartphone laden
- Schritt 1 prüft automatisch, ob `QonectaUnit_0.1.18-unit.apk` bereits auf deinem Handy liegt; andernfalls lädt ein Tipp auf den Cloud-Button automatisch die neueste Version von GitHub herunter.

### Schritt 2 · APK auf einen USB‑C-Stick speichern
1. Stecke einen **USB‑C-Stick** an dein Smartphone *(oder kopiere `QonectaUnit_0.1.18-unit.apk` am PC direkt in das Hauptverzeichnis eines FAT32-USB-Sticks)*.
2. Tippe auf den Button zum Speichern auf USB, wähle links im Menü (`☰`) deinen USB-Stick aus und speichere die Datei. Die Statusanzeige springt automatisch auf **🟢 Grün**.
3. Ziehe den USB-Stick ab und stecke ihn in den **Daten-USB-Anschluss unter der Mittelarmlehne** deines Leapmotor C10.

### Schritt 3 · WLAN-Hotspot am Smartphone einschalten (kein Proxy · kein DNS)
1. Tippe in Schritt 3 auf den Hotspot-Button und schalte den **Mobilen WLAN-Hotspot / Tethering** deines Smartphones ein.
2. Der Assistent erkennt die aktive Hotspot-IP sofort automatisch (`🟢 Hotspot aktiv!`) und startet den lokalen `:8788`-Webserver.
3. Verbinde das **WLAN deines Leapmotor C10** ganz normal mit dem Hotspot deines Smartphones.

### Schritt 4 · Portal in Vivaldi öffnen & der Trick mit den 2 Pfeilen `⤢`
1. Öffne im Leapmotor C10 den Browser **Vivaldi** und gib oben exakt die kurze Adresse ein, die dir Schritt 4 anzeigt (z. B. `http://192.168.43.1:8788/portal`).
2. **Live-Radar:** In derselben Millisekunde, in der Vivaldi die Seite lädt, springt die Anzeige auf deinem Smartphone automatisch auf **🟢 Grün**!
3. Tippe auf der Webseite in Vivaldi auf den **gelben Button Nr. 2 (`📁 ABRIR ARCHIVOS DEL COCHE` / Fahrzeug-Dateien öffnen)**.
4. Es öffnet sich der native Android-Dateimanager **Dateien** im C10:
   - Wähle in der linken Spalte dein **USB-Laufwerk** aus.
   - Du siehst dort `QonectaUnit_0.1.18-unit.apk`.
   - **🔑 DER ENTSCHEIDENDE TRICK:** **Tippe NICHT auf die Mitte des Dateisymbols** (das markiert die Datei nur). Tippe stattdessen auf das **kleine diagonale Zwei-Pfeile-Symbol `⤢` oben in der Ecke der APK-Kachel** (genau wie in der Grafik in Schritt 4 dargestellt!).
5. Dadurch startet direkt der offizielle Android-Paketinstaller des Fahrzeugs! Erlaube beim ersten Mal die Installation aus dieser Quelle, gehe einmal zurück und tippe auf **Installieren**.

### Schritt 5 · Koppeln & Live-Synchronisierung
1. Öffne **Qonecta Unit** auf dem C10-Bildschirm und tippe oben auf **`🔑 Vincular Companion`**, um den **6-stelligen Kopplungscode** anzuzeigen.
2. Wähle in Schritt 5 am Smartphone das Bluetooth deines C10 aus, gib die **6 Ziffern** ein und tippe auf **Koppeln**.
3. Sofort erscheint das grüne Bestätigungsbanner: Fahrzeug und Smartphone sind gekoppelt und in Echtzeit synchronisiert! *(Hotspot kann nun ausgeschaltet und der USB-Stick abgezogen werden).*

---

## ☑️ TEIL 3 · Verbindungsoptionen frei wählen (Nur Bluetooth vs. Kabelloses Android Auto)

Sowohl in **Qonecta Unit** (Auto) als auch in **Qonecta Companion** (Handy) kannst du jede Option einzeln **anhaken oder abwählen (`☑ / ☐`)**:

- **🔵 Reiner Bluetooth-Modus (kein 5-GHz-WLAN, kein Android Auto):**
  Lasse nur **`☑ Bluetooth`** angehakt und deaktiviere **`☐ Wi‑Fi 5 GHz`** (sowie optional **`☐ USB-Kabel`**, wenn das Kabel nur den Akku laden soll). Fahrten, Strom-/Benzintarife, Fahrzeugdaten, Routenübergabe und Bluetooth-Internet für den Routenplaner funktionieren zu 100 % über Bluetooth – ohne WLAN und ohne dein FM/DAB-Radio zu berühren.
- **📡 Kabelloses Android Auto:**
  Aktiviere **`☑ Wi‑Fi 5 GHz`** in `Qonecta Unit` und tippe auf **Telefon verbinden**.
- **🔌 Android Auto oder Apple CarPlay per Kabel:**
  Aktiviere **`☑ USB-Kabel`** und verbinde das Smartphone mit dem Daten-USB-Port unter der Mittelarmlehne.

---

## ⚡ TEIL 4 · Zukünftige Updates mit nur 1 Fingertipp (ohne USB & ohne Browser)

Ab Version `v0.1.18` brauchst du für Updates **nie wieder einen USB-Stick oder Vivaldi**:

1. Tippe in **Qonecta Companion** (Karte 2) auf **`☁️ Buscar nueva versión en Internet`**, um die neueste Version herunterzuladen.
2. Tippe bei verbundenem Fahrzeug (`🟢`) auf **`⚡ Actualizar coche ahora`**: das Smartphone überträgt das Update direkt ans Auto (`SHA-256`-geprüft) und öffnet automatisch den Installationsdialog auf dem C10-Bildschirm!

---

## ☕ Projekt unterstützen (`von CharlysEV`)

Wenn dir **Qonecta Unit** und **Qonecta Companion** im Alltag mit deinem Leapmotor C10 Freude machen und du die Weiterentwicklung mit einem Kaffee unterstützen möchtest:

- ☕ **Ko-fi:** [https://ko-fi.com/charlysev](https://ko-fi.com/charlysev)
- 💙 **PayPal:** [https://www.paypal.me/CharlysEV](https://www.paypal.me/CharlysEV)
- 🐙 **GitHub:** [https://github.com/CharlysEV](https://github.com/CharlysEV)
