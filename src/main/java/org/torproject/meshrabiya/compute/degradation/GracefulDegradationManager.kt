package org.torproject.meshrabiya.compute.degradation

import kotlinx.coroutines.*
import org.torproject.meshrabiya.compute.RuntimeRegistry
import org.torproject.meshrabiya.compute.TaskType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GracefulDegradationManager handles service degradation and fallback strategies.
 * 
 * Features:
 * - Automatic runtime availability monitoring
 * - Fallback to simpler task execution methods
 * - Degraded mode operation with reduced functionality
 * - Service-level SLO enforcement
 * - Automatic recovery from degraded state
 * 
 * Architecture:
 * - Monitors runtime health and availability
 * - Maintains degradation levels per service
 * - Selects appropriate execution strategy based on available resources
 * - Notifies system of degradation events
 * - Attempts periodic recovery
 * 
 * Degradation Levels:
 * - NORMAL: Full functionality, all features available
 * - REDUCED: Minor degradation, some non-critical features disabled
 * - MINIMAL: Significant degradation, only core features available
 * - EMERGENCY: Critical degradation, emergency fallback mode
 * - UNAVAILABLE: Service unavailable
 * 
 * Fallback Strategies:
 * - Runtime unavailable -> Use alternative runtime
 * - Storage unavailable -> Use local cache
 * - Network unavailable -> Queue operations
 * - Compute unavailable -> Reject new tasks, complete existing
 * 
 * @property runtimeRegistry Runtime registry for availability checks
 * @property healthCheckIntervalMs Interval for health checks (default 30s)
 * @property recoveryAttemptIntervalMs Interval for recovery attempts (default 60s)
 */
