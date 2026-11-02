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
    fun `sin pen solo queda la memoria interna`() {
        assertEquals(listOf("primary"), CarFiles.rootOrder(listOf(CarFiles.Volume("primary", false))))
        assertEquals(emptyList<String>(), CarFiles.rootOrder(emptyList()))
    }
}
