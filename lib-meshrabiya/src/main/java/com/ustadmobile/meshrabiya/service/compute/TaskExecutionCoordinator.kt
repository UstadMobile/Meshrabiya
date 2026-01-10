package com.ustadmobile.meshrabiya.service.compute

import android.content.Context
import com.ustadmobile.meshrabiya.service.compute.DistributedServiceLibrary.ServiceLibraryEntry
import com.ustadmobile.meshrabiya.service.compute.executor.TaskExecutor
import com.ustadmobile.meshrabiya.service.compute.executor.JVMExecutor
import com.ustadmobile.meshrabiya.service.compute.executor.JSExecutor
import com.ustadmobile.meshrabiya.service.compute.executor.MLNativeExecutor
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TaskExecutionCoordinator
 * 
 * Coordinates task execution via appropriate runtime executor.
 * 
 * Features:
 * - Lazy executor initialization (only when needed)
 * - Reads inputs from sandbox
 * - Routes to JVM/JS/ML executor based on executor class name
 * - Updates task state (EXECUTING → COMPLETED/FAILED)
 */
class TaskExecutionCoordinator(
    private val context: Context,
    private val betaLogger: BetaTestLogger? = null
) {
    companion object {
        private const val TAG = "TaskExecutionCoordinator"
    }
    
    /**
     * Lazy executor initialization - only created on first use per type.
     */
    private val executors: Map<String, TaskExecutor> by lazy {
        mapOf(
            "JVMExecutor" to JVMExecutor(),
            "JSExecutor" to JSExecutor(),
            "MLNativeExecutor" to MLNativeExecutor(context)
        )
    }
    
    /**
     * Execute task using appropriate executor.
     * Updates task state and timestamps.
     */
    suspend fun executeTask(
        task: Task,
        sandboxPaths: SandboxPaths,
        serviceLibraryEntry: ServiceLibraryEntry
    ): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            // Transition to EXECUTING state
            task.state = TaskState.EXECUTING
            task.startedAt = System.currentTimeMillis()
            
            betaLogger?.log(LogLevel.DEBUG, TAG, "Executing task \${task.taskId} with executor \${task.executionContext.executorType}")
            
            // Get appropriate executor
            val executor = executors[task.executionContext.executorType] ?: run {
                betaLogger?.log(LogLevel.ERROR, TAG, "No executor found for executor type: \${task.executionContext.executorType}")
                task.state = TaskState.FAILED
                throw Exception("Executor not found for ${task.executionContext.executorType}")
            }
            
            // Read input files from sandbox inputs directory
            val inputsDir = File(sandboxPaths.inputs)
            val inputFiles = mutableMapOf<String, ByteArray>()
            
            if (inputsDir.exists()) {
                inputsDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        inputFiles[file.name] = file.readBytes()
                    }
                }
            }
            
            // Execute task
            val containerId = "container_\${task.taskId}"
            val result = executor.execute(task.executionContext, inputFiles, containerId)
            
            // Update task state based on result
            if (result.success) {
                task.state = TaskState.COMPLETED
                task.completedAt = System.currentTimeMillis()
                betaLogger?.log(LogLevel.DEBUG, TAG, "Task \${task.taskId} completed successfully")
            } else {
                task.state = TaskState.FAILED
                task.completedAt = System.currentTimeMillis()
                betaLogger?.log(LogLevel.ERROR, TAG, "Task \${task.taskId} failed: \${result.errorMessage}")
            }
            
            return@withContext ExecutionResult(
                success = result.success,
                outputFiles = result.outputManifest.map { it.fileName },
                executionTimeMs = result.executionTimeMs,
                errorMessage = result.errorMessage
            )
            
        } catch (e: Exception) {
            betaLogger?.log(LogLevel.ERROR, TAG, "Task execution failed: \${task.taskId} - \${e.message}")
            task.state = TaskState.FAILED
            task.completedAt = System.currentTimeMillis()
            throw e
        }
    }
}
