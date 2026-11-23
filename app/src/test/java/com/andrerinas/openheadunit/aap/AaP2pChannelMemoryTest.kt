package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AaP2pChannelMemoryTest {

    @Test
    fun `accepts non-DFS 5 GHz UNII-1 and UNII-3 frequencies`() {
        assertTrue(AaP2pChannelMemory.isValidNonDfs5Ghz(5180))
        assertTrue(AaP2pChannelMemory.isValidNonDfs5Ghz(5240))
        assertTrue(AaP2pChannelMemory.isValidNonDfs5Ghz(5745))
        assertTrue(AaP2pChannelMemory.isValidNonDfs5Ghz(5825))
    }

    @Test
    fun `rejects 2_4 GHz and DFS 5 GHz frequencies`() {
        assertFalse(AaP2pChannelMemory.isValidNonDfs5Ghz(2412))
        assertFalse(AaP2pChannelMemory.isValidNonDfs5Ghz(2462))
        assertFalse(AaP2pChannelMemory.isValidNonDfs5Ghz(5260)) // ch 52 DFS
        assertFalse(AaP2pChannelMemory.isValidNonDfs5Ghz(5500)) // ch 100 DFS
        assertFalse(AaP2pChannelMemory.isValidNonDfs5Ghz(0))
    }

    @Test
    fun `converts frequencies to Wi-Fi channel numbers and chooses escalation cleanly`() {
        assertEquals(36, AaP2pChannelMemory.channelNumberFor(5180))
        assertEquals(48, AaP2pChannelMemory.channelNumberFor(5240))
        assertEquals(149, AaP2pChannelMemory.channelNumberFor(5745))
        assertEquals(165, AaP2pChannelMemory.channelNumberFor(5825))
        assertEquals(1, AaP2pChannelMemory.channelNumberFor(2412))
        assertEquals(14, AaP2pChannelMemory.channelNumberFor(2484))

        assertEquals(5220, AaP2pChannelMemory.chooseFrequencyMhz(5220, 5745, false, 0, 0))
        assertEquals(5745, AaP2pChannelMemory.chooseFrequencyMhz(0, 5745, false, 0, 0))
        assertEquals(5180, AaP2pChannelMemory.chooseFrequencyMhz(0, 0, false, 0, 0))
        assertEquals(5745, AaP2pChannelMemory.chooseFrequencyMhz(0, 5180, false, 1, 0))
        assertEquals(5180, AaP2pChannelMemory.chooseFrequencyMhz(0, 5745, false, 1, 0))
        assertEquals(0, AaP2pChannelMemory.chooseFrequencyMhz(0, 5180, true, 0, 0))
        assertEquals(0, AaP2pChannelMemory.chooseFrequencyMhz(0, 5180, false, 2, 0))
    }
}
