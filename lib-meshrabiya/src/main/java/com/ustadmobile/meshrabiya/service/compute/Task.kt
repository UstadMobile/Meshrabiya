package com.ustadmobile.meshrabiya.service.compute

import com.ustadmobile.meshrabiya.storage.RecipientEntry
import com.ustadmobile.meshrabiya.service.compute.model.TaskExecutionContext
import com.ustadmobile.meshrabiya.service.compute.model.RuntimeType as ModelRuntimeType

/**
 * Represents a compute task being executed on the mesh network.
 * Combines task metadata, execution context, and lifecycle management.
 *
 * @property taskId Unique identifier for the task
 * @property executionContext Complete task execution context (reuses existing model)
 * @property serviceId Service identifier from ServiceLibrary
 * @property inputParams Input parameters for the task
 * @property requesterNodeId Public key of the node requesting the task
 * @property recipients List of recipients with access to task results
 * @property state Current execution state of the task
 * @property publicKey Public key for encrypting data shared with this task
 * @property privateKey Private key for decrypting input files (stored securely)
 * @property sandboxDir Sandbox directory for isolated execution (renamed from filesDir)
 * @property executable Code bundle to execute
 * @property createdAt Timestamp when task was created
 * @property startedAt Timestamp when execution started (null if not started)
 * @property completedAt Timestamp when execution completed (null if not completed)
 * @property assignedExecutor Which executor is handling this task (JVM/JS/ML)
 */
data class Task(
    val taskId: String,
    val executionContext: TaskExecutionContext,
    val serviceId: String,
    val inputParams: Map<String, Any>,
    val requesterNodeId: Int,
    val recipients: List<RecipientEntry>,
    var state: TaskState,
    val publicKey: String,
    val privateKey: ByteArray,
    val sandboxDir: String,
    val executable: Executable,
    val createdAt: Long = System.currentTimeMillis(),
    var startedAt: Long? = null,
    var completedAt: Long? = null,
    var assignedExecutor: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Task

        if (taskId != other.taskId) return false
        if (executionContext != other.executionContext) return false
        if (serviceId != other.serviceId) return false
        if (inputParams != other.inputParams) return false
        if (requesterNodeId != other.requesterNodeId) return false
        if (recipients != other.recipients) return false
        if (state != other.state) return false
        if (publicKey != other.publicKey) return false
        if (!privateKey.contentEquals(other.privateKey)) return false
        if (sandboxDir != other.sandboxDir) return false
        if (executable != other.executable) return false
        if (createdAt != other.createdAt) return false
        if (startedAt != other.startedAt) return false
        if (completedAt != other.completedAt) return false
        if (assignedExecutor != other.assignedExecutor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = taskId.hashCode()
        result = 31 * result + executionContext.hashCode()
        result = 31 * result + serviceId.hashCode()
        result = 31 * result + inputParams.hashCode()
        result = 31 * result + requesterNodeId.hashCode()
        result = 31 * result + recipients.hashCode()
        result = 31 * result + state.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + privateKey.contentHashCode()
        result = 31 * result + sandboxDir.hashCode()
        result = 31 * result + executable.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (startedAt?.hashCode() ?: 0)
        result = 31 * result + (completedAt?.hashCode() ?: 0)
        result = 31 * result + (assignedExecutor?.hashCode() ?: 0)
        return result
    }
}

/**
 * Task execution state machine
 */
enum class TaskState {
    /** Task accepted, preparing sandbox and dependencies */
    PREPARING,
    
    /** Waiting for input files via FileAccessUpdateConfirmation */
    WAITING_FOR_INPUT,
    
    /** Task is actively executing */
    EXECUTING,
    
    /** Task completed successfully */
    COMPLETED,
    
    /** Task failed during execution */
    FAILED
}

/**
 * Represents executable code bundle for a task
 *
 * @property runtime Runtime environment for execution
 * @property codeBundle Binary code to execute
 * @property entryPoint Entry point for execution (function name, class name, etc.)
 */
data class Executable(
    val runtime: ModelRuntimeType,
    val codeBundle: ByteArray,
    val entryPoint: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Executable

        if (runtime != other.runtime) return false
        if (!codeBundle.contentEquals(other.codeBundle)) return false
        if (entryPoint != other.entryPoint) return false

        return true
    }

    override fun hashCode(): Int {
        var result = runtime.hashCode()
        result = 31 * result + codeBundle.contentHashCode()
        result = 31 * result + entryPoint.hashCode()
        return result
    }
}

/**
 * Supported runtime types for task execution
 * @deprecated Use com.ustadmobile.meshrabiya.service.compute.model.RuntimeType instead
 */
@Deprecated(
    message = "Use com.ustadmobile.meshrabiya.service.compute.model.RuntimeType instead",
    replaceWith = ReplaceWith("RuntimeType", "com.ustadmobile.meshrabiya.service.compute.model.RuntimeType"),
    level = DeprecationLevel.WARNING
)
enum class RuntimeType {
    /** JVM bytecode execution */
    JVM,
    
    /** JavaScript execution via J2V8 */
    JAVASCRIPT,
    
    /** TensorFlow Lite ML model execution */
    ML_NATIVE
}
