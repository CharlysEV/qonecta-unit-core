# Tarea A: pila de nube Leapmotor de Qonecta+ → Qonecta Unit

Análisis estático hecho solo con Glob, Grep y Read. No he compilado ni ejecutado nada, no he tocado `qd/unit`, y no copio certificados, claves, pines ni contraseñas: los cito por fichero y línea.

Abreviaturas de rutas:
- `link/` = `qd/hql/app/src/main/java/com/headqlink/link/`
- `res/` = `qd/hql/app/src/main/res/`
- `diauto/` = `qd/ref/diauto/app/`

## 1. Resumen

- **El núcleo son 10 ficheros Java y 3 stubs.**
  - Seis son puros: solo usan `java.*`, `javax.*`, `android.util.Base64` y `org.json`. Se copian casi tal cual: `LeapCrypto`, `LeapTls`, `LeapHttps`, `LeapApi`, `LeapStatus` y `CertPick`.
  - Los otros cuatro (`CarCloudStore`, `CarCloudSession`, `CarCloud` y `CarCloudActivity`) arrastran media app y hay que recortarlos.
  - Tras el recorte quedan unas **4.000 líneas**.
- **Encaja en DiAuto sin cambiar imports.**
  - Comparten el namespace `com.andrerinas.openheadunit` (`diauto/build.gradle.kts:15`). Por eso `com.andrerinas.openheadunit.R` y `...utils.ToastUtils` (con `@JvmStatic` y la misma firma) compilan igual.
  - El tema es Material3 (`diauto/src/main/res/values/styles.xml:3`), con Material 1.10.0 (`build.gradle.kts:224`) y Java 8 (`:188-191`). He comprobado que el código que se porta no usa APIs de Java 9+.
  - Solo falta `BuildConfig.LAB`, que DiAuto no tiene.
- **Para una v1 «simple»:** el reloj y la fecha del panel no necesitan nada de esto. La nube puede entrar en un segundo paso sin frenar el modo Extendido.

## 2. Grafo mínimo de dependencias

```
CarCloudActivity ─► CertPick ─► LeapTls ─► LeapCrypto
       ├─► CarCloudSession ─► LeapApi ─► LeapCrypto, LeapTls
       │         ├─► LeapHttps ─► LeapApi, LeapTls
       │         ├─► CarCloudStore ─► LeapTls, LeapApi.Session (+ KeystoreBox)
       │         └─► LeapStatus
       └─► CarCloud (hilo de sondeo + Snapshot) ─► CarCloudSession, CarCloudStore, LeapStatus
Stubs: L (log), Str (textos), Http.safeError
```

## 3. Ficha por fichero

| Fichero | Líneas | Acción | Depende de (Qonecta+) |
|---|---|---|---|
| LeapCrypto.java | 351 | copiar tal cual (recorte opcional) | — |
| LeapTls.java | 637 | copiar tal cual | LeapCrypto |
| LeapHttps.java | 157 | adaptar | LeapApi, LeapTls |
| LeapApi.java | 695 | adaptar | LeapCrypto, LeapTls |
| LeapStatus.java | 542 | copiar tal cual | — |
| CertPick.java | 224 | copiar tal cual | LeapTls |
| CarCloudStore.java | 487 | adaptar | LeapTls, LeapApi, CarSpec, L |
| CarCloudSession.java | 444 | adaptar | Leap*, CarCloudStore, CarCloud, CloudHistory, CloudUsage, CarCommand, L, Http, BuildConfig.LAB |
| CarCloud.java | 695 | adaptar | CarCloudStore/Session, LeapStatus, Str, R, L, Http, DemoMode, MateFeed, ChargeCheckJob, CarSensors, WidgetUpdater, LastCharge, CloudHistory, CloudUsage, CarSpec |
| CarCloudActivity.java | 854 | adaptar | todo lo anterior + CarStatusTab, CarOrders, Config, M3, CloudV2Probe, ToastUtils, R, BuildConfig.LAB |
| L.java / Str.java / Http.java | — | sustituir por stub | — |

**`LeapCrypto`** (`link/LeapCrypto.java`) — criptografía sin estado del protocolo antiguo:
- Constantes del protocolo: `APP_VERSION`, `POLICY_ID`, `LANGUAGE`… (32-38).
- Contraseña del PKCS#12 de cuenta, con SM4 y claves de ronda fijas (45-148).
- HKDF-SHA256 y clave de firma (156-181).
- Firma HMAC-SHA256 (194-205) y SHA-256 del login (208-212).
- deviceId del JWT (217-232) y caducidad del token (235-246).
- nonce y `encodeComponent` (249-274).
- Se puede quitar:
  - `operatePassword` y `OPERPWD_*` (279-299), que solo sirven para las órdenes;
  - el bloque V2 (301-333), que solo usa `CloudV2Probe`.
- La cabecera (8-10) dice «nada del PIN», pero sí lo incluye.

**`LeapTls`** (`link/LeapTls.java`) — hace falta entero:
- `Identity`, con `toJson/fromJson` para guardarla cifrada (69-123).
- `parse` del certificado del usuario (135-226):
  - acepta PEM `.crt` + `.key`, PEM combinado, DER, PKCS#8 cifrado y PKCS#12;
  - los errores salen tipados con `ImportException.Kind`.
