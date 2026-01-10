package com.ustadmobile.meshrabiya.model

import kotlinx.serialization.Serializable

@Serializable
data class DeviceCapabilities(
    val memoryMB: Int,
    val storageMB: Int,
    val batteryLevel: Float,
    val isCharging: Boolean,
    val cpuCores: Int,
    val meshHops: Int,
    val deviceClass: DeviceClass,
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
