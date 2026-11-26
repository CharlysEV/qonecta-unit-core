package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.aap.protocol.proto.Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType

/**
 * Automatic anti-loop guard for Android Auto high-resolution (2K / 4K) sessions.
 *
 * When 2K or 4K is negotiated, the phone requires H.265 (HEVC) and 1440p/4K enabled in
 * Android Auto Developer Settings. If the phone rejects that stream before delivering any video
 * frame (for example dropping and retrying in a loop), this policy automatically downgrades the
 * next session to 1080p H.264 after [MAX_FAILURES_BEFORE_FALLBACK] consecutive pre-video failures
 * so projection recovers cleanly instead of looping forever.
 */
object HighResFallbackPolicy {

    const val MAX_FAILURES_BEFORE_FALLBACK = 2

    private var trackedResolutionId: Int? = null
    private var consecutiveHighResFailures: Int = 0
    private var fallbackActive: Boolean = false
    private var pendingHighResAttempt: Boolean = false
    private var videoArrivedForCurrentAttempt: Boolean = false

    @Synchronized
    fun isHighRes(type: VideoCodecResolutionType): Boolean =
        type == VideoCodecResolutionType._2560x1440 ||
            type == VideoCodecResolutionType._1440x2560 ||
            type == VideoCodecResolutionType._3840x2160 ||
            type == VideoCodecResolutionType._2160x3840

    @Synchronized
    fun fallback1080pFor(type: VideoCodecResolutionType): VideoCodecResolutionType =
        when (type) {
            VideoCodecResolutionType._1440x2560,
            VideoCodecResolutionType._2160x3840 -> VideoCodecResolutionType._1080x1920
            else -> VideoCodecResolutionType._1920x1080
        }

    @Synchronized
    fun onResolutionSettingChanged(resolutionId: Int) {
        if (trackedResolutionId != null && trackedResolutionId != resolutionId) {
            consecutiveHighResFailures = 0
            fallbackActive = false
            pendingHighResAttempt = false
            videoArrivedForCurrentAttempt = false
        }
        trackedResolutionId = resolutionId
    }

    @Synchronized
    fun shouldForce1080pFallback(resolutionId: Int): Boolean =
        fallbackActive && trackedResolutionId == resolutionId

    @Synchronized
    fun onServiceDiscoverySent(resolutionId: Int, negotiated: VideoCodecResolutionType) {
        onResolutionSettingChanged(resolutionId)
        trackedResolutionId = resolutionId
        pendingHighResAttempt = isHighRes(negotiated)
        videoArrivedForCurrentAttempt = false
    }

    @Synchronized
    fun onMainVideoDataReceived() {
        videoArrivedForCurrentAttempt = true
        if (pendingHighResAttempt) {
            consecutiveHighResFailures = 0
            fallbackActive = false
        }
    }

    @Synchronized
    fun onTransportQuit(): Boolean {
        val failedBeforeVideo = pendingHighResAttempt && !videoArrivedForCurrentAttempt
        pendingHighResAttempt = false
        if (!failedBeforeVideo) {
            return false
        }
        consecutiveHighResFailures++
        if (consecutiveHighResFailures >= MAX_FAILURES_BEFORE_FALLBACK) {
            fallbackActive = true
            return true
        }
        return false
    }

    @Synchronized
    fun reset() {
        trackedResolutionId = null
        consecutiveHighResFailures = 0
        fallbackActive = false
        pendingHighResAttempt = false
        videoArrivedForCurrentAttempt = false
    }
}
