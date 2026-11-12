# 📘 Manual Técnico y Educativo · Qonecta Companion (`Modo Experto`, DNS, Portal Cautivo y Explorador de Archivos en C10)

Este documento explica con rigor técnico y fines educativos cómo funcionan por dentro las herramientas de red de **`Qonecta Companion`** (activables desde el botón **`🛠️ Experto`** arriba a la derecha), qué ocurre exactamente en el sistema Android 11 (`LeapOS`) del **Leapmotor C10** al conectar por Wi-Fi, qué papel juegan el **DNS**, los **puertos `80` vs `8788`** y el **Portal Cautivo**, y cómo se invoca de forma estándar el explorador nativo de **Archivos (`DocumentsUI`)** del coche.

---

## 1. Qué piezas tiene Android 11 dentro del Leapmotor C10

Según el inventario de paquetes del sistema del C10 (`Android 11 · API 30`), la unidad central incluye de fábrica estos cuatro componentes estándar de AOSP:

1. **`com.android.networkstack` (`NetworkMonitor`)**: Servicio del sistema que comprueba automáticamente si una red Wi-Fi recién conectada tiene salida real a Internet o requiere iniciar sesión (como el Wi-Fi de un hotel o aeropuerto).
2. **`com.android.captiveportallogin` (`CaptivePortalLogin`)**: Aplicación del sistema con un `WebView` integrado que Android abre cuando detecta un portal cautivo («Iniciar sesión en la red Wi-Fi»). En el C10 tiene declarado en sistema el permiso `REQUEST_INSTALL_PACKAGES`.
3. **`com.android.documentsui` (`Archivos`)**: El gestor y selector nativo de archivos de Android (oculto en el cajón de apps de Leapmotor, pero presente en `/system/priv-app/DocumentsUI/DocumentsUI.apk`).
4. **`com.android.packageinstaller` (`PackageInstaller`)**: El instalador oficial de paquetes APK de Android, siempre que el usuario conceda explícitamente permiso de orígenes desconocidos a la aplicación que lo invoca.

---

## 2. Cómo funciona el Servidor Local y el DNS en `Qonecta Companion` (`🛠️ Modo Experto`)

Cuando pulsas **`🛠️ Experto`** en la esquina superior derecha de **Qonecta Companion**, se desbloquea la tarjeta **«📡 Servidor HTTP / Portal Cautivo / DNS Manual»**, compuesta por dos bloques:

### A) Servidor HTTP Dual (`:8788` y `:8080`)
- Companion abre dos sockets TCP de escucha (`0.0.0.0:8788` y `0.0.0.0:8080`) manteniendo un `WifiLock` de alto rendimiento y un `WakeLock` temporal para que el teléfono no duerma la red mientras usas la pantalla del coche.
- Ofrece cuatro rutas principales:
  - `http://<IP-Hotspot>:8788/portal`: Página interactiva con detección automática de `User-Agent` (*Vivaldi* vs *CaptivePortalLogin*).
  - `http://<IP-Hotspot>:8788/download/com.tencent.qqmusiccar.apk`: Descarga binaria del APK con soporte de rangos HTTP (`206 Partial Content`) para el `DownloadManager` de Android.
  - `http://<IP-Hotspot>:8788/proxy.pac`: Archivo estándar *Proxy Auto-Config* para sistemas Android que sí muestran opciones avanzadas de Proxy en su menú Wi-Fi.
  - Respuesta `HTTP 302 Found → /portal`: Si recibe peticiones de comprobación de conectividad (`generate_204`, `gen_204`, `gstatic.com`, `captive.apple.com`, `msftconnecttest.com`), responde con redirección al portal local.

### B) Ayudante de DNS Personalizado Manual (`🌐 DNS Personalizado Manual`)
- Permite guardar y copiar en 1 toque un nombre de host de **DNS Privado (*DNS-over-TLS*, puerto 853)** (por ejemplo, un perfil propio de `NextDNS` o `AdGuard DNS`) o una IP DNS, y abre directamente los **Ajustes de Red / DNS Privado** del teléfono Android.
- **Uso educativo en el móvil (cuando el móvil comparte Hotspot con el coche):**
  En Android, cuando un dispositivo cliente (el coche) se conecta al Punto de acceso (*Hotspot*) del teléfono, `dnsmasq` / `tethering` del teléfono actúa como reenviador DNS para los clientes conectados al Hotspot. Si el teléfono resuelve nombres a través de un proveedor DNS configurado por el usuario, las respuestas DNS que no estén cacheadas siguen las reglas de ese perfil DNS.

---

## 3. Análisis Educativo: ¿Por qué el DNS por sí solo NO abre la ventana del Portal Cautivo en un móvil normal?

Esta es la duda técnica más habitual y merece entenderse de arriba abajo:

1. **Qué hace `NetworkMonitor` del coche al conectarse al Hotspot:**
   El coche lanza una petición HTTP en texto plano:
   ```text
   GET http://connectivitycheck.gstatic.com/generate_204 HTTP/1.1
   Host: connectivitycheck.gstatic.com
   ```
   Observa que al no llevar `:puerto`, el protocolo HTTP usa siempre por defecto el **puerto TCP `80`**.

2. **Qué hace un registro DNS personalizado (por ejemplo, redirigir un dominio a la IP del Hotspot `192.168.43.1`):**
   El protocolo DNS **solo traduce un nombre de dominio a una dirección IP (`A` / `AAAA`)**; **el DNS NO puede cambiar el número de puerto TCP**.
   Por tanto, aunque el DNS le diga al coche que ese dominio vive en `192.168.43.1`, el coche llamará a **`192.168.43.1:80`** (puerto `80`), **no** a `192.168.43.1:8788`.

