package com.ustadmobile.meshrabiya.storage

import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.MeshConnectionPool
import com.ustadmobile.meshrabiya.vnet.CoreGossipBroadcastService
import com.ustadmobile.meshrabiya.service.StorageNodeRequest

import com.ustadmobile.meshrabiya.vnet.MeshChunk
import com.ustadmobile.meshrabiya.service.StorageNodeResponse
import com.ustadmobile.meshrabiya.service.ChunkRetrievalResponse
import com.ustadmobile.meshrabiya.service.ReplicaResponse
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl
import com.ustadmobile.meshrabiya.storage.FileReference
import com.ustadmobile.meshrabiya.storage.FileMetadata
// import com.ustadmobile.meshrabiya.model.RecipientEntry
// import com.ustadmobile.meshrabiya.model.RecipientType
import com.ustadmobile.meshrabiya.service.FilePermissionUpdateConfirmationMessage
import com.ustadmobile.meshrabiya.storage.PendingStorageNodeRequest
import com.ustadmobile.meshrabiya.service.ChunkRetrievalQuery
import com.ustadmobile.meshrabiya.service.ChunkRetrievalQueryMessage
import com.ustadmobile.meshrabiya.service.ChunkTransferMessage
import java.security.PublicKey
import com.ustadmobile.meshrabiya.util.toHash
import com.ustadmobile.meshrabiya.util.toPublicKey
import java.util.Base64
/**
 * DistributedStorageClient: Client-side workflows for distributed storage.
 * 
 * Responsibilities:
 * - Initiate file storage (Workflow 1)
 * - Initiate file retrieval (Workflow 2)
 * - Initiate permission updates (Workflow 3)
 * - Broadcast requests and collect responses
 * - Select storage nodes
 * - Prepare and encrypt chunks
**/
class DistributedStorageClient(
    private val manager: DistributedStorageManager,
    private val virtualNode: VirtualNode,
    
) {
    companion object {
        private const val TAG = "DistributedStorageClient"
        private const val RESPONSE_TIMEOUT_MS = 5000L
        private const val RETRY_DELAY_MS = 10000L
        private const val MAX_RETRIES = 3
    }
    
    // === Request Tracking ===
    
    // PendingStorageNodeRequest tracking removed: canonical message flow only
    private val pendingChunkRetrievals = ConcurrentHashMap<String, MutableList<ChunkRetrievalResponse>>()
    private val pendingReplicaResponses = ConcurrentHashMap<String, MutableList<ReplicaResponse>>()
    private val pendingPermissionConfirmations = ConcurrentHashMap<String, MutableList<FilePermissionUpdateConfirmationMessage>>()
        // (Removed orphaned/duplicate code block that caused unresolved reference errors)
    private val pendingChunkTransfers = ConcurrentHashMap<String, ChunkTransferMessage>()
    private val pendingStorageNodeRequests = ConcurrentLinkedQueue<PendingStorageNodeRequest>()
    
    // === Response Handlers ===
    
    fun handleStorageNodeResponse(requestId: String, senderId: Int, response: StorageNodeResponse) {
        // PendingStorageNodeRequest logic removed: canonical message flow only
        
        manager.betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Received storage node response for request $requestId from node $senderId (fileId=${response.fileId})"
        )
    }
    
    fun handleChunkRetrievalResponse(requestId: String, senderId: Int, response: ChunkRetrievalResponse) {
        val chunkId = response.chunkId
        pendingChunkRetrievals.computeIfAbsent(chunkId) { mutableListOf() }.add(response)
        
        manager.betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Received chunk retrieval response for request $requestId from node $senderId (chunkId=$chunkId)"
        )
    }
    
    fun handleReplicaResponse(requestId: String, senderId: Int, response: ReplicaResponse) {
        val fileId = response.fileId
        pendingReplicaResponses.computeIfAbsent(fileId) { mutableListOf() }.add(response)
        
        manager.betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Received replica response for request $requestId from node $senderId (fileId=$fileId)"
        )
    }
    
    fun handlePermissionUpdateConfirmation(confirmation: FilePermissionUpdateConfirmationMessage) {
        val fileId = confirmation.fileId
        pendingPermissionConfirmations.computeIfAbsent(fileId) { mutableListOf() }.add(confirmation)
    }
    
    // === Client Workflows ===
    
    /**
     * Store file with permission parameters.
     * Canonical workflow Step 1: Client-side file preparation and distribution.
    **/
    suspend fun storeFile(
        path: String,
        data: ByteArray,
        replicationLevel: ReplicationLevel = ReplicationLevel.STANDARD,
        // owner: RecipientEntry? = null,
        recipients: List<RecipientEntry>? = null
    ): FileReference? = coroutineScope {
        manager.betaLogger.log(LogLevel.DEBUG, TAG, "Write operation started: $path (${data.size} bytes)")

        if (!manager.storageQuotaManager.canStoreFile(data.size.toLong())) {
            manager.betaLogger.log(LogLevel.WARN, TAG, "Storage quota exceeded for file: $path (${data.size} bytes)")
            throw DistributedStorageManager.StorageQuotaExceededException("Insufficient storage quota")
        }

        // Get current user info for ownership fields
        val user = MeshrabiyaApiImpl.getInstance().getUserInfo()
        
        // val ownerEntry = RecipientEntry(
        //     publicKey = java.util.Base64.getEncoder().encodeToString(user.publicKey.encoded),
        //     recipientType = RecipientType.USER,
        //     recipientId = user.userId
        // )
        val effectiveRecipients = recipients.orEmpty() + user.entry 

        manager.betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Starting file storage: $path, size=${data.size}B, owner=${user.entry.recipientId}, recipients=${effectiveRecipients.size}"
        )

        // Store unencrypted data temporarily for chunking
        val file = File(path)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(data) }

        val fileId = manager.sha256File(file)
        val chunks = manager.chunkFile(
            file,
            fileId,
            // chunkSize,
            owner=user.entry
        )

        manager.chunkReplicaTracker.clear()

        // Process each chunk with single-node selection and targeted encryption
        val jobs = chunks.map { chunk ->
            async {
                // Step 1: Broadcast StorageNodeRequest
                val storageNodeResponses = broadcastStorageNodeRequest(chunk, fileId)

                // Step 2: Select single best storage node
                val selectedNode = selectBestStorageNode(storageNodeResponses)

                if (selectedNode != null) {
                    // Step 3: Read chunk bytes
                    val chunkBytes = manager.readChunkBytes(file, chunk)

                    // Step 4: Build recipients list (owner, storage node, additional recipients)
                    val publicKey = java.util.Base64.getEncoder().encodeToString(selectedNode.servicePublicKey)
                    val publicKeyBytes = selectedNode.servicePublicKey
                    val publicKeyObj = publicKeyBytes.toPublicKey()
                    val storageNodeEntry = RecipientEntry(
                        publicKey = Base64.getEncoder().encodeToString(publicKeyBytes),
                        recipientType = RecipientType.USER,
                        recipientId = publicKeyObj.toHash()
                    )
                    val allRecipients = listOf(storageNodeEntry) + effectiveRecipients
                    
                    val publicKeys: Map<String, String> = allRecipients.associate { it.recipientId to it.publicKey }
                    // Step 5: Hybrid encrypt chunk
                    val (encryptedChunkBytes, sessionKeys) = manager.encryptionManager.encryptWithRecipients(
                        data = chunkBytes,
                        
                        recipientPublicKeys = publicKeys
                    )
                    val chunkMsg = ChunkTransferMessage(
                        chunkId = chunk.chunkId,
                        fileId = chunk.fileId,
                        chunkIndex = chunk.chunkIndex,
                        totalChunks = chunk.totalChunks,
                        fileName = chunk.fileName,
                        relativePath = chunk.relativePath,
                        chunkBytes = encryptedChunkBytes,
                        hash = chunk.hash,
                        replicaCount = 0,
                        recipients = allRecipients,
                        sessionKeys = sessionKeys,
                        owner = user.entry
                    )

                    // Step 7: Send chunk to selected storage node as direct message
                    val targetNodeAddress = selectedNode.nodeId.toInt()
                    virtualNode.sendEcosystemMessage(targetNodeAddress, chunkMsg.toBytes())

                    manager.chunkReplicaTracker[chunk.chunkId] = mutableSetOf()
                    manager.chunkReplicaTracker[chunk.chunkId]?.add(selectedNode.nodeId)

                    manager.betaLogger.log(
                        LogLevel.INFO,
                        TAG,
                        "Chunk ${chunk.chunkId} prepared for node ${selectedNode.nodeId} (replica 0/${allRecipients.size})"
                    )
                } else {
                    manager.betaLogger.log(LogLevel.WARN, TAG, "No storage node available for chunk ${chunk.chunkId}")
                }
            }
        }
        jobs.forEach { it.await() }

        // Register with StagedSyncManager
        val targetReplicaCount = MeshrabiyaConstants.getDefaultReplicaCount()

        manager.stagedSyncManager.registerForSync(
            filePath = path,
            fileId = fileId,
            size = data.size.toLong(),
            targetReplicaCount = targetReplicaCount
        )

        manager.stagedSyncManager.updateChunkIds(path, chunks.map { it.chunkId })
        manager.stagedSyncManager.updateMeshNodeIds(path, manager.chunkReplicaTracker.values.flatten().toList())
        val fileName = File( path).name
        // Store metadata
        val fileMetadata = FileMetadata(
            fileId = fileId,
            path = path,
            sizeBytes = data.size.toLong(),
            owner = user.entry,
            recipients = effectiveRecipients,
            createdAt = System.currentTimeMillis(),
            relativePath = ""
        )
        manager.fileMetadataStore[fileId] = fileMetadata

        manager.updateStorageStats()
        manager.betaLogger.log(LogLevel.DEBUG, TAG, "Write operation completed: $path")
        manager.onFileStored?.invoke(fileId, file)

        FileReference(fileId, path, fileName, data.size.toLong())
    }
    
    /**
    * Retrieve file from distributed storage.
    * Canonical workflow Step 2: Client-side file retrieval.
    **/
    suspend fun retrieveFile(fileId: String): ByteArray? = coroutineScope {
        val metadata = manager.fileMetadataStore[fileId]
        if (metadata != null) {
            val file = File(metadata.path)
            if (file.exists()) {
                return@coroutineScope try {
                    // If encrypted, decrypt here; else just read bytes
                    manager.encryptionManager?.decrypt(file.readBytes()) ?: file.readBytes()
                } catch (e: Exception) {
                    null
                }
            }
        }

        // If not local, retrieve from distributed storage
        val chunks = getFileChunks(fileId)
        if (chunks.isEmpty()) return@coroutineScope null

        val retrievedChunks = Array<ByteArray?>(chunks.size) { null }
         val user = MeshrabiyaApiImpl.getInstance().getUserInfo()
        val jobs = chunks.mapIndexed { idx, chunk ->
            async {
                try {
                    // Step 1: Broadcast ChunkRetrievalQuery
                    val query = ChunkRetrievalQuery(
                        fileId = chunk.fileId,
                        owner = user.entry,
                        senderId = virtualNode.addressAsInt
                    )
                    val message = ChunkRetrievalQueryMessage(query)
                    CoreGossipBroadcastService.getInstance().sendBroadcast(message)

                    // Step 2: Wait for ChunkRetrievalResponse
                    val startTime = System.currentTimeMillis()
                    var response: ChunkRetrievalResponse? = null
                    while (System.currentTimeMillis() - startTime < RESPONSE_TIMEOUT_MS) {
                        val responses = pendingChunkRetrievals[chunk.chunkId]
                        if (responses != null && responses.isNotEmpty()) {
                            response = responses.firstOrNull { it.chunkId == chunk.chunkId }
                            if (response != null) break
                        }
                        delay(100)
                    }
                    if (response == null) {
                        retrievedChunks[idx] = null
                        return@async
                    }

                    // Step 3: Request chunk data directly
                    val chunkBytes = requestChunkData(response, user.entry)
                    retrievedChunks[idx] = chunkBytes
                } catch (e: Exception) {
                    retrievedChunks[idx] = null
                }
            }
        }
        jobs.forEach { it.await() }
        if (retrievedChunks.any { it == null }) return@coroutineScope null

        return@coroutineScope try {
            retrievedChunks.filterNotNull().reduce { acc, bytes -> acc + bytes }
        } catch (e: Exception) {
            null
        }
    }
    // suspend fun retrieveFile(fileId: String): ByteArray? = coroutineScope {
    //     // 1. Check for local file
    //     val metadata = manager.fileMetadataStore[fileId]
    //     if (metadata != null) {
    //         val file = File(metadata.path)
    //         if (file.exists()) {
    //             val decryptedData = manager.encryptionManager.decrypt(file.readBytes())
    //             manager.onFileRetrieved?.invoke(fileId, file)
    //             return@coroutineScope decryptedData
    //         }
    //     }

    //     // 2. Not local: broadcast ChunkRetrievalQuery for each chunk
    //     val user = MeshrabiyaApiImpl.getInstance().getUserInfo()
    //     val userEntry = user.entry
    //     val chunks = getFileChunks(fileId)
    //     if (chunks.isEmpty()) return@coroutineScope null
    //     val retrievedChunks = Array<ByteArray?>(chunks.size) { null }

    //     // 3. For each chunk, broadcast query and request chunk data
    //     val jobs = chunks.mapIndexed { idx, chunk ->
    //         async {
    //             // Broadcast ChunkRetrievalQuery
    //             val query = ChunkRetrievalQuery(
    //                 fileId = "fileId",
    //                 owner = userEntry,
    //                 senderId = virtualNode.addressAsInt
    //             )
    //             val message = ChunkRetrievalQueryMessage(query)
    //             CoreGossipBroadcastService.getInstance().sendBroadcast(message)

    //             // Wait for ChunkRetrievalResponse
    //             val startTime = System.currentTimeMillis()
    //             var response: ChunkRetrievalResponse? = null
    //             while (System.currentTimeMillis() - startTime < RESPONSE_TIMEOUT_MS) {
    //                 val responses = pendingChunkRetrievals[chunk.chunkId]
    //                 if (responses != null && responses.isNotEmpty()) {
    //                     response = responses.firstOrNull { it.chunkId == chunk.chunkId }
    //                     if (response != null) break
    //                 }
    //                 delay(100)
    //             }
    //             if (response == null) return@async

    //             // Request chunk data directly
    //             val chunkBytes = requestChunkData(response, userEntry)
    //             if (chunkBytes != null) {
    //                 retrievedChunks[idx] = chunkBytes
    //             }
    //         }
    //     }
    //     jobs.forEach { it.await() }
    //     if (retrievedChunks.any { it == null }) return@coroutineScope null
    //     val fileBytes = retrievedChunks.filterNotNull().reduce { acc, bytes -> acc + bytes }
    //     return@coroutineScope fileBytes
    // }

    // Helper to request chunk data from a node after receiving ChunkRetrievalResponse
    suspend fun requestChunkData(chunkResponse: ChunkRetrievalResponse, userEntry: RecipientEntry): ByteArray? = coroutineScope {
        val meshFile = manager.storageDataStore.getMeshFile(chunkResponse.fileId)
        if (meshFile == null) {
            manager.betaLogger.log(
                LogLevel.WARN,
                TAG,
                "Mesh file not found for fileId: ${chunkResponse.fileId} (cannot request chunk data)"
            )
            return@coroutineScope null
        }
        val requestMsg = ChunkTransferMessage(
            chunkId = chunkResponse.chunkId,
            fileId = chunkResponse.fileId,
            chunkIndex = chunkResponse.chunkIndex,
            totalChunks = chunkResponse.totalChunks,
            fileName = chunkResponse.fileName,
            relativePath = chunkResponse.relativePath,
            chunkBytes = ByteArray(0), // Empty to indicate request
            hash = chunkResponse.chunkId, // Or actual hash if available
            replicaCount = 0,
            recipients = meshFile.recipients,
            sessionKeys = emptyMap(),
            owner = meshFile.owner
        )
        val targetNodeId = chunkResponse.nodeId.toInt()
        virtualNode.sendEcosystemMessage(targetNodeId, requestMsg.toBytes())
        // Wait for response (implement a pendingChunkTransfers map to receive the response)
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < RESPONSE_TIMEOUT_MS) {
            val chunkMsg = pendingChunkTransfers[chunkResponse.chunkId]
            if (chunkMsg != null && chunkMsg.chunkBytes.isNotEmpty()) {
                return@coroutineScope chunkMsg.chunkBytes
            }
            delay(100)
        }
        null
    }

    // Handler for incoming ChunkTransferMessage responses
    fun handleIncomingChunkTransfer(message: ChunkTransferMessage) {
        if (message.chunkBytes.isNotEmpty()) {
            pendingChunkTransfers[message.chunkId] = message
        }
    }

    /**
     * Internal test to verify visibility of handleIncomingChunkTransfer.
    **/
    fun testHandleIncomingChunkTransferVisibility(chunkTransfer: ChunkTransferMessage) {
        // Should call the public function without issue
        handleIncomingChunkTransfer(chunkTransfer)
    }
    
    /**
     * Update file access permissions.
     * Canonical workflow Step 3: Client-side permission update initiation.
     **/
    suspend fun updateFileAccess(fileId: String, addRecipients:List<RecipientEntry> = emptyList(), removeRecipients:List<String> = emptyList()):Boolean {
        val metadata = manager.fileMetadataStore[fileId] ?: return false
        
        manager.betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Updating file access for $fileId: +${addRecipients.size} recipients, -${removeRecipients.size} recipients"
        )
        
        // Build updated recipient list
        val updatedRecipients = metadata.recipients
            .filter { it.recipientId !in removeRecipients }
            .toMutableList()
            .apply { addAll(addRecipients) }
        
        // Update metadata
        val updatedMetadata = metadata.copy(recipients = updatedRecipients)
        manager.fileMetadataStore[fileId] = updatedMetadata
        
        manager.betaLogger.log(
            LogLevel.INFO,
            TAG,
            "File access updated for $fileId: ${updatedRecipients.size} total recipients"
        )
        
        // TODO: Broadcast FilePermissionUpdateMessage to storage nodes
        
        return true
    }
    
    // === Helper Methods ===
    
    private suspend fun broadcastStorageNodeRequest(
        chunk: MeshChunk,
        fileId: String
    ): List<StorageNodeResponse> = coroutineScope {
        val requestId = "${fileId}_${chunk.chunkIndex}_${System.currentTimeMillis()}"

        val request = StorageNodeRequest(
            requestId = requestId,
            chunkId = chunk.chunkId,
            chunkIndex = chunk.chunkIndex,
            fileId = fileId,
            chunkSizeBytes = chunk.chunkSize,
            replicaCount = 0,
            requiredSpace = chunk.chunkSize,
            fileName = chunk.fileName,
            senderId = virtualNode.addressAsInt
        )
        val pending = PendingStorageNodeRequest(
            request = request,
            chunk = chunk,
            fileId = fileId
        )
        pendingStorageNodeRequests.add(pending)
        CoreGossipBroadcastService.getInstance().sendStorageNodeRequest(request)
        manager.betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Broadcasted StorageNodeRequest for chunk ${chunk.chunkId} (requestId=$requestId)"
        )
        // Wait for responses
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < RESPONSE_TIMEOUT_MS) {
            if (pending.responses.isNotEmpty()) {
                break
            }
            delay(100)
        }
        pendingStorageNodeRequests.remove(pending)
        manager.betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Received ${pending.responses.size} StorageNodeResponses for chunk ${chunk.chunkId}"
        )
        pending.responses.toList()
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
    
    private fun getFileChunks(fileId: String): List<MeshChunk> {
        val syncedFile = manager.stagedSyncManager.getSyncedFileByFileId(fileId)
        if (syncedFile == null) {
            manager.betaLogger.log(LogLevel.WARN, TAG, "No synced file found for fileId: $fileId")
            return emptyList()
        }
        
        val user = MeshrabiyaApiImpl.getInstance().getUserInfo()
        return syncedFile.chunkIds.mapIndexed { idx, chunkId ->
            MeshChunk(
                chunkId = chunkId,
                fileId = fileId,
                chunkIndex = idx,
                totalChunks = syncedFile.chunkIds.size,
                chunkSize = MeshrabiyaConstants.getChunkSizeKb() * 1024L,
                fileName = File(syncedFile.filePath).name,
                relativePath = "",
                hash = chunkId,
                serverPath = syncedFile.filePath
                // owner = user.entry,
                // recipients = emptyList()
            )
        }
    }
}