package com.ustadmobile.meshrabiya.mmcp

class MmcpPong(
    val payload: ByteArray,
): MmcpMessage(WHAT_PONG) {
    override fun toBytes() = whatAndPayloadToBytes(WHAT_PONG, payload)

    companion object {

        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int =  byteArray.size,
        ): MmcpPong {
            val (_, payload) = whatAndPayloadFromBytes(byteArray, offset, len)
            return MmcpPong(payload)
        }

    }
}