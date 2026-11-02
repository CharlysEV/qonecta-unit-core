# Plan de fork Android honesto para el C10

Objetivo: un receptor de **Android Auto que corra EN LA UNIDAD del C10** (head unit), honesto y libre, como alternativa a LeapLink pero **sin sus dos trucos sucios** (ni disfraz de nombre de paquete `com.tencent.qqmusiccar`, ni identidad MFi sacada de un Carlinkit, ni escritura de la lista blanca del coche). Es un proyecto distinto de Qonecta+: Qonecta+ corre en el móvil y usa el C10 como pantalla remota por QDLink; este fork correría dentro del C10 y el móvil se conectaría a él.

## 1. Base recomendada: DiAuto (no OpenAutoLink)

**Base = `ref/diauto`** (shihabal3amri), y con bastante diferencia.

- Es ya un **receptor de AA en head unit** y comparte el **mismo ancestro que Qonecta+**: `namespace com.andrerinas.openheadunit` idéntico (`diauto/app/build.gradle.kts`; Open Headunit de andreknieriem, revival del `headunit` de Mike Reid). Eso permite cherry-pick de paquetes enteros entre ambos proyectos sin fricción de licencia.
- Recibe AA **directamente en Kotlin/Java** (+ffmpeg en `diauto/app/src/main/cpp`), **sin companion ni dongle**. Pila de vídeo muy madura: `decoder/VideoDecoder.kt` (H.264/HEVC, recuperación por keyframe, backpressure) y render en `view/ProjectionView.kt` / `view/GlProjectionView.kt`.
- **OpenAutoLink queda descartado como base**: asume Android Automotive OS (AAOS) de GM, con Car App Library + Templates Host + VHAL por `CarPropertyManager`, nada de lo cual existe en el C10 (LeapOS = Android normal). Su recepción exige `aasdk` nativo (C++/JNI, GPLv3) + companion en el móvil: un retroceso enorme. De OpenAutoLink solo nos interesa, y más adelante, su **capa EV en Kotlin** (ver Fase 4).

**Licencia resultante:** el fork hereda **AGPL-3.0-or-later** (`diauto/LICENSE`), lo mismo que ya es Qonecta+, así que se puede compartir código en ambos sentidos sin problemas de licencia.

## 2. Identidad propia honesta

- **Nombre de app propio** y **`applicationId` propio** (ejemplo coherente con la familia actual: app "Qonecta Unit", `applicationId com.headqlink.unit`; el nombre final es una decisión, ver abajo). **Nunca** reutilizar el `applicationId` de DiAuto ni, por supuesto, disfrazarse de `com.tencent.qqmusiccar`.
- Conservar el `namespace com.andrerinas.openheadunit` (es solo el espacio de **clases** heredado, no la identidad instalada) para poder seguir cherry-pickeando entre DiAuto, Qonecta+ y el fork.
- **Firma propia**: keystore nuevo nuestro. (El `applicationId com.andrerinas.headunitrevived` de DiAuto, en `diauto/app/build.gradle.kts:71`, y su flavor `.bydhudtest` en `:164`, se sustituyen por los nuestros.)
- Icono y etiqueta propios; descargo de no afiliación (las marcas "Android Auto", "QDLink", "Leapmotor" solo para indicar compatibilidad).

## 3. Pasos concretos del fork

### 3.1 Qué quitar (acoplamiento a BYD/FYT)
Retirar **limpiamente** todo lo que habla con servicios que no existen en el C10 (si se dejan restos, intentarán hablar con servicios BYD inexistentes y fallarán):

