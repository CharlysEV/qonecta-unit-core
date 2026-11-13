# 🚗 Qonecta Unit (`v0.1.18`)

<p align="center">
  <b>Idioma / Language / Sprache / Idioma:</b><br/>
  🇪🇸 <b>Español</b> ·
  <a href="README.en.md">🇬🇧 <b>English</b></a> ·
  <a href="README.de.md">🇩🇪 <b>Deutsch</b></a> ·
  <a href="README.pt.md">🇵🇹 <b>Português</b></a>
</p>

<p align="center">
  <b>por <a href="https://github.com/CharlysEV">CharlysEV</a></b> · Android Auto, CarPlay, Panel Extendido y Planificador EV nativo en la pantalla del <b>Leapmotor C10</b>
</p>

<p align="center">
  <a href="https://github.com/CharlysEV/qonecta-unit/releases/latest"><b>⬇️ Descargar Qonecta Unit (Coche)</b></a> ·
  <a href="https://github.com/CharlysEV/qonecta-unit-companion/releases/latest"><b>📱 Descargar Qonecta Companion (Móvil)</b></a>
</p>

<p align="center">
  <b>Manuales paso a paso:</b>
  <a href="docs/MANUAL_INSTALACION_QONECTA_UNIT_C10.md">🇪🇸 Español</a> ·
  <a href="docs/MANUAL_INSTALLATION_QONECTA_UNIT_C10.en.md">🇬🇧 English</a> ·
  <a href="docs/INSTALLATIONSANLEITUNG_QONECTA_UNIT_C10.de.md">🇩🇪 Deutsch</a> ·
  <a href="docs/MANUAL_INSTALACAO_QONECTA_UNIT_C10.pt.md">🇵🇹 Português</a>
</p>

---

**Qonecta Unit** es la aplicación gratuita y de código abierto que se instala directamente **dentro de la pantalla original del Leapmotor C10** (tanto BEV como REEV) para convertirla en un receptor nativo de **Android Auto** (inalámbrico o por cable USB) y **Apple CarPlay**, acompañada de un **Panel Extendido C10** con telemetría en tiempo real, historial de viajes, cálculo de costes eléctricos/combustible y **Planificador de Rutas EV con paradas de carga**.

