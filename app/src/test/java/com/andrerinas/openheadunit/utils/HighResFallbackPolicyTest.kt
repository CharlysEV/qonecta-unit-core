package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.aap.protocol.proto.Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HighResFallbackPolicyTest {

    @Before
    fun setUp() {
        HighResFallbackPolicy.reset()
    }

    @Test
    fun singlePreVideoFailureDoesNotTriggerFallback() {
        val resId = Settings.Resolution._2560x1440.id
        HighResFallbackPolicy.onServiceDiscoverySent(resId, VideoCodecResolutionType._2560x1440)

        assertFalse(HighResFallbackPolicy.onTransportQuit())
        assertFalse(HighResFallbackPolicy.shouldForce1080pFallback(resId))
    }

    @Test
    fun twoConsecutivePreVideoFailuresTriggerFallbackForLandscapeAndPortrait() {
        val resId = Settings.Resolution._2560x1440.id

        HighResFallbackPolicy.onServiceDiscoverySent(resId, VideoCodecResolutionType._2560x1440)
        assertFalse(HighResFallbackPolicy.onTransportQuit())
        assertFalse(HighResFallbackPolicy.shouldForce1080pFallback(resId))

        HighResFallbackPolicy.onServiceDiscoverySent(resId, VideoCodecResolutionType._2560x1440)
        assertTrue(HighResFallbackPolicy.onTransportQuit())
        assertTrue(HighResFallbackPolicy.shouldForce1080pFallback(resId))

        assertEquals(
            VideoCodecResolutionType._1920x1080,
            HighResFallbackPolicy.fallback1080pFor(VideoCodecResolutionType._2560x1440),
        )
        assertEquals(
            VideoCodecResolutionType._1080x1920,
            HighResFallbackPolicy.fallback1080pFor(VideoCodecResolutionType._1440x2560),
        )
        assertEquals(
            VideoCodecResolutionType._1080x1920,
            HighResFallbackPolicy.fallback1080pFor(VideoCodecResolutionType._2160x3840),
        )
    }

    @Test
    fun receivingMainVideoResetsFailuresAndDoesNotTriggerFallbackOnQuit() {
        val resId = Settings.Resolution._2560x1440.id

        HighResFallbackPolicy.onServiceDiscoverySent(resId, VideoCodecResolutionType._2560x1440)
        assertFalse(HighResFallbackPolicy.onTransportQuit())

        HighResFallbackPolicy.onServiceDiscoverySent(resId, VideoCodecResolutionType._2560x1440)
        HighResFallbackPolicy.onMainVideoDataReceived()
        assertFalse(HighResFallbackPolicy.onTransportQuit())
        assertFalse(HighResFallbackPolicy.shouldForce1080pFallback(resId))
    }

    @Test
    fun changingResolutionSettingClearsActiveFallback() {
        val twoK = Settings.Resolution._2560x1440.id
        val fourK = Settings.Resolution._3840x2160.id

        HighResFallbackPolicy.onServiceDiscoverySent(twoK, VideoCodecResolutionType._2560x1440)
        HighResFallbackPolicy.onTransportQuit()
        HighResFallbackPolicy.onServiceDiscoverySent(twoK, VideoCodecResolutionType._2560x1440)
        assertTrue(HighResFallbackPolicy.onTransportQuit())
        assertTrue(HighResFallbackPolicy.shouldForce1080pFallback(twoK))

        HighResFallbackPolicy.onResolutionSettingChanged(fourK)
        assertFalse(HighResFallbackPolicy.shouldForce1080pFallback(twoK))
        assertFalse(HighResFallbackPolicy.shouldForce1080pFallback(fourK))
    }
}
