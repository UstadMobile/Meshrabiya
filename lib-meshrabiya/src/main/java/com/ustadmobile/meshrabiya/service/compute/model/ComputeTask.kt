package com.ustadmobile.meshrabiya.service.compute.model
import com.ustadmobile.meshrabiya.model.ResourceRequirements
import com.ustadmobile.meshrabiya.service.compute.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual
import java.util.UUID
import com.ustadmobile.meshrabiya.storage.FileReference
/**
 * Sealed class representing a compute task in the distributed mesh system.
 * Subclasses represent specific task types and their properties.
 */


/**
    * Enhanced task status enum with keypair lifecycle states.
    */
enum class TaskStatus {
    PENDING,
    ASSIGNED,
    KEYPAIR_GENERATED,
    SCHEDULED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
    * Task data class.
    */
data class ComputeTask(
    val taskId: String,
    val taskType: com.ustadmobile.meshrabiya.service.compute.model.TaskType,
    val jobType: com.ustadmobile.meshrabiya.service.compute.model.JobType,
    val inputFiles: List<String>,
    val codeBundle: ByteArray,
    val metadata: Map<String, Any> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ComputeTask
        if (taskId != other.taskId) return false
        if (!codeBundle.contentEquals(other.codeBundle)) return false
        return true
    }
    
    override fun hashCode(): Int {
        var result = taskId.hashCode()
        result = 31 * result + codeBundle.contentHashCode()
        return result
    }

    /**
    * Python compute task. - temp comment out
    */
    // data class PythonTask(
    //     override val taskId: String,
    //     val scriptCode: String,
    //     val inputData: Map<String, Any>,
    //     val libraries: Set<PythonLibrary>,
    //     override val estimatedExecutionMs: Long,
    //     override val resourceRequirements: ResourceRequirements,
    //     override val dependencies: List<String> = emptyList(),
    //     val outputSchema: OutputSchema
    // ) : ComputeTask()

    /**
    * LiteRT compute task.
    */
    // data class LiteRTTask(
    //     override val taskId: String,
    //     val modelId: String,
    //     val inputTensors: List<ByteArray>,
    //     val modelConfig: LiteRTConfig,
    //     override val estimatedExecutionMs: Long,
    //     override val resourceRequirements: ResourceRequirements,
    //     override val dependencies: List<String> = emptyList(),
    //     val inferenceConfig: InferenceConfig
    // ) : ComputeTask()

    /**
    * Hybrid compute task (Python preprocessing, LiteRT inference, Python postprocessing). - temp comment out
    */
    // data class HybridTask(
    //     override val taskId: String,
    //     val pythonPreprocessing: PythonTask?,
    //     // val liteRTInference: LiteRTTask,
    //     val pythonPostprocessing: PythonTask?,
    //     override val estimatedExecutionMs: Long,
    //     override val resourceRequirements: ResourceRequirements,
    //     override val dependencies: List<String> = emptyList()
    // ) : ComputeTask()

}

/**
    * Task result data class.
    */
// data class TaskResult(
//     val taskId: String,
//     val status: TaskStatus,
//     val executionTimeMs: Long,
//     val outputFiles: List<String>,
//     val error: String?
// )
/**
 * TaskResult
 *
 * Encapsulates task execution result with output files, metrics, and error details.
 */
@Serializable
data class TaskResult(
    @Contextual val id: UUID,
    val success: Boolean,
    val executionResult:ExecutionResult,
    val outputManifest: List<FileReference>,
    // val metrics: ResourceMetrics,
    val errorMessage: String? = null
)
