# 🚗 Qonecta Unit (`v0.1.18`)

<p align="center">
  <b>Idioma / Language / Sprache / Idioma:</b><br/>
  <a href="README.md">🇪🇸 <b>Español</b></a> ·
  <a href="README.en.md">🇬🇧 <b>English</b></a> ·
  🇩🇪 <b>Deutsch</b> ·
  <a href="README.pt.md">🇵🇹 <b>Português</b></a>
</p>

<p align="center">
  <b>von <a href="https://github.com/CharlysEV">CharlysEV</a></b> · Natives Android Auto, CarPlay, Erweitertes Fahrzeugpanel & EV-Routenplaner direkt auf dem Display des <b>Leapmotor C10</b>
</p>

<p align="center">
  <a href="https://github.com/CharlysEV/qonecta-unit/releases/latest"><b>⬇️ Qonecta Unit herunterladen (Fahrzeug)</b></a> ·
  <a href="https://github.com/CharlysEV/qonecta-unit-companion/releases/latest"><b>📱 Qonecta Companion herunterladen (Smartphone)</b></a>
</p>

<p align="center">
  <b>Schritt-für-Schritt-Anleitungen:</b>
  <a href="docs/INSTALLATIONSANLEITUNG_QONECTA_UNIT_C10.de.md">🇩🇪 Deutsch</a> ·
  <a href="docs/MANUAL_INSTALLATION_QONECTA_UNIT_C10.en.md">🇬🇧 English</a> ·
  <a href="docs/MANUAL_INSTALACION_QONECTA_UNIT_C10.md">🇪🇸 Español</a> ·
  <a href="docs/MANUAL_INSTALACAO_QONECTA_UNIT_C10.pt.md">🇵🇹 Português</a>
</p>

---

**Qonecta Unit** ist eine kostenlose Open-Source-App, die **direkt auf dem Infotainment-System des Leapmotor C10** (BEV und REEV) installiert wird. Sie verwandelt den Originalbildschirm des Fahrzeugs in einen nativen Empfänger für **Android Auto** (kabellos oder per USB-Kabel) und **Apple CarPlay** – ergänzt durch ein **Erweitertes C10-Seitenpanel** mit Echtzeit-Fahrzeugdaten, automatischer Fahrtenaufzeichnung, Strom-/Kraftstoffkostenberechnung und einem **EV-Routenplaner inklusive Ladestopps**.

