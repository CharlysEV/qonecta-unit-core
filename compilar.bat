@echo off
rem Qonecta Unit: compila desde cualquier carpeta (esta, una copia, un pen o un clon de git).
rem   compilar.bat           pruebas de Qonecta Unit + APK para el coche (tipo «coche»: no depurable)
rem   compilar.bat rapido    solo el APK, sin pruebas
rem El APK sale en la carpeta apk\ de aquí al lado. Se firma con firma\debug.keystore si está (la misma clave
rem que los APK ya instalados, para que el coche deje actualizar encima); esa carpeta no va a git.
rem Herramientas de este PC: si se mueven, cambia estas dos líneas.
setlocal
chcp 65001 >nul
set "JDK=D:\Android\jdk"
set "SDK=D:\Android\sdk"
set "RESULTADO=0"
rem pushd también entra en una carpeta de red (\\servidor\...), donde cd /d falla y se quedaría en otra carpeta.
pushd "%~dp0" || (
  echo No puedo entrar en la carpeta de compilar.bat.
  set "RESULTADO=1"
  goto fin
)
rem Antes de borrar cachés o escribir local.properties: que esto sea de verdad la carpeta de Qonecta Unit.
if not exist "gradlew.bat" (
  echo No encuentro gradlew.bat junto a compilar.bat: no toco nada.
  set "RESULTADO=1"
  goto fin
)

if not exist "%JDK%\bin\java.exe" (
  echo No encuentro el JDK en %JDK%. Cambia la línea JDK de compilar.bat.
  set "RESULTADO=1"
  goto fin
)
if not exist "%SDK%\platforms" (
  echo No encuentro el SDK de Android en %SDK%. Cambia la línea SDK de compilar.bat.
  set "RESULTADO=1"
  goto fin
)
set "JAVA_HOME=%JDK%"
set "ANDROID_HOME=%SDK%"
set "ANDROID_SDK_ROOT=%SDK%"

rem local.properties es de cada PC: se reescribe siempre con el SDK de arriba.
set "SDKF=%SDK:\=/%"
> local.properties echo sdk.dir=%SDKF%

rem Si la carpeta se ha copiado o movido, las cachés guardan la ruta vieja (CMake falla): se borran.
set "RUTA="
if exist ".gradle\ruta-compilacion.txt" set /p RUTA=<".gradle\ruta-compilacion.txt"
if /i not "%RUTA%"=="%CD%" (
  echo Carpeta nueva: borro las cachés de compilación de la ruta anterior.
  for %%d in (.gradle build app\build app\.cxx contract\build) do if exist "%%d" rmdir /s /q "%%d" 2>nul
)
if not exist ".gradle" mkdir ".gradle"
> ".gradle\ruta-compilacion.txt" echo %CD%

if exist "firma\debug.keystore" (
  echo Firma: firma\debug.keystore
) else (
  echo Firma: la clave de depuración de este PC. Si no es la de los APK instalados, el coche no dejará actualizar encima.
)

set "TAREAS=:app:testGithubDebugUnitTest --tests com.headqlink.unit.* :app:assembleGithubCoche"
if /i "%~1"=="rapido" set "TAREAS=:app:assembleGithubCoche"
call "%~dp0gradlew.bat" %TAREAS% --console=plain
if errorlevel 1 (
  echo.
  echo ERROR: la compilación ha fallado.
  set "RESULTADO=1"
  goto fin
)

if not exist apk mkdir apk
for %%f in ("app\build\outputs\apk\github\coche\*.apk") do call :copia "%%f"

:fin
popd 2>nul
rem Con doble clic, espera a una tecla para que se pueda leer el resultado.
echo %cmdcmdline% | findstr /i /c:"%~nx0" >nul && pause
exit /b %RESULTADO%

rem Fuera del bloque del for: una «)» en la ruta (%CD%, «unit - copia (2)») no rompe nada.
:copia
copy /y "%~1" "apk\" >nul
echo.
echo APK: %CD%\apk\%~nx1
exit /b 0
