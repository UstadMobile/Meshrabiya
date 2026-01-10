package com.ustadmobile.meshrabiya.service.compute.model

import com.ustadmobile.meshrabiya.service.compute.model.ResourceMetrics
import kotlinx.serialization.Serializable

/**
 * ServiceEntry
 *
 * Represents a discoverable service in the mesh network, including compute capability metadata.
 * Extends traditional service discovery (host, port, category) with distributed compute capabilities
 * (task types, job types, concurrency limits, resource capacity).
 *
 * Phase 3.2: Service Library Enhancements
 * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md Section 7.1
 */
@Serializable
data class ServiceEntry(
    val serviceName: String,
    val host: String,
    val port: Int,
    val category: ServiceCategory,
    
    // Compute capability fields (Phase 3.2)
    val supportsCompute: Boolean = false,
    val taskTypes: List<TaskType> = emptyList(),
    val jobTypes: List<JobType> = emptyList(),
    val maxConcurrentTasks: Int = 1,
    val estimatedCapacity: ResourceMetrics? = null
)

/**
 * ServiceCategory
 *
 * Categorizes services by their primary function.
 */
@Serializable
enum class ServiceCategory {
    COMPUTE,        // Distributed compute execution
    STORAGE,        // Distributed storage
    DISCOVERY,      // Service discovery
    NETWORKING,     // Network infrastructure
    COORDINATION    // Task coordination and scheduling
}

// Note: ResourceMetrics is now imported from MeshComputeDataDefinitions.kt
// to avoid redeclaration errors

