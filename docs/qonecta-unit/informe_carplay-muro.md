Base de las rutas citadas: `qd/ref/` (las abrevio como `ref/...`).

# El muro de CarPlay: por qué un receptor honesto no es viable hoy

## Resumen

DiPlay y xcertplay son la **misma base de código** (DiPlay es un fork de xcertplay; `ref/diplay/docs/THIRD_PARTY_NOTICES.md:5`). Implementan un receptor de CarPlay para head units Android, y su código del protocolo es correcto y abierto (GPL-3.0). Pero **ninguno de los dos contiene una identidad MFi de Apple**, y sin ella el iPhone rechaza la conexión. El muro no es de software: es que CarPlay obliga al accesorio a demostrar que tiene licencia MFi de Apple, cosa que solo se consigue con **hardware MFi real** (un coprocesador vendido únicamente a licenciatarios) o con un **par certificado+clave privada MFi** que no se puede obtener ni distribuir legítimamente. DiPlay "funciona" en sus releases porque empaqueta una identidad **extraída del firmware de un Carlinkit** — justo la práctica sucia que no vamos a copiar.

## 1. Cómo autentica el handshake de CarPlay

El punto exacto del muro está en `ref/xcertplay/shared/src/main/java/com/shilapi/xcertplay/airplay/MfiSapAuthSetup.kt:10-19`. Es el responder de `/auth-setup` (MFiSAP), y su propio comentario lo dice sin rodeos: *"Proves MFi licensing to the phone"*. El accesorio debe devolver al iPhone tres cosas: su clave pública efímera, **su certificado MFi** y **una firma del coprocesador** sobre ambas claves públicas, cifrada con AES-128-CTR (`MfiSapAuthSetup.kt:36-50`). Si no entrega certificado y firma MFi válidos, el iPhone corta.

Esa firma la produce un `MfiAuthenticator`, interfaz con tres implementaciones (`ref/xcertplay/shared/src/main/java/com/shilapi/xcertplay/mfi/MfiAuthenticationClient.kt:21-36`), y **las tres dependen de material que el proyecto no tiene**:

- **Chip físico (I2C)** — `mfi/MfiAuthenticationClient.kt:38-120`. Habla por I2C con un coprocesador de autenticación de Apple siguiendo "la secuencia I2C de LIVI". `signChallenge()` escribe el reto en registros del chip (0x20/0x21), lanza el comando AUTH (0x10) y lee la firma (0x11/0x12); el certificado se lee de 0x30/0x31 (`:222-236`). **La clave privada nunca sale del chip**: la grabó Apple de fábrica. El software solo hace de intermediario.
- **Ficheros locales** — `mfi/LocalMfiAuthenticationClient.kt:15-23`. El usuario aporta un certificado DER PKCS#7 (`.p7b`) y su clave privada DER PKCS#8 sin cifrar (`.pk8`); el software firma el reto con esa clave en el móvil (`:49-70`). Esos ficheros solo existen legítimamente si salen de un chip MFi o del programa MFi.
- **Servicio remoto** — `mfi/RemoteMfiAuthenticationClient.kt:13-14`. Delega la firma a un servidor HTTP (`/mfi/certificate`, `/mfi/sign`; `:277-280`). Solo traslada el problema a otra máquina que sí posee la identidad.

## 2. El muro: sin hardware/identidad MFi no hay CarPlay por software puro

xcertplay es explícito en que hace falta **hardware**: su README se describe como receptor *"connecting to the MFi chip through a CH341 I2C bridge or directly through the board's I2C controller"* (`ref/xcertplay/README.md:5`), y entre los requisitos exige *"A physical USB Host/OTG Android device and MFi hardware ... for hardware validation"* (`README.md:102-111`). El paso 5 del uso es literalmente *"Connect your MFi chip"* (`README.md:33`).

Qué es el "chip MFi a bordo o CH341":
- El **CH341** es un chip puente USB→I2C/SPI/UART del fabricante WCH. Aquí se usa como adaptador USB para hablar por I2C con el coprocesador MFi. El filtro USB lo identifica por VID 0x1A86 / PID 0x5512 (`ref/xcertplay/common/src/main/res/xml/usb_device_filter.xml:3-4`), y `ref/xcertplay/shared/src/main/java/com/shilapi/xcertplay/transport/Ch341DeviceMatcher.kt:6-10` aclara que *"deployed CH341 hardware must be identified on the target unit first"*: es hardware externo que hay que enchufar. El README remite incluso a una placa adaptadora aparte, `ch341-to-mfi-chip` (`README.md:60`).
- La alternativa "a bordo" es el bus I2C nativo del SoC (`/dev/i2c-N`), vía `transport/LinuxI2cTransport.kt`. Requiere un coprocesador MFi soldado a la placa y acceso root/sistema al bus.

En cualquier caso: **el certificado y la firma los produce un coprocesador MFi físico con licencia de Apple**. No hay forma por software puro.

Dónde se demuestra que la identidad NO está en el software, y de dónde sale en DiPlay:
- El árbol de fuentes de ambos repos **no contiene ninguna clave ni certificado** (búsqueda de `.pk8/.p7b/.der/.p12/.pem/...`: sin resultados en los dos).
- `ref/diplay/docs/BUILD.md:79-81`: *"The source APK contains no accessory identity unless you supply runtime authentication assets. Standalone CarPlay needs these assets to connect to an iPhone."* Para conectar hay que aportar `offline-mfi/identity.pk8` + `certificate.p7b` en un directorio externo (`BUILD.md:109-138`), y el APK resultante los lleva dentro, extraíbles: *"Recipients can extract that identity from the APK"* (`BUILD.md:163-164`).
- De dónde sale esa identidad en las releases públicas: `ref/diplay/docs/THIRD_PARTY_NOTICES.md:43-45` — *"an accessory certificate/key pair **recovered from public Carlinkit C2Air Allwinner V821 firmware** ... These data are **not newly generated Apple-issued credentials** for DiPlay and are not relicensed as project source code."* El README lo repite: *"not an Apple-certified product ... bundles an experimental accessory identity recovered from public Carlinkit firmware ... A bundled private key is extractable"* (`ref/diplay/README.md:25`).

