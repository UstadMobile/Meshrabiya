package com.ustadmobile.meshrabiya.storage

import com.ustadmobile.meshrabiya.service.StorageNodeResponse
import com.ustadmobile.meshrabiya.service.ChunkRetrievalResponse
import com.ustadmobile.meshrabiya.service.ReplicaResponse
import android.content.Context
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.MeshChunk
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.service.ChunkTransferMessage
import java.security.PublicKey
import com.ustadmobile.meshrabiya.util.toHash
import com.ustadmobile.meshrabiya.util.toPublicKey
import java.util.Base64
import com.ustadmobile.meshrabiya.service.ChunkRetrievalQuery
import  com.ustadmobile.meshrabiya.vnet.MeshConnectionPool
/**

 * DistributedStorageServer: Server-side workflows for distributed storage.
 * 
 * Responsibilities:
 * - Handle incoming chunk storage (Workflow 1.6)
 * - Initiate daisy-chain replication (Workflow 4)
 * - Respond to storage node requests
 * - Respond to chunk retrieval queries
 * - Re-encrypt chunks for permission updates
 */
class DistributedStorageServer(
    private val manager: DistributedStorageManager,
    private val virtualNode: VirtualNode,
    private val context: Context
) {
    companion object {
        private const val TAG = "DistributedStorageServer"
        private const val RESPONSE_TIMEOUT_MS = 5000L
    }
    
    // === Server Workflows ===
    
    /**
     * Handle incoming chunk transfer from requester or another storage node.
     * Canonical workflow Step 1.6: Storage node receives and processes chunk.
     */
    fun handleIncomingChunkTransfer(senderId: Int, chunkTransfer: ChunkTransferMessage) {
        manager.scope.launch {
            try {
                manager.betaLogger.log(
                    LogLevel.DEBUG,
                    TAG,
                    "Receiving chunk ${chunkTransfer.chunkId} from node $senderId " +
                    "(replica ${chunkTransfer.replicaCount}/${MeshrabiyaConstants.getReplicaCount()})"
                )
                
                // Step 1: Decrypt chunk using storage node's service private key
                val decryptedChunkBytes = try {
                    manager.encryptionManager.decrypt(chunkTransfer.chunkBytes)
                } catch (e: Exception) {
                    manager.betaLogger.log(
                        LogLevel.ERROR,
                        TAG,
                        "Failed to decrypt chunk ${chunkTransfer.chunkId}: ${e.message}"
                    )
                    return@launch
                }
                
                // Step 2: Increment replicaCount (canonical workflow)
                val newReplicaCount = chunkTransfer.replicaCount + 1
                
                manager.betaLogger.log(
                    LogLevel.DEBUG,
                    TAG,
                    "Decrypted chunk ${chunkTransfer.chunkId}, incrementing replica count: " +
                    "${chunkTransfer.replicaCount} -> $newReplicaCount"
                )
                
                // Step 3: Write chunk to filesystem
                // TODO integrate storage participation folder selection(s)
                val sharedStorageDir = File(
                    context.filesDir,
                    "shared_storage/${chunkTransfer.fileId}/${chunkTransfer.relativePath}"
                )
                if (!sharedStorageDir.exists()) sharedStorageDir.mkdirs()
                
               val chunkFile = File(sharedStorageDir, "${chunkTransfer.chunkId}.chunk")
                FileOutputStream(chunkFile).use { it.write(decryptedChunkBytes) }
                
                manager.betaLogger.log(
                    LogLevel.DEBUG,
                    TAG,
                    "Wrote chunk ${chunkTransfer.chunkId} to filesystem: ${chunkFile.absolutePath}"
                )
                
                // Step 4: Index in StorageDataStore
                val meshChunk = MeshChunk(
                    chunkId = chunkTransfer.chunkId,
                    fileId = chunkTransfer.fileId,
                    chunkIndex = chunkTransfer.chunkIndex,
                    totalChunks = chunkTransfer.totalChunks,
                    chunkSize = decryptedChunkBytes.size.toLong(),
                    fileName = chunkTransfer.fileName,
                    relativePath = chunkTransfer.relativePath,
                    hash = chunkTransfer.hash,
                    storedAt = System.currentTimeMillis(),
                    sessionKeys = chunkTransfer.sessionKeys,
                    replicaCount = newReplicaCount,
                    
                    serverPath = chunkFile.absolutePath // <-- NEW FIELD
                )
                
                manager.addMeshChunk(meshChunk)
                
                manager.betaLogger.log(
                    LogLevel.INFO,
                    TAG,
                    "Chunk ${chunkTransfer.chunkId} stored successfully (replica $newReplicaCount)"
                )
                
                // Step 5: Send completion notification
                // TODO: Implement completion notification
                
                // Step 6: Initiate daisy-chain replication if needed
                val canonicalReplicaCount = MeshrabiyaConstants.getReplicaCount()
                if (newReplicaCount < canonicalReplicaCount) {
                    manager.betaLogger.log(
                        LogLevel.DEBUG,
                        TAG,
                        "Initiating daisy-chain replication for chunk ${chunkTransfer.chunkId} " +
                        "(${newReplicaCount}/${canonicalReplicaCount})"
                    )
                    initiateChunkReplication(meshChunk, chunkTransfer, canonicalReplicaCount)
                } else {
                    manager.betaLogger.log(
                        LogLevel.DEBUG,
                        TAG,
                        "Chunk ${chunkTransfer.chunkId} replication complete " +
                        "(${newReplicaCount}/${canonicalReplicaCount})"
                    )
                }
                
            } catch (e: Exception) {
                manager.betaLogger.log(
                    LogLevel.ERROR,
                    TAG,
                    "Error handling incoming chunk ${chunkTransfer.chunkId}: ${e.message}",
                    emptyMap(),
                    e
                )
            }
        }
    }
    
    /**
     * Initiates daisy-chain replication for a stored chunk.
     * Canonical workflow Step 4: Storage node acts as requester to propagate chunk.
     */
    private suspend fun initiateChunkReplication(
        meshChunk: MeshChunk,
        originalTransfer: ChunkTransferMessage,
        totalReplicas: Int
    ) {
        try {
            manager.betaLogger.log(
                LogLevel.DEBUG,
                TAG,
                "Beginning daisy-chain replication for chunk ${meshChunk.chunkId} " +
                "(current: ${meshChunk.replicaCount}, target: $totalReplicas)"
            )
            
            // Step 1: Broadcast to discover available storage nodes
            val responses = broadcastStorageNodeRequest(
                chunk = meshChunk,
                fileId = meshChunk.fileId
            )
            
            if (responses.isEmpty()) {
                manager.betaLogger.log(
                    LogLevel.WARN,
                    TAG,
                    "No storage nodes available for chunk ${meshChunk.chunkId} replication"
                )
                return
            }
            
            manager.betaLogger.log(
                LogLevel.DEBUG,
                TAG,
                "Received ${responses.size} storage node responses for chunk ${meshChunk.chunkId}"
            )
            
            // Step 2: Select single best storage node
            val selectedNode = selectBestStorageNode(responses)
            
            if (selectedNode == null) {
                manager.betaLogger.log(
                    LogLevel.WARN,
                    TAG,
                    "No suitable storage node found for chunk ${meshChunk.chunkId} replication"
                )
                return
            }
            
            manager.betaLogger.log(
                LogLevel.DEBUG,
                TAG,
                "Selected node ${selectedNode.nodeId} for chunk ${meshChunk.chunkId} replication " +
                "(fitness: ${selectedNode.fitnessScore}, latency: ${selectedNode.latency}ms)"
            )
            
            // Step 3: Read stored chunk from filesystem
            val sharedStorageDir = File(
                context.filesDir,
                "shared_storage/${meshChunk.fileId}/${meshChunk.relativePath}"
            )
            val chunkFile = File(sharedStorageDir, "${meshChunk.chunkId}.chunk")
            
            if (!chunkFile.exists()) {
                manager.betaLogger.log(
                    LogLevel.ERROR,
                    TAG,
                    "Chunk file ${meshChunk.chunkId} not found on filesystem for replication"
                )
                return
            }
            
            val chunkBytes = chunkFile.readBytes()
            
            // Step 4: Re-encrypt chunk for next storage node
            // Add the next node as a recipient
            val publicKeyBytes = selectedNode.servicePublicKey
            val publicKeyObj = publicKeyBytes.toPublicKey()
            val nextNodeRecipient = RecipientEntry(
                recipientId = publicKeyObj.toHash(),
                publicKey = Base64.getEncoder().encodeToString(publicKeyBytes),
                recipientType = RecipientType.USER
            )
            val updatedRecipients: List<RecipientEntry> = originalTransfer.recipients + nextNodeRecipient + originalTransfer.owner

            // Create a map of <recipientId, publicKey>
            val recipientMap: Map<String, String> = updatedRecipients.associate { it.recipientId to it.publicKey }
            val (encryptedChunkBytes, sessionKeys) = manager.encryptionManager.encryptWithRecipients(
                data = chunkBytes,
                recipientPublicKeys = recipientMap
            )
            
            manager.betaLogger.log(
                LogLevel.DEBUG,
                TAG,
                "Re-encrypted chunk ${meshChunk.chunkId} for node ${selectedNode.nodeId}"
            )
            
            // Step 5: Forward chunk with SAME replicaCount
            val forwardMessage = ChunkTransferMessage(
                chunkId = meshChunk.chunkId,
                fileId = meshChunk.fileId,
                chunkIndex = meshChunk.chunkIndex,
                totalChunks = meshChunk.totalChunks,
                chunkBytes = encryptedChunkBytes,
                fileName = meshChunk.fileName,
                relativePath = meshChunk.relativePath,
                hash = meshChunk.hash,
                replicaCount = meshChunk.replicaCount,
                recipients = updatedRecipients,
                sessionKeys = originalTransfer.sessionKeys,
                owner = originalTransfer.owner
            )
            
            manager.betaLogger.log(
                LogLevel.INFO,
                TAG,
                "Forwarding chunk ${meshChunk.chunkId} to node ${selectedNode.nodeId} " +
                "(replica ${meshChunk.replicaCount} -> will become ${meshChunk.replicaCount + 1})"
            )
            
            // Step 6: Send to selected node as direct message
            val targetNodeAddress = selectedNode.nodeId.toInt()
            virtualNode.sendEcosystemMessage(targetNodeAddress, forwardMessage.toBytes())
            
            val chunkReplicaSet = manager.chunkReplicaTracker.getOrPut(meshChunk.chunkId) { mutableSetOf() }
            chunkReplicaSet.add(selectedNode.nodeId)
            
            manager.betaLogger.log(
                LogLevel.DEBUG,
                TAG,
                "Chunk ${meshChunk.chunkId} now has ${chunkReplicaSet.size + 1} replicas tracked"
            )
            
        } catch (e: Exception) {
            manager.betaLogger.log(
                LogLevel.ERROR,
                TAG,
                "Error initiating replication for chunk ${meshChunk.chunkId}: ${e.message}",
                emptyMap(),
                e
            )
        }
    }
    
    // === Helper Methods ===
    
    private suspend fun broadcastStorageNodeRequest(
        chunk: MeshChunk,
        fileId: String
    ): List<StorageNodeResponse> = coroutineScope {
        val requestId = "${fileId}_${chunk.chunkIndex}_${System.currentTimeMillis()}"
        val request = com.ustadmobile.meshrabiya.service.StorageNodeRequest(
            requestId = requestId,
            chunkId = chunk.chunkId,
            chunkIndex = chunk.chunkIndex,
            fileId = fileId,
            chunkSizeBytes = chunk.chunkSize,
            replicaCount = chunk.replicaCount,
            requiredSpace = chunk.chunkSize,
            fileName = chunk.fileName,
            senderId = virtualNode.addressAsInt
        )
        // ...existing code...
        val responses = mutableListOf<StorageNodeResponse>()
        // ...existing code...
        manager.betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Broadcasted StorageNodeRequest for replication of chunk ${chunk.chunkId} (requestId=$requestId)"
        )
        // ...existing code...
        delay(RESPONSE_TIMEOUT_MS)
        // ...existing code...
        manager.betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Received ${responses.size} StorageNodeResponses for chunk ${chunk.chunkId} replication"
        )
        responses.toList()
    }
    
    private fun selectBestStorageNode(
        responses: List<StorageNodeResponse>
    ): StorageNodeResponse? {
        if (responses.isEmpty()) {
            manager.betaLogger.log(LogLevel.WARN, TAG, "No storage node responses available")
            return null
        }
        
        val suitableNodes = responses.filter { response ->
            response.availableSpace > 0 && response.systemState == "HEALTHY"
        }
        
        if (suitableNodes.isEmpty()) {
            manager.betaLogger.log(LogLevel.WARN, TAG, "No suitable storage nodes (all full or unhealthy)")
            return null
        }
        
        val bestNode = suitableNodes.maxWithOrNull(
            compareBy<StorageNodeResponse> { it.fitnessScore }
                .thenBy { -it.latency }
        )
        
        bestNode?.let {
            manager.betaLogger.log(
                LogLevel.INFO,
                TAG,
                "Selected storage node ${it.nodeId}: fitness=${it.fitnessScore}, latency=${it.latency}ms"
            )
        }
        
        return bestNode
    }
    
    /**
     * Respond to storage node request as a potential storage provider.
     * Called when this node receives a broadcast looking for storage.
     */
    fun respondToStorageNodeRequest(request: com.ustadmobile.meshrabiya.service.StorageNodeRequest) {
        manager.scope.launch {
            try {
                // Check eligibility
                val availableSpace = manager.storageConfig.defaultQuota - manager.storageStats.value.currentlyUsed
                val isHealthy = checkSystemHealth()
                
                if (!isHealthy || availableSpace < request.chunkSizeBytes) {
                    manager.betaLogger.log(
                        LogLevel.DEBUG,
                        TAG,
                        "Node ineligible for storage request: healthy=$isHealthy, space=$availableSpace"
                    )
                    return@launch
                }
                
                // Check if already storing this chunk (prevent duplicate storage)
                if (manager.getMeshChunk(request.chunkId) != null) {
                    manager.betaLogger.log(
                        LogLevel.DEBUG,
                        TAG,
                        "Already storing chunk ${request.chunkId}, skipping response"
                    )
                    return@launch
                }
                
                // Send response
                val response = StorageNodeResponse(
                    nodeId = virtualNode.addressAsInt,
                    availableSpace = availableSpace,
                    totalStorageAllocated = manager.storageStats.value.currentlyUsed,
                    systemState = if (isHealthy) "HEALTHY" else "DEGRADED",
                    url = "mesh://${virtualNode.addressAsInt}",
                    latency = estimateLatency(),
                    fitnessScore = calculateFitnessScore(availableSpace, isHealthy),
                    fileId = request.fileId,
                    servicePublicKey = manager.servicePublicKey
                )
                
                // Send response as direct message to requester
                val requesterAddress = request.senderId.toInt()
                val responseMessage = com.ustadmobile.meshrabiya.service.StorageNodeResponseMessage(response)
                virtualNode.sendEcosystemMessage(requesterAddress, responseMessage.toBytes())
                
                manager.betaLogger.log(
                    LogLevel.DEBUG,
                    TAG,
                    "Sent StorageNodeResponse for request ${request.requestId}"
                )
                
            } catch (e: Exception) {
                manager.betaLogger.log(
                    LogLevel.ERROR,
                    TAG,
                    "Error responding to storage node request: ${e.message}",
                    emptyMap(),
                    e
                )
            }
        }
    }
    
    // === RETRIEVE FILE WORKFLOW (Server-Side) ===
    
    /**
     * Respond to chunk retrieval query.
     * Canonical workflow Step 2.2: Storage node responds to query.
     * 
     * Called when this node receives a broadcast looking for nodes that have chunks.
     * Checks if this node has the requested chunks and responds with metadata.
     */
    fun respondToChunkRetrievalQuery(query: ChunkRetrievalQuery) {
        manager.scope.launch {
            try {
                val fileId = query.fileId
                val requestedChunkIndexes = query.chunkIndexes
                
                manager.betaLogger.log(
                    LogLevel.DEBUG,
                    TAG,
                    "Received chunk retrieval query for fileId=$fileId, chunkIndexes=$requestedChunkIndexes"
                )
                
                // Get all chunks for this fileId
                val allChunks = manager.getAllMeshChunks().filter { it.fileId == fileId }
                
                // Filter by requested chunk indexes if specified
                val matchingChunks = if (requestedChunkIndexes != null) {
                    allChunks.filter { it.chunkIndex in requestedChunkIndexes }
                } else {
                    allChunks
                }
                
                if (matchingChunks.isEmpty()) {
                    manager.betaLogger.log(
                        LogLevel.DEBUG,
                        TAG,
                        "No matching chunks found for fileId=$fileId"
                    )
                    return@launch
                }
                
                // Send response for each matching chunk
                matchingChunks.forEach { chunk ->
                    val response = ChunkRetrievalResponse(
                        chunkId = chunk.chunkId,
                        fileId = chunk.fileId,
                        chunkIndex = chunk.chunkIndex,
                        totalChunks = chunk.totalChunks,
                        nodeId = virtualNode.addressAsInt,
                        fileName = chunk.fileName,
                        relativePath = chunk.relativePath,
                        chunkSize = chunk.chunkSize,
                        requestId = query.requestId
                    )
                    
                    // Send response as direct message to requester
                    val requesterAddress = query.senderId.toInt()
                    val responseMessage = com.ustadmobile.meshrabiya.service.ChunkRetrievalResponseMessage(response)
                    virtualNode.sendEcosystemMessage(requesterAddress, responseMessage.toBytes())
                    
                    manager.betaLogger.log(
                        LogLevel.DEBUG,
                        TAG,
                        "Sent ChunkRetrievalResponse for chunk ${chunk.chunkId}"
                    )
                }
                
            } catch (e: Exception) {
                manager.betaLogger.log(
                    LogLevel.ERROR,
                    TAG,
                    "Error responding to chunk retrieval query: ${e.message}",
                    emptyMap(),
                    e
                )
            }
        }
    }
    
    /**
     * Handle chunk transfer request (send chunk to requester).
     * Canonical workflow Step 2.3: Storage node sends chunk to requester.
     * 
     * Called when a client node directly requests a specific chunk after
     * discovering this node has it via the retrieval query response.
     * 
     * @param requesterNodeId The node address requesting the chunk
     * @param chunkId The chunk ID to send
     * @param connectionPool Connection pool for establishing connection
     */
    fun handleChunkTransferRequest(
        requesterNodeId: Int,
        chunkId: String,
        connectionPool: MeshConnectionPool=MeshConnectionPool.getInstance()
    ) {
        manager.scope.launch {
            try {
                manager.betaLogger.log(
                    LogLevel.DEBUG,
                    TAG,
                    "Handling chunk transfer request for chunk $chunkId to node $requesterNodeId"
                )
                
                // Get chunk metadata
                val chunk = manager.getMeshChunk(chunkId)
                if (chunk == null) {
                    manager.betaLogger.log(
                        LogLevel.ERROR,
                        TAG,
                        "Chunk $chunkId not found in local storage"
                    )
                    return@launch
                }
                val meshFile = manager.storageDataStore.getMeshFile(chunk.fileId)
                if (meshFile == null) {
                    manager.betaLogger.log(
                        LogLevel.WARN,
                        TAG,
                        "Mesh file not found for fileId: ${chunk.fileId} (cannot request chunk data)"
                    )
                    return@launch 
                }
                // Read chunk from filesystem
                val sharedStorageDir = File(
                    context.filesDir,
                    "shared_storage/${chunk.fileId}/${chunk.relativePath}"
                )
                val chunkFile = File(sharedStorageDir, "${chunk.chunkId}.chunk")
                
                if (!chunkFile.exists()) {
                    manager.betaLogger.log(
                        LogLevel.ERROR,
                        TAG,
                        "Chunk file ${chunk.chunkId} not found on filesystem"
                    )
                    return@launch
                }
                
                val chunkBytes = chunkFile.readBytes()
                
                // Encrypt chunk for requester
                // Note: Chunk is stored decrypted locally, must re-encrypt for transmission
                val encryptedChunkBytes = manager.encryptionManager.encrypt(chunkBytes)
                
                // Create transfer message
                val transferMessage = ChunkTransferMessage(
                    chunkId = chunk.chunkId,
                    fileId = chunk.fileId,
                    chunkIndex = chunk.chunkIndex,
                    totalChunks = chunk.totalChunks,
                    fileName = chunk.fileName,
                    relativePath = chunk.relativePath,
                    chunkBytes = encryptedChunkBytes,
                    hash = chunk.hash,
                    replicaCount = chunk.replicaCount,
                    recipients = meshFile.recipients,
                    sessionKeys = chunk.sessionKeys,
                    owner = meshFile.owner
                )
                
                // Acquire connection and send chunk
                val connection = try {
                    connectionPool.acquireConnection(timeoutMs = RESPONSE_TIMEOUT_MS)
                } catch (e: Exception) {
                    manager.betaLogger.log(
                        LogLevel.ERROR,
                        TAG,
                        "Failed to acquire connection for chunk transfer: ${e.message}"
                    )
                    null
                }
                
                if (connection != null) {
                    try {
                        // Send chunk via direct message using VirtualPacket
                        virtualNode.sendEcosystemMessage(requesterNodeId, transferMessage.toBytes())
                        
                        manager.betaLogger.log(
                            LogLevel.INFO,
                            TAG,
                            "Successfully sent chunk ${chunk.chunkId} to node $requesterNodeId"
                        )
                        
                    } catch (e: Exception) {
                        manager.betaLogger.log(
                            LogLevel.ERROR,
                            TAG,
                            "Error sending chunk ${chunk.chunkId}: ${e.message}",
                            emptyMap(),
                            e
                        )
                    } finally {
                        connectionPool.releaseConnection(connection)
                    }
                } else {
                    manager.betaLogger.log(
                        LogLevel.ERROR,
                        TAG,
                        "No connection available for chunk transfer"
                    )
                }
                
            } catch (e: Exception) {
                manager.betaLogger.log(
                    LogLevel.ERROR,
                    TAG,
                    "Error handling chunk transfer request: ${e.message}",
                    emptyMap(),
                    e
                )
            }
        }
    }
    
    // === System Health Checks ===
    
    private fun checkSystemHealth(): Boolean {
        // TODO: Implement actual system health checks
        // Check battery level, thermal state, available resources
        return true
    }
    
    private fun estimateLatency(): Int {
        // TODO: Implement actual latency estimation
        return 100 // ms
    }
    
    private fun calculateFitnessScore(availableSpace: Long, isHealthy: Boolean): Float {
        var score = 0.5f
        
        // Factor in available space (0.0 to 0.3)
        val spaceRatio = (availableSpace.toFloat() / manager.storageConfig.defaultQuota).coerceIn(0f, 1f)
        score += spaceRatio * 0.3f
        
        // Factor in health status (0.0 or 0.2)
        if (isHealthy) {
            score += 0.2f
        }
        
        return score.coerceIn(0f, 1f)
    }
}