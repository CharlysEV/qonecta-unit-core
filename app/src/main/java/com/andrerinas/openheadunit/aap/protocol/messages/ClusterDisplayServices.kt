package com.andrerinas.openheadunit.aap.protocol.messages

import android.content.Context
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.cluster.ClusterMapController
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * Builds the Android Auto instrument-cluster video (`Channel.ID_CLUSTER_VID = 14`) and input
 * (`Channel.ID_CLUSTER_INP = 15`) services for `ServiceDiscoveryResponse` when a secondary
 * cluster display (`Display 2` on the Leapmotor C10) is present AND explicitly enabled by the user
 * (`settings.clusterMapEnabled == true`, off by default in Core).
 */
object ClusterDisplayServices {

    private const val CLUSTER_PROTOCOL_DISPLAY_ID = 1

    fun buildUiConfig720(settings: Settings): Media.UiConfig {
        val shiftX720 = settings.clusterMarkerX * 40
        val shiftY720 = settings.clusterMarkerY * 25
        val leftInset720 = (820 + shiftX720).coerceIn(560, 960)
        val rightInset720 = (28 - shiftX720).coerceIn(16, 260)
        val topInset720 = (138 + shiftY720).coerceIn(20, 280)
        val bottomInset720 = (108 - shiftY720).coerceIn(20, 240)

        val zeroMargins = Media.Insets.newBuilder()
            .setTop(0)
            .setBottom(0)
            .setLeft(0)
            .setRight(0)
            .build()

        val contentInsets720 = Media.Insets.newBuilder()
            .setTop(topInset720)
            .setBottom(bottomInset720)
            .setLeft(leftInset720)
            .setRight(rightInset720)
            .build()

        return Media.UiConfig.newBuilder()
            .setMargins(zeroMargins)
            .setContentInsets(contentInsets720)
            .setStableContentInsets(contentInsets720)
            .build()
    }

    fun build(context: Context, settings: Settings): List<Control.Service> {
        if (!settings.clusterMapEnabled) {
            return emptyList()
        }
        val clusterDisplay = ClusterMapController.findClusterDisplay(context) ?: return emptyList()

        AppLog.i(
            "[ServiceDiscovery] Announcing Android Auto DISPLAY_TYPE_CLUSTER on channels " +
                "${Channel.ID_CLUSTER_VID}/${Channel.ID_CLUSTER_INP} for physical displayId=${clusterDisplay.displayId}"
        )

        val shiftX720 = settings.clusterMarkerX * 40
        val shiftY720 = settings.clusterMarkerY * 25
        val leftInset720 = (820 + shiftX720).coerceIn(560, 960)
        val rightInset720 = (28 - shiftX720).coerceIn(16, 260)
        val topInset720 = (138 + shiftY720).coerceIn(20, 280)
        val bottomInset720 = (108 - shiftY720).coerceIn(20, 240)

        val zeroMargins = Media.Insets.newBuilder()
            .setTop(0)
            .setBottom(0)
            .setLeft(0)
            .setRight(0)
            .build()

        val uiConfig720 = buildUiConfig720(settings)

        val video720p = Control.Service.MediaSinkService.VideoConfiguration.newBuilder()
            .setCodecResolution(Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720)
            .setFrameRate(Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30)
            .setMarginWidth(0)
            .setMarginHeight(0)
            .setDensity(160)
            .setRealDensity(160)
            .setViewingDistance(750)
            .setPixelAspectRatioE4(10000)
            .setVideoCodecType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
            .setUiConfig(uiConfig720)
            .build()

        val leftInset480 = (leftInset720 * 800) / 1280
        val rightInset480 = (rightInset720 * 800) / 1280
        val topInset480 = (topInset720 * 480) / 720
        val bottomInset480 = (bottomInset720 * 480) / 720

        val contentInsets480 = Media.Insets.newBuilder()
            .setTop(topInset480)
            .setBottom(bottomInset480)
            .setLeft(leftInset480)
            .setRight(rightInset480)
            .build()

        val uiConfig480 = Media.UiConfig.newBuilder()
            .setMargins(zeroMargins)
            .setContentInsets(contentInsets480)
            .setStableContentInsets(contentInsets480)
            .build()

        val video480p = Control.Service.MediaSinkService.VideoConfiguration.newBuilder()
            .setCodecResolution(Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480)
            .setFrameRate(Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30)
            .setMarginWidth(0)
            .setMarginHeight(0)
            .setDensity(160)
            .setRealDensity(160)
            .setViewingDistance(750)
            .setPixelAspectRatioE4(10000)
            .setVideoCodecType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
            .setUiConfig(uiConfig480)
            .build()

        val clusterVideoService = Control.Service.newBuilder()
            .setId(Channel.ID_CLUSTER_VID)
            .setMediaSinkService(
                Control.Service.MediaSinkService.newBuilder()
                    .setAvailableType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                    .setAudioType(Media.AudioStreamType.NONE)
                    .setAvailableWhileInCall(true)
                    .setDisplayId(CLUSTER_PROTOCOL_DISPLAY_ID)
                    .setDisplayType(Control.Service.MediaSinkService.DisplayType.DISPLAY_TYPE_CLUSTER)
                    .addVideoConfigs(video720p)
                    .addVideoConfigs(video480p)
                    .build()
            )
            .build()

        val clusterInputService = Control.Service.newBuilder()
            .setId(Channel.ID_CLUSTER_INP)
            .setInputSourceService(
                Control.Service.InputSourceService.newBuilder()
                    .setDisplayId(CLUSTER_PROTOCOL_DISPLAY_ID)
                    .build()
            )
            .build()

        return listOf(clusterVideoService, clusterInputService)
    }
}
