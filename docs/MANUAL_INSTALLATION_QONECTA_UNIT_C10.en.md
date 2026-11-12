# 🚗📱 Step-by-Step User Manual · Qonecta Unit & Qonecta Companion (`v0.1.18`)

<p align="center">
  <a href="MANUAL_INSTALACION_QONECTA_UNIT_C10.md">🇪🇸 Español</a> ·
  🇬🇧 <b>English</b> ·
  <a href="INSTALLATIONSANLEITUNG_QONECTA_UNIT_C10.de.md">🇩🇪 Deutsch</a>
</p>

Clear, human-friendly guide to installing, pairing, and updating **Qonecta Unit** (the app installed directly on the **Leapmotor C10** screen) and **Qonecta Companion** (the companion app on your **Android phone**), created by **[CharlysEV](https://github.com/CharlysEV)**.

> **⚠️ Safety first:** Always perform installation and initial pairing while the vehicle is safely parked in **`P`**.

---

## 🧩 What Are These Two Tools?

1. **🚗 `Qonecta Unit` (`QonectaUnit_0.1.18-unit.apk` · Installed ON THE CAR):**
   Turns the Leapmotor C10's native display into a full **Android Auto** (wireless or USB cable) and **Apple CarPlay** receiver, complete with a **C10 Extended Side Panel** (live battery SoC, range, power, automatic trip history, BEV/REEV consumption & cost calculator, and an **EV Route Planner with charging stops**), independent transport checkboxes (**True Bluetooth-Only Mode without 5 GHz Wi‑Fi**), and 100% clean coexistence with the native FM/DAB radio and steering-wheel controls.
2. **📱 `Qonecta Companion` (`QonectaUnitCompanion_0.1.18.apk` · Installed ON YOUR ANDROID PHONE):**
   Automatically synchronizes in real time with `Qonecta Unit` when you enter the car to:
   - Guide you through first-time C10 installation via the **🎓 Interactive 5-Step Live Tutorial**.
   - Connect via **pure Bluetooth** (unchecking Wi‑Fi so 5 GHz Wi‑Fi is never started) or fast Wi‑Fi.
   - Receive multi-stop EV charging routes planned on the car screen and **launch them automatically in Google Maps**.
   - **Provide Bluetooth Internet** to the car's Route Planner without using your 2 GB Leapmotor data plan or turning on a Wi‑Fi Hotspot.
   - **Push future app updates to the car in 1 single tap** (no USB stick or browser needed ever again).

---

## ⚠️ Before You Start

- **If you previously installed `Qonecta+` or `HeadQLink` on your phone:** turn off its automatic connection so both apps don't try to start Android Auto at the same time.
- **Install Vivaldi on your car first:** open the official **`LeapAppStore`** on your Leapmotor C10 screen and install the free **Vivaldi** browser (if you haven't already).

---

## 📱 PART 1 · Install `Qonecta Companion` on Your Android Phone

1. Download and install **`QonectaUnitCompanion_0.1.18.apk`** from [GitHub Releases](https://github.com/CharlysEV/qonecta-unit-companion/releases/latest) on your Android phone.
2. Open **Qonecta Companion** and grant the recommended permissions on the top card so everything works automatically in your pocket:
   - **Nearby devices (Bluetooth):** to detect and connect to your C10 automatically.
   - **Notifications:** to keep the background bridge active.
   - **Display over other apps (Launch Google Maps automatically):** required by Android so the phone can open Google Maps routes sent from the car while locked.
   - **Unrestricted battery:** prevents Android from killing the connection mid-drive.

---

## 🎓 PART 2 · Install `Qonecta Unit` on the Leapmotor C10 (First Time Only · 3 Minutes)

Because Vivaldi on the Leapmotor C10 blocks direct `.apk` downloads, the **one-time initial installation** uses a **USB‑C flash drive** plus your phone's local web portal (**zero Proxy and zero DNS changes required**).

Open **Qonecta Companion** and tap the **`🎓 Modo Tutorial paso a paso (C10)`** button at the top. Follow the **5 live-synced steps**:

### Step 1 · Download `Qonecta Unit` to Your Phone
- Step 1 automatically checks if `QonectaUnit_0.1.18-unit.apk` is already on your phone; if not, tap the cloud download button to fetch the latest release straight from GitHub.

### Step 2 · Save the APK to a USB‑C Flash Drive
1. Plug a **USB‑C flash drive** into your phone *(or copy `QonectaUnit_0.1.18-unit.apk` to the root of a FAT32 USB stick from your computer)*.
2. Tap the **Save to USB** button, select your USB drive on the left menu (`☰`), and save. Step 2 automatically turns **🟢 Green** once saved.
3. Unplug the USB stick and plug it into the **data USB port under the center armrest** of your Leapmotor C10.

### Step 3 · Turn On Your Phone's Wi‑Fi Hotspot (No Proxy · No DNS)
1. Tap the **Open Hotspot** button in Step 3 and turn on your phone's **Wi‑Fi Hotspot / Tethering**.
2. The tutorial automatically detects your active Hotspot IP (`🟢 Hotspot active!`) and starts the local `:8788` portal server.
3. On your Leapmotor C10 screen, go to **Settings → Wi‑Fi** and connect normally to your phone's Hotspot.

### Step 4 · Open the Portal in Vivaldi & the `⤢` Two-Arrows Trick
1. Open **Vivaldi** on the Leapmotor C10 screen and type the short address shown in Step 4 (for example: `http://192.168.43.1:8788/portal`).
2. **Live Sync Radar:** the exact millisecond Vivaldi opens the page, Step 4 on your phone turns bright green confirming your C10 is connected!
3. On the webpage inside Vivaldi, tap the **Yellow Button #2 (`📁 ABRIR ARCHIVOS DEL COCHE` / Open Car Files)**.
4. The native Android **Files** manager opens on the C10:
   - Select your **USB Drive** in the left sidebar.
   - You will see `QonectaUnit_0.1.18-unit.apk`.
   - **🔑 THE KEY TRICK:** **DO NOT tap the center of the file tile** (that only selects it). Instead, tap the **tiny diagonal two-arrows preview icon `⤢` in the top-right corner of the APK tile** (illustrated directly inside Step 4 of the tutorial!).
5. The official Android Package Installer opens! Allow installation from *Files* if prompted, go back once, and tap **Install**.

### Step 5 · Pair & Synchronize Live
1. Open **Qonecta Unit** on your C10 screen and tap **`🔑 Vincular Companion`** in the top bar to display a **6-digit pairing code**.
2. In Step 5 on your phone, select your C10's Bluetooth hands-free device, enter the **6 digits**, and tap **Pair**.
3. Within 2 seconds, Step 5 celebrates with a green banner confirming live synchronization! *(You can now turn off your Hotspot and unplug the USB drive).*

---

## ☑️ PART 3 · Choosing Your Connection Checkboxes (Bluetooth-Only vs Wireless Android Auto)

Both **Qonecta Unit** (car) and **Qonecta Companion** (phone) feature independent **`☑ / ☐` checkboxes**:

- **🔵 Pure Bluetooth Only (Zero 5 GHz Wi‑Fi, Zero Android Auto):**
  Check **`☑ Bluetooth`** and uncheck **`☐ Wi‑Fi 5 GHz`** (and uncheck **`☐ USB Cable`** if you want the USB port strictly for charging). Trips, energy tariffs, live telemetry, EV route transfers, and Bluetooth internet for the car's Route Planner work 100% over Bluetooth without touching Wi‑Fi or your FM/DAB radio.
- **📡 Wireless Android Auto:**
  Check **`☑ Wi‑Fi 5 GHz`** on `Qonecta Unit` and tap **Connect phone**.
- **🔌 Wired Android Auto or Apple CarPlay:**
  Check **`☑ USB Cable`** and plug your phone into the car's data USB port.

---

## ⚡ PART 4 · Future Updates in 1 Single Tap (No USB, No Browser)

From `v0.1.18` onwards, you will **never need a USB stick or Vivaldi again** to update the car app:

1. In **Qonecta Companion** (Card 2), tap **`☁️ Buscar nueva versión en Internet`** to download the newest release.
2. While connected to your car (`🟢`), tap **`⚡ Actualizar coche ahora`**: your phone transfers the APK directly to the C10 (`SHA-256` verified) and automatically opens the Android update prompt on the car screen!

---

## ☕ Support the Project (`by CharlysEV`)

If **Qonecta Unit** and **Qonecta Companion** make driving your Leapmotor C10 better and you'd like to buy me a coffee or support future updates:

- ☕ **Ko-fi:** [https://ko-fi.com/charlysev](https://ko-fi.com/charlysev)
- 💙 **PayPal:** [https://www.paypal.me/CharlysEV](https://www.paypal.me/CharlysEV)
- 🐙 **GitHub:** [https://github.com/CharlysEV](https://github.com/CharlysEV)
