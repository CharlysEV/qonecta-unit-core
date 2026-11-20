package com.andrerinas.openheadunit.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterMapPolicyTest {

    @Test
    fun `track change triggers popup once and ignores repeated updates for same song`() {
        val sig1 = ClusterMapController.trackSignature("Bohemian Rhapsody", "Queen")
        assertTrue(ClusterMapController.shouldTriggerMediaPopup("", "Bohemian Rhapsody", "Queen"))
        assertFalse(ClusterMapController.shouldTriggerMediaPopup(sig1, "Bohemian Rhapsody", "Queen"))
        assertFalse(ClusterMapController.shouldTriggerMediaPopup(sig1, "  Bohemian Rhapsody  ", " Queen "))
        assertTrue(ClusterMapController.shouldTriggerMediaPopup(sig1, "Don't Stop Me Now", "Queen"))
    }

    @Test
    fun `blank track title never triggers popup`() {
        assertFalse(ClusterMapController.shouldTriggerMediaPopup("", "", "Queen"))
        assertFalse(ClusterMapController.shouldTriggerMediaPopup("", "   ", ""))
    }

    @Test
    fun `track signature normalizes surrounding whitespace`() {
        assertEquals(
            "Song|Artist",
            ClusterMapController.trackSignature("  Song ", " Artist\t"),
        )
    }
}
