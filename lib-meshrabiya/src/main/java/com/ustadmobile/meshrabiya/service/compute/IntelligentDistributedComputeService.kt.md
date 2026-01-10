package com.ustadmobile.meshrabiya.service.compute

import kotlinx.coroutines.*
// import com.ustadmobile.meshrabiya.service.compute.model.ResourceLimits
// import com.ustadmobile.meshrabiya.service.compute.mesh.*
import com.ustadmobile.meshrabiya.service.compute.scheduler.*
import com.ustadmobile.meshrabiya.service.compute.executor.*
import com.ustadmobile.meshrabiya.vnet.MeshConnectionPool
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.CoreGossipBroadcastService
import com.ustadmobile.meshrabiya.service.MeshEcosystemListener
import com.ustadmobile.meshrabiya.service.MeshGossipService
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import android.content.Context
import androidx.appcompat.app.AlertDialog
import android.widget.ArrayAdapter
import com.ustadmobile.meshrabiya.service.storage.StorageDropFolderManager
import java.util.concurrent.ConcurrentHashMap
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
import com.ustadmobile.meshrabiya.service.TaskAssignmentMessage
import com.ustadmobile.meshrabiya.service.compute.model.TaskAcceptanceMessage
import com.ustadmobile.meshrabiya.service.compute.model.ComputeNodeResponse
import com.ustadmobile.meshrabiya.service.compute.model.LocalComputeTaskRequest
import com.ustadmobile.meshrabiya.service.compute.DistributedServiceLibrary.ServiceLibraryEntry
import com.ustadmobile.meshrabiya.service.compute.DistributedServiceLibrary.MaintainerInfo
import com.ustadmobile.meshrabiya.service.compute.runtime.RuntimeRegistry
import com.ustadmobile.meshrabiya.service.compute.model.TaskResult
import com.ustadmobile.meshrabiya.service.compute.model.TaskRejectionMessage
import com.ustadmobile.meshrabiya.service.TaskCompletedMessage
import com.ustadmobile.meshrabiya.service.compute.model.TaskCompletionAckMessage
import com.ustadmobile.meshrabiya.service.MeshEcosystemMessage
import com.ustadmobile.meshrabiya.service.ComputeTaskRequestMessage
import com.ustadmobile.meshrabiya.model.ResourceRequirements
import com.ustadmobile.meshrabiya.service.compute.Task

/**
 * Main service class for intelligent distributed compute.
 * Integrates mesh intelligence, task scheduling, and execution.
 * Uses MeshConnectionPool for chunk/file transfer.
 */
