@echo off
REM ---------------------------------------------------------------------------
REM  Qonecta Unit - Abre el emulador del coche (AVD "coche", 2560x1440) en TU
REM  escritorio, instala el ultimo APK compilado y arranca la app.
REM  Doble clic sobre este archivo.
REM ---------------------------------------------------------------------------
setlocal EnableDelayedExpansion
set SDK=D:\Android\Sdk
set ADB=%SDK%\platform-tools\adb.exe
set EMU=%SDK%\emulator\emulator.exe
set AVD=coche
set APK=d:\unit\apk\com.tencent.qqmusiccar_0.1.4-unit.apk
set PKG=com.tencent.qqmusiccar
set ACT=com.andrerinas.openheadunit.main.MainActivity
if "%ANDROID_AVD_HOME%"=="" set ANDROID_AVD_HOME=D:\Android\.android\avd
if "%ANDROID_USER_HOME%"=="" set ANDROID_USER_HOME=D:\Android\.android

echo.
echo  === Qonecta Unit - Emulador Leapmotor C10 ===
echo.

REM 1. Si hay un emulador abierto (aunque sea en segundo plano), lo cerramos
tasklist /FI "IMAGENAME eq qemu-system-x86_64.exe" | find /I "qemu-system" >nul
if %errorlevel%==0 (
    echo  Cerrando el emulador que ya estaba abierto...
    "%ADB%" -s emulator-5554 emu kill >nul 2>&1
)

REM 2. Esperamos a que se cierre del todo (puede tardar hasta ~40 s)
set /a N=0
:esperar_cierre
tasklist /FI "IMAGENAME eq qemu-system-x86_64.exe" | find /I "qemu-system" >nul
if %errorlevel%==0 (
    set /a N+=1
    if !N! GEQ 30 (
        echo  El emulador anterior no se cierra; lo cierro a la fuerza...
        taskkill /F /IM qemu-system-x86_64.exe >nul 2>&1
        taskkill /F /IM emulator.exe >nul 2>&1
        timeout /t 3 /nobreak >nul
    ) else (
        timeout /t 2 /nobreak >nul
        goto esperar_cierre
    )
)

REM 3. Abrimos el emulador con ventana
echo  Abriendo el emulador "%AVD%"...
start "Emulador C10" "%EMU%" -avd %AVD% -gpu host -no-snapshot-save

REM 4. Esperamos a que aparezca y arranque Android (1-2 minutos)
echo  Esperando a que arranque Android (puede tardar 1-2 minutos)...
set /a N=0
:esperar_arranque
timeout /t 3 /nobreak >nul
set /a N+=1
tasklist /FI "IMAGENAME eq qemu-system-x86_64.exe" | find /I "qemu-system" >nul
if not %errorlevel%==0 (
    if !N! GEQ 8 goto fallo_emulador
    goto esperar_arranque
)
set BOOT=
for /f "delims=" %%b in ('"%ADB%" -s emulator-5554 shell getprop sys.boot_completed 2^>nul') do set BOOT=%%b
if not "!BOOT!"=="1" (
    if !N! GEQ 100 goto fallo_arranque
    goto esperar_arranque
)

REM 5. Instalamos y abrimos la app
if exist "%APK%" (
    echo  Instalando la ultima version de Qonecta Unit...
    "%ADB%" -s emulator-5554 install -r "%APK%"
) else (
    echo  [Aviso] No encuentro %APK% - se abre la version ya instalada.
)
echo  Abriendo Qonecta Unit...
"%ADB%" -s emulator-5554 shell am start -n %PKG%/%ACT%
echo.
echo  Listo. Ya puedes manejar la app con el raton en la ventana del emulador.
echo  (Esta ventana se cerrara sola.)
timeout /t 8 >nul
goto fin

:fallo_emulador
echo.
echo  [ERROR] El emulador no ha llegado a abrirse.
echo  Prueba a abrir "coche" desde Android Studio ^> Device Manager ^> Play.
pause
goto fin

:fallo_arranque
echo.
echo  [ERROR] El emulador se abrio pero Android no termino de arrancar en 5 minutos.
pause
goto fin

:fin
endlocal
