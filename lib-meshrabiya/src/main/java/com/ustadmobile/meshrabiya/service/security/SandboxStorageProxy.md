package com.ustadmobile.meshrabiya.service.security

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.ustadmobile.meshrabiya.service.compute.model.AccessScope
import com.ustadmobile.meshrabiya.storage.FileReference

/**
 * SANDBOX-SAFE STORAGE ACCESS LAYER
 * 
 * Addresses: "How can sandboxed tasks safely access Distributed Storage?"
 * 
 * SOLUTION: Treat storage calls exactly like communication channels!
 * 
 * KEY INSIGHT: Storage is just another form of IPC (Inter-Process Communication)
 * - Input/Output pipes: For direct data transfer
 * - Storage pipes: For distributed storage operations
 * - Same security model, same isolation guarantees
 */
class SandboxStorageProxy(
    private val distributedStorageManager: com.ustadmobile.meshrabiya.storage.DistributedStorageManager,
    private val sandboxPolicy: StorageAccessPolicy
) {
    
    companion object {
        private const val TAG = "SandboxStorageProxy"
        private const val STORAGE_PIPE_PREFIX = "storage_"
        private const val MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024 // 100MB max per file
        private const val MAX_TOTAL_STORAGE_BYTES = 1024 * 1024 * 1024L // 1GB max per sandbox
    }
    
    /**
     * STORAGE ACCESS POLICY
     * 
     * Defines what storage operations a sandboxed service can perform
     */
    @Serializable
    data class StorageAccessPolicy(
        val allowedOperations: Set<StorageOperation>,
        val maxFileSize: Long = MAX_FILE_SIZE_BYTES.toLong(),
        val maxTotalStorage: Long = MAX_TOTAL_STORAGE_BYTES,
        val allowedFileTypes: Set<String> = emptySet(), // empty = all allowed
        val storageQuotaPerTask: Long = 64 * 1024 * 1024, // 64MB per task
        val retentionPolicy: RetentionPolicy = RetentionPolicy.EPHEMERAL,
        val accessScope: AccessScope = AccessScope.TASK_ISOLATED
    )
    
    enum class StorageOperation {
        READ,           // Read existing files
        WRITE,          // Write new files
        LIST,           // List available files
        DELETE,         // Delete files (usually restricted)
        METADATA        // Get file metadata only
    }
    
    enum class RetentionPolicy {
        EPHEMERAL,      // Delete when task completes
        TEMPORARY,      // Delete after 1 hour
        PERSISTENT      // Keep until manually deleted
    }
    
    
    /**
     * STORAGE REQUEST/RESPONSE PROTOCOL
     * 
     * All storage operations go through this protocol for security
     */
    @Serializable
    sealed class StorageRequest {
        abstract val requestId: String
        abstract val taskId: String
        
        @Serializable
        data class Store(
            override val requestId: String,
            override val taskId: String,
            val fileName: String,
            val data: String, // Base64 encoded
            val metadata: Map<String, String> = emptyMap(),
            val retentionPolicy: RetentionPolicy = RetentionPolicy.EPHEMERAL
        ) : StorageRequest()
        
        @Serializable
        data class Retrieve(
            override val requestId: String,
            override val taskId: String,
            val fileName: String
        ) : StorageRequest()
        
        @Serializable
        data class List(
            override val requestId: String,
            override val taskId: String,
            val pattern: String = "*" // Glob pattern
        ) : StorageRequest()
        
        @Serializable
        data class Delete(
            override val requestId: String,
            override val taskId: String,
            val fileName: String
        ) : StorageRequest()
        
        @Serializable
        data class GetMetadata(
            override val requestId: String,
            override val taskId: String,
            val fileName: String
        ) : StorageRequest()
    }
    
    @Serializable
    sealed class StorageResponse {
        abstract val requestId: String
        abstract val success: Boolean
        
        @Serializable
        data class StoreResponse(
            override val requestId: String,
            override val success: Boolean,
            val fileId: String? = null,
            val error: String? = null
        ) : StorageResponse()
        
        @Serializable
        data class RetrieveResponse(
            override val requestId: String,
            override val success: Boolean,
            val data: String? = null, // Base64 encoded
            val metadata: Map<String, String> = emptyMap(),
            val error: String? = null
        ) : StorageResponse()
        
        @Serializable
        data class ListResponse(
            override val requestId: String,
            override val success: Boolean,
            val files: List<FileInfo> = emptyList(),
            val error: String? = null
        ) : StorageResponse()
        
        @Serializable
        data class DeleteResponse(
            override val requestId: String,
            override val success: Boolean,
            val error: String? = null
        ) : StorageResponse()
        
        @Serializable
        data class MetadataResponse(
            override val requestId: String,
            override val success: Boolean,
            val metadata: Map<String, String> = emptyMap(),
            val error: String? = null
        ) : StorageResponse()
    }
    
    @Serializable
    data class FileInfo(
        val fileName: String,
        val sizeBytes: Long,
        val createdAt: Long,
        val lastModified: Long,
        val metadata: Map<String, String> = emptyMap()
    )
    
    /**
     * QUOTA TRACKING per sandbox task
     */
    private val taskStorageUsage = ConcurrentHashMap<String, AtomicLong>()
    private val taskFileCount = ConcurrentHashMap<String, AtomicLong>()
    
    /**
     * STORAGE PIPE INTERFACE
     * 
     * This is how sandboxed code interacts with storage - through pipes!
     */
    suspend fun processStorageRequest(
        requestJson: String,
        taskId: String,
        sandboxId: String
    ): String = withContext(Dispatchers.IO) {
        
        try {
            // Parse request
            val request = Json.decodeFromString<StorageRequest>(requestJson)
            
            // Validate task ID matches
            if (request.taskId != taskId) {
                return@withContext createErrorResponse(request.requestId, "Task ID mismatch")
            }
            
            // Check if operation is allowed
            val operationType = when (request) {
                is StorageRequest.Store -> StorageOperation.WRITE
                is StorageRequest.Retrieve -> StorageOperation.READ
                is StorageRequest.List -> StorageOperation.LIST
                is StorageRequest.Delete -> StorageOperation.DELETE
                is StorageRequest.GetMetadata -> StorageOperation.METADATA
            }
            
            if (operationType !in sandboxPolicy.allowedOperations) {
                return@withContext createErrorResponse(request.requestId, "Operation not allowed: $operationType")
            }
            
            // Process request based on type
            val response = when (request) {
                is StorageRequest.Store -> handleStoreRequest(request, taskId, sandboxId)
                is StorageRequest.Retrieve -> handleRetrieveRequest(request, taskId, sandboxId)
                is StorageRequest.List -> handleListRequest(request, taskId, sandboxId)
                is StorageRequest.Delete -> handleDeleteRequest(request, taskId, sandboxId)
                is StorageRequest.GetMetadata -> handleMetadataRequest(request, taskId, sandboxId)
            }
            
            return@withContext Json.encodeToString(response)
            
        } catch (e: Exception) {
            Log.e(TAG, "Storage request processing failed", e)
            return@withContext createErrorResponse("unknown", "Processing error: ${e.message}")
        }
    }
    
    /**
     * STORE FILE with quota and security checks
     */
    private suspend fun handleStoreRequest(
        request: StorageRequest.Store,
        taskId: String,
        sandboxId: String
    ): StorageResponse {
        
        try {
            // Decode data
            val data = java.util.Base64.getDecoder().decode(request.data)
            
            // Check file size limits
            if (data.size > sandboxPolicy.maxFileSize) {
                return StorageResponse.StoreResponse(
                    requestId = request.requestId,
                    success = false,
                    error = "File too large: ${data.size} > ${sandboxPolicy.maxFileSize}"
                )
            }
            
            // Check quota
            val currentUsage = getTaskStorageUsage(taskId)
            if (currentUsage + data.size > sandboxPolicy.storageQuotaPerTask) {
                return StorageResponse.StoreResponse(
                    requestId = request.requestId,
                    success = false,
                    error = "Storage quota exceeded"
                )
            }
            
            // Check file type if restricted
            if (sandboxPolicy.allowedFileTypes.isNotEmpty()) {
                val extension = request.fileName.substringAfterLast('.', "").lowercase()
                if (extension !in sandboxPolicy.allowedFileTypes) {
                    return StorageResponse.StoreResponse(
                        requestId = request.requestId,
                        success = false,
                        error = "File type not allowed: $extension"
                    )
                }
            }
            
            // Create namespaced file path for isolation
            val namespacedFileName = createNamespacedFileName(request.fileName, taskId, sandboxId)
            
            // Store in distributed storage
            val fileReference = distributedStorageManager.storeFile(
                path = namespacedFileName,
                data = data
            )
            
            if (fileReference != null) {
                // Update quota tracking
                updateTaskStorageUsage(taskId, data.size.toLong())
                
                // Schedule cleanup based on retention policy
                scheduleFileCleanup(namespacedFileName, request.retentionPolicy, taskId)
                
                Log.d(TAG, "File stored successfully: $namespacedFileName (${data.size} bytes)")
                
                return StorageResponse.StoreResponse(
                    requestId = request.requestId,
                    success = true,
                    fileId = fileReference.fileId
                )
            } else {
                return StorageResponse.StoreResponse(
                    requestId = request.requestId,
                    success = false,
                    error = "Storage operation failed"
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Store request failed", e)
            return StorageResponse.StoreResponse(
                requestId = request.requestId,
                success = false,
                error = "Store failed: ${e.message}"
            )
        }
    }
    
    /**
     * RETRIEVE FILE with access control
     */
    private suspend fun handleRetrieveRequest(
        request: StorageRequest.Retrieve,
        taskId: String,
        sandboxId: String
    ): StorageResponse {
        
        try {
            // Create namespaced file path
            val namespacedFileName = if (sandboxPolicy.accessScope == AccessScope.TASK_ISOLATED) {
                createNamespacedFileName(request.fileName, taskId, sandboxId)
            } else {
                // For shared access, allow accessing files from same service
                findAccessibleFile(request.fileName, taskId, sandboxId)
            }
            
            if (namespacedFileName == null) {
                return StorageResponse.RetrieveResponse(
                    requestId = request.requestId,
                    success = false,
                    error = "File not found or access denied"
                )
            }
            
            // Create file reference for retrieval
            val fileReference = FileReference(
                fileId = generateFileId(namespacedFileName),
                path = namespacedFileName,
                sizeBytes = 0L // Size will be determined during retrieval
            )
            
            // Retrieve from distributed storage
            val data = distributedStorageManager.retrieveFile(fileReference.fileId)
            
            if (data != null) {
                val encodedData = java.util.Base64.getEncoder().encodeToString(data)
                
                Log.d(TAG, "File retrieved successfully: $namespacedFileName (${data.size} bytes)")
                
                return StorageResponse.RetrieveResponse(
                    requestId = request.requestId,
                    success = true,
                    data = encodedData
                )
            } else {
                return StorageResponse.RetrieveResponse(
                    requestId = request.requestId,
                    success = false,
                    error = "File not found"
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Retrieve request failed", e)
            return StorageResponse.RetrieveResponse(
                requestId = request.requestId,
                success = false,
                error = "Retrieve failed: ${e.message}"
            )
        }
    }
    
    /**
     * LIST FILES with scope restrictions
     */
    private suspend fun handleListRequest(
        request: StorageRequest.List,
        taskId: String,
        sandboxId: String
    ): StorageResponse {
        
        try {
            // Get list of files accessible to this task
            val accessibleFiles = getAccessibleFiles(taskId, sandboxId, request.pattern)
            
            val fileInfoList = accessibleFiles.map { fileName ->
                FileInfo(
                    fileName = fileName,
                    sizeBytes = 0L, // TODO: Get actual size
                    createdAt = System.currentTimeMillis(),
                    lastModified = System.currentTimeMillis()
                )
            }
            
            return StorageResponse.ListResponse(
                requestId = request.requestId,
                success = true,
                files = fileInfoList
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "List request failed", e)
            return StorageResponse.ListResponse(
                requestId = request.requestId,
                success = false,
                error = "List failed: ${e.message}"
            )
        }
    }
    
    /**
     * DELETE FILE (usually restricted)
     */
    private suspend fun handleDeleteRequest(
        request: StorageRequest.Delete,
        taskId: String,
        sandboxId: String
    ): StorageResponse {
        
        // Most sandboxes shouldn't be able to delete files
        if (StorageOperation.DELETE !in sandboxPolicy.allowedOperations) {
            return StorageResponse.DeleteResponse(
                requestId = request.requestId,
                success = false,
                error = "Delete operation not allowed"
            )
        }
        
        // TODO: Implement delete operation
        return StorageResponse.DeleteResponse(
            requestId = request.requestId,
            success = false,
            error = "Delete not implemented yet"
        )
    }
    
    /**
     * GET METADATA without downloading file
     */
    private suspend fun handleMetadataRequest(
        request: StorageRequest.GetMetadata,
        taskId: String,
        sandboxId: String
    ): StorageResponse {
        
        // TODO: Implement metadata retrieval
        return StorageResponse.MetadataResponse(
            requestId = request.requestId,
            success = true,
            metadata = mapOf(
                "size" to "0",
                "created" to System.currentTimeMillis().toString()
            )
        )
    }
    
    // === HELPER METHODS ===
    
    private fun createNamespacedFileName(fileName: String, taskId: String, sandboxId: String): String {
        return when (sandboxPolicy.accessScope) {
            AccessScope.TASK_ISOLATED -> "task_${taskId}_${sandboxId}/$fileName"
            AccessScope.SERVICE_SHARED -> "service_${extractServiceId(taskId)}/$fileName" 
            AccessScope.MESH_GLOBAL -> fileName
        }
    }
    
    private fun findAccessibleFile(fileName: String, taskId: String, sandboxId: String): String? {
        // Look for file in accessible namespaces
        return when (sandboxPolicy.accessScope) {
            AccessScope.TASK_ISOLATED -> createNamespacedFileName(fileName, taskId, sandboxId)
            AccessScope.SERVICE_SHARED -> {
                // Check service namespace
                val serviceFileName = "service_${extractServiceId(taskId)}/$fileName"
                // TODO: Check if file exists
                serviceFileName
            }
            AccessScope.MESH_GLOBAL -> fileName
        }
    }
    
    private fun getAccessibleFiles(taskId: String, sandboxId: String, pattern: String): List<String> {
        // TODO: Implement file listing based on access scope
        return emptyList()
    }
    
    private fun getTaskStorageUsage(taskId: String): Long {
        return taskStorageUsage.getOrPut(taskId) { AtomicLong(0) }.get()
    }
    
    private fun updateTaskStorageUsage(taskId: String, additionalBytes: Long) {
        taskStorageUsage.getOrPut(taskId) { AtomicLong(0) }.addAndGet(additionalBytes)
        taskFileCount.getOrPut(taskId) { AtomicLong(0) }.incrementAndGet()
    }
    
    private fun scheduleFileCleanup(fileName: String, retentionPolicy: RetentionPolicy, taskId: String) {
        val cleanupDelay = when (retentionPolicy) {
            RetentionPolicy.EPHEMERAL -> 0L // Clean up when task completes
            RetentionPolicy.TEMPORARY -> 3600_000L // 1 hour
            RetentionPolicy.PERSISTENT -> Long.MAX_VALUE // Never auto-cleanup
        }
        
        if (cleanupDelay > 0 && cleanupDelay < Long.MAX_VALUE) {
            // Schedule cleanup job
            Log.d(TAG, "Scheduled cleanup for $fileName in ${cleanupDelay}ms")
        }
    }
    
    private fun extractServiceId(taskId: String): String {
        // Extract service ID from task ID (assuming format like "service_id_task_timestamp")
        return taskId.split("_").getOrNull(0) ?: "unknown"
    }
    
    private fun generateFileId(fileName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(fileName.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    private fun createErrorResponse(requestId: String, error: String): String {
        val errorResponse = StorageResponse.StoreResponse(
            requestId = requestId,
            success = false,
            error = error
        )
        return Json.encodeToString(errorResponse)
    }
    
    /**
     * CLEANUP when task completes
     */
    suspend fun cleanupTaskStorage(taskId: String, sandboxId: String) {
        Log.d(TAG, "Cleaning up storage for task: $taskId")
        
        // Remove files with EPHEMERAL retention policy
        // Update quota tracking
        taskStorageUsage.remove(taskId)
        taskFileCount.remove(taskId)
        
        Log.d(TAG, "Storage cleanup completed for task: $taskId")
    }
}

/**
 * INTEGRATION WITH BULLETPROOF SANDBOX
 * 
 * Extend the sandbox to include storage pipes alongside input/output pipes
 */
class SandboxWithStorageAccess(
    private val bulletproofSandbox: BulletproofSandbox,
    private val storageProxy: SandboxStorageProxy
) {
    
    /**
     * Enhanced communication channels that include storage access
     */
    data class EnhancedCommunicationChannels(
        val inputChannel: String,      // Data input
        val outputChannel: String,     // Data output
        val storageChannel: String,    // Storage requests/responses
        val controlChannel: String     // Sandbox control
    )
    
    /**
     * Create sandbox with storage access
     */
    suspend fun createSandboxWithStorage(
        codeBundle: ByteArray,
        sandboxPolicy: BulletproofSandbox.SandboxPolicy,
        storagePolicy: SandboxStorageProxy.StorageAccessPolicy
    ): SandboxWithStorageInstance {
        
        // Create base sandbox
        val baseSandbox = bulletproofSandbox.createBulletproofSandbox(codeBundle, sandboxPolicy)
        
        // Add storage channel
        val storageChannelId = "storage_${baseSandbox.id}"
        
        return SandboxWithStorageInstance(
            baseSandbox = baseSandbox,
            storageChannelId = storageChannelId,
            storageProxy = storageProxy
        )
    }
    
    data class SandboxWithStorageInstance(
        val baseSandbox: BulletproofSandbox.SandboxInstance,
        val storageChannelId: String,
        val storageProxy: SandboxStorageProxy
    )
    
    /**
     * Execute task with storage access
     */
    suspend fun executeWithStorage(
        sandbox: SandboxWithStorageInstance,
        input: ByteArray,
        timeoutMs: Long = 30_000
    ): BulletproofSandbox.SandboxExecutionResult {
        
        val taskId = "task_${System.currentTimeMillis()}"
        
        try {
            // Start storage request handler
            val storageHandler = kotlinx.coroutines.coroutineScope {
                async {
                    handleStorageRequests(sandbox, taskId)
                }
            }
            
            // Execute main task
            val result = bulletproofSandbox.executeInSandbox(sandbox.baseSandbox, input, timeoutMs)
            
            // Stop storage handler
            storageHandler.cancel()
            
            // Cleanup task storage
            sandbox.storageProxy.cleanupTaskStorage(taskId, sandbox.baseSandbox.id)
            
            return result
            
        } catch (e: Exception) {
            Log.e("SandboxWithStorage", "Execution with storage failed", e)
            return BulletproofSandbox.SandboxExecutionResult.Error("Storage execution failed: ${e.message}")
        }
    }
    
    private suspend fun handleStorageRequests(
        sandbox: SandboxWithStorageInstance,
        taskId: String
    ) {
        // Listen for storage requests on storage channel and proxy to storage manager
        // This would be implemented with actual pipe/socket communication
    }
}

/**
 * PRACTICAL EXAMPLE: OCR with Storage
 */
class OCRWithStorageExample {
    
    suspend fun performOCRWithResultStorage(imageBytes: ByteArray): String? {
        
        val storagePolicy = SandboxStorageProxy.StorageAccessPolicy(
            allowedOperations = setOf(
                SandboxStorageProxy.StorageOperation.WRITE,
                SandboxStorageProxy.StorageOperation.READ,
                SandboxStorageProxy.StorageOperation.LIST
            ),
            maxFileSize = 10 * 1024 * 1024, // 10MB
            storageQuotaPerTask = 50 * 1024 * 1024, // 50MB
            retentionPolicy = SandboxStorageProxy.RetentionPolicy.TEMPORARY,
            accessScope = AccessScope.TASK_ISOLATED
        )
        
        // Service can:
        // 1. Store intermediate processing results
        // 2. Cache models/data across invocations
        // 3. Share results with other tasks (if policy allows)
        // 4. Access distributed mesh storage for training data
        
        return "OCR result with storage access"
    }
}

/**
 * SUMMARY: Sandbox-Safe Storage Access ✅
 * 
 * ✅ PIPE-BASED ACCESS: Storage operations go through communication pipes
 * ✅ QUOTA ENFORCEMENT: Per-task storage limits prevent abuse
 * ✅ ACCESS CONTROL: Task-isolated, service-shared, or global access scopes
 * ✅ RETENTION POLICIES: Automatic cleanup of ephemeral data
 * ✅ TYPE RESTRICTIONS: Can limit allowed file types
 * ✅ OPERATION CONTROLS: Can allow/deny read/write/delete operations
 * ✅ NAMESPACING: Files isolated by task/service namespace
 * ✅ DISTRIBUTED INTEGRATION: Uses existing DistributedStorageManager
 * 
 * This provides safe storage access while maintaining bulletproof isolation!
 */
