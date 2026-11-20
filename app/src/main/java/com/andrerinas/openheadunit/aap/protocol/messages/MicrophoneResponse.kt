package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.AapMessage
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.google.protobuf.Message

/**
 * Acknowledges the phone's microphone Open/Close request with status and a capture session id.
 * A successful Open response is queued before DATA, so the phone can associate the uplink with
 * that session. Omitting it would leave a peer waiting for confirmation even if capture ran.
 */
class MicrophoneResponse(status: Int, sessionId: Int)
    : AapMessage(Channel.ID_MIC, Media.MsgType.MEDIA_MESSAGE_MICROPHONE_RESPONSE_VALUE,
        makeProto(status, sessionId)) {

    companion object {
        private fun makeProto(status: Int, sessionId: Int): Message {
            return Media.MicrophoneResponse.newBuilder().apply {
                this.status = status
                this.sessionId = sessionId
            }.build()
        }
    }
}
