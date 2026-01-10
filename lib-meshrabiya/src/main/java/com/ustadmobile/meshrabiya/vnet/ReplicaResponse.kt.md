package com.ustadmobile.meshrabiya.vnet

/**
 * Response containing replica status information.
 * Used for replica consistency verification in distributed storage.
 */
data class ReplicaResponse(
    val fileId: String
)
