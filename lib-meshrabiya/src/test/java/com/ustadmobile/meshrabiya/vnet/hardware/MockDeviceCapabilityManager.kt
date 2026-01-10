package com.ustadmobile.meshrabiya.vnet.hardware

import com.ustadmobile.meshrabiya.mmcp.*
import com.ustadmobile.meshrabiya.vnet.NodeCapabilitySnapshot
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.random.Random

/**
 * Mock implementation of DeviceCapabilityManager for testing and simulation.
 * 
 * Provides configurable device characteristics and realistic metric variations
 * suitable for comprehensive testing of mesh role assignment algorithms.
 */
class MockDeviceCapabilityManager(
    private val deviceProfile: DeviceProfile = DeviceProfile.BALANCED_SMARTPHONE
) : DeviceCapabilityManager {
    
    companion object {
        private const val MONITORING_INTERVAL_MS = 1000L
    }
    
    private var isMonitoringActive = false
    private var monitoringStartTime = 0L
    private val random = Random(System.currentTimeMillis())
    
    // Configurable device characteristics
    private var baseConfig = deviceProfile.toConfiguration()
    
    /**
     * Predefined device profiles for testing different scenarios
     */
    enum class DeviceProfile {
        HIGH_END_SMARTPHONE,
        BALANCED_SMARTPHONE, 
        LOW_END_SMARTPHONE,
        TABLET,
        LAPTOP_TETHERED,
        IOT_DEVICE,
        OVERHEATING_DEVICE,
        LOW_BATTERY_DEVICE,
        UNSTABLE_NETWORK_DEVICE
    }
    
    private data class DeviceConfiguration(
        val baseCpuUtilization: Float,
        val cpuVariation: Float,
        val totalMemory: Long,
        val memoryUtilization: Float,
        val baseBatteryLevel: Int,
        val batteryDrainRate: Float, // % per hour
        val thermalState: ThermalState,
        val thermalVariation: Boolean,
        val networkBandwidth: Long,
        val networkQuality: Float,
        val baseStability: Float,
        val stabilityVariation: Float,
        val storageCapacity: Long,
        val isCharging: Boolean
    )
    
    private fun DeviceProfile.toConfiguration(): DeviceConfiguration = when (this) {
        DeviceProfile.HIGH_END_SMARTPHONE -> DeviceConfiguration(
            baseCpuUtilization = 0.2f,
            cpuVariation = 0.1f,
            totalMemory = 8L * 1024 * 1024 * 1024, // 8 GB
            memoryUtilization = 0.4f,
            baseBatteryLevel = 85,
            batteryDrainRate = 5.0f,
            thermalState = ThermalState.COOL,
            thermalVariation = false,
            networkBandwidth = 100_000_000L, // 100 Mbps
            networkQuality = 0.95f,
            baseStability = 0.9f,
            stabilityVariation = 0.05f,
            storageCapacity = 100L * 1024 * 1024 * 1024, // 100 GB
            isCharging = false
        )
        
        DeviceProfile.BALANCED_SMARTPHONE -> DeviceConfiguration(
            baseCpuUtilization = 0.4f,
            cpuVariation = 0.2f,
            totalMemory = 4L * 1024 * 1024 * 1024, // 4 GB
            memoryUtilization = 0.6f,
            baseBatteryLevel = 60,
            batteryDrainRate = 8.0f,
            thermalState = ThermalState.WARM,
            thermalVariation = true,
            networkBandwidth = 50_000_000L, // 50 Mbps
            networkQuality = 0.8f,
            baseStability = 0.75f,
            stabilityVariation = 0.1f,
            storageCapacity = 20L * 1024 * 1024 * 1024, // 20 GB
            isCharging = false
        )
        
        DeviceProfile.LOW_END_SMARTPHONE -> DeviceConfiguration(
            baseCpuUtilization = 0.7f,
            cpuVariation = 0.2f,
            totalMemory = 2L * 1024 * 1024 * 1024, // 2 GB
            memoryUtilization = 0.8f,
            baseBatteryLevel = 35,
            batteryDrainRate = 12.0f,
            thermalState = ThermalState.HOT,
            thermalVariation = true,
            networkBandwidth = 10_000_000L, // 10 Mbps
            networkQuality = 0.6f,
            baseStability = 0.6f,
            stabilityVariation = 0.15f,
            storageCapacity = 5L * 1024 * 1024 * 1024, // 5 GB
            isCharging = false
        )
        
        DeviceProfile.TABLET -> DeviceConfiguration(
            baseCpuUtilization = 0.3f,
            cpuVariation = 0.15f,
            totalMemory = 6L * 1024 * 1024 * 1024, // 6 GB
            memoryUtilization = 0.5f,
            baseBatteryLevel = 70,
            batteryDrainRate = 6.0f,
            thermalState = ThermalState.COOL,
            thermalVariation = false,
            networkBandwidth = 80_000_000L, // 80 Mbps WiFi
            networkQuality = 0.9f,
            baseStability = 0.85f,
            stabilityVariation = 0.08f,
            storageCapacity = 50L * 1024 * 1024 * 1024, // 50 GB
            isCharging = true
        )
        
        DeviceProfile.LAPTOP_TETHERED -> DeviceConfiguration(
            baseCpuUtilization = 0.25f,
            cpuVariation = 0.1f,
            totalMemory = 16L * 1024 * 1024 * 1024, // 16 GB
            memoryUtilization = 0.3f,
            baseBatteryLevel = 100, // Always plugged in
            batteryDrainRate = 0.0f,
            thermalState = ThermalState.COOL,
            thermalVariation = false,
            networkBandwidth = 1_000_000_000L, // 1 Gbps Ethernet
            networkQuality = 0.98f,
            baseStability = 0.95f,
            stabilityVariation = 0.02f,
            storageCapacity = 500L * 1024 * 1024 * 1024, // 500 GB
            isCharging = true
        )
        
        DeviceProfile.IOT_DEVICE -> DeviceConfiguration(
            baseCpuUtilization = 0.15f,
            cpuVariation = 0.05f,
            totalMemory = 512L * 1024 * 1024, // 512 MB
            memoryUtilization = 0.7f,
            baseBatteryLevel = 90,
            batteryDrainRate = 2.0f, // Very efficient
            thermalState = ThermalState.COOL,
            thermalVariation = false,
            networkBandwidth = 1_000_000L, // 1 Mbps
            networkQuality = 0.7f,
            baseStability = 0.9f,
            stabilityVariation = 0.05f,
            storageCapacity = 1L * 1024 * 1024 * 1024, // 1 GB
            isCharging = false
        )
        
        DeviceProfile.OVERHEATING_DEVICE -> DeviceConfiguration(
            baseCpuUtilization = 0.9f, // High usage causing heat
            cpuVariation = 0.05f,
            totalMemory = 4L * 1024 * 1024 * 1024,
            memoryUtilization = 0.85f,
            baseBatteryLevel = 45,
            batteryDrainRate = 15.0f,
            thermalState = ThermalState.CRITICAL,
            thermalVariation = true,
            networkBandwidth = 20_000_000L,
            networkQuality = 0.5f, // Thermal throttling affects network
            baseStability = 0.4f,
            stabilityVariation = 0.2f,
            storageCapacity = 10L * 1024 * 1024 * 1024,
            isCharging = false
        )
        
        DeviceProfile.LOW_BATTERY_DEVICE -> DeviceConfiguration(
            baseCpuUtilization = 0.3f,
            cpuVariation = 0.1f,
            totalMemory = 3L * 1024 * 1024 * 1024,
            memoryUtilization = 0.6f,
            baseBatteryLevel = 8, // Critical battery
            batteryDrainRate = 20.0f,
            thermalState = ThermalState.WARM,
            thermalVariation = false,
            networkBandwidth = 30_000_000L,
            networkQuality = 0.65f,
            baseStability = 0.5f, // Unstable due to power management
            stabilityVariation = 0.15f,
            storageCapacity = 15L * 1024 * 1024 * 1024,
            isCharging = false
        )
        
        DeviceProfile.UNSTABLE_NETWORK_DEVICE -> DeviceConfiguration(
            baseCpuUtilization = 0.35f,
            cpuVariation = 0.15f,
            totalMemory = 4L * 1024 * 1024 * 1024,
            memoryUtilization = 0.5f,
            baseBatteryLevel = 65,
            batteryDrainRate = 7.0f,
            thermalState = ThermalState.WARM,
            thermalVariation = false,
            networkBandwidth = 40_000_000L,
            networkQuality = 0.3f, // Poor network quality
            baseStability = 0.3f, // Very unstable
            stabilityVariation = 0.25f,
            storageCapacity = 25L * 1024 * 1024 * 1024,
            isCharging = false
        )
    }
    
    override suspend fun getCpuUtilization(): Float {
        val variation = (random.nextFloat() - 0.5f) * 2 * baseConfig.cpuVariation
        return (baseConfig.baseCpuUtilization + variation).coerceIn(0.0f, 1.0f)
    }
    
    override suspend fun getAvailableMemory(): Long {
        val usedMemory = (baseConfig.totalMemory * baseConfig.memoryUtilization).toLong()
        val variation = (random.nextFloat() - 0.5f) * baseConfig.totalMemory * 0.1f
        return (baseConfig.totalMemory - usedMemory + variation.toLong()).coerceAtLeast(0L)
    }
    
    override suspend fun getTotalMemory(): Long = baseConfig.totalMemory
    
    override suspend fun getBatteryInfo(): BatteryInfo {
        val currentTime = System.currentTimeMillis()
        val hoursElapsed = if (monitoringStartTime > 0) {
            (currentTime - monitoringStartTime) / (3600 * 1000.0)
        } else {
            0.0
        }
        
        val batteryDrain = if (!baseConfig.isCharging) {
            (baseConfig.batteryDrainRate * hoursElapsed).toInt()
        } else {
            -(baseConfig.batteryDrainRate * hoursElapsed * 2).toInt() // Charges faster than drains
        }
        
        val currentLevel = (baseConfig.baseBatteryLevel - batteryDrain).coerceIn(0, 100)
        
        // Temperature varies with CPU usage and thermal state
        val baseTemp = when (baseConfig.thermalState) {
            ThermalState.COOL -> 25
            ThermalState.WARM -> 35
            ThermalState.HOT -> 42
            ThermalState.CRITICAL -> 50
            ThermalState.THROTTLING -> 55
        }
        
        val tempVariation = if (baseConfig.thermalVariation) {
            (random.nextFloat() - 0.5f) * 10
        } else {
            0f
        }
        
        val temperature = (baseTemp + tempVariation).toInt().coerceIn(-10, 70)
        
        val health = when {
            temperature > 50 -> BatteryHealth.POOR
            currentLevel < 10 && !baseConfig.isCharging -> BatteryHealth.POOR
            else -> BatteryHealth.GOOD
        }
        
        return BatteryInfo(
            level = currentLevel,
            isCharging = baseConfig.isCharging,
            estimatedTimeRemaining = if (!baseConfig.isCharging && baseConfig.batteryDrainRate > 0) {
                Duration.parse("${(currentLevel / baseConfig.batteryDrainRate).toLong()}h")
            } else null,
            temperatureCelsius = temperature,
            health = health,
            chargingSource = if (baseConfig.isCharging) ChargingSource.USB else null
        )
    }
    
    override suspend fun getThermalState(): ThermalState {
        return if (baseConfig.thermalVariation && random.nextFloat() < 0.2f) {
            // 20% chance of thermal state variation
            val states = ThermalState.values()
            val currentIndex = states.indexOf(baseConfig.thermalState)
            val variation = if (random.nextBoolean()) 1 else -1
            val newIndex = (currentIndex + variation).coerceIn(0, states.size - 1)
            states[newIndex]
        } else {
            baseConfig.thermalState
        }
    }
    
    override suspend fun getEstimatedBandwidth(): Long {
        val variation = (random.nextFloat() - 0.5f) * baseConfig.networkBandwidth * 0.3f
        return (baseConfig.networkBandwidth + variation.toLong()).coerceAtLeast(1000L)
    }
    
    override suspend fun getNetworkInterfaces(): Set<SerializableNetworkInterfaceInfo> {
        val interfaces = mutableSetOf<SerializableNetworkInterfaceInfo>()
        
        // Always include loopback
        interfaces.add(SerializableNetworkInterfaceInfo(
            name = "lo",
            displayName = "Loopback Interface",
            mtu = 65536,
            isLoopback = true,
            supportsMulticast = false,
            isPointToPoint = false,
            isVirtual = false,
            interfaceAddresses = listOf("127.0.0.1/8", "::1/128"),
            inetAddresses = listOf("127.0.0.1", "::1")
        ))
        
        // Add WiFi interface based on device profile
        if (baseConfig.networkBandwidth > 5_000_000L) { // Only if good network
            interfaces.add(SerializableNetworkInterfaceInfo(
                name = "wlan0",
                displayName = "WiFi Interface",
                mtu = 1500,
                isLoopback = false,
                supportsMulticast = true,
                isPointToPoint = false,
                isVirtual = false,
                interfaceAddresses = listOf("192.168.1.${100 + random.nextInt(50)}/24"),
                inetAddresses = listOf("192.168.1.${100 + random.nextInt(50)}")
            ))
        }
        
        // Add cellular interface for mobile devices
        if (baseConfig.totalMemory < 8L * 1024 * 1024 * 1024) { // Mobile device heuristic
            interfaces.add(SerializableNetworkInterfaceInfo(
                name = "rmnet0",
                displayName = "Cellular Interface",
                mtu = 1500,
                isLoopback = false,
                supportsMulticast = false,
                isPointToPoint = true,
                isVirtual = false,
                interfaceAddresses = listOf("10.${random.nextInt(256)}.${random.nextInt(256)}.${random.nextInt(256)}/24"),
                inetAddresses = listOf("10.${random.nextInt(256)}.${random.nextInt(256)}.${random.nextInt(256)}")
            ))
        }
        
        return interfaces
    }
    
    override suspend fun getStorageCapabilities(): StorageCapabilities {
        val offeredStorage = (baseConfig.storageCapacity * 0.4f).toLong() // Offer 40% of total
        val availableMB = (baseConfig.storageCapacity * 0.7f / (1024 * 1024)).toLong() // 70% available
        
        return StorageCapabilities(
            totalOffered = offeredStorage,
            localStorageAvailableMB = availableMB,
            compressionSupported = true,
            encryptionSupported = true
            // REMOVED: replicationFactor (handled by storage manager)
            // REMOVED: currentlyUsed (less useful than localStorageAvailableMB)
            // REMOVED: accessPatterns (incorporated into fitness via I/O benchmarking)
        )
    }
    
    override suspend fun getStabilityScore(): Float {
        val variation = (random.nextFloat() - 0.5f) * 2 * baseConfig.stabilityVariation
        return (baseConfig.baseStability + variation).coerceIn(0.0f, 1.0f)
    }
    
    override suspend fun getCapabilitySnapshot(nodeId: String): NodeCapabilitySnapshot {
        val cpuUtilization = getCpuUtilization()
        val availableMemory = getAvailableMemory()
        val batteryInfo = getBatteryInfo()
        val thermalState = getThermalState()
        val bandwidth = getEstimatedBandwidth()
        val networkInterfaces = getNetworkInterfaces()
        val storageCapabilities = getStorageCapabilities()
        val stabilityScore = getStabilityScore()
        
        val powerState = when {
            batteryInfo.level > 70 -> PowerState.BATTERY_HIGH
            batteryInfo.level > 30 -> PowerState.BATTERY_MEDIUM
            batteryInfo.level > 10 -> PowerState.BATTERY_LOW
            else -> PowerState.BATTERY_CRITICAL
        }
        
        val resources = ResourceCapabilities(
            availableCPU = 1.0f - cpuUtilization,
            availableRAM = availableMemory,
            availableBandwidth = bandwidth,
            storageOffered = storageCapabilities.totalOffered,
            batteryLevel = batteryInfo.level,
            thermalThrottling = thermalState == ThermalState.HOT || thermalState == ThermalState.CRITICAL,
            powerState = powerState,
            networkInterfaces = networkInterfaces
        )
        
        return NodeCapabilitySnapshot(
            nodeId = nodeId,
            resources = resources,
            batteryInfo = batteryInfo,
            thermalState = thermalState,
            networkQuality = baseConfig.networkQuality,
            stability = stabilityScore
        )
    }
    
    override fun startMonitoring(intervalMs: Long) {
        if (!isMonitoringActive) {
            isMonitoringActive = true
            monitoringStartTime = System.currentTimeMillis()
        }
    }
    
    override fun stopMonitoring() {
        isMonitoringActive = false
        monitoringStartTime = 0L
    }
    
    override fun isMonitoring(): Boolean = isMonitoringActive
    
    /**
     * Simulate device state changes for testing
     */
    fun simulateStateChange(change: StateChange) {
        when (change) {
            StateChange.OVERHEAT -> {
                baseConfig = baseConfig.copy(
                    thermalState = ThermalState.CRITICAL,
                    baseCpuUtilization = 0.9f,
                    baseStability = 0.3f
                )
            }
            StateChange.LOW_BATTERY -> {
                baseConfig = baseConfig.copy(
                    baseBatteryLevel = 5,
                    batteryDrainRate = 25.0f
                )
            }
            StateChange.NETWORK_LOSS -> {
                baseConfig = baseConfig.copy(
                    networkBandwidth = 0L,
                    networkQuality = 0.0f,
                    baseStability = 0.2f
                )
            }
            StateChange.CHARGING_CONNECTED -> {
                baseConfig = baseConfig.copy(
                    isCharging = true,
                    batteryDrainRate = -10.0f // Charging
                )
            }
            StateChange.PERFORMANCE_MODE -> {
                baseConfig = baseConfig.copy(
                    baseCpuUtilization = 0.1f,
                    thermalState = ThermalState.COOL,
                    networkBandwidth = baseConfig.networkBandwidth * 2,
                    baseStability = 0.95f
                )
            }
        }
    }
    
    enum class StateChange {
        OVERHEAT,
        LOW_BATTERY,
        NETWORK_LOSS,
        CHARGING_CONNECTED,
        PERFORMANCE_MODE
    }
}
