# Modo Extendido en Qonecta Unit: layout de proyección y márgenes de AA (tarea B)

**Raíces citadas** (solo lectura):
- `DiAuto/` = `qd/ref/diauto/app/src/main/java/com/andrerinas/openheadunit/`. `res/` y `proto/` cuelgan de `qd/ref/diauto/app/src/main/`.
- `Q+/` = `qd/hql/app/src/main/java/`.

Las líneas son las de la copia original de DiAuto. En `qd/unit` se moverán cuando la F0 quite `hud/` y lo de BYD.

---

## 0. Conclusión

**Recomiendo la opción (a).** AA se negocia una sola vez a pantalla completa, como hoy. En Extendido se mandan **en caliente** márgenes por lado con todo el hueco a la derecha: `UpdateUiConfigRequest(L, T, R + panel, B)`. La vista de proyección y la capa táctil siguen a pantalla completa. El panel nativo es una vista opaca **encima** del 30 % derecho, la zona donde AA ya no dibuja.

Por qué (a):
1. **No toca nada de la tubería de vídeo.** La superficie, el decodificador, el escalador y `HeadUnitScreenConfig` quedan igual. Esa es justo la maquinaria frágil de DiAuto: los relanzamientos y los arreglos #650 y #767 en `DiAuto/aap/AapProjectionActivity.kt:134-601`.
2. **Ya está probado en el C10.** Es el mecanismo que usa Qonecta+: márgenes por lado con el hueco a la derecha, cambiados en caliente, con confirmación y reenviados en cada conexión (`Q+/com/headqlink/link/AaPassthroughSource.java:559-637, 805-824`).
3. **Los toques no cambian.** Con L=0, la x del contenido de AA es la x del frame y la x de pantalla.
4. **(b) choca con el código de DiAuto.** Lo detallo en el §6.
5. **"Negociar la resolución del 70 %" no existe en caliente.** La resolución del códec, los fps y la densidad solo viajan en ServiceDiscovery. 1344×882 seguiría siendo 1920×1080 más márgenes (`DiAuto/utils/HeadUnitScreenConfig.kt:302`). Lo único que cambia sin reconectar son los márgenes, así que (a) y (b) usan la misma palanca. La diferencia es que (b) además redimensiona la superficie.

---

## 1. Cómo crea y pone a pantalla completa la proyección `AapProjectionActivity`

**Layout.** `res/layout/activity_headunit.xml:2-6` es un `FrameLayout @id/container` negro con `match_parent`. Dentro solo está `loading_overlay` (8-115). Se infla en `AapProjectionActivity.kt:770`.

**La vista de proyección.** La crea `setupProjectionView()` (1863-1915) según `viewMode`:
- `TextureProjectionView`, `GlProjectionView` o `ProjectionView` (SurfaceView), siempre con `MATCH_PARENT` (1876-1901).
- `HeadUnitScreenConfig.init(this, displayMetrics, settings)` (1903).
- `container.addView(view)` **al final** (1906).

El renderizador por defecto es **TEXTURE**, salvo en el modelo `DiLink5.1` (`DiAuto/view/ProjectionRendererPolicy.kt:5-8`, `utils/Settings.kt:238-243`). En el C10 saldrá TextureView.

**Capa táctil.** `OverlayTouchView` es transparente, clicable y `MATCH_PARENT`. Se añade después de la proyección (864-882) y lo manda todo a `sendTouchEvent`.

**Pantalla completa.**
- `setFullscreen()` (1310-1343) llama a `SystemUI.apply`, en `DiAuto/utils/SystemUI.kt:17-200`, que hace esto:
  - `KEEP_SCREEN_ON` (19);
  - recorte de notch `SHORT_EDGES` (22-26);
  - `setDecorFitsSystemWindows(false)` en inmersivo (31-35);
  - oculta las barras del sistema con `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` (38-51), más las banderas antiguas `IMMERSIVE_STICKY` (59-67);
  - pone el padding del contenedor según los insets manuales y llama a `HeadUnitScreenConfig.updateInsets` (111-120, 148-196).
