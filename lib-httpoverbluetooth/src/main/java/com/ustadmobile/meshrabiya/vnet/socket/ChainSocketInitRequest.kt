package com.ustadmobile.meshrabiya.vnet.socket

import com.ustadmobile.meshrabiya.ext.getInet4Address
import com.ustadmobile.meshrabiya.ext.putInet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * When running a ChainSocket, the init request will be written first.
 */
data class ChainSocketInitRequest(
    val virtualDestAddr: InetAddress,
    val virtualDestPort: Int,
    val fromAddr: InetAddress,
    val hopCount: Byte = 0,
) {

    fun toBytes(): ByteArray {
        val byteArr = ByteArray(MESSAGE_SIZE)
        val byteBuffer = ByteBuffer.wrap(byteArr)
            .order(ByteOrder.BIG_ENDIAN)
        byteBuffer.putInet4Address(virtualDestAddr)
        byteBuffer.putInt(virtualDestPort)
        byteBuffer.putInet4Address(fromAddr)
        byteBuffer.put(hopCount)

        return byteArr
    }

    companion object {
        const val MESSAGE_SIZE = 4 + 4 + 4 + 1

        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0
        ) : ChainSocketInitRequest {
            val byteBuf = ByteBuffer.wrap(byteArray, offset, MESSAGE_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
            val virtualDestADdr = byteBuf.getInet4Address()
            val virtualDestPort = byteBuf.getInt()
            val fromAddr = byteBuf.getInet4Address()
            val hopCount = byteBuf.get()

            return ChainSocketInitRequest(
                virtualDestAddr = virtualDestADdr,
                virtualDestPort = virtualDestPort,
                fromAddr = fromAddr,
                hopCount = hopCount,
            )
        }
    }
}