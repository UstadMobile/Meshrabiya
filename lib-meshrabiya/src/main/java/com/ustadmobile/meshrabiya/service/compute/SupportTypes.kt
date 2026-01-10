package com.ustadmobile.meshrabiya.service.compute

import com.ustadmobile.meshrabiya.vnet.hardware.ThermalState
import kotlinx.serialization.Serializable

/**
 * SupportTypes
 * 
 * Shared lightweight support types used across compute code.
 * This file consolidates ResourceRequirements, PythonLibrary, InferenceConfig,
 * Precision, CPUIntensity definitions.
 * 
 * Note: ThermalState is now imported from hardware package (canonical version).
 * Note: Other files (JobTypes.kt, ResourceRequirements.kt, ServiceManifest.kt) should import these types instead of redefining them.
 *
 * MIGRATION NOTE: LibraryEntry.kt is deprecated and replaced by ServiceLibraryEntry. Do not use LibraryEntry in new code.
 */

// data class ResourceRequirements(
//     @Serializable
//     val minRAMMB: Int,
//     val preferredRAMMB: Int,
//     val cpuIntensity: CPUIntensity,
//     val requiresGPU: Boolean = false,
//     val requiresNPU: Boolean = false,
//     val requiresStorage: Boolean = false,
//     val minStorageGB: Float = 0f,
//     val thermalConstraints: Set<ThermalState> = setOf(ThermalState.COOL, ThermalState.WARM, ThermalState.HOT, ThermalState.CRITICAL),  // Changed COLD to COOL
//     val maxNetworkLatencyMs: Int = 1000,
//     val minBatteryLevel: Int = 25
// )

enum class PythonLibrary {
    NUMPY, PANDAS, OPENCV, PILLOW, SCIKIT_LEARN,
    MATPLOTLIB, SCIPY, REQUESTS, JSON, BASE64, TORCH, TENSORFLOW
}

data class InferenceConfig(
    val batchSize: Int = 1,
    val precision: Precision = Precision.QUANTIZED
)

enum class Precision { FLOAT32, FLOAT16, QUANTIZED }

enum class CPUIntensity { LIGHT, MODERATE, HEAVY, BURST }

// REMOVED: enum class ThermalState - now using hardware.ThermalState (canonical)