Funciona sincronizada en vivo con **[Qonecta Companion](https://github.com/CharlysEV/qonecta-unit-companion)** en tu teléfono Android, permitiendo incluso **conectar únicamente por Bluetooth (sin encender el Wi‑Fi 5 GHz)** y respetando al 100 % la **Radio FM/DAB original** y los mandos del volante.

---

## ✨ Novedades principales en `v0.1.18`

- **☑️ Casillas de conexión independientes (`☑ / ☐`) y Modo Solo Bluetooth real:**
  Marca o desmarca libremente **`Bluetooth`**, **`Wi-Fi 5 GHz`**, **`Hotspot Móvil`** y **`Cable USB`** tanto en el coche como en el móvil. Si dejas marcado únicamente **`☑ Bluetooth`**, ni el coche ni el teléfono levantan redes Wi‑Fi 5 GHz ni despiertan Android Auto inalámbrico: sincronizas viajes, tarifas, rutas de Google Maps e Internet ligero por Bluetooth puro, y puedes dejar el cable USB solo para cargar batería sin lanzar proyecciones.
- **📻 Convivencia limpia al 100 % con la Radio FM/DAB y los mandos del volante:**
  Gestión de foco de audio transitorio (`AUDIOFOCUS_GAIN_TRANSIENT`) con liberación garantizada en pausa/parada/error y filtrado estricto de teclas del volante para que la radio nativa del C10 jamás se quede muda ni bloqueada.
- **🔄 Sincronización instantánea `Qonecta Unit ↔ Qonecta Companion`:**
  Las casillas marcadas en la pantalla del coche y el estado del vínculo se muestran en tiempo real en la pantalla principal del móvil.
- **🎓 Modo Tutorial interactivo en `Qonecta Companion` (5 pasos en vivo):**
  Asistente guiado para tu primera instalación en el C10 que comprueba automáticamente el APK en el móvil, confirma el guardado en el Pendrive USB‑C, detecta cuándo enciendes el Hotspot (cero Proxy y cero DNS) e incluye un **radar en directo** que te avisa en el milisegundo exacto en que **Vivaldi** del coche abre el portal local, junto con un dibujo claro del icono de dos flechas `⤢` en *Archivos*.
- **⚡ Actualización OTA en 1 solo toque:**
  Completada la primera instalación, todas las versiones futuras se envían directamente del móvil al coche en un solo toque (con verificación `SHA-256`), sin volver a usar pendrive ni navegador.

---

## 🧩 Las dos herramientas del ecosistema

| Herramienta | Dónde se instala | Descarga oficial | Qué hace |
| :--- | :--- | :--- | :--- |
| **🚗 Qonecta Unit** | Pantalla del **Leapmotor C10** | [Releases · `qonecta-unit`](https://github.com/CharlysEV/qonecta-unit/releases/latest) | Android Auto / CarPlay, Panel Extendido C10, Viajes, Consumos BEV/REEV y Planificador EV con paradas |
| **📱 Qonecta Companion** | Tu **móvil Android** | [Releases · `qonecta-unit-companion`](https://github.com/CharlysEV/qonecta-unit-companion/releases/latest) | Modo Tutorial C10 en vivo, puente de rutas hacia Google Maps, Internet por Bluetooth, tarifas/viajes y actualización del coche en 1 toque |

> 💡 **¿En qué se diferencia de [Qonecta+](https://github.com/CharlysEV/qonecta)?**
> `Qonecta+` funciona desde el móvil proyectando por *QDLink/SSPLink* sin instalar nada en el coche. En cambio, **`Qonecta Unit` corre de forma nativa dentro del hardware del C10** y se acompaña de **`Qonecta Companion`** en el móvil. Si tenías `Qonecta+` instalada en tu teléfono, desactiva su inicio automático mientras uses `Qonecta Unit` para que no compitan por Android Auto a la vez.

---

## 🚀 Cómo instalarla por primera vez en 3 minutos

1. Instala **[Qonecta Companion](https://github.com/CharlysEV/qonecta-unit-companion/releases/latest)** en tu móvil Android y descarga el navegador **Vivaldi** desde la tienda oficial `LeapAppStore` de tu C10.
2. Abre **Qonecta Companion** y toca **`🎓 Abrir Modo Tutorial paso a paso (C10)`**: el asistente descargará el APK del coche, te guiará para guardarlo en un pendrive USB‑C, detectará tu Hotspot y abrirá *Archivos* en el coche desde Vivaldi para instalarlo tocando el icono de esquina **`⤢`**.
3. Consulta la guía completa en tu idioma:
   - 🇪🇸 **Español:** [docs/MANUAL_INSTALACION_QONECTA_UNIT_C10.md](docs/MANUAL_INSTALACION_QONECTA_UNIT_C10.md)
   - 🇬🇧 **English:** [docs/MANUAL_INSTALLATION_QONECTA_UNIT_C10.en.md](docs/MANUAL_INSTALLATION_QONECTA_UNIT_C10.en.md)
   - 🇩🇪 **Deutsch:** [docs/INSTALLATIONSANLEITUNG_QONECTA_UNIT_C10.de.md](docs/INSTALLATIONSANLEITUNG_QONECTA_UNIT_C10.de.md)
   - 🇵🇹 **Português:** [docs/MANUAL_INSTALACAO_QONECTA_UNIT_C10.pt.md](docs/MANUAL_INSTALACAO_QONECTA_UNIT_C10.pt.md)

---

## ☕ Apoyar el proyecto

**Qonecta Unit**, **Qonecta Companion** y **Qonecta+** son proyectos abiertos, gratuitos y sin anuncios creados con cariño para la comunidad Leapmotor. Si te resultan útiles en el día a día y te apetece invitar a un café o apoyar su desarrollo:

- ☕ **Ko-fi:** [https://ko-fi.com/charlysev](https://ko-fi.com/charlysev)
- 💙 **PayPal:** [https://www.paypal.me/CharlysEV](https://www.paypal.me/CharlysEV)

¡Muchísimas gracias!

---

## 🛠️ Compilación desde código fuente

Requiere **JDK 17**, **Android SDK 36**, **NDK `29.0.14206865`** y **CMake `3.22.1`**.

```sh
./gradlew :bridge:testDebugUnitTest :app:testGithubDebugUnitTest :app:assembleGithubCoche :companion:assembleRelease
```

> Nunca subas claves privadas (`*.keystore`, `*.jks`, `key.properties`, `firma/`). Los APKs publicados en GitHub Releases van firmados con la clave privada del mantenedor para permitir actualizar encima en 1 toque sin perder datos.

---

## 📜 Créditos y Licencia

Desarrollado por **[CharlysEV](https://github.com/CharlysEV)** como adaptación independiente para **Leapmotor C10**, a partir de [DiAuto](https://github.com/shihabal3amri/DiAuto) (README original en [README_DIAUTO.md](README_DIAUTO.md)) y [Open Headunit](https://github.com/andreknieriem/open-headunit) / [Michael Reid](https://github.com/mikereidis/headunit).

Distribuido bajo licencia **[GNU AGPL-3.0](LICENSE)**. Consulta [LICENSE](LICENSE) y [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md). *Android Auto* es una marca registrada de Google LLC. Proyecto independiente no afiliado ni respaldado por Leapmotor ni Google.
