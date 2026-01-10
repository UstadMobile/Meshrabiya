package com.ustadmobile.meshrabiya.storage

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
// DEPRECATED: AccessPattern incorporated into fitness calculation
// import com.ustadmobile.meshrabiya.mmcp.AccessPattern
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.MeshConnectionPool
import com.ustadmobile.meshrabiya.service.MeshGossipService
import com.ustadmobile.meshrabiya.service.MeshEcosystemListener
import com.ustadmobile.meshrabiya.vnet.CoreGossipBroadcastService
import com.ustadmobile.meshrabiya.service.MeshEcosystemMessage
import com.ustadmobile.meshrabiya.service.StorageNodeRequest
import com.ustadmobile.meshrabiya.service.StorageNodeResponse
import com.ustadmobile.meshrabiya.service.ChunkRetrievalQuery
import com.ustadmobile.meshrabiya.service.ChunkRetrievalResponse
import com.ustadmobile.meshrabiya.service.ReplicaQuery
import com.ustadmobile.meshrabiya.service.ReplicaResponse
import com.ustadmobile.meshrabiya.service.ChunkTransferMessage
import com.ustadmobile.meshrabiya.service.FilePermissionUpdateConfirmationMessage
import com.ustadmobile.meshrabiya.vnet.MeshChunk
import com.ustadmobile.meshrabiya.storage.StorageDataStore
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageBufferPacker
import org.msgpack.core.MessageUnpacker
import org.bouncycastle.openpgp.PGPPublicKey
import com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy.AccessScope
import kotlinx.serialization.Serializable

// === Storage Types ===

/**
 * File reference for tracking stored files in distributed storage.
 */
data class FileReference(
    val id: String,        // File ID (SHA-256 hash)
    val path: String,      // Original file path
    val size: Long         // File size in bytes
)

/**
 * Replication level for file storage across mesh network.
 */
enum class ReplicationLevel {
    MINIMAL,    // 1 replica
    STANDARD,   // 3 replicas (default)
    HIGH,       // 5 replicas
    CRITICAL    // 7 replicas
}

/**
 * Priority level for sync operations.
 */
enum class SyncPriority {
    LOW,        // Background sync only
    NORMAL,     // Standard sync when battery allows
    HIGH,       // Priority sync
    CRITICAL    // Always sync immediately
}

/**
 * File metadata with permission information
 * 
 * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md Section 2.4
 * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART1.md Section 4 (USER vs TASK recipients)
 */
@Serializable
data class FileMetadata(
    val fileId: String,
    val path: String,
    val sizeBytes: Long,
    val owner: String,                          // Task requester node public key
    val recipients: List<RecipientEntry>,       // Authorized recipients with type info
    val createdAt: Long,
    val lastAccessedBy: String? = null,
    val encryptionKeyId: String? = null
) {
    /**
     * Get all active (non-expired) recipients.
     */
    fun getActiveRecipients(): List<RecipientEntry> {
        return recipients.filter { !it.isExpired() }
    }
    
    /**
     * Get all USER recipients (long-lived).
     */
    fun getUserRecipients(): List<RecipientEntry> {
        return recipients.filter { it.recipientType == RecipientType.USER }
    }
    
    /**
     * Get all TASK recipients (ephemeral).
     */
    fun getTaskRecipients(): List<RecipientEntry> {
        return recipients.filter { it.recipientType == RecipientType.TASK }
    }
    
    /**
     * Check if a specific task has access.
     */
    fun hasTaskAccess(taskId: String): Boolean {
        return recipients.any { 
            it.recipientType == RecipientType.TASK && 
            it.taskId == taskId && 
            !it.isExpired() 
        }
    }
}

