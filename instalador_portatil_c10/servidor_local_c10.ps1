# =============================================================================
# QONECTA UNIT 0.1.15-unit - SERVIDOR WEB LOCAL PARA PORTATIL (WINDOWS)
# =============================================================================
# Sirve el paquete QonectaUnit_0.1.15-unit.apk en la red local / Zona con
# cobertura inalambrica movil de Windows (192.168.137.1) en los puertos 80 y 8788.
# =============================================================================

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ApkPath = Join-Path $ScriptDir "QonectaUnit_0.1.15-unit.apk"

if (-not (Test-Path $ApkPath)) {
    $AltApk = Join-Path $ScriptDir "..\apk\QonectaUnit_0.1.15-unit.apk"
    if (Test-Path $AltApk) {
        Copy-Item $AltApk $ApkPath -Force
    }
}

Clear-Host
Write-Host "=====================================================================" -ForegroundColor Cyan
Write-Host "   QONECTA UNIT 0.1.15-unit - SERVIDOR LOCAL WIFI (PORTATIL)         " -ForegroundColor Cyan
Write-Host "=====================================================================" -ForegroundColor Cyan

if (-not (Test-Path $ApkPath)) {
    Write-Host "[ERROR] No se encuentra QonectaUnit_0.1.15-unit.apk en $ScriptDir" -ForegroundColor Red
    Read-Host "Pulsa ENTER para salir"
    exit 1
}

$ApkInfo = Get-Item $ApkPath
$ApkSizeMB = [Math]::Round($ApkInfo.Length / 1MB, 2)
Write-Host "[OK] APK preparado: $($ApkInfo.Name) ($ApkSizeMB MB)" -ForegroundColor Green

$LocalIPs = @()
try {
    $LocalIPs = Get-NetIPAddress -AddressFamily IPv4 | Where-Object {
        $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*"
    } | Select-Object -ExpandProperty IPAddress
} catch {
    $LocalIPs = @("192.168.137.1")
}

$PrimaryIP = "192.168.137.1"
if ($LocalIPs -contains "192.168.137.1") {
    $PrimaryIP = "192.168.137.1"
} elseif ($LocalIPs.Count -gt 0) {
    $PrimaryIP = $LocalIPs[0]
}

Write-Host ""
Write-Host "Direcciones IP locales detectadas:" -ForegroundColor Yellow
foreach ($ip in $LocalIPs) {
    Write-Host "  -> http://$ip/portal" -ForegroundColor Green
    Write-Host "  -> http://${ip}:8788/portal" -ForegroundColor Green
}
Write-Host "=====================================================================" -ForegroundColor Cyan
Write-Host ""