- Se vuelve a aplicar en `onResume` (1018) y `onWindowFocusChanged` (1027-1033).
- Por defecto `fullscreenMode = IMMERSIVE` (`Settings.kt:353-354`).
- La orientación se bloquea en `applyOrientationSettings` (767, 1846-1861).
- El manifiesto declara `singleTask`, `configChanges` con orientación y tamaño, y `resizeableActivity` (`AndroidManifest.xml:94-103`).
- `bydFullscreen.expand` (885) es solo para BYD y se va en la F0.

**Escalado.** `ProjectionViewScaler.updateScale` (`DiAuto/view/ProjectionViewScaler.kt:13-126`) deja la vista a `MATCH_PARENT` y aplica `getScaleX/getScaleY` alrededor del **centro**:
- TextureView: `View.scaleX/Y` (`TextureProjectionView.kt:61-66`);
- SurfaceView: `View.scaleX/Y` (`ProjectionView.kt:89-92`);
- GLES: escala la matriz MVP (`GlProjectionView.kt:64-66, 642-644`).

`updateScale` vuelve a llamar a `HeadUnitScreenConfig.init()` **cada vez** (líneas 18-19).

> **Ojo con los nombres.** En DiAuto, "fullscreen" significa **barras del sistema**: `FullscreenMode` (`Settings.kt:1407-1417`), `activityFullscreenOverride` (91-93) y el gesto de dos dedos (1499-1510). Nuestro conmutador debe llamarse `extendedMode` para no mezclar conceptos.

---

## 2. Resolución, densidad y márgenes

**`HeadUnitScreenConfig.init`** (`DiAuto/utils/HeadUnitScreenConfig.kt:50-188`):
- Lee la ventana y la pantalla. En API 30+ usa `realW = max(displayMetrics, currentWindowMetrics)` (59-75) y normaliza la orientación (97-115).
- El ancla es la pantalla entera en inmersivo y la ventana usable fuera de él (136-144).
- Siembra los insets manuales (151-154).
- Puede usar el tamaño de superficie guardado en caché (156-183). **En inmersivo nunca acepta un ancla menor que la ventana** ("Stale cached anchor… using the window", 168-176).
- Sale pronto solo si el ancla coincide con la ventana (122).

**`recalculate`** (250-355):
- Área usable = ancla − insets (252-253).
- AUTO elige 1920×1080 si el ancho pasa de 1280 (296-304), con tope por panel (313-322).
- `isSmallScreen` y `scaleFactor` (333-352).
- Márgenes: `(ajustado − usable)/scale` (402-410).
- Por lado se reparten a medias (471-475).

La resolución se bloquea en `HandshakeComplete` (`AapProjectionActivity.kt:837`). La densidad es `dpiPixelDensity` si está fijada y, si no, la `densityDpi` de la propia unidad (452-458).

**`ServiceDiscoveryResponse.kt`** (`DiAuto/aap/protocol/messages/`):
- Llama a `init` con el contexto del servicio (25).
- La `VideoConfiguration` lleva resolución, `setDensity` (112), PAR (113) y `setMarginWidth/Height` con el significado simétrico (114-115).
- La pantalla táctil se declara con el tamaño negociado (126-129).
- El proto ya tiene `optional UiConfig ui_config = 11` (`proto/control.proto:110`). DiAuto solo lo usa en el sink del clúster (`ClusterDisplayServices.kt:70-77`).

**Números del C10** (suponiendo ventana inmersiva de 1920×882):

