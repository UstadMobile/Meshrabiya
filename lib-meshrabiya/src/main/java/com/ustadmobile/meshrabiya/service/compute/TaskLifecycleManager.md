package com.ustadmobile.meshrabiya.service.compute

import kotlinx.coroutines.*
import java.util.UUID
import com.ustadmobile.meshrabiya.service.compute.model.ComputeTask
import com.ustadmobile.meshrabiya.service.compute.model.TaskResult
import com.ustadmobile.meshrabiya.service.compute.model.TaskStatus
/**
 * TaskLifecycleManager - Manages enhanced task lifecycle with keypair support.
 * 
 * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md Section 14
 * 
 * Provides backward compatibility with legacy task execution while adding
 * per-task keypair enhancement for encrypted file workflows.
 * 
 * Enhanced Lifecycle:
 * PENDING → ASSIGNED → KEYPAIR_GENERATED → SCHEDULED → RUNNING → COMPLETED
 * 
 * Legacy Lifecycle (no changes):
 * PENDING → ASSIGNED → RUNNING → COMPLETED
 */
object TaskLifecycleManager {
    
    /**
     * Feature flag to enable/disable keypair enhancement.
     * Can be toggled at runtime without code changes.
     */
    private var keypairEnhancementEnabled = true
    
    /**
     * Set the keypair enhancement feature flag.
     */
    fun setKeypairEnhancementEnabled(enabled: Boolean) {
        keypairEnhancementEnabled = enabled
    }
    
    /**
     * Check if keypair enhancement is globally enabled.
     */
    fun isKeypairEnhancementEnabled(): Boolean {
        return keypairEnhancementEnabled
    }
    
    /**
     * Check if a specific task requires keypair enhancement.
     * 
     * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md Section 14.2.1
     * 
     * Criteria:
     * 1. Feature flag is enabled
     * 2. Task has encrypted input files
     * 3. All files are marked for encryption
     * 
     * @param task Task to check
     * @return true if task requires keypair enhancement
     */
    fun requiresKeypairEnhancement(task: ComputeTask): Boolean {
        // Check feature flag
        if (!keypairEnhancementEnabled) {
            return false
        }
        
        // Check if task has input files
        if (task.inputFiles.isEmpty()) {
            return false
        }
        
        // Check if task explicitly requests keypair encryption
        val requiresEncryption = task.metadata["requiresKeypairEncryption"] as? Boolean ?: false
        
        return requiresEncryption
    }
    
    /**
     * Execute task with appropriate mode (keypair or direct).
     * 
     * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md Section 14.2.1
     * 
     * @param task Task to execute
     * @return TaskResult with execution outcome
     */
    suspend fun executeTask(task: ComputeTask): TaskResult {
        return if (requiresKeypairEnhancement(task)) {
            // Enhanced mode: generate keypair, wait for file re-encryption
            executeTaskWithKeypair(task)
        } else {
            // Legacy mode: direct execution
            executeTaskDirect(task)
        }
    }
    
    /**
     * Legacy execution path (no keypair).
     * 
     * Uses existing StrangersSafeComputeEngine directly.
     * 
     * @param task Task to execute
     * @return TaskResult
     */
    private suspend fun executeTaskDirect(task: ComputeTask): TaskResult {
        // TODO: Integrate with StrangersSafeComputeEngine from Phase 2
        // For now, placeholder implementation
        
        return TaskResult(
            taskId = task.taskId,
            status = TaskStatus.COMPLETED,
            executionTimeMs = 0L,
            outputFiles = emptyList(),
            error = null
        )
    }
    
    /**
     * Enhanced execution path (with keypair).
     * 
     * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md Section 14.2.1
     * 
     * Steps:
     * 1. Generate task keypair
     * 2. Send TASK_SCHEDULED message with public key
     * 3. Wait for file re-encryption
     * 4. Create container with keypair environment
     * 5. Execute task
     * 6. Cleanup keypair
     * 
     * @param task Task to execute
     * @return TaskResult
     */
    private suspend fun executeTaskWithKeypair(task: ComputeTask): TaskResult {
        var keypairGenerated = false
        
        try {
            // 1. Generate keypair
            val keypair = TaskManager.generateTaskKeypair(
                taskId = task.taskId,
                lifetimeMs = 24 * 60 * 60 * 1000L // 24 hours
            )
            keypairGenerated = true
            
            // 2. Send TASK_SCHEDULED with public key
            // TODO: Send message via mesh network
            // sendTaskScheduledMessage(task.taskId, keypair.publicKey)
            
            // 3. Wait for files to be re-encrypted
            val filesReady = waitForFileReEncryption(
                taskId = task.taskId,
                fileIds = task.inputFiles,
                timeoutMs = 60_000L // 60 seconds
            )
            
            if (!filesReady) {
                throw TaskExecutionException("Files not re-encrypted within timeout")
            }
            
            // 4. Create container with keypair environment
            // TODO: Integrate with StrangersSafeComputeEngine
            // val container = StrangersSafeComputeEngine.createContainerWithKeypair(task, keypair)
            
            // 5. Execute (placeholder)
            val result = TaskResult(
                taskId = task.taskId,
                status = TaskStatus.COMPLETED,
                executionTimeMs = 0L,
                outputFiles = emptyList(),
                error = null
            )
            
            return result
            
        } catch (e: Exception) {
            return TaskResult(
                taskId = task.taskId,
                status = TaskStatus.FAILED,
                executionTimeMs = 0L,
                outputFiles = emptyList(),
                error = e.message ?: "Unknown error"
            )
        } finally {
            // 6. Cleanup keypair
            if (keypairGenerated) {
                TaskManager.removeTaskKeypair(task.taskId)
            }
        }
    }
    
    /**
     * Wait for input files to be re-encrypted with task public key.
     * 
     * @param taskId Task identifier
     * @param fileIds List of file IDs to wait for
     * @param timeoutMs Timeout in milliseconds
     * @return true if all files ready, false if timeout
     */
    private suspend fun waitForFileReEncryption(
        taskId: String,
        fileIds: List<String>,
        timeoutMs: Long
    ): Boolean {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // Check if all files have been updated with task recipient
            val allReady = fileIds.all { fileId ->
                // TODO: Check DistributedStorageManager for file metadata
                // val metadata = DistributedStorageManager.getFileMetadata(fileId)
                // metadata?.hasTaskAccess(taskId) == true
                false // Placeholder
            }
            
            if (allReady) {
                return true
            }
            
            delay(1000L) // Check every second
        }
        
        return false
    }
    
    
    
    /**
     * Task execution exception.
     */
    class TaskExecutionException(message: String) : Exception(message)
}
