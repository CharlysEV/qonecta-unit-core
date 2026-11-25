package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Unified single-request Android AudioFocus coordinator for Apple CarPlay.
 *
 * Guarantees at most ONE live [AudioFocusRequest] (never steals focus from itself across
 * simultaneous media + navigation/alert CarPlay audio streams) and cleanly pauses/ducks
 * the head unit's FM/DAB radio when CarPlay plays, then releases focus when all streams stop.
 */
internal class CarPlayAudioFocusController(
    private val audioManager: AudioManager?,
    private val onExternalFocusLoss: () -> Unit = {},
) {
    private val lock = Any()
    private val activeChannels = mutableMapOf<Int, AudioChannel>()
    private var currentRequest: AudioFocusRequest? = null
    private var currentFocusGain: Int = AudioManager.AUDIOFOCUS_NONE
    private var requestGeneration: Long = 0L

    private fun priorityOf(gain: Int): Int = when (gain) {
        AudioManager.AUDIOFOCUS_GAIN -> 3
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> 2
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> 1
        else -> 0
    }

    internal fun desiredFocusGainFor(channels: Collection<AudioChannel>): Int {
        if (channels.isEmpty()) return AudioManager.AUDIOFOCUS_NONE
        return if (AudioChannel.MEDIA in channels) {
            // GAIN_TRANSIENT pauses the C10's FM/DAB radio while CarPlay music plays and allows
            // the radio to resume automatically once CarPlay stops.
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        } else {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
    }

    fun onStreamStarted(streamType: Int, channel: AudioChannel) {
        val manager = audioManager ?: return
        val desiredGain: Int
        val oldRequest: AudioFocusRequest?
        val generation: Long
        synchronized(lock) {
            activeChannels[streamType] = channel
            desiredGain = desiredFocusGainFor(activeChannels.values)
            if (desiredGain == AudioManager.AUDIOFOCUS_NONE ||
                priorityOf(currentFocusGain) >= priorityOf(desiredGain)
            ) {
                return
            }
            oldRequest = currentRequest
            currentRequest = null
            requestGeneration += 1
            generation = requestGeneration
            currentFocusGain = desiredGain
        }
        abandonQuietly(manager, oldRequest)
        requestFocus(manager, desiredGain, generation)
    }

    fun onStreamStopped(streamType: Int) {
        val manager = audioManager ?: return
        val desiredGain: Int
        val oldRequest: AudioFocusRequest?
        val generation: Long
        synchronized(lock) {
            activeChannels.remove(streamType)
            desiredGain = desiredFocusGainFor(activeChannels.values)
            if (desiredGain == currentFocusGain) return
            oldRequest = currentRequest
            currentRequest = null
            requestGeneration += 1
            generation = requestGeneration
            currentFocusGain = desiredGain
        }
        abandonQuietly(manager, oldRequest)
        if (desiredGain != AudioManager.AUDIOFOCUS_NONE) {
            requestFocus(manager, desiredGain, generation)
        }
    }

    fun releaseAll() {
        val manager = audioManager ?: return
        val oldRequest: AudioFocusRequest?
        synchronized(lock) {
            activeChannels.clear()
            oldRequest = currentRequest
            currentRequest = null
            currentFocusGain = AudioManager.AUDIOFOCUS_NONE
            requestGeneration += 1
        }
        abandonQuietly(manager, oldRequest)
    }

    private fun requestFocus(manager: AudioManager, focusGain: Int, generation: Long) {
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            handleFocusChange(change, generation)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(
                    if (focusGain == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) {
                        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                    } else {
                        AudioAttributes.USAGE_MEDIA
                    },
                )
                .setContentType(
                    if (focusGain == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) {
                        AudioAttributes.CONTENT_TYPE_SPEECH
                    } else {
                        AudioAttributes.CONTENT_TYPE_MUSIC
                    },
                )
                .build()
            val req = AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(listener)
                .build()
            val result = runCatching { manager.requestAudioFocus(req) }
                .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            val channelsSnapshot = synchronized(lock) { activeChannels.values.toSet() }
            val focusMsg = "CarPlay audio focus request gain=$focusGain result=$result channels=$channelsSnapshot"
            Log.i(TAG, focusMsg)
            runCatching { AppLog.i("[CarPlayAudioFocus] $focusMsg") }
            synchronized(lock) {
                if (generation == requestGeneration && result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    currentRequest = req
                } else if (generation == requestGeneration) {
                    currentFocusGain = AudioManager.AUDIOFOCUS_NONE
                } else {
                    abandonQuietly(manager, req)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val result = runCatching {
                manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, focusGain)
            }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            val legacyMsg = "CarPlay audio focus request (legacy) gain=$focusGain result=$result"
            Log.i(TAG, legacyMsg)
            runCatching { AppLog.i("[CarPlayAudioFocus] $legacyMsg") }
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                synchronized(lock) {
                    if (generation == requestGeneration) currentFocusGain = AudioManager.AUDIOFOCUS_NONE
                }
            }
        }
    }

    internal fun handleFocusChange(change: Int, generation: Long) {
        val shouldYield: Boolean
        val oldRequest: AudioFocusRequest?
        synchronized(lock) {
            // Ignore callbacks from superseded internal focus transitions.
            if (generation != requestGeneration) return
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    shouldYield = true
                    oldRequest = currentRequest
                    currentRequest = null
                    currentFocusGain = AudioManager.AUDIOFOCUS_NONE
                    requestGeneration += 1
                }
                else -> {
                    shouldYield = false
                    oldRequest = null
                }
            }
        }
        if (shouldYield) {
            val yieldMsg = "CarPlay yielding audio focus to external car audio ($change)"
            Log.i(TAG, yieldMsg)
            runCatching { AppLog.w("[CarPlayAudioFocus] $yieldMsg") }
            audioManager?.let { abandonQuietly(it, oldRequest) }
            runCatching { onExternalFocusLoss() }
        }
    }

    private fun abandonQuietly(manager: AudioManager, request: AudioFocusRequest?) {
        if (request == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { manager.abandonAudioFocusRequest(request) }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
    }
}