- `loadPkcs12`, que también abre el PKCS#12 de cuenta (420-434).
- `FixedKeyManager` (459-501).
- `PinTrust`: el pin SPKI de serie más los que el usuario acepte (520-549).
- `spkiPin/fingerprint` (552-569) y `socketFactory` (572-576).
- El pin de serie está en la línea 58: corresponde al certificado del servidor válido de 2026-07 a 2027-08 (57).

**`LeapHttps`** (`link/LeapHttps.java`):
- `post()` (58-86) hace un POST con mTLS usando la identidad que toque:
  - timeouts de 15/20 s y sin redirecciones;
  - tope de 1 MiB en la respuesta (22-24, 145-156).
- Si falla el pin, lo convierte en `ServerKeyChangedException` (27-34, 79-82, 135-143).
- Hay que quitar:
  - `v2Origin` y `sendV2` (88-132);
  - la llamada a `LeapApi.remoteAllowedNow` (60).

**`LeapApi`** (`link/LeapApi.java`):
- **Se queda:**
  - `HOST` y `BASE_URL` (40-41).
  - `PATH_LOGIN`, `PATH_REFRESH`, `PATH_VEHICLES` y `PATH_STATUS` (47-50).
  - `allowed()` y `ALLOWED_PATHS`, reducidos a esas 4 rutas (64-65, 89-96).
  - `Transport`, `Response`, `ApiException` y `SessionExpiredException` (100-145).
  - `Session` (150-195) y `Vehicle.statusPath`, que manda B10/B11 a c10 (198-220).
  - El constructor (266-272).
  - `login` (287-310), `restore` (313-322), `refresh` (325-351), `vehicles` (354-375) y `status` (378-388).
  - `withTokenRetry` (481-491).
  - `call`, las cabeceras, `parse` y `str` (607-690).
- **Se quita:**
  - V2 (42-45).
  - Viajes, consumo, energía y km (51-61, 390-472).
  - Las órdenes (67-86, 237-258, 502-605): `REMOTE_*`, `CMD_*`, `REMOTE_NOW`, `Sleeper`, `remote*` y `notSent`.

**`LeapStatus`** (`link/LeapStatus.java`) — parser puro del `data` de `/status/get/{modelo}`:
- Señales (33-75):
  - 1204 = `soc`;
  - 100003 = `preciseSoc`;
  - 2188 = autonomía;
  - en un REEV: 3235, 3263, 3259 y 3261.
- `merge` (220-235) y hora del dato (336-356).
- `socBest()` (396-398) y `evRangeKm()` (415-422).
- Se puede quitar:
  - `position()` (163-209), que solo usa «Localizar»;
  - `rawSignalsDump` (241-278), que solo usa LAB.

