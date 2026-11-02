# Plan de implementación: Fase 1 «Extendido» de Qonecta Unit

**Respuesta corta:** sí se puede, y sin tocar la parte delicada de DiAuto: el vídeo y el decodificador.
- AA sigue negociándose a pantalla completa, como hoy.
- En Extendido se le mandan en caliente unos márgenes que le dejan libre la franja derecha. Es el mismo mecanismo que Qonecta+ ya usa en el C10.
- Encima de esa franja va nuestro panel.

**Lo simple para empezar son los bloques A + B** (unas 1.300 líneas de código):
- el conmutador entre Extendido y Pantalla completa, que se recuerda;
- el reloj y la fecha;
- la tarjeta de batería, en DEMO o con «Sin cuenta».

La nube (bloque C, unas 4.000 líneas, el 70 % copiado de Qonecta+) puede llegar después sin bloquear nada.

**Rutas abreviadas**
- `U/` = `qd/unit/app/src/main/` (destino).
- `UT/` = `qd/unit/app/src/test/java/`.
- `D/` = `qd/ref/diauto/app/src/main/java/com/andrerinas/openheadunit/`. Solo para citar líneas; en `qd/unit` los mismos ficheros cuelgan de `U/java/com/andrerinas/openheadunit/`.
- `Q/` = `qd/hql/app/src/main/java/com/headqlink/link/`.
- `Qres/` = `qd/hql/app/src/main/res/`.

Las líneas de DiAuto son de la copia original. Tras la F0 (sin `hud/`, BYD ni Shizuku) se habrán movido: hay que buscar cada punto por el símbolo citado, no por el número.

---

## 0. Decisiones de partida

1. **Mecanismo: la opción (a) del mapeo 2.**
   - **Negociación.** Se negocia como hoy: en el C10, 1920×1080 con `margin_height` 198.
   - **Extendido.** Se envía `UpdateUiConfigRequest(L, T, R + extraR, B)` con todo el hueco a la derecha.
   - **Vistas.** La de proyección y la capa táctil siguen a pantalla completa. El panel es una vista opaca encima del 30 % derecho.
   - **Lo que no se toca:** la superficie, el decodificador, el escalador y `HeadUnitScreenConfig`.
   - Es lo que hace Qonecta+ en el coche (`Q/AaPassthroughSource.java:559-637, 805-824`).
2. **Orden por bloques.** Cada bloque compila y se puede enseñar por separado:
   - A: layout y conmutador;
   - B: panel y DEMO;
   - C: nube y pantalla de cuenta;
   - D: DEMO por adb y pruebas.
3. **Paquetes.**
   - El código nuevo de la unidad va en Kotlin, en `com.headqlink.unit.extended`.
   - La nube va en Java, en `com.headqlink.link`, **con las mismas rutas que en Qonecta+**. Así los arreglos se pueden traer por cherry-pick, como se decidió el 1-11.
   - Esas clases son package-private, así que el panel solo ve una fachada pública: `UnitCloud`.
4. **Ninguna credencial real en ningún paso.**
   - Las pruebas usan DEMO, `TestCerts` (certificados generados durante la prueba) o la pantalla vacía.
   - El usuario importa su certificado y entra él mismo en la unidad.

---

## 1. Pasos

### Bloque A: layout 70/30, conmutador y recordar el modo

**A1. Ajustes.** MODIFICAR `U/java/com/andrerinas/openheadunit/utils/Settings.kt`, junto a `fullscreenMode` (`D/utils/Settings.kt:344-356`).

Ajustes nuevos:
- `extendedMode: Boolean`, clave `qu-extended-mode`. Por defecto `true`, porque es lo que se quiere probar (ver §6).
- `extendedPanelPercent: Int`, clave `qu-extended-panel-percent`. Vale 30, acotado entre 20 y 40, y no tiene interfaz en la v1.
- `panelDemo: Boolean`, clave `qu-panel-demo`. Por defecto `false`.

Además:
- **`dpiPixelDensity` (257-261):** por defecto 200 en lugar de 0. El 0 significa «automático», es decir, la densidad de la unidad. 200 es la que usa Qonecta+ en el C10 (`Q/AaSize.java:21-25`). Solo se aplica al reconectar, y se respeta si el usuario ya eligió un valor.
- **Ninguno de los ajustes nuevos entra en `computeSettingsHash`** (`D/utils/HeadUnitScreenConfig.kt:529-548`). Así el conmutador no desbloquea la resolución ni invalida la caché.
- **El nombre es `extendedMode`, nunca «fullscreen».** En DiAuto, «fullscreen» se refiere a las barras del sistema.

**A2. Generación de transporte.** MODIFICAR `U/java/com/andrerinas/openheadunit/connection/CommManager.kt`.
- Añadir `@Volatile var transportGeneration = 0; private set` junto a `onUpdateUiConfigReplyReceived` (`D/connection/CommManager.kt:165`).
- Añadir `transportGeneration++` justo antes de `_transport = AapTransport(` (396-398).
- Es lo mismo que hace Qonecta+ en su `CommManager.kt:153-155, 517`.

**A3. Geometría pura.** AÑADIR `U/java/com/headqlink/unit/extended/ExtendedLayout.kt`, un `object` sin Android:

```
uiW = negW − widthMargin ;  uiH = negH − heightMargin
minAa   = max(ceil(800·dpi/160), ceil(1,15·uiH))                  // Q/CarUi.java:597-598, 603-606
panelPx = min(round(viewW·pct/100), viewW − ceil(minAa·viewW/uiW))
disponible = panelPx ≥ max(200, 0,15·viewW)
aaVideoW = floor((viewW − panelPx)·uiW/viewW) ;  extraR = uiW − aaVideoW
Extendido: (L, T, R + extraR, B)     Completa: (L, T, R, B) simétricos (D/utils/HeadUnitScreenConfig.kt:468-475)
```

- **C10, 1920 px a 200 dpi:** panel de 576, AA de 1344 y `extraR` de 576. Los márgenes quedan en L0 T99 R576 B99.
- **A 320 dpi:** el panel baja a 320.
- **Con una ventana de 2560:** el panel sale de 768 y `extraR` sigue en 576.
- Todo se calcula con medidas del contenedor y de `HeadUnitScreenConfig`. Nunca con 1920×882 fijo.

**A4. Envío de márgenes.** AÑADIR dos ficheros.