| | Pantalla completa | Extendido 30 % |
|---|---|---|
| Códec | 1920×1080 | igual (no cambia en caliente) |
| ServiceDiscovery | `margin_width` 0, `margin_height` 198 | igual |
| Márgenes por lado | L0 T99 R0 B99 | L0 T99 **R576** B99 |
| Contenido de AA | 1920×882 | 1344×882 (1075 dp a 200 dpi) |
| Escala de la vista | 1,0 × 1,2245, centrada (`HeadUnitScreenConfig.kt:416-450`, estirado por defecto `Settings.kt:74-76`) | igual |
| Panel | oculto | 576 px a la derecha, encima |

---

## 3. Márgenes en caliente con `UpdateUiConfigRequest`

**Lo que hace DiAuto:**
- En `onSurfaceChanged` (1544-1569), si la superficie no coincide con lo esperado, guarda el tamaño en caché (1554-1556). Si el transporte está en `TransportStarted`, llama a `commManager.sendUpdateUiConfigRequest(getLeft, getTop, getRight, getBottom)` (1558-1567). Esos márgenes son **simétricos**.
- El mensaje (`DiAuto/aap/protocol/messages/UpdateUiConfigRequest.kt:12-39`) va por `Channel.ID_VID` y lleva **solo** `margins`. Es idéntico en Qonecta+.
- El proto (`proto/media.proto:125-149`) define `Insets` como top/bottom/left/right y `UiConfig` como margins/content_insets/stable_content_insets/ui_theme.
- `CommManager.kt:756-766` añade detrás un `VideoFocusEvent(gain, unsolicited)`, que no molesta.
- La respuesta llega por `AapControl.kt:81-85` sin mirar el `status`. La actividad solo la registra en el log (929-931).

**¿AA los acepta sin reconectar? Sí, los márgenes.**
1. DiAuto los manda a mitad de sesión.
2. Qonecta+ los cambia en caliente en el C10 cada vez que cambia el ancho de AA. Con el panel a la derecha usa R≈320. Con la pantalla partida usa R≈900.
   - `setAaRegion` → `announceTopAlignedMargins` → `sendMargins` (`AaPassthroughSource.java:805-824, 559-594`).
   - Comentario de la línea 818: "AA se rehace con cada cambio".

**Matices medidos:**
- **AA rehace su interfaz con cada cambio de márgenes.** Se ve una pantalla negra con su logo (`Q+/com/headqlink/link/CarUi.java:697-698`). Qonecta+ evita cambiar márgenes cuando el panel se despliega sobre AA (649-656). El conmutador es, por tanto, un salto puntual: **nunca animar el ancho**.
- **Si se mandan antes de que AA dé imagen, o tras un relanzamiento, AA se queda con los de serie.** Por eso Qonecta+ espera confirmación (2500 ms, hasta 4 intentos) y los repite con el **primer frame de cada conexión** (`AaPassthroughSource.java:571-626, 631-632`).
- **Un `UpdateUiConfigRequest` no trae keyframe** (16 de 16, `DiAuto/aap/KeyframeCycleEscalationPolicy.kt:17-19`).
- **No meter `content_insets` en caliente.** En el canal del clúster tiraba la conexión (`DiAuto/hud/ClusterMapConfig.kt:195-202`).
- **Lo que no cambia en caliente:** la resolución del códec, los fps y la densidad. Hay que reconectar (`Q+/com/headqlink/link/SettingsScreen.java:1081-1084`).

---

## 4. Toques: de la vista a las coordenadas de AA

`sendTouchEvent` (1635-1742):
- `videoW/H` = tamaño negociado.
- `viewW/H` = tamaño de la **capa táctil**. Es el modo "medido", activo por defecto en inmersivo (`Settings.kt:88-90`, líneas 1648-1665). Si no, se usa el área usable.
- `marginW/H` = márgenes de `HeadUnitScreenConfig` (1669-1670).
- Con estirado: `x = px/viewW·(videoW−marginW)` y `y = py/viewH·(videoH−marginH)` (1707-1709; `DiAuto/aap/TouchCoordinateMapper.kt:33-35`).
- Recorta a 0..videoW (1735-1736; mapper 59-62).

