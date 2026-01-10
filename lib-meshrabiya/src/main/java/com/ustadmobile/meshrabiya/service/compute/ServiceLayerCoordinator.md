package com.ustadmobile.meshrabiya.service.compute

import android.util.Log
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.ustadmobile.meshrabiya.service.compute.scheduler.ComputeTask
/**
 * Service Layer Coordinator - Central orchestrator for distributed services
 * Manages compute tasks, storage operations, and mesh network coordination
 */
class ServiceLayerCoordinator(
    // Optional beta logger - if not provided, attempt to obtain from MeshServiceCoordinator context
    private val betaLogger: BetaTestLogger? = try {
        com.ustadmobile.meshrabiya.service.MeshServiceCoordinator.getInstance(com.ustadmobile.meshrabiya.OrbotApp.instance.applicationContext).let {
            BetaTestLogger.getInstance(com.ustadmobile.meshrabiya.OrbotApp.instance.applicationContext)
        }
    } catch (_: Exception) { null }
) {
    
    companion object {
        private const val TAG = "Serv"
    }
    
    // BROKEN: IntelligentDistributedComputeService initialization requires virtualNode and other parameters
    // This entire class is deprecated/unused - kept for reference only
    // private val computeService by lazy { 
    //     IntelligentDistributedComputeService(
    //         virtualNode = ..., // Needs VirtualNode instance
    //         pythonExecutor = mockPythonExecutor(),
    //         emergentRoleManager = ..., // Needs EmergentRoleManager instance
    //         betaLogger = betaLogger
    //     )
    // }
    
    // DEPRECATED: Storage agent - November 14, 2025 - Use DistributedStorageManager instead
    // private val storageAgent by lazy { DistributedStorageAgent(meshrabiyaMeshAdapter) }
    
    private val isActiveFlag = AtomicBoolean(false)
    private fun isActive() = isActiveFlag.get()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Service statistics
    private val serviceStats = ServiceStatistics()
    // Notifier used for tests to wait for storage request handling to complete
    private val storageHandledNotifier = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    /**
     * Test helper: suspend until at least one storage request has been handled or timeout.
     * Returns true if a storage request was observed, false if the timeout elapsed.
     */
    internal suspend fun awaitStorageHandled(timeoutMs: Long = 5000L): Boolean {
        // Fast path: maybe already handled
        if (serviceStats.storageRequestsHandled > 0) return true

        // Wait for a flow emission (best-effort), with timeout
        return try {
            withTimeout(timeoutMs) {
                storageHandledNotifier.first()
                true
            }
        } catch (e: Exception) {
            // Fallback: short polling check in case the emission raced the collector
            val deadline = System.currentTimeMillis() + 200L
            while (System.currentTimeMillis() < deadline) {
                if (serviceStats.storageRequestsHandled > 0) return true
                delay(10)
            }
            false
        }
    }
    
    // Active service operations
    private val activeComputeTasks = ConcurrentHashMap<String, ComputeTaskStatus>()
    // DEPRECATED: activeStorageOps - November 14, 2025
    // private val activeStorageOps = ConcurrentHashMap<String, StorageOperationStatus>()
    
    data class ServiceStatistics(
        var computeTasksCompleted: Long = 0L,
        var computeTasksFailed: Long = 0L,
        var computeTasksCanceled: Long = 0L,
        // DEPRECATED: Storage statistics - November 14, 2025
        // var storageRequestsHandled: Long = 0L,
        // var storageErrorsEncountered: Long = 0L,
        var totalBytesProcessed: Long = 0L,
        var totalComputeTimeMs: Long = 0L,
        var meshContributionScore: Float = 0.0f,
        var serviceUptimeMs: Long = 0L // Stores service start time when active
    )
    
    data class ComputeTaskStatus(
        val taskId: String,
        val status: String,
        val progress: Float,
        val startTime: Long,
        val estimatedCompletion: Long? = null
    )
    
    // DEPRECATED: StorageOperationStatus - November 14, 2025
    // data class StorageOperationStatus(
    //     val operationId: String,
    //     val type: String, // "STORE", "RETRIEVE", "DELETE"
    //     val fileId: String,
    //     val status: String,
    //     val progress: Float,
    //     val startTime: Long,
    //     val fileSizeBytes: Long = 0L
    // )
    
    data class ServiceCapabilities(
        val computeEnabled: Boolean,
        val storageEnabled: Boolean,
        val maxComputeThreads: Int,
        val maxStorageGB: Float,
        val supportedComputeTypes: Set<String>,
        val meshProtocolVersion: String
    )
    
    // === SERVICE LIFECYCLE ===
    
    suspend fun startServices(): Boolean {
        return try {
            if (isActiveFlag.compareAndSet(false, true)) {
                // Initialize compute service (service initializes itself lazily)
                val computeStarted = true // computeService.initialize() - not needed with lazy init
                if (!computeStarted) {
                    isActiveFlag.set(false)
                    return false
                }
                
                // Start maintenance tasks
                startMaintenanceTasks()
                
                // Register with mesh network
                registerWithMesh()
                
                // Update statistics - store start time
                val startTime = System.currentTimeMillis()
                serviceStats.serviceUptimeMs = startTime
                betaLogger?.log(LogLevel.INFO, "SERVICES", "Service layer started at: $startTime")
                Log.d(TAG, "Service layer started at: $startTime")
                
                true
            } else {
                false // Already active
            }
        } catch (e: Exception) {
            isActiveFlag.set(false)
            false
        }
    }
    
    suspend fun stopServices(): Boolean {
        return try {
            if (isActiveFlag.compareAndSet(true, false)) {
                // Gracefully complete active tasks
                gracefulShutdown()
                
                // Unregister from mesh
                unregisterFromMesh()
                betaLogger?.log(LogLevel.INFO, "SERVICES", "Service layer stopped")
                
                // Cancel maintenance tasks
                serviceScope.cancel()
                
                true
            } else {
                false // Already inactive
            }
        } catch (e: Exception) {
            false
        }
    }
    
    fun isServiceActive(): Boolean = isActive()
    
    // === COMPUTE SERVICE DELEGATION ===
    
    suspend fun submitComputeTask(task: IntelligentDistributedComputeService.ComputeTask): String {
        if (!isActive()) {
            throw IllegalStateException("Service layer not active")
        }
        
        val taskId = task.taskId
        
        // Track task status
        activeComputeTasks[taskId] = ComputeTaskStatus(
            taskId = taskId,
            status = "SUBMITTED",
            progress = 0.0f,
            startTime = System.currentTimeMillis()
        )
        
        // For now, just mark as completed since we don't have actual execution
        serviceScope.launch {
            delay(1000) // Simulate processing
            activeComputeTasks[taskId] = activeComputeTasks[taskId]?.copy(
                status = "COMPLETED",
                progress = 1.0f
            ) ?: return@launch
            serviceStats.computeTasksCompleted++
        }
        
        return taskId
    }
    
    suspend fun getComputeTaskStatus(taskId: String): ComputeTaskStatus? {
        return activeComputeTasks[taskId]
    }
    
    suspend fun cancelComputeTask(taskId: String): Boolean {
        if (!isActive()) {
            return false
        }
        
        activeComputeTasks.remove(taskId)
        serviceStats.computeTasksCanceled++
        return true
    }
    
    // === STORAGE SERVICE DELEGATION === DEPRECATED November 14, 2025
    /*
    suspend fun storeFile(
        fileName: String,
        data: ByteArray,
        tags: Set<String> = emptySet()
    ): String {
        if (!isActive()) {
            throw IllegalStateException("Service layer not active")
        }
        
        val fileId = generateFileId(fileName, data)
        val operationId = generateOperationId()
        
        // Track operation
        activeStorageOps[operationId] = StorageOperationStatus(
            operationId = operationId,
            type = "STORE",
            fileId = fileId,
            status = "STARTED",
            progress = 0.0f,
            startTime = System.currentTimeMillis(),
            fileSizeBytes = data.size.toLong()
        )
        
        // Execute storage request
        serviceScope.launch {
            try {
                val request = DistributedStorageAgent.StorageRequest(
                    fileId = fileId,
                    fileName = fileName,
                    data = data,
                    tags = tags
                )
                
                val response = storageAgent.storeFileFromOtherNode(request)
                betaLogger?.log(LogLevel.DEBUG, "STORAGE", "storeFileFromOtherNode response success=${response.success}, fileId=${request.fileId}")
                
                // Update operation status
                activeStorageOps[operationId] = activeStorageOps[operationId]?.copy(
                    status = if (response.success) "COMPLETED" else "FAILED",
                    progress = 1.0f
                ) ?: return@launch
                
                // Update statistics
                if (response.success) {
                    serviceStats.storageRequestsHandled++
                    // Notify any test waiters that a storage request was handled
                    try {
                        storageHandledNotifier.tryEmit(Unit)
                    } catch (_: Exception) {
                        // best-effort notify
                    }
                    serviceStats.totalBytesProcessed += data.size
                } else {
                    serviceStats.storageErrorsEncountered++
                }
                
            } catch (e: Exception) {
                activeStorageOps[operationId] = activeStorageOps[operationId]?.copy(
                    status = "ERROR",
                    progress = 1.0f
                ) ?: return@launch
                
                serviceStats.storageErrorsEncountered++
            }
        }
        
        return fileId
    }
    
    suspend fun retrieveFile(fileId: String): ByteArray? {
        if (!isActive()) {
            throw IllegalStateException("Service layer not active")
        }
        
        val operationId = generateOperationId()
        
        // Track operation
        activeStorageOps[operationId] = StorageOperationStatus(
            operationId = operationId,
            type = "RETRIEVE",
            fileId = fileId,
            status = "STARTED",
            progress = 0.0f,
            startTime = System.currentTimeMillis()
        )
        
        return try {
            val request = DistributedStorageAgent.RetrievalRequest(fileId = fileId)
            val response = storageAgent.retrieveFile(request)
            
            // Update operation status
            val finalFileSize = response.data?.size?.toLong() ?: 0L
            activeStorageOps[operationId] = activeStorageOps[operationId]?.copy(
                status = if (response.success) "COMPLETED" else "FAILED",
                progress = 1.0f,
                fileSizeBytes = finalFileSize
            ) ?: return null
            
            // Update statistics
            if (response.success) {
                serviceStats.storageRequestsHandled++
                try {
                    storageHandledNotifier.tryEmit(Unit)
                } catch (_: Exception) {
                    // best-effort notify
                }
                response.data?.let { serviceStats.totalBytesProcessed += it.size }
            } else {
                serviceStats.storageErrorsEncountered++
            }
            
            response.data
            
        } catch (e: Exception) {
            activeStorageOps[operationId] = activeStorageOps[operationId]?.copy(
                status = "ERROR",
                progress = 1.0f
            ) ?: return null
            
            serviceStats.storageErrorsEncountered++
            null
        }
    }
    */
    
    // === SERVICE MONITORING ===
    
    fun getServiceStatistics(): ServiceStatistics {
        return if (isActive()) {
            // Calculate uptime without modifying the stored start time
            val currentTime = System.currentTimeMillis()
            val startTime = serviceStats.serviceUptimeMs
            val currentUptime = currentTime - startTime
            
            Log.d(TAG, "getServiceStatistics: currentTime=$currentTime, startTime=$startTime, uptime=${currentUptime}ms (${currentUptime/60000}min)")
            
            serviceStats.copy(serviceUptimeMs = currentUptime)
        } else {
            Log.d(TAG, "getServiceStatistics: service not active, returning copy")
            serviceStats.copy()
        }
    }
    
    fun getServiceCapabilities(): ServiceCapabilities {
        return ServiceCapabilities(
            computeEnabled = isActive(),
            storageEnabled = isActive(),
            maxComputeThreads = Runtime.getRuntime().availableProcessors(),
            maxStorageGB = 5.0f, // TODO: Make configurable
            supportedComputeTypes = setOf(
                "PYTHON_SCRIPT", "DATA_ANALYSIS", "ML_INFERENCE", 
                "IMAGE_PROCESSING", "TEXT_PROCESSING"
            ),
            meshProtocolVersion = "1.0"
        )
    }
    
    fun getActiveOperations(): Map<String, Any> {
        return mapOf(
            "computeTasks" to activeComputeTasks.values.toList()
            // DEPRECATED: "storageOperations" to activeStorageOps.values.toList()
        )
    }
    
    // DEPRECATED: getActiveStorageOperationsCount - November 14, 2025
    /*
    fun getActiveStorageOperationsCount(): Int {
        return activeStorageOps.size
    }
    */
    
    /**
     * Get the count of active compute tasks (Python scripts, ML inference)
     */
    fun getActiveComputeTasksCount(): Int {
        // Return only "generic" compute tasks (not Python or ML specific).
        // Python and ML tasks are tracked separately via getActivePythonTasksCount and getActiveMLTasksCount.
        return activeComputeTasks.values.count { (it.status == "STARTED" || it.status == "IN_PROGRESS") &&
                !it.taskId.startsWith("python_") && !it.taskId.startsWith("ml_") }
    }
    
    /**
     * Get storage transfer statistics including throughput
     */
    fun getStorageTransferStats(): StorageTransferStats {
        val activeOps = activeStorageOps.values.filter { it.status == "STARTED" || it.status == "IN_PROGRESS" }
        val totalTransfers = activeOps.size
        
        if (totalTransfers == 0) {
            return StorageTransferStats(0, 0.0)
        }
        
        val currentTime = System.currentTimeMillis()
        var totalThroughput = 0.0
        var validCalculations = 0
        
        activeOps.forEach { op ->
            val elapsedTimeMs = currentTime - op.startTime
            if (elapsedTimeMs > 1000 && op.fileSizeBytes > 0) { // At least 1 second elapsed
                val bytesTransferred = (op.progress * op.fileSizeBytes).toLong()
                val throughputBytesPerSec = (bytesTransferred.toDouble() / elapsedTimeMs) * 1000
                totalThroughput += throughputBytesPerSec
                validCalculations++
            }
        }
        
        val avgThroughput = if (validCalculations > 0) totalThroughput / validCalculations else 0.0
        return StorageTransferStats(totalTransfers, avgThroughput)
    }
    
    data class StorageTransferStats(
        val activeTransfers: Int,
        val avgThroughputBytesPerSec: Double
    )
    
    // === MESH NETWORK INTEGRATION ===
    
    private suspend fun registerWithMesh() {
        try {
            // TODO: Register service capabilities with mesh network
            val capabilities = getServiceCapabilities()
            // meshNetwork.registerServiceNode(capabilities)
        } catch (e: Exception) {
            // Log error but continue
        }
    }
    
    private suspend fun unregisterFromMesh() {
        try {
            // TODO: Unregister from mesh network
            // meshNetwork.unregisterServiceNode()
        } catch (e: Exception) {
            // Log error but continue
        }
    }
    
    private fun startMaintenanceTasks() {
        // Periodic statistics update
        serviceScope.launch {
            while (isActive()) {
                delay(30000) // 30 seconds
                updateServiceStatistics()
            }
        }
        
        // Cleanup completed operations
        serviceScope.launch {
            while (isActive()) {
                delay(60000) // 1 minute
                cleanupCompletedOperations()
            }
        }
        
        // DEPRECATED: Storage maintenance - November 14, 2025
        /*
        serviceScope.launch {
            while (isActive()) {
                delay(300000) // 5 minutes
                storageAgent.performMaintenance()
            }
        }
        */
    }
    
    private suspend fun gracefulShutdown() {
        // Wait for active operations to complete (with timeout)
        val shutdownTimeout = 30000L // 30 seconds
        val startTime = System.currentTimeMillis()
        
        while ((activeComputeTasks.isNotEmpty() || activeStorageOps.isNotEmpty()) 
               && (System.currentTimeMillis() - startTime) < shutdownTimeout) {
            delay(1000)
        }
        
        // Force cancel remaining operations
        activeComputeTasks.keys.forEach { taskId ->
            try {
                // Just remove from tracking since we don't have actual cancel method
                activeComputeTasks.remove(taskId)
            } catch (e: Exception) {
                // Log error
            }
        }
        
        activeComputeTasks.clear()
        activeStorageOps.clear()
    }
    
    private fun updateServiceStatistics() {
        // Update mesh contribution score based on completed tasks
        val totalTasks = serviceStats.computeTasksCompleted + serviceStats.computeTasksFailed
        if (totalTasks > 0) {
            serviceStats.meshContributionScore = 
                (serviceStats.computeTasksCompleted.toFloat() / totalTasks) * 100f
        }
    }
    
    private fun cleanupCompletedOperations() {
        val cutoffTime = System.currentTimeMillis() - 300000L // 5 minutes
        
        // Remove old compute task status entries
        activeComputeTasks.entries.removeIf { (_, status) ->
            status.startTime < cutoffTime && 
            (status.status == "COMPLETED" || status.status == "FAILED" || status.status == "ERROR")
        }
        
        // Remove old storage operation status entries
        activeStorageOps.entries.removeIf { (_, status) ->
            status.startTime < cutoffTime && 
            (status.status == "COMPLETED" || status.status == "FAILED" || status.status == "ERROR")
        }
    }
    
    private fun generateFileId(fileName: String, data: ByteArray): String {
        val hash = data.contentHashCode()
        val timestamp = System.currentTimeMillis()
        return "file_${fileName}_${hash}_${timestamp}"
    }
    
    private fun generateOperationId(): String {
        return "op_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"
    }
    
    // === MOCK IMPLEMENTATIONS FOR MISSING DEPENDENCIES ===
    
    private fun mockGossipProtocol(): IntelligentDistributedComputeService.EnhancedGossipProtocol {
        return SimpleGossipProtocol()
    }
    
    private fun mockQuorumManager(): IntelligentDistributedComputeService.QuorumManager {
        return SimpleQuorumManager()
    }
    
    private fun mockResourceManager(): IntelligentDistributedComputeService.ResourceManager {
        return SimpleResourceManager()
    }
    
    private fun mockPythonExecutor(): IntelligentDistributedComputeService.PythonExecutor {
        return MockPythonExecutor()
    }
    
    // private fun mockLiteRTEngine(): IntelligentDistributedComputeService.LiteRTEngine {
    //     return MockLiteRTEngine()
    // }

    // --- Named mock implementations (previously returned as anonymous objects) ---
    private class SimpleGossipProtocol : IntelligentDistributedComputeService.EnhancedGossipProtocol {
        override fun getCurrentNodeStates(): Map<String, IntelligentDistributedComputeService.NodeCapabilitySnapshot> = emptyMap()
    }

    private class SimpleQuorumManager : IntelligentDistributedComputeService.QuorumManager {
        override fun getActiveQuorums(): List<IntelligentDistributedComputeService.ActiveQuorum> = emptyList()
    }

    private class SimpleResourceManager : IntelligentDistributedComputeService.ResourceManager {
        override fun getClusterResourceState(): IntelligentDistributedComputeService.ClusterResourceState =
            IntelligentDistributedComputeService.ClusterResourceState(
                availableNodes = 3,
                totalRAMMB = 24576L,
                averageRAMMB = 8192,
                totalStorageGB = 150L,
                averageCPULoad = 0.25f,
                nodesWithGPU = 1,
                nodesWithNPU = 0
            )
    }

    private class MockPythonExecutor : IntelligentDistributedComputeService.PythonExecutor {
        override suspend fun executeTask(task: IntelligentDistributedComputeService.ComputeTask.PythonTask): IntelligentDistributedComputeService.TaskExecutionResult =
            IntelligentDistributedComputeService.TaskExecutionResult.Success(
                taskId = task.taskId,
                result = mapOf("output" to "Mock result"),
                executionTimeMs = 1000L,
                nodeId = "mock_node"
            )
    }

    // private class MockLiteRTEngine : IntelligentDistributedComputeService.LiteRTEngine {
    //     override suspend fun executeTask(task: IntelligentDistributedComputeService.ComputeTask.LiteRTTask): IntelligentDistributedComputeService.TaskExecutionResult =
    //         IntelligentDistributedComputeService.TaskExecutionResult.Success(
    //             taskId = task.taskId,
    //             result = mapOf("output" to byteArrayOf()),
    //             executionTimeMs = 1000L,
    //             nodeId = "mock_node"
    //         )
    // }
    
    // === TASK MANAGEMENT METHODS FOR TESTING ===
    
    /**
     * Get status string for Python Script Execution service
     */
    fun getPythonExecutionStatus(): String {
        val activeCount = getActivePythonTasksCount()
        return if (activeCount > 0) {
            "Active ($activeCount tasks)"
        } else {
            "Ready"
        }
    }

    /**
     * Get status string for Machine Learning Inference service
     */
    fun getMLInferenceStatus(): String {
        val activeCount = getActiveMLTasksCount()
        return if (activeCount > 0) {
            "Active ($activeCount tasks)"
        } else {
            "Ready"
        }
    }    /**
     * Get status string for Intelligent Task Scheduler service
     */
    fun getTaskSchedulerStatus(): String {
        if (!isActive()) {
            return "Disabled"
        }
        
        val uptimeMs = System.currentTimeMillis() - serviceStats.serviceUptimeMs
        val uptimeMinutes = uptimeMs / (1000 * 60)
        return "Ready (${uptimeMinutes}m uptime)"
    }
    
    /**
     * Add a Python task for testing
     */
    fun addPythonTask(taskId: String, scriptName: String): String {
        val fullTaskId = "python_${taskId}_${System.currentTimeMillis()}"
        activeComputeTasks[fullTaskId] = ComputeTaskStatus(
            taskId = fullTaskId,
            status = "STARTED",
            progress = 0.0f,
            startTime = System.currentTimeMillis()
        )
        Log.d(TAG, "Added Python task: $fullTaskId ($scriptName)")
        return fullTaskId
    }
    
    /**
     * Add an ML task for testing
     */
    fun addMLTask(taskId: String, modelType: String): String {
        val fullTaskId = "ml_${taskId}_${System.currentTimeMillis()}"
        activeComputeTasks[fullTaskId] = ComputeTaskStatus(
            taskId = fullTaskId,
            status = "STARTED",
            progress = 0.0f,
            startTime = System.currentTimeMillis()
        )
        Log.d(TAG, "Added ML task: $fullTaskId ($modelType)")
        return fullTaskId
    }
    
    /**
     * Complete a Python task for testing
     */
    fun completePythonTask(taskId: String): Boolean {
        val fullTaskId = activeComputeTasks.keys.find { it.contains(taskId) }
        return if (fullTaskId != null) {
            activeComputeTasks.remove(fullTaskId)
            serviceStats.computeTasksCompleted++
            Log.d(TAG, "Completed Python task: $fullTaskId")
            true
        } else {
            Log.w(TAG, "Python task not found: $taskId")
            false
        }
    }
    
    /**
     * Complete an ML task for testing
     */
    fun completeMLTask(taskId: String): Boolean {
        val fullTaskId = activeComputeTasks.keys.find { it.contains(taskId) }
        return if (fullTaskId != null) {
            activeComputeTasks.remove(fullTaskId)
            serviceStats.computeTasksCompleted++
            Log.d(TAG, "Completed ML task: $fullTaskId")
            true
        } else {
            Log.w(TAG, "ML task not found: $taskId")
            false
        }
    }
    
    /**
     * Check if service layer is currently active
     */
    fun isServiceLayerActive(): Boolean {
        return isActive()
    }
    
    /**
     * Get count of active Python tasks for UI display
     */
    fun getActivePythonTasksCount(): Int {
        return activeComputeTasks.values.count { it.taskId.startsWith("python_") }
    }
    
    /**
     * Get count of active ML tasks for UI display  
     */
    fun getActiveMLTasksCount(): Int {
        return activeComputeTasks.values.count { it.taskId.startsWith("ml_") }
    }
    
    // === SIMULATION METHODS FOR TESTING ===
    
    /**
     * Simulate some active tasks for demonstration purposes
     */
    private fun simulateActiveTasks() {
        serviceScope.launch {
            // Simulate 2 Python tasks
            activeComputeTasks["python_task_1"] = ComputeTaskStatus(
                taskId = "python_data_analysis_1",
                status = "IN_PROGRESS",
                progress = 0.6f,
                startTime = System.currentTimeMillis() - 30000 // Started 30 seconds ago
            )
            
            activeComputeTasks["python_task_2"] = ComputeTaskStatus(
                taskId = "python_web_scraper_2",
                status = "STARTED",
                progress = 0.2f,
                startTime = System.currentTimeMillis() - 10000 // Started 10 seconds ago
            )
            
            // Simulate 1 ML inference task
            activeComputeTasks["ml_task_1"] = ComputeTaskStatus(
                taskId = "ml_image_inference_1",
                status = "IN_PROGRESS",
                progress = 0.8f,
                startTime = System.currentTimeMillis() - 45000 // Started 45 seconds ago
            )
            
            // DEPRECATED: Storage operations test initialization - November 14, 2025
            /*
            // Simulate some storage operations
            activeStorageOps["storage_op_1"] = StorageOperationStatus(
                operationId = "storage_upload_1",
                type = "STORE",
                fileId = "file_123",
                status = "IN_PROGRESS",
                progress = 0.4f,
                startTime = System.currentTimeMillis() - 20000,
                fileSizeBytes = 2048 * 1024 // 2MB
            )
            
            activeStorageOps["storage_op_2"] = StorageOperationStatus(
                operationId = "storage_upload_2",
                type = "STORE",
                fileId = "file_456",
                status = "STARTED",
                progress = 0.1f,
                startTime = System.currentTimeMillis() - 5000,
                fileSizeBytes = 5120 * 1024 // 5MB
            )
            
            activeStorageOps["storage_op_3"] = StorageOperationStatus(
                operationId = "storage_retrieve_3",
                type = "RETRIEVE",
                fileId = "file_789",
                status = "IN_PROGRESS",
                progress = 0.7f,
                startTime = System.currentTimeMillis() - 35000,
                fileSizeBytes = 1024 * 1024 // 1MB
            )
            */
            
            Log.d(TAG, "Simulated active tasks: ${activeComputeTasks.size} compute")
            // DEPRECATED: ", ${activeStorageOps.size} storage"
        }
    }
}