3. **La barrera de seguridad del kernel Linux / Android (`puertos < 1024`):**
   En Android (Linux), todos los puertos por debajo del `1024` (incluido el puerto `80`) son **puertos privilegiados (`CAP_NET_BIND_SERVICE`)**. Ninguna aplicación normal instalada por el usuario (`Qonecta Companion` incluida) tiene permiso del sistema operativo para escuchar en el puerto `80` (`BindException: Permission denied`), ni puede modificar las tablas `iptables`/`nftables` del sistema en un teléfono estándar sin privilegios elevados.
   - *Consecuencia práctica:* Si solo cambias las DNS apuntando a la IP del móvil sin nada que escuche en el puerto `80`, el coche recibe `Connection refused` en `192.168.43.1:80` y **no se abre `CaptivePortalLogin`**.
   - Del mismo modo, en el menú Wi-Fi personalizado por Leapmotor en el C10 (cuando no tienes ya una app dentro que abra los Ajustes AOSP), Leapmotor ocultó el desplegable *Opciones avanzadas → Proxy*, por lo que tampoco puedes indicarle al Wi-Fi del coche que encamine el tráfico hacia el puerto `:8788`.

---

## 4. Cómo se abre limpiamente el Explorador de Archivos (`DocumentsUI`) del C10 usando los estándares web y de Android

Dado que `DocumentsUI` (`com.android.documentsui`) sí está instalado de serie en el C10 pero no tiene icono visible en el lanzador de Leapmotor, el estándar HTML5 y Android ofrecen una vía completamente legítima, documentada y sin privilegios especiales para abrirlo:

### Mecanismo técnico paso a paso
1. En el HTML servido por **Qonecta Companion** en `http://<IP-Hotspot>:8788/portal` se incluye un elemento estándar de formulario web:
   ```html
   <label class="btn btn-usb" for="usbFilePicker">
       📁 2. ABRIR «ARCHIVOS DEL COCHE» (INSTALAR DESDE USB)
   </label>
   <input id="usbFilePicker" type="file" accept="*/*" style="display:none;">
   ```
2. Cuando abres `http://<IP-Hotspot>:8788/portal` en **Vivaldi Automotive** en la pantalla del C10 y tocas ese botón amarillo:
   - Chromium/Vivaldi traduce el clic sobre `<input type="file">` en el *Intent* estándar de Android `Intent.ACTION_GET_CONTENT` / `Intent.ACTION_OPEN_DOCUMENT` (`*/*`).
   - El sistema Android del C10 resuelve ese *Intent* abriendo **`com.android.documentsui.picker.PickActivity`** (la ventana nativa de **Archivos** del coche).
3. **Diferencia entre «Seleccionar» y «Vista previa (`⤢`)» dentro de `DocumentsUI` en Android 11:**
   - Si tocas **el centro** de un archivo `.apk` cuando `DocumentsUI` se abrió en modo selector (`PickActivity`), `DocumentsUI` simplemente devuelve el archivo a Vivaldi para subirlo a la página web (no lo instala).
   - En cambio, Android 11 incluye en la esquina de cada tarjeta de archivo dentro de `DocumentsUI` un pequeño **icono de dos flechas en diagonal (`⤢`)** correspondiente a la acción nativa **`ACTION_QUICK_VIEW` / Abrir documento**. En la implementación AOSP de `DocumentsUI` del C10, pulsar ese icono sobre un `.apk` alojado en la unidad USB lanza `ACTION_VIEW` con tipo MIME `application/vnd.android.package-archive`, entregándoselo directamente a **`com.android.packageinstaller`**.

---

## 5. Guía Paso a Paso Práctica en `Qonecta Companion`

Si quieres probar y verificar este flujo completo en tu propio vehículo (siempre estacionado en **`P`**):

1. **Preparar el Pendrive USB (opcional si solo quieres explorar carpetas, necesario si vas a instalar un APK):**
   - Conecta un pendrive USB-C al móvil, abre **Qonecta Companion**, despliega **«🆘 Instalar desde cero»** y pulsa **«💾 1. Guardar APK en Pendrive USB»**.
   - Conecta ese pendrive al puerto USB de datos del Leapmotor C10.
2. **Levantar Hotspot y Servidor `:8788` en Companion:**
   - Pulsa **«📶 2. Encender Hotspot»** en Companion y activa el Punto de acceso Wi-Fi de tu teléfono.
   - Regresa a Companion y pulsa **«🔄 3. Actualizar IP»** *(si activas **`🛠️ Experto`** arriba a la derecha, verás en tiempo real todas las interfaces de red detectadas —`swlan0`, `ap0`, `wlan1`— y el registro en vivo de cada conexión entrante desde el coche)*.
3. **Conectar el C10 y abrir el Explorador de Archivos:**
   - Conecta el Wi-Fi del C10 al Hotspot de tu móvil *(sin Proxy y dejando el DNS en automático)*.
   - Abre **Vivaldi** en el C10 e introduce la dirección mostrada por Companion (habitualmente `http://192.168.43.1:8788/portal`).
   - Pulsa el **Botón 2 Amarillo («📁 2. ABRIR ARCHIVOS DEL COCHE»)**: verás abrirse el explorador `Archivos` (`DocumentsUI`) del sistema, desde donde puedes navegar tanto por el almacenamiento compartido como por tu unidad USB conectada.
4. **Una vez instalado `Qonecta Unit`:**
   - Ya no necesitas Vivaldi para abrir el explorador: dentro de `Qonecta Unit` tienes un botón directo **«Archivos del coche»** que lanza `com.android.documentsui` con un solo toque, y desde `v0.1.17` todas las actualizaciones futuras llegan directamente desde Companion con **«⚡ Actualizar Coche en 1 Toque»**.
