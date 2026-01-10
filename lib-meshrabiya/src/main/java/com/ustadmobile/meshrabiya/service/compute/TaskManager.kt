package com.ustadmobile.meshrabiya.service.compute

import android.content.Context
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.service.compute.model.TaskExecutionContext
import com.ustadmobile.meshrabiya.service.compute.DistributedServiceLibrary.ServiceLibraryEntry
import com.ustadmobile.meshrabiya.storage.DistributedStorageClient
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import kotlinx.coroutines.*
import java.security.KeyPairGenerator
import java.util.concurrent.ConcurrentHashMap
import java.util.Base64

/**
 * TaskManager
 * 
 * Orchestrates compute task lifecycle on a compute node.
 * 
 * Architecture:
 * - Delegates sandbox management to TaskSandboxManager
 * - Delegates file I/O to TaskInputFileManager
 * - Delegates execution to TaskExecutionCoordinator
 * - Owns task registry and generates keypairs
 * 
 * Public API:
 * - addTask() - Create new task with keypair
 * - prepareTask() - Prepare sandbox and handle input files
 * - handleTaskDataAccessUpdate() - Process incoming files
 * - getTask() / getActiveTasks() - Query tasks
 * - removeTask() - Cleanup task resources
 * - shutdown() - Cleanup all tasks
 */
class TaskManager(
    private val context: Context,
    private val virtualNode: VirtualNode,
    private val distributedStorageClient: DistributedStorageClient,
    private val betaLogger: BetaTestLogger? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeTasks = ConcurrentHashMap<String, Task>()
    
    // Component delegation
    private val sandboxManager = TaskSandboxManager(context)
    private val fileManager = TaskInputFileManager(context, distributedStorageClient, sandboxManager, betaLogger)
    private val executionCoordinator = TaskExecutionCoordinator(context, betaLogger)
    
    companion object {
        private const val TAG = "TaskManager"
    }
    
    /**
     * Add new task for execution.
     * Generates RSA keypair and creates sandbox.
     */
    suspend fun addTask(
        executionContext: TaskExecutionContext,
        serviceLibraryEntry: ServiceLibraryEntry
    ): Task = withContext(Dispatchers.IO) {
        betaLogger?.log(LogLevel.DEBUG, TAG, "Adding task: \${executionContext.taskId}")
        
        try {
            // Generate RSA keypair for task
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(2048)
            val keyPair = keyGen.generateKeyPair()
            
            val publicKeyEncoded = Base64.getEncoder().encodeToString(keyPair.public.encoded)
            val privateKeyBytes = keyPair.private.encoded
            
            // Create sandbox
            val sandboxPaths = sandboxManager.createSandbox(executionContext.taskId)
            
            // Create task
            val task = Task(
                taskId = executionContext.taskId,
                executionContext = executionContext,
                serviceId = serviceLibraryEntry.serviceId,
                inputParams = emptyMap(),
                requesterNodeId = executionContext.requesterNodeId,
                recipients = emptyList(),
                state = TaskState.PREPARING,
                publicKey = publicKeyEncoded,
                privateKey = privateKeyBytes,
                sandboxDir = sandboxPaths.base,
                executable = Executable(
                    runtime = serviceLibraryEntry.runtime,
                    codeBundle = executionContext.codeBundle,
                    entryPoint = ""
                )
            )
            
            // Track task
            activeTasks[task.taskId] = task
            
            // Track expected input files if needed
            if (serviceLibraryEntry.hasInputFiles) {
                fileManager.trackExpectedFiles(task.taskId, executionContext.inputManifest)
            }
            
            betaLogger?.log(LogLevel.DEBUG, TAG, "Task added: \${task.taskId}")
            
            return@withContext task
            
        } catch (e: Exception) {
            betaLogger?.log(LogLevel.ERROR, TAG, "Failed to add task: \${executionContext.taskId} - \${e.message}")
            throw e
        }
    }
    
    /**
     * Prepare task for execution.
     * Checks for input files and proceeds to execution when ready.
     */
    suspend fun prepareTask(
        taskId: String,
        serviceLibraryEntry: ServiceLibraryEntry
    ) = withContext(Dispatchers.IO) {
        val task = activeTasks[taskId] ?: run {
            betaLogger?.log(LogLevel.ERROR, TAG, "Task not found for preparation: \$taskId")
            return@withContext
        }
        
        try {
            betaLogger?.log(LogLevel.DEBUG, TAG, "Preparing task: \$taskId")
            
            // Check if ready to execute
            if (fileManager.shouldProceedToExecution(task, serviceLibraryEntry)) {
                // Proceed to execution
                val sandboxPaths = sandboxManager.getSandboxPaths(taskId)
                executionCoordinator.executeTask(task, sandboxPaths, serviceLibraryEntry)
            } else {
                // Wait for input files
                task.state = TaskState.WAITING_FOR_INPUT
                betaLogger?.log(LogLevel.DEBUG, TAG, "Task \$taskId waiting for input files")
            }
            
        } catch (e: Exception) {
            betaLogger?.log(LogLevel.ERROR, TAG, "Task preparation failed: \$taskId - \${e.message}")
            task.state = TaskState.FAILED
            throw e
        }
    }
    
    /**
     * Handle file access update notification.
     * Retrieves file and checks execution readiness.
     */
    suspend fun handleTaskDataAccessUpdate(
        taskId: String,
        fileId: String
    ) = withContext(Dispatchers.IO) {
        val task = activeTasks[taskId] ?: run {
            betaLogger?.log(LogLevel.ERROR, TAG, "Task not found for access update: \$taskId")
            return@withContext
        }
        
        try {
            // Retrieve and store file
            fileManager.handleFileAccessUpdate(task, fileId)
            
            // Check if ready to proceed
            // Note: Would need serviceLibraryEntry here - simplified for now
            if (task.state == TaskState.WAITING_FOR_INPUT) {
                // Resume preparation (would call prepareTask with serviceLibraryEntry)
                betaLogger?.log(LogLevel.DEBUG, TAG, "File received for task \$taskId, checking readiness")
            }
            
        } catch (e: Exception) {
            betaLogger?.log(LogLevel.ERROR, TAG, "Access update failed for task \$taskId: \${e.message}")
        }
    }
    
    /**
     * Get task by ID.
     */
    fun getTask(taskId: String): Task? = activeTasks[taskId]
    
    /**
     * Get all active tasks.
     */
    fun getActiveTasks(): List<Task> = activeTasks.values.toList()
    
    /**
     * Remove task and cleanup all resources.
     */
    suspend fun removeTask(taskId: String) = withContext(Dispatchers.IO) {
        val task = activeTasks.remove(taskId) ?: return@withContext
        
        betaLogger?.log(LogLevel.DEBUG, TAG, "Removing task: \$taskId")
        
        // Cleanup components
        sandboxManager.cleanupSandbox(taskId)
        fileManager.cleanupTask(taskId)
        
        betaLogger?.log(LogLevel.DEBUG, TAG, "Task removed: \$taskId")
    }
    
    /**
     * Shutdown manager and cleanup all tasks.
     */
    suspend fun shutdown() {
        betaLogger?.log(LogLevel.DEBUG, TAG, "Shutting down TaskManager")
        
        activeTasks.keys.forEach { taskId ->
            removeTask(taskId)
        }
        
        scope.cancel()
    }
}
