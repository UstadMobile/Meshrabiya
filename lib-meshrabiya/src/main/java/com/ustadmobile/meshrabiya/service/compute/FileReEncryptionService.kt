package com.ustadmobile.meshrabiya.service.compute

import android.content.Context
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import com.ustadmobile.meshrabiya.storage.RecipientType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * File Re-encryption Service for task keypair workflow.
 * 
 * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART2.md Section 6
 * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART4.md Section 11 (Performance: 43ms per file)
 * 
 * Client-side workflow:
 * 1. Receive TASK_SCHEDULED message with task public key from compute node
 * 2. Re-encrypt input files to add task public key as recipient
 * 3. Upload updated file metadata
 * 4. Send FILE_RE_ENCRYPTION_COMPLETE acknowledgment
 */
object FileReEncryptionService {
    
    /**
     * Re-encrypt input files to add task public key as recipient.
     * 
     * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART2.md Section 6
     * 
     * This operation does NOT re-encrypt the entire file data, only the
     * session key is re-encrypted for the new task recipient.
     * 
     * Performance: ~43ms per file (from benchmarks)
     * 
     * @param context Android context
     * @param fileIds List of file IDs to re-encrypt
     * @param taskId Task identifier
     * @param taskPublicKey Task public key (PEM format)
     * @param taskLifetimeMs Task keypair lifetime (for expiration)
     * @return true if all files successfully re-encrypted
     */
    suspend fun reEncryptFilesForTask(
        context: Context,
        fileIds: List<String>,
        taskId: String,
        taskPublicKey: String,
        taskLifetimeMs: Long = 24 * 60 * 60 * 1000L // 24 hours default
    ): Boolean = withContext(Dispatchers.IO) {
        val storageManager = DistributedStorageManager.getInstance()
        val expiresAt = System.currentTimeMillis() + taskLifetimeMs
        
        // Create task recipient entry
        val taskRecipient = RecipientEntry(
            publicKey = taskPublicKey,
            recipientType = RecipientType.TASK,
            expiresAt = expiresAt,
            recipientId = taskId
        )
        
        try {
            // Re-encrypt each file (adds task recipient to session key encryption)
            for (fileId in fileIds) {
                val success = storageManager.updateFileAccess(
                    fileId = fileId,
                    addRecipients = listOf(taskRecipient),
                    removeRecipients = emptyList()
                )
                    
                if (!success) {
                    // Rollback previous files
                    rollbackFileAccess(context, fileIds.take(fileIds.indexOf(fileId)), taskPublicKey)
                    return@withContext false
                }
            }
            
            true
        } catch (e: Exception) {
            // Rollback on error
            rollbackFileAccess(context, fileIds, taskPublicKey)
            false
        }
    }
    
    /**
     * Rollback file access changes (remove task recipient).
     * 
     * @param context Android context
     * @param fileIds File IDs to rollback
     * @param taskPublicKey Task public key to remove
     */
    private suspend fun rollbackFileAccess(
        context: Context,
        fileIds: List<String>,
        taskPublicKey: String
    ) {
        val storageManager = DistributedStorageManager.getInstance()
        
        for (fileId in fileIds) {
            try {
                storageManager.updateFileAccess(
                    fileId = fileId,
                    addRecipients = emptyList(),
                    removeRecipients = listOf(taskPublicKey)
                )
            } catch (e: Exception) {
                // Log error but continue rollback
            }
        }
    }
    
    /**
     * Remove task recipient from files after task completion.
     * 
     * @param context Android context
     * @param fileIds File IDs to clean up
     * @param taskPublicKey Task public key to remove
     */
    suspend fun cleanupTaskFileAccess(
        context: Context,
        fileIds: List<String>,
        taskPublicKey: String
    ) {
        val storageManager = DistributedStorageManager.getInstance()
        
        for (fileId in fileIds) {
            try {
                storageManager.updateFileAccess(
                    fileId = fileId,
                    addRecipients = emptyList(),
                    removeRecipients = listOf(taskPublicKey)
                )
            } catch (e: Exception) {
                // Log error but continue cleanup
            }
        }
    }
    
    /**
     * Verify that all files have task recipient access.
     * 
     * @param context Android context
     * @param fileIds File IDs to verify
     * @param taskId Task identifier
     * @return true if all files have task access
     */
    suspend fun verifyTaskFileAccess(
        context: Context,
        fileIds: List<String>,
        taskId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val storageManager = DistributedStorageManager.getInstance()
        
        fileIds.all { fileId ->
            val metadata = storageManager.getFileMetadata(fileId)
            metadata?.hasTaskAccess(taskId) == true
        }
    }
}
