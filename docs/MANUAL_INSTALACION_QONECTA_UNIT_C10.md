# 🚗📱 Manual Paso a Paso · Qonecta Unit & Qonecta Companion (`v0.1.17`)

Guía clara para humanos normales para instalar, vincular y actualizar **Qonecta Unit** (la app que va en la pantalla del **Leapmotor C10**) y **Qonecta Companion** (la app compañera que va en tu **móvil Android**).

> **⚠️ Seguridad ante todo:** Haz toda la instalación y el primer emparejamiento siempre con el coche estacionado en **`P`**.

---

## 🧩 ¿Qué son las dos aplicaciones?

1. **🚗 `Qonecta Unit` (`QonectaUnit_0.1.17-unit.apk` · Se instala EN EL COCHE):**
   Convierte la pantalla original del Leapmotor C10 en un receptor nativo de **Android Auto** (inalámbrico o por cable USB) y **Apple CarPlay** (USB / inalámbrico), con **Panel Extendido C10** (batería, autonomía, potencia, viajes, consumos REEV/BEV y **Planificador de Rutas EV con paradas de carga**), respetando al 100 % la Radio original y los mandos del volante.
2. **📱 `Qonecta Companion` (`QonectaUnitCompanion_0.1.17.apk` · Se instala EN TU MÓVIL ANDROID):**
   Conecta tu móvil con `Qonecta Unit` automáticamente al subir al coche para:
   - Recibir las rutas con paradas de carga del Planificador del coche y **abrirlas solas en Google Maps** (proyectándolas en Android Auto).
   - **Dar Internet por Bluetooth** al Planificador del coche sin gastar los 2 GB de datos de Leapmotor y sin encender el Hotspot.
   - **Actualizar la app del coche en 1 solo toque** desde el móvil (sin volver a usar pendrives ni navegador).
   - Sincronizar tus viajes, tarifas eléctricas/gasolina y estado del coche.

---

## ⚠️ Antes de empezar (¡Muy importante!)

- **Si tenías instalada `Qonecta+` o `HeadQLink` antigua en tu móvil:** desactívala (o desactiva su conexión automática de Android Auto) antes de usar `Qonecta Unit`, para que las dos apps del móvil no se peleen por conectarse a Android Auto a la vez.
- **Descarga en tu móvil Android los dos archivos `.apk` de la Release:**
  1. `QonectaUnitCompanion_0.1.17.apk` *(para instalar en tu móvil)*
  2. `QonectaUnit_0.1.17-unit.apk` *(déjala guardada en la carpeta `Descargas` de tu móvil; Companion la detectará automáticamente)*

---

## 📱 PARTE 1 · Instalar `Qonecta Companion` en tu móvil Android

1. Abre el archivo **`QonectaUnitCompanion_0.1.17.apk`** en tu teléfono Android y pulsa **Instalar** *(si tu móvil te pregunta permiso para instalar desde tu navegador o gestor de archivos, acéptalo)*.
2. Abre **Qonecta Companion**. Verás una pantalla limpia con solo 3 tarjetas (y un botón opcional `🛠️ Experto` arriba a la derecha que no necesitas tocar).
3. En la primera tarjeta (**Estado de conexión**), pulsa **`Dar`** en los permisos que te pida para que todo funcione solo en el bolsillo:
   - **Bluetooth (Dispositivos cercanos):** para detectar el coche al subirte.
   - **Notificaciones:** para mantener la conexión activa en segundo plano.
   - **Abrir Google Maps automáticamente (*Mostrar sobre otras apps*):** imprescindible en Android para que el móvil pueda abrir Google Maps con la ruta sin que tengas que desbloquear la pantalla.
   - **Sin ahorro de batería:** para que el móvil no corte la conexión en mitad de un viaje.

---

## 🚗 PARTE 2 · Instalar `Qonecta Unit` en el Leapmotor C10 por primera vez

