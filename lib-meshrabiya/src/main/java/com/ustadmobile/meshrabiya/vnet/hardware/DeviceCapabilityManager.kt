package com.ustadmobile.meshrabiya.vnet.hardware

import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.mmcp.*
import com.ustadmobile.meshrabiya.vnet.NodeCapabilitySnapshot
import kotlinx.coroutines.flow.StateFlow
import java.time.Duration

/**
 * Interface for collecting device hardware capabilities and metrics.
 * 
 * Provides a clean abstraction for hardware monitoring that can be mocked
 * for testing and implemented differently across platforms.
 * 
 * Privacy Note: All metrics collection respects user consent levels and
 * can be disabled through BetaTestLogger settings.
 */
interface DeviceCapabilityManager {
    
    /**
     * Get current CPU utilization as a percentage (0.0-1.0)
     * @return CPU utilization where 1.0 = 100% usage
     */
    suspend fun getCpuUtilization(): Float
    
    /**
     * Get available RAM in bytes
     * @return Available memory in bytes
     */
    suspend fun getAvailableMemory(): Long
    
    /**
     * Get total RAM in bytes
     * @return Total system memory in bytes
     */
    suspend fun getTotalMemory(): Long
    
    /**
     * Get current battery information
     * @return Complete battery status including level, charging state, temperature
     */
    suspend fun getBatteryInfo(): BatteryInfo
    
    /**
     * Get current thermal state of the device
     * @return Thermal state from COOL to CRITICAL
     */
    suspend fun getThermalState(): ThermalState
    
    /**
     * Get estimated network bandwidth in bits per second
     * @return Estimated bandwidth based on current network connection
     */
    suspend fun getEstimatedBandwidth(): Long
    
    /**
     * Get all available network interfaces
     * @return Set of network interface information
     */
    suspend fun getNetworkInterfaces(): Set<SerializableNetworkInterfaceInfo>
    
    /**
     * Get device storage capabilities
     * @return Storage information including available space and characteristics
     */
    suspend fun getStorageCapabilities(): StorageCapabilities
    
    /**
     * Calculate device stability score based on uptime and connectivity history
     * @return Stability score from 0.0 (unstable) to 1.0 (very stable)
     */
    suspend fun getStabilityScore(): Float
    
    /**
     * Get comprehensive device capabilities snapshot
     * @param nodeId The node ID for this device
     * @return Complete capability snapshot for role assignment
     */
    suspend fun getCapabilitySnapshot(nodeId: String): NodeCapabilitySnapshot
    
    /**
     * Start continuous monitoring of device metrics
     * @param intervalMs Monitoring interval in milliseconds
     */
    fun startMonitoring(intervalMs: Long = 30000L)
    
    /**
     * Stop continuous monitoring
     */
    fun stopMonitoring()
    
    /**
     * Check if monitoring is currently active
     * @return true if monitoring is running
     */
    fun isMonitoring(): Boolean
}

/**
 * Exception thrown when hardware metrics collection fails
 */
class HardwareMetricsException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Data class for CPU metrics
 */
data class CpuMetrics(
    val utilization: Float, // 0.0-1.0
    val cores: Int,
    val frequency: Long, // Hz
    val architecture: String
)

/**
 * Data class for memory metrics
 */
data class MemoryMetrics(
    val totalMemory: Long, // bytes
    val availableMemory: Long, // bytes
    val usedMemory: Long, // bytes
    val utilization: Float // 0.0-1.0
)

/**
 * Data class for network metrics
 */
data class NetworkMetrics(
    val estimatedBandwidth: Long, // bits per second
    val connectionType: NetworkConnectionType,
    val signalStrength: Int, // percentage 0-100
    val isMetered: Boolean
)

/**
 * Network connection types
 */
enum class NetworkConnectionType {
    WIFI,
    CELLULAR,
    ETHERNET,
    BLUETOOTH,
    VPN,
    UNKNOWN
}

/**
 * Device stability factors
 */
data class StabilityMetrics(
    val uptime: Duration,
    val connectivityScore: Float, // 0.0-1.0 based on connection stability
    val crashCount: Int, // crashes in last 24 hours
    val thermalThrottlingFrequency: Float, // 0.0-1.0 based on recent throttling
    val batteryHealthScore: Float // 0.0-1.0 based on battery condition
)
