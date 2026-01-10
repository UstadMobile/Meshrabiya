package com.ustadmobile.meshrabiya.service

import android.content.Context
import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Mobile-optimized service registry with storage quotas and LRU eviction
 * Integrates with existing Meshrabiya AndroidVirtualNode infrastructure
 */
class MobileServiceRegistry(
    private val context: Context,
    private val logger: MNetLogger,
    private val maxStorageBytes: Long,
    private val deviceRole: DeviceCapabilities.DeviceClass
) {
    
    companion object {
        private const val TAG = "MobileServiceRegistry"
        private const val SERVICE_CACHE_DIR = "mesh_services"
    }
    
    // Service cache with LRU eviction
    private val serviceCache = LRUServiceCache(maxStorageBytes)
    
    // Currently available services in mesh
    private val _availableServices = MutableStateFlow<Map<String, ServiceDiscoveryInfo>>(emptyMap())
    val availableServices: StateFlow<Map<String, ServiceDiscoveryInfo>> = _availableServices
    
    // Local service hosting
    private val hostedServices = ConcurrentHashMap<String, ServiceBundle>()
    
    // Service execution metrics
    private val executionMetrics = ConcurrentHashMap<String, ServiceMetrics>()
    
    data class ServiceDiscoveryInfo(
        val serviceId: String,
        val announcement: ServiceAnnouncement,
        val hostNodeAddress: Int,
        val hopCount: Int,
        val lastSeen: Long,
        val averageLatency: Long = 0L,
        val successRate: Float = 1.0f
    )
    
    data class ServiceMetrics(
        val executionCount: AtomicLong = AtomicLong(0),
        val totalExecutionTime: AtomicLong = AtomicLong(0),
        val successCount: AtomicLong = AtomicLong(0),
        val lastExecuted: AtomicLong = AtomicLong(0)
    ) {
        fun getAverageExecutionTime(): Long {
            val count = executionCount.get()
            return if (count > 0) totalExecutionTime.get() / count else 0L
        }
        
        fun getSuccessRate(): Float {
            val total = executionCount.get()
            return if (total > 0) successCount.get().toFloat() / total else 1.0f
        }
    }
    
    /**
     * Storage-aware service fetching based on device capabilities
     */
    fun shouldFetchService(serviceId: String, serviceSizeKB: Int): Boolean {
        val availableStorageKB = getAvailableStorageKB()
        val batteryLevel = getBatteryLevel()
        
        return when (deviceRole) {
            DeviceCapabilities.DeviceClass.CONSUMER -> {
                // Very conservative - only fetch tiny services
                serviceSizeKB <= 1024 && // 1MB max
                availableStorageKB > serviceSizeKB * 5 && // 5x safety margin
                batteryLevel > 0.20f
            }
            
            DeviceCapabilities.DeviceClass.ML_BASIC -> {
                serviceSizeKB <= 10 * 1024 && // 10MB max
                availableStorageKB > serviceSizeKB * 3 &&
                batteryLevel > 0.15f
            }
            
            DeviceCapabilities.DeviceClass.ML_CAPABLE -> {
                serviceSizeKB <= 50 * 1024 && // 50MB max
                availableStorageKB > serviceSizeKB * 2 &&
                batteryLevel > 0.10f
            }
            
            DeviceCapabilities.DeviceClass.ML_POWERHOUSE -> {
                serviceSizeKB <= 200 * 1024 && // 200MB max
                availableStorageKB > serviceSizeKB * 1.5 &&
                batteryLevel > 0.05f // Can run on very low battery
            }
        }
    }
    
    /**
     * Update available services from mesh discovery
     */
    fun updateServiceAvailability(
        nodeAddress: Int,
        serviceAnnouncements: List<ServiceAnnouncement>,
        hopCount: Int
    ) {
        val currentTime = System.currentTimeMillis()
        val updatedServices = _availableServices.value.toMutableMap()
        
        // Add/update services from this node
        serviceAnnouncements.forEach { announcement ->
            val discoveryInfo = ServiceDiscoveryInfo(
                serviceId = announcement.serviceId,
                announcement = announcement,
                hostNodeAddress = nodeAddress,
                hopCount = hopCount,
                lastSeen = currentTime
            )
            
            updatedServices[announcement.serviceId] = discoveryInfo
            logger(Log.DEBUG, "$TAG: Updated service ${announcement.serviceId} from node $nodeAddress (${hopCount} hops)")
        }
        
        // Remove stale services (not seen for 5 minutes)
        val staleThreshold = currentTime - 5 * 60 * 1000
        updatedServices.values.removeAll { it.lastSeen < staleThreshold }
        
        _availableServices.value = updatedServices
    }
    
    /**
     * Get optimal nodes for executing a specific service
     */
    fun getOptimalServiceNodes(serviceId: String): List<ServiceDiscoveryInfo> {
        return availableServices.value.values
            .filter { it.serviceId == serviceId }
            .sortedWith(compareBy<ServiceDiscoveryInfo> { it.hopCount }
                .thenByDescending { it.successRate }
                .thenBy { it.averageLatency })
    }
    
    /**
     * Record service execution metrics for routing optimization
     */
    fun recordServiceExecution(
        serviceId: String,
        executionTimeMs: Long,
        success: Boolean
    ) {
        val metrics = executionMetrics.computeIfAbsent(serviceId) { ServiceMetrics() }
        
        metrics.executionCount.incrementAndGet()
        metrics.totalExecutionTime.addAndGet(executionTimeMs)
        metrics.lastExecuted.set(System.currentTimeMillis())
        
        if (success) {
            metrics.successCount.incrementAndGet()
        }
        
        logger(Log.DEBUG, "$TAG: Recorded execution for $serviceId: ${executionTimeMs}ms, success=$success")
    }
    
    private fun getAvailableStorageKB(): Long {
        return try {
            val cacheDir = context.getDir(SERVICE_CACHE_DIR, Context.MODE_PRIVATE)
            val freeBytes = cacheDir.usableSpace
            freeBytes / 1024 // Convert to KB
        } catch (e: Exception) {
            logger(Log.WARN, "$TAG: Failed to get available storage", e)
            0L
        }
    }
    
    private fun getBatteryLevel(): Float {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) / 100f
        } catch (e: Exception) {
            logger(Log.WARN, "$TAG: Failed to get battery level", e)
            0.5f // Assume 50% if unknown
        }
    }
}