**`U/java/com/headqlink/unit/extended/MarginsSync.kt`**
- Máquina de estados pura, probada en el PC.
- Lleva los márgenes deseados, la generación, la secuencia, los intentos y los márgenes confirmados.
- Decide en cada momento si toca enviar, esperar o no hacer nada.

**`U/java/com/headqlink/unit/extended/ExtendedMarginsController.kt`**

Es un `object` de proceso, para que sobreviva a los relanzamientos de la actividad. Porta a Kotlin `Q/AaPassthroughSource.java:571-626`.

- **`want(margins, motivo)`:**
  - sin `TransportStarted`, los márgenes solo quedan pendientes;
  - con transporte, se envían si no están ya confirmados en esta generación.
- **Confirmación:** espera 2500 ms y repite hasta 4 veces (`MARGIN_ACK_MS` y `MARGIN_TRIES`, 631-632).
- **`hookReply(commManager)`:**
  - encadena `onUpdateUiConfigReplyReceived` sin perder el callback anterior, como `hookMarginReply` (596-609);
  - salta al hilo principal;
  - sustituye al hook que hoy solo escribe en el log (`D/aap/AapProjectionActivity.kt:929-931`).
- **`onFirstFrame()`:** reenvía los márgenes con el primer frame de cada generación, como `marginsForNewAa` (611-626).
- **Red de seguridad.**
  - El problema: `onFirstFrameListener` es de un solo disparo (`D/decoder/VideoDecoder.kt:347, 1041, 1884`).
  - Qué hace: mientras haya `TransportStarted`, mira cada segundo si `videoDecoder.framesRenderedThisSession > 0`. Si esa generación aún no tiene sus márgenes, los envía.
  - El contador vuelve a 0 en cada handshake (`D/connection/CommManager.kt:429`).
- **`SEND_IN_FULLSCREEN = false`.**
  - En Pantalla completa no se manda nada al conectar, igual que hace DiAuto hoy.
  - Solo se mandan márgenes simétricos al volver de Extendido dentro de la misma sesión.
  - Si en el coche aparece una franja negra o el desfase de 99 px, se cambia a `true` (una línea).
- **Nunca `content_insets` en caliente:** en el clúster tiraba la conexión (`D/hud/ClusterMapConfig.kt:195-202`).
- **Log:** una línea por cambio. Ejemplo: `[QU] extendido: panel 576 px · AA 1344 · L0 T99 R576 B99 · pendiente|enviado|confirmado|sin confirmar tras 4`.

**A5. Vista y conmutador.** AÑADIR `U/java/com/headqlink/unit/extended/ExtendedUi.kt`.

Lo crea la actividad y reúne todo lo nuestro, para que `AapProjectionActivity` solo lleve ganchos.

- **Panel.**
  - Añade `ExtendedPanelView` (bloque B) con `FrameLayout.LayoutParams(panelPx, MATCH_PARENT, Gravity.END)`.
  - Lo marca `isClickable = true` y no enfocable.
  - Escucha `OnLayoutChangeListener` en el contenedor: al cambiar, recalcula `ExtendedLayout` y llama a `want(...)`.
- **Pasar a Extendido:**
  1. guarda el ajuste;
  2. muestra el panel enseguida, para que tape la franja mientras AA se rehace;
  3. llama a `want`.
- **Pasar a Pantalla completa:**
  1. guarda el ajuste;
  2. llama a `want(simétricos)`;
  3. oculta el panel cuando llega la confirmación, o al cabo de 1 s como mucho. El retardo se ajusta tras medirlo en el coche.

  Así no se ve el margen negro mientras AA se rehace.
- **Rebotes y animaciones.**
  - Ignora pulsaciones separadas menos de 1,5 s.
  - Nunca anima el ancho: AA se rehace con cada cambio (`Q/CarUi.java:697-698`).
- **Botón flotante «Extendido», en Pantalla completa.**
  - Tamaño y aspecto: 56×56 px escalados, con el icono `hql_ic_split` y alfa 0,6.
  - Posición: arriba a la derecha, a 16 px del borde.
  - Se oculta a los 6 s sin tocar la pantalla. Vuelve 6 s cada vez que se toca AA.
  - Así molesta poco sobre los botones de Maps.
- **Capa de carga.**
  - En Extendido pone `rightMargin = panelPx` a `loading_overlay`. «Android Auto se está iniciando» queda centrado en la zona de AA y el reloj se ve desde el arranque.
  - El panel se sube por encima de la capa con un `bringToFront` posterior al de la línea 901.
- **`clampX(x)`.**
  - En Extendido devuelve `min(x, aaVideoW − 1)`.
  - Sirve para que un arrastre que empieza en AA y cruza el panel no se salga del contenido, como en `Q/AaPassthroughSource.java:947, 953`.
- **Casos especiales.**
  - En PiP oculta el panel y el botón, sin tocar los márgenes.
  - Con `hudMirroring` no hay Extendido.
  - Con `disponible = false` tampoco, y lo avisa en el log.
- **`applyIntent(intent)`:** lee los extras de DEMO, solo si `BuildConfig.DEBUG` (bloque D).
- **`onStart`, `onStop` y `onResume`:**
  - piden o sueltan la nube (bloque C);
  - al volver de Ajustes, releen el modo y la DEMO.

**A6. Ganchos en la actividad.** MODIFICAR `U/java/com/andrerinas/openheadunit/aap/AapProjectionActivity.kt`, unas 70 líneas.

