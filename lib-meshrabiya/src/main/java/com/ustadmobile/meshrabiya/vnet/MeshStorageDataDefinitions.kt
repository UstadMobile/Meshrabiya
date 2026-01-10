package com.ustadmobile.meshrabiya.vnet
import com.ustadmobile.meshrabiya.storage.FileReference
import java.io.File
import com.ustadmobile.meshrabiya.storage.RecipientEntry


/**
 * Represents a chunk of a file stored in the mesh network.
 * Stores recipient key IDs and session keys for per-chunk access control.
 */
data class MeshChunk(
    val chunkId: String,
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunkSize: Long,
    val fileName: String,
    val relativePath: String,
    val hash: String,
    val storedAt: Long = System.currentTimeMillis(),
    // val recipients: List<com.ustadmobile.meshrabiya.storage.RecipientEntry> = emptyList(),
    val sessionKeys: Map<String, ByteArray> = emptyMap(),
    // val owner: com.ustadmobile.meshrabiya.storage.RecipientEntry,
    /**
     * The number of times this chunk has been replicated. The canonical maximum is always
     * MeshrabiyaConstants.getReplicaCount().
     */
    var replicaCount: Int = 0,
    // val fileReference: FileReference,
    val serverPath: String
)

// data class MeshFile(
//     val fileId: String,
//     val fileName: String,
//     val fileSize: Long,
//     val storedAt: Long = System.currentTimeMillis(),
//     val owner: RecipientEntry,
//     val recipients: List<RecipientEntry> = emptyList(),
//     val relativePath: String = ""
// )
data class MeshFile(
    val fileId: String,
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val owner: RecipientEntry,
    val recipients: List<RecipientEntry>,
    val createdAt: Long,
    val relativePath: String
)


