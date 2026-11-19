package com.andrerinas.openheadunit.aap

import android.content.Context
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.decoder.MicRecorder
import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

internal class AapMessageHandlerType(
        private val transport: AapTransport,
        recorder: MicRecorder,
        private val aapAudio: AapAudio,
        private val aapVideo: AapVideo,
        settings: Settings,
        context: Context,
        onAaMediaMetadata: ((MediaPlayback.MediaMetaData) -> Unit)? = null,
        onAaPlaybackStatus: ((MediaPlayback.MediaPlaybackStatus) -> Unit)? = null) : AapMessageHandler {

    private val aapControl: AapControl = AapControlGateway(transport, recorder, aapAudio, settings, context)
    private val mediaPlayback = AapMediaPlayback(onAaMediaMetadata, onAaPlaybackStatus)
    private val aapNavigation = AapNavigation(context, settings)

    @Throws(AapMessageHandler.HandleException::class)
    override fun handle(message: AapMessage) {

        // Every decrypted inbound message passes through here, on every channel, which makes this
        // the one place that can say the link is alive rather than just that the picture is moving.
        // See AapTransport.lastMessageReceivedMs for why that distinction matters.
        transport.noteMessageReceived()

        val msgType = message.type
        val flags = message.flags
        val isFirstOrSingle = (flags.toInt() and 0x01) != 0

        // 1. Try processing as Video stream first (ID_VID)
        // High priority for the smoothest possible display.
        // Control messages on ID_VID are always single-fragment (flags == FLAG_SINGLE) with
        // msgType >= 32768 (e.g. VIDEO_FOCUS_INDICATION 32776). Passing them into the fragment
        // assembler while a multi-fragment video frame (flags = 9, 8, ..., 10) is mid-assembly
        // would falsely trigger TRUNCATED_PREVIOUS and discard the in-flight video frame!
        if (message.channel == Channel.ID_VID &&
            (!isFirstOrSingle || flags.toInt() != VideoFragmentAssembler.FLAG_SINGLE || msgType in 0..1)
        ) {
            if (aapVideo.process(message)) {
                if (isFirstOrSingle && (msgType == 0 || msgType == 1)) {
                    transport.onMainVideoData()
                    transport.sendMediaAck(message.channel)
                }
                return
            }
        }

        // 1b. Core mode: drop any stray secondary/cluster video traffic without ever initializing
        // the secondary MediaCodec pipeline (protects C10 SA8155P video decoder pool).
        if (message.channel == Channel.ID_CLUSTER_VID) {
            return
        }

        // 2. Try processing as Audio stream (Speech, System, Media)
        if (message.isAudio) {
            if (aapAudio.process(message)) {
                // Send ACK AFTER processing only for actual media data packets (first/single)
                if (isFirstOrSingle && (msgType == 0 || msgType == 1)) {
                    transport.sendMediaAck(message.channel)
                }
                return
            }
        }

        // 3. Media Playback Status (separate channel)
        // Continuation fragments (flags 0x08/0x0A) carry raw payload bytes at offsets 0..1 rather
        // than a message type header, so they MUST always reach mediaPlayback regardless of msgType.
        if (message.channel == Channel.ID_MPB && (!isFirstOrSingle || msgType > 31)) {
            mediaPlayback.process(message)
            return
        }

        // 4. Navigation (turn-by-turn from any AA nav app)
        // Process payload messages (>31) and any continuation fragments on NAV channel.
        // Control/handshake messages on NAV channel must pass through to AapControl.
        if (message.channel == Channel.ID_NAV && (!isFirstOrSingle || msgType > 31)) {
            if (aapNavigation.process(message)) {
                return
            }
        }

        // 5. Control Message Fallback
        if (msgType in 0..31 || msgType in 32768..32799 || msgType in 65504..65535) {
            try {
                aapControl.execute(message)
            } catch (e: Exception) {
                AppLog.e(e)
                throw AapMessageHandler.HandleException(e)
            }
        } else {
            AppLog.e("Unknown msg_type: %d, flags: %d, channel: %d", msgType, flags, message.channel)
        }
    }
}
