## Resumen ejecutivo

OpenAutoLink (OAL) es un receptor de Android Auto **y** CarPlay pensado para head units con **Android Automotive OS (AAOS)**, enfocado a los EV de GM (Chevrolet Blazer/Equinox/Bolt EV). Corre **en la unidad** como app normal de Google Play, sin privilegios de sistema ni sideload. Licencia **GPL-3.0-or-later**.

Para el C10 es **peor base que DiAuto**, porque su cuadro de instrumentos, su recepción de AA y su lectura de vehículo dependen de infraestructura exclusiva de AAOS/GM que LeapOS (Android normal) no tiene. Pero aporta **una** pieza que DiAuto no tiene y que para un REEV como el C10 es muy golosa: el **modelo de energía EV inyectado en Google Maps** (batería/rango/curvas de carga, más la lectura del forecast de ruta). Esa capa es Kotlin puro y sí es reaprovechable como inspiración/código puntual; el resto (cluster/HUD) no.

Rutas: todo cuelga de `qd/ref/` (`openautolink/` y `diauto/`). Cito en relativo a cada repo.

## 1. Arquitectura de OpenAutoLink

### Cómo recibe Android Auto
La topología real está en la auditoría del propio repo `openautolink/docs/research/aa-replacement-oal-architecture.md` (§1.1): teléfono con **gearhead** → **companion proxy** (app en el móvil) → TCP sobre Wi-Fi → en la unidad `transport/aasdk/AasdkSession.kt` → **JNI** (`app/src/main/cpp/jni_session.cpp`, `jni_transport.cpp`, `jni_channel_handlers.cpp`) → **aasdk** (fork `mossyhub/aasdk`, submódulo `external/opencardev-aasdk`, `.gitmodules:1-6`) que hace TLS/framing/multiplexado del protocolo AA. De ahí: vídeo → MediaCodec → `SurfaceView`; audio → AudioTrack; nav/media/teléfono → flows Kotlin; touch/teclas/GPS/VHAL/mic → protobuf AA → teléfono.

Avisos importantes:
- `openautolink/docs/architecture.md` describe una topología **vieja y retirada** (bridge TCP de 3 canales 5288/5289/5290 + SBC). No es la actual; la auditoría lo dice explícitamente (§8.3 y §10.3). No fiarse de ese documento.
- Los protos `app/src/main/proto/oal/*.proto` **modelan el contrato de cable de AA de Google**, no un protocolo propio (audit §2.2).
- Inalámbrico = **WPP** (UUID de AA de Google, RFCOMM) con **companion obligatorio** (`transport/bluetooth/AaWirelessBtServer.kt`).

### Cómo hace el cluster (cuadro de instrumentos)
No dibuja el cuadro: **delega en la Car App Library de Google** (`androidx.car.app`) + el **Templates Host** (privilegiado). Obtiene `NavigationManager` del `CarContext` y le pasa `Trip`/maniobras con `navigationStarted()` / `updateTrip()` / `navigationEnded()`. En GM, el servicio interno `OnStarTurnByTurnManager` consume ese `Trip` y lo pinta en el cuadro con sus propios iconos.
- `cluster/OalClusterService.kt:9-21` (CarAppService; explica el camino GM).
- `cluster/ClusterMainSession.kt` — construye `Trip.Builder`/`Step`/`Maneuver`/`TravelEstimate`/`Destination` (líneas 395-458) y lo envía (`updateTrip` en 357).
- `cluster/ClusterManager.kt` — ciclo de vida complejísimo: habilita el componente, lanza `CarAppActivity` para forzar el binding del Templates Host y se **recupera de que GM mate la sesión** (health checks, generaciones de binding). Casi todo ese código es ruido específico de GM.
- `cluster/ClusterNavigationState.kt` — fuente única normalizada de estado de navegación.

Límites que el propio repo reconoce (audit §4.2): no da dibujo arbitrario ni superficie de cluster, no extrae el estado de Maps nativo, y no concede `CAR_DISPLAY_IN_CLUSTER`/`CAR_NAVIGATION_MANAGER` (los posee el Templates Host). Para un AAOS estándar que renderice `Screen.onGetTemplate()` directo en el cluster "haría falta otro tipo de sesión (trabajo futuro)" — `OalClusterService.kt:16-18`.

### Cómo hace el HUD
OAL **no tiene HUD de parabrisas propio**. En su código, "HUD" = el overlay de conexión/estadísticas que dibuja sobre el `SurfaceView` (`ui/projection/ProjectionScreen.kt`). El cuadro/HUD físico lo pinta GM a partir del `Trip`. (Contraste fuerte con DiAuto, que **sí** tiene HUD de parabrisas propio; ver §3.)

