# El muro de CarPlay (iPhone)

**Resumen en una línea:** para **Android Auto** sí hay un fork honesto posible; para **CarPlay (iPhone)** no hay forma limpia por software hoy. El muro no lo pone el coche: lo pone el iPhone.

## 1. Por qué CarPlay no se puede hacer limpio por software

Android Auto no exige que el accesorio demuestre nada especial: por eso un receptor honesto es viable. **CarPlay sí lo exige.** Cuando un iPhone se conecta a un accesorio CarPlay, el accesorio tiene que **demostrar que tiene licencia MFi de Apple** antes de que el iPhone mande nada.

El punto exacto está en `ref/xcertplay/.../airplay/MfiSapAuthSetup.kt:10-19`, el responder de `/auth-setup` (MFiSAP). Su propio comentario lo dice sin rodeos: *"Proves MFi licensing to the phone"* (verificado en `:11,13`). El accesorio debe devolver al iPhone tres cosas: su clave pública efímera, **su certificado MFi** y **una firma del coprocesador** sobre ambas claves, cifradas. **Si no entrega certificado y firma MFi válidos, el iPhone corta la conexión.** No hay rodeo por software: o tienes la identidad, o no entras.

## 2. Qué es la identidad MFi

**MFi = "Made for iPhone"**, el programa de licencias de Apple para fabricantes de accesorios. La "identidad MFi" es:

- un **certificado** emitido por la autoridad de certificación (CA) **de Apple**, más
- una **clave privada** que normalmente va grabada de fábrica dentro de un **coprocesador de autenticación de Apple** (un chip). Esa clave privada **nunca sale del chip**: el chip firma los retos del iPhone y el software solo hace de intermediario.

Lo clave: **firmar con una clave cualquiera no sirve.** El iPhone solo confía en una identidad firmada por Apple. El propio código lo avisa: `ref/xcertplay/.../mfi/LocalMfiAuthenticationClient.kt:17` dice que *"una firma válida aquí prueba consistencia de clave, no que un iPhone confíe en la identidad"*.

xcertplay ofrece tres vías de autenticación (`mfi/MfiAuthenticationClient.kt`) y **las tres necesitan material externo que el proyecto no tiene**:
1. **Chip físico por I2C** (`MfiAuthenticationClient.kt:38-120`): habla con el coprocesador de Apple; la clave vive en el chip.
2. **Ficheros locales** (`LocalMfiAuthenticationClient.kt`): el usuario aporta certificado `.p7b` + clave privada `.pk8`; solo existen legítimamente si salieron de un chip o del programa MFi.
3. **Servicio remoto** (`RemoteMfiAuthenticationClient.kt`): delega la firma a otra máquina que sí tiene la identidad. Solo traslada el problema.

## 3. Cómo "funcionan" DiPlay / LeapCarPlay / LeapLink (y por qué nosotros no)

El código fuente de esos repos es **limpio**: no contiene ninguna clave ni certificado (sin `.pk8/.p7b/.der` en el árbol). Entonces, ¿cómo conectan sus *releases*? Porque **empaquetan una identidad MFi "recuperada del firmware público de un Carlinkit C2Air (Allwinner V821)"** y la inyectan en tiempo de compilación desde un directorio externo. Lo dicen ellos mismos:

- `ref/diplay/docs/THIRD_PARTY_NOTICES.md:43-45`: es *"un par certificado/clave recuperado de firmware público de Carlinkit... NO son credenciales nuevas emitidas por Apple"*.
- `ref/diplay/README.md:25` y `ref/leapcarplay/README.md:29`: *"el APK empaqueta una identidad de accesorio experimental recuperada de firmware público de Carlinkit... la clave privada es extraíble"*.
- `ref/diplay/docs/BUILD.md:163-164`: *"los receptores pueden extraer esa identidad del APK"*.

