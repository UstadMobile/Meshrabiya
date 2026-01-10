package com.ustadmobile.meshrabiya.service.compute

import android.content.Context
import com.ustadmobile.meshrabiya.storage.DistributedStorageClient
import com.ustadmobile.meshrabiya.storage.FileReference
import com.ustadmobile.meshrabiya.service.compute.DistributedServiceLibrary.ServiceLibraryEntry
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl

/**
 * TaskInputFileManager
 * 
 * Manages input file retrieval and tracking for tasks.
 * 
 * Features:
 * - FileReference tracking per task
 * - Determines execution readiness
 * - Integrates with DistributedStorageClient and TaskSandboxManager
 */
class TaskInputFileManager(
    private val context: Context,
    private val distributedStorageClient: DistributedStorageClient,
    private val sandboxManager: TaskSandboxManager,
    private val betaLogger: BetaTestLogger? = null
) {
    private val expectedFiles = ConcurrentHashMap<String, MutableSet<String>>()
    private val receivedFiles = ConcurrentHashMap<String, MutableSet<String>>()
    
    companion object {
        private const val TAG = "TaskInputFileManager"
    }
    
    /**
     * Track expected input files for task.
     */
    fun trackExpectedFiles(taskId: String, inputManifest: List<FileReference>) {
        val fileIds = inputManifest.map { it.fileId }.toMutableSet()
        expectedFiles[taskId] = fileIds
        receivedFiles.getOrPut(taskId) { mutableSetOf() }
        
        betaLogger?.log(LogLevel.DEBUG, TAG, "Tracking \${fileIds.size} expected files for task \$taskId")
    }
    
    /**
     * Handle file access update notification.
     * Retrieves file from storage and writes to sandbox.
     */
    suspend fun handleFileAccessUpdate(
        task: Task,
        fileId: String
    ) = withContext(Dispatchers.IO) {
        try {
            betaLogger?.log(LogLevel.DEBUG, TAG, "Retrieving file \$fileId for task \${task.taskId}")
            
            // Retrieve file from distributed storage
            // val fileReference = FileReference(
            //     fileId = fileId,
            //     path = fileId,
            //     sizeBytes = 0L
            // )
            
            val fileBytes = MeshrabiyaApiImpl.getInstance().retrieveFile(fileId)
            
            if (fileBytes != null) {
                // Write to sandbox inputs directory
                sandboxManager.writeInputFile(task.taskId, fileId, fileBytes)
                
                // Track received file
                receivedFiles.getOrPut(task.taskId) { mutableSetOf() }.add(fileId)
                
                betaLogger?.log(LogLevel.DEBUG, TAG, "File \$fileId added to task \${task.taskId} sandbox")
            } else {
                betaLogger?.log(LogLevel.ERROR, TAG, "Failed to retrieve file: \$fileId")
                throw Exception("File retrieval failed: $fileId")
            }
            
        } catch (e: Exception) {
            betaLogger?.log(LogLevel.ERROR, TAG, "File access update failed for task \${task.taskId}: \${e.message}")
            task.state = TaskState.FAILED
            throw e
        }
    }
    
    /**
     * Check if all expected input files have been received.
     */
    fun shouldProceedToExecution(task: Task, serviceLibraryEntry: ServiceLibraryEntry): Boolean {
        // If task doesn't expect input files, proceed immediately
        if (!serviceLibraryEntry.hasInputFiles) {
            return true
        }
        
        val expected = expectedFiles[task.taskId] ?: emptySet()
        val received = receivedFiles[task.taskId] ?: emptySet()
        
        val ready = expected == received
        
        betaLogger?.log(LogLevel.DEBUG, TAG, "Task \${task.taskId} ready check: \${received.size}/\${expected.size} files received")
        
        return ready
    }
    
    /**
     * Cleanup tracking data for task.
     */
    fun cleanupTask(taskId: String) {
        expectedFiles.remove(taskId)
        receivedFiles.remove(taskId)
    }
}
