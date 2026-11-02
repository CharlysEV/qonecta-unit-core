## Resumen

Analicé en estático `leapcarplay`, `diauto`, `diplay`, `xcertplay` y `overtakelink`. Conclusiones de partida, antes del detalle:

- **"Para Android" (recibir Android Auto en la unidad del C10) es viable y honesto**: la base es `diauto` (com.andrerinas.openheadunit, misma raíz que Qonecta+), AGPL, sin trucos. El único muro es instalar/arrancar la APK en el C10.
- **"Para iPhone" (recibir CarPlay en la unidad) choca con un muro infranqueable de forma 100% software honesta**: CarPlay exige un coprocesador de autenticación MFi de Apple. La única vía honesta es un **chip MFi físico** (adaptador CH341/I2C). LeapCarPlay y DiPlay saltan ese muro con una identidad MFi **extraída de firmware de Carlinkit** (lo que NO vamos a copiar).
- **El C10 sí deja instalar y arrancar una app de nombre propio**: hay un caso real (DiPlay, `com.shihab.diplay`) en un C10 europeo con Android 12. La instalación honesta (orígenes desconocidos / pen / navegador / ADB) es la vía; nadie en estos repos suplanta QQ Music.

---

## 1. LeapCarPlay: qué cambia respecto a DiPlay (para Leapmotor/8155)

`leapcarplay` (signxer) **no es un fork grande de DiPlay, sino un fork recortado de `xcertplay`** (el motor CarPlay base, GPL-3.0) con la UI de DiAuto (AGPL-3.0). Tiene ~400 ficheros frente a los ~1.400 de DiPlay: elimina casi todo el soporte multi-marca de DiPlay (Geely, Ecarx, Harman, variantes de cluster DiLink) y se queda con lo justo para Leapmotor. Cambios concretos:

- **Identidad de app propia (honesta).** `applicationId = com.signxer.leapcarplay` (móvil) y `com.signxer.leapcarplay.automotive` (`mobile/build.gradle.kts:17`, `automotive/build.gradle.kts:13`). El `namespace = com.shilapi.xcertplay` (`mobile/build.gradle.kts:11`) es solo el espacio de nombres de clases heredado del upstream; **no** es la identidad del paquete instalado. Nada de disfraz.
- **Segunda pantalla por HDMI2 (el cambio estrella para Leapmotor).** `PresentationDisplaySelector.kt:10-15` elige la presentation display cuyo nombre contiene "HDMI2"/"HDMI 2"; si solo hay una, usa esa; si hay varias sin nombre, deja el cluster desactivado. `CarPlaySecondaryDisplay.kt:14-19` es un `Presentation` a pantalla completa que recibe el stream de cluster de CarPlay. Es salida-only, sin táctil (`docs/COMPATIBILITY.md:17`).
- **Etiqueta OEM "Leapmotor".** `AirPlayPersistence.kt:73` fija `DEFAULT_OEM_LABEL = "Leapmotor"` (nombre que CarPlay muestra como "coche").
- **60 fps por defecto** para dual-screen, con 30 fps de respaldo (`CHANGELOG.md:4-6`, `docs/INSTALL.md:23`).
- **8155 NO verificado.** "8155" aparece **solo como plataforma objetivo sin verificar** (`docs/COMPATIBILITY.md:7,29`, `docs/CODE_REVIEW.md:25,29`). Las pruebas físicas reales fueron en un coche BYD DiLink5.1, no en un Leapmotor. No hay código específico de 8155; es genérico Android 9+/10+ (Wi-Fi Direct). La Wi-Fi usa el mismo motor que xcertplay/DiPlay (P2P, hotspot del coche, LAN), sin nada "Leapmotor" salvo la selección de HDMI2.

En resumen: lo que hace falta tocar para un head unit Leapmotor según este repo es **(a) detectar y enrutar la segunda pantalla "HDMI2", (b) la etiqueta OEM y (c) ajustar fps/resolución**. El resto es herencia de xcertplay/DiPlay.

## 2. El muro CarPlay/MFi (lo más importante para "el fork para iPhone")

CarPlay como receptor exige autenticación MFi de Apple. En el código:

