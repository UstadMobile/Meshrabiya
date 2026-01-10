package com.ustadmobile.meshrabiya.vnet

/**
 * Response containing chunk retrieval metadata from storage nodes.
 * Used in distributed storage chunk retrieval operations.
 */
data class ChunkRetrievalResponse(
    val chunkId: String,
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val nodeId: String,
    val fileName: String,
    val relativePath: String,
    val chunkSize: Long
)
