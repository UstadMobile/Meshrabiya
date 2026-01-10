package com.ustadmobile.meshrabiya.vnet

/**
 * Query to check replica status in distributed storage.
 * Used for replica consistency verification.
 */
data class ReplicaQuery(
    val fileId: String
)
