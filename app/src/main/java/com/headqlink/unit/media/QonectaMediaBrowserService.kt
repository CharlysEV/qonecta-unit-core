package com.headqlink.unit.media

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import androidx.media.MediaBrowserServiceCompat

/**
 * Intentionally inert stub on Leapmotor C10 (`com.tencent.qqmusiccar`).
 *
 * IMPORTANT:
 * Because our package name on the Leapmotor C10 is `com.tencent.qqmusiccar` (the OEM QQ Music ID
 * hardcoded inside `com.leapmotor.multimedia` and `com.leapmotor.mediaclient`), exporting a
 * `MediaBrowserService` or keeping a second `MediaSessionCompat` causes LeapOS's native Radio /
 * Multimedia app (`com.leapmotor.multimedia` / `com.leapmotor.fmdab`) to bind to `com.tencent.qqmusiccar`
 * every time the user opens the car's Radio app, hijacking steering-wheel controls away from the
 * Radio. Therefore, this service is NOT registered in `AndroidManifest.xml` and `QonectaMediaSessionManager`
 * is a strict no-op (all cluster media rendering uses `ClusterMapController.mediaState` in-process).
 */
class QonectaMediaBrowserService : MediaBrowserServiceCompat() {
    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot? = null

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        result.sendResult(mutableListOf())
    }
}

object QonectaMediaSessionManager {
    interface MediaControlListener {
        fun onPlay()
        fun onPause()
        fun onPlayPause()
        fun onSkipToNext()
        fun onSkipToPrevious()
        fun onStop()
    }

    @Suppress("UNUSED_PARAMETER")
    fun ensureInitialized(context: Context) {
        // No-op: never create a second MediaSessionCompat in com.tencent.qqmusiccar.
    }

    @Suppress("UNUSED_PARAMETER")
    fun setMediaControlListener(listener: MediaControlListener?) {
        // No-op
    }

    @Suppress("UNUSED_PARAMETER")
    fun publishIdleWelcome(context: Context) {
        // No-op
    }

    @Suppress("UNUSED_PARAMETER")
    fun publishConnecting(context: Context, modeLabel: String, detail: String) {
        // No-op
    }

    @Suppress("UNUSED_PARAMETER")
    fun publishSessionActive(context: Context, modeLabel: String, subtitle: String) {
        // No-op
    }

    @Suppress("UNUSED_PARAMETER")
    fun updateNowPlaying(
        context: Context,
        title: String,
        artist: String,
        album: String,
        appName: String,
        cover: Bitmap?,
        playing: Boolean = true,
        durationMs: Long = 0L,
        positionMs: Long = 0L,
        activateSession: Boolean = playing,
    ) {
        // No-op
    }
}
