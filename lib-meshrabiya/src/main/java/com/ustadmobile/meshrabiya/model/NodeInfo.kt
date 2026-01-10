package com.ustadmobile.meshrabiya.model

/**
 * Represents information about a node in the mesh network.
 */
data class NodeInfo(
    val nodeId: String = "",
    val displayName: String = "",
    val isOnline: Boolean = false,
    val lastSeen: Long = 0L,
    val capabilities: List<String> = emptyList()
)