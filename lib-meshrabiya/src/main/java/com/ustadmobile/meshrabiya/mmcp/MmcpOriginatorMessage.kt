package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.vnet.MeshRole
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MMCP Originating Message - broadcasts node presence and routing information.
 * 
 * This is the canonical message type from the official design, enhanced with
 * topology and centrality fields for distributed mesh intelligence.
 * 
 * Official fields (from OriginatingMessageManager_official.md):
 * - messageId: Unique identifier
 * - sentTime: Timestamp for message freshness
 * - pingTimeSum: Cumulative latency across hops
 * - connectConfig: WiFi connection configuration
 * 
 * Enhanced fields (for EmergentRoleManager integration):
 * - neighbors: Direct neighbor addresses for topology building
 * - centralityScore: BFS centrality from EmergentRoleManager
 * - fitnessScore: Node capability assessment (0.0-1.0)
 * - meshRoles: Currently assigned mesh roles
 */
class MmcpOriginatorMessage(
    messageId: Int,
    
    // === OFFICIAL FIELDS (from canonical design) ===
    val sentTime: Long,
    val pingTimeSum: Short = 0,
    val connectConfig: Any? = null,  // WiFi ConnectConfig (platform-specific)
    
    // === ENHANCED FIELDS (for topology/centrality) ===
    val neighbors: List<Int> = emptyList(),  // Direct neighbor virtual addresses
    val centralityScore: Float = 0f,         // BFS centrality score
    val fitnessScore: Float = 0f,            // Node fitness (0.0-1.0)
    val meshRoles: Set<MeshRole> = emptySet(), // Current mesh roles
    
) : MmcpMessage(WHAT_ORIGINATOR, messageId) {

    /**
     * Create updated message with incremented ping time (called at each hop).
     * This preserves the official behavior where pingTimeSum accumulates.
     */
    fun copyWithPingTimeIncrement(connectionPingTime: Long): MmcpOriginatorMessage {
        val newPingTimeSum = (pingTimeSum + connectionPingTime.toInt())
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
        
        return MmcpOriginatorMessage(
            messageId = messageId,
            sentTime = sentTime,
            pingTimeSum = newPingTimeSum,
            connectConfig = connectConfig,
            neighbors = neighbors,
            centralityScore = centralityScore,
            fitnessScore = fitnessScore,
            meshRoles = meshRoles
        )
    }

    override fun toBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        
        // Write official fields
        dos.writeLong(sentTime)
        dos.writeShort(pingTimeSum.toInt())
        
        // Write connectConfig (simplified - null for now)
        dos.writeBoolean(connectConfig != null)
        // TODO: Serialize connectConfig if present
        
        // Write enhanced fields
        dos.writeInt(neighbors.size)
        neighbors.forEach { dos.writeInt(it) }
        
        dos.writeFloat(centralityScore)
        dos.writeFloat(fitnessScore)
        
        dos.writeInt(meshRoles.size)
        meshRoles.forEach { dos.writeByte(it.ordinal) }
        
        return baos.toByteArray()
    }

    companion object {
        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int = byteArray.size
        ): MmcpOriginatorMessage {
            val buffer = ByteBuffer.wrap(byteArray, offset, len).order(ByteOrder.BIG_ENDIAN)
            buffer.position(offset + 1) // Skip 'what' byte
            
            val messageId = buffer.int
            val sentTime = buffer.long
            val pingTimeSum = buffer.short
            
            // Read connectConfig
            val hasConnectConfig = buffer.get() != 0.toByte()
            val connectConfig = if (hasConnectConfig) {
                // TODO: Deserialize connectConfig
                null
            } else null
            
            // Read enhanced fields
            val neighborCount = buffer.int
            val neighbors = List(neighborCount) { buffer.int }
            
            val centralityScore = buffer.float
            val fitnessScore = buffer.float
            
            val meshRolesCount = buffer.int
            val meshRoles = (0 until meshRolesCount).map {
                MeshRole.values()[buffer.get().toInt()]
            }.toSet()
            
            return MmcpOriginatorMessage(
                messageId = messageId,
                sentTime = sentTime,
                pingTimeSum = pingTimeSum,
                connectConfig = connectConfig,
                neighbors = neighbors,
                centralityScore = centralityScore,
                fitnessScore = fitnessScore,
                meshRoles = meshRoles
            )
        }
    }
}