- Todo el paquete `diauto/app/.../hud/` (23 ficheros): `BydClusterOutput.kt`, `BydHudBridge.kt`, `BydGuidance.kt`, `BydFactoryTurnCode.kt`, `BydHudProtocol/Bridge/Payload`, `BydVehicleProbe`, `BydStandalone*`, `BydStarterBridge`, `DiLink51ClusterMonitor.kt`, `ClusterMapScreen/Config/TurnCardView`, `DashboardGuidance`.
- `diauto/.../com/byd/spi/ipc/cursor/BinderCursor.java` (IPC con `com.byd.car.server.provider.CarServiceProvider`).
- `diauto/.../utils/BydFullscreen.kt`.
- En `diauto/app/src/main/AndroidManifest.xml`: los `queries` a `com.byd.clusterdebug`, `com.byd.amapservice`, `com.ts.car.someip.service` (`:13-16`); y en `app/src/debug/AndroidManifest.xml` los permisos `BYDAUTO_INSTRUMENT_COMMON` y `com.byd.car.server.PROVIDER`.
- `diauto/.../connection/carkey/fyt/` (teclas de unidades aftermarket FYT, con `setprop` por root): se reemplaza por la entrada por AA o se elimina.
- Nota sobre GM: no hay nada que quitar porque **no** partimos de OpenAutoLink; su cluster/HUD (Templates Host/AAOS/OnStarTurnByTurnManager) **no es portable** al C10 y no se copia.

Lo que **se queda** (no es de BYD): `aap/` (protocolo AAP + SSL + protobuf), `decoder/`, `view/`, el núcleo de `connection/` y la UI `main/`.

### 3.2 Qué adaptar al C10
- **Resolución y fps**: fijar la resolución/fps que negocia AA al panel del C10 (como referencia, LeapCarPlay usa 60 fps por defecto con respaldo a 30). Medir térmica y rendimiento del decodificador en el hardware real.
- **Entrada de AA (transporte)**: decidir vía (ver Fase 1). DiAuto trae tres: USB/AOA (`connection/UsbAccessoryMode.kt`, `MANUFACTURER "Android"` / `MODEL "Android Auto"`), inalámbrico nativo (`connection/NativeAaHandshakeManager.kt`: RFCOMM sobre `AA_UUID 4de17a00-52cb-11e6-bdf4-0800200c9a66` + WPP, el móvil conecta a `credIp:5288`) y Self Mode. Hay que ver cuál permite la pila del C10.
- **Wi-Fi del C10**: si se va por inalámbrico, `connection/WifiDirectManager.kt` (grupo P2P) o SoftAP (`utils/HotspotManager.kt`). Incógnita: si el C10 deja a nuestra app abrir un service record RFCOMM y levantar SoftAP/P2P.
- **Detección/etiqueta Leapmotor**: fijar nombre de head unit/modelo en la negociación AA (`ServiceDiscoveryResponse`), igual que LeapCarPlay fija `DEFAULT_OEM_LABEL = "Leapmotor"` en `leapcarplay/.../AirPlayPersistence.kt:73`.
- **Cluster: diferir**. El de DiAuto es salida BYD. La pieza genérica reutilizable es el mensaje de AA `aap/protocol/messages/ClusterDisplayServices.kt`. Para el cuadro del C10 habría que **descubrir en el coche** su canal propietario (broadcast tipo AutoNavi, SOME/IP u otro), replicando el *patrón* de DiAuto, no su código BYD. Alternativa de segunda pantalla: el patrón de `leapcarplay/.../PresentationDisplaySelector.kt:10,14` (elige la presentation display "HDMI2") + `CarPlaySecondaryDisplay.kt`, **si** el C10 expone una pantalla secundaria así.

### 3.3 Qué reutilizar de Qonecta+ (mejora la recepción; es agnóstico del sentido)
- Robustez de decodificación que Qonecta+ añadió y **no** está en DiAuto: `decoder/video/CorruptionConcealmentPolicy.kt`, `DecodeLatencyMonitor.kt`, `PictureCredibilityPolicy.kt`, `VideoFeedThrottlePolicy.kt`, `DecoderExceptionPolicy.kt`, `HeadlessDriver.kt`.
- Jitter-buffer de audio adaptativo de `decoder/audio/` (`AdaptiveJitterPolicy`, `AdaptivePcmBuffer`, `AudioJitterBufferPolicy`, `AacDecoderRecovery`, `LatencyRecoveryPolicy`).
- `aap/AapVideo.kt` + `aap/FragmentedMessageAudit.kt` (descartar unidad de acceso incompleta sin emborronar imagen).
- `ssl/BundledRootTrustManager.kt` y `view/PerformanceOverlay.kt`.
- Traducciones i18n (`values-es`, `-de`, `-fr`, `-it`, `-pt-rPT`...).

