package com.andrerinas.openheadunit.aap

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.andrerinas.openheadunit.aap.protocol.AudioConfigs
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.decoder.AudioDecoder
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.Settings

internal class AapAudio(
    private val audioDecoder: AudioDecoder,
    private val audioManager: AudioManager,
    private val settings: Settings,
    private val context: Context
) {

    private val staticAudioFocus = settings.staticAudioFocus
    private val separateAudioStreams = settings.separateAudioStreams
    private val mediaVolumeOffset = settings.mediaVolumeOffset
    private val assistantVolumeOffset = settings.assistantVolumeOffset
    private val navigationVolumeOffset = settings.navigationVolumeOffset
    private val audioLatencyMultiplier = settings.audioLatencyMultiplier
    private val useAacAudio = settings.useAacAudio
    private val audioQueueCapacity = settings.audioQueueCapacity
    private val enableAudioSink = settings.enableAudioSink
    private val attachHwDspEqualizer = settings.attachHwDspEqualizer
    private val playbackFocusMode = settings.effectivePlaybackFocusMode

    // Single unified AudioFocusRequest slot guarded by a monotonic generation token.
    // All static, protocol, and dynamic playback focus transitions share this slot so two
    // AudioFocusRequests from this app can never compete with or evict each other.
    private val focusLock = Any()
    @Volatile
    private var focusGeneration = 0L
    private var currentFocusRequest: AudioFocusRequest? = null
    private var currentLegacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    @Volatile
    private var currentFocusGainType: Int = AudioManager.AUDIOFOCUS_NONE

    private val activeAudioChannels = mutableSetOf<Int>()

    @Volatile
    private var holdingPlaybackFocus = false
    var onExternalAudioFocusLost: (() -> Unit)? = null
    var onPlaybackAudioFocusAcquired: (() -> Unit)? = null
    @Volatile
    var externalFocusLossUntilMs = 0L

    @Volatile
    private var focusAcquiredAtMs = 0L
    @Volatile
    private var selfDefeatingStops = 0
    @Volatile
    private var selfDefeatingLatched = false

    @Volatile
    private var isDucked = false
    private val handler = Handler(Looper.getMainLooper())
    private val unduckRunnable = Runnable {
        unduckMedia()
    }

    private fun abandonCurrentFocusLocked() {
        currentFocusGainType = AudioManager.AUDIOFOCUS_NONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            currentFocusRequest?.let { req ->
                runCatching { audioManager.abandonAudioFocusRequest(req) }
            }
            currentFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            currentLegacyFocusListener?.let { listener ->
                runCatching { audioManager.abandonAudioFocus(listener) }
            }
            currentLegacyFocusListener = null
        }
    }

    private fun abandonUnifiedFocus() {
        synchronized(focusLock) {
            focusGeneration++
            abandonCurrentFocusLocked()
        }
    }

    private fun acquireUnifiedFocus(
        stream: Int,
        gainType: Int,
        extraCallback: AudioManager.OnAudioFocusChangeListener? = null,
        skipIfAlreadyHoldingMedia: Boolean = false
    ): Int {
        synchronized(focusLock) {
            if (skipIfAlreadyHoldingMedia &&
                (currentFocusGainType == AudioManager.AUDIOFOCUS_GAIN ||
                    currentFocusGainType == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            ) {
                return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }

            val gen = ++focusGeneration
            abandonCurrentFocusLocked()

            val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                if (gen != focusGeneration) {
                    AppLog.d("AapAudio: ignoring stale audio focus change $focusChange (gen=$gen != $focusGeneration)")
                    return@OnAudioFocusChangeListener
                }
                AppLog.i("AapAudio: unified system audio focus changed: $focusChange")
                extraCallback?.onAudioFocusChange(focusChange)
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                    focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    onExternalFocusLoss("systemFocus($focusChange)")
                }
            }

            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Never map to USAGE_VOICE_COMMUNICATION on AAOS/LeapOS: it hijacks HFP/Lyra calls.
                val (usage, contentType) = when (stream) {
                    AudioManager.STREAM_NOTIFICATION -> Pair(
                        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                        AudioAttributes.CONTENT_TYPE_SPEECH
                    )
                    else -> Pair(
                        AudioAttributes.USAGE_MEDIA,
                        AudioAttributes.CONTENT_TYPE_MUSIC
                    )
                }
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(contentType)
                    .build()
                val req = AudioFocusRequest.Builder(gainType)
                    .setAudioAttributes(audioAttributes)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(listener)
                    .build()
                val r = runCatching { audioManager.requestAudioFocus(req) }
                    .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
                if (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    currentFocusRequest = req
                    currentFocusGainType = gainType
                }
                r
            } else {
                @Suppress("DEPRECATION")
                val safeStream = if (stream == AudioManager.STREAM_VOICE_CALL) {
                    AudioManager.STREAM_NOTIFICATION
                } else {
                    stream
                }
                @Suppress("DEPRECATION")
                val r = runCatching { audioManager.requestAudioFocus(listener, safeStream, gainType) }
                    .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
                if (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    currentLegacyFocusListener = listener
                    currentFocusGainType = gainType
                }
                r
            }
            AppLog.i(
                "AapAudio: unified focus request (stream=$stream, gainType=$gainType) -> " +
                    if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED ($result)"
            )
            return result
        }
    }

    fun onExternalFocusLoss(reason: String) {
        AppLog.i("AapAudio: yielding audio focus to external car audio/radio ($reason)")
        externalFocusLossUntilMs = SystemClock.elapsedRealtime() + 3500L
        synchronized(activeAudioChannels) { activeAudioChannels.clear() }
        holdingPlaybackFocus = false
        abandonUnifiedFocus()
        runCatching { audioDecoder.stop(Channel.ID_AUD) }
        onExternalAudioFocusLost?.invoke()
    }

    private fun duckMedia() {
        if (!isDucked) {
            val mediaTrack = audioDecoder.getTrack(Channel.ID_AUD)
            if (mediaTrack != null) {
                val duckedVolume = getMediaGain() * DUCK_VOLUME_FACTOR
                AppLog.i("Static Audio Focus: Ducking media volume to $duckedVolume")
                mediaTrack.setVolume(duckedVolume)
                isDucked = true
            }
        }
    }

    private fun unduckMedia() {
        if (isDucked) {
            val mediaTrack = audioDecoder.getTrack(Channel.ID_AUD)
            if (mediaTrack != null) {
                val originalVolume = getMediaGain()
                AppLog.i("Static Audio Focus: Restoring media volume to $originalVolume")
                mediaTrack.setVolume(originalVolume)
            }
            isDucked = false
        }
    }

    private fun getMediaGain(): Float {
        return (1.0f + (mediaVolumeOffset / 100.0f)).coerceIn(0.0f, 2.0f)
    }

    fun shouldHonourProtocolFocusRequest(isRelease: Boolean): Boolean {
        if (isRelease) return true

        val btMediaLinkActive = BluetoothHelper.isA2dpMediaLinkActive(context)
        val honour = PlaybackFocusPolicy.shouldAcquire(
            mode = playbackFocusMode,
            staticAudioFocus = staticAudioFocus,
            audioSinkEnabled = enableAudioSink,
            isAudioChannel = true,
            btMediaLinkActive = btMediaLinkActive,
            selfDefeatingLatched = selfDefeatingLatched
        )

        if (!honour) {
            AppLog.i(
                "AapAudio: phone asked for audio focus - leaving system audio focus alone " +
                    "(mode=$playbackFocusMode, bluetoothMedia=$btMediaLinkActive, latched=$selfDefeatingLatched)"
            )
        }
        return honour
    }

    fun requestFocusChange(
        stream: Int,
        focusRequest: Int,
        callback: AudioManager.OnAudioFocusChangeListener? = null
    ): Int {
        AppLog.i("Audio Focus Request: stream=$stream, type=$focusRequest")
        return when (focusRequest) {
            Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE_VALUE -> {
                AppLog.i("AapAudio: Releasing audio focus")
                holdingPlaybackFocus = false
                abandonUnifiedFocus()
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_VALUE -> {
                acquireUnifiedFocus(stream, AudioManager.AUDIOFOCUS_GAIN, callback)
            }
            Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_VALUE -> {
                acquireUnifiedFocus(stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, callback)
            }
            Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_MAY_DUCK_VALUE -> {
                acquireUnifiedFocus(stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, callback)
            }
            else -> AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
    }

    private fun requestPlaybackFocus() {
        acquireUnifiedFocus(
            stream = AudioManager.STREAM_MUSIC,
            gainType = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            extraCallback = null,
            skipIfAlreadyHoldingMedia = true
        )
    }

    private fun releasePlaybackFocus() {
        AppLog.i("AapAudio: Releasing playback transient audio focus")
        abandonUnifiedFocus()
    }

    fun releaseAllFocus() {
        AppLog.i("AapAudio: Releasing all audio focus.")
        handler.removeCallbacks(unduckRunnable)
        isDucked = false
        synchronized(activeAudioChannels) { activeAudioChannels.clear() }
        // The latch is a property of one connection, not of the head unit: re-arm it so a session
        // that reconnects with Bluetooth off gets the car-radio behaviour back.
        holdingPlaybackFocus = false
        focusAcquiredAtMs = 0L
        selfDefeatingStops = 0
        selfDefeatingLatched = false
        abandonUnifiedFocus()
    }

    /**
     * Processes a message as an audio stream packet.
     * Returns true if the packet was identified and processed as audio data, false otherwise.
     */
    fun process(message: AapMessage): Boolean {
        // Media stream packets have msgType 0 or 1.
        // Control packets on audio channels (Setup, Start, Stop) have types > 32767.
        if (message.type == 0 || message.type == 1) {
            if (message.size >= 10) {
                decode(message.channel, 10, message.data, message.size - 10)
            }
            return true
        }
        return false
    }

    private fun startAudioTrack(channel: Int) {
        if (audioDecoder.getTrack(channel) != null) return

        val config = AudioConfigs.get(channel)
        val stream = AudioConfigs.stream(channel, separateAudioStreams)

        val offset = when (channel) {
            Channel.ID_AUD -> mediaVolumeOffset
            Channel.ID_AU1 -> assistantVolumeOffset
            Channel.ID_AU2 -> navigationVolumeOffset
            else -> 0
        }
        val gain = (1.0f + (offset / 100.0f)).coerceIn(0.0f, 2.0f)

        // Voice and Navigation benefit from lower latency. Cap the multiplier for those channels.
        val effectiveMultiplier = if (channel == Channel.ID_AUD) {
            audioLatencyMultiplier
        } else {
            audioLatencyMultiplier.coerceAtMost(4)
        }

        AppLog.i("AudioDecoder.start: channel=$channel, stream=$stream, gain=$gain, sampleRate=${config.sampleRate}, numberOfBits=${config.numberOfBits}, numberOfChannels=${config.numberOfChannels}, isAac=$useAacAudio, latencyMultiplier=$effectiveMultiplier, queueCapacity=$audioQueueCapacity, attachHwDspEqualizer=$attachHwDspEqualizer")
        audioDecoder.start(channel, stream, config.sampleRate, config.numberOfBits, config.numberOfChannels, useAacAudio, gain, effectiveMultiplier, audioQueueCapacity, staticAudioFocus, attachHwDspEqualizer)
        onAudioPlaybackStarted(channel)
    }

    private fun onAudioPlaybackStarted(channel: Int) {
        if (staticAudioFocus || !enableAudioSink || !Channel.isAudio(channel)) return

        val wasEmpty = synchronized(activeAudioChannels) {
            if (channel == Channel.ID_AUD && SystemClock.elapsedRealtime() < externalFocusLossUntilMs) {
                AppLog.i("AapAudio: suppressing playback focus re-grab during external car radio cooldown")
                return
            }
            val empty = activeAudioChannels.isEmpty()
            activeAudioChannels.add(channel)
            empty
        }
        if (!wasEmpty) return

        val btMediaLinkActive = BluetoothHelper.isA2dpMediaLinkActive(context)
        val acquire = PlaybackFocusPolicy.shouldAcquire(
            mode = playbackFocusMode,
            staticAudioFocus = staticAudioFocus,
            audioSinkEnabled = enableAudioSink,
            isAudioChannel = true,
            btMediaLinkActive = btMediaLinkActive,
            selfDefeatingLatched = selfDefeatingLatched
        )

        if (!acquire) {
            AppLog.i(
                "AapAudio: AA audio started (${Channel.name(channel)}) - leaving system audio focus alone " +
                    "(mode=$playbackFocusMode, bluetoothMedia=$btMediaLinkActive, latched=$selfDefeatingLatched)"
            )
            return
        }

        AppLog.i(
            "AapAudio: AA audio started (${Channel.name(channel)}) - acquiring transient system audio focus " +
                "(mode=$playbackFocusMode, bluetoothMedia=$btMediaLinkActive)"
        )
        focusAcquiredAtMs = SystemClock.elapsedRealtime()
        holdingPlaybackFocus = true
        requestPlaybackFocus()
        if (channel == Channel.ID_AUD) {
            onPlaybackAudioFocusAcquired?.invoke()
        }
    }

    private fun onAudioPlaybackStopped(channel: Int) {
        if (staticAudioFocus || !enableAudioSink || !Channel.isAudio(channel)) return

        val nowEmpty = synchronized(activeAudioChannels) {
            activeAudioChannels.remove(channel)
            activeAudioChannels.isEmpty()
        }
        if (!nowEmpty || !holdingPlaybackFocus) return

        noteStopWhileHoldingFocus(channel)

        AppLog.i("AapAudio: last AA audio channel stopped - releasing transient system audio focus")
        holdingPlaybackFocus = false
        releasePlaybackFocus()
    }

    private fun noteStopWhileHoldingFocus(channel: Int) {
        if (selfDefeatingLatched || focusAcquiredAtMs == 0L) return

        val elapsedMs = SystemClock.elapsedRealtime() - focusAcquiredAtMs
        if (!PlaybackFocusPolicy.countsAsSelfDefeating(channel == Channel.ID_AUD, elapsedMs)) {
            selfDefeatingStops = 0
            return
        }

        selfDefeatingStops++
        AppLog.d(
            "AapAudio: media stopped ${elapsedMs}ms after taking audio focus " +
                "($selfDefeatingStops/${PlaybackFocusPolicy.SELF_DEFEATING_LIMIT})"
        )
        if (selfDefeatingStops >= PlaybackFocusPolicy.SELF_DEFEATING_LIMIT) {
            selfDefeatingLatched = true
            AppLog.w(
                "AapAudio: taking system audio focus is stopping the phone's own playback " +
                    "(the head unit is most likely its Bluetooth audio sink) - not acquiring it again this session"
            )
        }
    }

    fun precreateAudioTrack(channel: Int) {
        if (!staticAudioFocus) return
        if (channel != Channel.ID_AU2) return
        startAudioTrack(channel)
    }

    private fun decode(channel: Int, start: Int, buf: ByteArray, len: Int) {
        // Drop trailing phone media packets during external audio-focus cooldown so we never
        // recreate an AudioTrack without focus and bleed audio over the car's FM/DAB radio.
        if (channel == Channel.ID_AUD && SystemClock.elapsedRealtime() < externalFocusLossUntilMs) {
            return
        }

        var length = len
        if (length > AUDIO_BUFS_SIZE) {
            AppLog.e("Error audio len: %d  aud_buf_BUFS_SIZE: %d", length, AUDIO_BUFS_SIZE)
            length = AUDIO_BUFS_SIZE
        }

        if (audioDecoder.getTrack(channel) == null) {
            startAudioTrack(channel)
        }

        audioDecoder.decode(channel, buf, start, length)

        if ((channel == Channel.ID_AU1 || channel == Channel.ID_AU2) && staticAudioFocus) {
            duckMedia()
            handler.removeCallbacks(unduckRunnable)
            handler.postDelayed(unduckRunnable, UNDUCK_DELAY_MS)
        }
    }

    fun updateGains() {
        val mediaGain = (1.0f + (settings.mediaVolumeOffset / 100.0f)).coerceIn(0.0f, 2.0f)
        val assistantGain = (1.0f + (settings.assistantVolumeOffset / 100.0f)).coerceIn(0.0f, 2.0f)
        val navGain = (1.0f + (settings.navigationVolumeOffset / 100.0f)).coerceIn(0.0f, 2.0f)

        audioDecoder.setGain(Channel.ID_AUD, mediaGain)
        audioDecoder.setGain(Channel.ID_AU1, assistantGain)
        audioDecoder.setGain(Channel.ID_AU2, navGain)
    }

    fun restartAudio() {
        AppLog.i("AapAudio: Restarting all audio tracks")
        audioDecoder.stop()
    }

    fun stopAudio(channel: Int) {
        AppLog.i("Audio Stop: " + Channel.name(channel))
        if ((channel == Channel.ID_AU1 || channel == Channel.ID_AU2) && staticAudioFocus) {
            // Keep the speech wrappers alive to prevent recreate overhead and keep state consistent.
            // Just restore media volume.
            handler.removeCallbacks(unduckRunnable)
            unduckMedia()
        } else {
            audioDecoder.stop(channel)
            onAudioPlaybackStopped(channel)
        }
    }

    companion object {
        private const val AUDIO_BUFS_SIZE = 65536 * 4  // Up to 256 Kbytes
        private const val DUCK_VOLUME_FACTOR = 0.4f
        private const val UNDUCK_DELAY_MS = 1500L
    }
}
