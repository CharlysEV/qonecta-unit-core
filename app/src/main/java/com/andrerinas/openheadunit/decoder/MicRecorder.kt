package com.andrerinas.openheadunit.decoder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import kotlin.math.abs

class MicRecorder(private val micSampleRate: Int, private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private val settings = Settings(context)

    private val captureRateHz: Int
    private val decimationFactor: Int
    private val micBufferSize: Int
    private var micAudioBuf: ByteArray
    private var wireAudioBuf: ByteArray

    // Indicates whether mic recording is available on this device
    val isAvailable: Boolean

    init {
        val targetRate = 16000
        val directMin = AudioRecord.getMinBufferSize(targetRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (directMin > 0) {
            captureRateHz = targetRate
            decimationFactor = 1
            micBufferSize = maxOf(directMin, 4096)
            micAudioBuf = ByteArray(micBufferSize)
            wireAudioBuf = micAudioBuf
            isAvailable = true
        } else {
            val fallbackRate = if (micSampleRate > targetRate && micSampleRate % targetRate == 0) micSampleRate else 48000
            val fallbackMin = AudioRecord.getMinBufferSize(fallbackRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (fallbackMin > 0) {
                captureRateHz = fallbackRate
                decimationFactor = fallbackRate / targetRate
                micBufferSize = maxOf(fallbackMin, 4096 * decimationFactor)
                micAudioBuf = ByteArray(micBufferSize)
                wireAudioBuf = ByteArray(4096 * 2)
                isAvailable = true
                AppLog.w("MicRecorder: 16 kHz rejected ($directMin), capturing at $captureRateHz Hz and decimating $decimationFactor:1")
            } else {
                AppLog.w("MicRecorder: getMinBufferSize returned $directMin/$fallbackMin, mic recording unavailable")
                captureRateHz = targetRate
                decimationFactor = 1
                micBufferSize = 0
                micAudioBuf = ByteArray(0)
                wireAudioBuf = ByteArray(0)
                isAvailable = false
            }
        }
    }

    @Volatile private var threadMicAudioActive = false
    private var threadMicAudio: Thread? = null
    var listener: Listener? = null

    private var bluetoothScoStarted = false
    private var scoReceiver: BroadcastReceiver? = null
    private var decimateSum = 0
    private var decimateCount = 0

    companion object {
        const val SOURCE_BLUETOOTH_SCO = 100

        @Volatile
        var cachedWorkingSource: Int? = null

        @Volatile
        var cachedProbeResult: Boolean? = null

        fun peakAmplitude(buf: ByteArray, len: Int): Int {
            var peak = 0
            var i = 0
            while (i + 1 < len) {
                val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                val mag = if (sample == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else abs(sample)
                if (mag > peak) peak = mag
                i += 4
            }
            return peak
        }

        /**
         * Probes whether the head unit's AudioRecord can open and return non-zero PCM samples
         * (as opposed to missing RECORD_AUDIO permission or AudioFlinger zeroing the stream
         * because an OEM voice daemon like com.aispeech.lyra.daemon holds exclusive capture).
         */
        @Synchronized
        fun canHeadUnitCaptureAudio(context: Context): Boolean {
            if (PermissionChecker.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PermissionChecker.PERMISSION_GRANTED) {
                AppLog.w("MicRecorder: RECORD_AUDIO not granted; head-unit mic capture unavailable (phone mic will be used)")
                cachedProbeResult = false
                return false
            }
            cachedProbeResult?.let { return it }

            val rate = 16000
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) {
                cachedProbeResult = false
                return false
            }
            val bufSize = maxOf(minBuf, 2048)
            val probeBuf = ByteArray(bufSize)
            val candidates = intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.DEFAULT,
                MediaRecorder.AudioSource.CAMCORDER,
            )
            var anyInitialized = false
            var firstInitSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
            for (src in candidates) {
                var rec: AudioRecord? = null
                try {
                    rec = AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                    if (rec.state != AudioRecord.STATE_INITIALIZED) {
                        rec.release()
                        continue
                    }
                    if (!anyInitialized) {
                        anyInitialized = true
                        firstInitSource = src
                    }
                    rec.startRecording()
                    val n = rec.read(probeBuf, 0, minOf(probeBuf.size, 1600))
                    rec.stop()
                    rec.release()
                    rec = null
                    if (n > 0) {
                        val pk = peakAmplitude(probeBuf, n)
                        if (pk > 0) {
                            cachedWorkingSource = src
                            cachedProbeResult = true
                            AppLog.i("MicRecorder: AudioSource probe succeeded on source=$src (peak=$pk)")
                            return true
                        }
                    }
                } catch (_: Exception) {
                    try { rec?.release() } catch (_: Exception) {}
                }
            }
            if (anyInitialized) {
                // Initialized cleanly; allow live capture with automatic source rotation if peak stays 0
                cachedWorkingSource = firstInitSource
                cachedProbeResult = true
                return true
            }
            cachedProbeResult = false
            return false
        }
    }

    interface Listener {
        fun onMicDataAvailable(mic_buf: ByteArray, mic_audio_len: Int)
    }

    fun stop() {
        AppLog.i("MicRecorder: Stopping. Active: $threadMicAudioActive")

        threadMicAudioActive = false
        threadMicAudio?.interrupt()
        threadMicAudio = null

        releaseAudioRecordOnly()

        if (bluetoothScoStarted) {
            cleanupSco()
        }
    }

    private fun releaseAudioRecordOnly() {
        audioRecord?.apply {
            try {
                stop()
            } catch (_: Exception) {}
            try {
                release()
            } catch (e: Exception) {
                AppLog.e("MicRecorder: Error releasing AudioRecord", e)
            }
        }
        audioRecord = null

        try {
            aec?.release()
            ns?.release()
            agc?.release()
        } catch (e: Exception) {
            AppLog.e("MicRecorder: Error releasing AudioFX", e)
        }
        aec = null
        ns = null
        agc = null
    }

    private fun cleanupSco() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            scoReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        scoReceiver = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.communicationDevice
            if (device != null) {
                AppLog.i("MicRecorder: Clearing communication device: ${device.productName}")
            }
            audioManager.clearCommunicationDevice()
        } else {
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
        }

        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            AppLog.e("MicRecorder: Failed to restore audio mode to MODE_NORMAL", e)
        }

        bluetoothScoStarted = false
        AppLog.i("MicRecorder: Bluetooth SCO stopped and audio settings restored")
    }

    private fun decimateTo16k(src: ByteArray, srcLen: Int, dst: ByteArray): Int {
        if (decimationFactor <= 1) return srcLen
        var out = 0
        var i = 0
        while (i + 1 < srcLen && out + 1 < dst.size) {
            val sample = ((src[i + 1].toInt() shl 8) or (src[i].toInt() and 0xFF)).toShort().toInt()
            decimateSum += sample
            decimateCount++
            if (decimateCount == decimationFactor) {
                val avg = decimateSum / decimationFactor
                dst[out] = (avg and 0xFF).toByte()
                dst[out + 1] = ((avg shr 8) and 0xFF).toByte()
                out += 2
                decimateSum = 0
                decimateCount = 0
            }
            i += 2
        }
        return out
    }

    private fun getAudioSource(index: Int): Int {
        return when (index) {
            0 -> cachedWorkingSource ?: MediaRecorder.AudioSource.VOICE_RECOGNITION
            1 -> MediaRecorder.AudioSource.MIC
            2 -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            3 -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            4, SOURCE_BLUETOOTH_SCO -> SOURCE_BLUETOOTH_SCO
            else -> cachedWorkingSource ?: MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    private fun candidateSourcesFor(primary: Int): List<Int> {
        return listOf(
            primary,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.CAMCORDER,
        ).distinct()
    }

    fun start(): Int {
        if (!isAvailable) {
            AppLog.w("MicRecorder: Cannot start, mic not available on this device")
            return -4
        }

        if (PermissionChecker.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PermissionChecker.PERMISSION_GRANTED) {
            AppLog.e("MicRecorder: No RECORD_AUDIO permission")
            return -3
        }

        val configuredSource = getAudioSource(settings.micInputSource)

        return if (configuredSource == SOURCE_BLUETOOTH_SCO) {
            startScoAndRecord()
            0
        } else {
            startRecordingWithFallback(configuredSource)
        }
    }

    private fun startScoAndRecord() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val hasBluetoothPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PermissionChecker.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PermissionChecker.PERMISSION_GRANTED
        } else {
            true
        }

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) {
            AppLog.e("MicRecorder: Failed to set audio mode to MODE_IN_COMMUNICATION", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasBluetoothPermission) {
            AppLog.i("MicRecorder: API 31+. Using setCommunicationDevice for Bluetooth routing.")
            val devices = audioManager.availableCommunicationDevices
            val bluetoothDevice = devices.find {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (bluetoothDevice != null) {
                val success = audioManager.setCommunicationDevice(bluetoothDevice)
                AppLog.i("MicRecorder: setCommunicationDevice result: $success for device: ${bluetoothDevice.productName} (${bluetoothDevice.type})")
            } else {
                AppLog.w("MicRecorder: No Bluetooth SCO/BLE headset found in available communication devices.")
            }
            startRecordingWithFallback(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            bluetoothScoStarted = true
        } else {
            scoReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    AppLog.d("MicRecorder: SCO State change: $state")

                    if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                        AppLog.i("MicRecorder: SCO Connected. Starting AudioRecord.")
                        startRecordingWithFallback(MediaRecorder.AudioSource.MIC)
                    } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED && bluetoothScoStarted) {
                        AppLog.w("MicRecorder: SCO Disconnected unexpectedly.")
                        stop()
                    }
                }
            }

            ContextCompat.registerReceiver(context, scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_EXPORTED)

            AppLog.i("MicRecorder: Starting Bluetooth SCO...")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
            bluetoothScoStarted = true
        }
    }

    private fun openAudioRecordForSource(source: Int): AudioRecord? {
        return try {
            AppLog.i("MicRecorder: Initializing AudioRecord with source: ${getAudioSourceName(source)} ($source), SampleRate: $captureRateHz, BufferSize: $micBufferSize")
            val rec = AudioRecord(source, captureRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, micBufferSize)
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                AppLog.w("MicRecorder: AudioRecord failed to initialize for source ${getAudioSourceName(source)} ($source)")
                try { rec.release() } catch (_: Exception) {}
                null
            } else {
                val audioSessionId = rec.audioSessionId
                if (audioSessionId != 0) {
                    try {
                        if (settings.micNoiseSuppressor && NoiseSuppressor.isAvailable()) {
                            ns = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
                        }
                        if (settings.micAutoGainControl && AutomaticGainControl.isAvailable()) {
                            agc = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
                        }
                        if (settings.micEchoCanceler && AcousticEchoCanceler.isAvailable()) {
                            aec = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
                        }
                    } catch (e: Exception) {
                        AppLog.e("MicRecorder: Error initializing AudioFX", e)
                    }
                }
                rec.startRecording()
                rec
            }
        } catch (e: Exception) {
            AppLog.e("MicRecorder: Exception opening source ${getAudioSourceName(source)}", e)
            null
        }
    }

    private fun startRecordingWithFallback(primarySource: Int): Int {
        if (audioRecord != null && threadMicAudioActive) return 0
        val candidates = candidateSourcesFor(primarySource)
        var initialRecord: AudioRecord? = null
        var activeSourceIndex = 0
        for (i in candidates.indices) {
            val rec = openAudioRecordForSource(candidates[i])
            if (rec != null) {
                initialRecord = rec
                activeSourceIndex = i
                break
            }
        }
        if (initialRecord == null) {
            AppLog.e("MicRecorder: All candidate AudioSources failed to initialize")
            return -5
        }

        audioRecord = initialRecord
        decimateSum = 0
        decimateCount = 0
        threadMicAudioActive = true

        threadMicAudio = Thread({
            var currentIdx = activeSourceIndex
            var sourceStartedMs = SystemClock.elapsedRealtime()
            var sourcePeak = 0
            var totalBytesOnSource = 0L

            while (threadMicAudioActive) {
                val rec = audioRecord ?: break
                val len = try {
                    rec.read(micAudioBuf, 0, micBufferSize)
                } catch (e: Exception) {
                    AppLog.e("MicRecorder: read exception", e)
                    -1
                }
                if (!threadMicAudioActive) break

                if (len <= 0) {
                    // Try next fallback AudioSource if current source errors out
                    if (currentIdx + 1 < candidates.size) {
                        currentIdx++
                        val nextSrc = candidates[currentIdx]
                        AppLog.w("MicRecorder: read returned $len on ${getAudioSourceName(candidates[currentIdx - 1])}, switching to ${getAudioSourceName(nextSrc)}")
                        releaseAudioRecordOnly()
                        audioRecord = openAudioRecordForSource(nextSrc)
                        sourceStartedMs = SystemClock.elapsedRealtime()
                        sourcePeak = 0
                        totalBytesOnSource = 0L
                        continue
                    }
                    SystemClock.sleep(15)
                    continue
                }

                totalBytesOnSource += len
                val outBuf: ByteArray
                val outLen: Int
                if (decimationFactor > 1) {
                    outLen = decimateTo16k(micAudioBuf, len, wireAudioBuf)
                    outBuf = wireAudioBuf
                } else {
                    outLen = len
                    outBuf = micAudioBuf
                }

                if (outLen > 0) {
                    val pk = peakAmplitude(outBuf, outLen)
                    if (pk > sourcePeak) {
                        sourcePeak = pk
                        cachedWorkingSource = candidates[currentIdx]
                    }
                    listener?.onMicDataAvailable(outBuf, outLen)
                }

                // If 350 ms of capture on the current AudioSource produced pure digital zeros (peak == 0),
                // AudioFlinger or an OEM daemon (e.g. com.aispeech.lyra.daemon on C10) is silencing this source;
                // rotate immediately to the next candidate AudioSource!
                val elapsedOnSource = SystemClock.elapsedRealtime() - sourceStartedMs
                if (sourcePeak == 0 && elapsedOnSource >= 350L && totalBytesOnSource >= 6400L && currentIdx + 1 < candidates.size) {
                    val prevSrc = candidates[currentIdx]
                    currentIdx++
                    val nextSrc = candidates[currentIdx]
                    AppLog.w("MicRecorder: source ${getAudioSourceName(prevSrc)} returned digital silence (peak=0 after ${elapsedOnSource}ms), rotating to ${getAudioSourceName(nextSrc)}")
                    releaseAudioRecordOnly()
                    val nextRec = openAudioRecordForSource(nextSrc)
                    if (nextRec != null) {
                        audioRecord = nextRec
                        sourceStartedMs = SystemClock.elapsedRealtime()
                        sourcePeak = 0
                        totalBytesOnSource = 0L
                    }
                }
            }
            AppLog.i("MicRecorder: capture loop ended (peak=$sourcePeak, source=${getAudioSourceName(candidates.getOrElse(currentIdx) { primarySource })})")
        }, "mic_audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        return 0
    }

    private fun getAudioSourceName(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
            MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.REMOTE_SUBMIX -> "REMOTE_SUBMIX"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            SOURCE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            else -> "UNKNOWN ($source)"
        }
    }
}