| Zona (`D/aap/AapProjectionActivity.kt`) | Cambio |
|---|---|
| `setupProjectionView`, `container.addView(view)` (1906) | **`container.addView(view, 0)`**. Si no, `recreateProjectionView` (458-478, llamado desde 600 y 709) deja la proyección encima del panel. |
| `onCreate`, tras `container.addView(overlayView)` (881) y `loadingOverlay?.bringToFront()` (901) | crear `ExtendedUi`, llamar a `attach(intent)` y subir el panel por encima de la capa de carga |
| los dos `videoDecoder.onFirstFrameListener` (819-823 y 911-927) | añadir `ExtendedMarginsController.onFirstFrame()` |
| `commManager.onUpdateUiConfigReplyReceived = {…}` (929-931) | `ExtendedMarginsController.hookReply(commManager)`, que conserva la línea de log |
| `connectionState.collect`: `Disconnected` (797-828) y `TransportStarted` (847-849) | `onDisconnected()` y `onTransportStarted()` del controlador |
| desajuste de superficie (1558-1567) | cambiar `sendUpdateUiConfigRequest(simétricos)` por `extendedUi?.recompute("superficie")`. Si no, se pierde el hueco del panel. |
| `sendTouchEvent`, justo antes de `commManager.send(TouchEvent…)` (1741) | pasar la x de `pointerData` por `extendedUi?.clampX(x)`, que sirve a las dos ramas (1685-1698 y 1700-1737). Con `ACTION_DOWN`, `extendedUi?.onAaTouch()` para el botón flotante. |
| `onStart`/`onStop` (934-948), `onResume` (983-1019) y `onNewIntent` (1022-1025) | `extendedUi?.onStart()`, `onStop()`, `onResume()` y `applyIntent(intent)` |
| `onPictureInPictureModeChanged` (1456-1469) | `extendedUi?.onPiP(on)` |
| `showExitDialog` (1347-1403) | opcional: entrada «Extendido / Pantalla completa», como reserva del botón |
| `onDestroy` (1793) | `extendedUi?.detach()` |

**Sin cambios en la v1:**
- `HeadUnitScreenConfig`, `ProjectionViewScaler` y las tres vistas de proyección.
- `ServiceDiscoveryResponse` y `UpdateUiConfigRequest`. Este último ya manda solo `margins` (`D/aap/protocol/messages/UpdateUiConfigRequest.kt:12-39`).
- `TouchCoordinateMapper`: con L = 0, la x del contenido es la x de la pantalla (`D/aap/TouchCoordinateMapper.kt:33-35`).

**A7. Pruebas del PC.** AÑADIR:
- **`UT/com/headqlink/unit/extended/ExtendedLayoutTest.kt`**, con estos casos:
  - el C10 a 200 y a 320 dpi;
  - una ventana de 2560;
  - 1024×600: el Extendido queda no disponible o al mínimo;
  - el % se acota entre 20 y 40;
  - en Completa, los márgenes son simétricos.
- **`UT/com/headqlink/unit/extended/MarginsSyncTest.kt`**, con estos casos:
  - sin transporte, los márgenes quedan pendientes;
  - se envían con el primer frame;
  - no se repiten dentro de la misma generación;
  - se reenvían con una generación nueva;
  - tras 4 intentos se rinde;
  - en Completa no se envía nada al conectar.

### Bloque B: panel nativo (reloj y fecha, batería y autonomía) con DEMO

**B1. Modelo y fuentes de datos.** AÑADIR en `U/java/com/headqlink/unit/extended/`:

**`PanelCarData.kt`**
- `data class PanelCarData(kind, socPct, rangeKm, dataTimeMs, nextTryMs, demo)`.
- `enum Kind { OK, WAITING, NO_ACCOUNT, OFF, ERROR, EXPIRED, SERVER_KEY, UNSUPPORTED }`, los mismos estados de `Q/CarCloud.java:24-40`.
- `interface PanelDataSource { start(ctx); stop(); read(nowMs) }`.
- Con esto el panel no depende de la nube, y el bloque B funciona antes que el C.

**`DemoPanelData.kt`**

| Estado | Batería | Autonomía | Edad del dato | Nota |
|---|---|---|---|---|
| `ok` | 78 % | 359 km | hace 3 min | |
| `viejo` | 78 % | 359 km | hace 2 h | |
| `bajo` | 6 % | 28 km | — | anillo en rojo |
| `sin_cuenta` | — | — | — | |
| `caducada` | con dato | | | aviso en ámbar |
| `error` | con dato | | | «reintento en 2 min» |

- Siempre `demo = true`, y nunca usa la red.
- La edad es fija para que las capturas salgan siempre iguales.
- Los km siguen la regla de la demo de Qonecta+: % × 4,6 (`Q/DemoMode.java:409`).

**`PanelFormat.kt`** (puro)
- **Color del nivel:** acento desde el 20 %, ámbar desde el 8 %, rojo por debajo (`Q/CarStatusTab.java:154-156`).
- **Edad del dato:** en ámbar a partir de 15 min (32, 166-168).
- **Antigüedad:** «ahora», «hace 40 s», «3 min», «2 h», «1 d». Es como `Q/CarCloud.java:672-680`, pero con textos propios.
- **Sin valor:** «—» si es NaN.
- **Hora:** con `DateFormat.getTimeFormat(ctx)`, que respeta las 24 h.
- **Fecha:** con `DateUtils.formatDateTime(…, FORMAT_SHOW_WEEKDAY | FORMAT_SHOW_DATE | FORMAT_NO_YEAR)`.
- Ambas funcionan desde API 16, así que no hace falta `TextClock`.

**B2. Vistas.** AÑADIR:

**`BatteryRingView.kt`**
- Porta `CarKit.ring` (`Q/CarKit.java:256-270`):
  - pista en `#1E2935`;
  - arco de 270° que empieza en 135°;
  - trazo de 26 px con los extremos redondeados.
- Dentro del anillo:
  - el % en grande: 100 px, letra ligera;
  - los km debajo: 40 px, en gris.
- Debajo del anillo:
  - «Autonomía»: 20 px, tenue;
  - la edad del dato: 23 px, en acento o ámbar.
- Copia la disposición de `Q/CarStatusTab.java:172-186`.
- Los `Paint` se reutilizan: no se crea nada en `onDraw`.

**`ExtendedPanelView.kt`**

Se monta por código.

- **Medidas.**
  - Las medidas son px del C10, como en `Q/CarStyle.java:16-21`.
  - Se multiplican por `k = alto/882`, que se recalcula en `onSizeChanged`.
  - Así se ve igual en la unidad, a 2560 y en el emulador.
- **Colores.**
  - Fondo `#0A0E14`.
  - Tarjetas en `#17202B`, con radio 24 y borde de 2 px en `#243140` (`Q/CarKit.java:32-61`).
  - Acento `#00E5C7`.
- **Contenido, de arriba abajo:**
  1. **Ficha «DEMO»:** píldora ámbar `#FFB547` con texto oscuro, arriba a la derecha. Solo aparece en DEMO.
  2. **Tarjeta Reloj:** la hora a 112 px, ligera; la fecha a 28 px, en gris.
  3. **Tarjeta Batería:** el anillo. Si no hay dato, un estado vacío con título, explicación y «Toca para abrir Datos del coche».
     - En `EXPIRED`, `SERVER_KEY` o `ERROR` con dato: el dato, con un aviso en ámbar debajo.
  4. **Botón «Pantalla completa»:** píldora con `hql_ic_expand`, de 64 px de alto como mínimo. El mínimo táctil es 48 (`Q/CarStyle.java:48`).