**NO traer de Qonecta+**: el motor QDLink entero (`com.headqlink.link`, 286 .java + 20 .kt: `SspSession`, `VideoPipeline`, `VideoEncoder`, `LeapTls`...) porque resuelve el problema inverso; `ThermalPolicy.java`, que regula el **encoder de salida** (una unidad receptora **no codifica**); y la app de producto (`CarUi`, `RoutePlanner`, cargadores DGT/Swiss/BNetzA, nube Leapmotor, Home Assistant, etc.).

## 4. Permisos: solo los que de verdad hacen falta

| Permiso | Para qué | ¿Necesario? |
|---|---|---|
| `SYSTEM_ALERT_WINDOW` | Lanzar la proyección como overlay (`diauto/.../AapService.kt:2845`) | Sí |
| `RECEIVE_BOOT_COMPLETED` | Autoarranque (`BootCompleteReceiver`) | Sí (deseable) |
| `WRITE_SETTINGS` | Hotspot + brillo/modo día-noche | Solo si se usa SoftAP/tema por sistema; se pide con `ACTION_MANAGE_WRITE_SETTINGS` |
| Bluetooth | Handshake de AA inalámbrico | Solo si vía inalámbrica |
| `PACKAGE_USAGE_STATS` | Seguir el cluster BYD (`DiLink51ClusterMonitor`) | **Fuera** (se va con `hud/`) |
| `ACCESS_SUPERUSER` + Shizuku/libsu | `setprop` en FYT | **Fuera** (se va con `carkey/fyt/`) |
| `INTERACT_ACROSS_USERS_FULL` | Proteger el `ShizukuProvider` | **Fuera** (se va con Shizuku) |

**Confirmación de limpieza (verificado en estático):**
- **NO usamos `WRITE_SECURE_SETTINGS`** (cero ocurrencias en todo `diauto`).
- **NO tocamos la lista blanca del coche**: no hay `allowUnknownSources` ni `basicwhitelist`. Los únicos "whitelist" que quedan son el `packageWhiteList` del protobuf de AA (parte **normal** del protocolo) y un `dumpsys deviceidle whitelist` en `diauto/scripts/install.sh:24` (exención de doze, que lanza el **shell de ADB** del desarrollador, **no la app**).
- El `com.tencent.qqmusiccar...` que aparece es **solo un intent-action** para escuchar las teclas del volante (`diauto/app/src/main/AndroidManifest.xml:268`, `connection/CarKeyReceiver.kt:41,66`, `connection/carkey/CarKeyBroadcastReceiver.kt:44,93`), **no** un `applicationId` disfrazado. Es lo contrario del truco de LeapLink.
- **Cero telemetría, sin gate por VIN** (a diferencia de LeapLink, que envía VIN+logs).

## 5. Instalación y prueba desde un pen (vía honesta)

**Respuesta directa a tu pregunta: sí, no hace falta descargar la app por Vivaldi.** El APK (firmado con **nuestra** clave) se lleva **en un pen** y se abre con el **gestor de archivos** del C10 (o se pasa por ADB si el C10 lo permite). El C10 pedirá permitir **"instalar apps de orígenes desconocidos"** para ese origen (gestor de archivos/pen/navegador); eso lo habilita **el dueño en su propio coche** (vía honesta). Como plantilla de instalación por ADB sirve `diauto/scripts/install.sh` (`adb install -r`, `pm grant`, `appops SYSTEM_ALERT_WINDOW allow`, `am start`); ojo: lo que toca ajustes seguros ahí lo hace el **shell de ADB**, no la app.

**Qué incógnita resuelve esa prueba:** si el C10 (1) deja **instalar** un APK de orígenes desconocidos con **nombre y firma propios**; (2) lo **arranca y lo muestra** en marcha (que su launcher/lista blanca no lo oculte); y (3) completa el ciclo: handshake con el S25 Ultra + **proyecta AA en la pantalla**. Evidencia previa (no concluyente): un **C10 europeo con Android 12 instaló y arrancó DiPlay** (paquete propio `com.shihab.diplay`) —issue #119, `diplay/docs/ISSUE-AUDIT-2026-10-03.md:117`— pero **se desconectaba al minuto** por USB/hotspot/P2P (encaja con el corte de USB del C10 que ya conocemos). O sea: instalar y arrancar **fue posible** en ese C10; falta confirmar estabilidad y el coche del usuario.

