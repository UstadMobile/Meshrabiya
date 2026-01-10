package com.ustadmobile.meshrabiya.service.compute

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import java.nio.file.Path
import java.nio.file.Paths
import java.io.File

/**
 * Distributed Storage Agent - Handles file storage and retrieval requests
 * within the mesh network as part of the Intelligent Distributed Compute Service
 * 
 * Key Responsibilities:
 * - Handle incoming file shares (create SharedWithMe entries)
 * - Manage lazy downloads for shared files
 * - Replicate files from Drop Folder to other nodes
 * - Store files from other nodes in distributed storage area
 * 
 * TODO: Security & Trust Management
 * 
 * .onion Address Architecture (Research Findings):
 * - .onion addresses are SERVICE-SPECIFIC, not device or user identifiers
 * - Each hidden service gets a unique .onion address via Tor's cryptographic key generation
 * - Multiple services can run on same device with different .onion addresses
 * - Orbot leverages native Tor daemon (via JNI) to generate addresses in service directories
 * - Address is derived from Ed25519 public key (v3 onion services) stored in service's hostname file
 * 
 * Trust & Security Implementation Strategy:
 * - For mesh networking: Each node runs a hidden service, gets unique .onion address as node ID
 * - Peer trust: Implement allowlist/blocklist based on .onion addresses (service-level trust)
 * - Device identification: Use device-specific hidden service for mesh network participation
 * - File validation: Implement signature verification using service's private key
 * - Sandboxing: Isolate SharedWithMe content in separate storage area with restricted permissions
 * 
 * Security Considerations:
 * - .onion address rotation: Services can generate new addresses for privacy
 * - Key escrow: Store service private keys securely (Android Keystore integration)
 * - Network-level authentication: v3 client authentication for restricted access
 * - Reputation system: Track peer behavior based on .onion address history
 */