Sie arbeitet in Echtzeit synchronisiert mit **[Qonecta Companion](https://github.com/CharlysEV/qonecta-unit-companion)** auf deinem Android-Smartphone zusammen – auf Wunsch auch im **reinen Bluetooth-Modus (komplett ohne 5-GHz-WLAN)** und bei **100 % störungsfreiem Zusammenspiel mit dem originalen FM/DAB-Radio und den Lenkradtasten**.

---

## ✨ Die wichtigsten Neuerungen in `v0.1.18`

- **☑️ Frei wählbare Verbindungsoptionen (`☑ / ☐`) & Echter Nur-Bluetooth-Modus:**
  Aktiviere oder deaktiviere **`Bluetooth`**, **`5-GHz-WLAN`**, **`Mobiler Hotspot`** und **`USB-Kabel`** unabhängig voneinander – sowohl im Auto als auch auf dem Smartphone. Wenn nur **`☑ Bluetooth`** angehakt ist, starten weder Fahrzeug noch Smartphone ein 5-GHz-Wi‑Fi-Direct-Netz oder kabelloses Android Auto: Fahrten, Strom-/Sprittarife, Google-Maps-Routen mit Ladestopps und mobile Daten für den Routenplaner laufen rein über Bluetooth, während der USB-Anschluss auf Wunsch ausschließlich zum Laden dient.
- **📻 100 % saubere Koexistenz mit FM/DAB-Radio & Lenkradtasten:**
  Temporärer Audiofokus (`AUDIOFOCUS_GAIN_TRANSIENT`) mit garantierter Freigabe bei Pause/Stopp/Fehlern sowie saubere Prüfung der Lenkradtasten sorgen dafür, dass das originale C10-Radio niemals stumm bleibt oder einfriert.
- **🔄 Echtzeit-Synchronisierung (`Qonecta Unit ↔ Qonecta Companion`):**
  Alle auf dem Fahrzeugdisplay gewählten Verbindungsoptionen und der Kopplungsstatus werden verzögerungsfrei auf dem Smartphone angezeigt.
- **🎓 Interaktiver 5-Schritte-Installationsassistent in `Qonecta Companion`:**
  Integrierter Live-Tutorial-Modus für die Erstinstallation im C10: prüft/lädt die Fahrzeug-APK automatisch herunter, bestätigt das Speichern auf dem USB‑C-Stick, erkennt deinen Smartphone-Hotspot in Echtzeit (ohne Proxy, ohne DNS-Änderungen) und zeigt per **Live-Radar** auf die Millisekunde genau an, sobald der **Vivaldi**-Browser im C10 das lokale Portal öffnet – inklusive anschaulicher Grafik für das kleine Zwei-Pfeile-Symbol **`⤢`** in der App *Dateien*.
- **⚡ 1-Tipp-OTA-Updates direkt vom Smartphone:**
  Nach der einmaligen Erstinstallation werden zukünftige Updates mit einem einzigen Fingertipp vom Smartphone direkt auf das Fahrzeug übertragen (`SHA-256`-geprüft) – ganz ohne USB-Stick und ohne Browser.

---

## 🧩 Die beiden Werkzeuge im Überblick

| App | Installationsort | Offizieller Download | Hauptfunktion |
| :--- | :--- | :--- | :--- |
| **🚗 Qonecta Unit** | Display des **Leapmotor C10** | [Releases · `qonecta-unit`](https://github.com/CharlysEV/qonecta-unit/releases/latest) | Kabelloses/USB Android Auto & CarPlay, C10-Seitenpanel, Fahrtenbuch, BEV/REEV-Verbrauch & EV-Routenplaner |
| **📱 Qonecta Companion** | Dein **Android-Smartphone** | [Releases · `qonecta-unit-companion`](https://github.com/CharlysEV/qonecta-unit-companion/releases/latest) | Interaktives C10-Tutorial, automatische Übergabe von Routen an Google Maps, Bluetooth-Internetbrücke, Tarife & 1-Tipp-Updates |

> 💡 **Unterschied zu [Qonecta+](https://github.com/CharlysEV/qonecta):**
> `Qonecta+` läuft auf dem Smartphone und spiegelt das Bild per *QDLink/SSPLink*, ohne etwas im Auto zu installieren. **`Qonecta Unit` läuft hingegen nativ direkt auf der Hardware des C10** zusammen mit **`Qonecta Companion`** auf dem Handy. Falls du bisher `Qonecta+` genutzt hast, deaktiviere dort bitte den Autostart, damit nicht beide Apps gleichzeitig Android Auto starten wollen.

---

## 🚀 Schnelle Erstinstallation in 3 Minuten

1. Installiere **[Qonecta Companion](https://github.com/CharlysEV/qonecta-unit-companion/releases/latest)** auf deinem Android-Smartphone und lade im C10 über den offiziellen `LeapAppStore` den Browser **Vivaldi** herunter.
2. Öffne **Qonecta Companion** und tippe auf **`🎓 Modo Tutorial paso a paso (C10)`**: der 5-Schritte-Assistent lädt die Fahrzeug-APK herunter, speichert sie auf deinem USB‑C-Stick, erkennt deinen Hotspot und öffnet über Vivaldi im Auto den Dateimanager, wo du über das kleine Ecksymbol **`⤢`** den System-Installer startest.
3. Vollständige Anleitung in deiner Sprache:
   - 🇩🇪 **Deutsch:** [docs/INSTALLATIONSANLEITUNG_QONECTA_UNIT_C10.de.md](docs/INSTALLATIONSANLEITUNG_QONECTA_UNIT_C10.de.md)
   - 🇬🇧 **English:** [docs/MANUAL_INSTALLATION_QONECTA_UNIT_C10.en.md](docs/MANUAL_INSTALLATION_QONECTA_UNIT_C10.en.md)
   - 🇪🇸 **Español:** [docs/MANUAL_INSTALACION_QONECTA_UNIT_C10.md](docs/MANUAL_INSTALACION_QONECTA_UNIT_C10.md)
   - 🇵🇹 **Português:** [docs/MANUAL_INSTALACAO_QONECTA_UNIT_C10.pt.md](docs/MANUAL_INSTALACAO_QONECTA_UNIT_C10.pt.md)

---

## ☕ Projekt unterstützen

**Qonecta Unit**, **Qonecta Companion** und **Qonecta+** sind kostenlose, werbefreie Community-Projekte für Leapmotor-Fahrer. Wenn dir die Tools im Alltag Freude bereiten und du die Weiterentwicklung mit einem Kaffee unterstützen möchtest:

- ☕ **Ko-fi:** [https://ko-fi.com/charlysev](https://ko-fi.com/charlysev)
- 💙 **PayPal:** [https://www.paypal.me/CharlysEV](https://www.paypal.me/CharlysEV)

Herzlichen Dank für deine Unterstützung!

---

## 🛠️ Aus dem Quellcode kompilieren

Voraussetzungen: **JDK 17**, **Android SDK 36**, **NDK `29.0.14206865`** und **CMake `3.22.1`**.

```sh
./gradlew :bridge:testDebugUnitTest :app:testGithubDebugUnitTest :app:assembleGithubCoche :companion:assembleRelease
```

> Niemals private Signaturschlüssel (`*.keystore`, `*.jks`, `key.properties`, `firma/`) ins Repository hochladen. Die offiziellen Release-APKs sind mit dem privaten Schlüssel des Entwicklers signiert, damit direkte 1-Tipp-Updates problemlos darüberinstalliert werden können.

---

## 📜 Credits & Lizenz

Entwickelt von **[CharlysEV](https://github.com/CharlysEV)** als unabhängige Anpassung für den **Leapmotor C10**, basierend auf [DiAuto](https://github.com/shihabal3amri/DiAuto) (Original-README unter [README_DIAUTO.md](README_DIAUTO.md)) sowie [Open Headunit](https://github.com/andreknieriem/open-headunit) / [Michael Reid](https://github.com/mikereidis/headunit).

Veröffentlicht unter der **[GNU AGPL-3.0](LICENSE)**-Lizenz. Siehe [LICENSE](LICENSE) und [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md). *Android Auto* ist eine Marke der Google LLC. Unabhängiges Community-Projekt ohne Verbindung zu Leapmotor oder Google.
