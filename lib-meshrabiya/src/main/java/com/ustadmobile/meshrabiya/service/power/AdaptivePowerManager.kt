
package com.ustadmobile.meshrabiya.service.power

import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import android.util.Log
import android.os.BatteryManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import com.ustadmobile.meshrabiya.vnet.hardware.ThermalState  // Use canonical ThermalState

/**
 * ADAPTIVE POWER MANAGEMENT SYSTEM
 * 
 * User-configurable power management with intelligent defaults based on:
 * - Device profile (flagship, mid-range, budget)
 * - Current battery level and charging state
 * - Thermal conditions
 * - User preferences (slider controls)
 * 
 * Key Philosophy: Let users decide their comfort level, but provide safe defaults
 */
class AdaptivePowerManager(
    private val context: Context,
    private val emergentRoleManager: com.ustadmobile.meshrabiya.vnet.EmergentRoleManager
) {
    
    companion object {
        private const val TAG = "AdaptivePowerManager"
        private const val PREFS_NAME = "power_management"
        private const val THERMAL_CHECK_INTERVAL_MS = 30_000L // 30 seconds
        private const val BATTERY_CHECK_INTERVAL_MS = 60_000L // 1 minute
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * USER-CONFIGURABLE POWER SETTINGS
     * 
     * All settings exposed as sliders in UI with reasonable defaults
     */
    @Serializable
    data class PowerSettings(
        // Battery Impact Control (0-100%)
        val maxDailyBatteryImpact: Float = 10.0f,      // Default: 10% daily overhead
        val batteryThrottleThreshold: Float = 20.0f,   // Start throttling at 20% battery
        val batteryShutdownThreshold: Float = 10.0f,   // Stop all services at 10% battery
        
        // Thermal Management (0-100%)
        val thermalSensitivity: Float = 70.0f,          // Default: 70% - moderate sensitivity
        val maxCpuTemperature: Float = 45.0f,           // Celsius - safe for most devices
        val thermalThrottleAggression: Float = 50.0f,   // How aggressively to throttle
        
        // Service Priority (0-100%)
        val mlServicePriority: Float = 60.0f,           // ML services importance
        val storageServicePriority: Float = 80.0f,      // Storage services importance
        val meshRelayPriority: Float = 40.0f,           // Mesh relay importance
        
        // Power Saving Modes
        val enablePowerSavingModes: Boolean = true,
        val enableThermalProtection: Boolean = true,
        val enableBatteryOptimization: Boolean = true,
        
        // Advanced Settings
        val backgroundProcessingAllowed: Boolean = true,
        val chargingOnlyMode: Boolean = false,          // Only run when charging
        val wifiOnlyMode: Boolean = false               // Only run on WiFi
    )
    
    /**
     * DEVICE PROFILE DETECTION
     * 
     * Automatically detect device capabilities and set appropriate defaults
     */
    enum class DeviceProfile {
        FLAGSHIP,    // High-end device, can handle more load
        MID_RANGE,   // Balanced performance and efficiency
        BUDGET,      // Low-end device, minimize impact
        UNKNOWN      // Conservative defaults
    }
    
    @Serializable
    data class DeviceCapabilities(
        val profile: DeviceProfile,
        val totalRamMB: Long,
        val cpuCores: Int,
        val thermalThrottleTemp: Float,
        val batteryCapacityMah: Int,
        val supportsAdvancedThermals: Boolean
    )
    
    /**
     * REAL-TIME POWER STATE
     */
    @Serializable
    data class PowerState(
        val batteryLevel: Float,
        val isCharging: Boolean,
        val batteryTemperature: Float,
        val cpuTemperature: Float,
        val thermalState: ThermalState,
        val powerSavingMode: PowerSavingMode,
        val estimatedDailyBatteryUsage: Float,
        val canRunMLServices: Boolean,
        val canRunStorageServices: Boolean,
        val canRelayMeshTraffic: Boolean
    )
    
    // DEPRECATED: Use com.ustadmobile.meshrabiya.vnet.hardware.ThermalState (canonical)
    /*
    enum class ThermalState {
        COOL,           // < 35°C - Full performance
        WARM,           // 35-40°C - Minor throttling
        HOT,            // 40-45°C - Moderate throttling
        OVERHEATING,    // 45-50°C - Aggressive throttling
        CRITICAL        // > 50°C - Emergency shutdown
    }
    */
    
    enum class PowerSavingMode {
        FULL_PERFORMANCE,   // No restrictions
        BALANCED,           // Moderate restrictions
        POWER_SAVER,        // Aggressive power saving
        EMERGENCY           // Minimal services only
    }
    
    // Power state tracking
    private val _powerState = MutableStateFlow(PowerState(
        batteryLevel = 100f,
        isCharging = false,
        batteryTemperature = 25f,
        cpuTemperature = 25f,
        thermalState = ThermalState.COOL,
        powerSavingMode = PowerSavingMode.BALANCED,
        estimatedDailyBatteryUsage = 0f,
        canRunMLServices = true,
        canRunStorageServices = true,
        canRelayMeshTraffic = true
    ))
    val powerState: StateFlow<PowerState> = _powerState.asStateFlow()
    
    private val _powerSettings = MutableStateFlow(loadPowerSettings())
    val powerSettings: StateFlow<PowerSettings> = _powerSettings.asStateFlow()
    
    init {
        startPowerMonitoring()
    }
    
    /**
     * DEVICE PROFILE DETECTION
     * 
     * Automatically detect device capabilities for intelligent defaults
     */
    fun detectDeviceProfile(): DeviceCapabilities {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val totalRamMB = memInfo.totalMem / (1024 * 1024)
        val cpuCores = Runtime.getRuntime().availableProcessors()
        
        val profile = when {
            totalRamMB >= 8192 && cpuCores >= 8 -> DeviceProfile.FLAGSHIP
            totalRamMB >= 4096 && cpuCores >= 6 -> DeviceProfile.MID_RANGE
            totalRamMB >= 2048 -> DeviceProfile.BUDGET
            else -> DeviceProfile.UNKNOWN
        }
        
        val thermalThrottleTemp = when (profile) {
            DeviceProfile.FLAGSHIP -> 50f      // Can handle higher temps
            DeviceProfile.MID_RANGE -> 45f     // Moderate thermal limits
            DeviceProfile.BUDGET -> 40f        // Conservative thermal limits
            DeviceProfile.UNKNOWN -> 35f       // Very conservative
        }
        
        return DeviceCapabilities(
            profile = profile,
            totalRamMB = totalRamMB,
            cpuCores = cpuCores,
            thermalThrottleTemp = thermalThrottleTemp,
            batteryCapacityMah = getBatteryCapacity(),
            supportsAdvancedThermals = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        )
    }
    
    /**
     * INTELLIGENT DEFAULT SETTINGS
     * 
     * Set defaults based on device profile
     */
    fun getDefaultSettings(deviceProfile: DeviceProfile): PowerSettings {
        return when (deviceProfile) {
            DeviceProfile.FLAGSHIP -> PowerSettings(
                maxDailyBatteryImpact = 15.0f,          // Can afford higher impact
                thermalSensitivity = 50.0f,             // Less thermal sensitive
                maxCpuTemperature = 50.0f,              // Higher temp tolerance
                mlServicePriority = 80.0f,              // High ML priority
                thermalThrottleAggression = 30.0f       // Gentle throttling
            )
            
            DeviceProfile.MID_RANGE -> PowerSettings(
                maxDailyBatteryImpact = 10.0f,          // Balanced impact
                thermalSensitivity = 70.0f,             // Moderate sensitivity
                maxCpuTemperature = 45.0f,              // Moderate temp limit
                mlServicePriority = 60.0f,              // Medium ML priority
                thermalThrottleAggression = 50.0f       // Balanced throttling
            )
            
            DeviceProfile.BUDGET -> PowerSettings(
                maxDailyBatteryImpact = 5.0f,           // Minimal impact
                thermalSensitivity = 90.0f,             // High sensitivity
                maxCpuTemperature = 40.0f,              // Low temp limit
                mlServicePriority = 30.0f,              // Low ML priority
                thermalThrottleAggression = 80.0f       // Aggressive throttling
            )
            
            DeviceProfile.UNKNOWN -> PowerSettings(
                maxDailyBatteryImpact = 5.0f,           // Very conservative
                thermalSensitivity = 95.0f,             // Very sensitive
                maxCpuTemperature = 35.0f,              // Very low temp limit
                mlServicePriority = 20.0f,              // Minimal ML priority
                thermalThrottleAggression = 90.0f       // Very aggressive throttling
            )
        }
    }
    
    /**
     * REAL-TIME POWER MONITORING
     */
    private fun startPowerMonitoring() {
        // Battery monitoring
        scope.launch {
            while (isActive) {
                updateBatteryState()
                delay(BATTERY_CHECK_INTERVAL_MS)
            }
        }
        
        // Thermal monitoring
        scope.launch {
            while (isActive) {
                updateThermalState()
                delay(THERMAL_CHECK_INTERVAL_MS)
            }
        }
        
        // Power mode adjustment
        scope.launch {
            _powerState.collect { state ->
                adjustPowerMode(state)
                updateEmergentRoleManager(state)
            }
        }
    }
    
    private suspend fun updateBatteryState() {
        try {
            val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
            val isCharging = batteryManager.isCharging
            val batteryTempProp = try {
                BatteryManager::class.java.getField("BATTERY_PROPERTY_TEMPERATURE").getInt(null)
            } catch (e: Exception) {
                // Fallback to common constant value index (may not be present on older APIs)
                -1
            }

            val batteryTemp = if (batteryTempProp >= 0) {
                batteryManager.getIntProperty(batteryTempProp) / 10.0f
            } else {
                // If property not available, estimate or default
                25.0f
            }
            
            val currentState = _powerState.value
            val newState = currentState.copy(
                batteryLevel = batteryLevel,
                isCharging = isCharging,
                batteryTemperature = batteryTemp,
                estimatedDailyBatteryUsage = calculateDailyBatteryUsage()
            )
            
            _powerState.value = newState
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update battery state", e)
        }
    }
    
    private suspend fun updateThermalState() {
        try {
            val cpuTemp = getCpuTemperature()
            val thermalState = determineThermalState(cpuTemp)
            
            val currentState = _powerState.value
            val newState = currentState.copy(
                cpuTemperature = cpuTemp,
                thermalState = thermalState
            )
            
            _powerState.value = newState
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update thermal state", e)
        }
    }
    
    private fun determineThermalState(cpuTemp: Float): ThermalState {
        val settings = _powerSettings.value
        val maxTemp = settings.maxCpuTemperature
        
        return when {
            cpuTemp < maxTemp - 10f -> ThermalState.COOL
            cpuTemp < maxTemp - 5f -> ThermalState.WARM
            cpuTemp < maxTemp -> ThermalState.HOT
            cpuTemp < maxTemp + 5f -> ThermalState.THROTTLING  // Was OVERHEATING
            else -> ThermalState.CRITICAL
        }
    }
    
    /**
     * INTELLIGENT POWER MODE ADJUSTMENT
     */
    private suspend fun adjustPowerMode(state: PowerState) {
        val settings = _powerSettings.value
        
        val newPowerMode = when {
            // Emergency mode: Very low battery or critical temperature
            state.batteryLevel <= settings.batteryShutdownThreshold ||
            state.thermalState == ThermalState.CRITICAL -> PowerSavingMode.EMERGENCY
            
            // Power saver mode: Low battery or throttling (was overheating)
            state.batteryLevel <= settings.batteryThrottleThreshold ||
            state.thermalState == ThermalState.THROTTLING ||
            state.estimatedDailyBatteryUsage > settings.maxDailyBatteryImpact -> PowerSavingMode.POWER_SAVER
            
            // Balanced mode: Moderate conditions
            state.thermalState == ThermalState.HOT ||
            !state.isCharging -> PowerSavingMode.BALANCED
            
            // Full performance: Good conditions
            else -> PowerSavingMode.FULL_PERFORMANCE
        }
        
        if (newPowerMode != state.powerSavingMode) {
            val updatedState = state.copy(
                powerSavingMode = newPowerMode,
                canRunMLServices = canRunMLServices(newPowerMode, settings),
                canRunStorageServices = canRunStorageServices(newPowerMode, settings),
                canRelayMeshTraffic = canRelayMeshTraffic(newPowerMode, settings)
            )
            
            _powerState.value = updatedState
            Log.i(TAG, "Power mode changed to: $newPowerMode")
        }
    }
    
    /**
     * SERVICE CAPABILITY DETERMINATION
     */
    private fun canRunMLServices(powerMode: PowerSavingMode, settings: PowerSettings): Boolean {
        if (!settings.enablePowerSavingModes) return true
        
        return when (powerMode) {
            PowerSavingMode.FULL_PERFORMANCE -> true
            PowerSavingMode.BALANCED -> settings.mlServicePriority >= 50f
            PowerSavingMode.POWER_SAVER -> settings.mlServicePriority >= 80f
            PowerSavingMode.EMERGENCY -> false
        }
    }
    
    private fun canRunStorageServices(powerMode: PowerSavingMode, settings: PowerSettings): Boolean {
        if (!settings.enablePowerSavingModes) return true
        
        return when (powerMode) {
            PowerSavingMode.FULL_PERFORMANCE -> true
            PowerSavingMode.BALANCED -> settings.storageServicePriority >= 30f
            PowerSavingMode.POWER_SAVER -> settings.storageServicePriority >= 60f
            PowerSavingMode.EMERGENCY -> settings.storageServicePriority >= 90f
        }
    }
    
    private fun canRelayMeshTraffic(powerMode: PowerSavingMode, settings: PowerSettings): Boolean {
        if (!settings.enablePowerSavingModes) return true
        
        return when (powerMode) {
            PowerSavingMode.FULL_PERFORMANCE -> true
            PowerSavingMode.BALANCED -> settings.meshRelayPriority >= 40f
            PowerSavingMode.POWER_SAVER -> settings.meshRelayPriority >= 70f
            PowerSavingMode.EMERGENCY -> false
        }
    }
    
    /**
     * INTEGRATION WITH EMERGENT ROLE MANAGER
     */
    private suspend fun updateEmergentRoleManager(state: PowerState) {
        try {
            // Update role manager with current power constraints
            emergentRoleManager.updatePowerConstraints(
                canProvideMLInference = state.canRunMLServices,
                canProvideStorage = state.canRunStorageServices,
                canRelayTraffic = state.canRelayMeshTraffic,
                thermalState = state.thermalState.name,
                batteryLevel = state.batteryLevel.toInt(),
                powerSavingMode = state.powerSavingMode.name
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update EmergentRoleManager", e)
        }
    }
    
    /**
     * USER SETTINGS MANAGEMENT
     */
    fun updatePowerSettings(newSettings: PowerSettings) {
        _powerSettings.value = newSettings
        savePowerSettings(newSettings)
        Log.i(TAG, "Power settings updated by user")
    }
    
    private fun loadPowerSettings(): PowerSettings {
        // Load from SharedPreferences with defaults based on device profile
        val deviceProfile = detectDeviceProfile().profile
        val defaults = getDefaultSettings(deviceProfile)
        
        return PowerSettings(
            maxDailyBatteryImpact = prefs.getFloat("max_daily_battery_impact", defaults.maxDailyBatteryImpact),
            batteryThrottleThreshold = prefs.getFloat("battery_throttle_threshold", defaults.batteryThrottleThreshold),
            batteryShutdownThreshold = prefs.getFloat("battery_shutdown_threshold", defaults.batteryShutdownThreshold),
            thermalSensitivity = prefs.getFloat("thermal_sensitivity", defaults.thermalSensitivity),
            maxCpuTemperature = prefs.getFloat("max_cpu_temperature", defaults.maxCpuTemperature),
            thermalThrottleAggression = prefs.getFloat("thermal_throttle_aggression", defaults.thermalThrottleAggression),
            mlServicePriority = prefs.getFloat("ml_service_priority", defaults.mlServicePriority),
            storageServicePriority = prefs.getFloat("storage_service_priority", defaults.storageServicePriority),
            meshRelayPriority = prefs.getFloat("mesh_relay_priority", defaults.meshRelayPriority),
            enablePowerSavingModes = prefs.getBoolean("enable_power_saving_modes", defaults.enablePowerSavingModes),
            enableThermalProtection = prefs.getBoolean("enable_thermal_protection", defaults.enableThermalProtection),
            enableBatteryOptimization = prefs.getBoolean("enable_battery_optimization", defaults.enableBatteryOptimization),
            backgroundProcessingAllowed = prefs.getBoolean("background_processing_allowed", defaults.backgroundProcessingAllowed),
            chargingOnlyMode = prefs.getBoolean("charging_only_mode", defaults.chargingOnlyMode),
            wifiOnlyMode = prefs.getBoolean("wifi_only_mode", defaults.wifiOnlyMode)
        )
    }
    
    private fun savePowerSettings(settings: PowerSettings) {
        prefs.edit().apply {
            putFloat("max_daily_battery_impact", settings.maxDailyBatteryImpact)
            putFloat("battery_throttle_threshold", settings.batteryThrottleThreshold)
            putFloat("battery_shutdown_threshold", settings.batteryShutdownThreshold)
            putFloat("thermal_sensitivity", settings.thermalSensitivity)
            putFloat("max_cpu_temperature", settings.maxCpuTemperature)
            putFloat("thermal_throttle_aggression", settings.thermalThrottleAggression)
            putFloat("ml_service_priority", settings.mlServicePriority)
            putFloat("storage_service_priority", settings.storageServicePriority)
            putFloat("mesh_relay_priority", settings.meshRelayPriority)
            putBoolean("enable_power_saving_modes", settings.enablePowerSavingModes)
            putBoolean("enable_thermal_protection", settings.enableThermalProtection)
            putBoolean("enable_battery_optimization", settings.enableBatteryOptimization)
            putBoolean("background_processing_allowed", settings.backgroundProcessingAllowed)
            putBoolean("charging_only_mode", settings.chargingOnlyMode)
            putBoolean("wifi_only_mode", settings.wifiOnlyMode)
            apply()
        }
    }
    
    // === UTILITY METHODS ===
    
    private fun getBatteryCapacity(): Int {
        return try {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            3000 // Default assumption
        }
    }
    
    private fun getCpuTemperature(): Float {
        return try {
            // Try to read CPU temperature from thermal zones
            val thermalFiles = listOf(
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp",
                "/sys/devices/virtual/thermal/thermal_zone0/temp"
            )
            
            for (file in thermalFiles) {
                try {
                    val temp = java.io.File(file).readText().trim().toFloat() / 1000f // Convert from millidegrees
                    if (temp > 0 && temp < 100) return temp
                } catch (e: Exception) {
                    // Try next file
                }
            }
            
            // Fallback: estimate based on battery temperature
            _powerState.value.batteryTemperature + 5f
            
        } catch (e: Exception) {
            25f // Safe default
        }
    }
    
    private fun calculateDailyBatteryUsage(): Float {
        // TODO: Implement actual battery usage tracking
        // This would track battery drain over time and project daily usage
        return 5.0f // Placeholder
    }
}

/**
 * EXTENSION FOR EMERGENT ROLE MANAGER
 * 
 * Adds power management integration to EmergentRoleManager
 */
suspend fun com.ustadmobile.meshrabiya.vnet.EmergentRoleManager.updatePowerConstraints(
    canProvideMLInference: Boolean,
    canProvideStorage: Boolean,
    canRelayTraffic: Boolean,
    thermalState: String,
    batteryLevel: Int,
    powerSavingMode: String
) {
    try {
        // Update device capabilities based on power constraints
        // This would integrate with the existing EmergentRoleManager implementation
        
        // Example: Disable ML inference role if power constraints don't allow it
        if (!canProvideMLInference) {
            // Remove ML inference from available services
        }
        
        // Example: Reduce storage allocation if battery is low
        if (!canProvideStorage) {
            // Reduce or disable storage services
        }
        
        // Example: Stop mesh relay if power saving mode is aggressive
        if (!canRelayTraffic) {
            // Disable mesh relay functionality
        }
        
    } catch (e: Exception) {
        // Handle integration errors gracefully
    }
}