- **Refresco.**
  - Un tic de 1 s mientras el panel está visible. Se controla con `onAttachedToWindow`, `onDetachedFromWindow` y la visibilidad.
  - Repinta la hora y el «hace X» sin usar la red.
- **Toques.**
  - Tocar la tarjeta Batería en `NO_ACCOUNT`, `EXPIRED`, `SERVER_KEY` u `OFF` abre `CarCloudActivity` (cuando exista el bloque C).
  - Ningún hijo es enfocable, así que las teclas siguen yendo a la capa táctil (`dispatchKeyEvent`, 1745-1770).

**B3. Recursos.** AÑADIR:
- **`U/res/values/hql_colors.xml`:** copia exacta de `Qres/values/hql_colors.xml`. La misma paleta sirve para el panel (que la lee por código) y para la pantalla de cuenta.
- **`U/res/drawable/hql_ic_split.xml` y `hql_ic_expand.xml`:** copias de `Qres/drawable/`. Son los iconos de «Extendido» y «Pantalla completa».
- **`U/res/values/qu_strings.xml` y `U/res/values-es/qu_strings.xml`:** unas 30 claves.
  - modo: `qu_extended`, `qu_fullscreen`, `qu_demo`;
  - batería: `qu_battery`, `qu_range`;
  - antigüedad: `qu_ago_now`, `_s`, `_min`, `_h`, `_d`;
  - estados, con título y texto: `qu_state_no_account`, `_off`, `_expired`, `_server_key`, `_unsupported`, `_error`;
  - otros estados: `qu_state_waiting`, `qu_retry_in`, `qu_open_car_data`;
  - ajustes: `qu_settings_category`, `qu_settings_mode`, `qu_settings_car_data`, `qu_settings_demo` y `_demo_desc`.
- DiAuto también tiene `values-es-rES`. Las claves que no estén allí se toman de `values-es`, así que no hay conflicto.

**B4. Ajustes de la app.** MODIFICAR `U/java/com/andrerinas/openheadunit/main/SettingsFragment.kt`.
- Categoría nueva «Qonecta Unit», la primera dentro de `updateSettingsList()` (`D/main/SettingsFragment.kt:707-713`). Lleva tres entradas:
  - `quExtendedMode`: «Modo de pantalla: Extendido / Pantalla completa». Se recuerda.
  - `quCarCloud`: «Datos del coche (cuenta Leapmotor)». Se activa en el bloque C.
  - `quPanelDemo`: «Panel con datos inventados (DEMO)».
- Las tres se escriben en `Settings` al momento, sin pasar por el flujo «pendiente + Guardar».
- **Hay que añadir los tres ids a `basicSettingIds` (76-79).** Si no, no salen en la pestaña Básica (`shouldShowItem`, 2516-2518).

**B5. Pruebas.** AÑADIR:
- `UT/com/headqlink/unit/extended/PanelFormatTest.kt`: umbrales del 20 % y el 8 %, ámbar a partir de 15 min, textos de antigüedad y NaN.
- `UT/com/headqlink/unit/extended/DemoPanelDataTest.kt`: cada estado devuelve `demo = true` y sus valores.

### Bloque C: nube de Leapmotor (port mínimo) y pantalla de cuenta

Los ficheros van a `U/java/com/headqlink/link/`, con el mismo nombre que en `Q/`. Los recortes concretos están en el mapeo 1; aquí van el orden y lo que cambia respecto a él.

**C1. Copiar tal cual, sin recortar:** `LeapCrypto.java`, `LeapTls.java`, `LeapStatus.java` y `CertPick.java`.
- Solo dependen de `java.*`, `javax.*`, `android.util.Base64` y `org.json`.
- Al dejarlos idénticos:
  - el cherry-pick sale limpio, por ejemplo cuando cambie el pin de `LeapTls.java:57-58`;
  - `LeapCryptoTest` también se copia tal cual.
- El código de V2 y del PIN queda muerto, pero compila.

**C2. Adaptar.**

**`LeapHttps.java`**
- Quitar `v2Origin` y `sendV2` (88-132).
- Quitar la llamada a `LeapApi.remoteAllowedNow` (60).

**`LeapApi.java`**
- Se quedan `login`, `restore`, `refresh`, `vehicles`, `status`, `withTokenRetry`, `call`, las cabeceras y `parse`.
- `ALLOWED_PATHS` queda en login, refresh, list y status/get.
- Se quitan V2, viajes, consumo, energía, km y órdenes.

**`CarCloudStore.java`**
- Se quedan:
  - `KeystoreBox` (415-486);
  - `identity.bin` y `session.bin`, cifrados;
  - las prefs.
- Se quitan los perfiles de batería, el historial y el PIN. Pero se conserva `KWH_C10_LIFE` y unas versiones triviales de `capacityKwh()` y `carType()`, porque las usa `Snapshot` (`Q/CarCloud.java:71, 369, 398`).
- **Nuevo:** guardar `readsToday` y `readsDay` en prefs (dos claves).

**`CarCloudSession.java`**
- Se quitan `PageGate`, viajes, órdenes, posición, consumo y el volcado LAB (173, 177-185).
- **`BuildConfig.LAB` no existe en DiAuto:** si se queda, no compila.

**`CarCloud.java`**
- **Se queda:** `State`, `Snapshot` y `Policy`.
- **Recortes:**

  | Pieza | Qué se le quita |
  |---|---|
  | `acquire` y `release` | `MateFeed` |
  | `start` | `DemoMode` (261) y `ChargeCheckJob` (266) |
  | `snapshot()` | `DemoMode` (313) |
  | `pollOnce` | `adoptReev`, `adoptCarType`, `WidgetUpdater` y `LastCharge` (388-402) |
  | `loop` | la llamada a `pollHistory` (372) |

- **`setHubVisible` pasa a significar «panel visible».** **Nuevo:** sin panel visible no lee; espera `IDLE_MS` sin usar la red.
- **`driving()` (378-380)** lee un indicador `aaActive` que pone la fachada: hay sesión de AA con imagen, luego el coche va en marcha. Así:
  - no se espacian las lecturas en pleno viaje;
  - sí se espacian con la unidad encendida pero sin móvil.
