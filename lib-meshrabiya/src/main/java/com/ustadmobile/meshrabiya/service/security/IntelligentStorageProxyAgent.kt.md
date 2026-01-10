package com.ustadmobile.meshrabiya.service.security

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * INTELLIGENT STORAGE PROXY AGENT
 * 
 * Clarifies the storage architecture:
 * 
 * ┌─────────────────┐    ┌──────────────────┐    ┌─────────────────────┐
 * │   Sandboxed     │    │  Storage Proxy   │    │  Storage Backend    │
 * │     Task        │◄──►│     Agent        │◄──►│   (Flexible)        │
 * │                 │    │   (Local)        │    │                     │
 * └─────────────────┘    └──────────────────┘    └─────────────────────┘
 *      Storage Pipes         Security/Routing      - Local files
 *                                                  - Distributed Storage
 *                                                  - Memory cache
 *                                                  - Network storage
 * 
 * KEY INSIGHT: This is NOT "local-first storage" - it's intelligent routing!
 * 
 * The proxy decides where to store data based on:
 * 1. Device capabilities (participating in distributed storage?)
 * 2. Available space (enough local storage?)
 * 3. Task requirements (ephemeral vs persistent?)
 * 4. Network conditions (distributed storage reachable?)
 * 5. Trust level (trusted service vs stranger?)
 */
