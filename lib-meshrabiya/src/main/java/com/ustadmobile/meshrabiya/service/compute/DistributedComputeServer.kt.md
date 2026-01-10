package com.ustadmobile.meshrabiya.service.compute

import android.content.Context
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import com.ustadmobile.meshrabiya.vnet.EmergentRoleManager
import com.ustadmobile.meshrabiya.service.compute.model.*
import com.ustadmobile.meshrabiya.service.*
import com.ustadmobile.meshrabiya.storage.DistributedStorageClient
import com.ustadmobile.meshrabiya.storage.SyncPriority
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import com.ustadmobile.meshrabiya.storage.RecipientType
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.service.compute.DistributedServiceLibrary.ServiceLibraryEntry
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
    
    companion object {
        private const val TAG = "DistributedComputeServer"
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
            val serviceEntry = findServiceLibraryEntry(request.serviceId)
            if (serviceEntry == null) {
                betaLogger?.log(LogLevel.WARN, TAG, "Service not found: ${request.serviceId}")
                return@withContext
            }
            
            // Verify service hash matches
            if (serviceEntry.serviceBundleHash != request.metadata["serviceHash"]) {
                betaLogger?.log(LogLevel.WARN, TAG, "Service hash mismatch for: ${request.serviceId}")
                return@withContext
            }
            
            // Check if we have required capabilities
            val resourceReqs = serviceEntry.resourceRequirements
            if (!hasRequiredCapabilities(resourceReqs)) {
                betaLogger?.log(LogLevel.DEBUG, TAG, "Insufficient capabilities for task: ${request.taskId}")
                return@withContext
            }
            
            // Check fitness level
            val fitnessScore = emergentRoleManager.calculateFitnessScore()
            if (fitnessScore < 0.3f) { // Minimum fitness threshold
                betaLogger?.log(LogLevel.DEBUG, TAG, "Fitness too low for task: $fitnessScore")
                return@withContext
            }
            
            // Get ML capabilities if needed
            val (mlKitFeatures, mlKitCustomSupport) = emergentRoleManager.getLocalMLCapabilitiesForResponse()
            
            // Calculate current load (normalized to 0.0-1.0 based on active jobs)
            // Fitness score and user-defined CPU/RAM limits control when node stops responding
            val currentLoad = activeJobCount.get().toDouble()
            
            // Estimate latency
            val estimatedLatencyMs = estimateLatency()
            
            // Send response
            val response = ComputeNodeResponse(
                nodeAddress = virtualNode.addressAsInt,
                available = true,
                estimatedLatencyMs = estimatedLatencyMs.toLong(),
                currentLoad = currentLoad.toFloat(),
                mlKitFeatures = mlKitFeatures,
                mlKitCustomSupport = mlKitCustomSupport,
                requestId = requestId
            )
            
            val responseMessage = ComputeNodeResponseMessage(
                requestId = requestId,
                response = response
            )
            
            // Send direct message using VirtualPacket with ecosystem port
            val ecosystemPort = MeshrabiyaConstants.getEcosystemGossipPort()
            val messageBytes = responseMessage.toBytes()
            val packetData = ByteArray(VirtualPacketHeader.HEADER_SIZE + messageBytes.size)
            val header = VirtualPacketHeader(
                toAddr = requesterNodeAddress,
                toPort = ecosystemPort,
                fromAddr = virtualNode.addressAsInt,
                fromPort = ecosystemPort,
                lastHopAddr = virtualNode.addressAsInt,
                hopCount = 0,
                maxHops = 10,
                payloadSize = messageBytes.size
            )
            System.arraycopy(messageBytes, 0, packetData, VirtualPacketHeader.HEADER_SIZE, messageBytes.size)
            val packet = VirtualPacket.fromHeaderAndPayloadData(
                header = header,
                data = packetData,
                payloadOffset = VirtualPacketHeader.HEADER_SIZE
            )
            virtualNode.route(packet, null, null)
            
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
            val serviceEntry = findServiceLibraryEntry(assignment.metadata?.get("serviceId") as? String ?: "")
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
                taskType = TaskType.valueOf(assignment.taskType),
                jobType = JobType.valueOf(assignment.jobType),
                codeBundle = ByteArray(0), // TODO: Get from serviceEntry when available
                inputManifest = assignment.inputFiles.map { parseFileReference(it) },
                requesterNodeId = assignment.requesterNodeId,
                callbackAddress = senderAddress.toString(),
                accessScope = AccessScope.TASK_ISOLATED
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
     * Send task acceptance message to client.
     */
    private suspend fun sendTaskAcceptance(
        requesterAddress: Int,
        task: Task
    ) {
        val acceptanceMessage = TaskAcceptanceMessage(
            taskId = task.taskId,
            publicKey = task.publicKey,
            computeNodeAddress = virtualNode.getLocalNodeAddress().toString()
        )
        
        // Send direct message using VirtualPacket with ecosystem port
        val ecosystemPort = MeshrabiyaConstants.getEcosystemGossipPort()
        val messageBytes = acceptanceMessage.toBytes()
        val packetData = ByteArray(VirtualPacketHeader.HEADER_SIZE + messageBytes.size)
        val header = VirtualPacketHeader(
            toAddr = requesterAddress,
            toPort = ecosystemPort,
            fromAddr = virtualNode.addressAsInt,
            fromPort = ecosystemPort,
            lastHopAddr = virtualNode.addressAsInt,
            hopCount = 0,
            maxHops = 10,
            payloadSize = messageBytes.size
        )
        System.arraycopy(messageBytes, 0, packetData, VirtualPacketHeader.HEADER_SIZE, messageBytes.size)
        val packet = VirtualPacket.fromHeaderAndPayloadData(
            header = header,
            data = packetData,
            payloadOffset = VirtualPacketHeader.HEADER_SIZE
        )
        virtualNode.route(packet, null, null)
        
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
                executorNodeId = virtualNode.getLocalNodeAddress().toString(),
                status = if (task.state == TaskState.COMPLETED) "SUCCESS" else "FAILED",
                executionStats = ExecutionStats(
                    executionTimeMs = (task.completedAt ?: System.currentTimeMillis()) - (task.startedAt ?: task.createdAt),
                    cpuTimeMs = 0L, // TODO: Track actual CPU time
                    memoryPeakBytes = 0L, // TODO: Track actual memory
                    diskReadBytes = 0L,
                    diskWriteBytes = 0L
                ),
                executionError = if (task.state == TaskState.FAILED) {
                    ExecutionError(
                        errorType = "EXECUTION_FAILED",
                        errorMessage = "Task execution failed",
                        errorCode = 1
                    )
                } else null,
                resultStorageRefs = outputManifest.map { it.fileId }
            )
            
            // Send completion message
            val ecosystemPort = MeshrabiyaConstants.getEcosystemGossipPort()
            val messageBytes = completionMessage.toBytes()
            val packetData = ByteArray(VirtualPacketHeader.HEADER_SIZE + messageBytes.size)
            val header = VirtualPacketHeader(
                toAddr = requesterAddress,
                toPort = ecosystemPort,
                fromAddr = virtualNode.addressAsInt,
                fromPort = ecosystemPort,
                lastHopAddr = virtualNode.addressAsInt,
                hopCount = 0,
                maxHops = 10,
                payloadSize = messageBytes.size
            )
            System.arraycopy(messageBytes, 0, packetData, VirtualPacketHeader.HEADER_SIZE, messageBytes.size)
            val packet = VirtualPacket.fromHeaderAndPayloadData(
                header = header,
                data = packetData,
                payloadOffset = VirtualPacketHeader.HEADER_SIZE
            )
            virtualNode.route(packet, null, null)
            
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
    private suspend fun collectAndStoreOutputFiles(task: Task): List<model.FileReference> {
        val outputFiles = mutableListOf<model.FileReference>()
        
        try {
            val outputsDir = File(task.sandboxDir, "outputs")
            if (!outputsDir.exists()) return emptyList()
            
            outputsDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    // Store file to distributed storage with task permissions
                    val fileRef = distributedStorageClient.storeFile(
                        path = file.name,
                        data = file.readBytes(),
                        priority = SyncPriority.HIGH,
                        owner = task.executionContext.requesterNodeId,
                        recipients = listOf(
                            RecipientEntry(
                                publicKey = task.executionContext.requesterNodeId,
                                recipientType = RecipientType.USER
                            ),
                            RecipientEntry(
                                publicKey = task.publicKey,
                                recipientType = RecipientType.TASK,
                                taskId = task.taskId
                            )
                        )
                    )
                    
                    if (fileRef != null) {
                        outputFiles.add(fileRef)
                        betaLogger?.log(LogLevel.DEBUG, TAG, "Stored output file: ${file.name} -> ${fileRef.id}")
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
                val ecosystemPort = MeshrabiyaConstants.getEcosystemGossipPort()
                val messageBytes = completionMessage.toBytes()
                val packetData = ByteArray(VirtualPacketHeader.HEADER_SIZE + messageBytes.size)
                val header = VirtualPacketHeader(
                    toAddr = requesterAddress,
                    toPort = ecosystemPort,
                    fromAddr = virtualNode.addressAsInt,
                    fromPort = ecosystemPort,
                    lastHopAddr = virtualNode.addressAsInt,
                    hopCount = 0,
                    maxHops = 10,
                    payloadSize = messageBytes.size
                )
                System.arraycopy(messageBytes, 0, packetData, VirtualPacketHeader.HEADER_SIZE, messageBytes.size)
                val packet = VirtualPacket.fromHeaderAndPayloadData(
                    header = header,
                    data = packetData,
                    payloadOffset = VirtualPacketHeader.HEADER_SIZE
                )
                virtualNode.route(packet, null, null)
                betaLogger?.log(LogLevel.DEBUG, TAG, "Retry $retryCount: Sent completion for task $taskId")
                
                retryCount++
            }
            
            if (retryCount >= maxRetries) {
                betaLogger?.log(LogLevel.WARN, TAG, "Task $taskId completion notification failed after $retryCount retries")
                cleanupTask(taskId)
            }
        }
        
        completionRetryJobs[taskId] = retryJob
    }
    
    /**
     * Handle task completion acknowledgment from client.
     * Stops retry loop and cleans up resources.
     */
    suspend fun handleTaskCompletionAckMessage(
        senderAddress: Int,
        ack: TaskCompletionAckMessage
    ) {
        betaLogger?.log(LogLevel.INFO, TAG, "Received completion ACK for task: ${ack.taskId}")
        
        // Stop retry loop
        completionRetryJobs[ack.taskId]?.cancel()
        completionRetryJobs.remove(ack.taskId)
        
        // Cleanup task
        cleanupTask(ack.taskId)
    }
    
    /**
     * Handle file access update notification.
     * Routes to TaskManager for processing.
     */
    suspend fun handleTaskDataAccessUpdate(
        fileAccessUpdate: FileAccessUpdateConfirmation
    ) {
        betaLogger?.log(LogLevel.DEBUG, TAG, "Received file access update for file: ${fileAccessUpdate.fileId}")
        
        // Check if any active task needs this file
        taskManager.getActiveTasks().forEach { task ->
            if (task.state == TaskState.WAITING_FOR_INPUT) {
                // Pass to TaskManager to handle
                taskManager.handleTaskDataAccessUpdate(task.taskId, fileAccessUpdate.fileId)
            }
        }
    }
    
    /**
     * Cleanup task resources.
     */
    private suspend fun cleanupTask(taskId: String) {
        betaLogger?.log(LogLevel.DEBUG, TAG, "Cleaning up task: $taskId")
        taskManager.removeTask(taskId)
    }
    
    // === Helper Methods ===
    
    private fun findServiceLibraryEntry(serviceId: String): ServiceLibraryEntry? {
        // TODO: Implement service library lookup
        // For now, return null (would integrate with ServiceLibraryManager)
        return null
    }
    
    private fun hasRequiredCapabilities(requirements: com.ustadmobile.meshrabiya.model.ResourceRequirements): Boolean {
        // TODO: Check actual system resources against requirements
        return true
    }
    
    private fun calculateCapabilityMetric(requirements: com.ustadmobile.meshrabiya.model.ResourceRequirements): Double {
        // TODO: Calculate how well this node matches required capabilities
        return 1.0
    }
    
    private fun getAvailableRamMb(): Long {
        // TODO: Get actual available RAM
        return 1024L
    }
    
    private fun getAvailableStorageMb(): Long {
        // TODO: Get actual available storage
        return 10240L
    }
    
    private fun estimateLatency(): Long {
        // TODO: Estimate execution latency based on current load
        return 1000L
    }
    
    private fun parseFileReference(fileMap: Map<String, Any>): model.FileReference {
        return model.FileReference(
            fileId = fileMap["fileId"] as? String ?: "",
            fileName = fileMap["fileName"] as? String ?: "",
            sizeBytes = (fileMap["sizeBytes"] as? Number)?.toLong() ?: 0L
        )
    }
    
    /**
     * Shutdown server and cleanup all tasks.
     */
    suspend fun shutdown() {
        betaLogger?.log(LogLevel.INFO, TAG, "Shutting down DistributedComputeServer")
        
        // Cancel all retry jobs
        completionRetryJobs.values.forEach { it.cancel() }
        completionRetryJobs.clear()
        
        scope.cancel()
    }
}
