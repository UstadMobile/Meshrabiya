package com.ustadmobile.meshrabiya.mmcp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * @param replyToMessageId the message ID of the ping that this is a reply to
 */
class MmcpPong(
    messageId: Int,
    val replyToMessageId: Int,
): MmcpMessage(WHAT_PONG, messageId) {
    override fun toBytes() = headerAndPayloadToBytes(header,
        ByteBuffer.wrap(ByteArray(4))
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(replyToMessageId)
            .array())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MmcpPong) return false
        if (!super.equals(other)) return false

        if (replyToMessageId != other.replyToMessageId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + replyToMessageId
        return result
    }


    companion object {

        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int =  byteArray.size,
        ): MmcpPong {
            val (header, payload) = mmcpHeaderAndPayloadFromBytes(byteArray, offset, len)
            val replyToMessageId = ByteBuffer.wrap(payload)
                .order(ByteOrder.BIG_ENDIAN)
                .int

            return MmcpPong(header.messageId, replyToMessageId)
        }

    }
}