- **`takeDailyQuota` (541-556)** usa el contador guardado.
- **Javadoc de 13-14 y 124:** corregirlo. Las constantes reales son 120 s y 90 s, no 60 y 30.

**`CarCloudActivity.java`**
- **Se quita:**
  - el perfil de batería (160-177, 230-241, 707-762);
  - el PIN y las órdenes (85-93, 127-146, 259-266, 767-781);
  - la sonda V2 (157, 637-680).
- **Se copian dentro** `problem()` y `minutes()` (`Q/CarStatusTab.java:672-684, 159-164`).
- **`summary` (798-817)** se queda en «78 % · 359 km · hace 3 min».
- **Ancho** limitado a 720 dp y centrado. Se hace por código en `onCreate`, porque DiAuto no incluye ConstraintLayout.
- **Selector de ficheros, en cadena:**
  1. `ACTION_OPEN_DOCUMENT`;
  2. si falla, `ACTION_GET_CONTENT`;
  3. si también falla, una lista de `.crt/.key/.pem/.p12/.pfx` en `getExternalFilesDir("cert")`, que no necesita permisos. Las Descargas solo se miran en API 28 o anterior (`D/utils/SettingsBackupManager.kt:363`).

  Para saber si hay selector, se intenta abrir y se captura `ActivityNotFoundException`, como hace DiAuto (`D/main/SettingsFragment.kt:2918-2934`). No se usa `resolveActivity`, que en Android 11+ exigiría declarar `<queries>`.
- **Avisos fijos en pantalla:**
  - «Hazlo con el coche parado»;
  - «Borra app.key del pendrive o de Descargas al terminar».
- **Se conservan:**
  - `askServerKey` (824-838);
  - la confirmación del borrado (840-853).

**C3. Stubs** (mismo paquete):
- **`L.java`:** `init`, `i` y `w`, hacia `android.util.Log` con la etiqueta `QU-nube` (o hacia `AppLog.INSTANCE`).
- **`Str.java`:** `init` y `get(id, args)`, que llama a `getString`.
- **`Http.java`:** solo `safeError` (`Q/Http.java:25-31`).
- **`M3.java`:** copia de `field` y `outlined` (`Q/M3.java:19-41`).

**C4. Fachada pública.** AÑADIR `U/java/com/headqlink/link/UnitCloud.java`.
- **Control del sondeo:** `acquire(ctx, "panel")`, `release`, `setPanelVisible(boolean)` y `setAaActive(boolean)`.
- **`read(now)`:**
  - devuelve un `Reading` público con el tipo, `socBest()`, `evRangeKm()`, la hora del dato y el próximo intento;
  - lo construye desde `CarCloud.snapshot()`;
  - `socBest()` y `evRangeKm()` están en `LeapStatus.java:396-398, 415-422`.
- **`hasAccount(ctx)`:** como `Q/PhoneInsights.java:82-86`.
- **Opcional:** guardar en prefs el último %, los km y la hora del dato. Así, al arrancar, el panel enseña «hace 3 h» en vez de «Leyendo…».

**C5. Fuente real del panel.** AÑADIR `U/java/com/headqlink/unit/extended/CloudPanelData.kt`, una `PanelDataSource` sobre `UnitCloud`.
- `ExtendedUi` elige `DemoPanelData` cuando hay DEMO y `CloudPanelData` en el resto de casos.
- Pone `setAaActive(true)` cuando hay `TransportStarted` y `framesRenderedThisSession > 0`.

**C6. Recursos.** AÑADIR, copiados y adaptados de `Qres/`:
- **`layout/hql_activity_cloud.xml`:**
  - sin la sección Batería (265-334) ni la de PIN y órdenes (336-431);
  - `importantForAutofill="no"` en el campo de contraseña (177-178).
- **`values/hql_cloud_strings.xml` y `values-es/hql_cloud_strings.xml`:**
  - el subconjunto de unas 75 claves del §8 del mapeo 1;
  - reescribir `hql_cloud_intro`, `_wipe_confirm` y los textos que dicen «en el móvil».
- **`values/hql_styles.xml`**, solo esta parte:
  - el tema `Base.Theme.HeadQLink` (5-54), renombrado a `Theme.QonectaUnit.Cloud`;
  - `HQL.Dialog` y los `HQL.AlertDialog*` (71-106);
  - `HQL.Button`, `.Outline` y `.Quiet` (115-138);
  - los `HQL.Text.Body`, `.Label`, `.RowTitle` y `.RowDetail`.
- **`color/hql_btn_fill.xml`, `color/hql_btn_primary_text.xml`, `drawable/hql_panel.xml` y `drawable/hql_window_bg.xml`.**
- **Ya existen en DiAuto:** `ic_arrow_back`, y `save` y `cancel`, que sustituyen a `hql_save` y `hql_cancel`.
- **El tema oscuro es obligatorio.** El `AppTheme` de DiAuto es `Theme.Material3.DayNight` (`res/values/styles.xml:3`). Con el sistema en modo claro, el texto de `hql_text`, que es claro, no se leería.

**C7. Manifiesto y App.**
- MODIFICAR `U/AndroidManifest.xml`: declarar `com.headqlink.link.CarCloudActivity`, igual que en Qonecta+ (`AndroidManifest.xml:668-672`), con:
  - `exported=false`;
  - `theme=@style/Theme.QonectaUnit.Cloud`;
  - `windowSoftInputMode="adjustResize|stateHidden"`;
  - `configChanges="orientation|screenSize|keyboardHidden"`.
- MODIFICAR `U/java/com/andrerinas/openheadunit/App.kt`: llamar a `Str.init(this)` y `L.init(this)` en `onCreate` (`D/App.kt:32-102`), como hace Qonecta+ en su `App.kt:51`.
- Activar la entrada `quCarCloud` del paso B4.

**C8. Pruebas.** AÑADIR en `UT/com/headqlink/link/`:
- **Tal cual:** `TestCerts.kt`, `LeapTlsTest.kt`, `CertPickTest.kt`, `LeapStatusTest.kt` y `LeapCryptoTest.kt`. Usan Robolectric con `application = Application::class`; DiAuto ya trae la 4.17 (`build.gradle.kts:238`).
- **Recortadas:**
  - `LeapApiTest.kt`: fuera las líneas 269-496, y ajustar `onlyReadOnlyPaths` (237);
  - `CarCloudTest.kt`: fuera las líneas 204-271, y ajustar a la nueva firma de `Policy.delayMs`.