## 6. Riesgos y preguntas abiertas (solo el coche las resuelve)

- **El muro real**: ¿deja el C10 instalar y **ejecutar en primer plano** una app de terceros por vía honesta, o su launcher/whitelist solo arranca paquetes pre-aprobados? Si la bloqueara, la **única** vía aceptable es habilitarlo en los ajustes del propio coche; **nunca** el disfraz de paquete.
- ¿Hará el S25 Ultra AA **inalámbrico** (RFCOMM sobre `AA_UUID`) contra una app que corre **en** la unidad? ¿Permite la pila BT/Wi-Fi del C10 abrir service record RFCOMM y SoftAP/P2P?
- **AA 17.4+** rompe casi todos los triggers inalámbricos de terceros salvo dongle/Native Mode/Headunit Server; afecta a cómo el teléfono entrega AA a la unidad.
- ¿Se pueden conceder `WRITE_SETTINGS` y `SYSTEM_ALERT_WINDOW` en el Android del C10 (¿expone las pantallas de concesión estándar?)? ¿Shizuku/root? (previsiblemente no; son opcionales).
- ¿Funciona la ruta **USB/AOA** móvil↔unidad del C10, con los **cortes de USB** ya conocidos del C10? La desconexión al minuto del issue #119 podría repetirse.
- Rendimiento y **térmica** del decodificador en el hardware real (MediaCodec H.264/HEVC vs FFmpeg software).
- ¿Qué canal, si alguno, expone el C10 para el **cuadro de instrumentos**? Descubrir en el coche, como DiAuto hizo con BYD.

## 7. Orden de trabajo por fases

- **Fase 0 — Preparación (en PC):** fork de DiAuto, aplicar identidad propia (`applicationId`, nombre, icono, keystore), quitar todo lo BYD/FYT (§3.1), dejar que compile. Smoke test en un Android normal haciendo de "unidad" de prueba. *(La compilación la hace el usuario en su entorno; aquí no se ejecuta nada.)*
- **Fase 1 — Mínima: arrancar y pintar AA en el C10.** Recepción AA por la vía que el C10 permita (empezar por **USB/AOA**, lo más simple), decoder H.264 y render a pantalla completa (`ProjectionView`/`GlProjectionView`). Instalar **desde pen** y lograr que el S25 proyecte AA en la pantalla del C10. **Sin cluster, sin HUD.** Esta fase resuelve la incógnita central ("¿lo admite el C10?").
- **Fase 2 — Robustez:** incorporar las policies de recepción de Qonecta+ (`decoder/video` + `decoder/audio` + `FragmentedMessageAudit`), ajustar resolución/fps al panel del C10, **estabilizar la reconexión** (clave por el corte de USB), i18n.
- **Fase 3 — Cluster / segunda pantalla (si el C10 lo permite):** investigar en el coche el canal del cuadro; portar `ClusterDisplayServices.kt` (mensaje AA genérico) + patrón "descubrir canal propietario y enviar guidance normalizada" de DiAuto, o la segunda pantalla por presentation display (patrón HDMI2 de LeapCarPlay).
- **Fase 4 — Pulido/extra (opcional):** modelo de energía EV para Google Maps (capa Kotlin de OpenAutoLink: `EvLearnedRateEstimator.kt`, `VehicleEnergyForecast.kt`, `vehicle_energy_model.proto` como sensor tipo 23), alimentado con señales del C10, con el "Arrive ~N%". Marcarlo siempre como **no calibrado** (el propio OpenAutoLink lo advierte).

### Obligaciones de licencia (AGPL-3.0)
Publicar el fuente completo correspondiente, conservar avisos y **atribuir** a Open Headunit/andreknieriem, al original de Mike Reid y a DiAuto/shihabal3amri, indicar los cambios y cumplir la cláusula de red (§13). Todo coherente con Qonecta+, que ya es AGPL.