Son coordenadas **relativas al contenido**: no se suma el margen izquierdo ni el superior. La base Open Headunit más nueva lo confirma midiendo lo que AA registra (`Q+/com/andrerinas/openheadunit/input/TouchCoordinateMapper.kt:30-33`).

**En (a)** la capa táctil sigue a pantalla completa y el margen L sigue a 0, así que el mapeo queda igual: una x de pantalla en [0, 1344) es la misma x en el contenido de AA. El panel está encima y es clicable, de modo que recibe sus propios toques. Android entrega el gesto entero a quien recibió el primer DOWN. Basta con recortar x a `aaVideoW − 1` en Extendido para que un arrastre que empieza en AA y pasa sobre el panel no salga del contenido, como hace Qonecta+ (`AaPassthroughSource.java:938-954`). La Y queda como hoy.

---

## 5. Qonecta+: qué hay y qué se reutiliza

- **Su pantalla partida no está en `AapProjectionActivity`.** En el móvil esa actividad se cierra nada más crearse (`Q+/com/andrerinas/openheadunit/aap/AapProjectionActivity.kt:965-980`). Está en `AaPassthroughSource`, `GlFrameRelay` y `CarUi`.
- **`AaSize.java:21-35`** solo calcula los dpi: 200 en anchos de más de 1280. **Se reutiliza** como densidad por defecto.
- **`AaPassthroughSource`:**
  - frame de 1920×1080 con todo el margen a la derecha y abajo (559-569);
  - `sendMargins` con confirmación y reintentos (571-594);
  - `hookMarginReply` encadena la respuesta (596-609);
  - `marginsForNewAa` reenvía por `transportGeneration` (611-626; contador en `Q+/com/andrerinas/openheadunit/connection/CommManager.kt:153-155, 517`);
  - toques con origen y recorte (909-988).
  - **Se portan a Kotlin** `sendMargins`, `hookMarginReply`, `marginsForNewAa` y el recorte de x. VideoTap, el relay y el recorte de SPS no.
- **`GlFrameRelay.drawAa`** (736-741) recorta en GL la franja de AA porque el coche solo recibe la salida del codificador. **En la unidad no hace falta**: el panel tapa esa zona.
- **`CarUi`:**
  - ancho mínimo de AA **medido en el C10**: falla a 912 px y va bien a 950 px a 200 dpi. Regla: `max(800 dp, 1,15·alto)` (`CarUi.java:591-607`). **Se reutilizan las constantes.**
  - opción de panel a la derecha (`Config.java:995-1000`).
- **Lo que no hace falta:** el `externalCanvas`/`externalDpi` de su `HeadUnitScreenConfig` (15-26, 480-486, 664-671) es para el relay del móvil. Su `reannounceMargins`/`marginsMatchAnnounced` (2094-2128, 127-133) aporta solo la idea de no repetir márgenes ya anunciados.

---

## 6. (a) frente a (b)

| Criterio | (a) márgenes + panel encima | (b) vista al 70 % |
|---|---|---|
| Superficie y decodificador | intactos | cambian de tamaño en cada conmutación (`surfaceChanged`); riesgo de parones #650 |
| `HeadUnitScreenConfig` | sin cambios | `init()` se repite en cada `updateScale` (`ProjectionViewScaler.kt:18-19`). En inmersivo reancla al ancho de ventana (168-176) y deshace el 70 % |
| TextureView (por defecto en el C10) | indiferente | `onSurfaceTextureSizeChanged` no avisa a la actividad (`TextureProjectionView.kt:84-87`): nadie reenvía márgenes |
| Escalado | como hoy | `scaleX` de 1,43 centrado. La fórmula no estirada de `scaleY` falla con márgenes en los dos ejes (`HeadUnitScreenConfig.kt:441, 449`) |
| Toques | sin cambios | hay que redimensionar también la capa táctil (`viewW` medido, 1652-1655). Con L=R=288 depende de cómo interprete AA el origen |
| Vía "insets manuales" (`insetRight`) | — | funciona en estático, pero `insetRight` entra en el hash (534-537): desbloquea la resolución, invalida la caché y no dispara ningún `UpdateUiConfig` |
| Coste en AA | 1 `UpdateUiConfig` (AA se rehace) | lo mismo, más redimensionar la superficie |
| Probado en el C10 | sí (Qonecta+) | no |

