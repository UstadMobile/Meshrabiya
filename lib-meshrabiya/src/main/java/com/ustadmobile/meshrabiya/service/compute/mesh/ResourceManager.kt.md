package com.ustadmobile.meshrabiya.service.compute.mesh

/**
 * Interface for resource management in the mesh.
 * Provides cluster resource state information for task scheduling.
 */
interface ResourceManager {
    fun getClusterResourceState(): ClusterResourceState
}

/**
 * Simple implementation of ResourceManager with basic resource state.
 */
class SimpleResourceManager : ResourceManager {
    override fun getClusterResourceState(): ClusterResourceState {
        return ClusterResourceState(
            cpuUtilization = 0.3f,
            memoryUtilization = 0.5f,
            availableNodes = 1
        )
    }
}
