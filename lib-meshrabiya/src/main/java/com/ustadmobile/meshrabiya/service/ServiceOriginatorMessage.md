package com.ustadmobile.meshrabiya.service

import com.ustadmobile.meshrabiya.model.DeviceCapabilities
import com.ustadmobile.meshrabiya.model.ServiceAnnouncement
import kotlinx.serialization.Serializable

/**
 * Enhanced originator message that includes service announcements
 * Extends existing BATMAN protocol with service discovery capability
 */
@Serializable
data class ServiceOriginatorMessage(
    val originalMessage: ByteArray, // Existing originator message
    val serviceAnnouncements: List<ServiceAnnouncement> = emptyList(),
    val deviceCapabilities: DeviceCapabilities,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun toString(): String {
        return "ServiceOriginatorMessage(services=${serviceAnnouncements.size}, capabilities=$deviceCapabilities)"
    }
}


@Serializable
data class DeviceCapabilities(
    val memoryMB: Int,
    val storageMB: Int,
    val batteryLevel: Float,
    val isCharging: Boolean,
    val cpuCores: Int,
    val meshHops: Int,
    val deviceClass: DeviceClass,
    
    // ML capabilities (three-tier)
    val mlKitFeatures: List<String> = emptyList(),
    val mlKitCustomSupport: Boolean = false,
    val hasLiteRT: Boolean = false,
    val hasGPUAcceleration: Boolean = false,
    val hasNNAPI: Boolean = false
) {
    enum class DeviceClass {
        CONSUMER,      // Uses services only
        ML_BASIC,      // Basic ML Kit + small LiteRT models
        ML_CAPABLE,    // ML Kit + custom models + medium LiteRT
        ML_POWERHOUSE  // All ML tiers + large models
    }
}

@Serializable
data class ResourceRequirements(
    val minMemoryMB: Int,
    val minStorageMB: Int,
    val gpuAcceleration: String = "none", // none, preferred, required
    val networkAccess: Boolean = false,
    val batteryIntensive: Boolean = false
)

@Serializable
data class ExecutionProfile(
    val averageExecutionTimeMs: Int,
    val maxExecutionTimeMs: Int = 30000,
    val deterministic: Boolean = true,
    val concurrent: Boolean = true,
    val idempotent: Boolean = true
)