class DistributedStorageAgent(
    private val meshNetwork: com.ustadmobile.meshrabiya.storage.MeshNetworkInterface,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val localStoragePath: Path = Paths.get("distributed_storage"), // TODO: Use Android Context
    private val dropFolderPath: Path = Paths.get("drop_folder"), // TODO: Get from user selection
    private val maxStorageGB: Float = 5.0f
) {
    
    // For files replicated FROM other nodes (stored in distributed storage area)
    private val storedFiles = ConcurrentHashMap<String, FileMetadata>()
    private val replicationMap = ConcurrentHashMap<String, Set<String>>()
    // Test-only in-memory storage to simulate disk for unit tests
    internal val testStoredData = ConcurrentHashMap<String, ByteArray>()
    
    // For files shared WITH this user (metadata only until downloaded)
    private val sharedWithMeFiles = ConcurrentHashMap<String, SharedFileMetadata>()
    
    private var currentStorageUsedBytes: Long = 0L
    
    init {
        // Ensure SharedWithMe folder exists
        ensureSharedWithMeFolderExists()
    }
    
    data class FileMetadata(
        val fileId: String,
        val originalName: String,
        val sizeBytes: Long,
        val checksumMD5: String,
        val storedTimestamp: Long,
        val accessCount: Long = 0L,
        val lastAccessTimestamp: Long = 0L,
        val replicationFactor: Int = 3,
        val tags: Set<String> = emptySet()
    )
    
    data class SharedFileMetadata(
        val fileId: String,
        val originalName: String,
        val sizeBytes: Long,
        val checksumMD5: String,
        val sharedBy: String,
        val sharedTimestamp: Long,
        val isDownloaded: Boolean = false,
        val downloadedTimestamp: Long? = null,
        val localPath: String? = null
    )
    
    data class StorageRequest(
        val fileId: String,
        val fileName: String,
        val data: ByteArray,
        val replicationFactor: Int = 3,
        val tags: Set<String> = emptySet(),
        val priority: StoragePriority = StoragePriority.NORMAL
    )
    
    data class RetrievalRequest(
        val fileId: String,
        val preferredNodes: Set<String> = emptySet(),
        val maxLatencyMs: Long = 5000L
    )
    
    enum class StoragePriority {
        LOW, NORMAL, HIGH, CRITICAL
    }
    
    // === ERROR HANDLING SYSTEM ===
    
    sealed class StorageError {
        abstract val code: String
        abstract val message: String
        abstract val recoverable: Boolean
        
        // Network Errors
        data class PeerUnreachable(val nodeId: String) : StorageError() {
            override val code = "PEER_UNREACHABLE"
            override val message = "Target node $nodeId is not responding"
            override val recoverable = true
        }
        
        data class NetworkTimeout(val operationTimeMs: Long) : StorageError() {
            override val code = "NETWORK_TIMEOUT"
            override val message = "Operation timed out after ${operationTimeMs}ms"
            override val recoverable = true
        }
        
        object MeshDisconnected : StorageError() {
            override val code = "MESH_DISCONNECTED"
            override val message = "No mesh network connectivity"
            override val recoverable = true
        }
        
        // Storage Errors
        data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : StorageError() {
            override val code = "INSUFFICIENT_SPACE"
            override val message = "Need ${requiredBytes} bytes, only ${availableBytes} available"
            override val recoverable = false
        }
        
        data class DiskIOError(val path: String, val cause: String) : StorageError() {
            override val code = "DISK_IO_ERROR"
            override val message = "File system error at $path: $cause"
            override val recoverable = true
        }
        
        data class PermissionDenied(val path: String) : StorageError() {
            override val code = "PERMISSION_DENIED"
            override val message = "Access denied to $path"
            override val recoverable = false
        }
        
        // Security Errors
        data class UntrustedSource(val nodeId: String) : StorageError() {
            override val code = "UNTRUSTED_SOURCE"
            override val message = "File from untrusted peer: $nodeId"
            override val recoverable = false
        }
        
        data class ChecksumMismatch(val expected: String, val actual: String) : StorageError() {
            override val code = "CHECKSUM_MISMATCH"
            override val message = "File integrity check failed. Expected: $expected, Got: $actual"
            override val recoverable = false
        }
        
        // Application Errors
        data class InvalidFileId(val fileId: String) : StorageError() {
            override val code = "INVALID_FILE_ID"
            override val message = "File identifier not found: $fileId"
            override val recoverable = false
        }
        
        data class AlreadyExists(val fileId: String) : StorageError() {
            override val code = "ALREADY_EXISTS"
            override val message = "File already exists: $fileId"
            override val recoverable = false
        }
        
        // Implementation Errors
        data class NotImplemented(val operation: String) : StorageError() {
            override val code = "NOT_IMPLEMENTED"
            override val message = "Operation not yet implemented: $operation"
            override val recoverable = false
        }
    }
    
    data class StorageResponse(
        val success: Boolean,
        val fileId: String,
        val replicatedNodes: Set<String>,
        val error: StorageError? = null
    )
    
    data class RetrievalResponse(
        val success: Boolean,
        val fileId: String,
        val data: ByteArray?,
        val sourceNode: String? = null,
        val metadata: FileMetadata? = null,
        val error: StorageError? = null
    )
    
    // === SHARED WITH ME FUNCTIONALITY ===
    
    /**
     * Called when a file is shared with this user
     * Creates an entry in SharedWithMe (but doesn't download the file yet)
     */
    suspend fun notifyFileSharedWithUser(
        fileId: String,
        fileName: String,
        sizeBytes: Long,
        checksumMD5: String,
        sharedBy: String
    ): Boolean {
        return try {
            ensureSharedWithMeFolderExists()
            
            val sharedMetadata = SharedFileMetadata(
                fileId = fileId,
                originalName = fileName,
                sizeBytes = sizeBytes,
                checksumMD5 = checksumMD5,
                sharedBy = sharedBy,
                sharedTimestamp = System.currentTimeMillis(),
                isDownloaded = false
            )
            
            sharedWithMeFiles[fileId] = sharedMetadata
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Downloads a shared file to the SharedWithMe folder
     * Called when user clicks the download button
     */
    suspend fun downloadSharedFile(fileId: String): DownloadResult {
        return try {
            val sharedMetadata = sharedWithMeFiles[fileId]
                ?: return DownloadResult.Error(StorageError.InvalidFileId(fileId))
            
            if (sharedMetadata.isDownloaded) {
                return DownloadResult.AlreadyDownloaded(sharedMetadata.localPath!!)
            }
            
            // Retrieve file from mesh network
            val retrievalRequest = RetrievalRequest(fileId = fileId)
            val retrievalResponse = retrieveFile(retrievalRequest)
            
            if (!retrievalResponse.success || retrievalResponse.data == null) {
                return DownloadResult.Error(
                    retrievalResponse.error ?: StorageError.PeerUnreachable("unknown")
                )
            }
            
            // Verify checksum
            val downloadedChecksum = calculateMD5(retrievalResponse.data)
            if (downloadedChecksum != sharedMetadata.checksumMD5) {
                return DownloadResult.Error(
                    StorageError.ChecksumMismatch(sharedMetadata.checksumMD5, downloadedChecksum)
                )
            }
            
            // Save to SharedWithMe folder
            val sharedWithMePath = dropFolderPath.resolve("SharedWithMe")
            val localFilePath = sharedWithMePath.resolve(sharedMetadata.originalName)
            
            val saved = saveFileToSharedWithMe(localFilePath, retrievalResponse.data)
            if (!saved) {
                return DownloadResult.Error(
                    StorageError.DiskIOError(localFilePath.toString(), "Failed to write file")
                )
            }
            
            // Update metadata
            val updatedMetadata = sharedMetadata.copy(
                isDownloaded = true,
                downloadedTimestamp = System.currentTimeMillis(),
                localPath = localFilePath.toString()
            )
            sharedWithMeFiles[fileId] = updatedMetadata
            
            DownloadResult.Success(localFilePath.toString())
            
        } catch (e: Exception) {
            DownloadResult.Error(StorageError.DiskIOError("unknown", e.message ?: "Unknown error"))
        }
    }
    
    /**
     * Get all shared files (both downloaded and not downloaded)
     */
    fun getSharedWithMeFiles(): List<SharedFileMetadata> {
        return sharedWithMeFiles.values.toList()
    }
    
    /**
     * Get only files that are shared but not yet downloaded
     */
    fun getPendingDownloads(): List<SharedFileMetadata> {
        return sharedWithMeFiles.values.filter { !it.isDownloaded }
    }
    
    /**
     * Remove a shared file entry (if user dismisses or deletes)
     */
    suspend fun removeSharedFile(fileId: String): Boolean {
        return try {
            val metadata = sharedWithMeFiles.remove(fileId)
            if (metadata?.isDownloaded == true && metadata.localPath != null) {
                // Also delete the local file
                deleteLocalFile(Paths.get(metadata.localPath))
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    sealed class DownloadResult {
        data class Success(val localPath: String) : DownloadResult()
        data class AlreadyDownloaded(val localPath: String) : DownloadResult()
        data class Error(val error: StorageError) : DownloadResult()
    }
    
    // === REPLICATION TO OTHER NODES (from Drop Folder) ===
    
    /**
     * Replicate a file from the Drop Folder to other nodes in the mesh
     * Called when user shares a file that's already in their Drop Folder
     */
    suspend fun replicateDropFolderFile(
        fileId: String,
        fileName: String,
        localFilePath: Path,
        targetNodes: Set<String>,
        replicationFactor: Int = 3
    ): ReplicationResult {
        return try {
            // Read file from Drop Folder
            val fileData = readFileFromDropFolder(localFilePath)
                ?: return ReplicationResult.Error(
                    StorageError.DiskIOError(localFilePath.toString(), "Failed to read file")
                )
            
            // Calculate checksum
            val checksum = calculateMD5(fileData)
            
            // Create storage request
            val storageRequest = StorageRequest(
                fileId = fileId,
                fileName = fileName,
                data = fileData,
                replicationFactor = replicationFactor,
                priority = StoragePriority.NORMAL
            )
            
            // Replicate to target nodes
            val successfulNodes = mutableSetOf<String>()
            val failedNodes = mutableSetOf<String>()
            
            for (nodeId in targetNodes.take(replicationFactor)) {
                try {
                    val response = replicateToSpecificNode(nodeId, storageRequest)
                    if (response.success) {
                        successfulNodes.add(nodeId)
                    } else {
                        failedNodes.add(nodeId)
                    }
                } catch (e: Exception) {
                    failedNodes.add(nodeId)
                }
            }
            
            ReplicationResult.Success(
                fileId = fileId,
                replicatedNodes = successfulNodes,
                failedNodes = failedNodes,
                checksum = checksum
            )
            
        } catch (e: Exception) {
            ReplicationResult.Error(
                StorageError.DiskIOError("replication", e.message ?: "Unknown error")
            )
        }
    }
    
    sealed class ReplicationResult {
        data class Success(
            val fileId: String,
            val replicatedNodes: Set<String>,
            val failedNodes: Set<String>,
            val checksum: String
        ) : ReplicationResult()
        
        data class Error(val error: StorageError) : ReplicationResult()
    }
    
    // === STORAGE OPERATIONS (for files FROM other nodes) ===
    
    /**
     * Store a file from another node in our distributed storage area
     * This is NOT for Drop Folder files - those are already stored locally
     */
    suspend fun storeFileFromOtherNode(request: StorageRequest): StorageResponse {
        return try {
            // Check storage capacity
            if (!hasCapacityForFile(request.data.size.toLong())) {
                val available = (maxStorageGB * 1024 * 1024 * 1024).toLong() - currentStorageUsedBytes
                return StorageResponse(
                    success = false,
                    fileId = request.fileId,
                    replicatedNodes = emptySet(),
                    error = StorageError.InsufficientSpace(request.data.size.toLong(), available)
                )
            }
            
            // Calculate checksum
            val checksum = calculateMD5(request.data)
            
            // Create metadata
            val metadata = FileMetadata(
                fileId = request.fileId,
                originalName = request.fileName,
                sizeBytes = request.data.size.toLong(),
                checksumMD5 = checksum,
                storedTimestamp = System.currentTimeMillis(),
                replicationFactor = request.replicationFactor,
                tags = request.tags
            )
            
            // Store in distributed storage area (NOT Drop Folder)
            val localStored = storeFileInDistributedStorage(request.fileId, request.data, metadata)
            if (!localStored) {
                return StorageResponse(
                    success = false,
                    fileId = request.fileId,
                    replicatedNodes = emptySet(),
                    error = StorageError.DiskIOError(localStoragePath.toString(), "Failed to write file")
                )
            }
            
            // Replicate to other nodes
            val replicatedNodes = replicateToNodes(request, metadata)
            
            // Update tracking
            storedFiles[request.fileId] = metadata
            replicationMap[request.fileId] = replicatedNodes + "LOCAL"
            currentStorageUsedBytes += request.data.size.toLong()
            
            StorageResponse(
                success = true,
                fileId = request.fileId,
                replicatedNodes = replicatedNodes + "LOCAL"
            )
            
        } catch (e: Exception) {
            StorageResponse(
                success = false,
                fileId = request.fileId,
                replicatedNodes = emptySet(),
                error = StorageError.DiskIOError("storage", e.message ?: "Unknown error")
            )
        }
    }
    
    /**
     * Retrieve a file from distributed storage or mesh network
     * Used for downloading shared files or accessing replicated content
     */
    suspend fun retrieveFile(request: RetrievalRequest): RetrievalResponse {
        return try {
            // Check if file exists in distributed storage
            val localMetadata = storedFiles[request.fileId]
            if (localMetadata != null) {
                val localData = retrieveFileFromDistributedStorage(request.fileId)
                if (localData != null) {
                    // Update access tracking
                    updateAccessStats(request.fileId)
                    
                    return RetrievalResponse(
                        success = true,
                        fileId = request.fileId,
                        data = localData,
                        sourceNode = "DISTRIBUTED_STORAGE",
                        metadata = localMetadata
                    )
                }
            }
            
            // Try to retrieve from replicated nodes
            val replicatedNodes = replicationMap[request.fileId] ?: emptySet()
            
            for (nodeId in replicatedNodes) {
                if (nodeId == "LOCAL") continue
                
                try {
                    val remoteResponse = retrieveFromNode(nodeId, request)
                    if (remoteResponse.success && remoteResponse.data != null) {
                        // Verify checksum if metadata available
                        if (remoteResponse.metadata != null) {
                            val checksum = calculateMD5(remoteResponse.data)
                            if (checksum != remoteResponse.metadata.checksumMD5) {
                                continue // Try next node
                            }
                        }
                        
                        return remoteResponse
                    }
                } catch (e: Exception) {
                    // Continue to next node
                    continue
                }
            }
            
            RetrievalResponse(
                success = false,
                fileId = request.fileId,
                data = null,
                error = StorageError.InvalidFileId(request.fileId)
            )
            
        } catch (e: Exception) {
            RetrievalResponse(
                success = false,
                fileId = request.fileId,
                data = null,
                error = StorageError.DiskIOError("retrieval", e.message ?: "Unknown error")
            )
        }
    }
    
    suspend fun deleteFile(fileId: String): Boolean {
        return try {
            // Delete from distributed storage
            val localDeleted = deleteFileFromDistributedStorage(fileId)
            
            // Delete from replicated nodes
            val replicatedNodes = replicationMap[fileId] ?: emptySet()
            
            for (nodeId in replicatedNodes) {
                if (nodeId == "LOCAL") continue
                
                try {
                    deleteFromNode(nodeId, fileId)
                } catch (e: Exception) {
                    // Continue with other nodes
                }
            }
            
            // Update tracking
            val metadata = storedFiles.remove(fileId)
            replicationMap.remove(fileId)
            
            if (metadata != null) {
                currentStorageUsedBytes -= metadata.sizeBytes
            }
            
            localDeleted
            
        } catch (e: Exception) {
            false
        }
    }
    
    suspend fun verifyFileIntegrity(fileId: String): Boolean {
        return try {
            val metadata = storedFiles[fileId] ?: return false
            val data = retrieveFileFromDistributedStorage(fileId) ?: return false
            
            val currentChecksum = calculateMD5(data)
            currentChecksum == metadata.checksumMD5
            
        } catch (e: Exception) {
            false
        }
    }
    
    // === MESH INTEGRATION ===
    
    suspend fun handleStorageRequest(
        nodeId: String,
        request: StorageRequest
    ): StorageResponse {
        // Implement rate limiting and authorization here
        if (!isAuthorizedNode(nodeId)) {
            return StorageResponse(
                success = false,
                fileId = request.fileId,
                replicatedNodes = emptySet(),
                error = StorageError.UntrustedSource(nodeId)
            )
        }
        
        return storeFileFromOtherNode(request)
    }
    
    suspend fun handleRetrievalRequest(
        nodeId: String,
        request: RetrievalRequest
    ): RetrievalResponse {
        if (!isAuthorizedNode(nodeId)) {
            return RetrievalResponse(
                success = false,
                fileId = request.fileId,
                data = null,
                error = StorageError.UntrustedSource(nodeId)
            )
        }
        
        return retrieveFile(request)
    }
    
    // === STORAGE MANAGEMENT ===
    
    fun getStorageStats(): StorageStats {
        return StorageStats(
            totalCapacityGB = maxStorageGB,
            usedCapacityGB = currentStorageUsedBytes / (1024f * 1024f * 1024f),
            availableCapacityGB = maxStorageGB - (currentStorageUsedBytes / (1024f * 1024f * 1024f)),
            distributedFileCount = storedFiles.size, // Files from other nodes
            sharedFileCount = sharedWithMeFiles.size, // Files shared with this user
            downloadedSharedFileCount = sharedWithMeFiles.values.count { it.isDownloaded },
            totalAccessCount = storedFiles.values.sumOf { it.accessCount },
            averageFileSize = if (storedFiles.isNotEmpty()) currentStorageUsedBytes / storedFiles.size else 0L
        )
    }

    /**
     * Check whether this agent has a file with the given id (either stored from other nodes
     * or present in the 'shared with me' list).
     */
    fun hasFile(fileId: String): Boolean {
        return try {
            storedFiles.containsKey(fileId) || sharedWithMeFiles.containsKey(fileId)
        } catch (e: Exception) {
            false
        }
    }
    
    suspend fun performMaintenance() {
        // Cleanup old or rarely accessed files
        cleanupOldFiles()
        
        // Verify file integrity
        verifyStoredFiles()
        
        // Rebalance replication if needed
        rebalanceReplication()
    }
    
    // === PRIVATE METHODS ===
    
    private fun hasCapacityForFile(fileSizeBytes: Long): Boolean {
        val maxBytes = (maxStorageGB * 1024 * 1024 * 1024).toLong()
        return (currentStorageUsedBytes + fileSizeBytes) <= maxBytes
    }
    
    private fun calculateMD5(data: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
    
    // === COROUTINE-BASED FILE I/O OPERATIONS ===
    
    private suspend fun storeFileInDistributedStorage(
        fileId: String,
        data: ByteArray,
        metadata: FileMetadata
    ): Boolean = withContext(ioDispatcher) {
        try {
            // TODO: Implement actual distributed storage area file system storage
            // This is separate from Drop Folder - stores files FROM other nodes
            // Path would be: localStoragePath/fileId or similar
            // File(localStoragePath.resolve(fileId).toString()).writeBytes(data)
            // For tests, store into in-memory map so retrieval tests can read it
            testStoredData[fileId] = data
            delay(10) // Simulate I/O operation
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun retrieveFileFromDistributedStorage(fileId: String): ByteArray? = withContext(ioDispatcher) {
        try {
            // For tests, read from in-memory map if present
            delay(10) // Simulate I/O operation
            testStoredData[fileId]
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun deleteFileFromDistributedStorage(fileId: String): Boolean = withContext(ioDispatcher) {
        try {
            // For tests, remove from in-memory map
            testStoredData.remove(fileId)
            delay(5) // Simulate I/O operation
            true
        } catch (e: Exception) {
            false
        }
    }

    // --- Test helpers ---
    /**
     * Add a stored file and its data into the agent for unit tests. This avoids touching the real filesystem.
     */
    internal fun addStoredFileForTest(fileId: String, data: ByteArray, metadata: FileMetadata) {
        storedFiles[fileId] = metadata
        testStoredData[fileId] = data
        currentStorageUsedBytes += data.size.toLong()
    }

    /**
     * Set replication map entries for a file (used in retrieval/delete tests).
     */
    internal fun setReplicationMapForTest(fileId: String, nodes: Set<String>) {
        replicationMap[fileId] = nodes
    }
    
    private suspend fun readFileFromDropFolder(filePath: Path): ByteArray? = withContext(ioDispatcher) {
        try {
            // TODO: Implement reading from Drop Folder
            // This reads the actual user files from Drop Folder for replication
            // File(filePath.toString()).readBytes()
            delay(20) // Simulate I/O operation
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun saveFileToSharedWithMe(filePath: Path, data: ByteArray): Boolean = withContext(ioDispatcher) {
        try {
            // TODO: Implement actual file writing with proper error handling
            // File(filePath.toString()).writeBytes(data)
            delay(15) // Simulate I/O operation
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun deleteLocalFile(filePath: Path): Boolean = withContext(ioDispatcher) {
        try {
            // TODO: Implement actual file deletion
            // File(filePath.toString()).delete()
            delay(5) // Simulate I/O operation
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun replicateToSpecificNode(
        nodeId: String,
        request: StorageRequest
    ): StorageResponse {
        return try {
            // Convert to Meshrabiya transport type and send via MeshNetworkInterface if available
            val dfInfo = request.toFileTransportDTO()
            // meshNetwork is required to be a Meshrabiya MeshNetworkInterface (constructor enforces it)
            meshNetwork.sendStorageRequest(nodeId, dfInfo, com.ustadmobile.meshrabiya.vnet.StorageOperation.REPLICATE)
            StorageResponse(
                success = true,
                fileId = request.fileId,
                replicatedNodes = setOf(nodeId)
            )
        } catch (e: Exception) {
            StorageResponse(
                success = false,
                fileId = request.fileId,
                replicatedNodes = emptySet(),
                error = StorageError.PeerUnreachable(nodeId)
            )
        }
    }

    // Exposed for tests to exercise the replication logic without depending on
    // readFileFromDropFolder implementation.
    internal suspend fun replicateToSpecificNodeForTest(
        nodeId: String,
        request: StorageRequest
    ): StorageResponse {
        return replicateToSpecificNode(nodeId, request)
    }
    
    private suspend fun replicateToNodes(
        request: StorageRequest,
        metadata: FileMetadata
    ): Set<String> {
        val replicatedNodes = mutableSetOf<String>()
        
        try {
            // Ask the meshrabiya mesh for candidate nodes and attempt replication
            val candidates = meshNetwork.getAvailableStorageNodes()
            val toAttempt = candidates.take(request.replicationFactor - 1)
            for (node in toAttempt) {
                val resp = replicateToSpecificNode(node, request)
                if (resp.success) replicatedNodes.add(node)
            }
        } catch (e: Exception) {
            // Log error
        }
        
        return replicatedNodes
    }
    
    private suspend fun retrieveFromNode(
        nodeId: String,
        request: RetrievalRequest
    ): RetrievalResponse {
        return try {
            val data = meshNetwork.requestFileFromNode(nodeId, request.fileId)
            if (data != null) {
                RetrievalResponse(
                    success = true,
                    fileId = request.fileId,
                    data = data,
                    sourceNode = nodeId,
                    metadata = storedFiles[request.fileId]
                )
            } else {
                RetrievalResponse(
                    success = false,
                    fileId = request.fileId,
                    data = null,
                    error = StorageError.PeerUnreachable(nodeId)
                )
            }
        } catch (e: Exception) {
            RetrievalResponse(
                success = false,
                fileId = request.fileId,
                data = null,
                error = StorageError.PeerUnreachable(nodeId)
            )
        }
    }
    
    private suspend fun deleteFromNode(nodeId: String, fileId: String): Boolean {
        return try {
            val df = com.ustadmobile.meshrabiya.storage.DistributedStorageManager.FileTransportDTO(
                path = fileId,
                fileId = fileId,
                replicationLevel = com.ustadmobile.meshrabiya.vnet.ReplicationLevel.MINIMAL,
                priority = com.ustadmobile.meshrabiya.vnet.SyncPriority.NORMAL,
                createdAt = System.currentTimeMillis(),
                lastAccessed = 0L,
                meshReferences = emptyList(),
                checksum = ""
            )
            meshNetwork.sendStorageRequest(nodeId, df, com.ustadmobile.meshrabiya.storage.StorageOperation.DELETE)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun isAuthorizedNode(nodeId: String): Boolean {
        // TODO: Implement proper authorization
        return true
    }
    
    private fun updateAccessStats(fileId: String) {
        storedFiles[fileId]?.let { metadata ->
            storedFiles[fileId] = metadata.copy(
                accessCount = metadata.accessCount + 1,
                lastAccessTimestamp = System.currentTimeMillis()
            )
        }
    }
    
    private suspend fun cleanupOldFiles() {
        val cutoffTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L) // 7 days
        
        val filesToDelete = storedFiles.filter { (_, metadata) ->
            metadata.lastAccessTimestamp < cutoffTime && metadata.accessCount < 5
        }
        
        for ((fileId, _) in filesToDelete) {
            deleteFile(fileId)
        }
    }
    
    private suspend fun verifyStoredFiles() {
        for ((fileId, _) in storedFiles) {
            if (!verifyFileIntegrity(fileId)) {
                // Mark for re-replication or deletion
                // TODO: Implement integrity recovery
            }
        }
    }
    
    private suspend fun rebalanceReplication() {
        // TODO: Implement replication rebalancing
        // Check if replication factor is maintained for all files
    }
    
    // === SHARED WITH ME HELPER METHODS ===
    
    private fun ensureSharedWithMeFolderExists() {
        try {
            val sharedWithMePath = dropFolderPath.resolve("SharedWithMe")
            if (!java.io.File(sharedWithMePath.toString()).exists()) {
                java.io.File(sharedWithMePath.toString()).mkdirs()
            }
        } catch (e: Exception) {
            // Log error but don't throw - app should continue to function
        }
    }
    
    data class StorageStats(
        val totalCapacityGB: Float,
        val usedCapacityGB: Float,
        val availableCapacityGB: Float,
        val distributedFileCount: Int, // Files from other nodes stored in distributed storage
        val sharedFileCount: Int, // Files shared with this user (total)
        val downloadedSharedFileCount: Int, // Files shared with this user that are downloaded
        val totalAccessCount: Long,
        val averageFileSize: Long
    )
}
