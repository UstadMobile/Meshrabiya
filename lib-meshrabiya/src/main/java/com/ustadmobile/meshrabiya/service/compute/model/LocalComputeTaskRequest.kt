package com.ustadmobile.meshrabiya.service.compute.model
import com.ustadmobile.meshrabiya.storage.RecipientEntry

/**
 * Represents a compute task request initiated by this local node.
 * Used to track client-side request state throughout the request-response-selection lifecycle.
 */
data class LocalComputeTaskRequest(
    val requestId: String,
    val taskId: String,
    val taskType: String,  // "ml-kit-native", "ml-kit-custom", etc.
    // val requiredMLFeatures: List<String> = emptyList(),  // Required ML Kit features
    val timestamp: Long = System.currentTimeMillis(),
    val recipients: List<RecipientEntry> = emptyList()
) {
    // Placeholder for actual MMCP message (will be properly implemented in future)
     val mmcpRequest: String
        get() = "$taskType:$taskId"
    
}
