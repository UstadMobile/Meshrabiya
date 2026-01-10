package com.ustadmobile.meshrabiya.service.compute.mesh

import com.ustadmobile.meshrabiya.service.compute.model.*

/**
 * Represents the intelligence snapshot of the mesh network at a given time.
 * Includes node states, network metrics, resource availability, proximity matrix,
 * node specializations, active quorums, and timestamp.
 */
data class MeshIntelligence(
    val nodeStates: Map<String, NodeCapabilitySnapshot>,
    val networkMetrics: NetworkMetrics,
    val resourceAvailability: ClusterResourceState,
    val proximityMatrix: NetworkProximityMatrix,
    val specializations: Map<String, NodeSpecialization>,
    val activeQuorums: List<ActiveQuorum>,
    val timestamp: Long
)

/**
 * Snapshot of a node's capabilities and current state.
 */
data class NodeCapabilitySnapshot(
    val nodeId: String,
    val resourceCapabilities: ResourceCapabilities,
    val batteryInfo: BatteryInfo,
    val thermalState: ThermalState,
    val networkLatency: NetworkLatencyInfo,
    val reliabilityScore: Float,
    val currentLoad: Float,
    val availableForCompute: Boolean
)

/**
 * Describes the hardware and resource capabilities of a node.
 */
data class ResourceCapabilities(
    val availableRAMMB: Int,
    val availableCPU: Float,
    val storageGB: Float,
    val networkBandwidthMbps: Float,
    val supportsGPU: Boolean,
    val supportsNPU: Boolean
)

/**
 * Describes a node's specialization for distributed compute.
 */
data class NodeSpecialization(
    val hasGPUAcceleration: Boolean,
    val hasNPUAcceleration: Boolean,
    val hasPythonOptimizations: Boolean,
    val supportedPythonLibraries: Set<PythonLibrary>,
    // val supportedLiteRTModels: Set<String>,
    val specializedCapabilities: Set<SpecializedCapability>,
    val storageCapabilityGB: Float
)

/**
 * Matrix representing network proximity (latency) between nodes.
 */
data class NetworkProximityMatrix(private val matrix: Map<Pair<String, String>, Int>) {
    fun getLatency(node1: String, node2: String): Int {
        return matrix[Pair(node1, node2)] ?: matrix[Pair(node2, node1)] ?: Int.MAX_VALUE
    }
}

/**
 * Cluster-wide resource state for mesh compute.
 */
data class ClusterResourceState(
    val availableNodes: Int,
    val totalRAMMB: Long,
    val averageRAMMB: Int,
    val totalStorageGB: Long,
    val averageCPULoad: Float,
    val nodesWithGPU: Int,
    val nodesWithNPU: Int
)

/**
 * Represents an active quorum in the mesh network.
 */
data class ActiveQuorum(
    val quorumId: String,
    val quorumType: QuorumType,
    val memberNodes: Set<String>,
    val currentTask: String?,
    val resourcesAllocated: ResourceAllocation
)

/**
 * Allocation of resources for a quorum or job.
 */
data class ResourceAllocation(
    val allocatedRAMMB: Long,
    val allocatedCPU: Float,
    val allocatedStorage: Long
)