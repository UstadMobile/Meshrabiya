package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.util.emptyByteArray

/**
 * Note: the pong messageId should match the messageId from the sender.
 */
class MmcpPong(
    messageId: Int,
): MmcpMessage(WHAT_PONG, messageId) {
    override fun toBytes() = headerAndPayloadToBytes(header, emptyByteArray())

    companion object {

        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int =  byteArray.size,
        ): MmcpPong {
            val (header, _) = mmcpHeaderAndPayloadFromBytes(byteArray, offset, len)
            return MmcpPong(header.messageId)
        }

    }
}