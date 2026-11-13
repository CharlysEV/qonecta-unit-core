# 🚗 Qonecta Unit (`v0.1.18`)

<p align="center">
  <b>Idioma / Language / Sprache / Idioma:</b><br/>
  <a href="README.md">🇪🇸 <b>Español</b></a> ·
  🇬🇧 <b>English</b> ·
  <a href="README.de.md">🇩🇪 <b>Deutsch</b></a> ·
  <a href="README.pt.md">🇵🇹 <b>Português</b></a>
</p>

<p align="center">
  <b>by <a href="https://github.com/CharlysEV">CharlysEV</a></b> · Native Android Auto, CarPlay, Extended Telemetry Panel & EV Route Planner on the <b>Leapmotor C10</b> head unit
</p>

<p align="center">
  <a href="https://github.com/CharlysEV/qonecta-unit/releases/latest"><b>⬇️ Download Qonecta Unit (Car)</b></a> ·
  <a href="https://github.com/CharlysEV/qonecta-unit-companion/releases/latest"><b>📱 Download Qonecta Companion (Phone)</b></a>
</p>

<p align="center">
  <b>Step-by-Step User Manuals:</b>
  <a href="docs/MANUAL_INSTALLATION_QONECTA_UNIT_C10.en.md">🇬🇧 English</a> ·
  <a href="docs/INSTALLATIONSANLEITUNG_QONECTA_UNIT_C10.de.md">🇩🇪 Deutsch</a> ·
  <a href="docs/MANUAL_INSTALACION_QONECTA_UNIT_C10.md">🇪🇸 Español</a> ·
  <a href="docs/MANUAL_INSTALACAO_QONECTA_UNIT_C10.pt.md">🇵🇹 Português</a>
</p>

---

**Qonecta Unit** is a free, open-source application installed **directly on the Leapmotor C10 infotainment head unit** (both BEV and REEV). It turns the car's native display into a full **Android Auto** (wireless or USB) and **Apple CarPlay** receiver, paired with a native **C10 Extended Side Panel** featuring live vehicle telemetry, automatic trip logging, electricity/fuel cost tracking, and an **EV Route Planner with charging stops**.

