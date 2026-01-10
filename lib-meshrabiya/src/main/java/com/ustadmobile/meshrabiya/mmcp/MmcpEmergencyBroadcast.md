package com.ustadmobile.meshrabiya.mmcp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MMCP message for emergency broadcast messages using the enhanced gossip protocol.
 */
class MmcpEmergencyBroadcast(
    messageId: Int,
    val sourceNodeId: String,
    val emergencyMessage: String,
    val timestamp: Long = System.currentTimeMillis()
) : MmcpMessage(WHAT_EMERGENCY_BROADCAST, messageId) {

    override fun toBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        
        // Write source node ID
        val sourceNodeIdBytes = sourceNodeId.toByteArray()
        dos.writeInt(sourceNodeIdBytes.size)
        dos.write(sourceNodeIdBytes)
        
        // Write emergency message
        val emergencyMessageBytes = emergencyMessage.toByteArray()
        dos.writeInt(emergencyMessageBytes.size)
        dos.write(emergencyMessageBytes)
        
        // Write timestamp
        dos.writeLong(timestamp)
        
        return baos.toByteArray()
    }

    companion object {
        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int = byteArray.size
        ): MmcpEmergencyBroadcast {
            val buffer = ByteBuffer.wrap(byteArray, offset, len).order(ByteOrder.BIG_ENDIAN)
            buffer.position(offset + 1) // Skip what byte
            
            val messageId = buffer.int
            
            // Read source node ID
            val sourceNodeIdSize = buffer.int
            val sourceNodeIdBytes = ByteArray(sourceNodeIdSize)
            buffer.get(sourceNodeIdBytes)
            val sourceNodeId = String(sourceNodeIdBytes)
            
            // Read emergency message
            val emergencyMessageSize = buffer.int
            val emergencyMessageBytes = ByteArray(emergencyMessageSize)
            buffer.get(emergencyMessageBytes)
            val emergencyMessage = String(emergencyMessageBytes)
            
            // Read timestamp
            val timestamp = buffer.long
            
            return MmcpEmergencyBroadcast(
                messageId = messageId,
                sourceNodeId = sourceNodeId,
                emergencyMessage = emergencyMessage,
                timestamp = timestamp
            )
        }
    }
}