Es decir: DiPlay solo "funciona" en público porque **suplanta la identidad MFi de un Carlinkit**. Eso es lo mismo que hace LeapLink con la identidad MFi del Carlinkit para CarPlay, y es la línea que el usuario ya marcó que no vamos a cruzar.

## 3. Qué haría falta EXACTAMENTE para CarPlay legítimo

1. **Entrar en el MFi Program de Apple** (el antiguo "Made for iPhone"). Es un programa para fabricantes: requiere ser empresa aceptada, NDA, y aprobación de producto. CarPlay, además, exige una **aprobación específica de Apple para cada producto CarPlay**, que Apple concede a fabricantes de coche/head unit homologados, no a apps sueltas.
2. **Comprar el coprocesador de autenticación de Apple** (Apple Authentication Coprocessor, p. ej. 2.0C/3.0) a través del programa, e integrarlo por I2C en cada unidad. El coprocesador solo se vende a licenciatarios MFi.
3. Integrar el firmware/stack CarPlay bajo las condiciones (NDA) de Apple.

Por qué no es realista para un proyecto de comunidad:
- Apple **no licencia MFi a individuos ni a proyectos de software libre**; el coprocesador y la documentación CarPlay están bajo NDA.
- Aunque consiguiéramos el chip, la vía "trae tu propio coprocesador MFi" obliga a **cada usuario** a comprar y conectar hardware que un particular no adquiere fácilmente, y a tener acceso al I2C/USB host de su C10.
- Bajo GPL-3.0 la identidad **no es relicenciable como fuente** (lo dice el propio DiPlay), por eso ha de mantenerse fuera del repositorio; un proyecto honesto no puede distribuirla.

## 4. Licencias y riesgo de la identidad MFi

- **Licencias de software**: xcertplay es **GPL-3.0** (`ref/xcertplay/LICENSE`). DiPlay es **GPL-3.0** en el receptor (`ref/diplay/LICENSE`) y además su UI adapta DiAuto, marcada **AGPL-3.0** (`ref/diplay/docs/THIRD_PARTY_NOTICES.md:11`). Un fork heredaría copyleft fuerte (GPL/AGPL): obliga a publicar el código. Es **compatible con Qonecta+**, que ya es AGPL, pero no permite cerrar nada.
- **Marca**: "CarPlay" y su icono son de Apple; el icono se baja del developer site de Apple y **queda fuera de la licencia de código** (`THIRD_PARTY_NOTICES.md:13-19`). Los repos ya incluyen descargos de no-afiliación.
- **El riesgo grave es la identidad MFi, no la licencia de software**:
  - Empaquetar la clave privada/certificado MFi extraídos de un Carlinkit es **apropiación de material licenciado por Apple**, **suplantación de otro fabricante** y probable **elusión de medidas tecnológicas** (anticircumvention). Es la misma práctica de LeapLink.
  - Apple puede **revocar ese certificado** en cualquier actualización de iOS y dejar el CarPlay muerto sin aviso (los propios documentos de DiPlay reconocen que la aceptación futura es incierta: `README.md:25`, `SECURITY.md:7`).

## 5. Lo que estos repos SÍ hacen de forma honesta (contraste con LeapLink)

Relevante para nuestro criterio, aunque no borra el muro:
- **No piden `WRITE_SECURE_SETTINGS`** (sin coincidencias en ninguno de los dos). El manifest solo pide permisos de **solo lectura** de Automotive (`CAR_INFO`, `CAR_SPEED`, `CAR_POWERTRAIN`, `CAR_EXTERIOR_ENVIRONMENT`), USB y BOOT (`ref/xcertplay/common/src/main/AndroidManifest.xml:4-9`).
- **No se disfrazan de otra app.** Los applicationId son reales: `com.shilapi.xcertplay` y `com.shihab.diplay` (`ref/diplay/docs/BUILD.md:76-77`). La única mención a Tencent/QQ es `ref/diplay/common/src/main/java/com/shilapi/xcertplay/HomeScreenMonitor.kt:123` (`com.tencent.autolauncher`), que es un launcher **a vigilar**, no un nombre de paquete suplantado.
- **No escriben la lista blanca privada del coche.** `ref/diplay/shared/src/main/java/com/shilapi/xcertplay/hud/BydBootStartRepair.kt:10-11` dice explícitamente que *"Nothing here changes BYD's private autostart manager"*; solo usa `cmd deviceidle whitelist` (`:36-38`), que es la exención estándar de doze vía ADB opcional.

El único punto turbio de DiPlay es exactamente el del apartado 2: la identidad de Carlinkit empaquetada en las releases.

## 6. Qué significa para el C10

El muro MFi es **independiente del coche**: lo impone el iPhone, no el head unit. Daría igual que fuera BYD, Geely o el Leapmotor C10. Sobre el C10 se sumaría, además, el muro de instalación en la unidad (otro problema distinto, de otro agente). Conclusión para la decisión del usuario: para **Android Auto** sí tiene sentido un fork honesto; para **CarPlay**, hoy no hay vía honesta por software — o metemos hardware MFi con licencia (irreal para comunidad) o copiamos la suplantación de Carlinkit (descartado). Lo honesto es documentar el muro y no ofrecer CarPlay.