class GracefulDegradationManager(
    private val runtimeRegistry: RuntimeRegistry,
    private val healthCheckIntervalMs: Long = 30000,
    private val recoveryAttemptIntervalMs: Long = 60000
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    /**
     * Degradation level for a service.
     */
    enum class DegradationLevel(val value: Int) {
        NORMAL(0),
        REDUCED(1),
        MINIMAL(2),
        EMERGENCY(3),
        UNAVAILABLE(4);
        
        fun isAtLeast(other: DegradationLevel): Boolean = value >= other.value
        fun isWorseThan(other: DegradationLevel): Boolean = value > other.value
    }
    
    /**
     * Service type being monitored.
     */
    enum class ServiceType {
        RUNTIME,
        STORAGE,
        NETWORK,
        COMPUTE,
        MESSAGING
    }
    
    /**
     * Fallback strategy for a service.
     */
    sealed class FallbackStrategy {
        data class UseAlternative(val alternativeId: String) : FallbackStrategy()
        data class QueueOperations(val maxQueueSize: Int) : FallbackStrategy()
        object UseLocalCache : FallbackStrategy()
        object RejectNewRequests : FallbackStrategy()
        object EmergencyMode : FallbackStrategy()
        object Unavailable : FallbackStrategy()
    }
    
    /**
     * Service degradation state.
     */
    private data class DegradationState(
        val serviceType: ServiceType,
        val serviceId: String,
        var level: DegradationLevel = DegradationLevel.NORMAL,
        var lastHealthCheckMs: Long = System.currentTimeMillis(),
        var lastRecoveryAttemptMs: Long = 0,
        var consecutiveFailures: Int = 0,
        var degradedSinceMs: Long = 0,
        val activeStrategy: MutableList<FallbackStrategy> = mutableListOf()
    )
    
    /**
     * Degradation policy for a service.
     */
    data class DegradationPolicy(
        val serviceType: ServiceType,
        val degradationThresholds: Map<DegradationLevel, DegradationThreshold>,
        val fallbackStrategies: Map<DegradationLevel, List<FallbackStrategy>>,
        val enableAutoRecovery: Boolean = true
    )
    
    /**
     * Threshold for entering a degradation level.
     */
    data class DegradationThreshold(
        val consecutiveFailures: Int,
        val failureRatePercent: Int,
        val responseTimeMs: Long
    )
    
    // Service degradation states
    private val serviceStates = ConcurrentHashMap<String, DegradationState>()
    
    // Service degradation policies
    private val servicePolicies = ConcurrentHashMap<ServiceType, DegradationPolicy>()
    
    // Global monitoring state
    private val isMonitoring = AtomicBoolean(false)
    private var monitoringJob: Job? = null
    
    // Statistics
    @Volatile private var totalDegradationEvents = 0L
    @Volatile private var totalRecoveryEvents = 0L
    @Volatile private var totalFallbackActivations = 0L
    
    init {
        // Set up default policies
        setupDefaultPolicies()
    }
    
    /**
     * Setup default degradation policies.
     */
    private fun setupDefaultPolicies() {
        // Runtime service policy
        servicePolicies[ServiceType.RUNTIME] = DegradationPolicy(
            serviceType = ServiceType.RUNTIME,
            degradationThresholds = mapOf(
                DegradationLevel.REDUCED to DegradationThreshold(
                    consecutiveFailures = 3,
                    failureRatePercent = 10,
                    responseTimeMs = 5000
                ),
                DegradationLevel.MINIMAL to DegradationThreshold(
                    consecutiveFailures = 5,
                    failureRatePercent = 25,
                    responseTimeMs = 10000
                ),
                DegradationLevel.EMERGENCY to DegradationThreshold(
                    consecutiveFailures = 10,
                    failureRatePercent = 50,
                    responseTimeMs = 30000
                ),
                DegradationLevel.UNAVAILABLE to DegradationThreshold(
                    consecutiveFailures = 15,
                    failureRatePercent = 75,
                    responseTimeMs = 60000
                )
            ),
            fallbackStrategies = mapOf(
                DegradationLevel.REDUCED to listOf(
                    FallbackStrategy.UseAlternative("fallback_runtime")
                ),
                DegradationLevel.MINIMAL to listOf(
                    FallbackStrategy.QueueOperations(maxQueueSize = 100)
                ),
                DegradationLevel.EMERGENCY to listOf(
                    FallbackStrategy.RejectNewRequests
                ),
                DegradationLevel.UNAVAILABLE to listOf(
                    FallbackStrategy.Unavailable
                )
            )
        )
        
        // Storage service policy
        servicePolicies[ServiceType.STORAGE] = DegradationPolicy(
            serviceType = ServiceType.STORAGE,
            degradationThresholds = mapOf(
                DegradationLevel.REDUCED to DegradationThreshold(
                    consecutiveFailures = 3,
                    failureRatePercent = 10,
                    responseTimeMs = 2000
                ),
                DegradationLevel.MINIMAL to DegradationThreshold(
                    consecutiveFailures = 5,
                    failureRatePercent = 20,
                    responseTimeMs = 5000
                ),
                DegradationLevel.UNAVAILABLE to DegradationThreshold(
                    consecutiveFailures = 10,
                    failureRatePercent = 50,
                    responseTimeMs = 10000
                )
            ),
            fallbackStrategies = mapOf(
                DegradationLevel.REDUCED to listOf(
                    FallbackStrategy.UseLocalCache
                ),
                DegradationLevel.MINIMAL to listOf(
                    FallbackStrategy.QueueOperations(maxQueueSize = 50)
                ),
                DegradationLevel.UNAVAILABLE to listOf(
                    FallbackStrategy.Unavailable
                )
            )
        )
        
        // Network service policy
        servicePolicies[ServiceType.NETWORK] = DegradationPolicy(
            serviceType = ServiceType.NETWORK,
            degradationThresholds = mapOf(
                DegradationLevel.REDUCED to DegradationThreshold(
                    consecutiveFailures = 5,
                    failureRatePercent = 15,
                    responseTimeMs = 3000
                ),
                DegradationLevel.MINIMAL to DegradationThreshold(
                    consecutiveFailures = 10,
                    failureRatePercent = 30,
                    responseTimeMs = 10000
                ),
                DegradationLevel.UNAVAILABLE to DegradationThreshold(
                    consecutiveFailures = 20,
                    failureRatePercent = 60,
                    responseTimeMs = 30000
                )
            ),
            fallbackStrategies = mapOf(
                DegradationLevel.REDUCED to listOf(
                    FallbackStrategy.QueueOperations(maxQueueSize = 200)
                ),
                DegradationLevel.MINIMAL to listOf(
                    FallbackStrategy.QueueOperations(maxQueueSize = 1000)
                ),
                DegradationLevel.UNAVAILABLE to listOf(
                    FallbackStrategy.Unavailable
                )
            )
        )
    }
    
    /**
     * Start monitoring services for degradation.
     */
    fun startMonitoring() {
        if (!isMonitoring.compareAndSet(false, true)) {
            return
        }
        
        monitoringJob = scope.launch {
            while (isActive && isMonitoring.get()) {
                performHealthChecks()
                attemptRecoveries()
                delay(healthCheckIntervalMs)
            }
        }
    }
    
    /**
     * Stop monitoring services.
     */
    fun stopMonitoring() {
        isMonitoring.set(false)
        monitoringJob?.cancel()
    }
    
    /**
     * Register a service for degradation monitoring.
     * 
     * @param serviceType Service type
     * @param serviceId Service identifier
     */
    fun registerService(serviceType: ServiceType, serviceId: String) {
        serviceStates.getOrPut("${serviceType}_${serviceId}") {
            DegradationState(
                serviceType = serviceType,
                serviceId = serviceId
            )
        }
    }
    
    /**
     * Unregister a service from monitoring.
     * 
     * @param serviceType Service type
     * @param serviceId Service identifier
     */
    fun unregisterService(serviceType: ServiceType, serviceId: String) {
        serviceStates.remove("${serviceType}_${serviceId}")
    }
    
    /**
     * Report service failure.
     * 
     * @param serviceType Service type
     * @param serviceId Service identifier
     * @param failureReason Reason for failure
     */
    fun reportFailure(serviceType: ServiceType, serviceId: String, failureReason: String) {
        val key = "${serviceType}_${serviceId}"
        val state = serviceStates[key] ?: return
        
        state.consecutiveFailures++
        state.lastHealthCheckMs = System.currentTimeMillis()
        
        evaluateDegradation(state)
    }
    
    /**
     * Report service success.
     * 
     * @param serviceType Service type
     * @param serviceId Service identifier
     */
    fun reportSuccess(serviceType: ServiceType, serviceId: String) {
        val key = "${serviceType}_${serviceId}"
        val state = serviceStates[key] ?: return
        
        state.consecutiveFailures = 0
        state.lastHealthCheckMs = System.currentTimeMillis()
        
        // Check if we can recover from degradation
        if (state.level != DegradationLevel.NORMAL) {
            attemptRecovery(state)
        }
    }
    
    /**
     * Get current degradation level for a service.
     * 
     * @param serviceType Service type
     * @param serviceId Service identifier
     * @return Current degradation level
     */
    fun getDegradationLevel(serviceType: ServiceType, serviceId: String): DegradationLevel {
        val key = "${serviceType}_${serviceId}"
        return serviceStates[key]?.level ?: DegradationLevel.NORMAL
    }
    
    /**
     * Get active fallback strategies for a service.
     * 
     * @param serviceType Service type
     * @param serviceId Service identifier
     * @return List of active fallback strategies
     */
    fun getActiveFallbackStrategies(serviceType: ServiceType, serviceId: String): List<FallbackStrategy> {
        val key = "${serviceType}_${serviceId}"
        return serviceStates[key]?.activeStrategy?.toList() ?: emptyList()
    }
    
    /**
     * Check if service is available (not in UNAVAILABLE state).
     * 
     * @param serviceType Service type
     * @param serviceId Service identifier
     * @return True if service is available
     */
    fun isServiceAvailable(serviceType: ServiceType, serviceId: String): Boolean {
        return getDegradationLevel(serviceType, serviceId) != DegradationLevel.UNAVAILABLE
    }
    
    /**
     * Get fallback runtime for task type when primary runtime is degraded.
     * 
     * @param taskType Task type
     * @param primaryRuntimeId Primary runtime that is degraded
     * @return Fallback runtime ID or null if none available
     */
    fun getFallbackRuntime(taskType: TaskType, primaryRuntimeId: String): String? {
        val availableRuntimes = runtimeRegistry.getAvailableRuntimes(taskType)
        
        // Filter out degraded runtimes
        return availableRuntimes
            .filter { it.runtimeId != primaryRuntimeId }
            .filter { isServiceAvailable(ServiceType.RUNTIME, it.runtimeId) }
            .minByOrNull { getDegradationLevel(ServiceType.RUNTIME, it.runtimeId).value }
            ?.runtimeId
    }
    
    /**
     * Evaluate degradation level for a service.
     */
    private fun evaluateDegradation(state: DegradationState) {
        val policy = servicePolicies[state.serviceType] ?: return
        
        // Determine appropriate degradation level
        var newLevel = DegradationLevel.NORMAL
        
        for ((level, threshold) in policy.degradationThresholds.entries.sortedBy { it.key.value }) {
            if (state.consecutiveFailures >= threshold.consecutiveFailures) {
                newLevel = level
            }
        }
        
        // Update degradation level if changed
        if (newLevel != state.level) {
            val oldLevel = state.level
            state.level = newLevel
            
            if (newLevel.isWorseThan(oldLevel)) {
                totalDegradationEvents++
                
                if (state.degradedSinceMs == 0L) {
                    state.degradedSinceMs = System.currentTimeMillis()
                }
            }
            
            // Activate fallback strategies
            activateFallbackStrategies(state, policy)
        }
    }
    
    /**
     * Activate fallback strategies for a degradation level.
     */
    private fun activateFallbackStrategies(state: DegradationState, policy: DegradationPolicy) {
        state.activeStrategy.clear()
        
        val strategies = policy.fallbackStrategies[state.level] ?: emptyList()
        state.activeStrategy.addAll(strategies)
        
        if (strategies.isNotEmpty()) {
            totalFallbackActivations++
        }
    }
    
    /**
     * Perform health checks for all services.
     */
    private suspend fun performHealthChecks() {
        serviceStates.values.forEach { state ->
            // Check runtime availability
            if (state.serviceType == ServiceType.RUNTIME) {
                val isAvailable = runtimeRegistry.isRuntimeAvailable(state.serviceId)
                if (!isAvailable) {
                    reportFailure(state.serviceType, state.serviceId, "Runtime not available")
                } else {
                    reportSuccess(state.serviceType, state.serviceId)
                }
            }
        }
    }
    
    /**
     * Attempt recovery for degraded services.
     */
    private suspend fun attemptRecoveries() {
        val now = System.currentTimeMillis()
        
        serviceStates.values
            .filter { it.level != DegradationLevel.NORMAL }
            .filter { now - it.lastRecoveryAttemptMs >= recoveryAttemptIntervalMs }
            .forEach { state ->
                attemptRecovery(state)
            }
    }
    
    /**
     * Attempt recovery for a specific service.
     */
    private suspend fun attemptRecovery(state: DegradationState) {
        val policy = servicePolicies[state.serviceType] ?: return
        
        if (!policy.enableAutoRecovery) {
            return
        }
        
        state.lastRecoveryAttemptMs = System.currentTimeMillis()
        
        // If consecutive failures is 0, we can recover
        if (state.consecutiveFailures == 0) {
            state.level = DegradationLevel.NORMAL
            state.activeStrategy.clear()
            state.degradedSinceMs = 0
            totalRecoveryEvents++
        }
    }
    
    /**
     * Get degradation statistics.
     */
    fun getStatistics(): DegradationStatistics {
        val serviceLevels = serviceStates.values.groupBy { it.level }
        
        return DegradationStatistics(
            totalServices = serviceStates.size,
            normalCount = serviceLevels[DegradationLevel.NORMAL]?.size ?: 0,
            reducedCount = serviceLevels[DegradationLevel.REDUCED]?.size ?: 0,
            minimalCount = serviceLevels[DegradationLevel.MINIMAL]?.size ?: 0,
            emergencyCount = serviceLevels[DegradationLevel.EMERGENCY]?.size ?: 0,
            unavailableCount = serviceLevels[DegradationLevel.UNAVAILABLE]?.size ?: 0,
            totalDegradationEvents = totalDegradationEvents,
            totalRecoveryEvents = totalRecoveryEvents,
            totalFallbackActivations = totalFallbackActivations
        )
    }
    
    /**
     * Get detailed service states.
     */
    fun getAllServiceStates(): List<ServiceStateInfo> {
        return serviceStates.values.map { state ->
            ServiceStateInfo(
                serviceType = state.serviceType,
                serviceId = state.serviceId,
                level = state.level,
                lastHealthCheckMs = state.lastHealthCheckMs,
                consecutiveFailures = state.consecutiveFailures,
                degradedDurationMs = if (state.degradedSinceMs > 0) {
                    System.currentTimeMillis() - state.degradedSinceMs
                } else 0,
                activeFallbackStrategies = state.activeStrategy.toList()
            )
        }
    }
    
    /**
     * Shutdown degradation manager.
     */
    fun shutdown() {
        stopMonitoring()
        serviceStates.clear()
        scope.cancel()
    }
    
    /**
     * Degradation statistics.
     */
    data class DegradationStatistics(
        val totalServices: Int,
        val normalCount: Int,
        val reducedCount: Int,
        val minimalCount: Int,
        val emergencyCount: Int,
        val unavailableCount: Int,
        val totalDegradationEvents: Long,
        val totalRecoveryEvents: Long,
        val totalFallbackActivations: Long
    ) {
        val healthyPercent: Double
            get() = if (totalServices > 0) {
                (normalCount.toDouble() / totalServices) * 100
            } else 0.0
    }
    
    /**
     * Service state information.
     */
    data class ServiceStateInfo(
        val serviceType: ServiceType,
        val serviceId: String,
        val level: DegradationLevel,
        val lastHealthCheckMs: Long,
        val consecutiveFailures: Int,
        val degradedDurationMs: Long,
        val activeFallbackStrategies: List<FallbackStrategy>
    )
}