class DistributedStorageManager(
    private val context: Context,
    private val virtualNode: VirtualNode,
    private val meshGossipService: MeshGossipService,
    private val coreGossipBroadcastService: CoreGossipBroadcastService,
    private val storageConfig: StorageConfiguration,
    private val connectionPool: MeshConnectionPool = MeshConnectionPool.getInstance()
) {
    // --- Event Handlers ---
    var onFileStored: ((fileId: String, file: File) -> Unit)? = null
    var onFileRetrieved: ((fileId: String, file: File) -> Unit)? = null

    companion object {
        private const val TAG = "DistributedStorageManager"
        private const val RETRY_DELAY_MS = 10000L
        private const val RESPONSE_TIMEOUT_MS = 5000L
        private const val MAX_RETRIES = 3
        
        @Volatile
        private var instance: DistributedStorageManager? = null
        
        fun getInstance(context: Context): DistributedStorageManager {
            return instance ?: synchronized(this) {
                instance ?: throw IllegalStateException(
                    "DistributedStorageManager not initialized. Must be initialized through VirtualNode or explicitly via initialize()."
                )
            }
        }
        
        fun initialize(
            context: Context,
            virtualNode: VirtualNode,
            meshGossipService: MeshGossipService,
            coreGossipBroadcastService: CoreGossipBroadcastService,
            storageConfig: StorageConfiguration,
            connectionPool: MeshConnectionPool
        ): DistributedStorageManager {
            return instance ?: synchronized(this) {
                instance ?: DistributedStorageManager(
                    context,
                    virtualNode,
                    meshGossipService,
                    coreGossipBroadcastService,
                    storageConfig,
                    connectionPool
                ).also { instance = it }
            }
        }
    }

    private val stagedSyncManager = StagedSyncManager(
        context = context,
        onSyncComplete = { fileId, replicaCount -> 
            betaLogger.log(LogLevel.DEBUG, TAG, "StagedSyncManager synced file: $fileId with $replicaCount replicas")
        },
        onSyncFailed = { fileId, error ->
            betaLogger.log(LogLevel.ERROR, TAG, "StagedSyncManager sync failed for $fileId: $error")
        }
    )
    private val storageQuotaManager = StorageQuotaManager(context, storageConfig)
    private val encryptionManager = StorageEncryptionManager()
    
    // Service keypair for canonical workflow Step 0
    // Storage node's service keypair - public key sent in StorageNodeResponse,
    // private key used to decrypt incoming chunks
    private val serviceKeypair: Pair<ByteArray, ByteArray> by lazy {
        encryptionManager.generateServiceKeypair()
    }
    val servicePublicKey: ByteArray
        get() = serviceKeypair.first
    private val servicePrivateKey: ByteArray
        get() = serviceKeypair.second
    
    private val betaLogger = BetaTestLogger.getInstance(context)
    
    // In-memory metadata store (TODO: Persist to disk for production)
    private val fileMetadataStore = ConcurrentHashMap<String, FileMetadata>()

    private val _storageStats = MutableStateFlow(StorageStats())
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()
    private val _participationEnabled = MutableStateFlow(false)
    val participationEnabled: StateFlow<Boolean> = _participationEnabled.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Removed: distributedFiles - now using StagedSyncManager.syncedFiles
    // Removed: replicationTracker - DEPRECATED, not needed for canonical workflows
    private val chunkReplicaTracker = ConcurrentHashMap<String, MutableSet<String>>()
    private val storageDataStore: StorageDataStore = StorageDataStore.getInstance(context)

    // Ecosystem listener integration
    private var meshEcosystemListener: MeshEcosystemListener? = null

    // Track outstanding storage node requests for broadcast/response lifecycle
    private val pendingStorageNodeRequests = ConcurrentLinkedQueue<PendingStorageNodeRequest>()
    private val pendingChunkRetrievals = ConcurrentHashMap<String, MutableList<ChunkRetrievalResponse>>()
    private val pendingReplicaResponses = ConcurrentHashMap<String, MutableList<ReplicaResponse>>()
    private val pendingPermissionConfirmations = ConcurrentHashMap<String, MutableList<FilePermissionUpdateConfirmationMessage>>()

    data class PendingStorageNodeRequest(
        val request: StorageNodeRequest,
        val chunk: MeshChunk,
        val fileId: String,
        val desiredReplicas: Int,
        val responses: MutableList<StorageNodeResponse> = mutableListOf(),
        var retries: Int = 0,
        val completion: CompletableDeferred<List<StorageNodeResponse>> = CompletableDeferred()
    )

    fun registerWithEcosystemListener(listener: MeshEcosystemListener) {
        meshEcosystemListener = listener
        listener.registerStorageManager(this)
    }

    fun unregisterFromEcosystemListener(listener: MeshEcosystemListener) {
        if (meshEcosystemListener == listener) {
            meshEcosystemListener = null
            // Optionally: remove this manager from listener's registry
        }
    }

    /**
     * Handles responses from storage node broadcast.
     * Evaluates responses, retries if needed, and continues lifecycle for chunk distribution.
     * 
     * @param requestId The ID of the originating request for correlation
     * @param senderId The node address that sent the response
     * @param response The storage node response
     */
    fun handleStorageNodeResponse(requestId: String, senderId: Int, response: StorageNodeResponse) {
        pendingStorageNodeRequests.forEach { pending ->
            if (pending.request.fileId == response.fileId) {
                pending.responses.add(response)
            }
        }
        // Canonical workflow: no replication tracking needed, handled by daisy-chain
        
        betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Received storage node response for request $requestId from node $senderId (fileId=${response.fileId})"
        )
    }

    /**
     * Handles chunk retrieval responses.
     * Used to aggregate responses for ongoing retrieval requests.
     * 
     * @param requestId The ID of the originating request for correlation
     * @param senderId The node address that sent the response
     * @param response The chunk retrieval response
     */
    fun handleChunkRetrievalResponse(requestId: String, senderId: Int, response: ChunkRetrievalResponse) {
        val chunkId = response.chunkId
        pendingChunkRetrievals.computeIfAbsent(chunkId) { mutableListOf() }.add(response)
        
        betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Received chunk retrieval response for request $requestId from node $senderId (chunkId=$chunkId)"
        )
        // Optionally: trigger retrieval continuation or update state
    }

    /**
     * Handles replica responses.
     * Used to track replica state and health for files/chunks.
     * 
     * @param requestId The ID of the originating request for correlation
     * @param senderId The node address that sent the response
     * @param response The replica response
     */
    fun handleReplicaResponse(requestId: String, senderId: Int, response: ReplicaResponse) {
        val fileId = response.fileId
        pendingReplicaResponses.computeIfAbsent(fileId) { mutableListOf() }.add(response)
        // Canonical workflow: no replication tracking needed, handled by daisy-chain
        
        betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Received replica response for request $requestId from node $senderId (fileId=$fileId)"
        )
    }

    /**
     * Handles permission update confirmations.
     * Used to track confirmation status for permission updates.
     */
    fun handlePermissionUpdateConfirmation(confirmation: FilePermissionUpdateConfirmationMessage) {
        val fileId = confirmation.fileId
        pendingPermissionConfirmations.computeIfAbsent(fileId) { mutableListOf() }.add(confirmation)
        // Optionally: update permission state or trigger next lifecycle step
    }

    /**
     * Handles inbound chunk/file transfer events using connection pool.
     * Called by MeshEcosystemListener.
     */
    /**
     * Handle incoming chunk transfer from requester or another storage node.
     * 
     * Canonical workflow Step 2: Storage node receives and processes chunk.
     * 
     * Process:
     * 1. Decrypt chunk using storage node's service private key
     * 2. Increment replicaCount before storing
     * 3. Write chunk to filesystem
     * 4. Index in StorageDataStore with updated MeshChunk
     * 5. Send completion notification to requester
     * 6. Initiate daisy-chain replication if replicaCount < desiredReplicas
     * 
     * @param senderId Node address that sent the chunk
     * @param chunkTransfer The incoming chunk transfer message
     */
    fun handleIncomingChunkTransfer(senderId: Int, chunkTransfer: ChunkTransferMessage) {
        scope.launch {
            try {
                betaLogger.log(
                    LogLevel.DEBUG,
                    TAG,
                    "Receiving chunk ${chunkTransfer.chunkId} from node $senderId " +
                    "(replica ${chunkTransfer.replicaCount}/${chunkTransfer.desiredReplicas ?: "?"})"
                )
                
                // Step 1: Decrypt chunk using storage node's service private key
                val decryptedChunkBytes = try {
                    encryptionManager.decrypt(chunkTransfer.chunkBytes)
                } catch (e: Exception) {
                    betaLogger.log(
                        LogLevel.ERROR,
                        TAG,
                        "Failed to decrypt chunk ${chunkTransfer.chunkId}: ${e.message}"
                    )
                    // TODO: Send failure notification to requester
                    return@launch
                }
                
                // Step 2: Increment replicaCount (canonical workflow)
                val newReplicaCount = chunkTransfer.replicaCount + 1
                
                betaLogger.log(
                    LogLevel.DEBUG,
                    TAG,
                    "Decrypted chunk ${chunkTransfer.chunkId}, incrementing replica count: " +
                    "${chunkTransfer.replicaCount} -> $newReplicaCount"
                )
                
                // Step 3: Write chunk to filesystem
                val sharedStorageDir = File(
                    context.filesDir,
                    "shared_storage/${chunkTransfer.fileId}/${chunkTransfer.relativePath}"
                )
                if (!sharedStorageDir.exists()) sharedStorageDir.mkdirs()
                
                val chunkFile = File(sharedStorageDir, "${chunkTransfer.chunkId}.chunk")
                FileOutputStream(chunkFile).use { it.write(decryptedChunkBytes) }
                
                betaLogger.log(
                    LogLevel.DEBUG,
                    TAG,
                    "Wrote chunk ${chunkTransfer.chunkId} to filesystem: ${chunkFile.absolutePath}"
                )
                
                // Step 4: Index in StorageDataStore with canonical fields
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
                    recipientKeyIds = chunkTransfer.recipientKeyIds,
                    sessionKeys = chunkTransfer.sessionKeys,
                    replicaCount = newReplicaCount // Updated replica count
                )
                
                addMeshChunk(meshChunk)
                
                betaLogger.log(
                    LogLevel.INFO,
                    TAG,
                    "Chunk ${chunkTransfer.chunkId} stored successfully (replica $newReplicaCount)"
                )
                
                // Step 5: Send completion notification to requester
                // TODO: Implement completion notification
                
                // Step 6: Initiate daisy-chain replication if needed (Phase 2.3)
                val desiredReplicas = chunkTransfer.desiredReplicas
                if (desiredReplicas != null && newReplicaCount < desiredReplicas) {
                    betaLogger.log(
                        LogLevel.DEBUG,
                        TAG,
                        "Initiating daisy-chain replication for chunk ${chunkTransfer.chunkId} " +
                        "(${newReplicaCount}/${desiredReplicas})"
                    )
                    // Will be implemented in Phase 2.3
                    initiateChunkReplication(meshChunk, chunkTransfer, desiredReplicas)
                } else {
                    betaLogger.log(
                        LogLevel.DEBUG,
                        TAG,
                        "Chunk ${chunkTransfer.chunkId} replication complete " +
                        "(${newReplicaCount}/${desiredReplicas ?: newReplicaCount})"
                    )
                }
                
            } catch (e: Exception) {
                betaLogger.log(
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
     * 
     * Canonical workflow Step 2.5: Storage node acts as requester to propagate chunk.
     * 
     * This implements the daisy-chain replication pattern:
     * - Storage node that just stored a chunk becomes the requester for next replica
     * - Broadcasts StorageNodeRequest to discover available nodes
     * - Selects single best node (not multiple)
     * - Forwards chunk with current replicaCount (recipient will increment)
     * - Natural termination when replicaCount >= desiredReplicas
     * 
     * @param meshChunk The chunk metadata stored locally
     * @param originalTransfer The original transfer message (contains encryption metadata)
     * @param desiredReplicas Target number of replicas for this chunk
     */
    private suspend fun initiateChunkReplication(
        meshChunk: MeshChunk,
        originalTransfer: ChunkTransferMessage,
        desiredReplicas: Int
    ) {
        try {
            betaLogger.log(
                LogLevel.DEBUG,
                TAG,
                "Beginning daisy-chain replication for chunk ${meshChunk.chunkId} " +
                "(current: ${meshChunk.replicaCount}, target: $desiredReplicas)"
            )
            
            // Step 1: Broadcast to discover available storage nodes
            val responses = broadcastStorageNodeRequest(
                chunk = meshChunk,
                fileId = meshChunk.fileId,
                desiredReplicas = desiredReplicas
            )
            
            if (responses.isEmpty()) {
                betaLogger.log(
                    LogLevel.WARN,
                    TAG,
                    "No storage nodes available for chunk ${meshChunk.chunkId} replication"
                )
                return
            }
            
            betaLogger.log(
                LogLevel.DEBUG,
                TAG,
                "Received ${responses.size} storage node responses for chunk ${meshChunk.chunkId}"
            )
            
            // Step 2: Select single best storage node
            val selectedNode = selectBestStorageNode(responses)
            
            if (selectedNode == null) {
                betaLogger.log(
                    LogLevel.WARN,
                    TAG,
                    "No suitable storage node found for chunk ${meshChunk.chunkId} replication"
                )
                return
            }
            
            betaLogger.log(
                LogLevel.DEBUG,
                TAG,
                "Selected node ${selectedNode.nodeId} for chunk ${meshChunk.chunkId} replication " +
                "(fitness: ${selectedNode.fitnessScore}, latency: ${selectedNode.latency}ms)"
            )
            
            // Step 3: Read stored chunk from filesystem (it's decrypted)
            val sharedStorageDir = File(
                context.filesDir,
                "shared_storage/${meshChunk.fileId}/${meshChunk.relativePath}"
            )
            val chunkFile = File(sharedStorageDir, "${meshChunk.chunkId}.chunk")
            
            if (!chunkFile.exists()) {
                betaLogger.log(
                    LogLevel.ERROR,
                    TAG,
                    "Chunk file ${meshChunk.chunkId} not found on filesystem for replication"
                )
                return
            }
            
            val chunkBytes = chunkFile.readBytes()
            
            // Step 4: Re-encrypt chunk for the next storage node
            // Include next storage node's servicePublicKey in recipients
            val nextNodeRecipient = RecipientEntry(
                publicKey = selectedNode.servicePublicKey.toString(Charsets.ISO_8859_1),
                recipientType = RecipientType.USER
            )
            
            // Preserve original recipients and add next node
            val allRecipients = originalTransfer.recipientKeyIds.map { keyId ->
                RecipientEntry(
                    publicKey = keyId.toString(), // TODO: Get actual public key from keyId
                    recipientType = RecipientType.USER
                )
            } + nextNodeRecipient
            
            val encryptedChunkBytes = encryptionManager.encryptWithRecipients(
                data = chunkBytes,
                owner = virtualNode.addressAsInt.toString(), // Storage node is now the owner for this forward
                recipients = allRecipients.map { it.publicKey }
            )
            
            betaLogger.log(
                LogLevel.DEBUG,
                TAG,
                "Re-encrypted chunk ${meshChunk.chunkId} for node ${selectedNode.nodeId}"
            )
            
            // Step 5: Forward chunk with SAME replicaCount (recipient will increment)
            val forwardMessage = ChunkTransferMessage(
                chunkId = meshChunk.chunkId,
                fileId = meshChunk.fileId,
                chunkIndex = meshChunk.chunkIndex,
                totalChunks = meshChunk.totalChunks,
                chunkBytes = encryptedChunkBytes,
                fileName = meshChunk.fileName,
                relativePath = meshChunk.relativePath,
                hash = meshChunk.hash,
                replicaCount = meshChunk.replicaCount, // Keep current count, recipient increments
                recipientKeyIds = allRecipients.map { it.publicKey.hashCode().toLong() },
                sessionKeys = originalTransfer.sessionKeys, // Forward session keys
                desiredReplicas = desiredReplicas
            )
            
            betaLogger.log(
                LogLevel.INFO,
                TAG,
                "Forwarding chunk ${meshChunk.chunkId} to node ${selectedNode.nodeId} " +
                "(replica ${meshChunk.replicaCount} -> will become ${meshChunk.replicaCount + 1})"
            )
            
            // Step 6: Send to selected node
            // TODO: Implement actual message sending via ecosystem
            // meshEcosystemListener?.sendChunkTransfer(selectedNode.nodeId, forwardMessage)
            
            // Track replication
            val chunkReplicaSet = chunkReplicaTracker.getOrPut(meshChunk.chunkId) { mutableSetOf() }
            chunkReplicaSet.add(selectedNode.nodeId)
            
            betaLogger.log(
                LogLevel.DEBUG,
                TAG,
                "Chunk ${meshChunk.chunkId} now has ${chunkReplicaSet.size + 1} replicas tracked"
            )
            
        } catch (e: Exception) {
            betaLogger.log(
                LogLevel.ERROR,
                TAG,
                "Error initiating replication for chunk ${meshChunk.chunkId}: ${e.message}",
                emptyMap(),
                e
            )
        }
    }

    // === CHUNKING ===

    private fun chunkFile(file: File, fileId: String, chunkSize: Int): List<MeshChunk> {
        val chunks = mutableListOf<MeshChunk>()
        val fileName = file.name

        if (MeshrabiyaConstants.getNoChunking()) {
            val fileBytes = file.readBytes()
            val chunkId = sha256(fileBytes)
            chunks.add(
                MeshChunk(
                    chunkId = chunkId,
                    fileId = fileId,
                    chunkIndex = 0,
                    totalChunks = 1,
                    chunkSize = fileBytes.size.toLong(),
                    fileName = fileName,
                    relativePath = "",
                    hash = chunkId
                )
            )
        } else {
            val actualChunkSize = MeshrabiyaConstants.getChunkSizeKb() * 1024
            val totalChunks = ((file.length() + actualChunkSize - 1) / actualChunkSize).toInt()
            FileInputStream(file).use { fis ->
                var chunkIndex = 0
                var bytesRead: Int
                val buffer = ByteArray(actualChunkSize)
                while (fis.read(buffer).also { bytesRead = it } > 0) {
                    val chunkBytes = if (bytesRead < actualChunkSize) buffer.copyOf(bytesRead) else buffer.clone()
                    val chunkId = sha256(chunkBytes)
                    chunks.add(
                        MeshChunk(
                            chunkId = chunkId,
                            fileId = fileId,
                            chunkIndex = chunkIndex,
                            totalChunks = totalChunks,
                            chunkSize = bytesRead.toLong(),
                            fileName = fileName,
                            relativePath = "",
                            hash = chunkId
                        )
                    )
                    chunkIndex++
                }
            }
        }
        return chunks
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } > 0) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // === PUBLIC API ===

    /**
     * Broadcast StorageNodeRequest to discover available storage nodes for a chunk.
     * Canonical workflow Step 1: Request broadcast.
     * 
     * @param chunk The chunk to store
     * @param fileId The parent file ID
     * @param desiredReplicas Target replica count
     * @return List of StorageNodeResponse from available nodes
     */
    private suspend fun broadcastStorageNodeRequest(
        chunk: MeshChunk,
        fileId: String,
        desiredReplicas: Int
    ): List<StorageNodeResponse> = coroutineScope {
        val requestId = "${fileId}_${chunk.chunkIndex}_${System.currentTimeMillis()}"
        val request = StorageNodeRequest(
            requestId = requestId,
            chunkId = chunk.chunkId,
            chunkIndex = chunk.chunkIndex,
            fileId = fileId,
            desiredReplicas = desiredReplicas,
            chunkSizeBytes = chunk.chunkSize,
            replicaCount = 0, // Always 0 for initial request
            requiredSpace = chunk.chunkSize, // Legacy field
            fileName = chunk.fileName,
            senderId = virtualNode.addressAsInt.toString()
        )
        
        // Create pending request to collect responses
        val pending = PendingStorageNodeRequest(
            request = request,
            chunk = chunk,
            fileId = fileId,
            desiredReplicas = desiredReplicas
        )
        pendingStorageNodeRequests.add(pending)
        
    // Broadcast via CoreGossipBroadcastService
    coreGossipBroadcastService.sendStorageNodeRequest(request)
        
        betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Broadcasted StorageNodeRequest for chunk ${chunk.chunkId} (requestId=$requestId)"
        )
        
        // Wait for responses (timeout after RESPONSE_TIMEOUT_MS)
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < RESPONSE_TIMEOUT_MS) {
            if (pending.responses.isNotEmpty()) {
                break
            }
            delay(100) // Poll every 100ms
        }
        
        pendingStorageNodeRequests.remove(pending)
        
        betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Received ${pending.responses.size} StorageNodeResponses for chunk ${chunk.chunkId}"
        )
        
        pending.responses.toList()
    }
    
    /**
     * Select the single best storage node from available responses.
     * Canonical workflow Step 1.2: Single node selection (not multiple).
     * 
     * Selection criteria:
     * 1. Sufficient available space
     * 2. Highest fitness score (combines latency, capacity, health)
     * 3. Lowest latency as tiebreaker
     * 
     * @param responses List of available storage node responses
     * @return Single best StorageNodeResponse, or null if none suitable
     */
    private fun selectBestStorageNode(
        responses: List<StorageNodeResponse>
    ): StorageNodeResponse? {
        if (responses.isEmpty()) {
            betaLogger.log(LogLevel.WARN, TAG, "No storage node responses available")
            return null
        }
        
        // Filter nodes with sufficient space and healthy state
        val suitableNodes = responses.filter { response ->
            response.availableSpace > 0 && response.systemState == "HEALTHY"
        }
        
        if (suitableNodes.isEmpty()) {
            betaLogger.log(LogLevel.WARN, TAG, "No suitable storage nodes (all full or unhealthy)")
            return null
        }
        
        // Select node with highest fitness score, breaking ties with latency
        val bestNode = suitableNodes.maxWithOrNull(
            compareBy<StorageNodeResponse> { it.fitnessScore }
                .thenBy { -it.latency } // Lower latency is better (negate for descending)
        )
        
        bestNode?.let {
            betaLogger.log(
                LogLevel.INFO,
                TAG,
                "Selected storage node ${it.nodeId}: fitness=${it.fitnessScore}, " +
                "latency=${it.latency}ms, available=${it.availableSpace}B"
            )
        }
        
        return bestNode
    }

    /**
     * Store file with permission parameters
     * 
     * Canonical workflow: Single-node selection with post-selection encryption.
     * AccessScope removed - permissions managed via RecipientEntry lists.
     * 
     * @param path File path
     * @param data File data (unencrypted - encryption happens per-chunk after node selection)
     * @param priority Sync priority level
     * @param replicationLevel Number of replicas across mesh
     * @param owner Task requester node public key (null = local node)
     * @param recipients Authorized recipients with type info (null = owner only as USER)
     */
    suspend fun storeFile(
        path: String,
        data: ByteArray,
        priority: SyncPriority = SyncPriority.NORMAL,
        replicationLevel: ReplicationLevel = ReplicationLevel.STANDARD,
        owner: String? = null,
        recipients: List<RecipientEntry>? = null
    ): FileReference? = coroutineScope {
        betaLogger.log(LogLevel.DEBUG, "Storage", "Write operation started: $path (${data.size} bytes)")

        if (!storageQuotaManager.canStoreFile(data.size.toLong())) {
            betaLogger.log(LogLevel.WARN, "Storage", "Storage quota exceeded for file: $path (${data.size} bytes)")
            throw StorageQuotaExceededException("Insufficient storage quota")
        }

        // Canonical workflow: Prepare permission metadata
        val effectiveOwner = owner ?: virtualNode.addressAsInt.toString()
        val effectiveRecipients = recipients ?: listOf(
            RecipientEntry(
                publicKey = effectiveOwner,
                recipientType = RecipientType.USER
            )
        )
        
        betaLogger.log(
            LogLevel.DEBUG,
            "DistributedStorage",
            "Starting file storage: $path, size=${data.size}B, owner=$effectiveOwner, " +
            "recipients=${effectiveRecipients.size}"
        )

        // Canonical workflow: Store unencrypted data temporarily for chunking
        // Encryption happens PER CHUNK after storage node selection
        val file = File(path)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(data) }
        
        val fileId = sha256File(file)
        val chunkSize = MeshrabiyaConstants.getChunkSizeKb() * 1024
        val chunks = chunkFile(file, fileId, chunkSize)
        
        val desiredReplicas = when (replicationLevel) {
            ReplicationLevel.MINIMAL -> MeshrabiyaConstants.getMinimalReplicaCount()
            ReplicationLevel.STANDARD -> MeshrabiyaConstants.getStandardReplicaCount()
            ReplicationLevel.HIGH -> MeshrabiyaConstants.getHighReplicaCount()
            ReplicationLevel.CRITICAL -> MeshrabiyaConstants.getCriticalReplicaCount()
        }
        
        chunkReplicaTracker.clear()
        
        // Canonical workflow: Process each chunk with single-node selection and targeted encryption
        val jobs = chunks.map { chunk ->
            async {
                // Step 1: Broadcast StorageNodeRequest to discover available nodes
                val storageNodeResponses = broadcastStorageNodeRequest(chunk, fileId, desiredReplicas)
                
                // Step 2: Select SINGLE best storage node (canonical workflow)
                val selectedNode = selectBestStorageNode(storageNodeResponses)
                
                if (selectedNode != null) {
                    // Step 3: Read chunk bytes
                    val chunkBytes = readChunkBytes(file, chunk)
                    
                    // Step 4: Encrypt chunk INCLUDING storage node's service public key
                    // This allows storage node to decrypt for re-encryption without network request
                    val allRecipients = effectiveRecipients + RecipientEntry(
                        publicKey = selectedNode.servicePublicKey.toString(Charsets.ISO_8859_1),
                        recipientType = RecipientType.USER // Storage node as user-type recipient
                    )
                    
                    betaLogger.log(
                        LogLevel.DEBUG,
                        TAG,
                        "Encrypting chunk ${chunk.chunkId} for ${allRecipients.size} recipients (including storage node)"
                    )
                    
                    val encryptedChunkBytes = encryptionManager.encryptWithRecipients(
                        data = chunkBytes,
                        owner = effectiveOwner,
                        recipients = allRecipients.map { it.publicKey }
                    )
                    
                    // Step 5: Create ChunkTransferMessage with canonical fields
                    val chunkMsg = ChunkTransferMessage(
                        chunkId = chunk.chunkId,
                        fileId = chunk.fileId,
                        chunkIndex = chunk.chunkIndex,
                        totalChunks = chunk.totalChunks,
                        fileName = chunk.fileName,
                        relativePath = chunk.relativePath,
                        chunkBytes = encryptedChunkBytes,
                        hash = chunk.hash,
                        replicaCount = 0, // Canonical: Always starts at 0
                        recipientKeyIds = allRecipients.map { it.publicKey.hashCode().toLong() },
                        sessionKeys = emptyMap(), // TODO: Populate from encryption manager
                        desiredReplicas = desiredReplicas
                    )
                    
                    // Step 6: Send chunk to selected storage node
                    // TODO: Implement actual message sending via ecosystem
                    chunkReplicaTracker[chunk.chunkId] = mutableSetOf()
                    chunkReplicaTracker[chunk.chunkId]?.add(selectedNode.nodeId)
                    
                    betaLogger.log(
                        LogLevel.INFO,
                        TAG,
                        "Chunk ${chunk.chunkId} prepared for node ${selectedNode.nodeId} (replica 0/${desiredReplicas})"
                    )
                } else {
                    betaLogger.log(LogLevel.WARN, TAG, "No storage node available for chunk ${chunk.chunkId}")
                }
            }
        }
        jobs.forEach { it.await() }

            // Register with StagedSyncManager for battery-aware sync orchestration
            val targetReplicaCount = when (replicationLevel) {
                ReplicationLevel.MINIMAL -> MeshrabiyaConstants.getMinimalReplicaCount()
                ReplicationLevel.STANDARD -> MeshrabiyaConstants.getStandardReplicaCount()
                ReplicationLevel.HIGH -> MeshrabiyaConstants.getHighReplicaCount()
                ReplicationLevel.CRITICAL -> MeshrabiyaConstants.getCriticalReplicaCount()
            }
            
            stagedSyncManager.registerForSync(
                filePath = path,
                fileId = fileId,
                size = data.size.toLong(),
                priority = priority,
                targetReplicaCount = targetReplicaCount
            )
            
            // Update chunk and node tracking in StagedSyncManager
            stagedSyncManager.updateChunkIds(path, chunks.map { it.chunkId })
            stagedSyncManager.updateMeshNodeIds(path, chunkReplicaTracker.values.flatten().toList())

            // Store metadata with permissions (canonical workflow - no AccessScope)
            val fileMetadata = FileMetadata(
                fileId = fileId,
                path = path,
                sizeBytes = data.size.toLong(),
                owner = effectiveOwner,
                recipients = effectiveRecipients,
                createdAt = System.currentTimeMillis()
            )
            fileMetadataStore[fileId] = fileMetadata
            betaLogger.log(
                LogLevel.DEBUG,
                "DistributedStorage",
                "File metadata stored for $fileId: owner=$effectiveOwner, recipients=${effectiveRecipients.size}"
            )

            updateStorageStats()
            betaLogger.log(LogLevel.DEBUG, "Storage", "Write operation completed: $path")
            onFileStored?.invoke(fileId, file)
            
            // Return FileReference for compatibility
            FileReference(fileId, path, data.size.toLong())
    }
        // CLIENT ENTRY POINT: storeFile
        // Commented out for migration to DistributedStorageClient.kt
        /*
        suspend fun storeFile(
            path: String,
            data: ByteArray,
            priority: SyncPriority = SyncPriority.NORMAL,
            replicationLevel: ReplicationLevel = ReplicationLevel.STANDARD,
            owner: String? = null,
            recipients: List<RecipientEntry>? = null
        ): FileReference? = coroutineScope {
            ...existing code...
        }
        */

    private fun readChunkBytes(file: File, chunk: MeshChunk): ByteArray {
        val fis = FileInputStream(file)
        fis.skip(chunk.chunkIndex * chunk.chunkSize)
        val buffer = ByteArray(chunk.chunkSize.toInt())
        val bytesRead = fis.read(buffer)
        fis.close()
        return if (bytesRead < buffer.size) buffer.copyOf(bytesRead) else buffer
    }

    suspend fun retrieveFile(fileRef: FileReference): ByteArray? = coroutineScope {
        betaLogger.log(LogLevel.DEBUG, "Storage", "Read operation started: ${fileRef.path}")

        // Try reading from local file first
        val file = File(fileRef.path)
        val localData = if (file.exists()) file.readBytes() else null
        
        if (localData != null) {
            betaLogger.log(LogLevel.DEBUG, "Storage", "File retrieved from local storage: ${fileRef.path}")
            
            // Update last accessed time in StagedSyncManager
            stagedSyncManager.updateLastAccessed(fileRef.path)
            
            betaLogger.log(LogLevel.DEBUG, "DistributedStorage", "Starting decryption for local file: ${fileRef.path}, size=${localData.size}B")
            val decryptStartTime = System.currentTimeMillis()
            val decryptedData = encryptionManager.decrypt(localData)
            val decryptDuration = System.currentTimeMillis() - decryptStartTime
            betaLogger.log(
                LogLevel.DEBUG,
                "DistributedStorage",
                "Local decryption complete for ${fileRef.path}: ${localData.size}B -> ${decryptedData.size}B in ${decryptDuration}ms"
            )
            betaLogger.log(LogLevel.DEBUG, "Storage", "Read operation completed: ${fileRef.path}")
            onFileRetrieved?.invoke(fileRef.id, File(fileRef.path))
            return@coroutineScope decryptedData
        }

        if (_participationEnabled.value) {
            betaLogger.log(LogLevel.DEBUG, "Storage", "Attempting mesh retrieval: ${fileRef.path}")
            val chunks = getFileChunks(fileRef.id)
            val retrievedChunks = Array<ByteArray?>(chunks.size) { null }
            val jobs = chunks.map { chunk ->
                async {
                    val connection = try {
                        connectionPool.acquireConnection(timeoutMs = RESPONSE_TIMEOUT_MS)
                    } catch (e: Exception) {
                        null
                    }
                    if (connection != null) {
                        try {
                            val chunkInfoList = pendingChunkRetrievals[chunk.chunkId] ?: emptyList()
                            val nodeIds = chunkInfoList.map { it.nodeId }
                                // Canonical workflow: direct chunk requests are obsolete.
                                // for (nodeId in nodeIds) {
                                //     val msgPackBytes = connection.virtualNode.requestChunkFromNode(nodeId, chunk.chunkId)
                                //     if (msgPackBytes != null) {
                                //         val chunkMsg = MeshEcosystemMessage.fromBytes(msgPackBytes)
                                //         if (chunkMsg is ChunkTransferMessage && chunkMsg.chunkId == chunk.chunkId) {
                                //             retrievedChunks[chunk.chunkIndex] = chunkMsg.chunkBytes
                                //             break
                                //         }
                                //     }
                                // }
                                // TODO: Refactor to use mesh broadcast (CoreGossipBroadcastService.sendChunkRetrievalQuery) and aggregate responses.
                        } finally {
                            connectionPool.releaseConnection(connection)
                        }
                    } else {
                        betaLogger.log(LogLevel.ERROR, TAG, "No available connection for chunk retrieval")
                    }
                }
            }
            jobs.forEach { it.await() }

            if (retrievedChunks.any { it == null }) {
                betaLogger.log(LogLevel.ERROR, TAG, "Failed to retrieve all chunks for file ${fileRef.id}")
                return@coroutineScope null
            }

            val fileBytes = retrievedChunks.filterNotNull().reduce { acc, bytes -> acc + bytes }
            betaLogger.log(LogLevel.INFO, TAG, "File ${fileRef.id} reassembled from mesh")
            return@coroutineScope fileBytes
        } else {
            betaLogger.log(LogLevel.WARN, "Storage", "File not found locally and mesh participation disabled: ${fileRef.path}")
            return@coroutineScope null
        }
    }
        // CLIENT ENTRY POINT: retrieveFile
        // Commented out for migration to DistributedStorageClient.kt
        /*
        suspend fun retrieveFile(fileRef: FileReference): ByteArray? = coroutineScope {
            ...existing code...
        }
        */

    private fun getFileChunks(fileId: String): List<MeshChunk> {
        // Get file metadata from StagedSyncManager
        val syncedFile = stagedSyncManager.getSyncedFileByFileId(fileId)
        if (syncedFile == null) {
            betaLogger.log(LogLevel.WARN, TAG, "No synced file found for fileId: $fileId")
            return emptyList()
        }
        
        // Use stored chunkIds from StagedSyncManager
        return syncedFile.chunkIds.mapIndexed { idx, chunkId ->
            MeshChunk(
                chunkId = chunkId,
                fileId = fileId,
                chunkIndex = idx,
                totalChunks = syncedFile.chunkIds.size,
                chunkSize = MeshrabiyaConstants.getChunkSizeKb() * 1024L,
                fileName = File(syncedFile.filePath).name,
                relativePath = "",
                hash = chunkId
            )
        }
    }

    fun addMeshChunk(chunk: MeshChunk) = storageDataStore.addMeshChunk(chunk)
    fun getMeshChunk(chunkId: String): MeshChunk? = storageDataStore.getMeshChunk(chunkId)
    fun getAllMeshChunks(): List<MeshChunk> = storageDataStore.getAllMeshChunks()

    data class StorageConfiguration(
        val defaultReplicationFactor: Int = 3,
        val encryptionEnabled: Boolean = true,
        val compressionEnabled: Boolean = true,
        val maxFileSize: Long = 100L * 1024 * 1024,
        val defaultQuota: Long = 1L * 1024 * 1024 * 1024
    )

    data class StorageParticipationConfig(
        val participationEnabled: Boolean,
        val totalQuota: Long,
        val allowedDirectories: List<String>,
        val encryptionRequired: Boolean = true
    )

    data class StorageStats(
        val totalOffered: Long = 0L,
        val currentlyUsed: Long = 0L,
        val filesStored: Int = 0,
        val replicationHealth: Float = 1.0f
    )

    // Removed: DistributedFileInfo - now using StagedSyncManager.SyncedFile
    
    /**
     * Get file metadata by fileId
     * 
     * @param fileId File ID (SHA-256 hash)
     * @return FileMetadata if exists, null otherwise
     */
    fun getFileMetadata(fileId: String): FileMetadata? {
        return fileMetadataStore[fileId]
    }
    
    /**
     * Update file access permissions dynamically without re-encrypting the entire file.
     * Only re-encrypts chunk keys for new/removed recipients.
     * 
     * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART5.md Section 14.2
     * 
     * @param fileId File ID to update
     * @param addRecipients New recipients to grant access
     * @param removeRecipients Recipients to revoke access from
     * @return true if successful, false if file not found or operation failed
     */
    suspend fun updateFileAccess(
        fileId: String,
        addRecipients: List<RecipientEntry> = emptyList(),
        removeRecipients: List<String> = emptyList() // Public keys to remove
    ): Boolean = withContext(Dispatchers.IO) {
        val metadata = fileMetadataStore[fileId] ?: return@withContext false
        
        betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Updating file access for $fileId: +${addRecipients.size} recipients, -${removeRecipients.size} recipients"
        )
        
        // Build updated recipient list
        val updatedRecipients = metadata.recipients
            .filter { it.publicKey !in removeRecipients }
            .toMutableList()
            .apply { addAll(addRecipients) }
        
        // Update metadata
        val updatedMetadata = metadata.copy(recipients = updatedRecipients)
        fileMetadataStore[fileId] = updatedMetadata
        
        // Re-encrypt chunk keys for new recipients
        // Note: This is a placeholder for the actual chunk key re-encryption logic
        // Full implementation would:
        // 1. Retrieve encrypted chunk keys from storage
        // 2. Decrypt chunk keys using a master key or owner private key
        // 3. Re-encrypt chunk keys for new recipients
        // 4. Store updated chunk key packets
        
        betaLogger.log(
            LogLevel.INFO,
            TAG,
            "File access updated for $fileId: ${updatedRecipients.size} total recipients"
        )
        
        // TODO: Implement chunk key re-encryption in StorageEncryptionManager
        // encryptionManager.reEncryptChunkKeys(fileId, addRecipients.map { it.publicKey })
        
        true
    }
        // CLIENT ENTRY POINT: updateFileAccess
        // Commented out for migration to DistributedStorageClient.kt
        /*
        suspend fun updateFileAccess(
            fileId: String,
            addRecipients: List<RecipientEntry> = emptyList(),
            removeRecipients: List<String> = emptyList() // Public keys to remove
        ): Boolean = withContext(Dispatchers.IO) {
            ...existing code...
        }
        */
    
    /**
     * DTO for network transport of file metadata.
     * Used by interop layer for serialization only.
     */
    data class FileTransportDTO(
        val path: String,
        val fileId: String,
        val replicationLevel: ReplicationLevel,
        val priority: SyncPriority,
        val createdAt: Long,
        val lastAccessed: Long,
        val meshReferences: List<String> = emptyList(),
        val checksum: String = ""
    )

    class StorageQuotaExceededException(message: String) : Exception(message)

    /**
     * Update storage stats based on current chunks and metadata.
     * Called after file storage operations.
     */
    private fun updateStorageStats() {
        val allChunks = storageDataStore.getAllMeshChunks()
        val totalUsed = allChunks.sumOf { it.chunkSize }
        val filesCount = fileMetadataStore.size
        
        // Calculate replication health: ratio of chunks with adequate replicas
        // Use chunkReplicaTracker which maps chunkId -> Set of node addresses
        val replicationHealth = if (allChunks.isEmpty()) 1.0f else {
            val healthyChunks = allChunks.count { chunk ->
                val replicas = chunkReplicaTracker[chunk.chunkId]?.size ?: 0
                replicas >= storageConfig.defaultReplicationFactor
            }
            healthyChunks.toFloat() / allChunks.size
        }
        
        _storageStats.value = StorageStats(
            totalOffered = storageConfig.defaultQuota,
            currentlyUsed = totalUsed,
            filesStored = filesCount,
            replicationHealth = replicationHealth
        )
    }
}