En (a) no hace falta "recortar" en GL. Si algún día se quiere un panel translúcido, hay patrones listos: `GlFrameRelay.drawAa` o `ClusterMapConfig.cropMatrix` con `setTransform` (`ClusterMapConfig.kt:218-231`, `ClusterMapScreen.kt:113-122`).

---

## 7. Diseño concreto

### 7.1 Ajustes (`DiAuto/utils/Settings.kt`, junto a 344-356)
- `extendedMode: Boolean`, clave `qu-extended-mode`. El valor por defecto está por decidir.
- `extendedPanelPercent: Int`, clave `qu-extended-panel-percent`, 30 por defecto (entre 20 y 40).
- **No meter ninguno en `computeSettingsHash`** (`HeadUnitScreenConfig.kt:529-548`). Así el conmutador ni desbloquea la resolución ni invalida la caché. `HeadUnitScreenConfig` sigue describiendo la pantalla completa.

### 7.2 Geometría pura: `ExtendedLayout.kt` (nuevo, con tests)
```
viewW    = ancho del contenedor sin padding
uiW      = negW − getWidthMargin()      uiH = negH − getHeightMargin()
minAA    = max(ceil(800·dpi/160), ceil(1,15·uiH))                  // CarUi.java:597-598
panelPx  = min(round(viewW·pct/100), viewW − ceil(minAA·viewW/uiW))   // si < ~200 px: no hay Extendido
aaVideoW = floor((viewW − panelPx)·uiW/viewW)
extraR   = uiW − aaVideoW
márgenes = (getLeftMargin, getTopMargin, getRightMargin + extraR, getBottomMargin)
```
Valores en el C10 (1920, 200 dpi): panel 576, `extraR` 576, AA 1344. Con 320 dpi el panel baja a 320. Con una ventana de 2560 el panel sale de 768 px y `extraR` sigue en 576.

### 7.3 Controlador de márgenes: `ExtendedMarginsController.kt`
Es un `object` de proceso, para sobrevivir a relanzamientos de la actividad. Es el port de `AaPassthroughSource.java:571-637`.
- **`apply(reason)`:**
  - fuera de `TransportStarted` solo marca que hay márgenes pendientes;
  - no repite unos márgenes ya confirmados en la misma generación de transporte;
  - si toca enviar, llama a `commManager.sendUpdateUiConfigRequest(l, t, r, b)` y espera confirmación: 2500 ms, hasta 4 intentos.
- **Confirmación:** encadena `commManager.onUpdateUiConfigReplyReceived` (`CommManager.kt:165`) y pasa al hilo principal. Sustituye el hook que hoy solo registra en el log (929-931).
- **Nueva conexión:** añadir `transportGeneration++` donde se crea `AapTransport` (`CommManager.kt:396-398`), como `Q+ CommManager.kt:517`.
- **Primer frame:** `onFirstFrame()` se llama desde los dos `onFirstFrameListener` (911-927 y 819-823). El listener es de un solo disparo (`VideoDecoder.kt:347, 1884`). Reenvía los márgenes por conexión.
- **Márgenes explícitos en los dos modos.** Se mandan con el primer frame también en Pantalla completa, como hace Qonecta+ fuera del Extendido (`AaPassthroughSource.java:389, 726`). Así AA dibuja justo donde recorta el escalador, haga lo que haga con los márgenes de ServiceDiscovery.
- **Superficie:** la ruta de desajuste de superficie (1558-1566) pasa por `apply("superficie")`. Si no, volvería a mandar márgenes simétricos y se perdería el panel.

