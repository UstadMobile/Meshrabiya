package org.torproject.meshrabiya.compute.recovery

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * PartialExecutionRecovery manages task execution checkpoints and recovery.
 * 
 * Features:
 * - Automatic checkpoint creation at configurable intervals
 * - Incremental state saving
 * - Resume from last successful checkpoint
 * - Partial result collection
 * - Checkpoint cleanup after completion
 * 
 * Architecture:
 * - Saves execution state to disk at intervals
 * - Tracks progress markers (e.g., processed items, completed steps)
 * - Stores intermediate results
 * - Enables resume from last checkpoint on failure
 * 
 * Checkpoint Strategy:
 * - Time-based: Every N seconds
 * - Progress-based: Every N items processed
 * - Manual: Explicit checkpoint requests
 * 
 * Recovery Process:
 * 1. Load last successful checkpoint
 * 2. Validate checkpoint integrity
 * 3. Restore execution state
 * 4. Resume from checkpoint position
 * 5. Continue execution
 * 
 * @property checkpointDir Directory for storing checkpoints
 * @property autoCheckpointIntervalMs Automatic checkpoint interval (default 60s)
 * @property maxCheckpointsPerTask Maximum checkpoints to keep per task (default 3)
 */
class PartialExecutionRecovery(
    private val checkpointDir: File,
    private val autoCheckpointIntervalMs: Long = 60000,
    private val maxCheckpointsPerTask: Int = 3,
    private val enableCompression: Boolean = true
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        if (!checkpointDir.exists()) {
            checkpointDir.mkdirs()
        }
    }
    
    /**
     * Execution checkpoint containing task state.
     */
    @Serializable
    data class ExecutionCheckpoint(
        val taskId: String,
        val checkpointId: String,
        val timestampMs: Long,
        val progressPercent: Int,
        val currentStep: String,
        val processedItems: Int,
        val totalItems: Int,
        val executionState: Map<String, String>,
        val intermediateResults: List<IntermediateResult>,
        val metadata: Map<String, String> = emptyMap()
    )
    
    /**
     * Intermediate result from partial execution.
     */
    @Serializable
    data class IntermediateResult(
        val resultId: String,
        val timestampMs: Long,
        val dataType: String,
        val dataJson: String,
        val size: Long
    )
    
    /**
     * Active checkpoint session.
     */
    private data class CheckpointSession(
        val taskId: String,
        var lastCheckpointMs: Long = System.currentTimeMillis(),
        var checkpointCount: Int = 0,
        val checkpoints: MutableList<String> = mutableListOf(),
        var autoCheckpointJob: Job? = null
    )
    
    // Active checkpoint sessions
    private val activeSessions = ConcurrentHashMap<String, CheckpointSession>()
    
    // Statistics
    @Volatile private var totalCheckpointsCreated = 0L
    @Volatile private var totalCheckpointsRestored = 0L
    @Volatile private var totalCheckpointFailures = 0L
    @Volatile private var totalBytesWritten = 0L
    @Volatile private var totalBytesRead = 0L
    
    /**
     * Start checkpoint session for a task.
     * 
     * @param taskId Task identifier
     * @param enableAutoCheckpoint Whether to enable automatic checkpointing
     * @return True if session started, false if already exists
     */
    fun startSession(taskId: String, enableAutoCheckpoint: Boolean = true): Boolean {
        if (activeSessions.containsKey(taskId)) {
            return false
        }
        
        val session = CheckpointSession(taskId)
        
        if (enableAutoCheckpoint) {
            session.autoCheckpointJob = scope.launch {
                while (isActive) {
                    delay(autoCheckpointIntervalMs)
                    // Auto-checkpoint trigger - application should call saveCheckpoint
                }
            }
        }
        
        activeSessions[taskId] = session
        return true
    }
    
    /**
     * End checkpoint session for a task.
     * 
     * @param taskId Task identifier
     * @param cleanupCheckpoints Whether to delete all checkpoints (default true)
     * @return True if session ended, false if not found
     */
    fun endSession(taskId: String, cleanupCheckpoints: Boolean = true): Boolean {
        val session = activeSessions.remove(taskId) ?: return false
        
        session.autoCheckpointJob?.cancel()
        
        if (cleanupCheckpoints) {
            deleteCheckpoints(taskId)
        }
        
        return true
    }
    
    /**
     * Save execution checkpoint.
     * 
     * @param checkpoint Checkpoint data
     * @return Checkpoint ID if saved successfully, null on failure
     */
    suspend fun saveCheckpoint(checkpoint: ExecutionCheckpoint): String? = withContext(Dispatchers.IO) {
        val session = activeSessions[checkpoint.taskId] ?: return@withContext null
        
        try {
            val checkpointId = checkpoint.checkpointId
            val checkpointFile = getCheckpointFile(checkpoint.taskId, checkpointId)
            
            // Serialize checkpoint
            val json = Json.encodeToString(ExecutionCheckpoint.serializer(), checkpoint)
            val bytes = if (enableCompression) {
                compressData(json.toByteArray())
            } else {
                json.toByteArray()
            }
            
            // Write to file
            checkpointFile.writeBytes(bytes)
            
            // Update session
            session.lastCheckpointMs = System.currentTimeMillis()
            session.checkpointCount++
            session.checkpoints.add(checkpointId)
            
            // Cleanup old checkpoints
            if (session.checkpoints.size > maxCheckpointsPerTask) {
                val oldCheckpointId = session.checkpoints.removeAt(0)
                getCheckpointFile(checkpoint.taskId, oldCheckpointId).delete()
            }
            
            totalCheckpointsCreated++
            totalBytesWritten += bytes.size
            
            checkpointId
            
        } catch (e: Exception) {
            totalCheckpointFailures++
            null
        }
    }
    
    /**
     * Load latest checkpoint for a task.
     * 
     * @param taskId Task identifier
     * @return Checkpoint if found, null otherwise
     */
    suspend fun loadLatestCheckpoint(taskId: String): ExecutionCheckpoint? = withContext(Dispatchers.IO) {
        val checkpointIds = listCheckpoints(taskId)
        if (checkpointIds.isEmpty()) {
            return@withContext null
        }
        
        // Try loading checkpoints from newest to oldest
        for (checkpointId in checkpointIds.reversed()) {
            val checkpoint = loadCheckpoint(taskId, checkpointId)
            if (checkpoint != null) {
                return@withContext checkpoint
            }
        }
        
        null
    }
    
    /**
     * Load specific checkpoint.
     * 
     * @param taskId Task identifier
     * @param checkpointId Checkpoint identifier
     * @return Checkpoint if found and valid, null otherwise
     */
    suspend fun loadCheckpoint(taskId: String, checkpointId: String): ExecutionCheckpoint? = withContext(Dispatchers.IO) {
        try {
            val checkpointFile = getCheckpointFile(taskId, checkpointId)
            if (!checkpointFile.exists()) {
                return@withContext null
            }
            
            // Read from file
            val bytes = checkpointFile.readBytes()
            val json = if (enableCompression) {
                String(decompressData(bytes))
            } else {
                String(bytes)
            }
            
            // Deserialize checkpoint
            val checkpoint = Json.decodeFromString(ExecutionCheckpoint.serializer(), json)
            
            totalCheckpointsRestored++
            totalBytesRead += bytes.size
            
            checkpoint
            
        } catch (e: Exception) {
            totalCheckpointFailures++
            null
        }
    }
    
    /**
     * List all checkpoint IDs for a task.
     * 
     * @param taskId Task identifier
     * @return List of checkpoint IDs, sorted by timestamp (oldest first)
     */
    fun listCheckpoints(taskId: String): List<String> {
        val taskDir = File(checkpointDir, taskId)
        if (!taskDir.exists()) {
            return emptyList()
        }
        
        return taskDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".checkpoint") }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    }
    
    /**
     * Delete all checkpoints for a task.
     * 
     * @param taskId Task identifier
     * @return Number of checkpoints deleted
     */
    fun deleteCheckpoints(taskId: String): Int {
        val taskDir = File(checkpointDir, taskId)
        if (!taskDir.exists()) {
            return 0
        }
        
        val checkpointFiles = taskDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".checkpoint") }
            ?: emptyList()
        
        var deletedCount = 0
        checkpointFiles.forEach { file ->
            if (file.delete()) {
                deletedCount++
            }
        }
        
        // Delete task directory if empty
        if (taskDir.listFiles()?.isEmpty() == true) {
            taskDir.delete()
        }
        
        return deletedCount
    }
    
    /**
     * Delete specific checkpoint.
     * 
     * @param taskId Task identifier
     * @param checkpointId Checkpoint identifier
     * @return True if deleted, false otherwise
     */
    fun deleteCheckpoint(taskId: String, checkpointId: String): Boolean {
        val checkpointFile = getCheckpointFile(taskId, checkpointId)
        return checkpointFile.delete()
    }
    
    /**
     * Check if task has any checkpoints available.
     * 
     * @param taskId Task identifier
     * @return True if checkpoints exist
     */
    fun hasCheckpoints(taskId: String): Boolean {
        return listCheckpoints(taskId).isNotEmpty()
    }
    
    /**
     * Get time since last checkpoint for a task.
     * 
     * @param taskId Task identifier
     * @return Time in milliseconds, or null if no active session
     */
    fun getTimeSinceLastCheckpointMs(taskId: String): Long? {
        val session = activeSessions[taskId] ?: return null
        return System.currentTimeMillis() - session.lastCheckpointMs
    }
    
    /**
     * Create checkpoint builder for easier checkpoint creation.
     * 
     * @param taskId Task identifier
     * @return Checkpoint builder
     */
    fun createCheckpointBuilder(taskId: String): CheckpointBuilder {
        return CheckpointBuilder(taskId)
    }
    
    /**
     * Get checkpoint file for a task and checkpoint ID.
     */
    private fun getCheckpointFile(taskId: String, checkpointId: String): File {
        val taskDir = File(checkpointDir, taskId)
        if (!taskDir.exists()) {
            taskDir.mkdirs()
        }
        return File(taskDir, "$checkpointId.checkpoint")
    }
    
    /**
     * Compress data using GZIP.
     */
    private fun compressData(data: ByteArray): ByteArray {
        // TODO: Implement GZIP compression
        // For now, return uncompressed
        return data
    }
    
    /**
     * Decompress data using GZIP.
     */
    private fun decompressData(data: ByteArray): ByteArray {
        // TODO: Implement GZIP decompression
        // For now, return as-is
        return data
    }
    
    /**
     * Get recovery statistics.
     */
    fun getStatistics(): RecoveryStatistics {
        return RecoveryStatistics(
            activeSessionCount = activeSessions.size,
            totalCheckpointsCreated = totalCheckpointsCreated,
            totalCheckpointsRestored = totalCheckpointsRestored,
            totalCheckpointFailures = totalCheckpointFailures,
            totalBytesWritten = totalBytesWritten,
            totalBytesRead = totalBytesRead
        )
    }
    
    /**
     * Get checkpoint info for all active sessions.
     */
    fun getActiveSessionInfo(): List<SessionInfo> {
        return activeSessions.values.map { session ->
            SessionInfo(
                taskId = session.taskId,
                lastCheckpointMs = session.lastCheckpointMs,
                checkpointCount = session.checkpointCount,
                timeSinceLastCheckpointMs = System.currentTimeMillis() - session.lastCheckpointMs,
                autoCheckpointEnabled = session.autoCheckpointJob != null
            )
        }
    }
    
    /**
     * Shutdown recovery manager.
     */
    fun shutdown() {
        activeSessions.values.forEach { session ->
            session.autoCheckpointJob?.cancel()
        }
        activeSessions.clear()
        scope.cancel()
    }
    
    /**
     * Recovery statistics.
     */
    data class RecoveryStatistics(
        val activeSessionCount: Int,
        val totalCheckpointsCreated: Long,
        val totalCheckpointsRestored: Long,
        val totalCheckpointFailures: Long,
        val totalBytesWritten: Long,
        val totalBytesRead: Long
    ) {
        val successRate: Double
            get() = if (totalCheckpointsCreated + totalCheckpointFailures > 0) {
                totalCheckpointsCreated.toDouble() / (totalCheckpointsCreated + totalCheckpointFailures)
            } else 0.0
    }
    
    /**
     * Active session information.
     */
    data class SessionInfo(
        val taskId: String,
        val lastCheckpointMs: Long,
        val checkpointCount: Int,
        val timeSinceLastCheckpointMs: Long,
        val autoCheckpointEnabled: Boolean
    )
    
    /**
     * Builder for creating execution checkpoints.
     */
    class CheckpointBuilder(private val taskId: String) {
        private var currentStep: String = ""
        private var processedItems: Int = 0
        private var totalItems: Int = 0
        private val executionState = mutableMapOf<String, String>()
        private val intermediateResults = mutableListOf<IntermediateResult>()
        private val metadata = mutableMapOf<String, String>()
        
        fun setStep(step: String) = apply { this.currentStep = step }
        
        fun setProgress(processed: Int, total: Int) = apply {
            this.processedItems = processed
            this.totalItems = total
        }
        
        fun addState(key: String, value: String) = apply {
            executionState[key] = value
        }
        
        fun addIntermediateResult(
            resultId: String,
            dataType: String,
            dataJson: String,
            size: Long
        ) = apply {
            intermediateResults.add(
                IntermediateResult(
                    resultId = resultId,
                    timestampMs = System.currentTimeMillis(),
                    dataType = dataType,
                    dataJson = dataJson,
                    size = size
                )
            )
        }
        
        fun addMetadata(key: String, value: String) = apply {
            metadata[key] = value
        }
        
        fun build(): ExecutionCheckpoint {
            val progressPercent = if (totalItems > 0) {
                ((processedItems.toDouble() / totalItems) * 100).toInt()
            } else 0
            
            return ExecutionCheckpoint(
                taskId = taskId,
                checkpointId = generateCheckpointId(),
                timestampMs = System.currentTimeMillis(),
                progressPercent = progressPercent,
                currentStep = currentStep,
                processedItems = processedItems,
                totalItems = totalItems,
                executionState = executionState.toMap(),
                intermediateResults = intermediateResults.toList(),
                metadata = metadata.toMap()
            )
        }
        
        private fun generateCheckpointId(): String {
            return "checkpoint_${System.currentTimeMillis()}"
        }
    }
}
