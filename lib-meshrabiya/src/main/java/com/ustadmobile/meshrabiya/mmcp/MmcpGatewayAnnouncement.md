package com.ustadmobile.meshrabiya.mmcp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MMCP message for announcing gateway capabilities to the mesh network.
 * This extends the MMCP protocol to support distributed gateway discovery.
 */
data class MmcpGatewayAnnouncement(
    val nodeId: String,
    val gatewayType: GatewayType,
    val capacity: BandwidthCapacity,
    val latency: NetworkLatency,
    val isActive: Boolean = true,
    val supportedProtocols: Set<String> = setOf("HTTP", "HTTPS"),
    val timestamp: Long = System.currentTimeMillis(),
    val requestedMessageId: Int
) : MmcpMessage(WHAT_GATEWAY_ANNOUNCEMENT, requestedMessageId) {

    enum class GatewayType(val value: Byte) {
        CLEARNET(1),
        TOR(2),
        I2P(3);
        
        companion object {
            fun fromValue(value: Byte): GatewayType {
                return values().find { it.value == value } 
                    ?: throw IllegalArgumentException("Unknown gateway type: $value")
            }
        }
    }
    
    data class BandwidthCapacity(
        val uploadMbps: Float,
        val downloadMbps: Float
    )
    
    data class NetworkLatency(
        val averageMs: Int,
        val jitterMs: Int
    )

    override fun toBytes(): ByteArray {
        val nodeIdBytes = nodeId.toByteArray(Charsets.UTF_8)
        val protocolsString = supportedProtocols.joinToString(",")
        val protocolsBytes = protocolsString.toByteArray(Charsets.UTF_8)
        
        val size = 4 + // messageId
                   2 + nodeIdBytes.size + // nodeId length + data
                   1 + // gatewayType
                   4 + 4 + // bandwidth (upload, download)
                   4 + 4 + // latency (average, jitter)
                   1 + // isActive
                   2 + protocolsBytes.size + // protocols length + data
                   8 // timestamp
        
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        
        buffer.putInt(messageId)
        buffer.putShort(nodeIdBytes.size.toShort())
        buffer.put(nodeIdBytes)
        buffer.put(gatewayType.value)
        buffer.putFloat(capacity.uploadMbps)
        buffer.putFloat(capacity.downloadMbps)
        buffer.putInt(latency.averageMs)
        buffer.putInt(latency.jitterMs)
        buffer.put(if (isActive) 1.toByte() else 0.toByte())
        buffer.putShort(protocolsBytes.size.toShort())
        buffer.put(protocolsBytes)
        buffer.putLong(timestamp)
        
        return buffer.array()
    }

    companion object {
        fun fromBytes(byteArray: ByteArray, offset: Int = 0, len: Int = byteArray.size): MmcpGatewayAnnouncement {
            val buffer = ByteBuffer.wrap(byteArray, offset, len).order(ByteOrder.BIG_ENDIAN)
            
            val messageId = buffer.getInt()
            
            val nodeIdLength = buffer.getShort().toInt()
            val nodeIdBytes = ByteArray(nodeIdLength)
            buffer.get(nodeIdBytes)
            val nodeId = String(nodeIdBytes, Charsets.UTF_8)
            
            val gatewayType = GatewayType.fromValue(buffer.get())
            
            val uploadMbps = buffer.getFloat()
            val downloadMbps = buffer.getFloat()
            val capacity = BandwidthCapacity(uploadMbps, downloadMbps)
            
            val averageMs = buffer.getInt()
            val jitterMs = buffer.getInt()
            val latency = NetworkLatency(averageMs, jitterMs)
            
            val isActive = buffer.get() == 1.toByte()
            
            val protocolsLength = buffer.getShort().toInt()
            val protocolsBytes = ByteArray(protocolsLength)
            buffer.get(protocolsBytes)
            val protocolsString = String(protocolsBytes, Charsets.UTF_8)
            val supportedProtocols = if (protocolsString.isEmpty()) {
                emptySet()
            } else {
                protocolsString.split(",").toSet()
            }
            
            val timestamp = buffer.getLong()
            
            return MmcpGatewayAnnouncement(
                nodeId = nodeId,
                gatewayType = gatewayType,
                capacity = capacity,
                latency = latency,
                isActive = isActive,
                supportedProtocols = supportedProtocols,
                timestamp = timestamp,
                requestedMessageId = messageId
            )
        }
    }
}
