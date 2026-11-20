package com.andrerinas.openheadunit.aap

/**
 * Policy governing how USB device attach events interact with wireless Android Auto state
 * and how USB port reset results are interpreted on automotive head units (e.g. Leapmotor C10).
 */
object UsbAttachPolicy {

    /**
     * Only prompt the user to switch transports when an actual wireless projection session
     * is currently active. Having Wi-Fi 5 GHz or Hotspot checkboxes enabled in Settings while
     * idle must NEVER block plugging in a USB cable or abort AOA re-enumeration (`18D1:2D01`).
     */
    @JvmStatic
    fun shouldPromptSwitchToUsb(wirelessSessionActive: Boolean): Boolean = wirelessSessionActive

    /**
     * Interprets the return code of `USBDEVFS_RESET` (`ioctl`).
     * Linux returns `-19` (`-ENODEV`) when the phone resets and immediately disappears from
     * the USB bus to re-enumerate, which indicates the reset succeeded.
     */
    @JvmStatic
    fun isUsbPortResetSuccessful(rc: Int, deviceStillPresent: Boolean): Boolean =
        rc == 0 || rc == -19 || !deviceStillPresent
}