It works in real-time synchronization with **[Qonecta Companion](https://github.com/CharlysEV/qonecta-unit-companion)** on your Android phone — allowing **pure Bluetooth-only connectivity (with zero 5 GHz Wi‑Fi)** and **100% clean coexistence with the car's native FM/DAB Radio and steering-wheel buttons**.

---

## ✨ What's New in `v0.1.18`

- **☑️ Independent Checkboxes (`☑ / ☐`) & True Bluetooth-Only Mode:**
  Check or uncheck **`Bluetooth`**, **`Wi-Fi 5 GHz`**, **`Mobile Hotspot`**, and **`USB Cable`** independently on both the car and the phone. When you leave only **`☑ Bluetooth`** checked, neither the car nor the phone starts 5 GHz Wi‑Fi Direct or pokes wireless Android Auto: trips, energy tariffs, Google Maps multi-stop routes, and lightweight internet data flow purely over Bluetooth RFCOMM, while the USB port can be used strictly for charging.
- **📻 100% Clean Coexistence with FM/DAB Radio & Steering-Wheel Controls:**
  Uses transient audio focus (`AUDIOFOCUS_GAIN_TRANSIENT`) with guaranteed release on pause/stop/errors and strict steering-wheel key gating so your native C10 radio never mutes, hangs, or loses button control.
- **🔄 Real-Time Synchronization (`Qonecta Unit ↔ Qonecta Companion`):**
  Connection checkboxes toggled on the car's screen and live pairing status are reflected instantaneously on your phone.
- **🎓 Interactive 5-Step C10 Installation Tutorial in `Qonecta Companion`:**
  Built-in step-by-step wizard for first-time installation on the C10: automatically checks/downloads the car APK, confirms USB flash drive export, detects when your phone's Hotspot turns on (zero Proxy, zero DNS), and features a **live HTTP radar** that lights up green the exact millisecond the car's **Vivaldi** browser opens `/portal` — complete with a visual diagram of the `⤢` corner preview icon in *Files*.
- **⚡ 1-Tap OTA Car Updates:**
  Once installed the first time, future updates are pushed directly from your phone to the car screen in a single tap (`SHA-256` verified) — no USB stick or browser needed ever again.

---

## 🧩 The Two Tools at a Glance

| Tool | Where to Install | Official Download | Key Role |
| :--- | :--- | :--- | :--- |
| **🚗 Qonecta Unit** | **Leapmotor C10** screen | [Releases · `qonecta-unit`](https://github.com/CharlysEV/qonecta-unit/releases/latest) | Wireless/USB Android Auto & CarPlay, C10 Extended Panel, Trips, BEV/REEV Efficiency & EV Route Planner |
| **📱 Qonecta Companion** | Your **Android phone** | [Releases · `qonecta-unit-companion`](https://github.com/CharlysEV/qonecta-unit-companion/releases/latest) | Live 5-Step C10 Install Tutorial, automatic Google Maps route launcher, Bluetooth internet bridge, tariffs & 1-Tap OTA updates |

> 💡 **How does this differ from [Qonecta+](https://github.com/CharlysEV/qonecta)?**
> `Qonecta+` runs on your phone and mirrors over *QDLink/SSPLink* without installing anything on the car. **`Qonecta Unit` runs natively inside the C10's own Android head unit**, paired with **`Qonecta Companion`** on your phone. If you previously used `Qonecta+`, disable its auto-start so both apps don't compete for Android Auto simultaneously.

---

## 🚀 Quick First-Time Setup (3 Minutes)

1. Install **[Qonecta Companion](https://github.com/CharlysEV/qonecta-unit-companion/releases/latest)** on your Android phone, and install the **Vivaldi** browser from the official `LeapAppStore` on your Leapmotor C10.
2. Open **Qonecta Companion** and tap **`🎓Modo Tutorial paso a paso (C10)`**: follow the 5 live-synced steps to copy the car APK to a USB‑C flash drive, turn on your phone Hotspot (no Proxy or DNS changes needed), open the local portal in Vivaldi on the car, and tap the small **`⤢`** corner icon in the car's *Files* app to launch the Android installer.
3. Read the full step-by-step manual in your language:
   - 🇬🇧 **English:** [docs/MANUAL_INSTALLATION_QONECTA_UNIT_C10.en.md](docs/MANUAL_INSTALLATION_QONECTA_UNIT_C10.en.md)
   - 🇩🇪 **Deutsch:** [docs/INSTALLATIONSANLEITUNG_QONECTA_UNIT_C10.de.md](docs/INSTALLATIONSANLEITUNG_QONECTA_UNIT_C10.de.md)
   - 🇪🇸 **Español:** [docs/MANUAL_INSTALACION_QONECTA_UNIT_C10.md](docs/MANUAL_INSTALACION_QONECTA_UNIT_C10.md)
   - 🇵🇹 **Português:** [docs/MANUAL_INSTALACAO_QONECTA_UNIT_C10.pt.md](docs/MANUAL_INSTALACAO_QONECTA_UNIT_C10.pt.md)

---

## ☕ Support the Project

**Qonecta Unit**, **Qonecta Companion**, and **Qonecta+** are free, ad-free community tools built with passion for Leapmotor owners. If they make your drives more enjoyable and you'd like to buy me a coffee or support ongoing development:

- ☕ **Ko-fi:** [https://ko-fi.com/charlysev](https://ko-fi.com/charlysev)
- 💙 **PayPal:** [https://www.paypal.me/CharlysEV](https://www.paypal.me/CharlysEV)

Thank you so much for your support!

---

## 🛠️ Building from Source

Requires **JDK 17**, **Android SDK 36**, **NDK `29.0.14206865`**, and **CMake `3.22.1`**.

```sh
./gradlew :bridge:testDebugUnitTest :app:testGithubDebugUnitTest :app:assembleGithubCoche :companion:assembleRelease
```

> Never commit private signing keys (`*.keystore`, `*.jks`, `key.properties`, `firma/`). Official GitHub Release APKs are signed with the maintainer's private key so in-place 1-tap upgrades work seamlessly.

---

## 📜 Credits & License

Developed by **[CharlysEV](https://github.com/CharlysEV)** as an independent adaptation for the **Leapmotor C10**, built upon [DiAuto](https://github.com/shihabal3amri/DiAuto) (original README preserved at [README_DIAUTO.md](README_DIAUTO.md)) and [Open Headunit](https://github.com/andreknieriem/open-headunit) / [Michael Reid](https://github.com/mikereidis/headunit).

Licensed under **[GNU AGPL-3.0](LICENSE)**. See [LICENSE](LICENSE) and [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md). *Android Auto* is a trademark of Google LLC. Independent community project, not affiliated with or endorsed by Leapmotor or Google.
