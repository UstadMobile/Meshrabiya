package com.ustadmobile.meshrabiya.service.compute.model

import kotlinx.serialization.Serializable

/**
 * Response data from a node when it receives a compute task request broadcast.
 * Includes node capabilities (including ML Kit) for requesting node to evaluate.
 * 
 * This data class is wrapped by MeshEcosystemMessage.ComputeNodeResponseMessage for
 * serialization and transmission across the mesh network.
 */
@Serializable
data class ComputeNodeResponse(
    val nodeAddress: Int,
    val available: Boolean,
    val estimatedLatencyMs: Long,
    val currentLoad: Float,  // 0.0 = idle, 1.0 = fully loaded
    
    // ML Kit capabilities (from EmergentRoleManager)
    val mlKitFeatures: List<String> = emptyList(),  // ["text-recognition", "face-detection", etc.]
    val mlKitCustomSupport: Boolean = false,        // Can handle custom ML Kit models (>3GB RAM)
    
    val requestId: String,  // Correlation with task request
    val timestamp: Long = System.currentTimeMillis()
)