### 7.4 Vista y orden de dibujo (`AapProjectionActivity.kt`)
- `ExtendedPanelView` se crea por código tras la capa táctil (después de 881) con `FrameLayout.LayoutParams(panelPx, MATCH_PARENT, Gravity.END)` e `isClickable = true`. Sus botones no deben ser enfocables al tocar: las teclas van igual por `dispatchKeyEvent` (1745-1770).
- `panelPx` se recalcula con un `OnLayoutChangeListener` del contenedor.
- **No se redimensionan ni la proyección ni la capa táctil.**
- **Cambio obligatorio:** en `setupProjectionView`, `container.addView(view)` (1906) pasa a ser `container.addView(view, 0)`. Si no, una vista reconstruida queda **encima** del panel: `recreateProjectionView` (458-478) se llama desde la recuperación #650 (600) y desde el cambio de renderizador (709). Con TextureView el panel quedaría tapado. Con SurfaceView o GL, el "agujero" de la superficie lo borraría.
- Opcional: subir el panel por encima de `loading_overlay` (901) para que se vea el reloj mientras arranca AA.

### 7.5 Conmutador
- **Botones:** en el panel, "Pantalla completa". En Pantalla completa, un botón flotante pequeño "Extendido" (unos 48 dp, alfa 0,6, separado unos 12 px del borde derecho). Como reserva, una entrada en `showExitDialog` (1347-1403).
- **Pasar a Extendido:** guardar el ajuste, mostrar el panel (tapa la zona de AA mientras se rehace) y llamar a `apply("conmutador")`.
- **Pasar a Pantalla completa:** guardar el ajuste, llamar a `apply`, y ocultar el panel al llegar la `UpdateUiConfigReply` (o a 1 s) para que no se vea el margen negro mientras AA se rehace.
- Ignorar pulsaciones con menos de 1,5 s de separación.
- **Arranque en el último modo:** `onCreate` lee el ajuste. Los márgenes salen con el primer frame. Si la sesión ya está viva (895-898), se aplican al momento; la comprobación de repetidos evita un rehacer inútil de AA.

### 7.6 Densidad
La densidad es fija por conexión (`ServiceDiscoveryResponse.kt:112`). Recomiendo `dpiPixelDensity` = 200 por defecto en la unidad, como `AaSize`. Con la `densityDpi` de la unidad, si fuera de 270 o más, los 1344 px quedarían por debajo de 800 dp. Entonces AA pasa al diseño de tarjetas y Maps al modo reducido (`CarUi.java:591-598`).

### 7.7 Casos borde
- En PiP: ocultar el panel sin tocar márgenes (1456-1469).
- Con `hudMirroring`: sin Extendido.
- Con barras visibles: el padding del contenedor ya lo absorbe.

Experimento opcional para la v1.1: anunciar los márgenes del Extendido ya en ServiceDiscovery, vía `ui_config`, para que AA arranque directamente al 70 %. Eso no está probado en la pantalla principal, así que se mantiene igual el `UpdateUiConfig` del primer frame.

### 7.8 Pruebas
1. Tests puros de `ExtendedLayout` con los casos del C10 de arriba.
2. AVD `coche` para el aspecto, con el panel en DEMO.
3. Self Mode en el S25 (existe: `AapService.kt:1936, 3384`), con Qonecta+ parado, para márgenes y toques.
4. Coche, comprobando que no haya desfase de 99 px en Y.

