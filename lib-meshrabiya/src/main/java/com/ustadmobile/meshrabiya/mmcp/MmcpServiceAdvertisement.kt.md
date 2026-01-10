package com.ustadmobile.meshrabiya.mmcp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MMCP message for service advertisements using the enhanced gossip protocol.
 */
class MmcpServiceAdvertisement(
    messageId: Int,
    val serviceId: String,
    val serviceName: String,
    val serviceType: ServiceType,
    val hostNodeId: String,
    val endpoints: Map<String, ServiceEndpoint>,
    val currentLoad: Float,
    val maxCapacity: Int,
    val timestamp: Long = System.currentTimeMillis()
) : MmcpMessage(WHAT_SERVICE_ADVERTISEMENT, messageId) {

    override fun toBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        
        // Write service ID
        val serviceIdBytes = serviceId.toByteArray()
        dos.writeInt(serviceIdBytes.size)
        dos.write(serviceIdBytes)
        
        // Write service name
        val serviceNameBytes = serviceName.toByteArray()
        dos.writeInt(serviceNameBytes.size)
        dos.write(serviceNameBytes)
        
        // Write service type
        dos.writeInt(serviceType.ordinal)
        
        // Write host node ID
        val hostNodeIdBytes = hostNodeId.toByteArray()
        dos.writeInt(hostNodeIdBytes.size)
        dos.write(hostNodeIdBytes)
        
        // Write endpoints
        dos.writeInt(endpoints.size)
        endpoints.forEach { (key, endpoint) ->
            val keyBytes = key.toByteArray()
            dos.writeInt(keyBytes.size)
            dos.write(keyBytes)
            
            val protocolBytes = endpoint.protocol.toByteArray()
            dos.writeInt(protocolBytes.size)
            dos.write(protocolBytes)
            
            val addressBytes = endpoint.address.toByteArray()
            dos.writeInt(addressBytes.size)
            dos.write(addressBytes)
            
            dos.writeInt(endpoint.port)
            
            val pathBytes = endpoint.path?.toByteArray() ?: ByteArray(0)
            dos.writeInt(pathBytes.size)
            if (pathBytes.isNotEmpty()) {
                dos.write(pathBytes)
            }
            
            dos.writeBoolean(endpoint.authRequired)
            dos.writeBoolean(endpoint.tlsSupported)
        }
        
        // Write load and capacity
        dos.writeFloat(currentLoad)
        dos.writeInt(maxCapacity)
        
        // Write timestamp
        dos.writeLong(timestamp)
        
        return baos.toByteArray()
    }

    companion object {
        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int = byteArray.size
        ): MmcpServiceAdvertisement {
            val buffer = ByteBuffer.wrap(byteArray, offset, len).order(ByteOrder.BIG_ENDIAN)
            buffer.position(offset + 1) // Skip what byte
            
            val messageId = buffer.int
            
            // Read service ID
            val serviceIdSize = buffer.int
            val serviceIdBytes = ByteArray(serviceIdSize)
            buffer.get(serviceIdBytes)
            val serviceId = String(serviceIdBytes)
            
            // Read service name
            val serviceNameSize = buffer.int
            val serviceNameBytes = ByteArray(serviceNameSize)
            buffer.get(serviceNameBytes)
            val serviceName = String(serviceNameBytes)
            
            // Read service type
            val serviceType = ServiceType.values()[buffer.int]
            
            // Read host node ID
            val hostNodeIdSize = buffer.int
            val hostNodeIdBytes = ByteArray(hostNodeIdSize)
            buffer.get(hostNodeIdBytes)
            val hostNodeId = String(hostNodeIdBytes)
            
            // Read endpoints
            val endpointsSize = buffer.int
            val endpoints = mutableMapOf<String, ServiceEndpoint>()
            repeat(endpointsSize) {
                val keySize = buffer.int
                val keyBytes = ByteArray(keySize)
                buffer.get(keyBytes)
                val key = String(keyBytes)
                
                val protocolSize = buffer.int
                val protocolBytes = ByteArray(protocolSize)
                buffer.get(protocolBytes)
                val protocol = String(protocolBytes)
                
                val addressSize = buffer.int
                val addressBytes = ByteArray(addressSize)
                buffer.get(addressBytes)
                val address = String(addressBytes)
                
                val port = buffer.int
                
                val pathSize = buffer.int
                val path = if (pathSize > 0) {
                    val pathBytes = ByteArray(pathSize)
                    buffer.get(pathBytes)
                    String(pathBytes)
                } else null
                
                val authRequired = buffer.get() != 0.toByte()
                val tlsSupported = buffer.get() != 0.toByte()
                
                endpoints[key] = ServiceEndpoint(
                    protocol = protocol,
                    address = address,
                    port = port,
                    path = path,
                    authRequired = authRequired,
                    tlsSupported = tlsSupported
                )
            }
            
            // Read load and capacity
            val currentLoad = buffer.float
            val maxCapacity = buffer.int
            
            // Read timestamp
            val timestamp = buffer.long
            
            return MmcpServiceAdvertisement(
                messageId = messageId,
                serviceId = serviceId,
                serviceName = serviceName,
                serviceType = serviceType,
                hostNodeId = hostNodeId,
                endpoints = endpoints,
                currentLoad = currentLoad,
                maxCapacity = maxCapacity,
                timestamp = timestamp
            )
        }
    }
}
