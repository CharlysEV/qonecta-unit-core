## DiAuto vs Qonecta+ — comparativa de base común

Ambos derivan del mismo proyecto, **Open Headunit** de andreknieriem (a su vez revival del `headunit` de Mike Reid). Lo confirma el `namespace = "com.andrerinas.openheadunit"` idéntico en los dos `app/build.gradle(.kts)`. Lo que cambia es el `applicationId`: DiAuto compila como `com.andrerinas.headunitrevived` (con flavor `.bydhudtest`), Qonecta+ como `com.headqlink.app` (comentario explícito en su gradle: *"headqlink: identificador propio del fork"*). Ninguno de los dos se disfraza de otra app.

### 1. Ancestro común confirmado (capa AAP / decoder / ssl / connection)

Comparé por hash los 273 ficheros `.kt/.java` de DiAuto bajo `openheadunit` contra la misma ruta en Qonecta+:
- **62 idénticos byte a byte**, **92** con misma ruta y contenido editado, **69** movidos a otro subpaquete en Qonecta+, **50** exclusivos de DiAuto.

Coincidencias concretas del núcleo del protocolo (ruta relativa idéntica bajo `app/src/main/java/com/andrerinas/openheadunit`):
- **Protocolo AAP / protobuf**: `aap/protocol/MsgType.kt`, `aap/protocol/proto/Input.java`, `aap/protocol/proto/MediaPlayback.java` (idénticos); `aap/protocol/proto/Common.java` comparte cabecera, `package com.andrerinas.openheadunit.aap.protocol.proto` y `protoc 3.25.1` en ambos (`Common.java:1-7`) — difiere sólo por regeneración. Mensajes idénticos: `TouchEvent.kt`, `KeyCodeEvent.kt`, `SensorEvent.kt`, `DrivingStatusEvent.kt`, `NightModeEvent.kt`, `LocationUpdateEvent.kt`, `ScrollWheelEvent.kt`, `MediaAck.kt`, `UpdateUiConfigRequest.kt`.
- **SSL**: `ssl/SslContextFactory.kt` y `ssl/NoCheckTrustManager.kt` idénticos; `aap/AapSslNative.kt` idéntico.
- **AAP varios**: `aap/AapDump.kt`, `aap/AapMediaPlayback.kt`, `aap/AapMessageHandler.kt`, `aap/AapReadRecoveryPolicy.kt`, `aap/ByteArrayWithLimit.kt` idénticos.
- **extractor/**: `MediaExtractorInterface.kt`, `StreamVideoExtractor.kt`, `SystemMediaExtractor.kt` idénticos; **location/**: los 4 idénticos.

La lógica central comparte estructura aunque diverja en detalle: `aap/AapVideo.kt` (reensamblado de fragmentos H.264 de AA, `VideoFragmentAssembler`, petición de keyframe al teléfono) es casi línea por línea el mismo en los dos; el de Qonecta+ sólo añade `isPayload()`, un `VideoTap` de latencia y el `discardNextAssembly` del `FragmentedMessageAudit` (hql `AapVideo.kt:241-260,346-357`). El de DiAuto, en cambio, admite un **segundo stream** (parámetros `codecName`/`forceSoftware` para el panel del cuadro BYD: diauto `AapVideo.kt:13-18`).

### 2. El cambio de sentido de la arquitectura

Las dos son la "head unit" que habla AAP con un teléfono, pero el **sumidero del vídeo** va en direcciones opuestas:

- **DiAuto = la UNIDAD que recibe y muestra**. `connection/AccessoryConnection.kt` (interfaz `sendBlocking`/`recvBlocking`/`connect`) con implementaciones `UsbAccessoryConnection`, `LibusbAccessoryConnection`, `SocketAccessoryConnection` conecta a un teléfono **remoto** (AOAP por USB o servidor Wi-Fi del móvil). El flujo es: teléfono → `AccessoryConnection` → `AapTransport`/`AapRead` → `AapVideo` → `VideoDecoder` → **`view/GlProjectionView`** (un `GLSurfaceView` que pinta en la pantalla física del coche: diauto `GlProjectionView.kt:24`). `AapService.kt` (3162 líneas) es el `Service` que orquesta todo. **El vídeo entra y se consume localmente.**

- **Qonecta+ = el MÓVIL que recibe de sí mismo y reenvía**. La misma capa AAP se usa contra la **Android Auto del propio teléfono** (modo self/loopback): `connection/projection/ProjectionConnection.kt` (misma interfaz renombrada, + `enum Type{USB,WIFI}`) con `SocketProjectionConnection`, que trae `LoopbackBindPolicy` precisamente porque *"Loopback never leaves the device"* (hql `SocketProjectionConnection.kt:75`). El vídeo recibido **no va a una Surface**: lo roba `com.headqlink.link.AaPassthroughSource`, que *"reenvía al coche, sin recodificar, el H.264 que Android Auto entrega a Open Headunit (VideoTap)"* (hql `AaPassthroughSource.java:30-37`), y sale por `SspSession` — *"sesión SSPLink en modo WiFi: el móvil es servidor TCP y el coche se conecta… formato 5A5A"* (hql `SspSession.java:24-28`), que es el protocolo QDLink. **El C10 es sólo una pantalla remota tonta.**

En una frase: **en DiAuto el teléfono es la fuente y la unidad el sumidero; en Qonecta+ el móvil es a la vez sumidero de la AA local y fuente para el coche.** `VideoSource.create()` (hql `VideoSource.java:105-116`) enruta `MODE_AA`→`AaPassthroughSource` (reenvío directo o recodificado según `aaReencode`), `MODE_AA_EXT`→passthrough recodificado con panel propio, y `MODE_APP`→`AppSource` (un `VirtualDisplay` en el móvil donde se lanza una app y se inyectan toques por accesibilidad: hql `AppSource.java:40-82`). Nada de esto existe en una unidad receptora.

### 3. Qué reutilizar DE Qonecta+ en el fork de unidad (y qué no)

El fork de unidad honesto es, en esencia, **DiAuto** (o el Open Headunit original); de Qonecta+ se cosecha lo que mejora la **recepción**, que es agnóstico del sentido:

**Sí aplica (mejora recibir/decodificar AA):**
- Robustez de decodificación que Qonecta+ sumó sobre la base y que **no existe en DiAuto** (verificado, 0 coincidencias): `decoder/video/CorruptionConcealmentPolicy.kt`, `DecodeLatencyMonitor.kt`, `PictureCredibilityPolicy.kt`, `VideoFeedThrottlePolicy.kt`, `DecoderExceptionPolicy.kt`, `HeadlessDriver.kt`.
- Jitter-buffer de audio adaptativo de `decoder/audio/` (Qonecta+ tiene ~45 ficheros ahí: `AdaptiveJitterPolicy`, `AdaptivePcmBuffer`, `AudioJitterBufferPolicy`, `AacDecoderRecovery`, `LatencyRecoveryPolicy`…) frente a los 4 planos de DiAuto (`AudioDecoder`, `AudioMixer`, `AudioTrackWrapper`, `MicRecorder`).
- El refactor de `aap/AapVideo.kt` con `FragmentedMessageAudit` (descarte de unidad de acceso incompleta sin emborronar imagen).
- `ssl/BundledRootTrustManager.kt` (CA propia, no está en DiAuto) y `view/PerformanceOverlay.kt`.
- Madurez de pruebas: Qonecta+ tiene **483** ficheros de test vs **79** de DiAuto; muchas policies reutilizables ya vienen con sus tests puros.
- i18n: Qonecta+ tiene más idiomas (`values-fr`, `-hi`, `-th`, `-pt-rPT`, `-in`…); las cadenas compartidas con la base son reaprovechables.

**No aplica (todo lo de QDLink / SspSession / reenvío):** el paquete entero `com.headqlink.link` (**286 .java + 20 .kt**): `SspSession`, `VideoPipeline` (1060 l), `VideoEncoder`, `VideoSource`/`AaPassthroughSource`/`AppSource`/`PatternSource`, el control de enlace/anti-stutter (`LinkRateController`, `IdrSizeController`, `PFrameSizeController`, `PAfterIdr`, `Smoothness`, `AaAckBrake`, `KeyframePolicy`), el crypto/HTTPS propio al coche (`LeapTls`, `LeapApi`, `CertPick`), `UsbLink`/`QdLinkHost`/`QdSessionBridge`, `TouchService` (accesibilidad), `connection/self/*` (self mode) y `connection/wifi/modes/nativeaa/*`. Tampoco la UI de producto (`CarUi` 2766 l, `RoutePlanner` 3517 l, `RouteTab` 3670 l, cargadores DGT/Swiss/BNetzA, nube Leapmotor, Home Assistant, emulador NES…).

**Matiz importante sobre "gestión térmica":** `ThermalPolicy.java` de Qonecta+ regula el **encoder de salida** (baja fps/bitrate del vídeo que el móvil emite: hql `ThermalPolicy.java:11-19`). Una unidad receptora **no codifica**, así que esa pieza concreta **no se trae**; la gestión térmica de la unidad es otro problema (decodificador/pantalla).

### 4. Qué traer DE DiAuto a Qonecta+

Poco, porque en la capa `openheadunit` Qonecta+ va por delante. Candidatos reales:
- **Clúster / segundo display de AA**: `aap/protocol/messages/ClusterDisplayServices.kt` (exclusivo de DiAuto) y el paquete `hud/` (23 ficheros: `BydHudBridge`, `BydNavigationMapper`, `ClusterTurnCardView`…). La salida es específica de BYD, pero el **mensaje de servicio AA del clúster es genérico**: sería la base si algún día Qonecta+ quiere alimentar el cuadro del C10 con navegación turn-by-turn.
- `decoder/FrameReleasePacer.kt` (pacing de presentación, exclusivo de DiAuto) — comparable con `FramePacer.java` de headqlink.
- Policies de Wi-Fi Direct/hotspot exclusivas de DiAuto (`aap/LocalHotspotPolicy`, `P2pChannelPolicy`, `P2pOperatingChannelPolicy`, `WifiModePolicy`, `NativeGroupFrequencyPolicy`) por si cubren algún caso de handshake que el árbol `connection/wifi/direct` de Qonecta+ no contemple.

### 5. Nivel de divergencia

- **Capa base (AAP, ssl, extractor, location, protobuf): muy cerca.** Ancestro común reciente y evidente (62 idénticos + 69 sólo movidos de paquete + protos con mismo `package`/protoc).
- **Arquitectura global: muy separada.** El propósito es inverso (unidad receptora vs móvil-relay) y Qonecta+ añade un motor QDLink completo + toda una app de producto + una reorganización profunda de paquetes (p. ej. `aap/VideoFragmentAssembler.kt`→`decoder/video/…`, `aap/KeyCode.kt`→`input/…`, `connection/*`→`connection/{usb,wifi,wifi/direct,projection,self}/*`).
- DiAuto está **más cerca del upstream**; Qonecta+ ha **divergido mucho más**. Consecuencia práctica: ya no cabe un merge fichero-a-fichero (las rutas no casan), pero el linaje común permite portar piezas por "cherry-pick" conceptual.

**Conclusión operativa:** para el receptor-en-la-unidad del C10, partir de **DiAuto** e incorporar de Qonecta+ la robustez de recepción (decoder/video + decoder/audio + audit) y las traducciones. El motor de Qonecta+ resuelve el problema inverso y no sirve de base.