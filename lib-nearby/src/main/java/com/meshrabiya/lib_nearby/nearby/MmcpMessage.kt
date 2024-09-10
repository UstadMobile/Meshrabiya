package com.meshrabiya.lib_nearby.nearby

import java.nio.ByteBuffer

abstract class MmcpMessage {
    abstract val messageType: Int
    abstract val fromAddress: Int
    abstract val toAddress: Int

    abstract fun toBytes(): ByteArray

    companion object {
        const val HEADER_SIZE = 12

        fun fromBytes(bytes: ByteArray): MmcpMessage {
            val buffer = ByteBuffer.wrap(bytes)
            val messageType = buffer.int
            return when (messageType) {
                NearbyStreamHeader.MESSAGE_TYPE -> NearbyStreamHeader.fromBytes(bytes)
                else -> throw IllegalArgumentException("Unknown message type: $messageType")
            }
        }
    }
}