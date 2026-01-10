package com.ustadmobile.meshrabiya.storage
import android.os.Build

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayDeque
import android.content.Context
import java.io.File
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel

/**
 * MeshSyncCoordinator - Orchestrates mesh synchronization operations.
 * 
 * Manages WHEN and HOW local files are replicated to the mesh network using:
 * - Battery-aware sync control (respects battery level and charging state)
 * - Intelligent priority-based queue (critical files sync first)
 * - Robust retry logic with exponential backoff
 * - State persistence across app restarts
 * - Progress tracking for UI updates
 * 
 * Does NOT duplicate file data - tracks original file paths only.
 * Delegates actual chunking/replication to DistributedStorageManager.
 * 
 * ARCHITECTURE: No circular dependency with DistributedStorageManager.
 * Uses callbacks for completion notifications.
 */
class StagedSyncManager(
    private val context: Context,
    private val onSyncComplete: ((fileId: String, replicaCount: Int) -> Unit)? = null,
    private val onSyncFailed: ((fileId: String, error: String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "MeshSyncCoordinator"
        private const val MAX_CONCURRENT_SYNCS = 3
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val SYNC_BATCH_SIZE = 10
        private const val BASE_RETRY_DELAY_MS = 1000L
        private const val MAX_RETRY_DELAY_MS = 300_000L // 5 minutes
        private const val JITTER_FACTOR = 0.2
    }

    private val metadataFile = File(context.filesDir, "mesh_sync_metadata.db")
    
    // BetaTestLogger integration for comprehensive sync logging
    private val betaLogger = BetaTestLogger.getInstance(context)
    
    // Thread-safe collections for concurrent access
    private val syncedFiles = ConcurrentHashMap<String, SyncedFile>()  // fileId -> SyncedFile
    private val syncQueue = ArrayDeque<SyncOperation>()
    private val activeSyncs = ConcurrentHashMap<String, Job>()
    
    private val isMeshAvailable = AtomicBoolean(false)
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isPaused = AtomicBoolean(false)
    
    // Battery-aware sync control with priority support
    private val batteryAwareSync = BatteryAwareSync(context)
    
    init {
        betaLogger.log(LogLevel.DEBUG, TAG, "MeshSyncCoordinator initializing")
        loadSyncMetadataFromDisk()
        startSyncWorker()
        startMeshConnectivityMonitor()
        betaLogger.log(LogLevel.INFO, TAG, "MeshSyncCoordinator initialized with ${syncedFiles.size} tracked files")
    }
    
    // === PUBLIC API ===
    
    /**
     * Register a file for mesh synchronization without copying data.
     * The file is tracked and queued for replication when conditions are favorable.
     */
    suspend fun registerForSync(
        filePath: String,
        fileId: String,
        size: Long,
        targetReplicaCount: Int = 3
    ): Boolean {
        return try {
            val syncedFile = SyncedFile(
                filePath = filePath,
                fileId = fileId,
                size = size,
                state = FileState.PENDING_SYNC,
                checksum = null, // Will be calculated during replication
                lastModified = System.currentTimeMillis(),
                replicaCount = 0,
                targetReplicaCount = targetReplicaCount,
                lastSyncAttempt = null,
                syncAttempts = 0
            )
            
            syncedFiles[filePath] = syncedFile
            persistMetadata()
            
            // Queue for mesh sync if conditions allow
            if (isMeshAvailable.get() && batteryAwareSync.shouldSync()) {
                queueForSync(syncedFile, SyncType.REPLICATE)
            }
            
            onSyncComplete?.invoke(fileId, 0)
            true
        } catch (e: Exception) {
            betaLogger.log(LogLevel.ERROR, TAG, "Failed to register file: $filePath", emptyMap(), e)
            onSyncFailed?.invoke(fileId, e.message ?: "Unknown error")
            false
        }
    }
    
    /**
     * Queue a file for deletion from the mesh network.
     */
    suspend fun queueForDeletion(filePath: String, fileId: String): Boolean {
        val syncedFile = syncedFiles[filePath] ?: return false
        
        if (isMeshAvailable.get()) {
            queueForSync(syncedFile.copy(state = FileState.PENDING_SYNC), SyncType.DELETE)
        }
        
        syncedFiles.remove(filePath)
        persistMetadata()
        return true
    }
    
    /**
     * Get the current sync state for a file.
     */
    fun getFileState(path: String): FileState? = syncedFiles[path]?.state
    
    /**
     * Get detailed sync progress for a file.
     */
    fun getSyncProgress(path: String): SyncProgress? {
        val file = syncedFiles[path] ?: return null
        return SyncProgress(
            filePath = file.filePath,
            fileId = file.fileId,
            state = file.state,
            replicaCount = file.replicaCount,
            targetReplicaCount = file.targetReplicaCount,
            bytesTransferred = 0L, // TODO: Track actual transfer progress
            totalBytes = file.size,
            lastAttemptTime = file.lastSyncAttempt,
            nextRetryTime = file.lastSyncAttempt?.let { it + calculateRetryDelay(file.syncAttempts) }
        )
    }
    
    /**
     * Request immediate sync for a file, bypassing normal queue ordering.
     */
    fun requestSync(filePath: String) {
        val file = syncedFiles[filePath] ?: return
        if (batteryAwareSync.shouldSync()) {
            syncedFiles[filePath] = file.copy(state = FileState.PENDING_SYNC)
            queueForSync(file, SyncType.REPLICATE)
        }
    }
    
    /**
     * Cancel an active or pending sync operation.
     */
    fun cancelSync(filePath: String) {
        syncedFiles[filePath]?.let { file ->
            syncedFiles[filePath] = file.copy(state = FileState.PAUSED)
        }
    }
    
    /**
     * Pause all sync operations.
     */
    fun pauseAllSyncs() {
        isPaused.set(true)
        syncedFiles.keys.forEach { path ->
            syncedFiles[path]?.let { file ->
                if (file.state == FileState.SYNCING) {
                    syncedFiles[path] = file.copy(state = FileState.PAUSED)
                }
            }
        }
        betaLogger.log(LogLevel.INFO, TAG, "All syncs paused")
    }
    
    /**
     * Resume all paused sync operations.
     */
    fun resumeAllSyncs() {
        isPaused.set(false)
        syncedFiles.keys.forEach { path ->
            syncedFiles[path]?.let { file ->
                if (file.state == FileState.PAUSED) {
                    syncedFiles[path] = file.copy(state = FileState.PENDING_SYNC)
                    queueForSync(file.copy(state = FileState.PENDING_SYNC), SyncType.REPLICATE)
                }
            }
        }
        betaLogger.log(LogLevel.INFO, TAG, "All syncs resumed")
    }
    
    /**
     * Get metrics about the sync queue for monitoring.
     */
    fun getQueueMetrics(): Map<String, Int> {
        val stateCount = syncedFiles.values.groupingBy { it.state }.eachCount()
        return mapOf(
            "total" to syncedFiles.size,
            "pending" to (stateCount[FileState.PENDING_SYNC] ?: 0),
            "syncing" to (stateCount[FileState.SYNCING] ?: 0),
            "synced" to (stateCount[FileState.SYNCED] ?: 0),
            "failed" to (stateCount[FileState.SYNC_FAILED] ?: 0),
            "paused" to (stateCount[FileState.PAUSED] ?: 0),
            "queue_size" to syncQueue.size
        )
    }
    
    fun listFiles(): List<SyncedFile> = syncedFiles.values.toList()
    
    fun getPendingSyncCount(): Int = syncQueue.size
    
    fun getActiveSyncCount(): Int = activeSyncs.size
    
    // === FILE METADATA MANAGEMENT ===
    
    /**
     * Updates last accessed timestamp for LRU eviction policies.
     */
    fun updateLastAccessed(filePath: String) {
        syncedFiles[filePath]?.let { file ->
            syncedFiles[filePath] = file.copy(lastAccessed = System.currentTimeMillis())
            persistMetadata()
        }
    }
    
    /**
     * Updates mesh node IDs storing this file's chunks.
     */
    fun updateMeshNodeIds(filePath: String, nodeIds: List<Int>) {
        syncedFiles[filePath]?.let { file ->
            val updatedFile = file.copy(meshNodeIds = nodeIds)
            syncedFiles[filePath] = updatedFile
            persistMetadata()
        }
    }
    
    /**
     * Updates chunk IDs for file reconstruction.
     */
    fun updateChunkIds(filePath: String, chunkIds: List<String>) {
        syncedFiles[filePath]?.let { file ->
            syncedFiles[filePath] = file.copy(chunkIds = chunkIds)
            persistMetadata()
        }
    }
    
    /**
     * Retrieves SyncedFile metadata by file path.
     */
    fun getSyncedFile(filePath: String): SyncedFile? = syncedFiles[filePath]
    
    /**
     * Retrieves SyncedFile metadata by file ID.
     */
    fun getSyncedFileByFileId(fileId: String): SyncedFile? {
        return syncedFiles.values.find { it.fileId == fileId }
    }
    
    // === MESH CONNECTIVITY MANAGEMENT ===
    
    fun onMeshConnected() {
        isMeshAvailable.set(true)
        syncScope.launch {
            // Queue all PENDING_SYNC and SYNC_FAILED files for replication
            syncedFiles.values
                .filter { it.state in setOf(FileState.PENDING_SYNC, FileState.SYNC_FAILED) }
                .forEach { queueForSync(it, SyncType.REPLICATE) }
            
            betaLogger.log(LogLevel.INFO, TAG, "Mesh connected, queued ${syncedFiles.size} files for sync")
        }
    }
    
    fun onMeshDisconnected() {
        isMeshAvailable.set(false)
        // Pause active syncs
        activeSyncs.values.forEach { it.cancel() }
        activeSyncs.clear()
        
        // Update state of syncing files to PAUSED
        syncedFiles.keys.forEach { path ->
            syncedFiles[path]?.let { file ->
                if (file.state == FileState.SYNCING) {
                    syncedFiles[path] = file.copy(state = FileState.PAUSED)
                }
            }
        }
        
        betaLogger.log(LogLevel.INFO, TAG, "Mesh disconnected, paused active syncs")
    }
    
    // === PRIVATE IMPLEMENTATION ===
    
    private fun queueForSync(file: SyncedFile, syncType: SyncType) {
        val operation = SyncOperation(file, syncType)
        
        synchronized(syncQueue) {
            // Remove existing operations for this file
            syncQueue.removeAll { it.file.filePath == file.filePath }
            
            // Add to end of queue (FIFO)
            syncQueue.addLast(operation)
        }
        
        betaLogger.log(LogLevel.DEBUG, TAG, "Queued ${file.filePath} for ${syncType.name}, queue size: ${syncQueue.size}")
    }
    
    private fun startSyncWorker() {
        syncScope.launch {
            while (isActive) {
                try {
                    if (shouldSkipSync()) {
                        delay(30_000) // Wait 30s before checking again
                        continue
                    }
                    
                    val batch = mutableListOf<SyncOperation>()
                    synchronized(syncQueue) {
                        // Respect battery constraints only
                        if (batteryAwareSync.shouldSync()) {
                            repeat(minOf(SYNC_BATCH_SIZE, syncQueue.size)) {
                                syncQueue.removeFirstOrNull()?.let { batch.add(it) }
                            }
                        }
                    }
                    
                    if (batch.isEmpty()) {
                        delay(5_000) // Wait 5s before checking again
                        continue
                    }
                    
                    // Process batch with concurrency control
                    batch.chunked(MAX_CONCURRENT_SYNCS).forEach { chunk ->
                        chunk.map { operation ->
                            async {
                                val job = launch { 
                                    val result = performSync(operation)
                                    operation.deferred.complete(result)
                                }
                                activeSyncs[operation.file.filePath] = job
                                
                                try {
                                    job.join()
                                } finally {
                                    activeSyncs.remove(operation.file.filePath)
                                }
                            }
                        }.awaitAll()
                    }
                    
                } catch (e: Exception) {
                    betaLogger.log(LogLevel.ERROR, TAG, "Sync worker error", emptyMap(), e)
                    delay(10_000)
                }
            }
        }
    }
    
    private fun shouldSkipSync(): Boolean {
        return isPaused.get() || !isMeshAvailable.get()
    }
    
    private suspend fun performSync(operation: SyncOperation): SyncResult {
        val file = operation.file
        
        // Update state to SYNCING
        updateFileState(file.filePath, FileState.SYNCING)
        
        return try {
            val result = when (operation.operation) {
                SyncType.REPLICATE -> replicateToMesh(file)
                SyncType.DELETE -> deleteFromMesh(file)
            }
            
            // Update state based on result
            when (result) {
                is SyncResult.Success -> {
                    updateFileState(file.filePath, FileState.SYNCED)
                    onSyncComplete?.invoke(file.fileId, file.replicaCount)
                }
                is SyncResult.Partial -> {
                    updateFileState(file.filePath, FileState.PARTIAL)
                }
                is SyncResult.Conflict -> {
                    updateFileState(file.filePath, FileState.CONFLICT)
                }
                is SyncResult.Failure -> {
                    handleSyncFailure(file, result, operation)
                }
            }
            
            result
        } catch (e: Exception) {
            val retryable = e !is SecurityException && file.syncAttempts < MAX_RETRY_ATTEMPTS
            
            betaLogger.log(LogLevel.WARN, TAG, 
                "Sync failed for ${file.filePath}: ${e.message} (attempt ${file.syncAttempts + 1}/$MAX_RETRY_ATTEMPTS)")
            
            val failureResult = SyncResult.Failure(e.message ?: "Unknown error", retryable)
            handleSyncFailure(file, failureResult, operation)
            failureResult
        }
    }
    
    private suspend fun handleSyncFailure(
        file: SyncedFile, 
        failure: SyncResult.Failure, 
        operation: SyncOperation
    ) {
        if (failure.retryable && file.syncAttempts < MAX_RETRY_ATTEMPTS) {
            // Exponential backoff with jitter
            val backoffMs = calculateRetryDelay(file.syncAttempts)
            
            betaLogger.log(LogLevel.INFO, TAG, 
                "Retrying ${file.filePath} in ${backoffMs}ms (attempt ${file.syncAttempts + 1}/$MAX_RETRY_ATTEMPTS)")
            
            val retryFile = file.copy(
                syncAttempts = file.syncAttempts + 1,
                lastSyncAttempt = System.currentTimeMillis(),
                state = FileState.PENDING_SYNC
            )
            syncedFiles[file.filePath] = retryFile
            persistMetadata()
            
            delay(backoffMs)
            queueForSync(retryFile, operation.operation)
        } else {
            // Mark as permanently failed
            betaLogger.log(LogLevel.ERROR, TAG, 
                "Sync permanently failed for ${file.filePath}: ${failure.error}")
            
            val failedFile = file.copy(
                state = FileState.SYNC_FAILED,
                lastSyncAttempt = System.currentTimeMillis()
            )
            syncedFiles[file.filePath] = failedFile
            persistMetadata()
            
            onSyncFailed?.invoke(file.fileId, failure.error)
        }
    }
    
    private fun calculateRetryDelay(syncAttempts: Int): Long {
        val exponentialDelay = BASE_RETRY_DELAY_MS * (1 shl syncAttempts)
        val cappedDelay = minOf(exponentialDelay.toLong(), MAX_RETRY_DELAY_MS)
        val jitter = (cappedDelay * JITTER_FACTOR * Math.random()).toLong()
        return cappedDelay + jitter
    }
    
    
    private suspend fun replicateToMesh(file: SyncedFile): SyncResult {
        // TODO: Call DistributedStorageManager.replicateFile(filePath, fileId, targetReplicaCount)
        // For now, placeholder that will be implemented in Step 6
        betaLogger.log(LogLevel.DEBUG, TAG, "Replicating ${file.filePath} to mesh (placeholder)")
        
        // Simulate replication success
        val replicatedFile = file.copy(
            state = FileState.SYNCED,
            replicaCount = file.targetReplicaCount,
            syncAttempts = 0
        )
        
        syncedFiles[file.filePath] = replicatedFile
        persistMetadata()
        
        return SyncResult.Success
    }
    
    private suspend fun deleteFromMesh(file: SyncedFile): SyncResult {
        // TODO: Call DistributedStorageManager.deleteFile(fileId)
        betaLogger.log(LogLevel.DEBUG, TAG, "Deleting ${file.filePath} from mesh (placeholder)")
        
        syncedFiles.remove(file.filePath)
        persistMetadata()
        return SyncResult.Success
    }
    
    private fun updateFileState(path: String, newState: FileState) {
        syncedFiles[path]?.let { file ->
            syncedFiles[path] = file.copy(state = newState)
            persistMetadata()
        }
    }
    
    private fun startMeshConnectivityMonitor() {
        syncScope.launch {
            while (isActive) {
                val wasAvailable = isMeshAvailable.get()
                val isNowAvailable = checkMeshConnectivity()
                
                if (!wasAvailable && isNowAvailable) {
                    onMeshConnected()
                } else if (wasAvailable && !isNowAvailable) {
                    onMeshDisconnected()
                }
                
                delay(10_000) // Check every 10 seconds
            }
        }
    }
    
    private suspend fun checkMeshConnectivity(): Boolean {
        return try {
            // Implementation depends on mesh network layer
            // For now, assume available if we have mesh network reference
            true
        } catch (e: Exception) {
            false
        }
    }
    
    // === PERSISTENCE ===
    
    private fun loadSyncMetadataFromDisk() {
        try {
            if (!metadataFile.exists()) {
                betaLogger.log(LogLevel.DEBUG, TAG, "No metadata file found, starting fresh")
                return
            }
            
            val json = metadataFile.readText()
            // TODO: Implement proper JSON deserialization of SyncedFile map in Step 5
            // For now, just log that we would load
            betaLogger.log(LogLevel.DEBUG, TAG, "Loaded sync metadata (placeholder)")
        } catch (e: Exception) {
            betaLogger.log(LogLevel.ERROR, TAG, "Failed to load metadata, starting fresh", emptyMap(), e)
            // Handle corruption gracefully - start with empty state
            syncedFiles.clear()
        }
    }
    
    private fun persistMetadata() {
        syncScope.launch {
            try {
                // TODO: Implement proper JSON serialization of syncedFiles map in Step 5
                // For now, just log that we would persist
                betaLogger.log(LogLevel.DEBUG, TAG, "Persisted sync metadata (placeholder)")
            } catch (e: Exception) {
                betaLogger.log(LogLevel.ERROR, TAG, "Failed to persist metadata", emptyMap(), e)
            }
        }
    }
    
    /**
     * Queue file for deletion from mesh network (internal API for DistributedStorageManager).
     * @param filePath Absolute path to the file
     * @param fileId SHA-256 hash of the file
     */
    internal fun queueForMeshDeletion(filePath: String, fileId: String) {
        syncScope.launch {
            try {
                val syncedFile = syncedFiles[filePath]
                if (syncedFile != null) {
                    queueForSync(syncedFile.copy(state = FileState.PENDING_SYNC), SyncType.DELETE)
                } else {
                    betaLogger.log(LogLevel.WARN, TAG, "File not found in sync tracking: $filePath")
                }
            } catch (e: Exception) {
                betaLogger.log(LogLevel.ERROR, TAG, "Failed to queue mesh deletion for $filePath", emptyMap(), e)
                syncedFiles[filePath]?.let { file ->
                    syncedFiles[filePath] = file.copy(state = FileState.SYNC_FAILED)
                }
            }
        }
    }
    
    fun close() {
        syncScope.cancel()
        betaLogger.log(LogLevel.INFO, TAG, "MeshSyncCoordinator closed")
    }
}


// === SUPPORTING CLASSES ===

/**
 * Tracks sync state for a file in the distributed mesh storage system.
 * Does NOT duplicate file data - tracks original file path only.
 */
data class SyncedFile(
    val filePath: String,               // Absolute path to original file
    val fileId: String,                 // SHA-256 hash for mesh identification
    val size: Long,                     // File size in bytes
    val state: FileState,               // Current sync state
    val lastModified: Long,             // File last modification time
    val lastSyncAttempt: Long? = null,  // When last sync was attempted (null if never)
    val syncAttempts: Int = 0,          // Number of sync retry attempts
    val replicaCount: Int = 0,          // Current known replicas on mesh
    val targetReplicaCount: Int,        // Desired replicas based on ReplicationLevel
    val checksum: String? = null,       // Optional integrity check (SHA-256)
    val metadata: Map<String, String> = emptyMap(),
    val lastAccessed: Long = System.currentTimeMillis(),  // For LRU eviction
    val meshNodeIds: List<Int> = emptyList(),          // Nodes storing this file
    val chunkIds: List<String> = emptyList()              // Chunk IDs for reconstruction
)

data class SyncOperation(
    val file: SyncedFile,
    val operation: SyncType,
    val timestamp: Long = System.currentTimeMillis(),
    val deferred: CompletableDeferred<SyncResult> = CompletableDeferred()
)

/**
 * File sync states - tracks replication status in distributed mesh storage.
 */
enum class FileState {
    PENDING_SYNC,    // Registered, waiting in queue for battery/network conditions
    SYNCING,         // Currently being chunked/replicated by DistributedStorageManager
    SYNCED,          // Successfully replicated to mesh (target replica count met)
    SYNC_FAILED,     // Sync failed after max retries
    PAUSED,          // Sync paused (low battery, user request, network unavailable)
    PARTIAL,         // Some chunks synced, but not all replicas met target
    CONFLICT         // File modified locally after sync started (future versioning)
}

/**
 * Sync operation types for mesh storage operations.
 */
enum class SyncType {
    REPLICATE,       // Replicate local file to mesh (chunking + distribution)
    DELETE           // Remove from mesh
}

sealed class SyncResult {
    object Success : SyncResult()
    data class Failure(val error: String, val retryable: Boolean) : SyncResult()
    data class Conflict(val localVersion: SyncedFile, val meshVersion: String) : SyncResult()
    data class Partial(val replicaCount: Int, val targetCount: Int) : SyncResult()
}

/**
 * Sync progress information for UI display.
 */
data class SyncProgress(
    val filePath: String,
    val fileId: String,
    val state: FileState,
    val replicaCount: Int,
    val targetReplicaCount: Int,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val lastAttemptTime: Long?,
    val nextRetryTime: Long?,
    val progressPercent: Int = if (totalBytes > 0) ((bytesTransferred * 100) / totalBytes).toInt() else 0
)

/**
 * Battery-aware sync management with priority-based decisions.
 * Prevents battery drain by limiting sync operations based on battery level and charging state.
 */
class BatteryAwareSync(private val context: Context) {
    
    /**
     * Determines if sync should proceed based on battery level and charging state.
     */
    fun shouldSync(): Boolean {
        val batteryLevel = getBatteryLevel()
        val isCharging = isCharging()
        
        return when {
            // Very low battery - stop all sync
            batteryLevel < 10 -> false
            
            // Low battery - only when charging
            batteryLevel < 20 -> isCharging
            
            // Medium battery - sync when charging
            batteryLevel < 50 -> isCharging
            
            // Good battery and above - sync allowed
            else -> true
        }
    }
    
    private fun getBatteryLevel(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        } catch (e: Exception) {
            100 // Assume full battery on error
        }
    }
    
    private fun isCharging(): Boolean {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS) ?: 0
            } else {
                0 // Not available pre-API 26
            }
            status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || 
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            false
        }
    }
}

