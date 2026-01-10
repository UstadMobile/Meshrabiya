package com.ustadmobile.meshrabiya.service.compute

import android.content.Context
import kotlinx.coroutines.*
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.service.*
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.service.compute.model.LocalComputeTaskRequest
import com.ustadmobile.meshrabiya.service.compute.model.ComputeNodeResponse
import java.util.concurrent.ConcurrentHashMap
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl

import com.ustadmobile.meshrabiya.service.TaskAssignmentMessage
import com.ustadmobile.meshrabiya.service.TaskAcceptanceMessage
import com.ustadmobile.meshrabiya.service.TaskCompletedMessage
import com.ustadmobile.meshrabiya.service.TaskCompletionAckMessage
import com.ustadmobile.meshrabiya.service.ComputeTaskRequestMessage
import com.ustadmobile.meshrabiya.storage.RecipientEntry

/**
 * Client-side distributed compute service.
 * Handles task submission, node selection, and result retrieval.
 */
class DistributedComputeClient(
    private val context: Context,
    private val virtualNode: VirtualNode,
    private val betaLogger: BetaTestLogger
) {
    private val activeRequests = ConcurrentHashMap<String, TrackedRequest>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Process a task request from the API.
     *
     * @param request LocalComputeTaskRequest from user
     * @return taskId for tracking
     */
    suspend fun processTaskRequest(request: LocalComputeTaskRequest): String {
        betaLogger.log(
            com.ustadmobile.meshrabiya.beta.LogLevel.INFO,
            "DistributedComputeClient",
            "Processing task request: taskId=${request.taskId}"
        )
        
        // Track request
        val trackedRequest = TrackedRequest(
            request = request,
            status = TaskRequestStatus.PENDING,
            createdAt = System.currentTimeMillis()
        )
        activeRequests[request.taskId] = trackedRequest
        
        // Broadcast compute task request
        val message = ComputeTaskRequestMessage(
            taskId = request.taskId,
            serviceId = request.taskType,  // Use taskType as serviceId
            inputParams = emptyMap(),  // Will be properly implemented in Part 2
            metadata = mapOf(
                "requestId" to request.requestId
            )
        )
        
        virtualNode.coreGossipBroadcastService.sendBroadcast(message)
        
        betaLogger.log(
            com.ustadmobile.meshrabiya.beta.LogLevel.INFO,
            "DistributedComputeClient",
            "Broadcast compute task request: taskId=${request.taskId}"
        )
        
        return request.taskId
    }

    /**
     * Handle compute node response (called by MeshEcosystemListener).
     *
     * @param response ComputeNodeResponseMessage from potential compute node
     */
    fun handleComputeNodeResponse(response: ComputeNodeResponseMessage) {
        val nodeResponse = response.response
        val taskId = nodeResponse.requestId
        
        val tracked = activeRequests[taskId] ?: run {
            betaLogger.log(
                com.ustadmobile.meshrabiya.beta.LogLevel.WARN,
                "DistributedComputeClient",
                "Received response for unknown task: $taskId"
            )
            return
        }
        
        // Add response to candidates if node is available
        if (nodeResponse.available) {
            tracked.candidateNodes.add(nodeResponse)
        }
        
        betaLogger.log(
            com.ustadmobile.meshrabiya.beta.LogLevel.INFO,
            "DistributedComputeClient",
            "Received compute node response: taskId=$taskId, node=${nodeResponse.nodeAddress}, available=${nodeResponse.available}"
        )
    }

    /**
     * Select best compute node and assign task.
     * Called after response collection timeout.
     *
     * @param taskId Task identifier
     */
    suspend fun selectAndAssignNode(taskId: String) {
        val tracked = activeRequests[taskId] ?: run {
            betaLogger.log(
                com.ustadmobile.meshrabiya.beta.LogLevel.ERROR,
                "DistributedComputeClient",
                "Cannot assign task - not found: $taskId"
            )
            return
        }
        
        if (tracked.candidateNodes.isEmpty()) {
            betaLogger.log(
                com.ustadmobile.meshrabiya.beta.LogLevel.WARN,
                "DistributedComputeClient",
                "No capable nodes for task: $taskId - will retry"
            )
            // TODO: Implement retry with backoff
            return
        }
        
        // Rank candidates by design decision weights
        // latency 30%, currentLoad 25%, (ML capabilities handled by available flag)
        val ranked = tracked.candidateNodes.sortedWith(
            compareBy<ComputeNodeResponse> { it.estimatedLatencyMs } // 30% - ascending
                .thenBy { it.currentLoad } // 25% - ascending
                .thenByDescending { it.mlKitFeatures.size } // ML capability
                .thenByDescending { if (it.mlKitCustomSupport) 1 else 0 } // Custom ML support
        )
        
        val selected = ranked.first()
        betaLogger.log(
            com.ustadmobile.meshrabiya.beta.LogLevel.INFO,
            "DistributedComputeClient",
            "Selected compute node: taskId=$taskId, node=${selected.nodeAddress}"
        )
        
        assignTaskToNode(taskId, selected)
    }

    /**
     * Assign task to selected compute node.
     *
     * @param taskId Task identifier
     * @param node Selected compute node
     */
    private suspend fun assignTaskToNode(taskId: String, node: ComputeNodeResponse) {
        val tracked = activeRequests[taskId] ?: return
        
        // Update status
        tracked.status = TaskRequestStatus.ASSIGNED
        tracked.selectedNodeAddress = node.nodeAddress.toString()
        
        // Create assignment message
        val assignmentMessage = TaskAssignmentMessage(
            taskId = taskId,
            executorNodeId = node.nodeAddress,
            requesterNodeId = virtualNode.addressAsInt,
            // callbackAddress = virtualNode.addressAsInt.toString(),  // Callback to requester
            executorType = tracked.request.taskType,  // LocalComputeTaskRequest.taskType (executor class name)
            // jobType = "compute",
            executionContext = emptyMap(),  // Will be properly implemented in Part 2
            // resourceLimits = emptyMap(),
            inputFiles = emptyList(),
            // outputRequirements = emptyMap(),
            assignedAt = System.currentTimeMillis(),
            owner = MeshrabiyaApiImpl.getInstance().getUserInfo().entry,
            recipients = tracked.recipients
        )
        
        // Send direct message to selected node
        virtualNode.sendEcosystemMessage(node.nodeAddress, assignmentMessage.toBytes())
        
        betaLogger.log(
            com.ustadmobile.meshrabiya.beta.LogLevel.INFO,
            "DistributedComputeClient",
            "Sent task assignment: taskId=$taskId, node=${node.nodeAddress}"
        )
    }

    /**
     * Handle task acceptance message from compute node.
     *
     * @param message TaskAcceptanceMessage from compute node
     */
    fun handleTaskAcceptanceMessage(message: TaskAcceptanceMessage) {
        val tracked = activeRequests[message.taskId] ?: run {
            betaLogger.log(
                com.ustadmobile.meshrabiya.beta.LogLevel.WARN,
                "DistributedComputeClient",
                "Received acceptance for unknown task: ${message.taskId}"
            )
            return
        }
        
        // Update status
        tracked.status = TaskRequestStatus.ACCEPTED
        tracked.taskPublicKey = message.publicKey
        
        betaLogger.log(
            com.ustadmobile.meshrabiya.beta.LogLevel.INFO,
            "DistributedComputeClient",
            "Task accepted: taskId=${message.taskId}, node=${message.computeNodeAddress}"
        )
    }

    /**
     * Handle task completion message from compute node.
     *
     * @param message TaskCompletedMessage from compute node
     */
    suspend fun handleTaskCompletionMessage(message: TaskCompletedMessage) {
        val tracked = activeRequests[message.taskId] ?: run {
            betaLogger.log(
                com.ustadmobile.meshrabiya.beta.LogLevel.WARN,
                "DistributedComputeClient",
                "Received completion for unknown task: ${message.taskId}"
            )
            return
        }
        
        // Update status based on completion status
        tracked.status = when (message.status) {
            "SUCCESS" -> TaskRequestStatus.COMPLETED
            else -> TaskRequestStatus.FAILED
        }
        tracked.completionMessage = message
        
        betaLogger.log(
            com.ustadmobile.meshrabiya.beta.LogLevel.INFO,
            "DistributedComputeClient",
            "Task completed: taskId=${message.taskId}, status=${message.status}"
        )
        
        // Download result files if any
        if (message.status == "SUCCESS" && message.resultStorageRefs.isNotEmpty()) {
            for (fileRef in message.resultStorageRefs) {
                betaLogger.log(
                    com.ustadmobile.meshrabiya.beta.LogLevel.INFO,
                    "DistributedComputeClient",
                    "Result file available: $fileRef"
                )
                // TODO: Call DistributedStorageClient.retrieveFile() when needed
            }
        }
        
        // Send acknowledgment as direct message to executor node
        val ackMessage = TaskCompletionAckMessage(taskId = message.taskId)
        val executorAddress = message.executorNodeId.toInt()
        virtualNode.sendEcosystemMessage(executorAddress, ackMessage.toBytes())
        
        // Notify UI via API
        // TODO: Call MeshrabiyaAPI callback with completion details
        
        betaLogger.log(
            com.ustadmobile.meshrabiya.beta.LogLevel.INFO,
            "DistributedComputeClient",
            "Sent task completion acknowledgment: taskId=${message.taskId}"
        )
    }

    /**
     * Tracked request with response collection
     */
    private data class TrackedRequest(
        val request: LocalComputeTaskRequest,
        var status: TaskRequestStatus,
        val createdAt: Long,
        val candidateNodes: MutableList<ComputeNodeResponse> = mutableListOf(),
        var selectedNodeAddress: String? = null,
        var taskPublicKey: String? = null,
        var completionMessage: TaskCompletedMessage? = null,
        var recipients: List<RecipientEntry> = emptyList()
    )

    /**
     * Task request status
     */
    private enum class TaskRequestStatus {
        PENDING,
        ASSIGNED,
        ACCEPTED,
        COMPLETED,
        FAILED
    }
}
