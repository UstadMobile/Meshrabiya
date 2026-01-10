package com.ustadmobile.meshrabiya.service.compute.model

import com.ustadmobile.meshrabiya.service.compute.model.FileReference
import com.ustadmobile.meshrabiya.service.compute.model.JobType
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
// import com.ustadmobile.meshrabiya.service.compute.model.ResourceLimits // Deprecated and removed
import kotlinx.serialization.Serializable

/**
 * TaskAssignmentMessages
 *
 * Message types for task assignment and execution lifecycle communication between
 * scheduler nodes and compute nodes.
 *
 * Phase 3.3: Task Assignment Integration
 * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md Section 8
 */

/**
 * TaskAssignmentMessage
 *
 * Sent from scheduler to compute node to assign a task for execution.
 * Contains all necessary metadata, code bundle, input file references, and resource limits.
 */
@Serializable
data class TaskAssignmentMessage(
    val messageId: String,
    val taskId: String,
    val requesterNodeId: String,
    val callbackAddress: String,
    val taskType: TaskType,
    val jobType: JobType,
    val codeBundle: ByteArray?,
    val inputFiles: List<String>,
    // val resourceLimits: ResourceLimits, // Deprecated and removed
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TaskAssignmentMessage) return false

        if (messageId != other.messageId) return false
        if (taskId != other.taskId) return false
        if (requesterNodeId != other.requesterNodeId) return false
        if (callbackAddress != other.callbackAddress) return false
        if (taskType != other.taskType) return false
        if (jobType != other.jobType) return false
        if (codeBundle != null) {
            if (other.codeBundle == null) return false
            if (!codeBundle.contentEquals(other.codeBundle)) return false
        } else if (other.codeBundle != null) return false
        if (inputFiles != other.inputFiles) return false
        // if (resourceLimits != other.resourceLimits) return false // Deprecated and removed
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + taskId.hashCode()
        result = 31 * result + requesterNodeId.hashCode()
        result = 31 * result + callbackAddress.hashCode()
        result = 31 * result + taskType.hashCode()
        result = 31 * result + jobType.hashCode()
        result = 31 * result + (codeBundle?.contentHashCode() ?: 0)
        result = 31 * result + inputFiles.hashCode()
        // result = 31 * result + resourceLimits.hashCode() // Deprecated and removed
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/**
 * TaskRejectionMessage
 *
 * Sent from compute node to scheduler if task cannot be executed.
 * Allows scheduler to reassign task to another node.
 */
@Serializable
data class TaskRejectionMessage(
    val taskId: String,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * TaskAcceptanceMessage
 *
 * Sent from compute node to scheduler confirming task execution has started.
 */
@Serializable
data class TaskAcceptanceMessage(
    val taskId: String,
    val estimatedCompletionMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * TaskCompletedMessage
 *
 * Sent from compute node to scheduler upon task completion (success or failure).
 */
// @Serializable
// data class TaskCompletedMessage(
//     val taskId: String,
//     val result: TaskResult,
//     val timestamp: Long = System.currentTimeMillis()
// )


/**
 * TaskCompletionAckMessage
 *
 * Acknowledgment from scheduler to compute node that completion was received.
 * Stops retry loop on compute node side.
 */
@Serializable
data class TaskCompletionAckMessage(
    val taskId: String,
    val timestamp: Long = System.currentTimeMillis()
)
