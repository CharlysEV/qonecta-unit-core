@echo off
set ADB=D:\Android\sdk\platform-tools\adb.exe
echo Lanzando emulador CarPlay en Qonecta Unit C10...
"D:\Android\sdk\platform-tools\adb.exe" -s emulator-5554 shell am start -n com.tencent.qqmusiccar/com.shilapi.xcertplay.CarPlayHostActivity --ez simulate_iphone true
"C:\Users\calva\AppData\Local\Microsoft\WinGet\Packages\Genymobile.scrcpy_Microsoft.Winget.Source_8wekyb3d8bbwe\scrcpy-win64-v4.1\scrcpy.exe" -s emulator-5554 --window-title "Qonecta Unit C10 - Simulador CarPlay"
