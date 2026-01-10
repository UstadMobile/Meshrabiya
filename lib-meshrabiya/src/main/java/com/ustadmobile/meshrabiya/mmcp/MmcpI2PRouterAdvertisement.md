package com.ustadmobile.meshrabiya.mmcp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MMCP message for I2P router advertisement messages using the enhanced gossip protocol.
 */
class MmcpI2PRouterAdvertisement(
    messageId: Int,
    val nodeId: String,
    val i2pCapabilities: I2PCapabilities,
    val routerInfo: I2PRouterInfo,
    val timestamp: Long = System.currentTimeMillis()
) : MmcpMessage(WHAT_I2P_ROUTER_ADVERTISEMENT, messageId) {

    override fun toBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        
        // Write node ID
        val nodeIdBytes = nodeId.toByteArray()
        dos.writeInt(nodeIdBytes.size)
        dos.write(nodeIdBytes)
        
        // Write I2P capabilities
        dos.writeBoolean(i2pCapabilities.hasI2PAndroidInstalled)
        dos.writeBoolean(i2pCapabilities.canRunRouter)
        dos.writeInt(i2pCapabilities.currentRole.ordinal)
        dos.writeInt(i2pCapabilities.routerStatus.ordinal)
        dos.writeInt(i2pCapabilities.tunnelCapacity)
        dos.writeInt(i2pCapabilities.activeTunnels)
        dos.writeInt(i2pCapabilities.netDbSize)
        dos.writeLong(i2pCapabilities.bandwidthLimits.upload)
        dos.writeLong(i2pCapabilities.bandwidthLimits.download)
        dos.writeInt(i2pCapabilities.participation.ordinal)
        
        // Write router info
        val versionBytes = routerInfo.version.toByteArray()
        dos.writeInt(versionBytes.size)
        dos.write(versionBytes)
        
        // Write timestamp
        dos.writeLong(timestamp)
        
        return baos.toByteArray()
    }

    companion object {
        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int = byteArray.size
        ): MmcpI2PRouterAdvertisement {
            val buffer = ByteBuffer.wrap(byteArray, offset, len).order(ByteOrder.BIG_ENDIAN)
            buffer.position(offset + 1) // Skip what byte
            
            val messageId = buffer.int
            
            // Read node ID
            val nodeIdSize = buffer.int
            val nodeIdBytes = ByteArray(nodeIdSize)
            buffer.get(nodeIdBytes)
            val nodeId = String(nodeIdBytes)
            
            // Read I2P capabilities
            val hasI2PAndroidInstalled = buffer.get() != 0.toByte()
            val canRunRouter = buffer.get() != 0.toByte()
            val currentRole = I2PRole.values()[buffer.int]
            val routerStatus = I2PRouterStatus.values()[buffer.int]
            val tunnelCapacity = buffer.int
            val activeTunnels = buffer.int
            val netDbSize = buffer.int
            val upload = buffer.long
            val download = buffer.long
            val participation = I2PParticipation.values()[buffer.int]
            
            val i2pCapabilities = I2PCapabilities(
                hasI2PAndroidInstalled = hasI2PAndroidInstalled,
                canRunRouter = canRunRouter,
                currentRole = currentRole,
                routerStatus = routerStatus,
                tunnelCapacity = tunnelCapacity,
                activeTunnels = activeTunnels,
                netDbSize = netDbSize,
                bandwidthLimits = BandwidthLimits(upload, download),
                participation = participation
            )
            
            // Read router info
            val versionSize = buffer.int
            val versionBytes = ByteArray(versionSize)
            buffer.get(versionBytes)
            val version = String(versionBytes)
            
            val routerInfo = I2PRouterInfo(version)
            
            // Read timestamp
            val timestamp = buffer.long
            
            return MmcpI2PRouterAdvertisement(
                messageId = messageId,
                nodeId = nodeId,
                i2pCapabilities = i2pCapabilities,
                routerInfo = routerInfo,
                timestamp = timestamp
            )
        }
    }
}