**`CertPick`** (`link/CertPick.java`) — puro:
- Guarda en memoria medio par (`.crt` o `.key`) durante 15 min.
- Clasifica el contenido (PEM, DER o PKCS#12).
- Pone los bytes a cero al descartarlos.

**`CarCloudStore`** (`link/CarCloudStore.java`):
- **Se queda:**
  - `KeystoreBox`: AES-256-GCM con clave del Android Keystore, sin autenticación de usuario (415-486).
  - `identity.bin` y `session.bin` en `getNoBackupFilesDir()/carcloud` (48-51, 81, 90-92, 224-286, 354-388):
    - se escriben de forma atómica;
    - al borrarlos se sobrescriben.
  - Prefs en claro (101-107, 204-220, 325-334, 400-411): activado, modelo, correo enmascarado, pines aceptados y último deviceId.
- **Se quita:**
  - Los perfiles de batería (63-75, 109-202), que arrastran `CarSpec.use` (122).
  - El historial (52-53, 288-300).
  - El PIN (54-55, 302-323).
  - Después hay que ajustar `deleteSession` y `wipeAll` (337-352).
- La llamada a `L.w` (483) pasa al stub.

**`CarCloudSession`** (`link/CarCloudSession.java`) — un único `LeapApi` por proceso, con métodos `static synchronized` (13-15):
- **Se queda:**
  - `NotConfiguredException`, `invalidate`, `transport` y `newApi` (19-71).
  - `ensure` y `persist` (74-111).
  - `REFRESH_AHEAD_MS`, `refreshDue`, `refreshIfDue` y `keepAlive` (116-155).
  - `readStatus` (158-175) y `login` (351-382).
  - `vehicles`, `chooseVehicle`, `vinTail`, `email`, `logout` y `wipe` (385-443).
- **Se quita:**
  - `PageGate` (26-28) y `readTrips` (191-214).
  - `command` (220-240).
  - `Found` y `readPosition` (243-273).
  - `readSplit`, `readWindow` y `readWeekly` (276-345).
  - El volcado LAB (173, 177-185).
- `Http.safeError` (153) y `L` pasan a stub.

**`CarCloud`** (`link/CarCloud.java`):
- **Se queda:**
  - `State` (24-40) y `Snapshot` (43-97).
  - `Policy` (107-158), sin `WATCH_AC_MS` ni la sobrecarga de 6 argumentos (146-157).
  - `acquire` y `release` sin `MateFeed` (226-238).
  - `start` sin `DemoMode` (261) ni `ChargeCheckJob` (266), y `stop`.
  - `setHubVisible` (282-286), que en la unidad será «panel visible».
  - `settingsChanged`, y `snapshot` sin `DemoMode` (311-315).
  - `gate`, `loop` y `pollOnce` (319-433).
  - `publish` (534-538), `takeDailyQuota` (541-556), `sleep` y `wake`.
  - `ago` (672-680), `realLabel` (683-685), `socStale` (691-694) y las constantes `SOC_*` (100-104).
- **Se quita:**
  - `HistoryPolicy` (161-188) y el historial con `pollHistory` (191-199, 438-531).
  - La vigilancia de carga: `chargeWatchMs`, `setChargeWatch` y `onlyChargeWatch` (223, 241-250).
  - `car()` con MateFeed (307-309).
  - Consumo y `tripSplit` (569-655).
  - En `pollOnce`:
    - `adoptReev` y `adoptCarType` (388-395);
    - `WidgetUpdater.poke` (400);
    - `LastCharge.observe` (402).
- `driving()` (378-380) usa `CarSensors` del móvil; lo trato en la sección 5.

**`CarCloudActivity`** (`link/CarCloudActivity.java`) — la pantalla está descrita en la sección 7. Se quita:
- El perfil de batería (160-177, 230-241, 707-762).
- El PIN y las órdenes (85-93, 127-146, 259-266, 767-781).
- La sonda V2 (157, 637-680), porque `BuildConfig.LAB` no existe en DiAuto.
- De `CarStatusTab` solo hacen falta dos métodos, que se copian dentro: `problem()` (`link/CarStatusTab.java:672-684`) y `minutes()` (159-164).

**Stubs:**
- `L` (`link/L.java`):
  - Copiado tal cual arrastra `LogRetention`, `Redact`, `CarTrace`, `QdTrace`, `LowLatency` y `LiveLog` (54, 88, 97, 152, 157, 172).
  - Basta con `init`, `i` y `w` redirigidos a `AppLog`.
  - En DiAuto, `AppLog` es un `object` de Kotlin sin `@JvmStatic` (`diauto/src/main/java/com/andrerinas/openheadunit/utils/AppLog.kt:18`). Desde Java se llama con `AppLog.INSTANCE.i(...)`; también vale `android.util.Log`.
- `Str` (`link/Str.java`):
  - El stub solo necesita `init` y `get(id, args)`, que llama a `getString`. El original depende de `CarSpec` (48-57).
  - Hay que iniciarlo en `App.onCreate`, como hace Qonecta+ (`qd/hql/app/src/main/java/com/andrerinas/openheadunit/App.kt:51`).
- `Http`:
  - Solo hace falta `safeError` (`link/Http.java:25-31`).
  - Copiar la clase entera arrastra `About.REPO_URL` y `BuildConfig.VERSION_NAME` a través de `USER_AGENT` (12-13).
- `M3.field/outlined` (`link/M3.java:19-41`): se copian los dos métodos o se crea un `TextInputLayout` a mano.

## 4. Camino del dato, del fichero al «78 % · 320 km · hace 3 min»

1. **Importar el certificado.**
   - `pickCertificate()` abre `ACTION_OPEN_DOCUMENT` con `CATEGORY_OPENABLE`, tipo `*/*`, `EXTRA_MIME_TYPES` y selección múltiple (CarCloudActivity.java:348-349, 365-369).
   - `onActivityResult` lee cada URI en el hilo `io`, con un tope de 256 KB por fichero (380-424, 496-508; LeapTls.java:61).
   - `CertPick.add` decide si está todo (READY) o falta la clave (NEED_KEY) o el certificado (NEED_CERT) (CertPick.java:95-143).
   - `LeapTls.parse` (162-226) lo lee y `store.saveIdentity` (CarCloudStore.java:238-240) lo guarda.
   - Los bytes leídos se ponen a cero (CarCloudActivity.java:547-549).
   - Si el `.p12` o la clave llevan contraseña, sale un diálogo y se reintenta; el `char[]` de la contraseña se borra (527, 565-589).
2. **Guardar cifrado.**
   - `KeystoreBox` escribe en el formato `[lenIV|IV|cifrado+tag]` y necesita Android 6 (CarCloudStore.java:94-97, 415-486).
   - La contraseña de la cuenta solo pasa por `LeapApi.login` (LeapApi.java:286-293) y el campo se vacía al terminar (CarCloudActivity.java:624).
   - El correo va cifrado en `session.bin` y enmascarado en las prefs (CarCloudStore.java:285, 400-411).
   - Matiz: el campo de contraseña tiene `autofillHints="password"` e `importantForAutofill="yes"` (res/layout/hql_activity_cloud.xml:177-178). Si se quiere garantizar que ni el autorrelleno del sistema la guarde, hay que poner `no`.
3. **Login.**
   - `CarCloudSession.login` reutiliza el deviceId anterior (CarCloudSession.java:354-362).
   - `LeapApi.login` firma con SHA-256 (LeapCrypto.java:208-212) y hace el POST con **mTLS del certificado del usuario** (LeapApi.java:287-293).
   - La respuesta trae token, refreshToken, material de firma y el **PKCS#12 de cuenta** (`base64Cert`) (296-307).
   - `restore()` abre ese PKCS#12 con su contraseña derivada y calcula la clave HMAC (313-322).
   - `vehicles()` (354-375): con un coche, queda elegido; con varios, sale un diálogo (CarCloudActivity.java:626-632, 690-704).
   - `persist()` guarda la sesión cifrada (CarCloudSession.java:94-111).
   - A partir de aquí, todas las peticiones van con mTLS del PKCS#12 de cuenta y cabecera `sign` HMAC (LeapApi.java:635-657).
   - El servidor se comprueba por pin (LeapTls.java:520-549) y por nombre, que lo verifica `HttpsURLConnection` (LeapHttps.java:17-19, 61-63).
4. **Sondeo.**
   - `CarCloud.acquire` arranca el hilo `carcloud` (CarCloud.java:226-267).
   - `loop` (330-375) comprueba `gate`, espera lo que toque, descuenta con `takeDailyQuota` y llama a `pollOnce`.
   - `pollOnce` usa `CarCloudSession.readStatus` (158-175):
     - `ensure` recupera la sesión (74-92);
     - `refreshIfDue` renueva el token si caduca en menos de 60 min (116-138);
     - `api.status` pide `/status/get/c10` (LeapApi.java:378-388) y repite una vez tras refrescar si la nube se queja del token (481-491).
   - `LeapStatus.parse` produce el estado, que se publica como un `Snapshot` volátil (CarCloud.java:398-399).
   - Si la sesión caduca (EXPIRED) o cambia la clave del servidor (SERVER_KEY), se bloquea. Si falla la red (ERROR), conserva el dato anterior (409-432).
5. **Panel.**
   - `CarCloud.snapshot()` no usa red (311-315).
   - Batería con `status.socBest()` (LeapStatus.java:396-398) y autonomía con `status.evRangeKm()` (415-422).
   - La edad se calcula sobre la hora que da el coche o, si no la da, sobre la de la lectura (CarCloud.java:79-85).
   - `ago()` escribe «hace 40 s / 3 min / 2 h / 1 d» (672-680); `realLabel()` (683-685).
   - Referencia en Qonecta+: la pestaña Estado (CarStatusTab.java:172-186). Pinta en ámbar los datos de más de 15 min (32, 166-168) y repinta cada 1 s sin red (30, 107-123).

## 5. Política de sondeo real

| Situación | Espera | Cita |
|---|---|---|
| Base | 120 s | CarCloud.java:113, 142 |
| Sección o panel visible (`setHubVisible(true)`) | 90 s | :114, :282-286 |
| 2 lecturas seguidas sin dato nuevo y sin `driving()` | 5 min y después 15 min | :115, :141, :403 |
| Tras fallos | 2, 5 y 10 min (2 min si `driving()`) | :117, :140, :426-431 |
| Tope diario | 400 lecturas por día local; al llegar, pausa de 15 min | :116, :363-366, :541-556 |
| Sin cuenta, OFF o bloqueado | comprueba cada 30 s, sin red | :119, :336-350 |

- **Javadoc desfasado.** Las líneas 13-14 y 124 hablan de 60 s y 30 s, pero las constantes son 120 s y 90 s.
- **`driving()` en la unidad.** Ahora usa `CarSensors.movingWithin` del móvil (378-380). Si la unidad está encendida, el coche está despierto. Propongo `driving()` = `true`, o usar la velocidad GPS de DiAuto. Si no, la regla de «coche dormido» (5-15 min) espaciaría las lecturas en pleno viaje.
- **El contador diario vive en memoria.** `readsToday` y `readsDay` son estáticos (205-206), así que vuelve a 0 si se reinicia el proceso. Hay que guardarlo en las prefs.
- **Quién pide el sondeo.** El panel: `acquire("panel")` al mostrarse y `release` al ocultarse. A 90 s por lectura, el tope de 400 da para 10 h de panel.

## 6. Lo que se deja fuera (no hace falta para SoC y autonomía)

- **Historial y consumo:** `CloudHistory`, `CloudUsage`, `CloudEnergy` y `CarUsage`, y las rutas `PATH_TRIPS`, `PATH_WEEKLY_EC`, `PATH_ENERGY_SPLIT` y `PATH_MILEAGE`.
- **Órdenes remotas:** `CarCommand`, `CarOrders`, `CarControlActivity`, el PIN, `operatePassword` y `REMOTE_PATHS`.
- **Laboratorio V2:** `CloudV2Probe`, `LeapHttps.sendV2` y la parte V2 de `LeapCrypto`.
- **Home Assistant:** `MateFeed` y `HomeAssistant`.
- **Demostración:** `DemoMode`.
- **Carga en directo y tareas de fondo:** `ChargeCheckJob`, `ChargeWatchService`, `ChargeWatch` y `LastCharge`.
- **Widget del móvil:** `WidgetUpdater`.
- **Perfiles y kWh:** `CarSpec`.
- **Pantallas de Qonecta+:**
  - `CarStatusTab`, `PhoneCar` y `CarHubScreen`.
  - `PhoneInsights`, de la que solo interesa `hasAccount()` (`link/PhoneInsights.java:82-86`).
- **Infraestructura de log:** `Redact`, `QdTrace`, `CarTrace`, `LowLatency`, `LiveLog` y `LogRetention`.

## 7. UI mínima de Qonecta+ (lo que hay que replicar)

En Qonecta+ se entra desde el menú ⚙ (link/HomeActivity.java:835-837) y desde «Mi coche» (link/PhoneCar.java:197, 447). Es una sola pantalla con scroll (`res/layout/hql_activity_cloud.xml`):

- **Cabecera:**
  - barra con el título `hql_cloud_menu` (29-39) e introducción (42-50);
  - aviso si el sistema es anterior a Android 6 (52-60; CarCloudActivity.java:102-106);
  - interruptor «Usar los datos reales del coche» (68-79), opcional.
- **1 · Certificado (81-125):**
  - estado: CN y caducidad, o «Sin certificado…»;
  - el botón cambia de texto entre «Importar certificado…», «Elegir la clave…», «Elegir el certificado…» y «Cambiar certificado…» (CarCloudActivity.java:215-217);
  - «Empezar de nuevo» aparece cuando hay medio par elegido (218-219).
- **2 · Cuenta (127-223):**
  - correo, contraseña con botón de ver, nota de que la contraseña no se guarda y «Entrar»;
  - con sesión iniciada: «Sesión iniciada: c***@g***.com» y «Cerrar sesión»;
  - si la sesión caducó, vuelve el formulario con el correo ya puesto (CarCloudActivity.java:180-181, 221-222).
- **3 · Coche (225-263):** modelo y final del VIN («…1234»), con «Elegir coche».
- **Parte final (433-480):**
  - «Leer estado ahora» y el recuadro de resultado. El resumen (`summary`, 798-817) hay que reducirlo a batería, autonomía y edad del dato;
  - la línea «En el coche: …»;
  - «Cerrar sesión y borrar datos»;
  - los créditos de licencia.
- **Diálogos:**
  - contraseña del certificado (565-589);
  - elegir coche (690-704);
  - **nueva clave del servidor** (824-838). Es imprescindible: sin este diálogo, cuando Leapmotor cambie la clave la unidad se queda en SERVER_KEY;
  - confirmar el borrado (840-853).
- **Fuera:** 4 · Batería (265-334), 5 · PIN y órdenes (336-431) y el botón de la sonda V2.
- **Pantalla de 1920×882.** El formulario está pensado para un móvil en vertical:
  - conviene limitar el ancho, por ejemplo a 720 dp centrado;
  - y mantener `adjustResize|stateHidden` (`AndroidManifest.xml:672` de Qonecta+).
- **Si la unidad no tiene selector de ficheros (DocumentsUI).** DiAuto ya lo resuelve con un diálogo y una lista de Descargas o de la carpeta de la app (`diauto/src/main/java/com/andrerinas/openheadunit/main/SettingsFragment.kt:2918-2943`, `utils/SettingsBackupManager.kt:359-365`). Se puede reutilizar para app.crt y app.key.

## 8. Recursos

### Textos

Están en `res/values/hql_cloud_strings.xml` (inglés) y `res/values-es/hql_cloud_strings.xml`, con las mismas claves. Hay traducciones también en de, fr, it, nl, pt-rBR, pt-rPT y zh-rCN. Hacen falta unas 75 claves (values-es:94-163):

- **Pantalla:**
  - Cabecera:
    - `hql_cloud_menu` y `hql_cloud_intro` (hay que reescribirla: habla de Ruta, Eficiencia y Viajes);
    - `_unsupported_phone`, `_enabled` y `_credits` (obligatoria por la licencia).
  - Certificado:
    - `_step_cert`, `_cert_none`, `_cert_ok`, `_cert_until`, `_cert_import`, `_cert_replace`;
    - `_cert_pick_key`, `_cert_pick_cert`, `_cert_restart`, `_cert_reading`, `_cert_have_cert`, `_cert_have_key`;
    - `_cert_saved`, `_cert_password_title`, `_cert_password_hint`.
  - Errores del certificado: `_cert_err_needs_password`, `_wrong_password`, `_no_key`, `_no_cert`, `_mismatch`, `_encrypted`, `_unreadable`, `_too_big`, `_read`, `_unknown`, `_no_picker` y `_save`.
  - Cuenta: `_step_account`, `_email`, `_password`, `_password_note`, `_login`, `_working`, `_logged_in`, `_login_failed`, `_need_cert_first` y `_logout`.
  - Coche: `_step_car`, `_car_none`, `_choose_car`, `_no_cars` y `_shared`.
  - Prueba: `_test`, `_test_result` (simplificado), `_test_failed` y `_status_line`.
  - Borrado:
    - `_wipe` y `_wiped`;
    - `_wipe_confirm`, quitando «Los viajes guardados se quedan».
  - Clave del servidor: `_server_key_dialog_title`, `_server_key_dialog` y `_server_key_accept`.
  - Errores generales: `_err_network`, `_err_expired`, `_err_p12` y `_err_not_configured`.
- **Panel:**
  - `hql_cloud_battery`, `_range`, `_real_ago` y `_ago_s`, `_ago_min`, `_ago_h`, `_ago_d` (values-es:6-16).
  - Estados cortos: `_retry_in`, `_expired_short` y `_server_key_short` (69-71).
  - Estados vacíos grandes, si se quieren (72-85): pares `_title` y `_body` de `_none`, `_off`, `_unsupported`, `_waiting`, `_expired`, `_server_key` y `_error`. Hay que reescribir «en el móvil: ⚙ › Datos del coche».
- **Genéricos:** `hql_save` y `hql_cancel` (`res/values-es/hql_strings.xml:290, 92`) se cambian por `save` y `cancel` de DiAuto (`diauto/src/main/res/values/strings.xml:428, 431`).

### Estilos, colores y fondos

Se copian o se cambian por los equivalentes de DiAuto:
- estilos `HQL.Button`, `HQL.Button.Quiet`, `HQL.Button.Outline`, `HQL.Text.Body`, `HQL.Text.Label`, `HQL.Text.RowTitle` y `HQL.Text.RowDetail` (`res/values/hql_styles.xml:115-177`);
- colores `hql_text`, `hql_text_faint`, `hql_accent`, `hql_warn` y `hql_error` (`res/values/hql_colors.xml:17-32`);
- `res/drawable/hql_panel.xml`.

`ic_arrow_back` ya existe en DiAuto.

## 9. Permisos y manifiesto

- **INTERNET** es el único permiso imprescindible y DiAuto ya lo tiene (`diauto/src/main/AndroidManifest.xml:62`; en Qonecta+ está en la 109).
- **No hace falta nada más:**
  - el Keystore no pide permiso;
  - el selector de ficheros (SAF) da acceso por URI;
  - no hay tráfico en claro ni `networkSecurityConfig`, porque la confianza la pone `PinTrust`.
- **Plan B sin selector** (leer de Descargas por ruta): necesita `READ_EXTERNAL_STORAGE` y Android anterior a 10. DiAuto ya declara `WRITE_EXTERNAL_STORAGE` (72).
- **Declaración de la actividad.** Como en Qonecta+ (`AndroidManifest.xml:666-672`):
  - `exported=false`;
  - `windowSoftInputMode="adjustResize|stateHidden"`;
  - el tema `@style/AppTheme` de DiAuto.
- **Versión mínima.** DiAuto declara minSdk 16/21 (`build.gradle.kts:72, 96`), pero el código exige API 23 en tiempo de ejecución mediante `CarCloudStore.supported()`. Lint avisará de NewApi, pero `abortOnError=false` (183-186).

## 10. Tests reutilizables

Están en `qd/hql/app/src/test/java/com/headqlink/link/`. Son Robolectric en Kotlin, y DiAuto ya trae Robolectric 4.17 (`build.gradle.kts:238`).

- **Tal cual:**
  - `TestCerts.kt`, que genera los certificados durante la prueba, sin ficheros;
  - `LeapTlsTest.kt`, `CertPickTest.kt` y `LeapStatusTest.kt`.
- **Recortando:**
  - `LeapApiTest.kt`: quitar las pruebas de órdenes, viajes, consumo, energía y km (269-496) y adaptar `onlyReadOnlyPaths` (237);
  - `LeapCryptoTest.kt`: quitar lo de V2 y el PIN;
  - `CarCloudTest.kt`: quitar `batteryProfiles`, `reevProfile…`, `historyIsSealed…` e `historyPolicy` (204-271).

## 11. Riesgos y preguntas abiertas

Los detalles van en los campos `risks` y `openQuestions`. Los tres principales:

1. **Una sesión por cuenta** (`qd/hql/MANUAL.de.md:1063-1064`). Si la unidad usa la misma cuenta que Qonecta+ o que la app oficial, se cierran la sesión mutuamente.
2. **La sesión puede caducar con la unidad apagada.** Qonecta+ mantiene vivo el token despertándose cada 15 min con `ChargeCheckJob` (ChargeCheckJob.java:78-79; CarCloudSession.java:140-155). Si al refrescar un token ya caducado la nube lo rechaza, la unidad pedirá la contraseña cada vez que arranque.
3. **El protocolo antiguo empieza a fallar.** El historial de viajes lo rechaza desde el 28-10 (CloudV2Probe.java:19-21).


## Ficheros
- qd/hql/app/src/main/java/com/headqlink/link/LeapCrypto.java | Criptografía sin estado del protocolo antiguo: contraseña del PKCS#12 de cuenta (SM4 con claves de ronda fijas), clave de firma HKDF-SHA256, firma HMAC-SHA256 y SHA-256 del login, deviceId del JWT, caducidad del token, nonce y encodeComponent. Recorte opcional: operatePassword/OPERPWD_* (279-299, solo órdenes) y bloque V2 (301-333, solo CloudV2Probe). | copiar tal cual
- qd/hql/app/src/main/java/com/headqlink/link/LeapTls.java | Identidad mTLS e importación del certificado del usuario (PEM .crt+.key, PEM combinado, DER, PKCS#8 cifrado, PKCS#12) con errores tipados; toJson/fromJson para guardarla cifrada; carga del PKCS#12 de cuenta; FixedKeyManager, PinTrust (pin SPKI de serie en la línea 58 más los aceptados) y socketFactory. Hace falta entero. | copiar tal cual
- qd/hql/app/src/main/java/com/headqlink/link/LeapHttps.java | Transporte real: POST con HttpsURLConnection y mTLS, pin del servidor, timeouts de 15/20 s, sin redirecciones, tope de 1 MiB; convierte el fallo de pin en ServerKeyChangedException. Quitar v2Origin/sendV2 (88-132) y la llamada a LeapApi.remoteAllowedNow (60). | adaptar
- qd/hql/app/src/main/java/com/headqlink/link/LeapApi.java | Cliente del protocolo antiguo (appgateway.leapmotor-international.de): login con el certificado del usuario, restore del PKCS#12 de cuenta y de la clave HMAC, refresh, lista de coches, estado, withTokenRetry, cabeceras firmadas, parse y lista blanca de rutas. Quitar V2 (42-45), viajes/consumo/energía/km (51-61, 390-472) y órdenes (67-86, 237-258, 502-605); dejar ALLOWED_PATHS/allowed() con login, refresh, list y status/get. | adaptar
- qd/hql/app/src/main/java/com/headqlink/link/LeapStatus.java | Parser puro del data de /status/get/{modelo}: señales 1204/100003/2188 (+ REEV 3235/3263/3259/3261), hora del dato (collectTime, signal.sts), socBest(), evRangeKm(), carga y logLine sin datos personales. Recorte opcional: position() (163-209) y rawSignalsDump (241-278). | copiar tal cual
- qd/hql/app/src/main/java/com/headqlink/link/CertPick.java | Medio par .crt/.key elegido en varias veces (en memoria, caduca a los 15 min), clasificación PEM/DER/PKCS#12 y borrado a ceros. Puro. | copiar tal cual
- qd/hql/app/src/main/java/com/headqlink/link/CarCloudStore.java | Almacén: identity.bin y session.bin cifrados con AES-256-GCM del Android Keystore (KeystoreBox, sin autenticación de usuario) en getNoBackupFilesDir()/carcloud; prefs en claro (activado, modelo, correo enmascarado, pines aceptados, último deviceId). Quitar perfiles de batería (63-75, 109-202; arrastran CarSpec), historial (52-53, 288-300) y PIN (54-55, 302-323); ajustar deleteSession/wipeAll (337-352). L.w (483) va al stub. | adaptar
- qd/hql/app/src/main/java/com/headqlink/link/CarCloudSession.java | Fachada static synchronized con un único LeapApi: ensure/restore, persist tras cada cambio de sesión (con el deviceId recordado), renovación del token 60 min antes de caducar, keepAlive, readStatus, login (elige el coche solo si hay uno), vehicles, chooseVehicle, logout, wipe, vinTail, email. Quitar PageGate, readTrips, command, readPosition, readSplit, readWindow, readWeekly y el volcado LAB (173, 177-185). | adaptar
- qd/hql/app/src/main/java/com/headqlink/link/CarCloud.java | Sondeo en un hilo propio con dueños (acquire/release), Snapshot inmutable con estado y edad del dato, estados NO_ACCOUNT/OFF/WAITING/OK/ERROR/EXPIRED/SERVER_KEY/UNSUPPORTED, Policy (120 s; 90 s visible; 5-15 min sin dato nuevo; 2/5/10 min tras fallos; 400 al día) y textos «hace X». Quitar historial, consumo, tripSplit, MateFeed, DemoMode, ChargeCheckJob, WidgetUpdater, LastCharge y perfiles; driving() a true en la unidad; guardar el contador diario en prefs. | adaptar
- qd/hql/app/src/main/java/com/headqlink/link/CarCloudActivity.java | Pantalla «Datos del coche (cuenta Leapmotor)»: importar certificado (ACTION_OPEN_DOCUMENT múltiple, medio par, contraseña), entrar con correo y contraseña (no se guarda), elegir coche, «Leer estado ahora», aceptar la nueva clave del servidor y borrar todo. Quitar perfil de batería, PIN, órdenes y sonda V2 (BuildConfig.LAB no existe en DiAuto); copiar CarStatusTab.problem() dentro; limitar el ancho para 1920x882; plan B sin selector de ficheros. | adaptar
- qd/hql/app/src/main/java/com/headqlink/link/L.java | Log de Qonecta+ (fichero, Redact, log unificado, LiveLog). Stub con init(Context), i(String) y w(String) hacia AppLog.INSTANCE o android.util.Log. | sustituir por stub
- qd/hql/app/src/main/java/com/headqlink/link/Str.java | Textos sin Context, con el nombre del coche (CarSpec). Stub: init(Context) y get(id, args) con getString; iniciarlo en App.onCreate. | sustituir por stub
- qd/hql/app/src/main/java/com/headqlink/link/Http.java | Solo hace falta safeError (25-31) para el log; copiar la clase arrastra About.REPO_URL y BuildConfig.VERSION_NAME (12-13). Copiar solo el método. | sustituir por stub
- qd/hql/app/src/main/java/com/headqlink/link/M3.java | Ayudas field/outlined (19-41) para los diálogos de contraseña; copiar los dos métodos o crear el TextInputLayout a mano. | sustituir por stub
- qd/hql/app/src/main/java/com/headqlink/link/CarStatusTab.java | Pestaña Estado del modo extendido de Qonecta+. Solo interesan problem() (672-684) y minutes() (159-164), copiados dentro; referencia visual del anillo batería + autonomía + edad (172-186) y del ámbar a partir de 15 min (32). | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/PhoneInsights.java | Pantallas del móvil; solo interesa hasAccount() (82-86): copiar esas 4 líneas a CarCloud. | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/CarSpec.java | Modelo del coche y capacidad por perfil (kWh); solo para pasar el % a kWh y para los textos con el nombre del coche. | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/Config.java | Ajustes de Qonecta+; aquí solo para el interruptor de órdenes del widget. | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/CloudHistory.java | Historial de viajes de la nube (kWh y litros por viaje, consumo semanal). | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/CloudUsage.java | «Consumo» por ventana de tiempo (km y reparto de la energía). | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/CloudEnergy.java | Consumo real a partir de la bajada del % y la capacidad del perfil. | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/CarUsage.java | Tarjeta «Consumo» de «Mi coche». | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/CloudV2Probe.java | Sonda de laboratorio del protocolo nuevo (app oficial 1.16.4). | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/CarCommand.java | Órdenes al coche (cerrar, abrir, maletero, clima, cable). | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/CarOrders.java | Órdenes desde el widget de Qonecta+. | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/CarControlActivity.java | Ventana de órdenes y «Localizar». | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/MateFeed.java | Leapmotor Mate a través de Home Assistant (CarCloud.car, acquire/release). | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/DemoMode.java | Datos inventados de la demostración y de las capturas. | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/ChargeCheckJob.java | JobScheduler cada 15 min en el móvil: carga en directo y keepAlive del token (78-79). En la unidad no sirve con el coche apagado; ver el riesgo de caducidad. | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/ChargeWatchService.java | Servicio de carga en directo del móvil. | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/ChargeWatch.java | Lógica de la carga en directo. | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/LastCharge.java | Km desde la última carga («Mi coche»). | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/WidgetUpdater.java | Widget del móvil. | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/CarSensors.java | Sensores del móvil; CarCloud.driving() (378-380) lo usa. En la unidad: true o la velocidad GPS de DiAuto. | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/Redact.java | Dependencia de L (tapa CarUUID e IP en el log). | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/QdTrace.kt | Dependencia de L (log unificado). | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/CarTrace.java | Dependencia de L (diario del coche). | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/LowLatency.java | Dependencia de L (hilo escritor). | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/LiveLog.java | Dependencia de L (log en vivo). | no hace falta
- qd/hql/app/src/main/java/com/headqlink/link/LogRetention.java | Dependencia de L (retención de ficheros de log). | no hace falta
- qd/hql/app/src/main/res/layout/hql_activity_cloud.xml | Layout de la pantalla de cuenta. Quitar Batería (265-334) y PIN/órdenes (336-431); cambiar estilos HQL.*, colores hql_*, hql_panel y tema por los de DiAuto (o copiarlos); limitar el ancho para 1920x882; importantForAutofill=no en la contraseña (177-178). | adaptar
- qd/hql/app/src/main/res/values/hql_cloud_strings.xml | Textos en inglés. Copiar el subconjunto de unas 75 claves (sección 8 del informe); reescribir intro, wipe_confirm y los cuerpos que dicen «en el móvil». | adaptar
- qd/hql/app/src/main/res/values-es/hql_cloud_strings.xml | Textos en español (pantalla 94-163, panel 6-16 y 69-85). Mismo subconjunto y mismas reescrituras. | adaptar
- qd/hql/app/src/main/res/values/hql_strings.xml | Solo hql_save y hql_cancel: cambiarlos por save y cancel de DiAuto (strings.xml:428, 431). | sustituir por stub
- qd/hql/app/src/main/res/values/hql_styles.xml | Estilos HQL.Button, HQL.Button.Quiet, HQL.Button.Outline y HQL.Text.* (115-177) que usa el layout. | adaptar
- qd/hql/app/src/main/res/values/hql_colors.xml | Colores hql_text, hql_text_faint, hql_accent, hql_warn y hql_error (17-32). | adaptar
- qd/hql/app/src/main/res/drawable/hql_panel.xml | Fondo de las tarjetas de la pantalla de cuenta. | copiar tal cual
- qd/hql/app/src/main/AndroidManifest.xml | Solo la declaración de CarCloudActivity (666-672), con exported=false, adjustResize|stateHidden y el tema AppTheme de DiAuto. INTERNET ya está en DiAuto (AndroidManifest.xml:62). | adaptar
- qd/hql/app/src/test/java/com/headqlink/link/TestCerts.kt | Genera certificados y claves durante las pruebas (sin ficheros de muestra). | copiar tal cual
- qd/hql/app/src/test/java/com/headqlink/link/LeapTlsTest.kt | Pruebas de importación del certificado y del TLS. | copiar tal cual
- qd/hql/app/src/test/java/com/headqlink/link/CertPickTest.kt | Pruebas del medio par .crt/.key. | copiar tal cual
- qd/hql/app/src/test/java/com/headqlink/link/LeapStatusTest.kt | Pruebas del parser de señales. | copiar tal cual
- qd/hql/app/src/test/java/com/headqlink/link/LeapCryptoTest.kt | Pruebas de firma, deviceId, token y encodeComponent; quitar las de V2 y PIN. | adaptar
- qd/hql/app/src/test/java/com/headqlink/link/LeapApiTest.kt | Pruebas de login, coches, estado, refresco y parse con transporte falso; quitar órdenes, viajes, consumo, energía y km (269-496) y adaptar onlyReadOnlyPaths (237). | adaptar
- qd/hql/app/src/test/java/com/headqlink/link/CarCloudTest.kt | Pruebas de Policy, edad del dato, almacén cifrado, renovación del token, enmascarado del correo y socStale; quitar perfiles e historial (204-271). | adaptar