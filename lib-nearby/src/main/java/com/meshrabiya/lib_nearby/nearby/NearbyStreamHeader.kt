package com.meshrabiya.lib_nearby.nearby

import java.nio.ByteBuffer

class NearbyStreamHeader(
    val streamId: Int,
    val isReply: Boolean,
    val payloadSize: Int,
    override val fromAddress: Int,
    override val toAddress: Int
) : MmcpMessage() {

    override val messageType: Int = MESSAGE_TYPE

    override fun toBytes(): ByteArray {
        return ByteBuffer.allocate(HEADER_SIZE).apply {
            putInt(messageType)
            putInt(fromAddress)
            putInt(toAddress)
            putInt(streamId)
            put(isReply.toByte())
            putInt(payloadSize)
        }.array()
    }

    companion object {
        const val MESSAGE_TYPE = 1
        const val HEADER_SIZE = MmcpMessage.HEADER_SIZE + 13

        fun fromBytes(bytes: ByteArray): NearbyStreamHeader {
            val buffer = ByteBuffer.wrap(bytes)
            buffer.position(MmcpMessage.HEADER_SIZE)
            return NearbyStreamHeader(
                streamId = buffer.int,
                isReply = buffer.get() != 0.toByte(),
                payloadSize = buffer.int,
                fromAddress = ByteBuffer.wrap(bytes).getInt(4),
                toAddress = ByteBuffer.wrap(bytes).getInt(8)
            )
        }
    }
}

fun Boolean.toByte(): Byte = if (this) 1 else 0