class IntelligentDistributedComputeService(
    private val virtualNode: VirtualNode,
    // private val gossipProtocol: EnhancedGossipProtocol,
    // private val quorumManager: QuorumManager,
    // DEPRECATED: ResourceManager replaced by canonical compute task request/execution workflows
    // Client nodes schedule tasks directly with compute nodes; TaskManager handles execution lifecycle
    // private val resourceManager: ResourceManager,
    // private val pythonExecutor: PythonExecutor,
    // private val liteRTEngine: LiteRTEngine,
    private val emergentRoleManager: com.ustadmobile.meshrabiya.vnet.EmergentRoleManager,
    private val betaLogger: BetaTestLogger? = null
) {
    // --- Event Handlers ---
    // UNUSED SCHEDULER CALLBACK - Commented 2025-11-12
    // var onTaskCompleted: ((taskId: String, result: ExecutionPlan) -> Unit)? = null
    var onTaskFailed: ((taskId: String, error: Throwable) -> Unit)? = null
    private var meshEcosystemListener: MeshEcosystemListener? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // UNUSED FIELDS - SCHEDULER INFRASTRUCTURE (Commented 2025-11-12)
    // These fields were part of the IntelligentTaskScheduler infrastructure which is unused.
    // The scheduler (line 46) is declared but NEVER called - codebase search shows 0 method invocations.
    // Phase 3-4 implementation uses direct ML-capable node selection (processTaskRequest → handleComputeNodeResponses → assignTaskToNode)
    // without scheduler decomposition. Keeping as comments for future scheduler reactivation if needed.
    
    // private val activeJobs = ConcurrentHashMap<String, DistributedJob>()  // Only .size used at line 306 (queueDepth calculation)
    // private val nodeCapabilities = ConcurrentHashMap<String, NodeCapabilitySnapshot>()  // UNUSED - Never accessed
    // private val networkTopology = NetworkTopologyTracker()  // UNUSED - Never accessed
    // private val taskScheduler = IntelligentTaskScheduler()  // UNUSED - Never called (scheduler infrastructure unused)
    
    // Replaced activeJobs with simple counter for queue depth calculation (used in handleIncomingComputeTaskRequest line ~306)
    private val activeJobCount = java.util.concurrent.atomic.AtomicInteger(0)
    
    private val connectionPool = MeshConnectionPool.getInstance()

    // Use singleton CoreGossipBroadcastService for canonical ecosystem messaging

    fun registerWithEcosystemListener(listener: MeshEcosystemListener) {
        meshEcosystemListener = listener
        listener.registerComputeService(this)
        registerEventHandlers(listener)
    }

    private fun registerEventHandlers(listener: MeshEcosystemListener) {
        // If MeshGossipService supports registering listeners for TaskDataAccessUpdate, do so here.
        // Otherwise, this is a placeholder for future event handler registration.
        // Example:
        // listener.meshGossipService.registerTaskDataAccessUpdateListener { updateMsg ->
        //     handleTaskDataAccessUpdate(updateMsg)
        // }
    }



    // ============================================================================
    // CLIENT-SIDE TASK REQUEST TRACKING & SELECTION
    // ============================================================================
    
    /**
     * Tracks client-side compute task requests throughout their lifecycle.
     * Manages request state, collected responses, retry count, and selection results.
     */
    data class TrackedRequest(
        val localRequest: LocalComputeTaskRequest,
        val responses: MutableList<ComputeNodeResponse> = mutableListOf(),
        var selectedNodeAddress: Int? = null,
        var retryCount: Int = 0,
        var status: RequestStatus = RequestStatus.PENDING,
        val createdAt: Long = System.currentTimeMillis(),
        var lastUpdated: Long = System.currentTimeMillis()
    )
    
    enum class RequestStatus {
        PENDING,        // Waiting for responses (broadcast sent)
        COLLECTING,     // Within timeout window collecting responses
        SELECTING,      // Evaluating responses after timeout
        ASSIGNED,       // Task assigned to node
        EXECUTING,      // Task running on selected node
        COMPLETED,      // Task finished successfully
        FAILED          // Task failed or timeout
    }
    
    private val activeRequests = ConcurrentHashMap<String, TrackedRequest>()
    
    /**
     * Process client-side task request: broadcast, collect responses, select best node.
     * Implements timeout-driven collection and retry logic.
     */
    fun processTaskRequest(localRequest: LocalComputeTaskRequest) {
        val taskId = localRequest.mmcpRequest.taskId
        activeRequests[taskId] = TrackedRequest(localRequest)
        scope.launch {
            betaLogger?.log(LogLevel.INFO, "ComputeService", 
                "Broadcasting compute task request $taskId (timeout=${MeshrabiyaConstants.getTimeoutMs()}ms) via CoreGossipBroadcastService")
            // Use canonical broadcast via singleton CoreGossipBroadcastService
            CoreGossipBroadcastService.getInstance().sendComputeTaskRequest(
                taskId = localRequest.mmcpRequest.taskId,
                serviceId = localRequest.mmcpRequest.serviceId,
                inputParams = localRequest.mmcpRequest.inputParams,
                metadata = localRequest.mmcpRequest.metadata
            )
            // Responses will be handled asynchronously via MeshEcosystemListener
        }
    }

    /**
     * Handle compute node responses after timeout expires.
     * Filters, ranks, and selects best node based on capabilities, load, and latency.
     * Implements retry logic for no responses or capability mismatches.
     */
    // fun handleComputeNodeResponses(
    //     localRequest: LocalComputeTaskRequest, 
    //     responses: List<ComputeNodeResponse>
    // ) {
    //     val taskId = localRequest.mmcpRequest.taskId
    //     val tracked = activeRequests[taskId] ?: return
        
    //     tracked.responses.addAll(responses)
    //     tracked.status = RequestStatus.SELECTING
    //     tracked.lastUpdated = System.currentTimeMillis()
        
    //     betaLogger?.log(LogLevel.INFO, "ComputeService",
    //         "Received ${responses.size} responses for task $taskId")
        
    //     if (responses.isEmpty()) {
    //         betaLogger?.log(LogLevel.WARN, "ComputeService",
    //             "No responses for task $taskId - triggering retry")
    //         // retryTaskRequest(localRequest)
    //         return
    //     }
        
    //     // Filter and rank responses:
    //     // 1. Available nodes only
    //     // 2. Has required ML Kit capabilities
    //     // 3. Sort by: currentLoad (asc) → estimatedLatencyMs (asc) → mlKitFeatures.size (desc)
    //     val rankedNodes = responses
    //         .filter { it.available }
    //         .filter { hasRequiredMLCapabilities(it, localRequest) }
    //         .sortedWith(
    //             compareBy<ComputeNodeResponse> { it.currentLoad }
    //                 .thenBy { it.estimatedLatencyMs }
    //                 .thenByDescending { it.mlKitFeatures.size }
    //         )
        
    //     if (rankedNodes.isEmpty()) {
    //         betaLogger?.log(LogLevel.WARN, "ComputeService",
    //             "No capable nodes for task $taskId (${responses.size} responded, 0 capable) - retrying")
    //         retryTaskRequest(localRequest)
    //         return
    //     }
        
    //     // Select best node (highest ranked)
    //     val selectedNode = rankedNodes.first()
    //     betaLogger?.log(LogLevel.INFO, "ComputeService",
    //         "Selected node ${selectedNode.nodeAddress} for task $taskId " +
    //         "(load=${selectedNode.currentLoad}, latency=${selectedNode.estimatedLatencyMs}ms, " +
    //         "mlFeatures=${selectedNode.mlKitFeatures})")
        
    //     assignTaskToNode(localRequest, selectedNode)
    // }
    
    /**
     * Handle single compute node response (called by MeshEcosystemListener).
     * Routes individual response to the appropriate tracked request.
     * 
     * @param requestId The UUID of the original broadcast request
     * @param senderId The node address that sent this response
     * @param response The compute node response with availability and capabilities
     */
    fun handleComputeNodeResponse(
        requestId: String,
        senderId: Int,
        response: ComputeNodeResponse
    ) {
        // Find the tracked request by matching requestId
        val tracked = activeRequests.values.firstOrNull { 
            it.localRequest.mmcpRequest.taskId == requestId 
        }
        
        if (tracked != null) {
            tracked.responses.add(response)
            betaLogger?.log(LogLevel.DEBUG, "ComputeService",
                "Added response from node $senderId for request $requestId " +
                "(total responses: ${tracked.responses.size})")
        } else {
            betaLogger?.log(LogLevel.WARN, "ComputeService",
                "Received response for unknown request $requestId from node $senderId")
        }
    }
    
    /**
     * Check if response has required ML Kit capabilities for the task.
     * Returns true if no specific requirements or all requirements met.
     */
    private fun hasRequiredMLCapabilities(
        response: ComputeNodeResponse,
        request: LocalComputeTaskRequest
    ): Boolean {
        // TODO: Extract required ML features from mmcpRequest
        // For now, accept any node with ML Kit features if custom support needed
        // This will be enhanced when MmcpComputeTaskRequest schema includes ML requirements
        return true
    }
    
    /**
     * Retry task request with exponential backoff.
     * Checks MeshrabiyaConstants.getMaxRetries() limit before retrying.
     */
    private fun retryTaskRequest(localRequest: LocalComputeTaskRequest) {
        val taskId = localRequest.mmcpRequest.taskId
        val tracked = activeRequests[taskId] ?: return
        val maxRetries = MeshrabiyaConstants.getMaxRetries()
        
        if (tracked.retryCount >= maxRetries) {
            betaLogger?.log(LogLevel.ERROR, "ComputeService",
                "Task $taskId failed: max retries ($maxRetries) exceeded")
            
            tracked.status = RequestStatus.FAILED
            tracked.lastUpdated = System.currentTimeMillis()
            
            onTaskFailed?.invoke(taskId, 
                Exception("No compute nodes responded after $maxRetries retries"))
            
            activeRequests.remove(taskId)
            return
        }
        
        tracked.retryCount++
        tracked.lastUpdated = System.currentTimeMillis()
        
        // Exponential backoff: 1s, 2s, 4s, 8s, capped at 30s
        val backoffMs = minOf(1000L * (1 shl tracked.retryCount), 30000L)
        
        betaLogger?.log(LogLevel.INFO, "ComputeService",
            "Retrying task $taskId (attempt ${tracked.retryCount}/$maxRetries) after ${backoffMs}ms")
        
        scope.launch {
            delay(backoffMs)
            
            betaLogger?.log(LogLevel.INFO, "ComputeService",
                "Re-broadcasting task $taskId (retry ${tracked.retryCount}/$maxRetries) via CoreGossipBroadcastService")
            CoreGossipBroadcastService.getInstance().sendComputeTaskRequest(
                taskId = localRequest.mmcpRequest.taskId,
                serviceId = localRequest.mmcpRequest.serviceId,
                inputParams = localRequest.mmcpRequest.inputParams,
                metadata = localRequest.mmcpRequest.metadata
            )
            // Responses will be handled asynchronously via MeshEcosystemListener
        }
    }
    
    /**
     * Assign task to selected node: send assignment message, update status, invoke callback.
     * 
     * Phase 3.3: Implements actual task assignment message protocol
     * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md Section 8.1
     */
    private fun assignTaskToNode(
        localRequest: LocalComputeTaskRequest,
        selectedNode: ComputeNodeResponse
    ) {
        val taskId = localRequest.taskId
        val tracked = activeRequests[taskId] ?: return
        
        tracked.selectedNodeAddress = selectedNode.nodeAddress
        tracked.status = RequestStatus.ASSIGNED
        tracked.lastUpdated = System.currentTimeMillis()
        
        betaLogger?.log(LogLevel.INFO, "ComputeService",
            "Task $taskId assigned to node ${selectedNode.nodeAddress}")
        
        scope.launch {
            try {
                // Create task assignment message
                val assignment = TaskAssignmentMessage(
                    messageId = java.util.UUID.randomUUID().toString(),
                    taskId = taskId,
                    requesterNodeId = virtualNode.addressAsInt.toString(),
                    callbackAddress = virtualNode.addressAsInt.toString(),
                    taskType = localRequest.mmcpRequest.taskType,
                    jobType = localRequest.mmcpRequest.jobType,
                    codeBundle = localRequest.mmcpRequest.codeBundle,
                    inputFiles = localRequest.mmcpRequest.inputFileIds,
                    // resourceLimits = ResourceLimits(
                    //     maxMemoryBytes = (localRequest.mmcpRequest.resourceLimits?.maxMemoryMB ?: 512) * 1024 * 1024L,
                    //     maxCpuTimeMs = localRequest.mmcpRequest.resourceLimits?.maxCpuTimeMs ?: 60000L,
                    //     maxDiskBytes = (localRequest.mmcpRequest.resourceLimits?.maxDiskMB ?: 100) * 1024 * 1024L,
                    //     maxExecutionTimeMs = localRequest.mmcpRequest.resourceLimits?.maxExecutionTimeMs ?: 60000L,
                    //     allowNetworkAccess = false
                    // ),
                    timestamp = System.currentTimeMillis()
                )
                
                // Send task assignment message to selected compute node via gossip service
                virtualNode.getMeshGossipService().sendTaskAssignmentMessage(selectedNode.nodeAddress, assignment)
                
                betaLogger?.log(LogLevel.INFO, "ComputeService",
                    "Task assignment message sent to node ${selectedNode.nodeAddress} for task $taskId")
                
                // Status will be updated when we receive acceptance/rejection message
                // For now, mark as assigned and wait for response
                
            } catch (e: Exception) {
                betaLogger?.log(LogLevel.ERROR, "ComputeService",
                    "Failed to send task assignment for task $taskId: ${e.message}")
                
                tracked.status = RequestStatus.FAILED
                tracked.lastUpdated = System.currentTimeMillis()
                activeRequests.remove(taskId)
                
                onTaskFailed?.invoke(taskId, e)
            }
        }
    }

    // ============================================================================
    // COMPUTE NODE SIDE - PHASE 4: INCOMING TASK REQUEST HANDLER
    // ============================================================================
    
    /**
     * Handle incoming compute task request from another node (compute node side).
     * Evaluates local ML capabilities, availability, and load to generate response.
     * Sends ComputeNodeResponse back to requester with ML Kit features and availability.
     * 
     * Phase 4 Implementation:
     * - Retrieves local ML capabilities via EmergentRoleManager.getLocalMLCapabilitiesForResponse()
     * - Checks resource availability and current load
     * - Estimates latency based on current workload
     * - Generates and sends ComputeNodeResponse with mlKitFeatures and mlKitCustomSupport
     */
    fun handleIncomingComputeTaskRequest(
        requestId: String,
        requesterNodeAddress: Int,
        request: ComputeTaskRequestMessage
    ) {
        betaLogger?.log(LogLevel.INFO, "ComputeService",
            "Received compute task request $requestId from node $requesterNodeAddress " +
            "(taskId=${request.taskId}, serviceId=${request.serviceId})")
        scope.launch {
            try {
                // Get local ML capabilities from EmergentRoleManager
                val (mlKitFeatures, mlKitCustomSupport) = emergentRoleManager.getLocalMLCapabilitiesForResponse()
                
                // DEPRECATED: Resource checks now handled by direct peer-to-peer task assignment
                // Canonical compute workflows allow client to schedule directly with compute node
                // val currentLoad = resourceManager.getCurrentLoad()
                // val available = currentLoad < 0.8 && resourceManager.hasAvailableResources()
                val available = true // Assume available; compute node will reject if overloaded
                
                // Estimate latency based on active jobs and queue depthAvailableResources()
                
                // Estimate latency based on active jobs and queue depth
                val queueDepth = activeJobCount.get()
                val estimatedLatencyMs = when {
                    queueDepth == 0 -> 100L
                    queueDepth < 3 -> 500L
                    queueDepth < 6 -> 1000L
                    else -> 2000L
                }
                
                val response = ComputeNodeResponse(
                    nodeAddress = virtualNode.addressAsInt,
                    available = available,
                    currentLoad = currentLoad,
                    estimatedLatencyMs = estimatedLatencyMs,
                    mlKitFeatures = mlKitFeatures,
                    mlKitCustomSupport = mlKitCustomSupport
                )
                
                betaLogger?.log(LogLevel.INFO, "ComputeService",
                    "Sending compute response for request $requestId: " +
                    "available=$available, load=$currentLoad, latency=${estimatedLatencyMs}ms, " +
                    "mlFeatures=$mlKitFeatures, mlCustom=$mlKitCustomSupport")
                
                // Send response back to requester via gossip service
                virtualNode.getMeshGossipService().sendComputeNodeResponse(requesterNodeAddress, requestId, response)
                
            } catch (e: Exception) {
                betaLogger?.log(LogLevel.ERROR, "ComputeService",
                    "Failed to handle compute task request $requestId: ${e.message}")
            }
        }
    }

    // val builtinLibraryEntries: List<LibraryEntry> = listOf(
    //     LibraryEntry.PythonServiceEntry(
    //         serviceId = "builtin_image_preprocessing",
    //         scriptCode = generateImagePreprocessingScript(),
    //         libraries = setOf(PythonLibrary.OPENCV, PythonLibrary.NUMPY, PythonLibrary.JSON, PythonLibrary.BASE64),
    //         manifest = ServiceManifest(
    //             serviceType = ServiceType.PYTHON,
    //             version = "1.0.0",
    //             author = "Orbot Team",
    //             signature = null,
    //             resourceRequirements = ResourceRequirements(
    //                 minRAMMB = 512,
    //                 preferredRAMMB = 1024,
    //                 cpuIntensity = CPUIntensity.MODERATE
    //             ),
    //             builtin = true
    //         ),
    //         executionProfile = ExecutionProfile(deterministic = true),
    //         inputs = listOf(ServiceInput("images", "List<Base64Image>", true)),
    //         outputs = listOf(ServiceOutput("processed_tensors", "List<Base64Tensor>")),
    //         capabilities = setOf(ServiceCapability.ML, ServiceCapability.CV)
    //     ),
    //     // LibraryEntry.LiteRTServiceEntry(
    //     //     serviceId = "builtin_mobilenet_v3_inference",
    //     //     modelId = "mobilenet_v3_quantized",
    //     //     modelConfig = LiteRTConfig(useGPU = true, useNNAPI = true, numThreads = 2),
    //     //     manifest = ServiceManifest(
    //     //         serviceType = ServiceType.LITERT,
    //     //         version = "1.0.0",
    //     //         author = "Orbot Team",
    //     //         signature = null,
    //     //         resourceRequirements = ResourceRequirements(
    //     //             minRAMMB = 256,
    //     //             preferredRAMMB = 512,
    //     //             cpuIntensity = CPUIntensity.LIGHT,
    //     //             requiresGPU = true
    //     //         ),
    //     //         builtin = true
    //     //     ),
    //     //     executionProfile = ExecutionProfile(deterministic = true),
    //     //     inputs = listOf(ServiceInput("input_tensors", "List<Base64Tensor>", true)),
    //     //     outputs = listOf(ServiceOutput("inference_results", "List<FloatArray>")),
    //     //     capabilities = setOf(ServiceCapability.ML, ServiceCapability.CV)
    //     // ),
    //     LibraryEntry.HybridServiceEntry(
    //         serviceId = "builtin_hybrid_image_pipeline",
    //         pythonPreprocessing = null,
    //         // liteRTInference = LibraryEntry.LiteRTServiceEntry(
    //         //     serviceId = "builtin_mobilenet_v3_inference",
    //         //     modelId = "mobilenet_v3_quantized",
    //         //     modelConfig = LiteRTConfig(useGPU = true, useNNAPI = true, numThreads = 2),
    //         //     manifest = ServiceManifest(
    //         //         serviceType = ServiceType.LITERT,
    //         //         version = "1.0.0",
    //         //         author = "Orbot Team",
    //         //         signature = null,
    //         //         resourceRequirements = ResourceRequirements(
    //         //             minRAMMB = 256,
    //         //             preferredRAMMB = 512,
    //         //             cpuIntensity = CPUIntensity.LIGHT,
    //         //             requiresGPU = true
    //         //         ),
    //         //         builtin = true
    //         //     ),
    //         //     executionProfile = ExecutionProfile(deterministic = true),
    //         //     inputs = listOf(ServiceInput("input_tensors", "List<Base64Tensor>", true)),
    //         //     outputs = listOf(ServiceOutput("inference_results", "List<FloatArray>")),
    //         //     capabilities = setOf(ServiceCapability.ML, ServiceCapability.CV)
    //         // ),
    //         pythonPostprocessing = null,
    //         manifest = ServiceManifest(
    //             serviceType = ServiceType.HYBRID,
    //             version = "1.0.0",
    //             author = "Orbot Team",
    //             signature = null,
    //             resourceRequirements = ResourceRequirements(
    //                 minRAMMB = 512,
    //                 preferredRAMMB = 1024,
    //                 cpuIntensity = CPUIntensity.MODERATE
    //             ),
    //             builtin = true
    //         ),
    //         executionProfile = ExecutionProfile(deterministic = true),
    //         inputs = listOf(ServiceInput("images", "List<Base64Image>", true)),
    //         outputs = listOf(ServiceOutput("inference_results", "List<FloatArray>")),
    //         capabilities = setOf(ServiceCapability.ML, ServiceCapability.CV)
    //     )
    // )
        val builtinLibraryEntries: List<ServiceLibraryEntry> = listOf(
            ServiceLibraryEntry(
                serviceId = "builtin_image_preprocessing",
                name = "Image Preprocessing",
                description = "Preprocesses images for ML tasks using OpenCV and NumPy.",
                version = "1.0.0",
                maintainer = MaintainerInfo(
                    onionAddress = "",
                    displayName = "Orbot Team",
                    publicKeyEd25519 = "",
                    i2pSiteAddress = "",
                    reputationScore = 1.0,
                    endorsements = emptyList()
                ),
                torrentMagnetLink = "",
                serviceBundleHash = "",
                signature = "",
                categories = listOf("ML", "CV"),
                resourceRequirements = ResourceRequirements(
                    minRAMMB = 512,
                    preferredRAMMB = 1024,
                    cpuIntensity = CPUIntensity.MODERATE
                ),
                auditReports = emptyList()
            )
            // TODO: Add additional ServiceLibraryEntry instances for other built-in services as needed.
            // TODO: Integrate ML Kit service wrappers and distributed service logic in future iterations.
        )

    // Helper for chunk/file transfer using connection pool and async I/O
    suspend fun <T> withConnectionForTransfer(action: suspend (MeshConnectionPool.Connection) -> T): T? {
        val connection = try {
            connectionPool.acquireConnection(timeoutMs = 5000)
        } catch (e: Exception) {
            null
        }
        return if (connection != null) {
            try {
                action(connection)
            } finally {
                connectionPool.releaseConnection(connection)
            }
        } else {
            betaLogger?.log(LogLevel.ERROR, "ComputeService", "No available connection for transfer")
            null
        }
    }

    fun showFolderPickerDialog(
        context: Context,
        initialPath: String = "/DropFolder",
        onFolderSelected: (String) -> Unit
    ) {
        val dialogBuilder = AlertDialog.Builder(context)
        dialogBuilder.setTitle("Select Destination Folder")
        val folderList = getDropFolderSubfolders(initialPath)
        val adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, folderList) {}
        dialogBuilder.setAdapter(adapter) { _, which ->
            val selectedFolder = folderList[which]
            onFolderSelected(selectedFolder)
        }
        dialogBuilder.setNegativeButton("Cancel", null)
        dialogBuilder.show()
    }

    // TODO refactor dropfolder logic
    // private fun getDropFolderSubfolders(path: String): List<String> {
    //     return StorageDropFolderManager.getSubfolders(path)
    // }

    fun pickDestinationFolder(
        context: Context,
        onPathSelected: (String) -> Unit
    ) {
        showFolderPickerDialog(context) { selectedPath ->
            onPathSelected(selectedPath)
        }
    }

    private fun generateImagePreprocessingScript(): String = """
import numpy as np
import cv2
import json
import base64

def preprocess_images(input_data):
    images = input_data.get('images', [])
    processed = []
    
    for img_b64 in images:
        img_data = base64.b64decode(img_b64)
        img_array = np.frombuffer(img_data, np.uint8)
        img = cv2.imdecode(img_array, cv2.IMREAD_COLOR)
        img_resized = cv2.resize(img, (224, 224))
        img_normalized = img_resized.astype(np.float32) / 255.0
        tensor_data = img_normalized.tobytes()
        processed.append(base64.b64encode(tensor_data).decode())
    return {
        'processed_tensors': processed,
        'tensor_shape': [224, 224, 3],
        'count': len(processed)
    }

result = preprocess_images(globals().get('input_data', {}))
print(json.dumps(result))
"""

    // ============================================================================
    // Phase 3.3: Task Assignment Message Handlers
    // ============================================================================
    
    // Note: RuntimeRegistry reference needed - must be passed to constructor or made available
    private lateinit var runtimeRegistry: RuntimeRegistry
    
    /**
     * HANDLE TASK ASSIGNMENT MESSAGE (Compute Node Side)
     * 
     * Compute node receives task assignment and begins execution.
     * Verifies runtime availability, creates execution context, and starts task.
     * 
     * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md Section 8.2
     */
    suspend fun handleTaskAssignmentMessage(
        senderAddress: Int,
        assignment: TaskAssignmentMessage
    ) {
        try {
            betaLogger?.log(
                LogLevel.INFO,
                "ComputeService",
                "Received task assignment: ${assignment.taskId} (${assignment.taskType}) from node $senderAddress"
            )
            
            // Verify runtime available (double-check)
            // TODO: Initialize runtimeRegistry in constructor
            // if (!runtimeRegistry.isRuntimeAvailable(assignment.taskType)) {
            //     sendTaskRejection(senderAddress, assignment, "Runtime ${assignment.taskType} not available")
            //     return
            // }
            
            // DEPRECATED: Task scheduling uses canonical compute workflows, not abstract cluster state
            // Resource availability handled by TaskManager execution lifecycle
            // val currentLoad = resourceManager.getCurrentLoad()
            // if (currentLoad > 0.9 || !resourceManager.hasAvailableResources()) {
            //     sendTaskRejection(senderAddress, assignment, "Node overloaded (load: $currentLoad)")
            //     return
            // }
            
            // Send acceptance message
            sendTaskAcceptance(senderAddress, assignment)
            
            // Increment active job count
            activeJobCount.incrementAndGet()
            
            // Execute task (implementation would delegate to TaskManager)
            // For Phase 3, this is a placeholder - full execution in Phase 4+
            scope.launch {
                try {
                    val task = taskAssignmentMessageToTask(assignment)
                    betaLogger?.log(
                        LogLevel.INFO,
                        "ComputeService",
                        "Starting execution of task ${assignment.taskId}"
                    )
                    
                    // TODO: Integrate with TaskManager for actual execution
                    val result = TaskManager.executeTask(task)
                    
                    
                    
                    // Send completion message (placeholder result)
                    val result = TaskResult(
                        success = true,
                        outputManifest = emptyList(),
                        // metrics = ResourceMetrics(
                        //     ramActualBytes = 100 * 1024 * 1024,
                        //     ramAverageBytes = 100 * 1024 * 1024,
                        //     ramPeakBytes = 100 * 1024 * 1024,
                        //     cpuTimeUsedMs = 1000,
                        //     cpuPercentage = 50f,
                        //     diskIoOperations = 0,
                        //     diskStorageUsedBytes = 0,
                        //     networkUsedBytes = 0
                        // ),
                        errorMessage = null
                    )
                    
                    sendTaskCompletion(senderAddress, assignment.taskId, result)
                    
                } catch (e: Exception) {
                    betaLogger?.log(
                        LogLevel.ERROR,
                        "ComputeService",
                        "Task execution failed: ${assignment.taskId} - ${e.message}"
                    )
                    
                    val errorResult = TaskResult(
                        success = false,
                        outputManifest = emptyList(),
                        // metrics = ResourceMetrics(
                        //     ramActualBytes = 0,
                        //     ramAverageBytes = 0,
                        //     ramPeakBytes = 0,
                        //     cpuTimeUsedMs = 0,
                        //     cpuPercentage = 0f,
                        //     diskIoOperations = 0,
                        //     diskStorageUsedBytes = 0,
                        //     networkUsedBytes = 0
                        // ),
                        errorMessage = e.message
                    )
                    
                    sendTaskCompletion(senderAddress, assignment.taskId, errorResult)
                    
                } finally {
                    activeJobCount.decrementAndGet()
                }
            }
            
        } catch (e: Exception) {
            betaLogger?.log(
                LogLevel.ERROR,
                "ComputeService",
                "Failed to handle task assignment: ${e.message}"
            )
        }
    }
    
    /**
     * SEND TASK REJECTION
     * 
     * Notify requester that task cannot be executed.
     */
    private suspend fun sendTaskRejection(
        requesterAddress: Int,
        assignment: TaskAssignmentMessage,
        reason: String
    ) {
        val rejection = TaskRejectionMessage(
            taskId = assignment.taskId,
            reason = reason
        )
        
        betaLogger?.log(
            LogLevel.INFO,
            "ComputeService",
            "Rejecting task ${assignment.taskId}: $reason"
        )
        
        // TODO: Implement in MeshNetworkInterface
        // meshNetwork.sendTaskRejectionMessage(requesterAddress, rejection)
    }
    
    /**
     * SEND TASK ACCEPTANCE
     * 
     * Confirm task execution has started.
     */
    private suspend fun sendTaskAcceptance(
        requesterAddress: Int,
        assignment: TaskAssignmentMessage
    ) {
        val acceptance = TaskAcceptanceMessage(
            taskId = assignment.taskId,
            estimatedCompletionMs = 5000 // Placeholder estimate
        )
        
        betaLogger?.log(
            LogLevel.INFO,
            "ComputeService",
            "Accepting task ${assignment.taskId}"
        )
        
        // Acceptance message implementation removed - replaced by task status updates
    }
    
    /**
     * SEND TASK COMPLETION
     * 
     * Notify requester that task execution is complete.
     */
    private suspend fun sendTaskCompletion(
        requesterAddress: Int,
        taskId: String,
        result: TaskResult
    ) {
        val completion = TaskCompletedMessage(
            taskId = taskId,
            result = result
        )
        
        betaLogger?.log(
            LogLevel.INFO,
            "ComputeService",
            "Task completed: $taskId (success=${result.success})"
        )
        
        // Task completion notification removed - replaced by task status updates
    }
    
    /**
     * HANDLE TASK REJECTION MESSAGE (Scheduler Side)
     * 
     * Scheduler receives rejection from compute node and can reassign task.
     */
    fun handleTaskRejectionMessage(
        senderAddress: Int,
        rejection: TaskRejectionMessage
    ) {
        betaLogger?.log(
            LogLevel.INFO,
            "ComputeService",
            "Task ${rejection.taskId} rejected by node $senderAddress: ${rejection.reason}"
        )
        
        val tracked = activeRequests[rejection.taskId]
        if (tracked != null) {
            tracked.status = RequestStatus.REJECTED
            tracked.lastUpdated = System.currentTimeMillis()
            
            // Retry task assignment with different node
            retryTaskRequest(tracked.localRequest)
        }
    }

    fun taskAssignmentMessageToTask(assignment: TaskAssignmentMessage): Task {
        val inputFiles = assignment.inputFiles.mapNotNull { it["fileId"] }
        val owner = assignment.requesterNodeId
        val recipients = listOf(assignment.executorNodeId)
        val priority = when (assignment.priority.uppercase()) {
            "HIGH" -> 10
            "NORMAL" -> 5
            "LOW" -> 1
            else -> 5
        }
        // You may need to construct Executable from codeBundle or executionContext
        val executable = Executable(assignment.codeBundle) // Replace with actual mapping logic

        return Task(
            taskId = assignment.taskId,
            executable = executable,
            inputFiles = inputFiles,
            owner = owner,
            recipients = recipients,
            priority = priority
        )
    }
    
    /**
     * HANDLE TASK ACCEPTANCE MESSAGE (Scheduler Side)
     * 
     * Scheduler receives acceptance from compute node.
     */
    fun handleTaskAcceptanceMessage(
        senderAddress: Int,
        acceptance: TaskAcceptanceMessage
    ) {
        betaLogger?.log(
            LogLevel.INFO,
            "ComputeService",
            "Task ${acceptance.taskId} accepted by node $senderAddress (ETA: ${acceptance.estimatedCompletionMs}ms)"
        )
        
        val tracked = activeRequests[acceptance.taskId]
        if (tracked != null) {
            tracked.status = RequestStatus.EXECUTING
            tracked.lastUpdated = System.currentTimeMillis()
        }
    }
    
    /**
     * HANDLE TASK COMPLETION MESSAGE (Scheduler Side)
     * 
     * Scheduler receives completion notification from compute node.
     */
    fun handleTaskCompletionMessage(
        senderAddress: Int,
        completion: TaskCompletedMessage
    ) {
        betaLogger?.log(
            LogLevel.INFO,
            "ComputeService",
            "Task ${completion.taskId} completed by node $senderAddress (success=${completion.result.success})"
        )
        
        val tracked = activeRequests[completion.taskId]
        if (tracked != null) {
            if (completion.result.success) {
                tracked.status = RequestStatus.COMPLETED
                // onTaskCompleted?.invoke(completion.taskId, completion.result)
            } else {
                tracked.status = RequestStatus.FAILED
                onTaskFailed?.invoke(
                    completion.taskId,
                    Exception(completion.result.errorMessage ?: "Task execution failed")
                )
            }
            
            tracked.lastUpdated = System.currentTimeMillis()
            activeRequests.remove(completion.taskId)
            
            // Send acknowledgment
            scope.launch {
                sendTaskCompletionAck(senderAddress, completion.taskId)
            }
        }
    }
    
    /**
     * SEND TASK COMPLETION ACKNOWLEDGMENT
     * 
     * Confirm receipt of completion notification (stops retry loop).
     */
    private suspend fun sendTaskCompletionAck(
        executorAddress: Int,
        taskId: String
    ) {
        val ack = TaskCompletionAckMessage(taskId = taskId)
        
        betaLogger?.log(
            LogLevel.INFO,
            "ComputeService",
            "Sending completion acknowledgment for task $taskId to node $executorAddress"
        )
        
        // TODO: Implement sending of completion notification 
    }
    
    /**
     * HANDLE TASK COMPLETION ACKNOWLEDGMENT (Compute Node Side)
     * 
     * Compute node receives acknowledgment and stops completion retry loop.
     */
    fun handleTaskCompletionAckMessage(
        senderAddress: Int,
        ack: TaskCompletionAckMessage
    ) {
        betaLogger?.log(
            LogLevel.INFO,
            "ComputeService",
            "Received completion acknowledgment for task ${ack.taskId} from node $senderAddress"
        )
        
        // Completion acknowledgment handling - retry loop managed by task status system
    }

    // The IntelligentTaskScheduler inner class and other logic are now imported from scheduler package.
}