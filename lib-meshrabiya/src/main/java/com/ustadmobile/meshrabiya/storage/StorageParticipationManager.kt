
package com.ustadmobile.meshrabiya.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel

/**
 * Manages storage participation UI settings and user preferences
 * Integrates with your excellent UI design for storage configuration
 */
class StorageParticipationManager(
    private val context: Context,
    private val distributedStorageManager: DistributedStorageManager
) {
    companion object {
        private const val PREFS_NAME = "storage_participation"
        private const val KEY_PARTICIPATION_ENABLED = "participation_enabled"
        private const val KEY_STORAGE_ALLOCATIONS = "storage_allocations"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // BetaTestLogger integration for comprehensive logging
    private val betaLogger = BetaTestLogger.getInstance(context)
    
    // UI State tracking
    private val _participationEnabled = MutableStateFlow(false)
    val participationEnabled: StateFlow<Boolean> = _participationEnabled.asStateFlow()
    
    private val _storageAllocations = MutableStateFlow<List<StorageAllocation>>(emptyList())
    val storageAllocations: StateFlow<List<StorageAllocation>> = _storageAllocations.asStateFlow()
    
    private val _availableStorageDevices = MutableStateFlow<List<StorageDevice>>(emptyList())
    val availableStorageDevices: StateFlow<List<StorageDevice>> = _availableStorageDevices.asStateFlow()
    
    init {
        betaLogger.log(LogLevel.DEBUG, "Storage", "StorageParticipationManager initializing")
        loadSettings()
        detectStorageDevices()
        betaLogger.log(LogLevel.INFO, "Storage", "StorageParticipationManager initialized")
    }
    
    /**
     * Enable/disable storage participation
     */
    suspend fun setParticipationEnabled(enabled: Boolean) {
        val previousState = _participationEnabled.value
        betaLogger.log(LogLevel.INFO, "Storage", "Storage participation changing from $previousState to $enabled")
        
        _participationEnabled.value = enabled
        
        if (enabled) {
            betaLogger.log(LogLevel.INFO, "Storage", "Enabling storage participation")
            // Configure distributed storage with current allocations
            val config = createStorageConfig()
            // TODO: Reimplement configureStorageParticipation (2025-12-04)
            // configureStorageParticipation(config)
            
            val totalMB = getTotalAllocatedMB()
            betaLogger.log(LogLevel.INFO, "Storage", "Storage participation enabled with ${totalMB}MB allocated")
        } else {
            betaLogger.log(LogLevel.INFO, "Storage", "Disabling storage participation")
            // Disable storage participation
            val disabledConfig = DistributedStorageManager.StorageParticipationConfig(
                participationEnabled = false,
                totalQuota = 0L,
                allowedDirectories = emptyList()
            )
            // TODO: Reimplement configureStorageParticipation (2025-12-04)
            // configureStorageParticipation(disabledConfig)
            betaLogger.log(LogLevel.INFO, "Storage", "Storage participation disabled")
        }
        
        saveSettings()
    }
    
    /**
     * Update storage allocation for a specific device
     */
    // TODO strip out device logic in storage allocation and participation (2025-12-24)
    suspend fun updateStorageAllocation(path: String, allocatedMB: Long) {
        val currentAllocations = _storageAllocations.value.toMutableList()
        val existingIndex = currentAllocations.indexOfFirst { it.path == path }
        
        val device = _availableStorageDevices.value.find { it.path == path } ?: return
        val maxAllowedMB = (device.availableSpaceGB * 1024 * 0.9).toLong() // Max 90% of available
        val clampedAllocation = allocatedMB.coerceIn(0L, maxAllowedMB)
        
        val allocation = StorageAllocation(
            deviceId = device.id,
            path = device.path,
            allocatedMB = clampedAllocation,
            // enabled = clampedAllocation > 0
        )
        
        if (existingIndex >= 0) {
            currentAllocations[existingIndex] = allocation
        } else {
            currentAllocations.add(allocation)
        }
        
        _storageAllocations.value = currentAllocations
        
        // Update distributed storage configuration if participation is enabled
        if (_participationEnabled.value) {
            val config = createStorageConfig()
            distributedStorageManager.configureStorageParticipation(config)
        }
        
        saveSettings()
    }
    
    /**
     * Enable/disable storage allocation for a specific device
     */
    suspend fun setDeviceEnabled(path: String, enabled: Boolean) {
        val currentAllocations = _storageAllocations.value.toMutableList()
        val existingIndex = currentAllocations.indexOfFirst { it.path == path }
        
        if (existingIndex >= 0) {
            val existing = currentAllocations[existingIndex]
            // currentAllocations[existingIndex] = existing.copy(enabled = enabled)
            _storageAllocations.value = currentAllocations
            
            // Update distributed storage configuration
            if (_participationEnabled.value) {
                val config = createStorageConfig()
                distributedStorageManager.configureStorageParticipation(config)
            }
            
            saveSettings()
        }
    }
    
    /**
     * Get current storage statistics for UI display
     */
    fun getStorageStats(): DistributedStorageManager.StorageStats {
        return distributedStorageManager.storageStats.value
    }
    
    /**
     * Get total allocated storage across all devices
     */
    fun getTotalAllocatedMB(): Long {
        return _storageAllocations.value
            .sumOf { it.allocatedMB }
    }
    
    /**
     * Get usage percentage for UI display
     */
    fun getUsagePercentage(): Float {
        val stats = getStorageStats()
        return if (stats.totalOffered > 0) {
            (stats.currentlyUsed.toFloat() / stats.totalOffered) * 100f
        } else 0f
    }
    
    // === PRIVATE IMPLEMENTATION ===
    
    private fun detectStorageDevices() {
        scope.launch(Dispatchers.IO) {
            val devices = mutableListOf<StorageDevice>()
            
            // Internal storage
            val internalStats = android.os.StatFs(context.filesDir.absolutePath)
            val internalAvailableGB = (internalStats.availableBytes / (1024L * 1024 * 1024)).toFloat()
            
            devices.add(
                StorageDevice(
                    id = "internal",
                    name = "📱 Internal Storage",
                    path = "/data/app/mesh",
                    availableSpaceGB = internalAvailableGB,
                    totalSpaceGB = (internalStats.totalBytes / (1024L * 1024 * 1024)).toFloat(),
                    type = StorageDeviceType.INTERNAL
                )
            )
            
            // External storage detection
            try {
                val externalDirs = context.getExternalFilesDirs(null)
                externalDirs?.forEachIndexed { index, dir ->
                    if (dir != null && dir != externalDirs[0]) { // Skip primary external (usually same as internal)
                        val stats = android.os.StatFs(dir.absolutePath)
                        val availableGB = (stats.availableBytes / (1024L * 1024 * 1024)).toFloat()
                        val totalGB = (stats.totalBytes / (1024L * 1024 * 1024)).toFloat()
                        
                        devices.add(
                            StorageDevice(
                                id = "external_$index",
                                name = "💾 SD Card ${if (index > 1) index else ""}".trim(),
                                path = "/sdcard/MeshStorage",
                                availableSpaceGB = availableGB,
                                totalSpaceGB = totalGB,
                                type = StorageDeviceType.EXTERNAL
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // External storage detection failed
            }
            
            _availableStorageDevices.value = devices
            
            // Initialize default allocations if none exist
            if (_storageAllocations.value.isEmpty()) {
                initializeDefaultAllocations(devices)
            }
        }
    }
    
    private fun initializeDefaultAllocations(devices: List<StorageDevice>) {
        val defaultAllocations = devices.map { device ->
            // Default to 10% of available space, minimum 100MB, maximum 2GB
            val defaultMB = (device.availableSpaceGB * 1024 * 0.1).toLong()
                .coerceIn(100L, 2048L)
            
            StorageAllocation(
                deviceId = device.id,
                path = device.path,
                allocatedMB = defaultMB,
                // enabled = false // Disabled by default
            )
        }
        
        _storageAllocations.value = defaultAllocations
    }
    
    private fun createStorageConfig(): DistributedStorageManager.StorageParticipationConfig {
        val enabledAllocations = _storageAllocations.value
        val totalQuota = enabledAllocations.sumOf { allocation -> allocation.allocatedMB } * 1024 * 1024 // Convert to bytes
        val allowedDirectories = enabledAllocations.map { it.path }
        
        return DistributedStorageManager.StorageParticipationConfig(
            participationEnabled = _participationEnabled.value,
            totalQuota = totalQuota,
            allowedDirectories = allowedDirectories,
            encryptionRequired = true
        )
    }
    
    private fun loadSettings() {
        _participationEnabled.value = prefs.getBoolean(KEY_PARTICIPATION_ENABLED, false)
        
        // Load storage allocations from preferences
        val allocationsJson = prefs.getString(KEY_STORAGE_ALLOCATIONS, "[]")
        // TODO: Implement JSON deserialization for storage allocations
    }
    
    private fun saveSettings() {
        scope.launch(Dispatchers.IO) {
            prefs.edit()
                .putBoolean(KEY_PARTICIPATION_ENABLED, _participationEnabled.value)
                .apply()
            
            // TODO: Implement JSON serialization for storage allocations
        }
    }
}

// === DATA CLASSES FOR UI ===

data class StorageDevice(
    val id: String,
    val name: String,
    val path: String,
    val availableSpaceGB: Float,
    val totalSpaceGB: Float,
    val type: StorageDeviceType
)

data class StorageAllocation(
    val path: String,
    val allocatedMB: Long,
    val deviceId: String,
    // val enabled: Boolean
)

enum class StorageDeviceType {
    INTERNAL,
    EXTERNAL,
    USB
}

/**
 * UI state for storage participation settings
 */
data class StorageParticipationUIState(
    val participationEnabled: Boolean = false,
    val availableDevices: List<StorageDevice> = emptyList(),
    val storageAllocations: List<StorageAllocation> = emptyList(),
    val totalAllocatedMB: Long = 0L,
    val currentUsagePercent: Float = 0f,
    val storageHealth: Float = 1.0f
)

/**
 * Extension functions for UI convenience
 */
fun StorageAllocation.getUsagePercentage(device: StorageDevice): Float {
    val maxAvailableMB = device.availableSpaceGB * 1024
    return if (maxAvailableMB > 0) (allocatedMB.toFloat() / maxAvailableMB) * 100f else 0f
}

fun StorageDevice.getFormattedAvailableSpace(): String {
    return when {
        availableSpaceGB >= 1024 -> "${(availableSpaceGB / 1024).toInt()} TB"
        availableSpaceGB >= 1 -> "${availableSpaceGB.toInt()} GB"
        else -> "${(availableSpaceGB * 1024).toInt()} MB"
    }
}
