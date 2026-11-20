package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAttachPolicyTest {

    @Test
    fun idleWirelessSettingsDoNotBlockUsbAttach() {
        // Even if Wi-Fi 5 GHz or Hotspot checkboxes are enabled in Settings, plugging in USB
        // when no wireless projection session is actually connected must connect USB directly.
        assertFalse(
            "USB attach must not prompt or abort when no wireless session is active",
            UsbAttachPolicy.shouldPromptSwitchToUsb(wirelessSessionActive = false),
        )
    }

    @Test
    fun activeWirelessSessionPromptsBeforeSwitchingToUsb() {
        assertTrue(
            "USB attach should prompt only when an active wireless session is currently running",
            UsbAttachPolicy.shouldPromptSwitchToUsb(wirelessSessionActive = true),
        )
    }

    @Test
    fun usbPortResetTreatsZeroEnodevAndVanishedDeviceAsSuccess() {
        assertTrue("rc == 0 indicates clean USBDEVFS_RESET", UsbAttachPolicy.isUsbPortResetSuccessful(0, true))
        assertTrue(
            "rc == -19 (-ENODEV) means phone reset and vanished immediately to re-enumerate",
            UsbAttachPolicy.isUsbPortResetSuccessful(-19, true),
        )
        assertTrue(
            "Vanished device handle indicates bus re-enumeration occurred",
            UsbAttachPolicy.isUsbPortResetSuccessful(-1, false),
        )
        assertFalse(
            "Other ioctl errors while device remains wedged must report failure",
            UsbAttachPolicy.isUsbPortResetSuccessful(-1, true),
        )
        assertFalse(
            "Permission error (-13 EACCES) with device still present must report failure",
            UsbAttachPolicy.isUsbPortResetSuccessful(-13, true),
        )
    }

    @Test
    fun donationLinksPointToKofiAndPaypal() {
        org.junit.Assert.assertEquals("https://ko-fi.com/charlysev", com.andrerinas.openheadunit.main.SupportDonationsDialog.KOFI_URL)
        org.junit.Assert.assertEquals("https://www.paypal.me/CharlysEV", com.andrerinas.openheadunit.main.SupportDonationsDialog.PAYPAL_URL)
    }
}