Como Vivaldi en el Leapmotor C10 bloquea por seguridad la descarga directa de archivos `.apk`, la **primera instalación desde cero** se hace en **3 minutos** con un **Pendrive USB-C** y el portal web de tu propio móvil **(sin configurar Proxy y sin cambiar DNS)**:

### Paso 1 · Pasar el APK del coche al Pendrive USB
1. Conecta un **Pendrive USB-C** a tu móvil *(o usa un ordenador si prefieres copiar `QonectaUnit_0.1.17-unit.apk` directamente a la raíz del pendrive)*.
2. En **Qonecta Companion** de tu móvil, despliega la tarjeta **«🆘 Instalar desde cero»** y pulsa **«💾 1. Guardar APK en Pendrive USB»**. Elige tu pendrive y guarda el archivo.
3. Desconecta el pendrive del móvil y **enchúfalo al puerto USB de datos del Leapmotor C10** (bajo la consola central).

### Paso 2 · Encender el Punto de acceso (Hotspot) del móvil
1. En esa misma tarjeta de **Qonecta Companion**, pulsa **«📶 2. Encender Hotspot»** y activa el **Punto de acceso Wi-Fi / Compartir conexión** de tu teléfono.
2. Vuelve atrás a **Qonecta Companion** y pulsa **«🔄 3. Actualizar IP»**. Fíjate en la dirección corta que te muestra en pantalla en el punto `3️⃣` (normalmente **`http://192.168.43.1:8788/portal`** o la IP que indique tu móvil).
3. En la pantalla del coche, ve a **Ajustes → Wi-Fi** y conéctate al Wi-Fi de tu móvil normalmente *(no hace falta tocar Proxy ni DNS)*.

### Paso 3 · Abrir el Instalador desde Vivaldi en el coche
1. Abre el navegador **Vivaldi** en la pantalla del Leapmotor C10.
2. Escribe en la barra de direcciones exactamente la dirección que te muestra Companion, por ejemplo:
   ```text
   http://192.168.43.1:8788/portal
   ```
3. Se abrirá el **Instalador Qonecta Unit · C10**. Toca el **Botón 2 Amarillo**:
   > **📁 2. ABRIR «ARCHIVOS DEL COCHE» (INSTALAR DESDE USB)**
4. Se abrirá el gestor nativo **Archivos** de Android en el coche:
   - En la columna de la izquierda (o menú `☰`), toca tu **Unidad USB**.
   - Verás el archivo `QonectaUnit_0.1.17-unit.apk`.
   - **🔑 EL TRUCO CLAVE:** **NO toques el centro del icono** (eso solo lo selecciona). Toca el **icono pequeñito de dos flechas en diagonal `⤢` que aparece en la esquina del archivo APK**.
5. Se abrirá el instalador oficial de Android del coche:
   - Si es la primera vez y te pide permitir instalar aplicaciones desde *Archivos*, activa el interruptor, vuelve atrás un toque y pulsa **Instalar**.
6. ¡Listo! Ya tienes **Qonecta Unit** instalada en el cajón de aplicaciones de tu C10 (ya puedes apagar el Hotspot del móvil y quitar el pendrive).

---

## 🔗 PARTE 3 · Vincular Móvil y Coche (solo se hace una vez)

1. Abre **Qonecta Unit** en la pantalla del coche.
2. Entra en **Ajustes / Menú Extendido → Qonecta Companion** y pulsa **«Emparejar móvil»**. Aparecerá un código grande de **6 cifras** en la pantalla del coche.
3. En **Qonecta Companion** de tu móvil (Tarjeta 1):
   - **Paso 1:** Pulsa **«1. Elegir coche»** y selecciona el Bluetooth manos libres de tu Leapmotor C10.
   - **Paso 2:** Escribe las **6 cifras** que muestra el coche y pulsa **«🔗 Vincular con mi coche»**.
4. En un par de segundos verás arriba:
   > **`🟢 Conectado con Leapmotor C10 · Coche en v0.1.17-unit`**

A partir de ese instante, cada vez que entres al coche se conectarán solos sin tocar nada.

