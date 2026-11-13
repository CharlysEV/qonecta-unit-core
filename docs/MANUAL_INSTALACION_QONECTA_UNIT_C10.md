# 🚗📱 Manual Paso a Paso · Qonecta Unit & Qonecta Companion (`v0.1.19`)

<p align="center">
  🇪🇸 <b>Español</b> ·
  <a href="MANUAL_INSTALLATION_QONECTA_UNIT_C10.en.md">🇬🇧 English</a> ·
  <a href="INSTALLATIONSANLEITUNG_QONECTA_UNIT_C10.de.md">🇩🇪 Deutsch</a> ·
  <a href="MANUAL_INSTALACAO_QONECTA_UNIT_C10.pt.md">🇵🇹 Português</a>
</p>

Guía clara paso a paso para instalar, vincular y actualizar **Qonecta Unit** (la app que va en la pantalla del **Leapmotor C10**) y **Qonecta Companion** (la app compañera que va en tu **móvil Android**), creadas por **[CharlysEV](https://github.com/CharlysEV)**.

> **⚠️ Seguridad ante todo:** Haz siempre la instalación y el primer emparejamiento con el coche estacionado en **`P`**.

---

## 🧩 ¿Qué son las dos herramientas?

1. **🚗 `Qonecta Unit` (`QonectaUnit_0.1.18-unit.apk` · Se instala EN EL COCHE):**
   Convierte la pantalla original del Leapmotor C10 en un receptor nativo de **Android Auto** (inalámbrico o por cable USB) y **Apple CarPlay**, con **Panel Extendido C10** (batería, autonomía, potencia, viajes, consumos REEV/BEV y **Planificador de Rutas EV con paradas de carga**), casillas independientes de conexión (**Modo Solo Bluetooth sin Wi‑Fi 5 GHz**) y respeto al 100 % de la Radio FM/DAB original y los mandos del volante.
2. **📱 `Qonecta Companion` (`QonectaUnitCompanion_0.1.19.apk` · Se instala EN TU MÓVIL ANDROID):**
   Disponible en **4 idiomas (`🇪🇸 ES · 🇬🇧 EN · 🇩🇪 DE · 🇵🇹 PT`)** con detección automática o selector manual instantáneo en la barra superior. Se sincroniza en vivo con `Qonecta Unit` al subir al coche para:
   - Guiarte en tu primera instalación con el **🎓 Modo Tutorial interactivo de 5 pasos en vivo**.
   - Conectar **solo por Bluetooth** (desmarcando Wi‑Fi si no quieres levantar redes de 5 GHz) o por Wi‑Fi rápido.
   - Recibir las rutas con paradas de carga del Planificador del coche y **abrirlas solas en Google Maps**.
   - **Dar Internet por Bluetooth** al Planificador del coche sin gastar los 2 GB de datos de Leapmotor ni encender el Hotspot.
   - **Actualizar la app del coche en 1 solo toque** desde el móvil (sin volver a usar pendrives ni navegador).

---

## ⚠️ Antes de empezar

- **Si tenías instalada `Qonecta+` o `HeadQLink` en tu móvil:** desactiva su inicio automático mientras uses `Qonecta Unit` para que las dos apps no intenten abrir Android Auto al mismo tiempo.
- **Instala primero Vivaldi en el coche:** abre la tienda oficial **`LeapAppStore`** en la pantalla del Leapmotor C10 y descarga el navegador gratuito **Vivaldi** (si aún no lo tienes).

---

## 📱 PARTE 1 · Instalar `Qonecta Companion` en tu móvil Android

1. Descarga e instala **`QonectaUnitCompanion_0.1.19.apk`** desde [GitHub Releases](https://github.com/CharlysEV/qonecta-unit-companion/releases/latest) en tu móvil Android.
2. Abre **Qonecta Companion** y concede en la primera tarjeta los permisos recomendados pulsando **`Dar`**:
   - **Bluetooth (Dispositivos cercanos):** para detectar y conectar con tu C10 automáticamente.
   - **Notificaciones:** para mantener el puente activo en segundo plano.
   - **Abrir Google Maps automáticamente (*Mostrar sobre otras apps*):** necesario en Android para lanzar la ruta enviada desde el coche con el móvil en el bolsillo.
   - **Sin ahorro de batería:** para que el sistema no corte el servicio en mitad de un viaje.

---

## 🎓 PARTE 2 · Instalar `Qonecta Unit` en el C10 con el Modo Tutorial en Vivo (solo la 1.ª vez)

En **Qonecta Companion**, pulsa **`🎓 Abrir Modo Tutorial paso a paso (C10)`** (arriba del todo o en la Tarjeta 3). El asistente sincronizado te llevará de la mano en **5 pasos automáticos** *(sin tocar Proxy ni DNS)*:

### Paso 1 · Descargar `Qonecta Unit` en el móvil
- El tutorial comprueba si ya tienes `QonectaUnit_0.1.18-unit.apk` en el móvil; si no, pulsa **«☁️ Descargar Qonecta Unit ahora»** y la bajará directamente de GitHub.

### Paso 2 · Guardar el APK en un Pendrive USB‑C
1. Conecta un **Pendrive USB‑C** a tu móvil *(o cópialo desde un PC a la raíz del pendrive)*.
2. Pulsa **«💾 Guardar QonectaUnit en Pendrive USB»**, elige tu USB y guarda el archivo. El tutorial marcará **`🟢 ¡APK guardada!`** automáticamente.
3. Enchufa ese pendrive en el **puerto USB de datos del Leapmotor C10** (debajo del reposabrazos central).

### Paso 3 · Encender Hotspot en el móvil (cero Proxy · cero DNS)
1. Pulsa **«📶 Abrir Punto de Acceso (Hotspot)»** y enciende Compartir conexión Wi‑Fi en tu móvil.
2. El tutorial detectará automáticamente tu Hotspot (`🟢 ¡Hotspot activo detectado!`) y encenderá el servidor local `:8788`.
3. Conecta el **Wi‑Fi del Leapmotor C10** al Hotspot de tu móvil con normalidad.

### Paso 4 · Abrir el Portal en Vivaldi + el truco de las 2 flechas `⤢`
1. Abre **Vivaldi** en la pantalla del C10 y escribe la dirección corta que te muestra el tutorial (por ejemplo `http://192.168.43.1:8788/portal`).
2. **Radar en vivo:** en cuanto Vivaldi cargue la página, tu móvil se pondrá en verde confirmando **`🟢 ¡SINCRONIZADO EN VIVO! Tu Leapmotor C10 acaba de abrir el Portal`**.
3. En la pantalla del coche, toca el **Botón Amarillo «📁 ABRIR ARCHIVOS DEL COCHE»**.
4. En **Archivos**, entra en tu **Unidad USB** a la izquierda:
   - **🔑 EL TRUCO CLAVE:** **NO toques el centro del archivo APK**. Toca las **dos flechas pequeñas `⤢` de la esquina del icono** (tal y como muestra el dibujo del tutorial) para abrir directamente el Instalador oficial del sistema.
   - Acepta el permiso si es la primera vez y pulsa **Instalar**.

### Paso 5 · Vincular y sincronizar en vivo
1. Abre **Qonecta Unit** en tu C10 y toca el botón superior **«🔑 Vincular Companion»** para ver el código de **6 cifras**.
2. En el Paso 5 del móvil, elige el Bluetooth de tu C10, escribe las **6 cifras** y pulsa **«🔗 Vincular y sincronizar ahora»**.
3. Verás al instante **`🎉 ¡C10 conectado y sincronizado en vivo!`** (ya puedes apagar el Hotspot y retirar el pendrive).

---

## ☑️ PARTE 3 · Elegir tus opciones de conexión (Solo Bluetooth vs Android Auto)

Tanto en la pantalla de **Qonecta Unit** (coche) como en **Qonecta Companion** (móvil) puedes **marcar y desmarcar (`☑` / `☐`)** libremente cada modo:

- **🔵 Quiero solo datos, viajes, tarifas y enviar rutas (sin Android Auto ni Wi‑Fi 5 GHz):**
  Deja marcado únicamente **`☑ Bluetooth`** y desmarca **`☐ Wi‑Fi 5 GHz`** (y desmarca **`☐ Cable USB`** si solo quieres cargar batería por cable). Coche y móvil hablarán 100 % por Bluetooth puro sin tocar el Wi‑Fi ni la radio.
- **📡 Quiero Android Auto inalámbrico:**
  Marca **`☑ Wi‑Fi 5 GHz`** en `Qonecta Unit` y pulsa **«Conectar teléfono»**.
- **🔌 Quiero Android Auto o CarPlay por cable:**
  Marca **`☑ Cable USB`** y enchufa el móvil al puerto USB de datos del coche.

---

## ⚡ PARTE 4 · Actualizar en el futuro en 1 SOLO TOQUE (sin pendrive ni Vivaldi)

A partir de ahora **nunca más necesitarás pendrive ni Vivaldi** para actualizar el coche:

1. En **Qonecta Companion** (Tarjeta 2 · *Actualizar Coche en 1 Toque*), pulsa **«☁️ Buscar nueva versión en Internet»**.
2. Pulsa **«⚡ Actualizar coche ahora»**: el móvil enviará la nueva versión directamente al coche (con verificación `SHA-256`) y abrirá solo en la pantalla del C10 el cartel oficial de Android para pulsar **«Actualizar»**.

---

## ☕ Apoyar el proyecto (`por CharlysEV`)

Si disfrutas de **Qonecta Unit** y **Qonecta Companion** en tu Leapmotor C10 y te apetece invitar a un café o apoyar su evolución:

- ☕ **Ko-fi:** [https://ko-fi.com/charlysev](https://ko-fi.com/charlysev)
- 💙 **PayPal:** [https://www.paypal.me/CharlysEV](https://www.paypal.me/CharlysEV)
- 🐙 **GitHub:** [https://github.com/CharlysEV](https://github.com/CharlysEV)