- **Nueva:** `UnitCloudTest.kt`. Comprueba:
  - el paso de `Snapshot` a `Reading`;
  - el contador diario guardado;
  - que no lee con el panel oculto.

### Bloque D: estado DEMO para capturas

**D1. Cómo se activa.**
- **Con el ajuste** `qu-panel-demo`.
- **Con extras de `AapProjectionActivity`, solo en compilaciones debug.** La actividad está exportada (`D/../AndroidManifest.xml:93-101`). Los extras son:
  - `--ez qu_demo true`;
  - `--es qu_demo_estado ok|viejo|bajo|sin_cuenta|caducada|error`;
  - `--es qu_modo extendido|completa`.

  Se leen en `onCreate` y también en `onNewIntent`, porque la actividad es `singleTask`.
- **En release los extras se ignoran.** Cualquier app podría lanzarlos y enseñar datos inventados; aunque saldrían con la ficha DEMO, mejor cerrarlo.

**D2. Qué hace la DEMO.**
- La ficha «DEMO» se ve siempre en el panel.
- Nunca se pide `UnitCloud`, así que no hay red.
- El reloj es el de verdad.

**D3. La zona de AA sin móvil.**
- La actividad se queda en «Android Auto se está iniciando»:
  - el primer `Disconnected` se ignora (`D/aap/AapProjectionActivity.kt:791-794`);
  - el vigilante sale porque no hay sesión (160-169).
- Eso es lo que aparece en la zona de AA en las capturas DEMO.
- Es también lo que el usuario verá en el coche mientras arranca AA.

---

## 2. Permisos y recursos nuevos

- **Permisos:** ninguno nuevo.
  - `INTERNET` ya está (`D/../AndroidManifest.xml:62`).
  - El Keystore y el selector SAF no piden permisos.
  - `READ_EXTERNAL_STORAGE` no sirve: desde Android 11 no da acceso a los .crt y .key de Descargas. Por eso el plan B usa `getExternalFilesDir("cert")`.
- **Manifiesto:** solo la declaración de `CarCloudActivity` (C7).
- **Recursos nuevos:**
  - `hql_colors.xml`, `qu_strings.xml` (en y es);
  - `hql_ic_split.xml`, `hql_ic_expand.xml`;
  - `hql_activity_cloud.xml`, `hql_cloud_strings.xml` (en y es), `hql_styles.xml` (subconjunto);
  - `color/hql_btn_fill.xml`, `color/hql_btn_primary_text.xml`;
  - `drawable/hql_panel.xml`, `drawable/hql_window_bg.xml`.

  Las tarjetas, la píldora y la ficha DEMO del panel se dibujan por código (`GradientDrawable`, escalado por `k`).
- **Dependencias:** ninguna nueva.
  - Material 1.10.0 (`build.gradle.kts:224`) ya trae `MaterialToolbar`, `MaterialSwitch` y `colorSurfaceContainer*`.
  - Si algún atributo no compila, se cambia por el color directo.
- **Licencias:**
  - conservar las cabeceras de los ficheros de Qonecta+: AGPL-3.0, y GPL-3.0 en lo que viene de LMB10;
  - conservar también el texto `hql_cloud_credits`.

---

## 3. Riesgos y cómo mitigarlos

### Márgenes de AA

| Riesgo | Mitigación |
|---|---|
| AA se rehace con cada cambio de márgenes: 1-2 s con su logo, sin medir aún (`Q/CarUi.java:697-698`) | El conmutador es un salto puntual, nunca animado. Rebote de 1,5 s. No se repiten márgenes ya confirmados. El panel aparece antes de pasar a Extendido y se va después de la confirmación al pasar a Completa. |
| AA ignora los márgenes que llegan antes de su primer frame o tras un relanzamiento (`Q/AaPassthroughSource.java:571-575, 611-614`) | Se reenvían en cada generación con el primer frame, con confirmación de 2,5 s y 4 intentos. Además, la red de seguridad de `framesRenderedThisSession`. Si no hay confirmación: queda en el log y se puede volver a Completa. |
| El desajuste de superficie reenvía márgenes simétricos y borra el hueco del panel (1558-1567) | Ese camino pasa por `ExtendedUi.recompute`. |
| Una vista de proyección reconstruida tapa el panel (1906, 458-478) | `addView(view, 0)`. |
| Con densidad alta, AA baja de 800 dp: pasa a tarjetas y Maps queda reducido (`Q/CarUi.java:591-598`) | 200 dpi por defecto, más la regla del ancho mínimo: el panel encoge, o el Extendido se desactiva. |
| Pruebas contradictorias sobre dónde dibuja AA con los márgenes de ServiceDiscovery | En Extendido se mandan siempre márgenes explícitos por lado. En Completa, `SEND_IN_FULLSCREEN` se activa si el coche muestra una franja o desfase. |
| `content_insets` en caliente tiraba la conexión | No se usan nunca. |

### Toques

| Riesgo | Mitigación |
|---|---|
| Un arrastre que empieza en AA y entra en el panel | x topada en `aaVideoW − 1`. El panel es clicable y está encima: sus toques nunca llegan a AA, porque Android entrega el gesto entero a quien recibió el DOWN. |
| Desfase de 99 px en Y si AA leyera coordenadas del frame en vez del contenido. Si pasa, ya pasaría hoy en DiAuto. | Comprobarlo en el coche tocando iconos arriba y abajo de AA. Con L = 0, la X no corre riesgo. |
| Que el panel se quede el foco de las teclas | Sus hijos no son enfocables; el foco sigue en la capa táctil (870-882). |
| LeapOS se queda los toques del borde izquierdo (`Q/CarUi.java:105-107`) | Nada nuestro pegado a los bordes. El botón flotante va a 16 px del borde derecho y se oculta solo. |

### Sondeo de la nube

