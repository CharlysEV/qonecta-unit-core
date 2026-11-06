package com.andrerinas.openheadunit.connection

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.andrerinas.openheadunit.aap.Utils
import com.andrerinas.openheadunit.utils.AppLog

class UsbAccessoryMode(private val usbMgr: UsbManager) {

    fun connectAndSwitch(device: UsbDevice, useLibusb: Boolean = false): Boolean {
        val connection: UsbDeviceConnection?
        try {
            connection = usbMgr.openDevice(device)                 // Open device for connection
        } catch (e: Throwable) {
            AppLog.e(e)
            return false
        }

        if (connection == null) {
            AppLog.e("Cannot open device")
            return false
        }

        val result = if (useLibusb) {
            AppLog.i("UsbAccessoryMode: Performing AOA switch via native libusb...")
            val native = UsbNative()
            if (native.wrap(connection, 0, 0)) {
                val switchResult = native.accModeSwitch() == 0
                native.close()
                switchResult
            } else {
                AppLog.e("UsbAccessoryMode: Failed to wrap device for native AOA switch")
                native.close()
                false
            }
        } else {
            switch(connection)
        }
        connection.close()

        AppLog.i("Result: $result")
        return result
    }

    private fun switch(connection: UsbDeviceConnection): Boolean {
        // Do accessory negotiation and attempt to switch to accessory mode. Called only by usb_connect()
        val buffer = ByteArray(2)
        var len = connection.controlTransfer(UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR, ACC_REQ_GET_PROTOCOL, 0, 0, buffer, 2, USB_TIMEOUT_IN_MS)
        if (len != 2) {
            AppLog.e("Error controlTransfer len: $len")
            return false
        }
        val acc_ver = Utils.getAccVersion(buffer)
        // Get OAP / ACC protocol version
        AppLog.i("Success controlTransfer len: $len  acc_ver: $acc_ver")
        if (acc_ver < 1) {
            // If error or version too low...
            AppLog.e("No support acc")
            return false
        }
        AppLog.i("acc_ver: $acc_ver")

        // Send all accessory identification strings. Abort if any transfer fails — a partial
        // identification (e.g. manufacturer sent but model missing) can cause the phone to
        // ignore the ACC_REQ_START or fail to switch into accessory mode.
        if (!initStringControlTransfer(connection, ACC_IDX_MAN, MANUFACTURER) ||
            !initStringControlTransfer(connection, ACC_IDX_MOD, MODEL) ||
            !initStringControlTransfer(connection, ACC_IDX_DES, DESCRIPTION) ||
            !initStringControlTransfer(connection, ACC_IDX_VER, VERSION) ||
            !initStringControlTransfer(connection, ACC_IDX_URI, URI) ||
            !initStringControlTransfer(connection, ACC_IDX_SER, SERIAL)) {
            return false
        }

        AppLog.i("Sending acc start")
        // Send accessory start request. Device should re-enumerate as an accessory.
        len = connection.controlTransfer(UsbConstants.USB_TYPE_VENDOR, ACC_REQ_START, 0, 0, byteArrayOf(), 0, USB_TIMEOUT_IN_MS)

        // len == 0: clean ACK before re-enumeration (expected path).
        // len < 0: phone disconnected before the ACK because it started re-enumerating
        //          immediately upon receipt — the command was still received. Treat as success.
        AppLog.i("Acc start sent (len=$len). Waiting for re-enumeration...")
        try { Thread.sleep(500) } catch (e: Exception) {}
        return true
    }

