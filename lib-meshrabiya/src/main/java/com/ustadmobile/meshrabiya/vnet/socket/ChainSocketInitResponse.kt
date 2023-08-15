package com.ustadmobile.meshrabiya.vnet.socket

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ChainSocketInitResponse(
    val statusCode: Int,
) {

    fun toBytes(): ByteArray {
        return ByteArray(4).also {
            ByteBuffer.wrap(it)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(statusCode)
        }
    }

    companion object {

        const val MESSAGE_SIZE = 4

        fun fromBytes(byteArray: ByteArray, offset: Int): ChainSocketInitResponse{
            val byteBuf = ByteBuffer.wrap(byteArray, offset, MESSAGE_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
            val statusCode = byteBuf.int
            return ChainSocketInitResponse(statusCode)
        }
    }

}