### Cómo mete el rango EV en Maps
Esto es lo valioso. OAL **reconstruye el "vehicle energy model" privado de Google** y lo inyecta por el canal de sensores de AA:
- `proto/oal/vehicle_energy_model.proto:7` — comentario literal: *"Reconstructed from AAOS Google Maps + Android Auto APK decompilation"*. Se envía como **sensor tipo 23 VEHICLE_ENERGY_MODEL** (`proto/oal/sensors.proto:239`), más tipos 25 (raw) y 26 (`RawEvTripSettings`). Contiene batería (capacidad, reserva, potencias máx. carga/descarga, regen), consumo (conducción/auxiliar/aerodinámico) y curvas de carga AC/DC.
- Construcción del modelo: `data/EvProfilesRepository.kt` (base de perfiles EV incluida: eficiencia + potencias de carga) y modos **Derived / Multiplier / Manual / Learned** (`docs/ev-range.md`). El modo Learned lo calcula `data/EvLearnedRateEstimator.kt`: EMA de Wh/km desde Δbatería/Δdistancia, integrando distancia de la **velocidad VHAL** porque "GM AAOS bloquea `PERF_ODOMETER`" (líneas 32-46); rechaza outliers, salta muestras en carga/regen, persiste por `marca|modelo|año|banda-de-capacidad` y **nunca por VIN** (líneas 156-164, 251-268).
- Retorno bidireccional: `navigation/VehicleEnergyForecast.kt` **lee de vuelta** el forecast de ruta que calcula Google Maps "over GAL 5.1+" (energía al llegar, distancia a vacío, próxima parada de carga con potencia y tiempo, calidad) y lo muestra en el cluster como "Arrive ~85%" (`VehicleEnergyForecastPolicy.tripText`, líneas 44-48; usado en `ClusterMainSession.kt:444-449`).
- Honestidad del propio proyecto: el aviso de cabecera de `docs/ev-range.md` admite que el modelo **no está calibrado ni verificado** y que **no debe usarse para decidir si una parada de carga es segura**.

## 2. Qué tiene OAL que DiAuto no tenga (y pueda inspirar nuestro fork)

Matiz que corrige la premisa: **DiAuto ya tiene cluster y HUD** (carpeta `diauto/app/.../hud/` con `BydClusterOutput.kt`, `BydHudBridge.kt`, `BydGuidance.kt`, `BydFactoryTurnCode.kt`, y `assets/byd-hud-icons`), y recibe el `ClusterDisplayServices` de AA (`aap/protocol/messages/ClusterDisplayServices.kt`). Así que no es "OAL tiene cuadro y DiAuto no". Lo que OAL aporta **por encima** de DiAuto:

1. **Modelo de energía EV para Google Maps** — lo único realmente exclusivo. Un `grep` de `VehicleEnergyModel|energy_model|EvProfile|LearnedRate|watt_hours|distanceToEmpty|range remaining` sobre `diauto/app/src` sale **vacío**: DiAuto no modela energía. Para el C10 (REEV) esto es el añadido más atractivo.
2. **Estimador de consumo aprendido** robusto y con tests (`EvLearnedRateEstimator.kt`). DiAuto no tiene nada parecido.
3. **Enriquecimiento del cuadro con energía** ("Arrive ~N%") integrado en el `Trip`.
4. **Render de maniobras neutral por Canvas** (`navigation/ManeuverIconRenderer.kt`, `ManeuverMapper.kt`, `DistanceFormatter.kt`), alternativa a depender de los iconos del OEM (DiAuto usa `byd-hud-icons` y códigos de AutoNavi).
5. Un **documento de auditoría honesto** (`docs/research/aa-replacement-oal-architecture.md`) que es un mapa de decisiones y de muros de privilegio reutilizable como checklist.

En cambio, para el cluster/HUD **de integración con el coche** (que es lo que el C10 necesita), quien inspira es **DiAuto**, no OAL.

## 3. ¿Mejor o peor base que DiAuto para el C10?

**Claramente peor base.** El entorno es distinto y manda: AAOS de GM ≠ LeapOS del C10. DiAuto debe seguir siendo la base; OAL es inspiración/código puntual (capa EV).

