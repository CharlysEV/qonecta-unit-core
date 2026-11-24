package com.headqlink.unit.files

import org.junit.Assert.assertEquals
import org.junit.Test

class CarFilesTest {

    @Test
    fun `el pen va antes que la memoria interna`() {
        val volumes = listOf(CarFiles.Volume("primary", false), CarFiles.Volume("1234-ABCD", true))
        assertEquals(listOf("1234-ABCD", "primary"), CarFiles.rootOrder(volumes))
    }

    @Test
    fun `varios pendrives deduplicados preceden a la memoria interna`() {
        val volumes = listOf(
            CarFiles.Volume("primary", false),
            CarFiles.Volume("USB-1", true),
            CarFiles.Volume("USB-1", true),
            CarFiles.Volume("USB-2", true),
        )
        assertEquals(listOf("USB-1", "USB-2", "primary"), CarFiles.rootOrder(volumes))
    }

    @Test
    fun `sin pen solo queda la memoria interna`() {
        assertEquals(listOf("primary"), CarFiles.rootOrder(listOf(CarFiles.Volume("primary", false))))
        assertEquals(emptyList<String>(), CarFiles.rootOrder(emptyList()))
    }

    @Test
    fun `preferredUsbRoot devuelve el primer pendrive extraible o null si no hay`() {
        val withUsb = listOf(
            CarFiles.Volume("primary", false),
            CarFiles.Volume("9F42-1B0E", true),
            CarFiles.Volume("ABCD-9999", true),
        )
        assertEquals("9F42-1B0E", CarFiles.preferredUsbRoot(withUsb))
        assertEquals(null, CarFiles.preferredUsbRoot(listOf(CarFiles.Volume("primary", false))))
        assertEquals(null, CarFiles.preferredUsbRoot(emptyList()))
    }
}
