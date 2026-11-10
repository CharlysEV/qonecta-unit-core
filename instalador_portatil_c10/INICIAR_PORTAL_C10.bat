@echo off
chcp 65001 >nul
title Qonecta Unit 0.1.15-unit - Servidor Local Portatil C10

net session >nul 2>&1
if %errorLevel% neq 0 (
    echo Solicitando permisos de Administrador para abrir los puertos 80 y 8788...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

cd /d "%~dp0"

netsh advfirewall firewall delete rule name="QonectaUnit_Local_HTTP" >nul 2>&1
netsh advfirewall firewall add rule name="QonectaUnit_Local_HTTP" dir=in action=allow protocol=TCP localport=80,8788 >nul 2>&1

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0servidor_local_c10.ps1"
pause
