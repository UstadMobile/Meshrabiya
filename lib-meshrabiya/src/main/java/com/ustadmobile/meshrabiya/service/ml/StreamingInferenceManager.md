
package com.ustadmobile.meshrabiya.service.ml

import kotlinx.coroutines.withTimeout
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import android.util.Log
import com.ustadmobile.meshrabiya.model.ServiceAnnouncement
import com.ustadmobile.meshrabiya.model.ResourceRequirements
import com.ustadmobile.meshrabiya.model.ExecutionProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Streaming inference manager - models stay on capable devices,
 * only inference results are transferred across mesh
 */
class StreamingInferenceManager(
    private val localMLManager: UnifiedMLServiceManager,
    private val meshNode: AndroidVirtualNode
) {
    
    companion object {
        private const val TAG = "StreamingInferenceManager"
        private const val MAX_CONCURRENT_REQUESTS = 3
    }
    
    // Track which devices have which models available
    private val modelAvailability = ConcurrentHashMap<String, Set<Int>>() // modelId -> Set<nodeAddress>
    
    // Active inference requests
    private val activeRequests = ConcurrentHashMap<String, CompletableDeferred<MLServiceResult>>()
    
    /**
     * Execute inference - either locally or via streaming to capable device
     */
    suspend fun executeInference(
        serviceId: String,
        serviceType: String,
        input: MLServiceInput,
        preferLocal: Boolean = true
    ): MLServiceResult {
        
        // Try local execution first if preferred and available
        if (preferLocal && canExecuteLocally(serviceId, serviceType)) {
            Log.d(TAG, "Executing $serviceId locally")
            return localMLManager.processMLRequest(serviceType, serviceId, input)
        }
        
        // Find capable devices for this model
        val capableNodes = findCapableNodes(serviceId, serviceType)
        if (capableNodes.isEmpty()) {
            return MLServiceResult.error("No capable devices found for service: $serviceId")
        }
        
        // Stream inference request to best device
        val targetNode = selectBestNode(capableNodes, serviceId)
        Log.d(TAG, "Streaming $serviceId inference to node $targetNode")
        
        return streamInferenceRequest(targetNode, serviceId, serviceType, input)
    }
    
    /**
     * Handle incoming model availability announcements from mesh
     */
    fun updateModelAvailability(nodeAddress: Int, availableServices: List<ServiceAnnouncement>) {
        availableServices.forEach { service ->
            val currentNodes = modelAvailability[service.serviceId] ?: emptySet()
            modelAvailability[service.serviceId] = currentNodes + nodeAddress
            
            Log.d(TAG, "Node $nodeAddress announced availability of ${service.serviceId}")
        }
    }
    
    /**
     * Handle incoming streaming inference requests
     */
    suspend fun handleInferenceRequest(
        requestId: String,
        serviceId: String,
        serviceType: String,
        input: MLServiceInput,
        requesterNodeId: Int
    ) {
        try {
            Log.d(TAG, "Processing streaming inference request $requestId for $serviceId")
            
            val result = localMLManager.processMLRequest(serviceType, serviceId, input)
            
            // Send result back to requester
            sendInferenceResult(requesterNodeId, requestId, result)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process streaming inference request", e)
            val errorResult = MLServiceResult.error("Inference failed: ${e.message}")
            sendInferenceResult(requesterNodeId, requestId, errorResult)
        }
    }
    
    private fun canExecuteLocally(serviceId: String, serviceType: String): Boolean {
        return when (serviceType) {
            "ml-kit-native" -> {
                // ML Kit services are usually available locally
                true
            }
            "ml-kit-custom" -> {
                // removed , "litert"
                // Check if we have the model locally 
                localMLManager.getAvailableServices().any { 
                    it.serviceId == serviceId && it.serviceType.name.lowercase().replace("_", "-") == serviceType
                }
            }
            else -> false
        }
    }
    
    private fun findCapableNodes(serviceId: String, serviceType: String): List<Int> {
        return modelAvailability[serviceId]?.toList() ?: emptyList()
    }
    
    private fun selectBestNode(capableNodes: List<Int>, serviceId: String): Int {
        // Select node based on:
        // 1. Hop count (prefer closer nodes)
        // 2. Historical performance
        // 3. Current load
        
        return capableNodes.minByOrNull { nodeAddress ->
            val hopCount = meshNode.getHopCountToNode(nodeAddress) ?: 10
            val loadFactor = getNodeLoadFactor(nodeAddress)
            
            // Weighted score: closer nodes and less loaded nodes preferred
            hopCount * 2.0 + loadFactor * 3.0
        } ?: capableNodes.first()
    }
    
    private suspend fun streamInferenceRequest(
        targetNode: Int,
        serviceId: String,
        serviceType: String,
        input: MLServiceInput
    ): MLServiceResult = withContext(Dispatchers.IO) {
        
        val requestId = generateRequestId()
        val resultDeferred = CompletableDeferred<MLServiceResult>()
        
        // Track the request
        activeRequests[requestId] = resultDeferred
        
        try {
            // Send inference request via mesh
            val request = StreamingInferenceRequest(
                requestId = requestId,
                serviceId = serviceId,
                serviceType = serviceType,
                input = input,
                requesterNodeId = meshNode.getNodeId()
            )
            
            meshNode.sendToNode(targetNode, request.toBytes())
            
            // Wait for result with timeout
            withTimeout(30_000) { // 30 second timeout
                resultDeferred.await()
            }
            
        } catch (e: Exception) {
            MLServiceResult.error("Streaming inference failed: ${e.message}")
        } finally {
            activeRequests.remove(requestId)
        }
    }
    
    private fun sendInferenceResult(
        targetNode: Int,
        requestId: String,
        result: MLServiceResult
    ) {
        val response = StreamingInferenceResponse(
            requestId = requestId,
            result = result
        )
        
        meshNode.sendToNode(targetNode, response.toBytes())
    }
    
    private fun getNodeLoadFactor(nodeAddress: Int): Double {
        // Simple load estimation - would be enhanced with real metrics
        val activeRequestsForNode = activeRequests.values.count { 
            // In real implementation, track which node is processing each request
            false 
        }
        
        return activeRequestsForNode.toDouble() / MAX_CONCURRENT_REQUESTS
    }
    
    private fun generateRequestId(): String {
        return "${System.currentTimeMillis()}-${(1000..9999).random()}"
    }
}

/**
 * Message format for streaming inference requests
 */
data class StreamingInferenceRequest(
    val requestId: String,
    val serviceId: String,
    val serviceType: String,
    val input: MLServiceInput,
    val requesterNodeId: Int
) {
    fun toBytes(): ByteArray {
        // Serialize to bytes for mesh transmission
        // In real implementation, use protobuf or similar
        return toString().toByteArray()
    }
}

data class StreamingInferenceResponse(
    val requestId: String,
    val result: MLServiceResult
) {
    fun toBytes(): ByteArray {
        return toString().toByteArray()
    }
}

// Extension functions that would be added to AndroidVirtualNode
interface StreamingCapableNode {
    fun getNodeId(): Int
    fun getHopCountToNode(nodeAddress: Int): Int?
    fun sendToNode(nodeAddress: Int, data: ByteArray)
}