- **Plataforma.** OAL asume AAOS: Car App Library, Templates Host, `CarAppService`, y VHAL vía `CarPropertyManager`. El C10 es **Android normal** (LeapOS). El cluster de OAL (Templates Host + `OnStarTurnByTurnManager`) y su lectura de vehículo **no existen** en el C10. DiAuto, por el contrario, llega al cuadro con mecanismos de **app normal**: broadcast de AutoNavi (`hud/BydClusterOutput.kt:44`, `AUTONAVI_STANDARD_BROADCAST_SEND` a `com.byd.amapservice`) y bind a un servicio propietario (`hud/BydHudBridge.kt:16-19`, SOME/IP `com.ts.car.someip.service` con transacciones Binder crudas). Ese **patrón** —descubrir el canal propietario del coche y enviarle guidance normalizada (`hud/BydGuidance.kt`)— es exactamente el que habría que replicar para el cuadro del C10, aunque los detalles BYD no sirvan tal cual.
- **Base de código.** DiAuto comparte el **namespace exacto** de Qonecta+ (`com.andrerinas.openheadunit`); es la misma familia open-headunit → reutilización directa. OAL es `com.openautolink.app`, base ajena.
- **Recepción de AA.** DiAuto recibe AA **directamente en Kotlin/Java** (+ffmpeg en `app/src/main/cpp/ffmpeg`), sin companion ni dongle (README DiAuto). OAL exige aasdk nativo (C++/JNI, GPLv3) + companion en el móvil + WPP/gearhead-proxy. Portar eso al C10 sería un retroceso enorme de complejidad para lograr lo que Qonecta+ ya hace.
- **Distribución.** OAL asume AAB en pista interna de Google Play para AAOS, **sin sideload**. El C10 permite instalar APK (es lo que hace LeapLink). Modelos opuestos.
- **CarPlay.** El muro MFi es idéntico en ambos; OAL lo documenta y **no** lo salta (ver riesgos).

Conclusión operativa: lo único de OAL que merece "subir" a nuestro fork es la **capa EV** (Kotlin puro, portable con adaptadores), alimentada en el C10 por señales del coche (nube Leapmotor — 75 señales — o, si el receptor corre en la unidad, lo que LeapOS exponga). El cluster/HUD se copia **en patrón** de DiAuto, no de OAL.

## 4. Licencias: GPL-3.0 (OAL) vs AGPL-3.0 (DiAuto/Qonecta+)

- OAL = **GPL-3.0-or-later** (`THIRD_PARTY_LICENSES.md:3`; `LICENSE` = "GNU GENERAL PUBLIC LICENSE"). La causa es que **aasdk es GPL-3.0-or-later y se enlaza estáticamente vía JNI**, así que el combinado nativo arrastra GPLv3 (`THIRD_PARTY_LICENSES.md:28-30`).
- DiAuto / Qonecta+ = **AGPL-3.0-or-later** (`diauto/LICENSE` = "GNU AFFERO GENERAL PUBLIC LICENSE"; `diauto/COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt`: "version 3 of the License, or (at your option) any later version").
- **Mezcla: compatible.** GPLv3 §13 y AGPLv3 §13 permiten explícitamente combinar una obra GPLv3 con una AGPLv3. Si incorporamos código GPL-3.0-or-later de OAL a Qonecta+ (AGPL), el **conjunto se distribuye como AGPL-3.0** (la cláusula de red §13 de la AGPL rige el combinado cuando hay interacción por red). Obligaciones: **conservar las cabeceras y avisos de copyright/licencia de OAL** en los ficheros reutilizados y mantener el fuente público (Qonecta+ ya lo hace). El "or-later" de ambos facilita la convergencia.
- **Cuidado con el nativo.** Reutilizar el stack C++ de OAL arrastraría aasdk (GPLv3) + OpenSSL/protobuf/abseil; innecesario, porque Qonecta+ ya tiene su propio AA. Reutilizar **solo Kotlin** (capa EV, formateadores de maniobra), no el C++.
- **Assets MFi.** No reutilizar nada bajo la política de "private authentication assets" de CarPlay: OAL **no los incluye** y prohíbe redistribuirlos (README §Authentication).

## Nota ética (coincide con nuestra postura anti-LeapLink)
OAL rechaza explícitamente construir producto sobre una vulnerabilidad: *"Do not build product behavior on the observed exported `com.gm.vehicleinfo.HistoryProvider` weakness. A vulnerability is neither a stable API nor an acceptable product dependency"* (audit §5.2, línea 309). Es la misma línea que seguimos nosotros al descartar el disfraz de paquete y el material MFi de Carlinkit de LeapLink.