Es decir: **suplantan la identidad MFi de otro fabricante (Carlinkit).** Es exactamente lo que hace LeapLink. **Esa línea no la cruzamos:** no extraemos ni redistribuimos identidad MFi de Apple ni material de firmware de Carlinkit.

## 4. xcertplay es honesto: dice que hace falta hardware

xcertplay no se esconde: exige **hardware MFi real**. Su README pide *"un dispositivo Android con USB Host/OTG y hardware MFi"* (`ref/xcertplay/README.md:102-111`) y, en el paso 5 de uso, literalmente *"Connect your MFi chip"* (`README.md:33`). El chip se conecta de dos formas:

- Por un **puente USB→I2C CH341** (chip del fabricante WCH; VID `0x1A86` / PID `0x5512`, `ref/xcertplay/.../usb_device_filter.xml:3-4`), con una placa adaptadora aparte `ch341-to-mfi-chip`. Es hardware que hay que **enchufar**.
- O por el **I2C nativo del SoC** (`/dev/i2c-N`), con un coprocesador MFi **soldado** a la placa y acceso root/sistema al bus.

En ambos casos, el certificado y la firma los produce **un coprocesador MFi físico con licencia de Apple**. No hay forma por software puro.

## 5. Qué haría falta para un CarPlay legítimo (y por qué no es realista)

1. **Entrar en el MFi Program de Apple**: ser empresa aceptada, firmar NDA y pasar aprobación de producto. CarPlay, además, exige **aprobación específica de Apple por cada producto CarPlay**, que concede a fabricantes de coche/head unit homologados, **no a apps sueltas**.
2. **Comprar el coprocesador de autenticación de Apple** (solo se vende a licenciatarios MFi) e **integrarlo por I2C en cada unidad**.
3. Integrar el stack CarPlay bajo las condiciones (NDA) de Apple.

Por qué no es realista para la comunidad:
- Apple **no licencia MFi a particulares ni a proyectos de software libre**; el coprocesador y la documentación de CarPlay están bajo NDA.
- Aunque consiguiéramos chips, la vía "trae tu propio chip MFi" obligaría a **cada usuario** a comprar y conectar hardware que un particular no adquiere fácilmente, y a tener acceso al I2C/USB host de su C10.
- Bajo GPL-3.0, esa identidad **no es relicenciable como fuente**; un proyecto honesto no puede distribuirla.
- Apple puede **revocar el certificado** en cualquier actualización de iOS y dejar el CarPlay muerto sin aviso (los propios docs de DiPlay admiten que la aceptación futura es incierta).

Además hay riesgo legal en la vía sucia: distribuir la clave/certificado extraídos de un Carlinkit es apropiación de material licenciado por Apple, suplantación de otro fabricante y probable elusión de medidas tecnológicas. Y la marca "CarPlay" y su icono son de Apple (quedan fuera de la licencia de código).

## 6. Dónde está el muro (independiente del coche)

El muro MFi **lo impone el iPhone, no el head unit**: daría igual que el coche fuera BYD, Geely o el Leapmotor C10. Sobre el C10 se sumaría, aparte, el muro de instalación en la unidad (eso es otro tema, común con Android Auto).

## 7. Conclusión honesta

- **Android (Android Auto): SÍ.** Fork honesto viable sobre DiAuto (AGPL, misma raíz que Qonecta+), sin identidad MFi, sin disfraz de paquete, sin escribir la lista blanca. Es el camino realista (ver el plan de fork Android).
- **iPhone (CarPlay): NO, no de forma limpia hoy.** O metemos hardware MFi con licencia de Apple (irreal para una comunidad) o copiamos la suplantación de identidad de Carlinkit (**descartado**). Lo honesto es **documentar el muro y no ofrecer CarPlay**.
- Si algún día se abordara, **solo** por la vía de un **chip MFi físico real** (`MfiTarget.USB_CH341` / `I2C` de xcertplay), que cada usuario conectaría a su coche, y **nunca** con identidad extraída de firmware. Realista: no para la comunidad.