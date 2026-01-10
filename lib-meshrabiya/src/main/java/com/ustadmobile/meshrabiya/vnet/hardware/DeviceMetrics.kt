package com.ustadmobile.meshrabiya.vnet.hardware

import kotlinx.serialization.Serializable
import java.net.NetworkInterface
import kotlin.time.Duration

/**
 * Canonical device hardware capability metrics for emergent role management.
 * 
 * EXTRACTION NOTE: These types were originally defined in EnhancedGossipMessage.kt
 * which has been deprecated to .md. The gossip protocol was a false start, but these
 * device capability types are essential for compute task distribution, storage management,
 * and mesh role assignment.
 * 
 * Extracted: 2025-01-15
 * Original: mmcp/EnhancedGossipMessage.kt.md (now deprecated)
 * Used by: EmergentRoleManager.kt, compute service, storage service
 */

/**
 * Device resource capabilities for role assignment and task distribution.
 * 
 * Used to assess node fitness for mesh roles (STORAGE_NODE, COMPUTE_NODE, etc.)
 */
@Serializable
data class ResourceCapabilities(
    val availableCPU: Float, // 0.0-1.0 normalized
    val availableRAM: Long, // bytes
    val availableBandwidth: Long, // bytes/sec
    val storageOffered: Long, // bytes
    val batteryLevel: Int, // 0-100 percentage
    val thermalThrottling: Boolean,
    val powerState: PowerState,
    val networkInterfaces: Set<SerializableNetworkInterfaceInfo>
)

/**
 * Serializable representation of NetworkInterface for mesh communication.
 * Allows network interface information to be shared across mesh nodes.
 */
@Serializable
data class SerializableNetworkInterfaceInfo(
    val name: String,
    val displayName: String?,
    val mtu: Int,
    val isLoopback: Boolean,
    val supportsMulticast: Boolean,
    val isPointToPoint: Boolean,
    val isVirtual: Boolean,
    val interfaceAddresses: List<String>, // String representation of addresses
    val inetAddresses: List<String> // String representation of addresses
)

/**
 * Battery state information for power-aware role management.
 * 
 * Informs decisions about taking on power-intensive roles (COMPUTE_NODE, MESH_ROUTER).
 */
@Serializable
data class BatteryInfo(
    val level: Int, // 0-100 percentage
    val isCharging: Boolean,
    val estimatedTimeRemaining: Duration?,
    val temperatureCelsius: Int,
    val health: BatteryHealth,
    val chargingSource: ChargingSource?
)

/**
 * Battery health status.
 */
@Serializable
enum class BatteryHealth {
    GOOD, DEGRADED, POOR
}

/**
 * Charging source type.
 */
@Serializable
enum class ChargingSource {
    AC, USB, WIRELESS, UNKNOWN
}

/**
 * Device power state for role eligibility assessment.
 * 
 * Nodes in POWER_SAVE_MODE or BATTERY_CRITICAL should avoid compute/routing roles.
 */
@Serializable
enum class PowerState {
    PLUGGED_IN,       // AC power - can take any role
    BATTERY_HIGH,     // >70% - can take any role
    BATTERY_MEDIUM,   // 30-70% - normal operation
    BATTERY_LOW,      // 15-30% - avoid compute roles
    BATTERY_CRITICAL, // <15% - minimal participation only
    POWER_SAVE_MODE   // User-enabled power saving
}

/**
 * Thermal state for throttling-aware role management.
 * 
 * Nodes in THROTTLING or CRITICAL should drop compute roles to cool down.
 */
@Serializable
/**
 * Canonical ThermalState used across the entire Meshrabiya codebase.
 * Thresholds aligned with AdaptivePowerManager for consistency.
 */
enum class ThermalState {
    COOL,       // < 35°C - Full performance, optimal for all roles
    WARM,       // 35-40°C - Minor throttling, normal operation
    HOT,        // 40-45°C - Moderate throttling, reduce compute load
    THROTTLING, // 45-50°C - Aggressive throttling (was OVERHEATING)
    CRITICAL    // > 50°C - Emergency shutdown/minimal participation
}

/**
 * Helper function to convert NetworkInterface to serializable form.
 * 
 * Safely handles exceptions when querying interface properties.
 */
fun NetworkInterface.toSerializable(): SerializableNetworkInterfaceInfo {
    return SerializableNetworkInterfaceInfo(
        name = this.name,
        displayName = this.displayName,
        mtu = runCatching { this.mtu }.getOrDefault(-1),
        isLoopback = runCatching { this.isLoopback }.getOrDefault(false),
        supportsMulticast = runCatching { this.supportsMulticast() }.getOrDefault(false),
        isPointToPoint = runCatching { this.isPointToPoint }.getOrDefault(false),
        isVirtual = runCatching { this.isVirtual }.getOrDefault(false),
        interfaceAddresses = runCatching { 
            this.interfaceAddresses.map { it.toString() } 
        }.getOrDefault(emptyList()),
        inetAddresses = runCatching { 
            this.inetAddresses.toList().map { it.toString() } 
        }.getOrDefault(emptyList())
    )
}

/**
 * Helper function to get all network interfaces in serializable form.
 * 
 * Used by DeviceCapabilityManager to collect network interface information.
 */
fun getNetworkInterfaces(): Set<SerializableNetworkInterfaceInfo> {
    return try {
        NetworkInterface.getNetworkInterfaces()?.toList()?.mapNotNull { ni ->
            try {
                ni.toSerializable()
            } catch (e: Exception) {
                null // Skip interfaces that fail to serialize
            }
        }?.toSet() ?: emptySet()
    } catch (e: Exception) {
        emptySet()
    }
}
