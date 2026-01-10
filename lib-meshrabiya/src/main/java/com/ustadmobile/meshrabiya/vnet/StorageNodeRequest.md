package com.ustadmobile.meshrabiya.vnet

data class StorageNodeRequest(
    val requestId: String,
    val chunkId: String,
    val chunkIndex: Int,
    val fileId: String,
    val desiredReplicas: Int? = null,
    val chunkSizeBytes: Long,
    val replicaCount: Int = 0,
    // Legacy fields for backward compatibility
    val requiredSpace: Long = chunkSizeBytes,
    val fileName: String = "",
    val senderId: String = ""
)