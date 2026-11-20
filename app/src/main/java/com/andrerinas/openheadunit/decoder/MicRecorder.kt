package com.andrerinas.openheadunit.decoder

import android.content.Context
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Safe no-op MicRecorder for Qonecta Unit Core (Phase 0).
 *
 * On Leapmotor C10 (LeapOS), probing multiple AudioRecord sources (especially VOICE_COMMUNICATION)
 * or running a MAX_PRIORITY capture rotation loop collides with the OEM voice daemon
 * (com.aispeech.lyra.daemon) and Qualcomm Audio HAL / CarAudioService.
 *
 * Returning false from [canHeadUnitCaptureAudio] makes Android Auto omit Channel.ID_MIC and
 * cleanly record voice commands using the phone's own microphone with zero head-unit Audio HAL load.
 */
class MicRecorder(private val sampleRate: Int, private val context: Context) {

    interface Listener {
        fun onMicDataAvailable(mic_buf: ByteArray, mic_audio_len: Int)
    }

    var listener: Listener? = null

    fun start(): Int {
        AppLog.i("MicRecorder: Head-unit mic capture disabled in Core mode (using phone mic).")
        return -1
    }

    fun stop() {
        // No-op: never allocates AudioRecord or Bluetooth SCO in Core mode.
    }

    companion object {
        const val SOURCE_BLUETOOTH_SCO = 4

        fun canHeadUnitCaptureAudio(context: Context): Boolean = false
    }
}