- `CarPlayRuntimeConfig.kt:13-18` define `MfiTarget = { LOCAL, USB_CH341, I2C, REMOTE }`, y en `:40-42` avisa expresamente: *"There are deliberately no built-in Apple or CH341 product IDs: the physical devices attached to the target must be identified first."*
- `LocalMfiAuthenticationClient.kt:17` deja claro el fondo del asunto: *"A valid signature here proves key consistency, not that an iPhone trusts the identity."* Es decir, firmar con una clave cualquiera no sirve: el iPhone solo confía en una identidad firmada por la CA de Apple, que únicamente tiene **un chip MFi real** o una **identidad extraída de un Carlinkit**.
- `LeapCarPlayBootstrap.kt:10,24,38-39`: LeapCarPlay copia `offline-mfi/identity.pk8` + `certificate.p7b` desde los assets y fija `MfiTarget.LOCAL` ("Installs the private beta's experimental identity. It has no remote fallback"). **Esos assets no están en el repo** y, sin ellos, el bootstrap falla y no hay CarPlay.
- Dónde salen esos assets lo dicen los propios README: `leapcarplay/README.md:29` y `diplay/README.md:25` — *"The APK bundles an experimental accessory identity recovered from public Carlinkit firmware… A bundled private key is extractable."* El empaquetado local los inyecta vía `LEAPCARPLAY_AUTH_ASSETS_DIR` (`docs/BUILD.md:15,23`).

**Conclusión dura**: para un fork honesto "para iPhone" NO podemos usar esa identidad de Carlinkit. La única vía honesta es `MfiTarget.USB_CH341` o `I2C`, es decir, **un chip de autenticación MFi físico** (hardware con licencia Apple, conectado por USB con adaptador CH341 o por I2C). Sin ese hardware, no hay CarPlay honesto. Esto hay que decírselo claro al usuario: el "para iPhone" no es simétrico con el "para Android".

## 3. Instalación honesta en el C10 (y contraste con LeapLink)

Ninguno de los repos abiertos suplanta el nombre de paquete ni toca ajustes seguros del coche:

- **Vía honesta documentada.** `leapcarplay/docs/INSTALL.md:3,5,31`: *"install the APK using the method supported by the head unit"*, *"your head unit must permit APK installation"*, *"APK installation restrictions are controlled by your car's firmware. ADB is optional if your car supports it, not an app runtime requirement."* DiPlay dice lo mismo (`diplay/README.md:15`: *"Your head unit must permit APK installation"*).
- **Guion de instalación por ADB (plantilla reutilizable).** `diauto/scripts/install.sh`: `adb connect IP:5555` → `adb install -r` (paquete propio `com.andrerinas.headunitrevived`, línea 7) → `pm grant` de permisos runtime → `appops SYSTEM_ALERT_WINDOW allow` → `settings put secure location_mode 3` → `dumpsys deviceidle whitelist +PKG` → `am start`. Importante: `settings put secure` y `deviceidle whitelist` los ejecuta **el shell de ADB** (que sí tiene ese privilegio), **no la app**: la app no pide WRITE_SECURE_SETTINGS.
- **Permisos de la app, mínimos y honestos.** `leapcarplay/common/src/main/AndroidManifest.xml` solo pide RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE*, POST_NOTIFICATIONS. DiAuto pide algo más (overlay, Bluetooth, Shizuku opcional) pero **ninguno pide `WRITE_SECURE_SETTINGS`** (grep en todo `ref` solo lo encuentra en `openautolink/docs/multi-display-projection-plan.md:114`, y ahí marcado como vía "system app… **Not viable**").
- **El `com.tencent.qqmusiccar...` de DiAuto NO es un disfraz.** `diauto/app/src/main/AndroidManifest.xml:268` y `CarKeyReceiver.kt:41,66` registran la **acción de intent** `com.tencent.qqmusiccar.action.MEDIA_BUTTON_INNER_ONKEY` para **escuchar** las teclas del volante en head units chinos donde ese broadcast existe. Es un `intent-filter` de recepción, no el `applicationId`. Es totalmente distinto del truco de LeapLink (que cambia SU propio `applicationId` a `com.tencent.qqmusiccar` para colarse en la lista blanca). Hay que tener cuidado de no confundir las dos cosas.

**Contraste con LeapLink** (del análisis previo en memoria): LeapLink (1) se renombra `com.tencent.qqmusiccar`, (2) usa identidad MFi de Carlinkit, (3) pide `WRITE_SECURE_SETTINGS` y **escribe la lista blanca del coche**, (4) envía VIN+logs a su servidor y tiene un gate por VIN. Nuestro fork honesto: **paquete propio** + el usuario habilita instalación en SU coche + sin escribir ajustes seguros ni lista blanca + sin telemetría.

