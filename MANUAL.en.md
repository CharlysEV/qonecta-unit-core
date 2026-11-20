# Qonecta Unit CORE · User Manual

**Languages:** [Español](MANUAL.md) · [Português](MANUAL.pt.md) · **English** · [Italiano](MANUAL.it.md) · [Deutsch](MANUAL.de.md)

Driver guide for **Qonecta Unit CORE** (`v1.0.0-core`), developed by **[CharlysEV](https://github.com/CharlysEV)** ([CharlysEV/qonecta-unit-core](https://github.com/CharlysEV/qonecta-unit-core)).

> [!IMPORTANT]
> **Classic / CORE edition — ONLY Android Auto & Apple CarPlay:**
> This edition is built exclusively for **Android Auto** and **Apple CarPlay** with maximum stability and low overhead. **It does NOT include the Extended system** (no side panel, no car telemetry bridge, no Companion app, no boot receivers, and no background services).

> [!WARNING]
> Complete all installation and setup steps **while parked**. Never adjust phone settings or advanced options while driving.

---

## Table of Contents

1. [What Qonecta Unit CORE is & requirements](#1-what-qonecta-unit-core-is--requirements)
2. [Installation on Leapmotor C10](#2-installation-on-leapmotor-c10)
3. [Connecting Android Auto (Wi-Fi 5 GHz, Hotspot or USB)](#3-connecting-android-auto-wi-fi-5-ghz-hotspot-or-usb)
4. [Connecting Apple CarPlay (Wi-Fi Direct, Hotspot or USB)](#4-connecting-apple-carplay-wi-fi-direct-hotspot-or-usb)
5. [Optional 2nd Screen (Instrument Cluster)](#5-optional-2nd-screen-instrument-cluster)
6. [Useful Settings & Language](#6-useful-settings--language)
7. [Troubleshooting](#7-troubleshooting)
8. [Reporting Issues on GitHub Issues](#8-reporting-issues-on-github-issues)
9. [Support the Project & Legal Notice](#9-support-the-project--legal-notice)

---

## 1. What Qonecta Unit CORE is & requirements

**Qonecta Unit CORE** installs directly onto your **Leapmotor C10** Android head unit, turning the screen into a native **Android Auto** and **Apple CarPlay** receiver — no external dongles or mirroring apps required.

| Feature | Details |
|---|---|
| **Protocols** | **Android Auto** (Android phones) & **Apple CarPlay** (iPhone) |
| **Connection modes** | 5 GHz Wi-Fi Direct · Hotspot · USB Data Cable |
| **2nd Screen (Cluster)** | Optional (`Cluster: OFF` by default for maximum safety; 1-tap toggle) |
| **CORE edition** | **No Extended system**, no microphone capture (`RECORD_AUDIO` removed), no auto-boot daemons |
| **UI languages** | Español, Português, English, Italiano, Deutsch (plus in-app language picker) |

**What you need:**
- A **Leapmotor C10** (or compatible Android head unit) able to install APKs.
- A FAT32/exFAT **USB flash drive** for initial installation (or direct download).
- Your **Android phone** (with up-to-date *Android Auto*) or your **iPhone**.

---

## 2. Installation on Leapmotor C10

1. Download `QonectaUnit_1.0.0-core.apk` from **[GitHub Releases](https://github.com/CharlysEV/qonecta-unit-core/releases)**.
2. Copy the APK onto a USB drive and plug it into the car's **USB data port**.
3. Open the car's file manager (or browser) and tap the `.apk` file to install it.
4. Launch **Qonecta Unit CORE** and grant the requested permissions (**Nearby devices / Bluetooth** and **Location** for Wi-Fi Direct).
5. For future updates, simply tap **«Files»** in the top bar of Qonecta Unit CORE.

---

## 3. Connecting Android Auto (Wi-Fi 5 GHz, Hotspot or USB)

You can enable one or multiple transport modes on the Home screen:

### A. Wi-Fi 5 GHz (recommended, wireless)
1. Pair your Android phone with the car's **Bluetooth** and keep **car Wi-Fi turned ON**.
2. Keep the **«Wi-Fi 5 GHz»** mode checked.
3. Tap **«Connect Android Auto»** or tap your phone card directly under **«Paired phones»**.
4. If your phone disconnects intermittently, disable *"Switch to mobile data"* / *"Intelligent Wi-Fi switching"* in your phone's Wi-Fi settings.

### B. Mobile Hotspot (Car Internet + Android Auto)
1. Turn on your phone's Mobile Hotspot and connect the car's Wi-Fi to it.
2. Check **«Mobile Hotspot»** and tap **«Connect Android Auto»**.

### C. USB Cable (Direct + Charging)
1. Keep **«USB Cable»** checked (it works cleanly alongside **Wi-Fi 5 GHz**!).
2. Plug your phone into the car's **USB data port** using a **data-capable USB cable** (not charge-only rear ports).
3. Home immediately displays `🔌 USB ready` and connects automatically — or tap **«Connect USB»**.
4. Plugging in USB while a wireless session is running will ask whether you want to switch to USB.

---

## 4. Connecting Apple CarPlay (Wi-Fi Direct, Hotspot or USB)

Tap **«Apple CarPlay»** on the Home screen (or plug in your iPhone via USB):

### A. Wireless CarPlay (5 GHz Wi-Fi Direct — recommended)
1. Pair your **iPhone via Bluetooth** with the car and keep both Bluetooth and Wi-Fi enabled on the iPhone.
2. On the CarPlay screen, tap your iPhone under **«Paired iPhones»** or tap **«Connect CarPlay»**.
3. Allow **CarPlay** on your iPhone when prompted the first time.

### B. Car Hotspot mode (fallback)
If Wi-Fi Direct is unstable on your firmware, switch to **«Car Hotspot»**, enter your car's Hotspot name & password, and turn on the car's Hotspot (turning off the car's Wi-Fi client in the top bar).

### C. USB Cable CarPlay
1. Plug your iPhone into the car's **USB data port** using a data cable.
2. Tap **«CarPlay USB»** (highlights automatically when an iPhone is detected).
3. Unlock your iPhone and accept **«Trust»** and **«CarPlay»**.
4. On first launch, Android may ask to allow a **local VPN tunnel** (strictly local between the USB port and the screen; zero external traffic).

> **Tip inside CarPlay:** swipe down with **three fingers** anytime to open CarPlay settings.

---

## 5. Optional 2nd Screen (Instrument Cluster)

For maximum safety and lightweight operation, the 2nd screen is **OFF by default (`Cluster: OFF`)**.
- Tap **`Cluster: OFF`** in the top bar (available on both Android Auto and CarPlay screens) to toggle **`Cluster: ON`** and show the live navigation map on the C10 instrument cluster.
- Applies on the **next connection**.
- Purely event-driven, with no 1 Hz polling or OEM telemetry hooks.

---

## 6. Useful Settings & Language

- **Language:** open **Settings → Language** to choose **English, Español, Português, Italiano, Deutsch**, or system default. Applies to both **Android Auto** and **Apple CarPlay**.
- **Display scale / DPI:** customize DPI in Android Auto **Settings**, or **CarPlay Settings → Icon & text size**.
- **Clean exit:** tapping **«Close»** on Home completely stops Android Auto, CarPlay, and Cluster with zero background processes left behind.

---

## 7. Troubleshooting

- **USB cable not detected:** make sure you are using a **data cable** and the **USB data port** (not a charge-only port). Select *File transfer / Android Auto* if prompted on your phone.
- **5 GHz Wi-Fi doesn't wake phone automatically:** tap your phone card under **«Paired phones»**. Make sure *"Switch to mobile data"* is disabled in phone Wi-Fi settings.
- **CarPlay Wi-Fi Direct gets stuck:** open **CarPlay Settings → Wireless help & CarPlay Wi-Fi reset → Reset CarPlay Wi-Fi**.
- **No audio:** check the car's media audio source (Bluetooth/Media) and phone volume.

---

## 8. Reporting Issues on GitHub Issues

If you find a bug, connection glitch, or have feedback, please open an issue in **GitHub Issues**:

👉 **[https://github.com/CharlysEV/qonecta-unit-core/issues](https://github.com/CharlysEV/qonecta-unit-core/issues)**

*(You can also open **Settings → Report a problem** inside the app and scan the offline QR code with your phone).*

Please include:
1. Car model and firmware version (e.g., Leapmotor C10).
2. Phone model and Android / iOS version.
3. Whether it happened in **Android Auto** or **Apple CarPlay**, and which mode (**Wi-Fi 5 GHz**, **Hotspot**, or **USB**).
4. Exported logs from **Settings → Debug → Export logs** (Android Auto) or **CarPlay Settings → Save diagnostic report**, if available.

---

## 9. Support the Project & Legal Notice

**Qonecta Unit CORE** is free, open source, and ad-free. You can support development directly (or via the offline QR codes behind **«☕ Support»** in the app):

- ☕ **Ko-fi:** [https://ko-fi.com/charlysev](https://ko-fi.com/charlysev)
- 💙 **PayPal:** [https://www.paypal.me/CharlysEV](https://www.paypal.me/CharlysEV)

Licensed under AGPL-3.0 / GPL-3.0. Independent community project not affiliated with Leapmotor, Google, or Apple. Always drive safely.
