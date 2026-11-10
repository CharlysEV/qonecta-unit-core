package com.headqlink.unit.companion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.headqlink.unit.bridge.BridgeConstants
import com.headqlink.unit.bridge.LineChannel
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Abre la conexión con Qonecta Unit (el coche), por Wi-Fi o por Bluetooth.
 *
 * - **Wi-Fi**: con Android Auto inalámbrico el móvil está en la red que crea el coche; el coche es la puerta de
 *   enlace de esa red. Se prueba la puerta de enlace de cada red Wi-Fi (atando el socket a esa red, porque no tiene
 *   Internet y Android mandaría el tráfico por datos móviles).
 * - **Bluetooth**: RFCOMM al coche emparejado. Funciona también con Android Auto por cable USB.
 */
class CarLink(private val context: Context) {

    companion object {
        @Volatile var lastWifiAddr: InetAddress? = null
        @Volatile var lastWifiNetwork: android.net.Network? = null
    }

    data class Opened(val channel: LineChannel, val transport: String)

    fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Intenta Wi-Fi primero (rápido si no está el coche) y luego Bluetooth. */
    fun open(carBtAddress: String?): Opened? {
        openWifi()?.let { return it }
        if (!carBtAddress.isNullOrBlank()) openBluetooth(carBtAddress)?.let { return it }
        return null
    }

    @Suppress("DEPRECATION")
    fun openWifi(): Opened? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val lp = cm.getLinkProperties(network) ?: continue
            val isP2pOrHotspotSubnet = lp.linkAddresses.any {
                val h = it.address.hostAddress.orEmpty()
                h.startsWith("192.168.49.") || h.startsWith("192.168.43.")
            }
            val hasValidatedInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            // Si es una Wi-Fi doméstica con Internet normal (y no subred de Android Auto P2P/Hotspot), no bloqueamos Bluetooth
            if (hasValidatedInternet && !isP2pOrHotspotSubnet) continue

            val candidates = LinkedHashSet<InetAddress>()
            if (Build.VERSION.SDK_INT >= 30) lp.dhcpServerAddress?.let { candidates.add(it) }
            lp.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway }
                .filter { it is Inet4Address && !it.isAnyLocalAddress }
                .forEach { candidates.add(it) }
            // Grupo Wi-Fi Direct: el coche (dueño del grupo) siempre es 192.168.49.1
            if (lp.linkAddresses.any { it.address.hostAddress?.startsWith("192.168.49.") == true }) {
                candidates.add(InetAddress.getByName("192.168.49.1"))
            }
            for (addr in candidates) {
                val socket = runCatching { network.socketFactory.createSocket() }.getOrNull() ?: continue
                try {
                    socket.connect(InetSocketAddress(addr, BridgeConstants.TCP_PORT), 500)
                    socket.soTimeout = BridgeConstants.READ_TIMEOUT_MS
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    lastWifiAddr = addr
                    lastWifiNetwork = network
                    return Opened(socketChannel(socket), "Wi-Fi")
                } catch (_: Exception) {
                    runCatching { socket.close() }
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun openBluetooth(address: String): Opened? {
        if (!hasBluetoothPermission()) return null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!adapter.isEnabled) return null
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return null
        var socket: BluetoothSocket? = null
        return try {
            runCatching { adapter.cancelDiscovery() }
            socket = device.createRfcommSocketToServiceRecord(BridgeConstants.RFCOMM_UUID)
            socket.connect()
            val s = socket
            Opened(LineChannel(s.inputStream, s.outputStream) { runCatching { s.close() } }, "Bluetooth")
        } catch (_: Exception) {
            runCatching { socket?.close() }
            null
        }
    }

    /**
     * Abre un socket TCP binario directo hacia el puerto OTA del coche (`tcpPort`),
     * probando primero la red Wi-Fi activa con el coche y luego clientes ARP del Hotspot.
     */
    @Suppress("DEPRECATION")
    fun openOtaTcpSocket(tcpPort: Int): Socket? {
        if (tcpPort <= 0) return null
        // 1. Si ya hay red Wi-Fi activa con el coche (Wi-Fi Direct 192.168.49.1 o puerta de enlace)
        val cachedAddr = lastWifiAddr
        val cachedNet = lastWifiNetwork
        if (cachedAddr != null) {
            val s = runCatching {
                val sock = cachedNet?.socketFactory?.createSocket() ?: Socket()
                sock.connect(InetSocketAddress(cachedAddr, tcpPort), 900)
                sock.soTimeout = 45_000
                sock.tcpNoDelay = true
                sock
            }.getOrNull()
            if (s != null) return s
        }

        // 2. Escanear todas las redes Wi-Fi activas
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val lp = cm.getLinkProperties(network) ?: continue
                val candidates = LinkedHashSet<InetAddress>()
                if (Build.VERSION.SDK_INT >= 30) lp.dhcpServerAddress?.let { candidates.add(it) }
                lp.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway }
                    .filter { it is Inet4Address && !it.isAnyLocalAddress }
                    .forEach { candidates.add(it) }
                if (lp.linkAddresses.any { it.address.hostAddress?.startsWith("192.168.49.") == true }) {
                    runCatching { candidates.add(InetAddress.getByName("192.168.49.1")) }
                }
                for (addr in candidates) {
                    val sock = runCatching {
                        val s = network.socketFactory.createSocket()
                        s.connect(InetSocketAddress(addr, tcpPort), 700)
                        s.soTimeout = 45_000
                        s.tcpNoDelay = true
                        s
                    }.getOrNull()
                    if (sock != null) return sock
                }
            }
        }

        // 3. Si el móvil hace de Hotspot, probar IPs vecinas en tabla ARP (/proc/net/arp)
        val arpIps = runCatching {
            java.io.File("/proc/net/arp").takeIf { it.canRead() }?.readLines()?.drop(1)
                ?.mapNotNull { line -> line.trim().split(Regex("\\s+")).firstOrNull() }
                ?.filter { it.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) && !it.startsWith("127.") }
                .orEmpty()
        }.getOrDefault(emptyList())
        for (ip in arpIps) {
            val sock = runCatching {
                val s = Socket()
                s.connect(InetSocketAddress(ip, tcpPort), 700)
                s.soTimeout = 45_000
                s.tcpNoDelay = true
                s
            }.getOrNull()
            if (sock != null) return sock
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun openOtaBluetoothSocket(address: String?): BluetoothSocket? {
        if (address.isNullOrBlank() || !hasBluetoothPermission()) return null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!adapter.isEnabled) return null
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return null
        var socket: BluetoothSocket? = null
        return try {
            runCatching { adapter.cancelDiscovery() }
            socket = device.createRfcommSocketToServiceRecord(BridgeConstants.APK_PUSH_RFCOMM_UUID)
            socket.connect()
            socket
        } catch (_: Exception) {
            runCatching { socket?.close() }
            null
        }
    }

    private fun socketChannel(s: Socket) =
        LineChannel(s.getInputStream(), s.getOutputStream()) { runCatching { s.close() } }
}