| Riesgo | Mitigación |
|---|---|
| Una sola sesión por cuenta (`qd/hql/MANUAL.de.md:1063-1064`): la unidad y Qonecta+ o la app oficial se echan mutuamente | Usar para la unidad una cuenta de «miembro compartido», o aceptar que Qonecta+ deje de leer la nube. Avisarlo en la pantalla de cuenta. |
| El token caduca con la unidad apagada. Qonecta+ lo mantiene vivo cada 15 min; la unidad no puede | Estado EXPIRED claro en el panel; al tocarlo se abre la pantalla con el correo ya puesto. Medir la vida del token en el log (`CarCloudSession.java:105-108`) antes de decidir más, por ejemplo que Qonecta+ le pase el dato. |
| El protocolo antiguo se está retirando: el historial falla desde el 28-10 | El panel pasa a ERROR sin romperse, y el reloj sigue funcionando. |
| El servidor cambia de clave (pin válido de 2026-07 a 2027-08, `LeapTls.java:57-58`) | Estado SERVER_KEY y el diálogo `askServerKey` portado. |
| El Keystore de la unidad (hace falta Android 6) falla o solo es por software | Estado UNSUPPORTED, sin plan B que rebaje la seguridad. |
| La unidad no da internet a apps de terceros | Se muestra ERROR «sin conexión». Medirlo en el coche. |
| El reloj de la unidad va mal: firmas rechazadas y «hace X» erróneo | Si la hora del dato cae en el futuro, se muestra «—». |
| Escribir el correo y la contraseña en el coche | Aviso «con el coche parado». La contraseña no se guarda e `importantForAutofill="no"`. |
| `app.key` queda en claro en el pendrive o en Descargas | Aviso para borrarlo después de importarlo. |
| Leapmotor podría limitar una cuenta que consulte demasiado (`Q/CarCloud.java:108-112`) | Ver el apartado siguiente. |

### Ahorro de lecturas

- El hilo de sondeo solo vive con la proyección en primer plano: `onStart`/`onStop`.
- Solo lee con el panel visible. En Pantalla completa se pausa, sin red.
- **Ritmo:**
  - 90 s con AA activo y 120 s sin él;
  - si llegan dos lecturas sin dato nuevo y no hay AA: 5 min, y luego 15;
  - tras errores: 2, 5 y 10 min.
- **Tope de 400 lecturas al día, guardado.** Hoy vive en memoria (`CarCloud.java:205-206`) y se pierde si se reinicia el proceso.
- Al volver a Extendido no se relee si la última lectura tiene menos de 90 s. Así, conmutar no gasta lecturas.
- En DEMO no hay red.
- En EXPIRED, SERVER_KEY o NO_ACCOUNT queda bloqueado, sin red, hasta que algo cambie en la pantalla de cuenta.
- Cálculo: 2 h de viaje con el panel a la vista son unas 80 lecturas.

### Otros

- **DEMO en uso real.** La ficha está siempre visible, viene apagada por defecto y los extras solo funcionan en debug.
- **Ventana real de la unidad desconocida** (¿2560 de ancho? ¿barras de LeapOS?). Todo se calcula con las medidas del contenedor. Hay pruebas a 1920 y a 2560.
- **Líneas desplazadas tras la F0.** Buscar por símbolo, no por número.

---

## 4. Pruebas en el emulador del PC (AVD `coche`) con DEMO

**El AVD `coche` no tiene el tamaño del C10.**
- Es Android Automotive 15 (`android-35-ext15`, x86_64) con pantalla de 1080×600 a 120 dpi (`D:/Android/.android/avd/coche.avd/config.ini:86-91, 127`).
- Hay que forzarle el tamaño del C10 con `wm`.
- La APK de DiAuto lleva librerías x86_64: no hay `abiFilters` en `build.gradle.kts`.

**Pasos** (PowerShell, desde `qd\unit`):

1. **Pruebas y APK**, el mismo comando de la F0:
   ```
   $env:JAVA_HOME='D:\Android\jdk'; .\gradlew.bat :app:testGithubDebugUnitTest --console=plain
   $env:JAVA_HOME='D:\Android\jdk'; .\gradlew.bat :app:assembleGithubDebug --console=plain
   ```
2. **Arrancar el emulador:** `& 'D:\Android\sdk\emulator\emulator.exe' -avd coche`. Si no encuentra el AVD, antes: `$env:ANDROID_AVD_HOME='D:\Android\.android\avd'`.
3. **Ponerle el tamaño del C10:**
   ```
   $adb='D:\Android\sdk\platform-tools\adb.exe'
   & $adb -s emulator-5554 shell wm size 1920x882
   & $adb -s emulator-5554 shell wm density 200
   ```
4. **Instalar:** `& $adb -s emulator-5554 install -r (Get-ChildItem app\build\outputs\apk\github\debug\*.apk).FullName`. La APK se llama `com.headqlink.unit_*_debug.apk` (`build.gradle.kts:204`).
5. **Idioma**, para que las capturas salgan en castellano: `& $adb -s emulator-5554 shell cmd locale set-app-locales com.headqlink.unit --locales es-ES`. DiAuto declara `localeConfig`, y el emulador es API 35. Si la hora sale en UTC, no importa para las capturas.
6. **Abrir en DEMO:**
   ```
   & $adb -s emulator-5554 shell am start -n com.headqlink.unit/com.andrerinas.openheadunit.aap.AapProjectionActivity --ez qu_demo true --es qu_demo_estado ok --es qu_modo extendido
   ```
   - Automotive arranca con el usuario 10 en primer plano. Si `am start` no encuentra la actividad, añadir `--user current`.
   - Cada `am start` posterior con otro estado llega a la actividad por `onNewIntent`.
7. **Capturas, con la herramienta Bash.** El `>` de PowerShell 5.1 estropea los PNG.
   ```
   "D:/Android/sdk/platform-tools/adb.exe" -s emulator-5554 exec-out screencap -p > "qd/datos/carlink/capturas/qu_01_extendido_demo.png"
   ```
   La carpeta es local y nunca se publica.
8. **Al terminar:** `wm size reset` y `wm density reset`.

**Capturas**