function Build-PortalHtml([string]$HostHeader) {
    return @"
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Qonecta Unit 0.1.15-unit - Instalador Local</title>
<style>
  body { background:#060D17; color:#E6F4F1; font-family:system-ui,sans-serif; padding:24px; margin:0; text-align:center; }
  .card { max-width:760px; margin:12px auto; background:#0F1E2E; border:2px solid #00E676; border-radius:20px; padding:26px; box-shadow:0 12px 32px rgba(0,0,0,0.5); }
  h1 { color:#00E676; font-size:28px; margin:0 0 8px 0; }
  p { color:#A7C4BC; font-size:17px; margin:0 0 18px 0; line-height:1.45; }
  .btn { display:block; width:100%; box-sizing:border-box; padding:20px; margin:14px 0; border-radius:14px; font-size:21px; font-weight:800; text-decoration:none; cursor:pointer; border:none; }
  .btn-primary { background:#00E676; color:#04120B; }
  .btn-usb { background:#FFD54F; color:#1F1600; }
  .btn-alt { background:#152C40; color:#80CBC4; border:2px solid #00BFA5; font-size:18px; padding:16px; }
  .help { text-align:left; background:#08131F; border:1px solid #1E3A52; border-radius:14px; padding:16px 20px; margin-top:18px; font-size:16px; color:#B0BEC5; line-height:1.55; }
  .help b { color:#00E676; }
  .usb-tip { color:#FFD54F; font-weight:bold; }
</style>
</head>
<body>
  <div class="card">
    <h1>Qonecta Unit 0.1.15-unit</h1>
    <p>Servidor Local ($HostHeader) &middot; Paquete listo ($ApkSizeMB MB)</p>

    <a class="btn btn-primary" href="/QonectaUnit_0.1.15-unit.apk" download="QonectaUnit_0.1.15-unit.apk">
      1. DESCARGAR E INSTALAR QONECTA UNIT (.APK)
    </a>

    <label class="btn btn-usb" for="usbPicker">
      2. ABRIR ARCHIVOS DEL COCHE (INSTALAR DESDE USB)
    </label>
    <input id="usbPicker" type="file" accept="*/*" style="display:none">

    <a class="btn btn-alt" href="/com.tencent.qqmusiccar_0.1.15-unit.apk">
      3. ENLACE DIRECTO ALTERNATIVO (com.tencent.qqmusiccar.apk)
    </a>

    <div class="help">
      <b>Como instalar desde el Pendrive USB (Boton 2):</b><br>
      1. Copia <code>QonectaUnit_0.1.15-unit.apk</code> en un pendrive USB y conectalo al coche.<br>
      2. Pulsa el boton amarillo <b>2. ABRIR ARCHIVOS DEL COCHE</b>.<br>
      3. En la ventana de Archivos, selecciona tu unidad USB a la izquierda.<br>
      4. <span class="usb-tip">IMPORTANTE:</span> Toca el <b>icono pequeno de vista previa (dos flechas en diagonal &#x2922; en la esquina del icono del APK)</b> para abrir el instalador de paquetes del sistema.
    </div>
  </div>
</body>
</html>
"@
}

$Listeners = @()
foreach ($Port in @(80, 8788)) {
    try {
        $Listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $Port)
        $Listener.Start()
        $Listeners += $Listener
        Write-Host "[ACTIVO] Escuchando peticiones HTTP en el puerto $Port" -ForegroundColor Green
    } catch {
        Write-Host "[AVISO] No se pudo abrir el puerto $Port ($($_.Exception.Message))" -ForegroundColor Yellow
    }
}

if ($Listeners.Count -eq 0) {
    Write-Host "[ERROR] No se pudo abrir ningun puerto. Ejecuta INICIAR_PORTAL_C10.bat como Administrador." -ForegroundColor Red
    Read-Host "Pulsa ENTER para salir"
    exit 1
}

Write-Host ""
Write-Host "Esperando conexiones en la red local... (Pulsa Ctrl+C para detener)" -ForegroundColor Cyan

try {
    while ($true) {
        $Handled = $false
        foreach ($Listener in $Listeners) {
            if ($Listener.Pending()) {
                $Handled = $true
                $Client = $Listener.AcceptTcpClient()
                try {
                    $ClientEndPoint = $Client.Client.RemoteEndPoint.ToString()
                    $Stream = $Client.GetStream()
                    $Stream.ReadTimeout = 5000
                    $Stream.WriteTimeout = 60000

                    $Reader = New-Object System.IO.StreamReader($Stream, [System.Text.Encoding]::ASCII, $false, 1024, $true)
                    $RequestLine = $Reader.ReadLine()
                    if ([string]::IsNullOrEmpty($RequestLine)) {
                        $Client.Close()
                        continue
                    }

                    $HostHeader = $PrimaryIP
                    while ($true) {
                        $HeaderLine = $Reader.ReadLine()
                        if ([string]::IsNullOrEmpty($HeaderLine)) { break }
                        if ($HeaderLine -match "^Host:\s*(.+)$") {
                            $HostHeader = $Matches[1].Trim()
                        }
                    }

                    $Parts = $RequestLine.Split(" ")
                    $Method = if ($Parts.Length -ge 1) { $Parts[0] } else { "GET" }
                    $RawPath = if ($Parts.Length -ge 2) { $Parts[1] } else { "/" }
                    $PathOnly = ($RawPath -split "\?")[0]

                    $Timestamp = (Get-Date).ToString("HH:mm:ss")
                    Write-Host "[$Timestamp] Cliente ($ClientEndPoint) -> $Method $RawPath (Host: $HostHeader)" -ForegroundColor White

                    if ($PathOnly -like "*.apk" -or $PathOnly -eq "/download") {
                        $FileLen = $ApkInfo.Length
                        Write-Host "  -> Enviando QonectaUnit_0.1.15-unit.apk ($ApkSizeMB MB)..." -ForegroundColor Green
                        $Headers = "HTTP/1.1 200 OK`r`n" +
                                   "Content-Type: application/vnd.android.package-archive`r`n" +
                                   "Content-Disposition: attachment; filename=`"QonectaUnit_0.1.15-unit.apk`"`r`n" +
                                   "Content-Length: $FileLen`r`n" +
                                   "Cache-Control: no-store`r`n" +
                                   "Connection: close`r`n`r`n"
                        $HeaderBytes = [System.Text.Encoding]::ASCII.GetBytes($Headers)
                        $Stream.Write($HeaderBytes, 0, $HeaderBytes.Length)

                        if ($Method -ne "HEAD") {
                            $FileStream = [System.IO.File]::OpenRead($ApkPath)
                            try {
                                $Buffer = New-Object byte[] 65536
                                $TotalSent = 0L
                                $LastPercent = -10
                                while (($Read = $FileStream.Read($Buffer, 0, $Buffer.Length)) -gt 0) {
                                    $Stream.Write($Buffer, 0, $Read)
                                    $TotalSent += $Read
                                    $Percent = [int](($TotalSent * 100) / $FileLen)
                                    if ($Percent -ge ($LastPercent + 20)) {
                                        $LastPercent = $Percent
                                        Write-Host "     Progreso descarga: $Percent%" -ForegroundColor DarkGreen
                                    }
                                }
                                $Stream.Flush()
                                Write-Host "  -> [COMPLETADO] APK enviado integramente ($TotalSent bytes)." -ForegroundColor Green
                            } finally {
                                $FileStream.Close()
                            }
                        }
                    }
                    else {
                        $Html = Build-PortalHtml -HostHeader $HostHeader
                        $HtmlBytes = [System.Text.Encoding]::UTF8.GetBytes($Html)
                        $Headers = "HTTP/1.1 200 OK`r`n" +
                                   "Content-Type: text/html; charset=utf-8`r`n" +
                                   "Content-Length: $($HtmlBytes.Length)`r`n" +
                                   "Cache-Control: no-store`r`n" +
                                   "Connection: close`r`n`r`n"
                        $HeaderBytes = [System.Text.Encoding]::ASCII.GetBytes($Headers)
                        $Stream.Write($HeaderBytes, 0, $HeaderBytes.Length)
                        $Stream.Write($HtmlBytes, 0, $HtmlBytes.Length)
                        $Stream.Flush()
                    }
                } catch {
                    Write-Host "  [AVISO] Conexion cerrada por el cliente: $($_.Exception.Message)" -ForegroundColor DarkYellow
                } finally {
                    $Client.Close()
                }
            }
        }
        if (-not $Handled) {
            Start-Sleep -Milliseconds 60
        }
    }
} finally {
    foreach ($Listener in $Listeners) {
        try { $Listener.Stop() } catch {}
    }
}