    /**
     * Sends ACC_REQ_START (53) to a device that is already in AOA accessory mode
     * (`0x18D1:0x2D00`/`0x2D01`) and waits up to 3s for it to re-enumerate.
     */
    fun restartAccessory(device: UsbDevice): Boolean {
        val deviceName = device.deviceName
        val connection = try {
            usbMgr.openDevice(device)
        } catch (e: Throwable) {
            AppLog.e("restartAccessory: openDevice failed for $deviceName: ${e.message}")
            return false
        }
        if (connection == null) {
            AppLog.w("restartAccessory: cannot open $deviceName")
            return false
        }
        val len = try {
            connection.controlTransfer(
                UsbConstants.USB_TYPE_VENDOR,
                ACC_REQ_START,
                0,
                0,
                null,
                0,
                USB_TIMEOUT_IN_MS
            )
        } finally {
            connection.close()
        }
        AppLog.i("restartAccessory: ACC_REQ_START on $deviceName returned $len; waiting for re-enumeration...")
        return waitForReEnumeration(deviceName, 3000L)
    }

    /**
     * Issues a bus-level `USBDEVFS_RESET` ioctl on the device's file descriptor to clear a
     * wedged AOA session on the Leapmotor C10 when `ACC_REQ_START` alone does not re-enumerate.
     */
    fun resetUsbPort(device: UsbDevice): Boolean {
        val deviceName = device.deviceName
        val connection = try {
            usbMgr.openDevice(device)
        } catch (e: Throwable) {
            AppLog.e("resetUsbPort: openDevice failed for $deviceName: ${e.message}")
            return false
        }
        if (connection == null) {
            AppLog.w("resetUsbPort: cannot open $deviceName")
            return false
        }
        val rc = try {
            UsbNative.resetDeviceFd(connection.fileDescriptor)
        } finally {
            connection.close()
        }
        if (rc != 0) {
            AppLog.w("resetUsbPort: USBDEVFS_RESET on $deviceName failed (rc=$rc)")
            return false
        }
        val gone = waitForReEnumeration(deviceName, 3000L)
        AppLog.i("resetUsbPort: USBDEVFS_RESET on $deviceName ok (re-enumerated=$gone)")
        return true
    }

    private fun waitForReEnumeration(oldDeviceName: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!usbMgr.deviceList.containsKey(oldDeviceName)) {
                return true
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun initStringControlTransfer(conn: UsbDeviceConnection, index: Int, string: String): Boolean {
        val len = conn.controlTransfer(UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, index, string.toByteArray(), string.length, USB_TIMEOUT_IN_MS)
        return if (len < 0) {
            // Negative means the USB transfer itself failed (e.g. device disconnected or
            // timed out). Abort the switch — ACC_REQ_START would be pointless.
            AppLog.e("Error controlTransfer len: $len  index: $index  string: \"$string\"")
            false
        } else {
            // len == string.length is the ideal ACK. Some phones return 0 for a successful
            // OUT control transfer (they accept the data but report 0 bytes in the data
            // stage). Treat any non-negative return as success; log a warning if unexpected.
            if (len != string.length) {
                AppLog.w("Unexpected controlTransfer len: $len (expected ${string.length})  index: $index  string: \"$string\"")
            } else {
                AppLog.i("Success controlTransfer len: $len  index: $index  string: \"$string\"")
            }
            true
        }
    }

    companion object {
        private const val USB_TIMEOUT_IN_MS = 1000
        private const val MANUFACTURER = "Android"
        private const val MODEL = "Android Auto"
        private const val DESCRIPTION = "Android Auto"//"Android Open Automotive Protocol"
        private const val VERSION = "2.0.1"
        private const val URI = "https://developer.android.com/auto/index.html"
        private const val SERIAL = "HU-AAAAAA001"

        // Indexes for strings sent by the host via ACC_REQ_SEND_STRING:
        private const val ACC_IDX_MAN = 0
        private const val ACC_IDX_MOD = 1
        private const val ACC_IDX_DES = 2
        private const val ACC_IDX_VER = 3
        private const val ACC_IDX_URI = 4
        private const val ACC_IDX_SER = 5

        // OAP Control requests:
        private const val ACC_REQ_GET_PROTOCOL = 51
        private const val ACC_REQ_SEND_STRING = 52
        private const val ACC_REQ_START = 53
    }
}
