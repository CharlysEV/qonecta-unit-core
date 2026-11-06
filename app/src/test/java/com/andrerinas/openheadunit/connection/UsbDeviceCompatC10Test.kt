package com.andrerinas.openheadunit.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDeviceCompatC10Test {

    @Test
    fun ignoresLeapmotorC10InternalUsbPeripherals() {
        assertTrue(UsbDeviceCompat.isIgnoredInternalVidPid(0x0424, 0x4911))
        assertTrue(UsbDeviceCompat.isIgnoredInternalVidPid(0x0424, 0x49A0))
        assertTrue(UsbDeviceCompat.isIgnoredInternalVidPid(0x2996, 0x0105))
        assertTrue(UsbDeviceCompat.isIgnoredInternalVidPid(0x2E88, 0x0001))

        assertFalse(UsbDeviceCompat.isKnownPhoneVendor(0x0424, 0x4911))
        assertFalse(UsbDeviceCompat.isKnownPhoneVendor(0x2996, 0x0105))
    }

    @Test
    fun recognizesKnownPhoneVendorsEvenInChargeOnlyMode() {
        assertTrue(UsbDeviceCompat.isKnownPhoneVendor(0x04E8, 0x6860)) // Samsung
        assertTrue(UsbDeviceCompat.isKnownPhoneVendor(0x2717, 0xFF40)) // Xiaomi
        assertTrue(UsbDeviceCompat.isKnownPhoneVendor(0x18D1, 0x4EE1)) // Google
        assertTrue(UsbDeviceCompat.isKnownPhoneVendor(0x22B8, 0x2E81)) // Motorola
        assertTrue(UsbDeviceCompat.isKnownPhoneVendor(0x12D1, 0x107E)) // Huawei
        assertTrue(UsbDeviceCompat.isKnownPhoneVendor(0x2A70, 0x9011)) // OnePlus
    }

    @Test
    fun identifiesAppleIphoneAndExcludesFromAndroidPhoneVendors() {
        assertTrue(UsbDeviceCompat.isAppleDevice(0x05AC))
        assertFalse(UsbDeviceCompat.isKnownPhoneVendor(0x05AC, 0x12A8))
    }
}
