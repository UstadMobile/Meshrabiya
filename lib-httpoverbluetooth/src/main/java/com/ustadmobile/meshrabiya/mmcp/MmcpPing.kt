package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.util.emptyByteArray

class MmcpPing(
    messageId: Int
): MmcpMessage(WHAT_PING, messageId) {

    override fun toBytes() = headerAndPayloadToBytes(header, emptyByteArray())

    //There is no need for equals/hashcode here because there is no real payload


    companion object {

        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int =  byteArray.size,
        ): MmcpPing {
            val (header, _) = mmcpHeaderAndPayloadFromBytes(byteArray, offset, len)
            return MmcpPing(header.messageId)
        }

    }

}