package com.ustadmobile.meshrabiya.mmcp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MMCP message for storage advertisement messages using the enhanced gossip protocol.
 */
class MmcpStorageAdvertisement(
    messageId: Int,
    val nodeId: String,
    val storageCapabilities: StorageCapabilities,
    val timestamp: Long = System.currentTimeMillis()
) : MmcpMessage(WHAT_STORAGE_ADVERTISEMENT, messageId) {

    override fun toBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        
        // Write node ID
        val nodeIdBytes = nodeId.toByteArray()
        dos.writeInt(nodeIdBytes.size)
        dos.write(nodeIdBytes)
        
        // Write storage capabilities
        dos.writeLong(storageCapabilities.totalOffered)
        dos.writeLong(storageCapabilities.currentlyUsed)
        dos.writeInt(storageCapabilities.replicationFactor)
        dos.writeBoolean(storageCapabilities.compressionSupported)
        dos.writeBoolean(storageCapabilities.encryptionSupported)
        
        // Write access patterns
        dos.writeInt(storageCapabilities.accessPatterns.size)
        storageCapabilities.accessPatterns.forEach { pattern ->
            dos.writeInt(pattern.ordinal)
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
        ): MmcpStorageAdvertisement {
            val buffer = ByteBuffer.wrap(byteArray, offset, len).order(ByteOrder.BIG_ENDIAN)
            buffer.position(offset + 1) // Skip what byte
            
            val messageId = buffer.int
            
            // Read node ID
            val nodeIdSize = buffer.int
            val nodeIdBytes = ByteArray(nodeIdSize)
            buffer.get(nodeIdBytes)
            val nodeId = String(nodeIdBytes)
            
            // Read storage capabilities
            val totalOffered = buffer.long
            val currentlyUsed = buffer.long
            val replicationFactor = buffer.int
            val compressionSupported = buffer.get() != 0.toByte()
            val encryptionSupported = buffer.get() != 0.toByte()
            
            // Read access patterns
            val accessPatternsSize = buffer.int
            val accessPatterns = mutableSetOf<AccessPattern>()
            repeat(accessPatternsSize) {
                accessPatterns.add(AccessPattern.values()[buffer.int])
            }
            
            val storageCapabilities = StorageCapabilities(
                totalOffered = totalOffered,
                currentlyUsed = currentlyUsed,
                replicationFactor = replicationFactor,
                compressionSupported = compressionSupported,
                encryptionSupported = encryptionSupported,
                accessPatterns = accessPatterns
            )
            
            // Read timestamp
            val timestamp = buffer.long
            
            return MmcpStorageAdvertisement(
                messageId = messageId,
                nodeId = nodeId,
                storageCapabilities = storageCapabilities,
                timestamp = timestamp
            )
        }
    }
}
