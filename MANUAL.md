# Qonecta Unit CORE (`v1.1.8-core`) · Manual de usuario

**Idiomas:** **Español** · [Português](MANUAL.pt.md) · [English](MANUAL.en.md) · [Italiano](MANUAL.it.md) · [Deutsch](MANUAL.de.md) · [简体中文](https://qonecta.app/manual?lang=zh&edition=core)

Guía para conductores de **Qonecta Unit CORE** (`v1.1.8-core`), desarrollada por **[CharlysEV](https://github.com/CharlysEV)** ([CharlysEV/qonecta-unit-core](https://github.com/CharlysEV/qonecta-unit-core)).

- 🌐 **Portal oficial e instalador Vivaldi (`/go`):** [https://qonecta.app](https://qonecta.app) · [https://qonecta.app/go](https://qonecta.app/go)
- 📖 **Manual web ilustrado:** [https://qonecta.app/manual](https://qonecta.app/manual?lang=es&edition=core)

> [!IMPORTANT]
> **Edición clásica / CORE — SOLO Android Auto y Apple CarPlay:**
> Esta versión está diseñada exclusivamente para proyectar **Android Auto** y **Apple CarPlay** con la máxima estabilidad y fluidez. **No incluye el sistema Extendido** (ni panel lateral, ni telemetría del coche, ni app Companion, ni procesos en segundo plano al arrancar).

> [!WARNING]
> Haz toda la instalación y configuración **con el coche aparcado**. No manipules el móvil ni ajustes avanzados mientras conduces.

---

## Índice

1. [Qué es Qonecta Unit CORE y qué necesitas](#1-qué-es-qonecta-unit-core-y-qué-necesitas)
2. [Instalación en el Leapmotor C10](#2-instalación-en-el-leapmotor-c10)
3. [Conectar Android Auto (Wi-Fi 5 GHz, Hotspot o USB)](#3-conectar-android-auto-wi-fi-5-ghz-hotspot-o-usb)
4. [Conectar Apple CarPlay (Wi-Fi Direct, Hotspot o USB)](#4-conectar-apple-carplay-wi-fi-direct-hotspot-o-usb)
5. [Segunda pantalla opcional (Cuadro de instrumentos)](#5-segunda-pantalla-opcional-cuadro-de-instrumentos)
6. [Ajustes útiles e idioma](#6-ajustes-útiles-e-idioma)
7. [Solución de problemas](#7-solución-de-problemas)
8. [Informar de incidencias en GitHub Issues](#8-informar-de-incidencias-en-github-issues)
9. [Apoyar el proyecto y aviso legal](#9-apoyar-el-proyecto-y-aviso-legal)

---

## 1. Qué es Qonecta Unit CORE y qué necesitas

**Qonecta Unit CORE** se instala directamente en la pantalla Android del **Leapmotor C10** y convierte la unidad en un receptor nativo de **Android Auto** y **Apple CarPlay**, sin necesidad de dongles externos ni apps de espejo.

| Característica | Detalle |
|---|---|
| **Protocolos** | **Android Auto** (móviles Android) y **Apple CarPlay** (iPhone) |
| **Modos de conexión** | Wi-Fi Direct 5 GHz · Hotspot · Cable USB de datos |
| **Segunda pantalla (Cuadro)** | Opcional (`Cuadro: OFF` por defecto para máxima seguridad; activable en 1 toque) |
| **Edición CORE** | **Sin sistema Extendido**, sin permisos de micrófono (`RECORD_AUDIO` eliminado), sin servicios al arrancar el coche |
| **Idiomas de menú** | Español, Português, English, Italiano, Deutsch y **Chino Simplificado (`简体中文` / `zh-rCN`)** |
| **Estabilidad AA / CarPlay** | Fallback automático AA `1080p H.264`, buffer de música CarPlay (`700 ms`), reanudación tras Radio y controles 3 dedos |
| **Diagnóstico 100 % USB** | Exportación `.txt` con carpeta USB recordada (audio CarPlay AAC/Opus/LPCM, `AudioFocus` y Bluetooth) sin escribir memoria interna |

**Qué necesitas:**
- Un **Leapmotor C10 / B10 / B05 / B03X / T03 / C16** (u otra unidad Android compatible) donde puedas instalar APKs.
- Un **pendrive USB** formateado en `FAT32` *(conectar el móvil por cable no sirve como unidad de instalación)*.
- Tu teléfono **Android** (con *Android Auto*) o tu **iPhone**.

---

## 2. Instalación en el Leapmotor C10

1. Descarga `QonectaUnit_1.1.8-core.apk` desde **[GitHub Releases](https://github.com/CharlysEV/qonecta-unit-core/releases)** o **[qonecta.app](https://qonecta.app)**.
2. Cópialo a la raíz de un pendrive USB **`FAT32`** y conéctalo al **puerto USB de datos** del coche.
3. Abre **Vivaldi** en el coche, entra en **`https://qonecta.app/go`**, pulsa **«Abrir Explorador de Archivos»**, entra en `☰` → tu pendrive USB y toca el icono **`⤢`** junto al `.apk` *(no toques el texto del nombre)* para instalarlo.
   - *Nota:* el paquete usa el ID compatible con la pantalla del C10 para aparecer en el cajón de apps.
4. Abre **Qonecta Unit CORE** y concede los permisos solicitados (**Dispositivos cercanos / Bluetooth** y **Ubicación** para Wi-Fi Direct).
5. En **Avanzado y Diagnóstico** puedes activar la píldora flotante **«← Volver a Qonecta»** y exportar informes redactados directamente al pendrive USB en 1 toque.

---

## 3. Conectar Android Auto (Wi-Fi 5 GHz, Hotspot o USB)

En la pantalla principal puedes activar uno o varios modos a la vez:

### A. Wi-Fi 5 GHz (recomendado, sin cables)
1. Asegúrate de tener tu móvil **vinculado por Bluetooth** con el coche y el **Wi-Fi del coche encendido**.
2. Deja marcada la tarjeta **«Wi-Fi 5 GHz»**.
3. Pulsa **«Conectar Android Auto»** o toca directamente la tarjeta de tu móvil en la fila inferior **«Móviles vinculados»**.
4. Si en el móvil tenías activado *«Cambiar a datos móviles»* o *«Aceleración de red»*, desactívalo si notas cortes al conectarse al Wi-Fi Direct del coche.

### B. Hotspot Móvil (Internet en el coche + Android Auto)
1. Activa el punto de acceso (Hotspot) en tu teléfono y conecta el Wi-Fi del coche a esa red.
2. Activa la tarjeta **«Hotspot Móvil»** y pulsa **«Conectar Android Auto»**.

### C. Cable USB (directo + carga)
1. Deja marcada la tarjeta **«Cable USB»** (puedes tener **Wi-Fi 5 GHz** marcado a la vez sin problema).
2. Enchufa tu móvil con un **cable USB de datos** en el **puerto USB de datos** del coche (no en los puertos traseros que son solo de carga).
3. En la pantalla aparecerá `🔌 USB listo` y se conectará automáticamente, o puedes pulsar **«Conectar USB»**.
4. Si enchufas el cable mientras ya estás proyectando por Wi-Fi, te preguntará si deseas cambiar a Cable USB.

---

## 4. Conectar Apple CarPlay (Wi-Fi Direct, Hotspot o USB)

Pulsa el botón **«Apple CarPlay»** en la portada (o enchufa directamente tu iPhone por USB):

### A. CarPlay inalámbrico (Wi-Fi Direct 5 GHz — recomendado)
1. Vincula primero tu **iPhone por Bluetooth** con el coche y mantén Bluetooth y Wi-Fi activos en el iPhone.
2. En la pantalla de CarPlay, toca tu iPhone en la fila inferior **«iPhones vinculados»** o pulsa **«Conectar CarPlay»**.
3. Acepta el aviso de **CarPlay** en el iPhone la primera vez.

### B. CarPlay por Hotspot del coche (alternativa)
Si en tu coche notas inestabilidad con Wi-Fi Direct, selecciona **«Hotspot del coche»**, introduce el nombre y contraseña de la Zona Wi-Fi del coche y enciéndela (apagando el Wi-Fi cliente en la barra superior del C10).

### C. CarPlay por Cable USB
1. Enchufa el iPhone al **puerto USB de datos** del coche con un cable de datos.
2. Pulsa **«CarPlay USB»** (se resaltará automáticamente al detectar el iPhone).
3. Desbloquea el iPhone y acepta **«Confiar»** y **«CarPlay»**.
4. La primera vez, Android puede pedir permiso para un **túnel VPN local** (es estrictamente local entre el puerto USB y la pantalla, no envía nada a Internet).

> **Truco dentro de CarPlay:** desliza hacia abajo con **tres dedos** en cualquier momento para abrir los ajustes de CarPlay.

---

## 5. Segunda pantalla opcional (Cuadro de instrumentos)

Por seguridad y ligereza, la segunda pantalla viene **desactivada por defecto (`Cuadro: OFF`)**.
- Si quieres ver el **mapa de navegación en directo** (y avisos discretos de canción/giro) en el cuadro del C10, pulsa el botón **`Cuadro: OFF`** en la barra superior (disponible tanto en Android Auto como en CarPlay) para cambiarlo a **`Cuadro: ON`**.
- El cambio se aplica en la **siguiente conexión** (desconecta y vuelve a conectar).
- No usa bucles de sondeo ni servicios de telemetría OEM.

---

## 6. Ajustes útiles e idioma

- **Cambiar idioma:** entra en **Ajustes → Idioma** para elegir **Español, Português, English, Italiano, Deutsch, 简体中文** o el idioma automático del sistema. El cambio se aplica tanto a **Android Auto** como a **Apple CarPlay**.
- **Tamaño de pantalla / DPI:** en **Ajustes** (Android Auto) o **Ajustes de CarPlay → Tamaño de iconos y texto** puedes ajustar la escala de iconos a tu gusto.
- **Cierre limpio:** el botón **«Cerrar»** de la portada detiene por completo Android Auto, CarPlay y el cuadro para no dejar nada en segundo plano.

---

## 7. Solución de problemas

- **El cable USB no detecta el móvil:** asegúrate de usar un **cable de datos** (no solo carga) y el **puerto USB de datos** del coche. Si el móvil pregunta el modo USB, acepta *Transferencia de archivos / Android Auto*.
- **Wi-Fi 5 GHz no despierta el móvil automáticamente:** toca tu móvil en la fila inferior **«Móviles vinculados»**. Si tu móvil ya estaba conectado a otro perfil Bluetooth del coche, desconéctalo 1 segundo o desactiva *«Cambiar entre redes»* en los ajustes Wi-Fi del teléfono.
- **CarPlay Wi-Fi Direct se queda atascado:** abre **Ajustes de CarPlay → Ayuda y reinicio de Wi-Fi CarPlay → Reiniciar Wi-Fi CarPlay**.
- **Sin audio o volumen bajo:** comprueba el canal de audio del coche (Bluetooth/Multimedia) y que el móvil no esté silenciado.

---

## 8. Informar de incidencias en GitHub Issues

Si encuentras cualquier fallo, problema de conexión o sugerencia de mejora, **déjanos una incidencia en la pestaña de Issues de GitHub**:

👉 **[https://github.com/CharlysEV/qonecta-unit-core/issues](https://github.com/CharlysEV/qonecta-unit-core/issues)**

*(También puedes abrir **Ajustes → Informar de un problema** dentro de la app y escanear el código QR con tu móvil).*

Para ayudarnos a solucionarlo rápido, indica:
1. Modelo de coche y versión de firmware (p. ej. Leapmotor C10).
2. Modelo de teléfono y versión de Android / iOS.
3. Si ocurrió en **Android Auto** o **Apple CarPlay**, y el modo usado (**Wi-Fi 5 GHz**, **Hotspot** o **USB**).
4. Si puedes, adjunta el log exportado desde **Ajustes → Depuración → Exportar registros** (Android Auto) o **Ajustes de CarPlay → Guardar informe de diagnóstico**.

---

## 9. Apoyar el proyecto y aviso legal

**Qonecta Unit CORE** es un proyecto gratuito, libre y sin anuncios. Si quieres apoyar su desarrollo (también accesible desde el botón **«☕ Apoyar»** con códigos QR offline en la app):

- ☕ **Ko-fi:** [https://ko-fi.com/charlysev](https://ko-fi.com/charlysev)
- 💙 **PayPal:** [https://www.paypal.me/CharlysEV](https://www.paypal.me/CharlysEV)

Software libre bajo licencias AGPL-3.0 / GPL-3.0. Proyecto independiente no afiliado con Leapmotor, Google ni Apple. Úsalo siempre con responsabilidad respetando las normas de tráfico.
