package com.ustadmobile.meshrabiya.vnet

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Virtual packet structure
 * Header:
 * To address (32bit int)
 * to port (16bit short)
 * from address (32bit int)
 * from port (16bit short)
 * hopCount (8bit byte)
 * maxHops (8bit byte)
 * payloadSize (16bit short)
 * payload (byte array where size = payloadSize)
 */

data class VirtualPacketHeader(
    val toAddr: Int,
    val toPort: Short,
    val fromAddr: Int,
    val fromPort: Short,
    val hopCount: Byte,
    val maxHops: Byte,
    val payloadSize: Int, //Max size should be in line with MTU e.g. 1500. Stored as short
) {

    init {
        if(payloadSize > MAX_PAYLOAD)
            throw IllegalArgumentException("Payload size must not be > $MAX_PAYLOAD")
    }

    fun toBytes(
        byteArray: ByteArray,
        offset: Int
    ) {
        val buf = ByteBuffer.wrap(byteArray, offset, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(toAddr)
        buf.putShort(toPort)
        buf.putInt(fromAddr)
        buf.putShort(fromPort)
        buf.put(hopCount)
        buf.put(maxHops)
        buf.putShort(payloadSize.toShort())
    }

    fun toBytes(): ByteArray {
        return ByteArray(HEADER_SIZE).also {
            toBytes(it, 0)
        }
    }

    companion object {

        @Suppress("LocalVariableName", "UsePropertyAccessSyntax")
        fun fromBytes(
            bytes: ByteArray,
            offset: Int = 0,
        ): VirtualPacketHeader {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            buf.position(offset)
            val _toAddr = buf.getInt()
            val _toPort = buf.getShort()
            val _fromAddr = buf.getInt()
            val _fromPort = buf.getShort()
            val _hopCount = buf.get()
            val _maxHops = buf.get()
            val _payloadSize = buf.getShort()

            return VirtualPacketHeader(
                toAddr = _toAddr,
                toPort = _toPort,
                fromAddr = _fromAddr,
                fromPort =  _fromPort,
                hopCount = _hopCount,
                maxHops = _maxHops,
                payloadSize = _payloadSize.toInt(),
            )
        }

        //Size of all header fields in bytes (as above)
        const val HEADER_SIZE = 16

        const val MAX_PAYLOAD = 2000


    }
}