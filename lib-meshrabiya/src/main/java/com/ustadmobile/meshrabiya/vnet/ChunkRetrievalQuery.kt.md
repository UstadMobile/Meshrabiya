package com.ustadmobile.meshrabiya.vnet

/**
 * Query to retrieve chunk information from storage nodes.
 * Used in distributed storage chunk retrieval operations.
 */
data class ChunkRetrievalQuery(
    val fileId: String
)
