package com.ustadmobile.meshrabiya.mmcp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MMCP message for heartbeat messages using the enhanced gossip protocol.
 */
class MmcpHeartbeat(
    messageId: Int,
    val nodeId: String,
    val timestamp: Long = System.currentTimeMillis()
) : MmcpMessage(WHAT_HEARTBEAT, messageId) {

    override fun toBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        
        // Write node ID
        val nodeIdBytes = nodeId.toByteArray()
        dos.writeInt(nodeIdBytes.size)
        dos.write(nodeIdBytes)
        
        // Write timestamp
        dos.writeLong(timestamp)
        
        return baos.toByteArray()
    }

    companion object {
        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int = byteArray.size
        ): MmcpHeartbeat {
            val buffer = ByteBuffer.wrap(byteArray, offset, len).order(ByteOrder.BIG_ENDIAN)
            buffer.position(offset + 1) // Skip what byte
            
            val messageId = buffer.int
            
            // Read node ID
            val nodeIdSize = buffer.int
            val nodeIdBytes = ByteArray(nodeIdSize)
            buffer.get(nodeIdBytes)
            val nodeId = String(nodeIdBytes)
            
            // Read timestamp
            val timestamp = buffer.long
            
            return MmcpHeartbeat(
                messageId = messageId,
                nodeId = nodeId,
                timestamp = timestamp
            )
        }
    }
}
