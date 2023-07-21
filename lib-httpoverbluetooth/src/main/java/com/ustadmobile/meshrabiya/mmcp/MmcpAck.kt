package com.ustadmobile.meshrabiya.mmcp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An MMCP message may request an acknowledgement (ACK) reply - e.g. to be sure that the original
 * message was received.
 */
class MmcpAck(
    messageId: Int,
    val ackOfMessageId: Int,
) : MmcpMessage(
    what = WHAT_ACK,
    messageId = messageId,
){

    override fun toBytes() = headerAndPayloadToBytes(header,
        ByteBuffer.wrap(ByteArray(MESSAGE_SIZE))
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(ackOfMessageId)
            .array())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MmcpAck) return false
        if (!super.equals(other)) return false

        if (ackOfMessageId != other.ackOfMessageId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + ackOfMessageId
        return result
    }

    companion object {

        //Size = size of ackOfMessageId = 4 bytes
        const val MESSAGE_SIZE = 4

        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int =  byteArray.size,
        ): MmcpAck {
            val (header, payload) = mmcpHeaderAndPayloadFromBytes(byteArray, offset, len)
            val ackOfMessageId = ByteBuffer.wrap(payload).int
            return MmcpAck(messageId = header.messageId, ackOfMessageId = ackOfMessageId)
        }

    }
}
