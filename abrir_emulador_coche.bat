@echo off
title Qonecta Unit - Emulador Android Automotive (coche)
echo Iniciando emulador Android Automotive 'coche' en tu escritorio...
start "" "D:\Android\Sdk\emulator\emulator.exe" -avd coche -gpu host
echo Esperando a que el emulador conecte por ADB...
"D:\Android\Sdk\platform-tools\adb.exe" wait-for-device
:wait_boot
for /f "tokens=*" %%a in ('"D:\Android\Sdk\platform-tools\adb.exe" shell getprop sys.boot_completed 2^>nul') do set BOOT=%%a
if not "%BOOT%"=="1" (
    timeout /t 2 /nobreak >nul
    goto wait_boot
)
echo Instalando Qonecta Unit...
"D:\Android\Sdk\platform-tools\adb.exe" install -r -g "d:\unit\app\build\outputs\apk\github\coche\QonectaUnit_0.1.6-unit.apk"
echo Abriendo Qonecta Unit...
"D:\Android\Sdk\platform-tools\adb.exe" shell am start -n "com.tencent.qqmusiccar/com.andrerinas.openheadunit.main.MainActivity"
echo Listo!
pause
