# Instalador Portátil Qonecta Unit `0.1.15-unit` (Leapmotor C10)

Esta carpeta contiene todo lo necesario para llevar en un pendrive o copiar a tu portátil Windows mañana, sin necesidad de instalar programas externos.

## Contenido de la carpeta

1. **`QonectaUnit_0.1.15-unit.apk`** (`46.7 MB`, `versionCode = 16`, paquete `com.tencent.qqmusiccar`): El APK final del coche listo para instalar.
2. **`com.tencent.qqmusiccar_0.1.15-unit.apk`**: Copia exacta del mismo APK con el nombre del paquete para copiar directamente a un pendrive USB.
3. **`INICIAR_PORTAL_C10.bat`**: Lanzador con doble clic que abre los puertos `80` y `8788` en el Firewall de Windows e inicia el servidor web local.
4. **`servidor_local_c10.ps1`**: Servidor HTTP y de Portal Cautivo local escrito en PowerShell nativo de Windows.

---

## Opción A — Instalar desde el Pendrive USB usando la pantalla «Archivos» del coche (Recomendado y más rápido)

Hoy comprobaste que el **Botón de abrir Archivos (`<input type="file">`)** de nuestra página sí consigue abrir el selector nativo **Archivos del coche** (`com.android.documentsui`).

El motivo por el que al tocar el archivo pareció que no pasaba nada es que en ese selector de Android:
- Si tocas el **centro del icono** del APK, Android simplemente selecciona el archivo para devolvérselo a Vivaldi (y Vivaldi lo ignora).
- En cambio, cada archivo APK dentro de **Archivos** muestra un **pequeño icono de vista previa en una esquina (`⤢` dos flechas en diagonal / lupa de previsualizar)**. Al tocar **ese icono pequeño (`⤢`)** sobre el APK guardado en tu pendrive USB, `Archivos` lanza la acción `ACTION_VIEW` directamente hacia el **Instalador de paquetes del sistema** sin pasar por Vivaldi.

### Pasos:
1. Copia `QonectaUnit_0.1.15-unit.apk` a la raíz de un **pendrive USB** (FAT32 / exFAT) y enchúfalo al puerto USB del coche.
2. Abre nuestra página local (desde tu móvil con `http://10.78.40.218:8788/portal` o desde el portátil con `http://192.168.137.1/portal`).
3. Pulsa el botón amarillo **`2. ABRIR ARCHIVOS DEL COCHE (INSTALAR DESDE USB)`**.
4. En la columna izquierda de **Archivos**, entra en tu **Unidad USB**.
5. Busca `QonectaUnit_0.1.15-unit.apk` y **pulsa el icono pequeño de vista previa (`⤢`) en la esquina del archivo** (no el centro). Se abrirá el instalador del sistema.

---

## Opción B — Usar el Portátil como Punto de Acceso Wi-Fi + Portal Cautivo Local

En tu inventario del coche (`3_INVENTARIO_PAQUETES_Y_MITM_C10.txt`), el componente del sistema **`com.android.captiveportallogin`** (*CaptivePortalLogin*) sí tiene el permiso `REQUEST_INSTALL_PACKAGES=SI`.

1. Copia esta carpeta al portátil Windows.
2. En Windows 10/11, abre **Configuración → Red e Internet → Zona con cobertura inalámbrica móvil** (*Mobile Hotspot*) y actívala. Windows asignará automáticamente al portátil la IP **`192.168.137.1`**.
3. Haz doble clic en **`INICIAR_PORTAL_C10.bat`** y acepta el permiso de Administrador.
4. Conecta el Wi-Fi del Leapmotor C10 a la red Wi-Fi del portátil:
   - Si el coche abre automáticamente la ventana de inicio de sesión en la red (*CaptivePortalLogin*), pulsa el botón verde **`1. DESCARGAR E INSTALAR QONECTA UNIT (.APK)`**.
   - Si abres en el navegador `http://192.168.137.1/portal` o `http://192.168.137.1:8788/portal`, verás en la consola negra del portátil cada petición en tiempo real y podrás pulsar el botón **`2. ABRIR ARCHIVOS DEL COCHE`** para lanzar el instalador desde el USB.