**Respuesta directa a tu pregunta (Vivaldi / pen):** sí, no hace falta descargar por el navegador del coche. El APK se puede llevar **en un pen** y abrirlo con el gestor de archivos del C10, o pasarlo por ADB. En ambos casos el C10 pedirá permitir "instalar apps de orígenes desconocidos" para ese origen (navegador, gestor de archivos o pen); eso lo habilita el dueño en SU coche. Lo que no podemos dar por hecho en estático es que el firmware europeo del C10 deje activar esa opción sin más (ver sección 5 y preguntas abiertas).

## 4. OvertakeLink: confirma el enfoque de Qonecta+

`overtakelink` (queryParams, AGPL-3.0) **es exactamente el mismo enfoque que Qonecta+/HeadQLink**, no la vía de este fork:

- `README.md:5,11`: *"Android Auto on stock QDLink head units — nothing to install on the head unit"*. Corre en el móvil.
- Pipeline idéntico (`README.md:121,130`): `Android Auto (móvil) → AAP localhost → OvertakeLink → QDLink por USB → head unit stock`. Usa el receptor de Open Headunit (misma raíz AGPL que Qonecta+) y el "AA head unit server" (dev mode de Android Auto, `README.md:35`).
- Usa el mismo `WhitelistAppOn` del protocolo QDLink que HeadQLink (`link/LinkSession.java:639-640`), que es la señal "app apta para conducir", no una lista blanca de instalación.

**Qué aporta de nuevo** (por si interesa para Qonecta+, no para este fork): está orientado a **Geely** y maneja **dos dialectos de QDLink** — el `!BIN` clásico (512 B) y el **nuevo `5A5A` + JSON** (Geely Okavango, experimental) — y un **modo "direct"** que reenvía el H.264 de AA sin recodificar (menos latencia/batería, `README.md:134-143`). El QDLink inalámbrico no está implementado (`README.md:170`). Para nuestro C10 ya cubrimos esto con Qonecta+.

## 5. Lista blanca del C10 europeo

En estático no hay documentación de una "lista blanca de instalación" del C10, pero sí evidencia indirecta muy útil:

- **Evidencia de que el C10 instala y arranca apps de nombre propio.** `diplay/docs/ISSUE-AUDIT-2026-10-03.md:117` (issue #119): un usuario con **Leapmotor C10, Android 12, iOS 16.6** instaló DiPlay (`com.shihab.diplay`, paquete propio) y lo arrancó; se le **desconecta al minuto** por USB, hotspot y P2P. O sea: la instalación y el arranque de una app no listada **sí fueron posibles** en ese C10; el problema fue de estabilidad de conexión (encaja con el corte de USB del C10 que ya tienes documentado).
- `headqlink/README.md:57` confirma la vía honesta genérica: *"Android will ask to allow installation from the browser or file manager"*.
- Las tres "whitelist" que aparecen en los repos **no son** la lista blanca de instalación del coche: (a) `deviceidle whitelist` = exención de **doze/batería** por ADB (`diplay/.../BydBootStartRepair.kt:10-11,36-38`, con comentario explícito de que **no** tocan el autostart privado del fabricante); (b) `package_white_list` = campo del **protocolo Android Auto** (Service Discovery); (c) `WhitelistAppOn` = señal **QDLink** de "app apta en marcha".

Es decir: no hay prueba en los repos de que el C10 bloquee la **instalación** o **ejecución** de apps no listadas; sí hay prueba de un C10 que las ejecutó. La "lista blanca del coche" que LeapLink escribe parece ser la de **apps permitidas en marcha/en el launcher**, no la de instalación. Esto hay que confirmarlo en el coche del usuario (preguntas abiertas).

## 6. Recomendación para nuestro fork

- **Haz el fork "para Android" (receptor AA en la unidad) sobre `diauto`**: AGPL, misma raíz que Qonecta+, instalación honesta, sin muro de identidad. Es el camino realista.
- **El fork "para iPhone" (CarPlay) déjalo aparcado o condiciónalo a hardware MFi real** (chip CH341/I2C vía `xcertplay`). Documenta el muro; no uses la identidad de Carlinkit.
- **Reutiliza** la selección de HDMI2 y el `Presentation` de leapcarplay, y el `install.sh` de DiAuto como plantilla.
- **Mantén AGPL**, paquete propio, cero telemetría, cero `WRITE_SECURE_SETTINGS`, cero escritura de lista blanca.