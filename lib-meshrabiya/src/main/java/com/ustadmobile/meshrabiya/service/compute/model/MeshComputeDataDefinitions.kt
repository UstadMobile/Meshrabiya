package com.ustadmobile.meshrabiya.service.compute.model
import kotlinx.serialization.Serializable
import java.util.UUID
import com.ustadmobile.meshrabiya.storage.FileReference
import com.ustadmobile.meshrabiya.storage.RecipientEntry
/**
 * MESH COMPUTE DATA DEFINITIONS
 * 
 * Centralized data structures for distributed compute system.
 * Created as part of Phase 1.2 (Data Structure Refactoring).
 * 
 * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md Section 1.1
 */

/**
 * TASK EXECUTION CONTEXT
 * 
 * Bundles all information needed to execute a task on a compute node
 */
@Serializable
data class TaskExecutionContext(
    val taskId: String,
    val executorType: String,  // Executor class name: JSExecutor, JVMExecutor, MLNativeExecutor
    // val jobType: String,       // IMAGE_PROCESSING, VIDEO_PROCESSING, DATA_ANALYSIS, etc. (deprecated enums removed)
    val codeBundle: ByteArray,           // Language-agnostic archive
    val inputManifest: List<FileReference>, // References to input files in DistributedStorage
    // val resourceLimits: ResourceLimits, // Deprecated and removed
    // val deadlineMs: Long,
    val requesterNodeId: Int,         // Task owner (for result permissions and completion callback)
    val accessScope: AccessScope = AccessScope.TASK_ISOLATED,
    val owner: RecipientEntry,
    val recipients: List<RecipientEntry> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TaskExecutionContext) return false
        return taskId == other.taskId
    }

    override fun hashCode(): Int = taskId.hashCode()
}


/**
 * RESOURCE LIMITS
 * 
 * Per-task resource constraints for sandboxing
 */
@Serializable
data class ResourceLimits(
    val maxMemoryBytes: Long,
    val maxCpuTimeMs: Long,
    val maxDiskBytes: Long,
    val maxExecutionTimeMs: Long,
    val allowNetworkAccess: Boolean = false // Always false for untrusted code
) {
    companion object {
        fun zero() = ResourceLimits(
            maxMemoryBytes = 0,
            maxCpuTimeMs = 0,
            maxDiskBytes = 0,
            maxExecutionTimeMs = 0,
            allowNetworkAccess = true,
           
        )
    }
}

/**
 * RESOURCE METRICS
 * 
 * Snapshot of resource usage at a point in time
 */
@Serializable
data class ResourceMetrics(
    val timestamp: Long = System.currentTimeMillis(),
    val ramActualBytes: Long,
    val ramAverageBytes: Long,
    val ramPeakBytes: Long,
    val cpuTimeUsedMs: Long,
    val cpuPercentage: Float,
    val diskIoOperations: Long,
    val diskStorageUsedBytes: Long,
    val networkUsedBytes: Long = 0
) {
    companion object {
        fun zero() = ResourceMetrics(
            ramActualBytes = 0,
            ramAverageBytes = 0,
            ramPeakBytes = 0,
            cpuTimeUsedMs = 0,
            cpuPercentage = 0f,
            diskIoOperations = 0,
            diskStorageUsedBytes = 0,
            networkUsedBytes = 0
        )
    }
}

/**
 * EXECUTION RESULT
 * 
 * Complete result of task execution including outputs and metrics
 */
@Serializable
data class ExecutionResult(
    val taskId: String,
    // val processId: Int,
    val success: Boolean,
    val outputManifest: List<FileReference>, // Zero or more output files
    val resultMessage: String? = null,       // Optional task-defined message
    // val resourcesUsed: ResourceMetrics,
    val executionTimeMs: Long,
    val errorMessage: String? = null,
    val errorType: ExecutionErrorType? = null
)


data class ExecutionState(
    val taskId: UUID,
    val containerId: String,
    val executorNodeAddress: String,
    val startTime: Long,
    val taskContext: TaskExecutionContext,
    val requesterNodeId: String,  // Already in taskContext, but kept for direct access
    // Phase 2.2: Resource monitoring fields
    // val resourceMetrics: ResourceMetrics = ResourceMetrics.zero(),
    val lastMetricUpdate: Long = 0L
)

/**
 * EXECUTION ERROR TYPES
 * 
 * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md Section 1.2
 */
@Serializable
enum class ExecutionErrorType {
    /** Exceeded maxExecutionTimeMs */
    TIMEOUT,
    
    /** Exceeded maxMemoryBytes */
    OUT_OF_MEMORY,
    
    /** Exceeded maxDiskBytes */
    DISK_QUOTA_EXCEEDED,
    
    /** Attempted prohibited syscall */
    SANDBOX_VIOLATION,
    
    /** Code threw exception */
    RUNTIME_ERROR,
    
    /** Input file not available */
    INPUT_NOT_FOUND,
    
    /** Code bundle malformed or unsupported */
    INVALID_CODE_BUNDLE,
    
    /** SecurityManager blocked prohibited operation (network, filesystem, native code) */
    SECURITY_VIOLATION,
    
    /** Unknown error */
    UNKNOWN,
    DISK_FULL
}

/**
 * OUTPUT MANIFEST
 * 
 * Describes files created by a task (sent in completion notification)
 */
@Serializable
data class OutputManifest(
    val taskId: String,
    val files: List<FileReference>,
    val totalSizeBytes: Long,
    val createdAtMs: Long = System.currentTimeMillis()
)

enum class AccessScope {
        TASK_ISOLATED,  // Can only access files created by this task
        SERVICE_SHARED, // Can access files from other tasks of same service
        MESH_GLOBAL     // Can access any files (dangerous, usually not allowed)
    }