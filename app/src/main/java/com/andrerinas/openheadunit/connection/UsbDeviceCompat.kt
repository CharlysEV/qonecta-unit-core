package com.andrerinas.openheadunit.connection

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.os.Build
import com.andrerinas.openheadunit.aap.Utils
import java.util.Locale

class UsbDeviceCompat(val wrappedDevice: UsbDevice) {

    val deviceName: String
        get() = wrappedDevice.deviceName

    val uniqueName: String
        get() = getUniqueName(wrappedDevice)

    override fun toString(): String {
        return String.format(Locale.US, "%s - %s", uniqueName, wrappedDevice.toString())
    }

    val isInAccessoryMode: Boolean
        get() = isInAccessoryMode(wrappedDevice)

    companion object {
        private const val USB_VID_GOO = 0x18D1   // 6353   Nexus or ACC mode, see PID to distinguish
        private const val USB_PID_ACC = 0x2D00      // Accessory                  100
        private const val USB_PID_ACC_ADB = 0x2D01      // Accessory + ADB            110
        const val APPLE_VID = 0x05AC

        /**
         * Internal USB peripherals on the Leapmotor C10 (`msmnile`) that must never be
         * treated as a connected phone or switched to AOA mode.
         */
        private val IGNORED_C10_VID_PIDS: Set<Pair<Int, Int>> = setOf(
            0x0424 to 0x4911, // Microchip USB hub (1060:18705)
            0x0424 to 0x49A0, // Microchip bridge (1060:18848)
            0x2996 to 0x0105  // Internal automotive peripheral (10646:261)
        )

        private val IGNORED_C10_VIDS: Set<Int> = setOf(
            0x2996, // 10646
            0x2E88  // 11912
        )

        /**
         * Known Android phone Vendor IDs. Used to identify phones even when connected in
         * "Charging Only" mode where MTP/ADB/PTP USB interfaces are not exposed yet.
         */
        private val KNOWN_PHONE_VENDOR_IDS: Set<Int> = setOf(
            0x04E8, // 1256  Samsung
            0x18D1, // 6353  Google
            0x22B8, // 8888  Motorola
            0x2717, // 10007 Xiaomi
            0x12D1, // 4817  Huawei
            0x339B, // 13211 Honor
            0x2A70, // 10864 OnePlus
            0x22D9, // 8921  OPPO / Realme
            0x2D95, // 11669 vivo
            0x1004, // 4100  LG
            0x0FCE, // 4046  Sony
            0x0BB4, // 2996  HTC
            0x19D2, // 6610  ZTE
            0x0B05, // 2821  ASUS
            0x17EF, // 6127  Lenovo
            0x2E04, // 11780 Nothing
            0x1BBB, // 7099  TCL / Alcatel
            0x2A45, // 10821 Meizu
            0x29A9, // 10665 Smartisan
            0x0E8D, // 3725  MediaTek
            0x05C6, // 1478  Qualcomm
            0x1CD3  // 7379  Fairphone / OEM
        )

        fun isAppleDevice(vendorId: Int): Boolean = vendorId == APPLE_VID

        fun isAppleDevice(device: UsbDevice): Boolean = isAppleDevice(device.vendorId)

        fun isIgnoredInternalVidPid(vendorId: Int, productId: Int): Boolean {
            if (vendorId in IGNORED_C10_VIDS) return true
            if ((vendorId to productId) in IGNORED_C10_VID_PIDS) return true
            return false
        }

        fun isIgnoredInternalDevice(device: UsbDevice): Boolean {
            if (isIgnoredInternalVidPid(device.vendorId, device.productId)) return true
            for (i in 0 until device.interfaceCount) {
                if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_HUB) {
                    return true
                }
            }
            return false
        }

        fun isKnownPhoneVendor(vendorId: Int, productId: Int): Boolean =
            !isIgnoredInternalVidPid(vendorId, productId) && vendorId in KNOWN_PHONE_VENDOR_IDS

        fun isKnownPhoneVendor(device: UsbDevice): Boolean =
            !isIgnoredInternalDevice(device) && device.vendorId in KNOWN_PHONE_VENDOR_IDS

        fun getUniqueName(device: UsbDevice): String {
            val vendorId = device.vendorId
            val productId = device.productId
            val vidHex = Utils.hex_get(vendorId.toShort())
            val pidHex = Utils.hex_get(productId.toShort())
            val vidPid = "VID: $vidHex PID: $pidHex"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val manufacturer = device.manufacturerName?.takeIf { it.isNotBlank() }
                val product = device.productName?.takeIf { it.isNotBlank() }
                if (manufacturer != null || product != null) {
                    // Include VID:PID so devices with identical strings but different
                    // hardware (e.g. internal multimedia module vs external phone) are
                    // treated as distinct entries.
                    return "${listOfNotNull(manufacturer, product).joinToString(" ")} ($vidPid)"
                }
            }

            return vidPid
        }

        fun isInAccessoryMode(device: UsbDevice): Boolean {
            val dev_vend_id = device.vendorId
            val dev_prod_id = device.productId
            return dev_vend_id == USB_VID_GOO &&
                (dev_prod_id == USB_PID_ACC || dev_prod_id == USB_PID_ACC_ADB)
        }

        fun isAndroidDevice(device: UsbDevice): Boolean {
            // Apple does not support Android Auto (handled by CarPlay)
            if (device.vendorId == APPLE_VID) return false

            // Exclude Leapmotor C10 internal hubs/bridges
            if (isIgnoredInternalDevice(device)) return false

            if (isInAccessoryMode(device)) return true

            // Recognize known Android phone OEMs even in charge-only USB mode
            if (isKnownPhoneVendor(device)) return true

            return hasAndroidInterface(device)
        }

        /**
         * Identifies an Android device by USB class/subclass
         * More reliable than a VID list
         */
        private fun hasAndroidInterface(device: UsbDevice): Boolean {
            for (i in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(i)
                val ifaceClass = usbInterface.interfaceClass
                val ifaceSubclass = usbInterface.interfaceSubclass
                val ifaceProtocol = usbInterface.interfaceProtocol

                // AOAP (Android Open Accessory Protocol)
                if (ifaceClass == 0xFF && ifaceSubclass == 0xFF && ifaceProtocol == 0x00) {
                    if (hasBulkEndpoint(usbInterface)) return true
                }

                // MTP (Media Transfer Protocol)
                if (ifaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    ifaceSubclass == 0x06 && ifaceProtocol == 0x01) {
                    return true
                }

                // ADB (Android Debug Bridge)
                if (ifaceClass == 0xFF && ifaceSubclass == 0x42 && ifaceProtocol == 0x01) {
                    return true
                }

                // RNDIS (USB tethering)
                if (ifaceClass == 0xE0 && ifaceSubclass == 0x01 && ifaceProtocol == 0x03) {
                    return true
                }

                // IAD (Interface Association Descriptor) - современные Android
                if (ifaceClass == 0xEF && ifaceSubclass == 0x04 && ifaceProtocol == 0x01) {
                    return true
                }

                // PTP (Picture Transfer Protocol) - старые Android
                if (ifaceClass == 0x06 && ifaceSubclass == 0x01 && ifaceProtocol == 0x01) {
                    return true
                }
            }

            return false
        }

        /**
         * Checks for the presence of a bulk endpoint (needed for data transfer)
         */
        private fun hasBulkEndpoint(usbInterface: android.hardware.usb.UsbInterface): Boolean {
            for (j in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(j)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    return true
                }
            }
            return false
        }
    }
}