| Fichero | Cómo | Qué comprobar |
|---|---|---|
| `qu_01_extendido_demo` | `ok`, `extendido` | Panel de 576 px a la derecha con la ficha DEMO. 78 % · 359 km · hace 3 min. Hora y fecha reales. «Android Auto se está iniciando» centrado en la zona izquierda. |
| `qu_02_completa` | `--es qu_modo completa` | Sin panel. Botón flotante arriba a la derecha; repetir la captura a los 7 s: ya oculto. |
| `qu_03_viejo` | `viejo` | «hace 2 h» en ámbar. |
| `qu_04_bajo` | `bajo` | Anillo rojo, 6 % · 28 km. |
| `qu_05_caducada` | `caducada` | Dato con aviso ámbar de sesión caducada. |
| `qu_06_sin_cuenta` | sin `qu_demo` y sin cuenta | Estado vacío real, sin ficha DEMO: «Sin cuenta de Leapmotor · Toca para abrir Datos del coche». |
| `qu_07_cuenta` | tocar la tarjeta de `qu_06` | `CarCloudActivity` oscura, de 720 dp y centrada, con «Sin certificado». Se puede abrir el teclado tocando el correo, sin escribir datos reales. |
| `qu_08_ajustes` | Ajustes de la app, pestaña Básica (aceptar antes el aviso inicial si sale) | Categoría «Qonecta Unit» con sus 3 entradas. |
| `qu_09_dpi320` | Ajustes › densidad 320 y luego `qu_01` | El panel baja a 320 px por la regla del ancho mínimo de AA. Ojo: `wm density` no influye aquí, porque manda el ajuste `dpiPixelDensity`. |
| `qu_10_2560` | `wm size 2560x1176` y luego `qu_01` | Mismas proporciones, con `k = 1176/882`. |

**Comprobaciones**
- **Se recuerda el modo.** Tocar «Pantalla completa» con `& $adb shell input tap 1632 826` (coordenadas aproximadas a 1920×882). Relanzar sin `qu_modo`: debe arrancar en Pantalla completa. Probar también al revés, con el botón flotante (≈ 1876, 44).
- **Log de márgenes.** `& $adb -s emulator-5554 logcat -d | Select-String "\[QU\]"` debe mostrar «pendiente (sin transporte)» con panel 576, AA 1344 y L0 T99 R576 B99.
- **Particularidades de Automotive.** Si las barras del sistema no se ocultan, el contenedor es más pequeño y el panel debe adaptarse igual. Si la captura no es representativa, repetirla en el AVD `a11`, con los mismos comandos `wm`.
- **Opcional: AA real en el emulador.** Solo si el AA del S25 tiene «Iniciar servidor de unidad principal» en sus opciones de desarrollador:
  - conectar el emulador por red a `IP_del_S25:5277` (DiAuto lo soporta: `D/connection/NetworkDiscovery.kt:301-314`, `D/aap/AapService.kt:2647-2654`);
  - Qonecta+ no debe tener ninguna sesión de AA abierta. Nunca `am force-stop`;
  - en el S23, con AA 18.1, no apareció esa opción.

**Después, en el coche** (USB/AOA, como en la F1). El usuario instala desde el pendrive. Lista de comprobación:
1. `[QU] … confirmado` en cada conexión en Extendido.
2. AA ocupa 1344 px, sin franja negra, y Maps no está en modo reducido.
3. Toques: iconos de AA arriba y abajo (desfase de 99 px) y junto a la frontera (x ≈ 1340).
4. Conmutar en los dos sentidos: medir cuánto tarda AA en rehacerse y comprobar que no asoma el margen negro.
5. Reiniciar el coche: vuelve en el mismo modo.
6. Nube: el usuario importa su certificado y entra él mismo. Revisar los estados y las lecturas por día en el log.

---

## 5. Estimación de tamaño

| Bloque | Ficheros nuevos (código / recursos / pruebas) | Modificados | Líneas nuevas | Líneas cambiadas |
|---|---|---|---|---|
| **A** Layout y conmutador | 4 / 0 / 2 | `AapProjectionActivity`, `Settings`, `CommManager` | ≈ 600 de código + 280 de pruebas | ≈ 100 |
| **B** Panel y DEMO | 5 / 5 / 2 | `SettingsFragment` | ≈ 620 de código + 140 de recursos + 140 de pruebas | ≈ 45 |
| **C** Nube y cuenta | 16 / 8 / 8 | `AndroidManifest`, `App`, `SettingsFragment` | ≈ 1.750 copiadas idénticas + 2.000 adaptadas + 280 nuevas; 810 de recursos; 1.500 de pruebas | ≈ 15 |
| **D** Extras DEMO | dentro de A y B | — | ≈ 40 | — |
| **Total** | **≈ 50 ficheros nuevos** | **6** | **≈ 5.300 de código (≈ 70 % traído de Qonecta+), 950 de recursos y 1.900 de pruebas** | **≈ 160** |

- **El «extendido simple» (A + B):** unas 1.300 líneas de código y 420 de pruebas, con solo 4 ficheros de DiAuto tocados. Ya da el conmutador, el reloj, la fecha y el panel de batería en DEMO o en «Sin cuenta».
- **El bloque C** es el más grande, pero en su mayor parte es copia.

**Detalle por fichero**
- **A:** `ExtendedLayout` ~90, `MarginsSync` ~110, `ExtendedMarginsController` ~140, `ExtendedUi` ~260.
- **B:** `PanelCarData` ~50, `DemoPanelData` ~60, `PanelFormat` ~80, `BatteryRingView` ~130, `ExtendedPanelView` ~300.
- **C, copiados:** `LeapCrypto` 351, `LeapTls` 637, `LeapStatus` 542, `CertPick` 224.
- **C, adaptados:** `LeapHttps` ~110, `LeapApi` ~420, `CarCloudStore` ~330, `CarCloudSession` ~270, `CarCloud` ~360, `CarCloudActivity` ~520.
- **C, stubs:** ~120 en total.
- **C, nuevos:** `UnitCloud` ~110, `CloudPanelData` ~45.

---

## 6. Por decidir antes de compilar

Son propuestas; según la regla de acordar antes de construir, conviene confirmarlas con el usuario.

1. **Modo de la primera instalación:** propongo Extendido. A partir de ahí, se recuerda el último.
2. **Control en Pantalla completa:** propongo un botón flotante que se oculta solo. La alternativa es una barra fina fija, como la de 96 px de Qonecta+ (`Q/CarUi.java:103`), que quita a AA un 5 % del ancho.
3. **Ancho del panel:** propongo un 30 % fijo en la v1.
4. **Densidad de AA:** propongo 200 dpi por defecto. Solo se aplica al reconectar.
5. **Cuenta de Leapmotor de la unidad:** cuál usar, porque solo admite una sesión por cuenta.
6. **C10 REEV:** propongo enseñar la autonomía eléctrica (`evRangeKm`) en vez de la total.
7. **Sondeo en Pantalla completa:** propongo que no.
8. **Cómo llegan `app.crt` y `app.key` a la unidad:** propongo pendrive y el selector de ficheros, con la carpeta de la app como plan B.
