package com.headqlink.unit.carplay

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * Direct USB CarPlay NCM / iAP2 network bridge (`android.net.VpnService`).
 *
 * Mirrors LeapLink's `CarPlayVpnService` declaration so when an iPhone is connected via
 * direct USB cable (USB NCM / link-local subnet), `Qonecta Unit` can route CarPlay TCP/UDP
 * traffic cleanly without conflicting with the head unit's cellular/Wi-Fi default route.
 */
class CarPlayVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val addr = intent?.getStringExtra(EXTRA_ADDRESS) ?: DEFAULT_LOCAL_ADDR
                val prefix = intent?.getIntExtra(EXTRA_PREFIX_LENGTH, DEFAULT_PREFIX_LEN) ?: DEFAULT_PREFIX_LEN
                val route = intent?.getStringExtra(EXTRA_ROUTE) ?: DEFAULT_SUBNET_ROUTE
                startTunnel(addr, prefix, route)
                return START_STICKY
            }
        }
    }

    private fun startTunnel(localAddress: String, prefixLength: Int, subnetRoute: String) {
        if (vpnInterface != null) return
        try {
            val builder = Builder()
                .setSession("Qonecta Unit CarPlay USB Bridge")
                .setMtu(1500)
                .addAddress(localAddress, prefixLength)
                .addRoute(subnetRoute, prefixLength)
            runCatching { builder.addAllowedApplication(packageName) }
            vpnInterface = builder.establish()
            Log.i(TAG, "CarPlay USB VPN bridge interface established ($localAddress/$prefixLength)")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not establish CarPlay VPN interface (permission not granted yet or unsupported): ${t.message}")
        }
    }

    private fun stopTunnel() {
        try {
            vpnInterface?.close()
        } catch (_: Throwable) {
        } finally {
            vpnInterface = null
        }
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CarPlayVpnService"
        const val ACTION_START = "com.headqlink.unit.carplay.action.START_VPN"
        const val ACTION_STOP = "com.headqlink.unit.carplay.action.STOP_VPN"
        const val EXTRA_ADDRESS = "extra_address"
        const val EXTRA_PREFIX_LENGTH = "extra_prefix_length"
        const val EXTRA_ROUTE = "extra_route"

        private const val DEFAULT_LOCAL_ADDR = "169.254.210.1"
        private const val DEFAULT_SUBNET_ROUTE = "169.254.0.0"
        private const val DEFAULT_PREFIX_LEN = 16

        fun requestStop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, CarPlayVpnService::class.java).apply {
                        action = ACTION_STOP
                    }
                )
            }
        }
    }
}
