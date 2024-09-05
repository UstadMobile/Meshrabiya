package com.meshrabiya.lib_nearby.nearby

import java.nio.ByteBuffer

data class NearbyStreamHeader(
    val streamId: Int,
    val isReply: Boolean,
    val payloadSize: Int
) {
    fun toBytes(): ByteArray {
        return ByteBuffer.allocate(12).apply {
            putInt(streamId)
            put(if (isReply) 1.toByte() else 0.toByte())
            putInt(payloadSize)
        }.array()
    }

    companion object {
        fun fromBytes(bytes: ByteArray): NearbyStreamHeader {
            val buffer = ByteBuffer.wrap(bytes)
            return NearbyStreamHeader(
                streamId = buffer.int,
                isReply = buffer.get() == 1.toByte(),
                payloadSize = buffer.int
            )
        }
    }
}