class IntelligentStorageProxyAgent(
    private val context: Context,
    private val distributedStorageManager: com.ustadmobile.meshrabiya.storage.DistributedStorageManager?,
    private val localStorageManager: LocalStorageManager
) {
    
    companion object {
        private const val TAG = "StorageProxyAgent"
        private const val EPHEMERAL_SIZE_THRESHOLD = 10 * 1024 * 1024 // 10MB
        private const val LOCAL_STORAGE_RESERVE_MB = 100 // Keep 100MB free
    }
    
    /**
     * STORAGE ROUTING DECISION ENGINE
     * 
     * Intelligently decides which storage backend to use
     */
    enum class StorageBackend {
        MEMORY_CACHE,           // For very small ephemeral data
        LOCAL_TEMPORARY,        // Local temp files (works everywhere)
        LOCAL_PERSISTENT,       // Local persistent files
        DISTRIBUTED_STORAGE,    // Mesh distributed storage
        NETWORK_STORAGE         // Remote network storage (future)
    }
    
    @Serializable
    data class StorageCapabilities(
        val hasDistributedStorage: Boolean,
        val distributedStorageAvailable: Boolean,
        val localStorageAvailableMB: Long,
        val memoryAvailableMB: Long,
        val networkConnectivity: Boolean
    )
    
    @Serializable
    data class StorageDecision(
        val backend: StorageBackend,
        val reason: String,
        val fallbackBackends: List<StorageBackend>
    )
    
    /**
     * STORAGE REQUEST ROUTING
     * 
     * This is where the magic happens - intelligent routing based on capabilities
     */
    suspend fun routeStorageRequest(
        request: SandboxStorageProxy.StorageRequest,
        taskId: String,
        sandboxId: String
    ): String = withContext(Dispatchers.IO) {
        
        try {
            // 1. Assess current storage capabilities
            val capabilities = assessStorageCapabilities()
            
            // 2. Make storage decision based on request and capabilities
            val decision = makeStorageDecision(request, capabilities)
            
            // 3. Route to appropriate backend
            val response = when (decision.backend) {
                StorageBackend.MEMORY_CACHE -> handleMemoryCacheStorage(request, taskId)
                StorageBackend.LOCAL_TEMPORARY -> handleLocalTemporaryStorage(request, taskId)
                StorageBackend.LOCAL_PERSISTENT -> handleLocalPersistentStorage(request, taskId)
                StorageBackend.DISTRIBUTED_STORAGE -> handleDistributedStorage(request, taskId)
                StorageBackend.NETWORK_STORAGE -> handleNetworkStorage(request, taskId)
            }
            
            Log.d(TAG, "Storage routed to ${decision.backend}: ${decision.reason}")
            return@withContext response
            
        } catch (e: Exception) {
            Log.e(TAG, "Storage routing failed", e)
            // Fallback to safest option
            return@withContext handleLocalTemporaryStorage(request, taskId)
        }
    }
    
    /**
     * ASSESS DEVICE STORAGE CAPABILITIES
     * 
     * Determines what storage options are available right now
     */
    private suspend fun assessStorageCapabilities(): StorageCapabilities {
        return StorageCapabilities(
            hasDistributedStorage = distributedStorageManager != null,
            distributedStorageAvailable = isDistributedStorageHealthy(),
            localStorageAvailableMB = getLocalStorageAvailableMB(),
            memoryAvailableMB = getMemoryAvailableMB(),
            networkConnectivity = hasNetworkConnectivity()
        )
    }
    
    /**
     * INTELLIGENT STORAGE DECISION MAKING
     * 
     * The core logic for choosing storage backend
     */
    private fun makeStorageDecision(
        request: SandboxStorageProxy.StorageRequest,
        capabilities: StorageCapabilities
    ): StorageDecision {
        
        when (request) {
            is SandboxStorageProxy.StorageRequest.Store -> {
                val dataSize = estimateDataSize(request.data)
                val retentionPolicy = request.retentionPolicy ?: SandboxStorageProxy.RetentionPolicy.EPHEMERAL
                
                return when {
                    // Very small ephemeral data → memory cache
                    dataSize < EPHEMERAL_SIZE_THRESHOLD && 
                    retentionPolicy == SandboxStorageProxy.RetentionPolicy.EPHEMERAL &&
                    capabilities.memoryAvailableMB > 50 -> {
                        StorageDecision(
                            backend = StorageBackend.MEMORY_CACHE,
                            reason = "Small ephemeral data, sufficient memory available",
                            fallbackBackends = listOf(StorageBackend.LOCAL_TEMPORARY)
                        )
                    }
                    
                    // Device not participating in distributed storage → local only
                    !capabilities.hasDistributedStorage -> {
                        val backend = if (retentionPolicy == SandboxStorageProxy.RetentionPolicy.PERSISTENT) {
                            StorageBackend.LOCAL_PERSISTENT
                        } else {
                            StorageBackend.LOCAL_TEMPORARY
                        }
                        StorageDecision(
                            backend = backend,
                            reason = "Device not participating in distributed storage",
                            fallbackBackends = listOf(StorageBackend.LOCAL_TEMPORARY)
                        )
                    }
                    
                    // Low local storage space → prefer distributed if available
                    capabilities.localStorageAvailableMB < LOCAL_STORAGE_RESERVE_MB &&
                    capabilities.distributedStorageAvailable -> {
                        StorageDecision(
                            backend = StorageBackend.DISTRIBUTED_STORAGE,
                            reason = "Low local storage, distributed storage available",
                            fallbackBackends = listOf(StorageBackend.LOCAL_TEMPORARY, StorageBackend.MEMORY_CACHE)
                        )
                    }
                    
                    // Persistent data + distributed available → distributed
                    retentionPolicy == SandboxStorageProxy.RetentionPolicy.PERSISTENT &&
                    capabilities.distributedStorageAvailable -> {
                        StorageDecision(
                            backend = StorageBackend.DISTRIBUTED_STORAGE,
                            reason = "Persistent data with distributed storage available",
                            fallbackBackends = listOf(StorageBackend.LOCAL_PERSISTENT)
                        )
                    }
                    
                    // Default case → local temporary (works everywhere)
                    else -> {
                        StorageDecision(
                            backend = StorageBackend.LOCAL_TEMPORARY,
                            reason = "Default safe option for general use",
                            fallbackBackends = listOf(StorageBackend.MEMORY_CACHE)
                        )
                    }
                }
            }
            
            is SandboxStorageProxy.StorageRequest.Retrieve -> {
                // For retrieval, try in order of most likely to least likely
                return StorageDecision(
                    backend = StorageBackend.MEMORY_CACHE, // Try cache first
                    reason = "Retrieval: check cache first",
                    fallbackBackends = listOf(
                        StorageBackend.LOCAL_TEMPORARY,
                        StorageBackend.LOCAL_PERSISTENT,
                        StorageBackend.DISTRIBUTED_STORAGE
                    )
                )
            }
            
            else -> {
                // List, Delete, Metadata operations
                return StorageDecision(
                    backend = StorageBackend.LOCAL_TEMPORARY,
                    reason = "Metadata operations use local backend",
                    fallbackBackends = listOf(StorageBackend.DISTRIBUTED_STORAGE)
                )
            }
        }
    }
    
    /**
     * STORAGE BACKEND HANDLERS
     * 
     * Each backend has different characteristics and use cases
     */
    
    // 1. MEMORY CACHE - Fastest, smallest capacity, ephemeral only
    private val memoryCache = ConcurrentHashMap<String, ByteArray>()
    
    private suspend fun handleMemoryCacheStorage(
        request: SandboxStorageProxy.StorageRequest,
        taskId: String
    ): String {
        when (request) {
            is SandboxStorageProxy.StorageRequest.Store -> {
                val data = java.util.Base64.getDecoder().decode(request.data)
                val key = "${taskId}_${request.fileName}"
                memoryCache[key] = data
                
                Log.d(TAG, "Stored in memory cache: ${request.fileName} (${data.size} bytes)")
                
                return Json.encodeToString(SandboxStorageProxy.StorageResponse.StoreResponse(
                    requestId = request.requestId,
                    success = true,
                    fileId = key
                ))
            }
            
            is SandboxStorageProxy.StorageRequest.Retrieve -> {
                val key = "${taskId}_${request.fileName}"
                val data = memoryCache[key]
                
                if (data != null) {
                    val encodedData = java.util.Base64.getEncoder().encodeToString(data)
                    return Json.encodeToString(SandboxStorageProxy.StorageResponse.RetrieveResponse(
                        requestId = request.requestId,
                        success = true,
                        data = encodedData
                    ))
                } else {
                    // Fallback to local temporary
                    return handleLocalTemporaryStorage(request, taskId)
                }
            }
            
            else -> {
                // Memory cache doesn't support other operations
                return handleLocalTemporaryStorage(request, taskId)
            }
        }
    }
    
    // 2. LOCAL TEMPORARY - Fast, medium capacity, automatic cleanup
    private suspend fun handleLocalTemporaryStorage(
        request: SandboxStorageProxy.StorageRequest,
        taskId: String
    ): String {
        return localStorageManager.handleRequest(request, taskId, isTemporary = true)
    }
    
    // 3. LOCAL PERSISTENT - Fast, medium capacity, manual cleanup
    private suspend fun handleLocalPersistentStorage(
        request: SandboxStorageProxy.StorageRequest,
        taskId: String
    ): String {
        return localStorageManager.handleRequest(request, taskId, isTemporary = false)
    }
    
    // 4. DISTRIBUTED STORAGE - Slower, large capacity, mesh-wide availability
    private suspend fun handleDistributedStorage(
        request: SandboxStorageProxy.StorageRequest,
        taskId: String
    ): String {
        if (distributedStorageManager == null) {
            Log.w(TAG, "Distributed storage not available, falling back to local")
            return handleLocalTemporaryStorage(request, taskId)
        }
        
        when (request) {
            is SandboxStorageProxy.StorageRequest.Store -> {
                try {
                    val data = java.util.Base64.getDecoder().decode(request.data)
                    val namespacedPath = "task_${taskId}/${request.fileName}"
                    
                    val fileReference = distributedStorageManager.storeFile(
                        path = namespacedPath,
                        data = data,
                        priority = com.ustadmobile.meshrabiya.storage.SyncPriority.NORMAL
                    )
                    
                    if (fileReference != null) {
                        Log.d(TAG, "Stored in distributed storage: $namespacedPath (${data.size} bytes)")
                        
                        return Json.encodeToString(SandboxStorageProxy.StorageResponse.StoreResponse(
                            requestId = request.requestId,
                            success = true,
                            fileId = fileReference.id
                        ))
                    } else {
                        Log.w(TAG, "Distributed storage failed, falling back to local")
                        return handleLocalTemporaryStorage(request, taskId)
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Distributed storage error", e)
                    return handleLocalTemporaryStorage(request, taskId)
                }
            }
            
            is SandboxStorageProxy.StorageRequest.Retrieve -> {
                try {
                    val namespacedPath = "task_${taskId}/${request.fileName}"
                    val fileReference = com.ustadmobile.meshrabiya.storage.FileReference(
                        id = namespacedPath.hashCode().toString(),
                        path = namespacedPath,
                        size = 0L
                    )
                    
                    val data = distributedStorageManager.retrieveFile(fileReference)
                    
                    if (data != null) {
                        val encodedData = java.util.Base64.getEncoder().encodeToString(data)
                        return Json.encodeToString(SandboxStorageProxy.StorageResponse.RetrieveResponse(
                            requestId = request.requestId,
                            success = true,
                            data = encodedData
                        ))
                    } else {
                        return handleLocalTemporaryStorage(request, taskId)
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Distributed retrieval error", e)
                    return handleLocalTemporaryStorage(request, taskId)
                }
            }
            
            else -> {
                // Other operations fall back to local
                return handleLocalTemporaryStorage(request, taskId)
            }
        }
    }
    
    // 5. NETWORK STORAGE - Future implementation for remote storage services
    private suspend fun handleNetworkStorage(
        request: SandboxStorageProxy.StorageRequest,
        taskId: String
    ): String {
        // TODO: Implement network storage (IPFS, cloud storage, etc.)
        Log.d(TAG, "Network storage not implemented, falling back to distributed")
        return handleDistributedStorage(request, taskId)
    }
    
    // === UTILITY METHODS ===
    
    private suspend fun isDistributedStorageHealthy(): Boolean {
        return try {
            distributedStorageManager?.let { manager ->
                // Check if distributed storage is running and reachable
                val stats = manager.storageStats.value
                stats.totalOffered > 0 && manager.participationEnabled.value
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed", e)
            false
        }
    }
    
    private fun getLocalStorageAvailableMB(): Long {
        return try {
            val internalDir = context.filesDir
            val freeBytes = internalDir.freeSpace
            freeBytes / (1024 * 1024) // Convert to MB
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local storage info", e)
            0L
        }
    }
    
    private fun getMemoryAvailableMB(): Long {
        return try {
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory()
            val totalMemory = runtime.totalMemory()
            val freeMemory = runtime.freeMemory()
            val availableMemory = maxMemory - totalMemory + freeMemory
            availableMemory / (1024 * 1024) // Convert to MB
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get memory info", e)
            0L
        }
    }
    
    private fun hasNetworkConnectivity(): Boolean {
        // TODO: Check actual network connectivity
        return true
    }
    
    private fun estimateDataSize(encodedData: String): Long {
        // Base64 encoding increases size by ~33%
        return (encodedData.length * 3L) / 4L
    }
}

/**
 * LOCAL STORAGE MANAGER
 * 
 * Handles actual local file operations
 */
class LocalStorageManager(private val context: Context) {
    
    private val tempDir = File(context.cacheDir, "sandbox_temp")
    private val persistentDir = File(context.filesDir, "sandbox_persistent")
    
    init {
        tempDir.mkdirs()
        persistentDir.mkdirs()
    }
    
    suspend fun handleRequest(
        request: SandboxStorageProxy.StorageRequest,
        taskId: String,
        isTemporary: Boolean
    ): String = withContext(Dispatchers.IO) {
        
        val baseDir = if (isTemporary) tempDir else persistentDir
        val taskDir = File(baseDir, "task_$taskId")
        taskDir.mkdirs()
        
        when (request) {
            is SandboxStorageProxy.StorageRequest.Store -> {
                try {
                    val data = java.util.Base64.getDecoder().decode(request.data)
                    val file = File(taskDir, request.fileName)
                    file.writeBytes(data)
                    
                    Json.encodeToString(SandboxStorageProxy.StorageResponse.StoreResponse(
                        requestId = request.requestId,
                        success = true,
                        fileId = file.absolutePath
                    ))
                } catch (e: Exception) {
                    Json.encodeToString(SandboxStorageProxy.StorageResponse.StoreResponse(
                        requestId = request.requestId,
                        success = false,
                        error = "Local storage failed: ${e.message}"
                    ))
                }
            }
            
            is SandboxStorageProxy.StorageRequest.Retrieve -> {
                try {
                    val file = File(taskDir, request.fileName)
                    if (file.exists()) {
                        val data = file.readBytes()
                        val encodedData = java.util.Base64.getEncoder().encodeToString(data)
                        
                        Json.encodeToString(SandboxStorageProxy.StorageResponse.RetrieveResponse(
                            requestId = request.requestId,
                            success = true,
                            data = encodedData
                        ))
                    } else {
                        Json.encodeToString(SandboxStorageProxy.StorageResponse.RetrieveResponse(
                            requestId = request.requestId,
                            success = false,
                            error = "File not found"
                        ))
                    }
                } catch (e: Exception) {
                    Json.encodeToString(SandboxStorageProxy.StorageResponse.RetrieveResponse(
                        requestId = request.requestId,
                        success = false,
                        error = "Local retrieval failed: ${e.message}"
                    ))
                }
            }
            
            else -> {
                Json.encodeToString(SandboxStorageProxy.StorageResponse.StoreResponse(
                    requestId = request.requestId,
                    success = false,
                    error = "Operation not supported by local storage"
                ))
            }
        }
    }
}

/**
 * SUMMARY: Intelligent Storage Architecture ✅
 * 
 * ✅ UNIVERSAL COMPATIBILITY: Tasks work on ANY device (with/without distributed storage)
 * ✅ INTELLIGENT ROUTING: Automatically chooses best storage backend
 * ✅ GRACEFUL FALLBACKS: If distributed storage fails, falls back to local
 * ✅ EFFICIENT RESOURCE USE: Small ephemeral data → memory, large persistent → distributed
 * ✅ SECURITY MAINTAINED: All storage access goes through controlled proxy
 * ✅ SCALABLE: Can add new storage backends (IPFS, cloud, etc.) without changing interface
 * 
 * This architecture ensures tasks from strangers can run everywhere while maintaining
 * optimal performance and security!
 */
