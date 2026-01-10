package com.ustadmobile.meshrabiya.service.compute

import android.content.Context
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import com.ustadmobile.meshrabiya.vnet.EmergentRoleManager
import com.ustadmobile.meshrabiya.service.*
import com.ustadmobile.meshrabiya.storage.DistributedStorageClient
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import com.ustadmobile.meshrabiya.storage.RecipientType
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.service.compute.DistributedServiceLibrary.ServiceLibraryEntry
import com.ustadmobile.meshrabiya.service.compute.model.*
import com.ustadmobile.meshrabiya.model.ResourceRequirements
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.ustadmobile.meshrabiya.storage.FileReference
/**
 * DistributedComputeServer
 * 
 * Server-side distributed compute service.
 * Handles incoming task requests, task execution, and result delivery.
 * 
 * Extracted from IntelligentDistributedComputeService (server-side functions).
 */
class DistributedComputeServer(
    private val context: Context,
    private val virtualNode: VirtualNode,
    private val emergentRoleManager: EmergentRoleManager,
    private val taskManager: TaskManager,
    private val distributedStorageClient: DistributedStorageClient,
    private val betaLogger: BetaTestLogger? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobCount = AtomicInteger(0)
    private val completionRetryJobs = ConcurrentHashMap<String, Job>()
    
    // Temporary in-memory service library - TODO: Replace with actual DistributedServiceLibrary integration
    private val services = mutableMapOf<String, ServiceLibraryEntry>()
    
    companion object {
        private const val TAG = "DistributedComputeServer"
        private const val MIN_FITNESS_THRESHOLD = 0.3f
    }
    
    /**
     * Handle incoming compute task request broadcast.
     * Evaluate capabilities and send response if eligible.
     */
    suspend fun handleIncomingComputeTaskRequest(
        requestId: String,
        requesterNodeAddress: Int,
        request: ComputeTaskRequestMessage
    ) = withContext(Dispatchers.IO) {
        betaLogger?.log(LogLevel.DEBUG, TAG, "Received compute task request: ${request.taskId}")
        
        try {
            // Check if we have the service in our library
            val serviceEntry = services[request.serviceId]
            if (serviceEntry == null) {
                betaLogger?.log(LogLevel.WARN, TAG, "Service not found: ${request.serviceId}")
                return@withContext
            }
            
            // Verify service hash matches (skipped for now - metadata structure TBD)
            // if (serviceEntry.serviceBundleHash != request.metadata["serviceHash"]) {
            //     betaLogger?.log(LogLevel.WARN, TAG, "Service hash mismatch for: ${request.serviceId}")
            //     return@withContext
            // }
            
            // Check if we have required capabilities
            if (!hasRequiredCapabilities(serviceEntry.resourceRequirements)) {
                betaLogger?.log(LogLevel.DEBUG, TAG, "Insufficient capabilities for task: ${request.taskId}")
                return@withContext
            }
            
            // Check fitness using normalized fitness calculation
            // TODO: Pass actual NodeCapabilitySnapshot
            val fitnessScore = 1.0f  // Stub - always high fitness for now
            if (fitnessScore < MIN_FITNESS_THRESHOLD) {
                betaLogger?.log(LogLevel.DEBUG, TAG, "Fitness too low for task: $fitnessScore")
                return@withContext
            }
            
            // Get ML capabilities - for now return empty list and false
            // TODO: Implement proper ML capability detection
            val mlKitFeatures = emptyList<String>()
            val mlKitCustomSupport = false
            
            // Calculate current load (active job count as float)
            val currentLoad = activeJobCount.get().toFloat()
            
            // Estimate latency based on current load and network conditions
            val estimatedLatencyMs = estimateLatency()
            
            // Send response
            val response = ComputeNodeResponse(
                nodeAddress = virtualNode.addressAsInt,
                available = true,
                estimatedLatencyMs = estimatedLatencyMs,
                currentLoad = currentLoad,
                mlKitFeatures = mlKitFeatures,
                mlKitCustomSupport = mlKitCustomSupport,
                requestId = requestId
            )
            
            val responseMessage = ComputeNodeResponseMessage(
                requestId = requestId,
                response = response
            )
            
            // Send direct message using VirtualPacket with ecosystem port
            sendDirectMessage(requesterNodeAddress, responseMessage.toBytes())
            
            betaLogger?.log(LogLevel.DEBUG, TAG, "Sent compute node response for task: ${request.taskId}")
            
        } catch (e: Exception) {
            betaLogger?.log(LogLevel.ERROR, TAG, "Error handling compute request: ${e.message}")
        }
    }
    
    /**
     * Handle task assignment message from client.
     * Accepts task, creates via TaskManager, and starts preparation.
     */
    suspend fun handleTaskAssignmentMessage(
        senderAddress: Int,
        assignment: TaskAssignmentMessage
    ) = withContext(Dispatchers.IO) {
        betaLogger?.log(LogLevel.INFO, TAG, "Received task assignment: ${assignment.taskId}")
        
        try {
            // Get service library entry
            val serviceId = assignment.taskId  // Use taskId as serviceId for now
            val serviceEntry = services[serviceId]
            if (serviceEntry == null) {
                sendTaskRejection(senderAddress, assignment, "Service not found")
                return@withContext
            }
            
            // Validate resources still available
            if (!hasRequiredCapabilities(serviceEntry.resourceRequirements)) {
                sendTaskRejection(senderAddress, assignment, "Insufficient resources")
                return@withContext
            }
            
            // Create execution context from assignment
            val executionContext = TaskExecutionContext(
                taskId = assignment.taskId,
                executorType = assignment.executorType,  // TaskAssignmentMessage.executorType
                // jobType = assignment.jobType,    // Already a String in TaskAssignmentMessage
                codeBundle = assignment.codeBundle ?: ByteArray(0),
                inputManifest = assignment.inputFiles.map { fileMap ->
                    FileReference(
                        fileId = fileMap["fileId"] ?: "",
                        path = fileMap["path"] ?: "",
                        fileName = fileMap["fileName"] ?: "",
                        sizeBytes = fileMap["sizeBytes"]?.toLongOrNull() ?: 0L,
                        mimeType = fileMap["mimeType"] ?: null,
                    )
                },
                requesterNodeId = assignment.requesterNodeId,  // Already stores callback info
                accessScope = AccessScope.TASK_ISOLATED,
                owner = assignment.owner,
                recipients = assignment.recipients
            )
            
            // Add task via TaskManager
            val task = taskManager.addTask(executionContext, serviceEntry)
            
            // Increment active job count
            activeJobCount.incrementAndGet()
            
            // Send acceptance message
            sendTaskAcceptance(senderAddress, task)
            
            // Start preparation phase
            scope.launch {
                try {
                    taskManager.prepareTask(task.taskId, serviceEntry)
                    
                    // After execution completes, send completion message
                    val completedTask = taskManager.getTask(task.taskId)
                    if (completedTask != null) {
                        sendTaskCompletion(senderAddress, completedTask, serviceEntry)
                    }
                    
                } catch (e: Exception) {
                    betaLogger?.log(LogLevel.ERROR, TAG, "Task preparation/execution failed: ${e.message}")
                    
                    // Send failure completion message
                    val failedTask = taskManager.getTask(task.taskId)
                    if (failedTask != null) {
                        failedTask.state = TaskState.FAILED
                        sendTaskCompletion(senderAddress, failedTask, serviceEntry)
                    }
                } finally {
                    activeJobCount.decrementAndGet()
                }
            }
            
        } catch (e: Exception) {
            betaLogger?.log(LogLevel.ERROR, TAG, "Error handling task assignment: ${e.message}")
            sendTaskRejection(senderAddress, assignment, "Internal error: ${e.message}")
        }
    }
    
    /**
     * Handle task completion acknowledgment from client.
     * Stops retry loop and cleans up task resources.
     */
    suspend fun handleTaskCompletionAckMessage(
        senderAddress: Int,
        ack: com.ustadmobile.meshrabiya.service.TaskCompletionAckMessage
    ) = withContext(Dispatchers.IO) {
        betaLogger?.log(LogLevel.INFO, TAG, "Received task completion ack: ${ack.taskId}")
        
        try {
            // Stop retry loop
            completionRetryJobs[ack.taskId]?.cancel()
            completionRetryJobs.remove(ack.taskId)
            
            // Clean up task resources
            val task = taskManager.getTask(ack.taskId)
            if (task != null) {
                cleanupTask(task)
            }
            
            betaLogger?.log(LogLevel.DEBUG, TAG, "Cleaned up task: ${ack.taskId}")
            
        } catch (e: Exception) {
            betaLogger?.log(LogLevel.ERROR, TAG, "Error handling completion ack: ${e.message}")
        }
    }
    
    /**
     * Send task acceptance message to client.
     */
    private suspend fun sendTaskAcceptance(
        requesterAddress: Int,
        task: Task
    ) {
        val acceptanceMessage = com.ustadmobile.meshrabiya.service.TaskAcceptanceMessage(
            taskId = task.taskId,
            publicKey = task.publicKey,
            computeNodeAddress = virtualNode.addressAsInt.toString()
        )
        
        // Send direct message using VirtualPacket
        sendDirectMessage(requesterAddress, acceptanceMessage.toBytes())
        
        betaLogger?.log(LogLevel.INFO, TAG, "Sent task acceptance: ${task.taskId}")
    }
    
    /**
     * Send task rejection message to client.
     */
    private suspend fun sendTaskRejection(
        requesterAddress: Int,
        assignment: TaskAssignmentMessage,
        reason: String
    ) {
        betaLogger?.log(LogLevel.WARN, TAG, "Sending task rejection: ${assignment.taskId} - $reason")
        // TODO: Implement TaskRejectionMessage if needed
    }
    
    /**
     * Send task completion message to client with retry logic.
     */
    private suspend fun sendTaskCompletion(
        requesterAddress: Int,
        task: Task,
        serviceEntry: ServiceLibraryEntry
    ) = withContext(Dispatchers.IO) {
        betaLogger?.log(LogLevel.INFO, TAG, "Sending task completion: ${task.taskId}")
        
        try {
            // Collect output files if task has output
            val outputManifest = if (serviceEntry.hasOutputFiles && task.state == TaskState.COMPLETED) {
                collectAndStoreOutputFiles(task)
            } else {
                emptyList()
            }
            
            // Create completion message
            val completionMessage = TaskCompletedMessage(
                taskId = task.taskId,
                executorNodeId = virtualNode.addressAsInt,
                status = if (task.state == TaskState.COMPLETED) "SUCCESS" else "FAILED",
                executionStats = TaskCompletedMessage.ExecutionStats(
                    executionTimeMs = (task.completedAt ?: System.currentTimeMillis()) - (task.startedAt ?: task.createdAt),
                    cpuTimeMs = 0L, // TODO: Track actual CPU time
                    memoryPeakBytes = 0L, // TODO: Track actual memory
                    diskReadBytes = 0L,
                    diskWriteBytes = 0L
                ),
                executionError = if (task.state == TaskState.FAILED) {
                    TaskCompletedMessage.ExecutionError(
                        errorType = "EXECUTION_FAILED",
                        errorMessage = "Task execution failed",
                        errorCode = 1
                    )
                } else null,
                resultStorageRefs = outputManifest.map { it.fileId }
            )
            
            // Send completion message
            sendDirectMessage(requesterAddress, completionMessage.toBytes())
            
            // Start retry loop
            startCompletionRetryLoop(task.taskId, requesterAddress, completionMessage)
            
        } catch (e: Exception) {
            betaLogger?.log(LogLevel.ERROR, TAG, "Error sending task completion: ${e.message}")
        }
    }
    
    /**
     * Collect output files and store to distributed storage.
     * Returns list of FileReferences for output manifest.
     */
    private suspend fun collectAndStoreOutputFiles(task: Task): List<com.ustadmobile.meshrabiya.storage.FileReference> {
        val outputFiles = mutableListOf<FileReference>()
        
        try {
            val outputsDir = File(task.sandboxDir, "outputs")
            if (!outputsDir.exists()) return emptyList()

            outputsDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    // Store file to distributed storage with task permissions
                    val fileRef = distributedStorageClient.storeFile(
                        path = file.absolutePath,
                        data = file.readBytes(),
                        
                        recipients = task.executionContext.recipients
                    )
                    
                    if (fileRef != null) {
                        outputFiles.add(fileRef)
                        betaLogger?.log(LogLevel.DEBUG, TAG, "Stored output file: ${file.name} -> ${fileRef.fileId}")
                    }
                }
            }

        } catch (e: Exception) {
            betaLogger?.log(LogLevel.ERROR, TAG, "Error collecting output files: ${e.message}")
        }
        
        return outputFiles
    }
    
    /**
     * Start retry loop for task completion notification.
     */
    private fun startCompletionRetryLoop(
        taskId: String,
        requesterAddress: Int,
        completionMessage: TaskCompletedMessage
    ) {
        val retryJob = scope.launch {
            val retryInterval = MeshrabiyaConstants.TASK_COMPLETION_RETRY_INTERVAL_MS
            val retryPeriod = MeshrabiyaConstants.TASK_COMPLETION_RETRY_PERIOD_MS
            val maxRetries = (retryPeriod / retryInterval).toInt()
            
            var retryCount = 0
            while (retryCount < maxRetries && isActive) {
                delay(retryInterval)
                
                // Resend completion message
                try {
                    sendDirectMessage(requesterAddress, completionMessage.toBytes())
                    betaLogger?.log(LogLevel.DEBUG, TAG, "Resent completion for task: $taskId (retry $retryCount)")
                } catch (e: Exception) {
                    betaLogger?.log(LogLevel.ERROR, TAG, "Error resending completion: ${e.message}")
                }
                
                retryCount++
            }
            
            // After max retries, clean up anyway
            if (retryCount >= maxRetries) {
                betaLogger?.log(LogLevel.WARN, TAG, "Max retries reached for task: $taskId")
                val task = taskManager.getTask(taskId)
                if (task != null) {
                    cleanupTask(task)
                }
            }
        }
        
        completionRetryJobs[taskId] = retryJob
    }
    
    /**
     * Clean up task resources (sandbox, files, etc.)
     */
    private suspend fun cleanupTask(task: Task) = withContext(Dispatchers.IO) {
        try {
            // Delete sandbox directory
            val sandboxDir = File(task.sandboxDir)
            if (sandboxDir.exists()) {
                sandboxDir.deleteRecursively()
                betaLogger?.log(LogLevel.DEBUG, TAG, "Deleted sandbox for task: ${task.taskId}")
            }
            
            // TODO: Additional cleanup (temp files, etc.)
            
        } catch (e: Exception) {
            betaLogger?.log(LogLevel.ERROR, TAG, "Error cleaning up task: ${e.message}")
        }
    }
    
    /**
     * Send a direct message to a specific node using VirtualNode ecosystem messaging.
     */
    private suspend fun sendDirectMessage(
        targetAddress: Int,
        messageBytes: ByteArray
    ) = withContext(Dispatchers.IO) {
        try {
            virtualNode.sendEcosystemMessage(targetAddress, messageBytes)
        } catch (e: Exception) {
            betaLogger?.log(LogLevel.ERROR, TAG, "Error sending direct message: ${e.message}")
            throw e
        }
    }
    
    /**
     * Check if this node has required capabilities for a service.
     */
    private fun hasRequiredCapabilities(requirements: ResourceRequirements): Boolean {
        // TODO: Implement actual capability checking against device resources
        // For now, always return true to allow testing
        return true
    }
    
    /**
     * Estimate latency for task execution based on current load.
     */
    private fun estimateLatency(): Long {
        // Simple estimation: base latency + load factor
        val baseLatencyMs = 100L
        val loadFactor = activeJobCount.get() * 50L
        return baseLatencyMs + loadFactor
    }
    
    /**
     * Handle file access update notification.
     * 
     * This is called when a storage node notifies that a file's access permissions
     * have been updated and a task is listed as a recipient.
     * 
     * Workflow (Phase 6 - CANONICAL_WORKFLOWS_v2.md):
     * 1. Check if any active task needs this file (WAITING_FOR_INPUT state)
     * 2. If yes, retrieve file from storage and add to task sandbox
     * 3. Notify TaskManager to check execution readiness
     * 
     * @param notification FileAccessUpdateNotification from storage node
     */
    suspend fun handleTaskDataAccessUpdate(
        notification: FileAccessUpdateNotification
    ) = withContext(Dispatchers.IO) {
        betaLogger?.log(
            LogLevel.DEBUG, 
            TAG, 
            "Received file access update for file: ${notification.fileId}"
        )
        
        try {
            // Check if any active task is waiting for this file
            taskManager.getActiveTasks().forEach { task ->
                if (task.state == TaskState.WAITING_FOR_INPUT) {
                    // Check if this task's input manifest includes this fileId
                    val needsThisFile = task.executionContext.inputManifest.any { 
                        it.fileId == notification.fileId 
                    }
                    
                    if (needsThisFile) {
                        betaLogger?.log(
                            LogLevel.INFO,
                            TAG,
                            "Task ${task.taskId} needs file ${notification.fileId}, triggering retrieval"
                        )
                        
                        // Pass to TaskManager to handle file retrieval and readiness check
                        taskManager.handleTaskDataAccessUpdate(
                            taskId = task.taskId,
                            fileId = notification.fileId
                        )
                    }
                }
            }
        } catch (e: Exception) {
            betaLogger?.log(
                LogLevel.ERROR,
                TAG,
                "Error handling file access update: ${e.message}"
            )
        }
    }
    
    /**
     * Shutdown the server and clean up resources.
     */
    fun shutdown() {
        scope.cancel()
        completionRetryJobs.values.forEach { it.cancel() }
        completionRetryJobs.clear()
        betaLogger?.log(LogLevel.INFO, TAG, "DistributedComputeServer shutdown complete")
    }
}
