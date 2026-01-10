package com.ustadmobile.meshrabiya.model
import kotlinx.serialization.Serializable
import com.ustadmobile.meshrabiya.vnet.hardware.ThermalState

// data class ResourceRequirements(
//     val minMemoryMB: Int = 0,
//     val minStorageMB: Int = 0,
//     val minCpuCores: Int = 1,
//     val minGpu: Boolean = false
// )
@Serializable
data class ResourceRequirements(
    val minRAMMB: Int,
    val preferredRAMMB: Int,
    val requiresGPU: Boolean = false,
    val requiresNPU: Boolean = false,
    val requiresStorage: Boolean = false,
    val minStorageMB: Float = 0f,
    val minCpuCores: Int = 1,
    val minGpu: Boolean = false,
    val thermalConstraints: Set<ThermalState> = setOf(ThermalState.COOL, ThermalState.WARM, ThermalState.HOT, ThermalState.CRITICAL),  // Changed COLD to COOL
    val maxNetworkLatencyMs: Int = 1000,
    val minBatteryLevel: Int = 25
)
