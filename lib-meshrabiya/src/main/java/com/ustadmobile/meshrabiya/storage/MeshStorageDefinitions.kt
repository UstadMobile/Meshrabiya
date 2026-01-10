package com.ustadmobile.meshrabiya.storage

import kotlinx.serialization.Serializable
import com.ustadmobile.meshrabiya.storage.RecipientType
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import com.ustadmobile.meshrabiya.service.StorageNodeRequest
import com.ustadmobile.meshrabiya.service.StorageNodeResponse
import com.ustadmobile.meshrabiya.vnet.MeshChunk

/**
 * Reference to a file in distributed storage.
 */
// data class FileReference(
//     val id: String,
//     val path: String,
//     val size: Long
// )
/**
 * FILE REFERENCE
 * 
 * Reference to a file in DistributedStorage (not the actual file data)
 */
@Serializable
data class FileReference(
    val fileId: String,        // SHA-256 hash of file
    val path: String,
    val fileName: String,      // Original filename
    val sizeBytes: Long,
    val mimeType: String? = null
)

/**
 * Metadata for a file in distributed storage.
 */
@Serializable
data class FileMetadata(
    val fileId: String,
    val path: String,
    val sizeBytes: Long,
    val owner: RecipientEntry,
    val recipients: List<RecipientEntry>,
    val createdAt: Long,
    val fileName: String = "",
    val relativePath: String = "",
    val lastAccessedBy: String? = null,
    val encryptionKeyId: String? = null
) {
    fun getActiveRecipients(): List<RecipientEntry> = recipients.filter { !it.isExpired() }
    fun getUserRecipients(): List<RecipientEntry> = recipients.filter { it.recipientType == RecipientType.USER }
    fun getTaskRecipients(): List<RecipientEntry> = recipients.filter { it.recipientType == RecipientType.TASK }
    fun hasTaskAccess(taskId: String): Boolean = recipients.any {
        it.recipientType == RecipientType.TASK && it.recipientId == taskId && !it.isExpired()
    }
}

// @Serializable
// data class FileMetadata(
//     val fileId: String,
//     val path: String,
//     val sizeBytes: Long,
//     val owner: RecipientEntry,
//     val recipients: List<RecipientEntry>,
//     val createdAt: Long,
//     val lastAccessedBy: String? = null,
//     val encryptionKeyId: String? = null
// ) {
//     fun getActiveRecipients(): List<RecipientEntry> {
//         return recipients.filter { !it.isExpired() }
//     }

//     fun getUserRecipients(): List<RecipientEntry> {
//         return recipients.filter { it.recipientType == RecipientType.USER }
//     }

//     fun getTaskRecipients(): List<RecipientEntry> {
//         return recipients.filter { it.recipientType == RecipientType.TASK }
//     }

//     fun hasTaskAccess(taskId: String): Boolean {
//         return recipients.any {
//             it.recipientType == RecipientType.TASK &&
//             it.recipientId == taskId &&
//             !it.isExpired()
//         }
//     }
// }

@Serializable
data class DropFolderItem(
    val itemRelativePath: String,
    val isFolder: Boolean,
    val parent: DropFolderItem? = null,
    val children: List<DropFolderItem> = emptyList(),
    val trigger: StoreFileTrigger? = null
)

@Serializable
data class StoreFileTrigger(
    val id: Int,
    val subPath: String,
    val recipients: List<RecipientEntry>
)

data class PendingStorageNodeRequest(
    val request: StorageNodeRequest,
    val chunk: MeshChunk,
    val fileId: String,
    val responses: MutableList<StorageNodeResponse> = mutableListOf(),
    var retries: Int = 0,
    val completion: CompletableDeferred<List<StorageNodeResponse>> = CompletableDeferred()
)