package com.ustadmobile.meshrabiya.mmcp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MMCP message for network metrics messages using the enhanced gossip protocol.
 */
class MmcpNetworkMetrics(
    messageId: Int,
    val nodeId: String,
    val metrics: Map<String, Float>,
    val timestamp: Long = System.currentTimeMillis()
) : MmcpMessage(WHAT_NETWORK_METRICS, messageId) {

    override fun toBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        
        // Write node ID
        val nodeIdBytes = nodeId.toByteArray()
        dos.writeInt(nodeIdBytes.size)
        dos.write(nodeIdBytes)
        
        // Write metrics
        dos.writeInt(metrics.size)
        metrics.forEach { (key, value) ->
            val keyBytes = key.toByteArray()
            dos.writeInt(keyBytes.size)
            dos.write(keyBytes)
            dos.writeFloat(value)
        }
        
        // Write timestamp
        dos.writeLong(timestamp)
        
        return baos.toByteArray()
    }

    companion object {
        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int = byteArray.size
        ): MmcpNetworkMetrics {
            val buffer = ByteBuffer.wrap(byteArray, offset, len).order(ByteOrder.BIG_ENDIAN)
            buffer.position(offset + 1) // Skip what byte
            
            val messageId = buffer.int
            
            // Read node ID
            val nodeIdSize = buffer.int
            val nodeIdBytes = ByteArray(nodeIdSize)
            buffer.get(nodeIdBytes)
            val nodeId = String(nodeIdBytes)
            
            // Read metrics
            val metricsSize = buffer.int
            val metrics = mutableMapOf<String, Float>()
            repeat(metricsSize) {
                val keySize = buffer.int
                val keyBytes = ByteArray(keySize)
                buffer.get(keyBytes)
                val key = String(keyBytes)
                
                val value = buffer.float
                metrics[key] = value
            }
            
            // Read timestamp
            val timestamp = buffer.long
            
            return MmcpNetworkMetrics(
                messageId = messageId,
                nodeId = nodeId,
                metrics = metrics,
                timestamp = timestamp
            )
        }
    }
}
