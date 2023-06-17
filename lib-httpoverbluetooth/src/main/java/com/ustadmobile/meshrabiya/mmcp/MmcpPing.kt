package com.ustadmobile.meshrabiya.mmcp

class MmcpPing(
    val payload: ByteArray
): MmcpMessage(WHAT_PING) {

    override fun toBytes() = whatAndPayloadToBytes(WHAT_PING, payload)

    companion object {

        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int =  byteArray.size,
        ): MmcpPing {
            val (_, payload) = whatAndPayloadFromBytes(byteArray, offset, len)
            return MmcpPing(payload)
        }

    }

}