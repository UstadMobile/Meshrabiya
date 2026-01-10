package com.ustadmobile.meshrabiya.mmcp

import com.ustadmobile.meshrabiya.mmcp.NodeType
import com.ustadmobile.meshrabiya.mmcp.MeshRole
import com.ustadmobile.meshrabiya.mmcp.MmcpNodeAnnouncement
import com.ustadmobile.meshrabiya.mmcp.MmcpHeartbeat
import com.ustadmobile.meshrabiya.mmcp.getCurrentResourceCapabilities
import com.ustadmobile.meshrabiya.mmcp.getCurrentBatteryInfo
import com.ustadmobile.meshrabiya.mmcp.getCurrentThermalState
import com.ustadmobile.meshrabiya.mmcp.calculateFitnessScore

/**
 * Factory class for creating MMCP messages using the new enhanced structure.
 * This replaces the old MmcpOriginatorMessage with comprehensive message types.
 */
object MmcpMessageFactory {
    
    fun createNodeAnnouncement(
        messageId: Int,
        nodeId: String,
        nodeType: NodeType = NodeType.SMARTPHONE,
        fitnessScore: Float? = null,
        centralityScore: Float = 0.0f,
        meshRoles: Set<MeshRole> = setOf(MeshRole.MESH_PARTICIPANT),
        timestamp: Long = System.currentTimeMillis()
    ): MmcpNodeAnnouncement {
        // Get current device capabilities
        val resourceCapabilities = getCurrentResourceCapabilities()
        val batteryInfo = getCurrentBatteryInfo()
        val thermalState = getCurrentThermalState()
        
        // Calculate fitness score if not provided
        val calculatedFitnessScore = fitnessScore ?: calculateFitnessScore(resourceCapabilities)
        
        return MmcpNodeAnnouncement(
            nodeId = nodeId,
            nodeType = nodeType,
            fitnessScore = calculatedFitnessScore.coerceIn(0.0f, 1.0f),
            centralityScore = centralityScore.coerceIn(0.0f, 1.0f),
            meshRoles = meshRoles,
            resourceCapabilities = resourceCapabilities,
            batteryInfo = batteryInfo,
            thermalState = thermalState,
            timestamp = timestamp,

        )
    }
    
    fun createHeartbeat(
        messageId: Int,
        nodeId: String,
        timestamp: Long = System.currentTimeMillis()
    ): MmcpHeartbeat {
        return MmcpHeartbeat(messageId, nodeId, timestamp)
    }
    
    fun createSimpleNodeAnnouncement(
        messageId: Int,
        nodeId: String,
        fitnessScore: Float? = null,
        centralityScore: Float = 0.0f
    ): MmcpNodeAnnouncement {
        return createNodeAnnouncement(
            messageId = messageId,
            nodeId = nodeId,
            fitnessScore = fitnessScore,
            centralityScore = centralityScore
        )
    }
}
