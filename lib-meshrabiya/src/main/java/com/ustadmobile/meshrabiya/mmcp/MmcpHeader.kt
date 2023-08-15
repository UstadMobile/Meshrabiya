package com.ustadmobile.meshrabiya.mmcp

import java.nio.ByteBuffer
import java.nio.ByteOrder


data class MmcpHeader(
    val what: Byte,
    val messageId: Int,
) {

    fun toBytes(
        byteArray: ByteArray,
        offset: Int
    ) {
        val headerBuf = ByteBuffer
            .wrap(byteArray, offset, MmcpMessage.MMCP_HEADER_LEN)
            .order(ByteOrder.BIG_ENDIAN)
        headerBuf.put(what)
        headerBuf.putInt(messageId)
    }



    companion object {

        fun fromBytes(
            byteArray: ByteArray,
            offset: Int
        ): MmcpHeader {
            val headerBuf = ByteBuffer
                .wrap(byteArray, offset, MmcpMessage.MMCP_HEADER_LEN)
                .order(ByteOrder.BIG_ENDIAN)
            val what = headerBuf.get()
            val messageId = headerBuf.int

            return MmcpHeader(what, messageId)
        }

    }
}