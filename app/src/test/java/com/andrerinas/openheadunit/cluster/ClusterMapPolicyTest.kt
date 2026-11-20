package com.andrerinas.openheadunit.cluster

import com.andrerinas.openheadunit.aap.AapMessageHandlerType
import com.andrerinas.openheadunit.aap.VideoFragmentAssembler
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media
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

    @Test
    fun `cluster control handshake messages never bypass AapControl into video decoder`() {
        val singleFlags = VideoFragmentAssembler.FLAG_SINGLE.toByte()
        val controlTypes = listOf(
            Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_REQUEST_VALUE,
            Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE,
            Media.MsgType.MEDIA_MESSAGE_START_VALUE,
            Media.MsgType.MEDIA_MESSAGE_STOP_VALUE,
            Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST_VALUE,
        )
        for (msgType in controlTypes) {
            assertFalse(
                "Control msgType=$msgType on ID_CLUSTER_VID must reach AapControl",
                AapMessageHandlerType.shouldOfferToVideoStream(
                    channel = Channel.ID_CLUSTER_VID,
                    targetChannel = Channel.ID_CLUSTER_VID,
                    isFirstOrSingle = true,
                    flags = singleFlags,
                    msgType = msgType,
                ),
            )
        }
    }

    @Test
    fun `cluster video data and continuation fragments route to cluster video pipeline`() {
        val singleFlags = VideoFragmentAssembler.FLAG_SINGLE.toByte()
        val firstFragFlags = 0x09.toByte()
        val contFragFlags = 0x08.toByte()
        val lastFragFlags = 0x0A.toByte()

        assertTrue(
            AapMessageHandlerType.shouldOfferToVideoStream(
                Channel.ID_CLUSTER_VID, Channel.ID_CLUSTER_VID, true, singleFlags, 0
            )
        )
        assertTrue(
            AapMessageHandlerType.shouldOfferToVideoStream(
                Channel.ID_CLUSTER_VID, Channel.ID_CLUSTER_VID, true, singleFlags, 1
            )
        )
        assertTrue(
            AapMessageHandlerType.shouldOfferToVideoStream(
                Channel.ID_CLUSTER_VID, Channel.ID_CLUSTER_VID, true, firstFragFlags, 0
            )
        )
        assertTrue(
            AapMessageHandlerType.shouldOfferToVideoStream(
                Channel.ID_CLUSTER_VID, Channel.ID_CLUSTER_VID, false, contFragFlags, 7
            )
        )
        assertTrue(
            AapMessageHandlerType.shouldOfferToVideoStream(
                Channel.ID_CLUSTER_VID, Channel.ID_CLUSTER_VID, false, lastFragFlags, 32768
            )
        )
        assertFalse(
            AapMessageHandlerType.shouldOfferToVideoStream(
                Channel.ID_VID, Channel.ID_CLUSTER_VID, true, singleFlags, 0
            )
        )
    }
}
