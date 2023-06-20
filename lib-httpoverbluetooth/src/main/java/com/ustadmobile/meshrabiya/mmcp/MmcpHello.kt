package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.util.emptyByteArray

class MmcpHello(
    messageId: Int,
) : MmcpMessage(WHAT_HELLO, messageId){

    override fun toBytes(): ByteArray {
        return headerAndPayloadToBytes(header, emptyByteArray())
    }

    companion object {

        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int =  byteArray.size,
        ): MmcpHello {
            val (header, _) = mmcpHeaderAndPayloadFromBytes(byteArray, offset, len)
            return MmcpHello(header.messageId)
        }

    }

}