/**
 * LRU cache for service bundles with storage quota enforcement
 */
private class LRUServiceCache(private val maxStorageBytes: Long) {
    private val cache = LinkedHashMap<String, CachedServiceBundle>(16, 0.75f, true)
    private var currentStorageBytes = 0L
    
    data class CachedServiceBundle(
        val bundle: ServiceBundle,
        val sizeBytes: Long,
        val lastAccessed: Long,
        val accessCount: Long
    )
    
    @Synchronized
    fun get(serviceId: String): ServiceBundle? {
        val cached = cache[serviceId]
        return cached?.bundle
    }
    
    @Synchronized
    fun put(serviceId: String, bundle: ServiceBundle, sizeBytes: Long) {
        // Remove existing entry if present
        cache.remove(serviceId)?.let { 
            currentStorageBytes -= it.sizeBytes 
        }
        
        // Evict old entries if needed
        while (currentStorageBytes + sizeBytes > maxStorageBytes && cache.isNotEmpty()) {
            evictLeastUsed()
        }
        
        // Add new entry
        if (currentStorageBytes + sizeBytes <= maxStorageBytes) {
            cache[serviceId] = CachedServiceBundle(bundle, sizeBytes, System.currentTimeMillis(), 1)
            currentStorageBytes += sizeBytes
        }
    }
    
    private fun evictLeastUsed() {
        val leastUsed = cache.entries.minByOrNull { it.value.lastAccessed }
        leastUsed?.let { entry ->
            cache.remove(entry.key)
            currentStorageBytes -= entry.value.sizeBytes
        }
    }
}

// Placeholder for ServiceBundle - will be implemented in Phase 2
data class ServiceBundle(
    val serviceId: String,
    val metadata: ServiceAnnouncement,
    val payload: ByteArray
)
