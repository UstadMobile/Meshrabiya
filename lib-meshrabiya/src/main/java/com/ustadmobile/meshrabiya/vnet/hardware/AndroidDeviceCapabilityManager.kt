package com.ustadmobile.meshrabiya.vnet.hardware

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.telephony.TelephonyManager
import android.net.wifi.WifiManager
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.mmcp.*
import com.ustadmobile.meshrabiya.vnet.NodeCapabilitySnapshot
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Android-specific implementation of DeviceCapabilityManager.
 * 
 * Collects real hardware metrics using Android system services.
 * All metrics collection is privacy-aware and respects user consent levels.
 * 
 * @param context Android application context
 * @param betaTestLogger Logger for privacy-filtered metric collection logging
 */
class AndroidDeviceCapabilityManager(
    private val context: Context,
    private val betaTestLogger: BetaTestLogger
) : DeviceCapabilityManager {
    
    companion object {
        private const val TAG = "AndroidHardwareMetrics"
        private const val CPU_STAT_FILE = "/proc/stat"
        private const val MEMORY_INFO_FILE = "/proc/meminfo"
        private const val DEFAULT_MONITORING_INTERVAL = 30000L // 30 seconds
        private const val STABILITY_HISTORY_HOURS = 24
        private const val MIN_BANDWIDTH_ESTIMATE = 1000L // 1 Kbps minimum
    }
    
    private val activityManager: ActivityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }
    
    private val batteryManager: BatteryManager by lazy {
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }
    
    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    
    private val powerManager: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    
    private val telephonyManager: TelephonyManager by lazy {
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }
    
    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    
    // Monitoring state
    private var monitoringJob: Job? = null
    private var isMonitoringActive = false
    
    // CPU tracking for utilization calculation
    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L
    private var lastCpuMeasurement = 0L
    
    // Stability tracking
    private val connectivityHistory = ConcurrentHashMap<Long, Boolean>()
    private val thermalEvents = ConcurrentHashMap<Long, ThermalState>()
    private var deviceStartTime = System.currentTimeMillis()
    
    override suspend fun getCpuUtilization(): Float = withContext(Dispatchers.IO) {
        try {
            val currentTime = System.currentTimeMillis()
            val cpuInfo = readCpuInfo()
            
            if (lastCpuMeasurement == 0L) {
                // First measurement, return default
                lastCpuTotal = cpuInfo.first
                lastCpuIdle = cpuInfo.second
                lastCpuMeasurement = currentTime
                
                betaTestLogger.log(LogLevel.DETAILED, TAG, "CPU utilization: initial measurement")
                return@withContext 0.5f
            }
            
            val totalDiff = cpuInfo.first - lastCpuTotal
            val idleDiff = cpuInfo.second - lastCpuIdle
            
            val utilization = if (totalDiff > 0) {
                1.0f - (idleDiff.toFloat() / totalDiff.toFloat())
            } else {
                0.5f // Default if unable to calculate
            }.coerceIn(0.0f, 1.0f)
            
            lastCpuTotal = cpuInfo.first
            lastCpuIdle = cpuInfo.second
            lastCpuMeasurement = currentTime
            
            betaTestLogger.log(LogLevel.DETAILED, TAG, "CPU utilization: ${utilization * 100}%")
            utilization
            
        } catch (e: Exception) {
            betaTestLogger.log(LogLevel.BASIC, TAG, "Failed to get CPU utilization: ${e.message}")
            0.5f // Default fallback
        }
    }
    
    override suspend fun getAvailableMemory(): Long = withContext(Dispatchers.IO) {
        try {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            
            betaTestLogger.log(LogLevel.DETAILED, TAG, 
                "Available memory: ${memInfo.availMem / (1024 * 1024)} MB")
            
            memInfo.availMem
        } catch (e: Exception) {
            betaTestLogger.log(LogLevel.BASIC, TAG, "Failed to get available memory: ${e.message}")
            Runtime.getRuntime().freeMemory() // Fallback
        }
    }
    
    override suspend fun getTotalMemory(): Long = withContext(Dispatchers.IO) {
        try {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            
            val totalMemory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                memInfo.totalMem
            } else {
                Runtime.getRuntime().maxMemory()
            }
            
            betaTestLogger.log(LogLevel.DETAILED, TAG, 
                "Total memory: ${totalMemory / (1024 * 1024)} MB")
            
            totalMemory
        } catch (e: Exception) {
            betaTestLogger.log(LogLevel.BASIC, TAG, "Failed to get total memory: ${e.message}")
            Runtime.getRuntime().maxMemory() // Fallback
        }
    }
    
    override suspend fun getBatteryInfo(): BatteryInfo = withContext(Dispatchers.IO) {
        try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 50
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
            val batteryPct = if (scale > 0) (level * 100) / scale else 50
            
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                           status == BatteryManager.BATTERY_STATUS_FULL
            
            val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) ?: 250
            val tempCelsius = temperature / 10
            
            val health = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN) ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
            val batteryHealth = when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealth.GOOD
                4, 3, 7 -> BatteryHealth.POOR // OVERHEAT, DEAD, OVER_VOLTAGE
                6 -> BatteryHealth.DEGRADED // COLD
                else -> BatteryHealth.DEGRADED // UNKNOWN, UNSPECIFIED_FAILURE
            }
            
            val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val chargingSource = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_USB -> ChargingSource.USB
                BatteryManager.BATTERY_PLUGGED_AC -> ChargingSource.AC
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> ChargingSource.WIRELESS
                else -> null
            }
            
            val batteryInfo = BatteryInfo(
                level = batteryPct,
                isCharging = isCharging,
                estimatedTimeRemaining = null, // Android doesn't provide this reliably
                temperatureCelsius = tempCelsius,
                health = batteryHealth,
                chargingSource = chargingSource
            )
            
            betaTestLogger.log(LogLevel.DETAILED, TAG, 
                "Battery: ${batteryPct}%, charging: $isCharging, temp: ${tempCelsius}°C")
            
            batteryInfo
        } catch (e: Exception) {
            betaTestLogger.log(LogLevel.BASIC, TAG, "Failed to get battery info: ${e.message}")
            // Fallback battery info
            BatteryInfo(
                level = 50,
                isCharging = false,
                estimatedTimeRemaining = null,
                temperatureCelsius = 25,
                health = BatteryHealth.GOOD,
                chargingSource = null
            )
        }
    }
    
    override suspend fun getThermalState(): ThermalState = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val thermalStatus = powerManager.currentThermalStatus
                val thermalState = when (thermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> ThermalState.COOL
                    PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.WARM
                    PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.WARM
                    PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.HOT
                    PowerManager.THERMAL_STATUS_CRITICAL -> ThermalState.CRITICAL
                    PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalState.CRITICAL
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
                    else -> ThermalState.COOL
                }
                
                // Track thermal events for stability calculation
                thermalEvents[System.currentTimeMillis()] = thermalState
                
                betaTestLogger.log(LogLevel.DETAILED, TAG, "Thermal state: $thermalState")
                thermalState
            } else {
                // Fallback for older Android versions - estimate from battery temperature
                val batteryInfo = getBatteryInfo()
                val thermalState = when {
                    batteryInfo.temperatureCelsius > 45 -> ThermalState.CRITICAL
                    batteryInfo.temperatureCelsius > 40 -> ThermalState.HOT
                    batteryInfo.temperatureCelsius > 35 -> ThermalState.WARM
                    else -> ThermalState.COOL
                }
                
                betaTestLogger.log(LogLevel.DETAILED, TAG, 
                    "Thermal state (estimated): $thermalState from battery temp ${batteryInfo.temperatureCelsius}°C")
                thermalState
            }
        } catch (e: Exception) {
            betaTestLogger.log(LogLevel.BASIC, TAG, "Failed to get thermal state: ${e.message}")
            ThermalState.COOL // Safe fallback
        }
    }
    
    override suspend fun getEstimatedBandwidth(): Long = withContext(Dispatchers.IO) {
        try {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            
            if (capabilities != null) {
                val downBandwidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    capabilities.linkDownstreamBandwidthKbps * 1000L // Convert to bps
                } else {
                    estimateBandwidthFromNetworkType(capabilities)
                }
                
                val bandwidth = maxOf(downBandwidth, MIN_BANDWIDTH_ESTIMATE)
                
                betaTestLogger.log(LogLevel.DETAILED, TAG, 
                    "Estimated bandwidth: ${bandwidth / 1000} Kbps")
                
                bandwidth
            } else {
                betaTestLogger.log(LogLevel.BASIC, TAG, "No active network for bandwidth estimation")
                MIN_BANDWIDTH_ESTIMATE
            }
        } catch (e: Exception) {
            betaTestLogger.log(LogLevel.BASIC, TAG, "Failed to estimate bandwidth: ${e.message}")
            MIN_BANDWIDTH_ESTIMATE
        }
    }
    
    override suspend fun getNetworkInterfaces(): Set<SerializableNetworkInterfaceInfo> = withContext(Dispatchers.IO) {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.mapNotNull { ni ->
                try {
                    SerializableNetworkInterfaceInfo(
                        name = ni.name,
                        displayName = ni.displayName ?: ni.name,
                        mtu = runCatching { ni.mtu }.getOrDefault(-1),
                        isLoopback = runCatching { ni.isLoopback }.getOrDefault(false),
                        supportsMulticast = runCatching { ni.supportsMulticast() }.getOrDefault(false),
                        isPointToPoint = runCatching { ni.isPointToPoint }.getOrDefault(false),
                        isVirtual = runCatching { ni.isVirtual }.getOrDefault(false),
                        interfaceAddresses = runCatching { 
                            ni.interfaceAddresses.map { "${it.address}/${it.networkPrefixLength}" }
                        }.getOrDefault(emptyList()),
                        inetAddresses = runCatching { 
                            ni.inetAddresses.toList().map { it.hostAddress ?: it.toString() }
                        }.getOrDefault(emptyList())
                    )
                } catch (e: Exception) {
                    betaTestLogger.log(LogLevel.DETAILED, TAG, "Failed to read interface ${ni.name}: ${e.message}")
                    null
                }
            }?.toSet() ?: emptySet()
            
            betaTestLogger.log(LogLevel.DETAILED, TAG, "Found ${interfaces.size} network interfaces")
            interfaces
        } catch (e: Exception) {
            betaTestLogger.log(LogLevel.BASIC, TAG, "Failed to get network interfaces: ${e.message}")
            emptySet()
        }
    }
    
    override suspend fun getStorageCapabilities(): StorageCapabilities = withContext(Dispatchers.IO) {
        try {
            val internalDir = context.filesDir
            val internalStats = StatFs(internalDir.absolutePath)
            
            val blockSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                internalStats.blockSizeLong
            } else {
                @Suppress("DEPRECATION")
                internalStats.blockSize.toLong()
            }
            
            val availableBlocks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                internalStats.availableBlocksLong
            } else {
                @Suppress("DEPRECATION")
                internalStats.availableBlocks.toLong()
            }
            
            val availableSpace = availableBlocks * blockSize
            val availableSpaceMB = availableSpace / (1024 * 1024)
            
            val storageCapabilities = StorageCapabilities(
                totalOffered = availableSpace / 2, // Offer half of available space
                localStorageAvailableMB = availableSpaceMB,
                compressionSupported = true,
                encryptionSupported = true
                // REMOVED: replicationFactor (handled by storage manager)
                // REMOVED: currentlyUsed (less useful than localStorageAvailableMB)
                // REMOVED: accessPatterns (incorporated into fitness via I/O benchmarking)
            )
            
            betaTestLogger.log(LogLevel.DETAILED, TAG, 
                "Storage: ${availableSpaceMB} MB available, offering ${storageCapabilities.totalOffered / (1024 * 1024)} MB")
            
            storageCapabilities
        } catch (e: Exception) {
            betaTestLogger.log(LogLevel.BASIC, TAG, "Failed to get storage capabilities: ${e.message}")
            StorageCapabilities(
                totalOffered = 100 * 1024 * 1024L, // 100 MB fallback
                localStorageAvailableMB = 100L,
                compressionSupported = true,
                encryptionSupported = true
            )
        }
    }
    
    override suspend fun getStabilityScore(): Float = withContext(Dispatchers.IO) {
        try {
            val currentTime = System.currentTimeMillis()
            val cutoffTime = currentTime - (STABILITY_HISTORY_HOURS * 3600 * 1000L)
            
            // Clean old history
            connectivityHistory.keys.removeAll { it < cutoffTime }
            thermalEvents.keys.removeAll { it < cutoffTime }
            
            // Calculate uptime score (0.0-1.0)
            val uptime = currentTime - deviceStartTime
            val uptimeHours = uptime / (3600 * 1000L).toDouble()
            val uptimeScore = minOf(uptimeHours / 24.0, 1.0).toFloat() // Max score at 24h uptime
            
            // Calculate connectivity stability (0.0-1.0)
            val connectivityScore = if (connectivityHistory.isNotEmpty()) {
                connectivityHistory.values.count { it }.toFloat() / connectivityHistory.size
            } else {
                0.8f // Default good connectivity
            }
            
            // Calculate thermal stability (0.0-1.0)
            val thermalScore = if (thermalEvents.isNotEmpty()) {
                val hotEvents = thermalEvents.values.count { 
                    it == ThermalState.HOT || it == ThermalState.CRITICAL 
                }
                1.0f - (hotEvents.toFloat() / thermalEvents.size)
            } else {
                1.0f // No thermal issues recorded
            }
            
            // Battery health contribution
            val batteryInfo = getBatteryInfo()
            val batteryScore = when (batteryInfo.health) {
                BatteryHealth.GOOD -> 1.0f
                BatteryHealth.DEGRADED -> 0.7f
                BatteryHealth.POOR -> 0.3f
            }
            
            // Weighted stability score
            val stabilityScore = (
                uptimeScore * 0.3f +
                connectivityScore * 0.4f +
                thermalScore * 0.2f +
                batteryScore * 0.1f
            ).coerceIn(0.0f, 1.0f)
            
            betaTestLogger.log(LogLevel.DETAILED, TAG, 
                "Stability score: $stabilityScore (uptime: $uptimeScore, connectivity: $connectivityScore, thermal: $thermalScore, battery: $batteryScore)")
            
            stabilityScore
        } catch (e: Exception) {
            betaTestLogger.log(LogLevel.BASIC, TAG, "Failed to calculate stability score: ${e.message}")
            0.8f // Default stable score
        }
    }
    
    override suspend fun getCapabilitySnapshot(nodeId: String): NodeCapabilitySnapshot {
        try {
            val cpuUtilization = getCpuUtilization()
            val availableMemory = getAvailableMemory()
            val totalMemory = getTotalMemory()
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
                availableCPU = 1.0f - cpuUtilization, // Available = 1 - utilized
                availableRAM = availableMemory,
                availableBandwidth = bandwidth,
                storageOffered = storageCapabilities.totalOffered,
                batteryLevel = batteryInfo.level,
                thermalThrottling = thermalState == ThermalState.HOT || thermalState == ThermalState.CRITICAL,
                powerState = powerState,
                networkInterfaces = networkInterfaces
            )
            
            // Calculate network quality based on connectivity and signal strength
            val networkQuality = calculateNetworkQuality()
            
            val snapshot = NodeCapabilitySnapshot(
                nodeId = nodeId,
                resources = resources,
                batteryInfo = batteryInfo,
                thermalState = thermalState,
                networkQuality = networkQuality,
                stability = stabilityScore
            )
            
            betaTestLogger.log(LogLevel.INFO, TAG, 
                "Capability snapshot for $nodeId: CPU=${(resources.availableCPU * 100).toInt()}% available, " +
                "Battery=${batteryInfo.level}%, Thermal=$thermalState, Network=${(networkQuality * 100).toInt()}%, " +
                "Stability=${(stabilityScore * 100).toInt()}%")
            
            return snapshot
        } catch (e: Exception) {
            betaTestLogger.log(LogLevel.BASIC, TAG, "Failed to create capability snapshot: ${e.message}")
            throw HardwareMetricsException("Failed to collect device capabilities", e)
        }
    }
    
    override fun startMonitoring(intervalMs: Long) {
        if (isMonitoringActive) {
            betaTestLogger.log(LogLevel.INFO, TAG, "Monitoring already active")
            return
        }
        
        isMonitoringActive = true
        betaTestLogger.log(LogLevel.INFO, TAG, "Started hardware monitoring with ${intervalMs}ms interval")
        
        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isMonitoringActive) {
                try {
                    // Update connectivity history
                    val isConnected = connectivityManager.activeNetwork != null
                    connectivityHistory[System.currentTimeMillis()] = isConnected
                    
                    // Update thermal history
                    val thermalState = getThermalState()
                    thermalEvents[System.currentTimeMillis()] = thermalState
                    
                    betaTestLogger.log(LogLevel.DETAILED, TAG, 
                        "Monitoring update: connected=$isConnected, thermal=$thermalState")
                    
                } catch (e: Exception) {
                    betaTestLogger.log(LogLevel.BASIC, TAG, "Error during monitoring: ${e.message}")
                }
                
                delay(intervalMs)
            }
        }
    }
    
    override fun stopMonitoring() {
        if (!isMonitoringActive) {
            return
        }
        
        isMonitoringActive = false
        monitoringJob?.cancel()
        monitoringJob = null
        
        betaTestLogger.log(LogLevel.INFO, TAG, "Stopped hardware monitoring")
    }
    
    override fun isMonitoring(): Boolean = isMonitoringActive
    
    /**
     * Read CPU stats from /proc/stat
     * @return Pair of (total CPU time, idle CPU time)
     */
    private fun readCpuInfo(): Pair<Long, Long> {
        return try {
            val statFile = File(CPU_STAT_FILE)
            if (!statFile.exists()) {
                return 0L to 0L
            }
            
            val cpuLine = statFile.readLines().firstOrNull { it.startsWith("cpu ") }
                ?: return 0L to 0L
            
            val values = cpuLine.split("\\s+".toRegex()).drop(1).map { it.toLongOrNull() ?: 0L }
            if (values.size < 4) {
                return 0L to 0L
            }
            
            val idle = values[3]
            val total = values.sum()
            
            total to idle
        } catch (e: Exception) {
            betaTestLogger.log(LogLevel.DETAILED, TAG, "Failed to read CPU info: ${e.message}")
            0L to 0L
        }
    }
    
    /**
     * Estimate bandwidth based on network type for older Android versions
     */
    private fun estimateBandwidthFromNetworkType(capabilities: NetworkCapabilities): Long {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 50_000_000L // 50 Mbps
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 100_000_000L // 100 Mbps
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                // Estimate based on cellular technology if available
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && 
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> 20_000_000L // 20 Mbps for unlimited data
                    else -> 5_000_000L // 5 Mbps for typical cellular
                }
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 1_000_000L // 1 Mbps
            else -> MIN_BANDWIDTH_ESTIMATE
        }
    }
    
    /**
     * Calculate network quality score based on connectivity and signal strength
     */
    private suspend fun calculateNetworkQuality(): Float {
        return try {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            
            if (capabilities == null) {
                return 0.0f
            }
            
            var qualityScore = 0.5f // Base score
            
            // Boost for different network types
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> qualityScore += 0.4f
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    qualityScore += 0.3f
                    // Add WiFi signal strength if available (using modern API)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        // Use NetworkCapabilities signal strength for Android Q+
                        val signalStrength = capabilities.signalStrength
                        if (signalStrength != Int.MIN_VALUE) {
                            // Convert dBm to quality score (typical WiFi range: -30 to -90 dBm)
                            val normalizedStrength = ((signalStrength + 90).coerceIn(0, 60) / 60.0f)
                            qualityScore += normalizedStrength * 0.2f
                        }
                    } else {
                        // Fallback for older devices - use deprecated API with suppression
                        @Suppress("DEPRECATION")
                        if (wifiManager.isWifiEnabled) {
                            @Suppress("DEPRECATION")
                            val wifiInfo = wifiManager.connectionInfo
                            @Suppress("DEPRECATION")
                            val signalLevel = WifiManager.calculateSignalLevel(wifiInfo.rssi, 5)
                            qualityScore += (signalLevel / 4.0f) * 0.2f
                        }
                    }
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    qualityScore += 0.2f
                    // Could add cellular signal strength here if needed
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> qualityScore += 0.1f
            }
            
            // Check for metered connection (reduce quality slightly)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && 
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                qualityScore -= 0.1f
            }
            
            qualityScore.coerceIn(0.0f, 1.0f)
        } catch (e: Exception) {
            betaTestLogger.log(LogLevel.DETAILED, TAG, "Failed to calculate network quality: ${e.message}")
            0.5f
        }
    }
}