---

## 📡 PARTE 4 · Conectar Android Auto (o CarPlay)

1. Con el Bluetooth del móvil ya emparejado con el coche, abre **Qonecta Unit** en el C10 y pulsa **«Conectar teléfono»** *(o conecta el cable USB de datos si prefieres ir por cable)*.
2. La primera vez, acepta en la pantalla de tu móvil el aviso para iniciar **Android Auto inalámbrico**.
3. **Panel Extendido vs Pantalla Completa:**
   - Puedes llevar Android Auto junto al **Panel Extendido C10** (batería, consumos, viajes y Planificador EV con paradas) o pulsar **«Pantalla completa»** para ocupar toda la pantalla (un botón flotante discreto te permite volver al modo extendido cuando quieras).
4. **Radio del coche y mandos del volante blindados (`v0.1.17`):**
   - Si cambias a la **Radio FM/DAB nativa del coche**, `Qonecta Unit` cede inmediatamente el foco de audio y los botones del volante a la radio para que nunca se bloqueen ni tengas que reiniciar la pantalla.

---

## ⚡ PARTE 5 · Cómo actualizar en el futuro (en 1 SOLO TOQUE, sin USB ni Vivaldi)

Una vez que tienes instalada la versión **`v0.1.17`** en el coche, **ya no volverás a necesitar pendrive USB, ni Hotspot, ni Vivaldi para futuras actualizaciones**:

1. Guarda la nueva versión de `QonectaUnit_X.X.X-unit.apk` en tu móvil *(o descárgala directamente desde el botón **«☁️ Buscar nueva versión en Internet»** de Companion)*.
2. Con el móvil conectado al coche (`🟢 Conectado`), mira la **Tarjeta 2 («⚡ Actualizar Coche en 1 Toque»)** en **Qonecta Companion**.
3. Pulsa el botón **«⚡ Actualizar coche ahora»**:
   - El móvil enviará la nueva versión directamente al coche (en ~2 segundos si estás con Android Auto inalámbrico por Wi-Fi, o automáticamente por Bluetooth si no hay Wi-Fi).
   - El coche verificará que el archivo esté intacto (`SHA-256`) y **abrirá solo en la pantalla del C10 el cartel de Android `«¿Quieres instalar una actualización de Qonecta Unit?»`**.
   - Solo tienes que tocar **«Instalar»** en la pantalla del coche y listo.

---

## 🛠️ Preguntas Frecuentes y Modo Experto

- **¿Para qué sirve el botón `🛠️ Experto` arriba a la derecha en Companion?**
  Por defecto viene apagado para que la app sea limpia y sencilla. Si alguien quiere cacharrear, al pulsar `🛠️ Experto` aparecen ajustes avanzados: selector manual de repositorios GitHub y Token PAT privado, servidor web `:8788/:8080`, DNS manual, contador de peticiones de datos Bluetooth y botón para **descargar los registros (`ohu.log` + `logcat`) del coche directo al móvil sin pendrive** para enviarnos diagnósticos si algo falla.
- **¿Cómo descargar actualizaciones directamente desde el repositorio privado `CharlysEV/headqlink-pruebas` en Companion?**
  Si quieres usar el botón *«☁️ Buscar nueva versión en Internet»* con este repositorio privado: activa `🛠️ Experto`, pon `CharlysEV/headqlink-pruebas` en las casillas de repositorio junto con tu *Personal Access Token (PAT)* de GitHub con permiso de lectura (`repo` / `Contents: Read`) y pulsa Guardar. Si no quieres líos de tokens, simplemente descarga el `.apk` desde la página de **Releases** en tu navegador del móvil a `Descargas` y Companion lo detectará solo al instante.
- **¿Cómo desinstalar `Qonecta Unit` del coche si algún día quiero quitarla?**
  Desde dentro de `Qonecta Unit` → **Ajustes → Diagnóstico / Ajustes del sistema Android → Aplicaciones → Qonecta Unit → Desinstalar**.