## Ficheros
- qd/ref/diauto/app/src/main/java/com/andrerinas/openheadunit/aap/AapProjectionActivity.kt | Actividad de proyección. Crea la vista (setupProjectionView 1863-1915), la capa táctil (864-882) y la pantalla completa (1310-1343); manda márgenes en caliente al cambiar la superficie (1544-1569) y mapea los toques (1635-1742). Aquí van el panel, el conmutador, addView(view, 0) en 1906, el enrutado de márgenes por el controlador y el recorte de x de los toques. | adaptar
- qd/ref/diauto/app/src/main/res/layout/activity_headunit.xml | FrameLayout raíz @id/container (2-6) con loading_overlay. El panel puede crearse por código; si se declara aquí, con layout_gravity=end y layout_height=match_parent. | no hace falta
- qd/ref/diauto/app/src/main/java/com/andrerinas/openheadunit/utils/Settings.kt | Preferencias (prefs en 18-22). Añadir extendedMode y extendedPanelPercent junto a fullscreenMode (344-356); proponer dpiPixelDensity=200 por defecto (257-261). | adaptar
- qd/ref/diauto/app/src/main/java/com/andrerinas/openheadunit/utils/HeadUnitScreenConfig.kt | Resolución, densidad y márgenes simétricos (init 50-188, recalculate 250-355, márgenes 402-410 y 471-475, densidad 452-458). Sigue describiendo la pantalla completa en los dos modos; extendedMode NO debe entrar en computeSettingsHash (529-548). | no hace falta
- qd/ref/diauto/app/src/main/java/com/andrerinas/openheadunit/aap/protocol/messages/ServiceDiscoveryResponse.kt | Anuncia la VideoConfiguration: resolución, setDensity (112), PAR (113), setMarginWidth/Height (114-115) y TouchConfig con el tamaño negociado (126-129). Cambio opcional: densidad fija; experimento v1.1 con ui_config para los márgenes del Extendido. | adaptar
- qd/ref/diauto/app/src/main/java/com/andrerinas/openheadunit/aap/protocol/messages/UpdateUiConfigRequest.kt | Mensaje UiConfig con solo margins por lado, por Channel.ID_VID (12-39). Idéntico en Qonecta+. | copiar tal cual
- qd/ref/diauto/app/src/main/java/com/andrerinas/openheadunit/connection/CommManager.kt | sendUpdateUiConfigRequest (756-766) y hook onUpdateUiConfigReplyReceived (165, 419). Añadir el contador transportGeneration al crear AapTransport (396-398), como Qonecta+. | adaptar
- qd/ref/diauto/app/src/main/java/com/andrerinas/openheadunit/aap/AapControl.kt | Recibe UPDATE_UI_CONFIG_REPLY y llama al hook (81-85). | no hace falta
- qd/ref/diauto/app/src/main/java/com/andrerinas/openheadunit/aap/TouchCoordinateMapper.kt | Mapeo de toques relativo al contenido (33-35; recorte en 59-62). Solo hay que añadir un tope de x (aaVideoW-1) en Extendido. | adaptar
- qd/ref/diauto/app/src/main/java/com/andrerinas/openheadunit/view/ProjectionViewScaler.kt | Escala centrada de la vista a pantalla completa (88-125); llama a HeadUnitScreenConfig.init en cada llamada (18-19). En (a) queda igual. | no hace falta
- qd/ref/diauto/app/src/main/java/com/andrerinas/openheadunit/view/TextureProjectionView.kt | Renderizador por defecto en el C10. Escala con View.scaleX/Y (61-66); al redimensionarse no avisa a la actividad (84-87), una de las razones contra (b). | no hace falta
- qd/ref/diauto/app/src/main/java/com/andrerinas/openheadunit/view/GlProjectionView.kt | Backend GLES: escala en la matriz MVP (642-644); onSurfaceChanged avisa a la actividad (601-607). | no hace falta
- qd/ref/diauto/app/src/main/java/com/andrerinas/openheadunit/view/ProjectionView.kt | Backend SurfaceView: surfaceChanged llega en cada cambio de tamaño (44-48). Si se añade encima del panel, su agujero lo borra. | no hace falta
- qd/ref/diauto/app/src/main/java/com/andrerinas/openheadunit/utils/SystemUI.kt | Modo inmersivo, notch, padding por insets y HeadUnitScreenConfig.updateInsets (17-200). | no hace falta
- qd/ref/diauto/app/src/main/java/com/andrerinas/openheadunit/utils/BydFullscreen.kt | Ajuste de pantalla completa propio de DiLink/BYD (34-77). Se quita en la F0. | no hace falta
- qd/ref/diauto/app/src/main/proto/control.proto | VideoConfiguration con margin_width/height y density (101-103) y ui_config=11 (110), este último solo para el experimento v1.1. | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/AaPassthroughSource.java | Extendido probado en el C10: márgenes por lado con el hueco a la derecha (559-569), sendMargins con confirmación y reintentos (571-594), hookMarginReply (596-609), marginsForNewAa (611-626) y toques con origen y tope (938-954). Se porta esa lógica a Kotlin, sin VideoTap, relay ni recorte de SPS. | adaptar
- qd/hql/app/src/main/java/com/headqlink/link/AaSize.java | Densidad automática: 200 dpi en anchos de más de 1280 (21-35). Base para la densidad por defecto de la unidad. | adaptar
- qd/hql/app/src/main/java/com/headqlink/link/CarUi.java | Ancho mínimo de AA medido en el C10: 800 dp y 1,15 veces el alto (591-607). No cambiar márgenes al desplegar el panel (649-656) y cada cambio de márgenes rehace AA (697-698). Solo se reutilizan las constantes y la regla. | adaptar
- qd/hql/app/src/main/java/com/headqlink/link/GlFrameRelay.java | Recorte GL de la franja de AA hacia el codificador (drawAa 736-741). En la unidad no hace falta: el panel tapa esa zona. | no hace falta
- qd/hql/app/src/main/java/com/andrerinas/openheadunit/aap/AapProjectionActivity.kt | En el móvil se cierra nada más crearse (965-980); su pantalla partida no está aquí. Solo aporta la idea de no repetir márgenes ya anunciados (2094-2128). | no hace falta
- qd/hql/app/src/main/java/com/andrerinas/openheadunit/connection/CommManager.kt | Contador transportGeneration (153-155, 517), que se trae a la unidad. | adaptar
- qd/hql/app/src/main/java/com/andrerinas/openheadunit/utils/HeadUnitScreenConfig.kt | externalCanvas/externalDpi (15-26, 480-486, 664-671), pensados para el relay del móvil. | no hace falta
- qd/hql/app/src/main/java/com/andrerinas/openheadunit/input/TouchCoordinateMapper.kt | Su comentario (30-33) confirma que los toques van relativos al contenido, sin sumar el margen. | no hace falta
- qd/unit/app/src/main/java/com/headqlink/unit/extended/ExtendedLayout.kt | NUEVO, puro: panelPx, extraR, aaVideoW y márgenes por modo, con el ancho mínimo de AA. | adaptar
- qd/unit/app/src/main/java/com/headqlink/unit/extended/ExtendedMarginsController.kt | NUEVO, object de proceso: apply(reason), confirmación (2500 ms, 4 intentos), reenvío con el primer frame de cada transportGeneration y sin repetir lo ya confirmado. | adaptar
- qd/unit/app/src/main/java/com/headqlink/unit/extended/ExtendedPanelView.kt | NUEVO: panel nativo de la derecha (reloj y fecha, batería y autonomía) con el botón «Pantalla completa». En la v1 es un stub DEMO; los datos de la nube los porta otro agente. | sustituir por stub
- qd/unit/app/src/test/java/com/headqlink/unit/extended/ExtendedLayoutTest.kt | NUEVO: tests puros con el C10 (1920 a 200 dpi → panel 576, AA 1344; 320 dpi → panel 320; ventana de 2560 → panel 768 y extraR 576). | adaptar