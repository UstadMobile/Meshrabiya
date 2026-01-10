package com.ustadmobile.meshrabiya.service.compute.model

import com.ustadmobile.meshrabiya.service.compute.ResourceRequirements
import com.ustadmobile.meshrabiya.service.compute.CPUIntensity
import com.ustadmobile.meshrabiya.vnet.hardware.ThermalState  // Changed to hardware package

/**
 * This file re-exports ResourceRequirements, CPUIntensity, and ThermalState
 * from SupportTypes.kt to maintain backward compatibility.
 *
 * Note: The actual definitions are in:
 * Meshrabiya/lib-meshrabiya/src/main/java/com/ustadmobile/meshrabiya/service/compute/SupportTypes.kt
 */

// Re-export types for backward compatibility

typealias CPUIntensityCompat = CPUIntensity  
typealias ThermalStateCompat = ThermalState

/**
 * Output schema for a compute task.
 * Describes format, expected size, and field schema.
 */
data class OutputSchema(
    val format: OutputFormat,
    val expectedSizeBytes: Long,
    val schema: Map<String, String>
)

/**
 * Enum representing output format for a compute task.
 */
enum class OutputFormat {
    JSON,
    BINARY,
    IMAGE,
    TENSOR,
    CSV
}

// DEPRECATED: StorageOperation enum - November 14, 2025
// This was part of the deprecated DistributedStorageAgent prototype
// Use DistributedStorageManager directly for storage operations
/*
enum class StorageOperation {
    STORE,
    RETRIEVE,
    DELETE,
    REPLICATE,
    VERIFY
}
*/