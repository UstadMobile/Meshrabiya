package org.torproject.meshrabiya.compute.rollout

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.hours

/**
 * CanaryDeploymentManager
 * 
 * Manages Phase 1 of the rollout: Canary deployment to 5% of mesh nodes.
 * 
 * Features:
 * - Intelligent node selection (most reliable nodes first)
 * - Real-time health monitoring with automated rollback
 * - Staged canary progression (1 node → 5% of nodes)
 * - Comprehensive metrics collection
 * - Automatic rollback on critical errors
 * 
 * Canary Strategy:
 * - Stage 1: Deploy to 1 node (24 hours monitoring)
 * - Stage 2: Deploy to remaining 4% of nodes (72 hours monitoring)
 * - Success criteria: 0 critical errors, <2.5% overhead, 100% test success
 * 
 * @property nodeSelector Strategy for selecting canary nodes
 * @property healthMonitor Monitors canary node health
 * @property metricsCollector Collects deployment metrics
 * @property featureFlagManager Controls feature flag state
 */
class CanaryDeploymentManager(
    private val nodeSelector: NodeSelector,
    private val healthMonitor: HealthMonitor,
    private val metricsCollector: CanaryMetricsCollector,
    private val featureFlagManager: FeatureFlagManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val _state = MutableStateFlow<CanaryState>(CanaryState.NotStarted)
    val state: StateFlow<CanaryState> = _state.asStateFlow()
    
    private val canaryNodes = ConcurrentHashMap<String, CanaryNodeInfo>()
    private val isMonitoring = AtomicBoolean(false)
    private var monitoringJob: Job? = null
    
    private val rollbackTriggers = listOf(
        RollbackTrigger.CriticalError,
        RollbackTrigger.PerformanceDegradation(threshold = 5.0), // >5% degradation
        RollbackTrigger.TestFailureRate(threshold = 5.0) // >5% failure rate
    )
    
    /**
     * Start canary deployment
     * 
     * Stage 1: Deploy to 1 node, monitor 24 hours
     * Stage 2: Deploy to 4% more nodes, monitor 72 hours
     * 
     * @param totalNodes Total nodes in mesh (for calculating 5%)
     * @return Result indicating success or failure reason
     */
    suspend fun startCanary(totalNodes: Int): CanaryResult {
        if (_state.value !is CanaryState.NotStarted) {
            return CanaryResult.Failure("Canary already started or completed")
        }
        
        _state.value = CanaryState.Selecting
        
        // Calculate canary size (5% of total, minimum 1 node)
        val canarySize = maxOf(1, (totalNodes * 0.05).toInt())
        
        // Stage 1: Select single most reliable node
        val stage1Node = nodeSelector.selectCanaryNodes(1).firstOrNull()
            ?: return CanaryResult.Failure("No suitable nodes found for canary")
        
        // Deploy to stage 1 node
        _state.value = CanaryState.DeployingStage1(stage1Node)
        val stage1Result = deployToNode(stage1Node, stage = 1)
        
        if (!stage1Result.success) {
            _state.value = CanaryState.Failed(stage1Result.error ?: "Unknown error")
            return CanaryResult.Failure(stage1Result.error ?: "Stage 1 deployment failed")
        }
        
        // Monitor stage 1 for 24 hours
        _state.value = CanaryState.MonitoringStage1(stage1Node)
        val stage1Health = monitorStage(
            nodes = listOf(stage1Node),
            duration = 24.hours,
            stage = 1
        )
        
        if (!stage1Health.healthy) {
            rollback("Stage 1 health check failed: ${stage1Health.reason}")
            return CanaryResult.Failure("Stage 1 failed: ${stage1Health.reason}")
        }
        
        // Stage 2: Select remaining 4% of nodes
        val stage2Nodes = nodeSelector.selectCanaryNodes(canarySize - 1)
        if (stage2Nodes.isEmpty()) {
            return CanaryResult.Failure("No additional nodes available for stage 2")
        }
        
        // Deploy to stage 2 nodes
        _state.value = CanaryState.DeployingStage2(stage2Nodes)
        val stage2Results = stage2Nodes.map { node ->
            node to deployToNode(node, stage = 2)
        }
        
        val failedNodes = stage2Results.filter { !it.second.success }
        if (failedNodes.isNotEmpty()) {
            rollback("Stage 2 deployment failed on ${failedNodes.size} nodes")
            return CanaryResult.Failure("Stage 2 deployment failed")
        }
        
        // Monitor stage 2 for 72 hours
        _state.value = CanaryState.MonitoringStage2(stage1Node, stage2Nodes)
        val stage2Health = monitorStage(
            nodes = listOf(stage1Node) + stage2Nodes,
            duration = 72.hours,
            stage = 2
        )
        
        if (!stage2Health.healthy) {
            rollback("Stage 2 health check failed: ${stage2Health.reason}")
            return CanaryResult.Failure("Stage 2 failed: ${stage2Health.reason}")
        }
        
        // Success!
        _state.value = CanaryState.Success(listOf(stage1Node) + stage2Nodes)
        return CanaryResult.Success(
            nodes = listOf(stage1Node) + stage2Nodes,
            metrics = metricsCollector.getCanaryMetrics()
        )
    }
    
    /**
     * Deploy to a single node
     * 
     * Steps:
     * 1. Deploy code (feature flag disabled)
     * 2. Verify deployment health
     * 3. Enable feature flag
     * 4. Run test tasks
     * 5. Register node in canary set
     */
    private suspend fun deployToNode(nodeId: String, stage: Int): DeploymentResult {
        try {
            // 1. Deploy code (feature flag disabled initially)
            val deployed = deployCode(nodeId)
            if (!deployed) {
                return DeploymentResult(false, "Code deployment failed")
            }
            
            // 2. Verify deployment health
            delay(10_000) // Wait for node to stabilize
            val healthy = healthMonitor.checkNodeHealth(nodeId)
            if (!healthy) {
                return DeploymentResult(false, "Node health check failed after deployment")
            }
            
            // 3. Enable feature flag on this node
            val flagEnabled = featureFlagManager.enableForNode(
                nodeId = nodeId,
                flag = "TASK_KEYPAIR_ENABLED"
            )
            if (!flagEnabled) {
                return DeploymentResult(false, "Failed to enable feature flag")
            }
            
            // 4. Run test tasks (only on stage 1 node)
            if (stage == 1) {
                val testsPassed = runTestTasks(nodeId, count = 10)
                if (!testsPassed) {
                    return DeploymentResult(false, "Test tasks failed")
                }
            }
            
            // 5. Register node in canary set
            canaryNodes[nodeId] = CanaryNodeInfo(
                nodeId = nodeId,
                deployedAt = Instant.now(),
                stage = stage,
                healthy = true
            )
            
            metricsCollector.recordDeployment(nodeId, stage, success = true)
            return DeploymentResult(true)
            
        } catch (e: Exception) {
            metricsCollector.recordDeployment(nodeId, stage, success = false)
            return DeploymentResult(false, "Exception: ${e.message}")
        }
    }
    
    /**
     * Monitor canary stage for specified duration
     * 
     * Checks:
     * - No critical errors
     * - Performance overhead <2.5%
     * - Test task success rate 100% (stage 1) or >95% (stage 2)
     * - No security violations
     */
    private suspend fun monitorStage(
        nodes: List<String>,
        duration: Duration,
        stage: Int
    ): HealthResult {
        val endTime = Instant.now().plusMillis(duration.inWholeMilliseconds)
        
        isMonitoring.set(true)
        monitoringJob = scope.launch {
            while (Instant.now().isBefore(endTime) && isActive) {
                // Check all nodes
                for (nodeId in nodes) {
                    val nodeInfo = canaryNodes[nodeId] ?: continue
                    
                    // Health check
                    val healthy = healthMonitor.checkNodeHealth(nodeId)
                    if (!healthy) {
                        canaryNodes[nodeId] = nodeInfo.copy(healthy = false)
                        rollback("Node $nodeId failed health check")
                        return@launch
                    }
                    
                    // Check rollback triggers
                    for (trigger in rollbackTriggers) {
                        if (trigger.shouldRollback(nodeId, metricsCollector)) {
                            rollback("Rollback trigger activated: ${trigger.reason(nodeId)}")
                            return@launch
                        }
                    }
                    
                    metricsCollector.recordHealthCheck(nodeId, healthy = true)
                }
                
                delay(1.minutes.inWholeMilliseconds)
            }
        }
        
        monitoringJob?.join()
        isMonitoring.set(false)
        
        // Check final state
        val currentState = _state.value
        return when (currentState) {
            is CanaryState.Failed, is CanaryState.RolledBack -> {
                HealthResult(false, currentState.toString())
            }
            else -> {
                // Verify success criteria
                val metrics = metricsCollector.getCanaryMetrics()
                val successCriteria = when (stage) {
                    1 -> SuccessCriteria(
                        maxCriticalErrors = 0,
                        maxPerformanceOverhead = 2.5,
                        minTestSuccessRate = 100.0
                    )
                    2 -> SuccessCriteria(
                        maxCriticalErrors = 0,
                        maxPerformanceOverhead = 2.5,
                        minTestSuccessRate = 95.0
                    )
                    else -> error("Invalid stage: $stage")
                }
                
                if (metrics.criticalErrors > successCriteria.maxCriticalErrors) {
                    HealthResult(false, "Critical errors: ${metrics.criticalErrors}")
                } else if (metrics.performanceOverhead > successCriteria.maxPerformanceOverhead) {
                    HealthResult(false, "Performance overhead: ${metrics.performanceOverhead}%")
                } else if (metrics.testSuccessRate < successCriteria.minTestSuccessRate) {
                    HealthResult(false, "Test success rate: ${metrics.testSuccessRate}%")
                } else {
                    HealthResult(true)
                }
            }
        }
    }
    
    /**
     * Rollback canary deployment
     * 
     * Steps:
     * 1. Disable feature flag on all canary nodes
     * 2. Wait for in-flight tasks to complete
     * 3. Mark deployment as rolled back
     */
    private suspend fun rollback(reason: String) {
        _state.value = CanaryState.RollingBack(reason)
        
        // Disable feature flag on all canary nodes
        for ((nodeId, _) in canaryNodes) {
            featureFlagManager.disableForNode(nodeId, "TASK_KEYPAIR_ENABLED")
        }
        
        // Wait for in-flight tasks (max 10 minutes)
        delay(10.minutes.inWholeMilliseconds)
        
        _state.value = CanaryState.RolledBack(reason)
        metricsCollector.recordRollback(reason)
    }
    
    /**
     * Get current canary status
     */
    fun getStatus(): CanaryStatus {
        val currentState = _state.value
        val metrics = metricsCollector.getCanaryMetrics()
        
        return CanaryStatus(
            state = currentState,
            nodes = canaryNodes.values.toList(),
            metrics = metrics,
            isMonitoring = isMonitoring.get()
        )
    }
    
    /**
     * Stop monitoring and clean up
     */
    fun shutdown() {
        monitoringJob?.cancel()
        isMonitoring.set(false)
        scope.cancel()
    }
    
    // ========== Helper Methods ==========
    
    private suspend fun deployCode(nodeId: String): Boolean {
        // In real implementation, this would deploy via kubectl or similar
        // For now, simulate deployment
        delay(5000)
        return true
    }
    
    private suspend fun runTestTasks(nodeId: String, count: Int): Boolean {
        // Run test tasks on the node
        // In real implementation, this would submit actual test tasks
        for (i in 1..count) {
            delay(1000)
            val success = healthMonitor.runTestTask(nodeId, taskId = "test-$i")
            if (!success) {
                return false
            }
            metricsCollector.recordTestTask(nodeId, success = true)
        }
        return true
    }
}

// ========== Data Classes ==========

/**
 * Canary deployment state
 */
sealed class CanaryState {
    object NotStarted : CanaryState()
    object Selecting : CanaryState()
    data class DeployingStage1(val node: String) : CanaryState()
    data class MonitoringStage1(val node: String) : CanaryState()
    data class DeployingStage2(val nodes: List<String>) : CanaryState()
    data class MonitoringStage2(val stage1Node: String, val stage2Nodes: List<String>) : CanaryState()
    data class RollingBack(val reason: String) : CanaryState()
    data class RolledBack(val reason: String) : CanaryState()
    data class Failed(val reason: String) : CanaryState()
    data class Success(val nodes: List<String>) : CanaryState()
}

/**
 * Canary node information
 */
data class CanaryNodeInfo(
    val nodeId: String,
    val deployedAt: Instant,
    val stage: Int,
    val healthy: Boolean
)

/**
 * Canary deployment result
 */
sealed class CanaryResult {
    data class Success(val nodes: List<String>, val metrics: CanaryMetrics) : CanaryResult()
    data class Failure(val reason: String) : CanaryResult()
}

/**
 * Node deployment result
 */
data class DeploymentResult(
    val success: Boolean,
    val error: String? = null
)

/**
 * Health monitoring result
 */
data class HealthResult(
    val healthy: Boolean,
    val reason: String? = null
)

/**
 * Success criteria for canary stage
 */
data class SuccessCriteria(
    val maxCriticalErrors: Int,
    val maxPerformanceOverhead: Double,
    val minTestSuccessRate: Double
)

/**
 * Canary status snapshot
 */
data class CanaryStatus(
    val state: CanaryState,
    val nodes: List<CanaryNodeInfo>,
    val metrics: CanaryMetrics,
    val isMonitoring: Boolean
)

/**
 * Canary metrics
 */
data class CanaryMetrics(
    val deploymentsTotal: Int,
    val deploymentsSuccessful: Int,
    val criticalErrors: Int,
    val performanceOverhead: Double, // percentage
    val testTasksTotal: Int,
    val testTasksSuccessful: Int,
    val testSuccessRate: Double, // percentage
    val healthChecksTotal: Int,
    val healthChecksSuccessful: Int,
    val rolledBack: Boolean,
    val rollbackReason: String? = null
)

// ========== Rollback Triggers ==========

/**
 * Rollback trigger
 * 
 * Defines conditions that trigger automatic rollback
 */
sealed class RollbackTrigger {
    abstract fun shouldRollback(nodeId: String, metrics: CanaryMetricsCollector): Boolean
    abstract fun reason(nodeId: String): String
    
    /**
     * Rollback on any critical error
     */
    object CriticalError : RollbackTrigger() {
        override fun shouldRollback(nodeId: String, metrics: CanaryMetricsCollector): Boolean {
            val canaryMetrics = metrics.getCanaryMetrics()
            return canaryMetrics.criticalErrors > 0
        }
        
        override fun reason(nodeId: String): String = "Critical error detected"
    }
    
    /**
     * Rollback on performance degradation
     */
    data class PerformanceDegradation(val threshold: Double) : RollbackTrigger() {
        override fun shouldRollback(nodeId: String, metrics: CanaryMetricsCollector): Boolean {
            val canaryMetrics = metrics.getCanaryMetrics()
            return canaryMetrics.performanceOverhead > threshold
        }
        
        override fun reason(nodeId: String): String =
            "Performance degradation >$threshold%"
    }
    
    /**
     * Rollback on high test failure rate
     */
    data class TestFailureRate(val threshold: Double) : RollbackTrigger() {
        override fun shouldRollback(nodeId: String, metrics: CanaryMetricsCollector): Boolean {
            val canaryMetrics = metrics.getCanaryMetrics()
            val failureRate = 100.0 - canaryMetrics.testSuccessRate
            return failureRate > threshold
        }
        
        override fun reason(nodeId: String): String =
            "Test failure rate >$threshold%"
    }
}

// ========== Node Selector ==========

/**
 * NodeSelector
 * 
 * Selects most reliable nodes for canary deployment
 */
interface NodeSelector {
    /**
     * Select N most reliable nodes for canary deployment
     * 
     * Selection criteria:
     * - Highest uptime
     * - Lowest error rate
     * - Sufficient resources
     * - Internal team or volunteer nodes first
     */
    fun selectCanaryNodes(count: Int): List<String>
}

/**
 * Default implementation: selects by reliability score
 */
class ReliabilityBasedNodeSelector(
    private val nodeRegistry: NodeRegistry
) : NodeSelector {
    override fun selectCanaryNodes(count: Int): List<String> {
        return nodeRegistry.getAllNodes()
            .sortedByDescending { it.reliabilityScore }
            .filter { it.availableForCanary }
            .take(count)
            .map { it.nodeId }
    }
}

/**
 * Node information for selection
 */
data class NodeInfo(
    val nodeId: String,
    val reliabilityScore: Double, // 0.0 to 1.0
    val uptime: Double, // percentage
    val errorRate: Double, // percentage
    val availableForCanary: Boolean
)

/**
 * Node registry interface
 */
interface NodeRegistry {
    fun getAllNodes(): List<NodeInfo>
    fun getNode(nodeId: String): NodeInfo?
}

// ========== Health Monitor ==========

/**
 * HealthMonitor
 * 
 * Monitors canary node health
 */
interface HealthMonitor {
    /**
     * Check if node is healthy
     * 
     * Checks:
     * - Node is reachable
     * - Services are running
     * - No recent crashes
     * - Resource usage normal
     */
    suspend fun checkNodeHealth(nodeId: String): Boolean
    
    /**
     * Run a test task on the node
     */
    suspend fun runTestTask(nodeId: String, taskId: String): Boolean
}

/**
 * Default health monitor implementation
 */
class DefaultHealthMonitor : HealthMonitor {
    override suspend fun checkNodeHealth(nodeId: String): Boolean {
        // In real implementation, check node health via API
        // For now, simulate health check
        delay(100)
        return true
    }
    
    override suspend fun runTestTask(nodeId: String, taskId: String): Boolean {
        // In real implementation, submit test task and verify result
        delay(1000)
        return true
    }
}

// ========== Canary Metrics Collector ==========

/**
 * CanaryMetricsCollector
 * 
 * Collects metrics for canary deployment
 */
class CanaryMetricsCollector {
    private val deploymentsTotal = AtomicInteger(0)
    private val deploymentsSuccessful = AtomicInteger(0)
    private val criticalErrors = AtomicInteger(0)
    private val testTasksTotal = AtomicInteger(0)
    private val testTasksSuccessful = AtomicInteger(0)
    private val healthChecksTotal = AtomicInteger(0)
    private val healthChecksSuccessful = AtomicInteger(0)
    
    private val performanceMetrics = ConcurrentHashMap<String, Double>()
    private var rolledBack = false
    private var rollbackReason: String? = null
    
    fun recordDeployment(nodeId: String, stage: Int, success: Boolean) {
        deploymentsTotal.incrementAndGet()
        if (success) {
            deploymentsSuccessful.incrementAndGet()
        }
    }
    
    fun recordCriticalError(nodeId: String, error: String) {
        criticalErrors.incrementAndGet()
    }
    
    fun recordTestTask(nodeId: String, success: Boolean) {
        testTasksTotal.incrementAndGet()
        if (success) {
            testTasksSuccessful.incrementAndGet()
        }
    }
    
    fun recordHealthCheck(nodeId: String, healthy: Boolean) {
        healthChecksTotal.incrementAndGet()
        if (healthy) {
            healthChecksSuccessful.incrementAndGet()
        }
    }
    
    fun recordPerformance(nodeId: String, overhead: Double) {
        performanceMetrics[nodeId] = overhead
    }
    
    fun recordRollback(reason: String) {
        rolledBack = true
        rollbackReason = reason
    }
    
    fun getCanaryMetrics(): CanaryMetrics {
        val testSuccessRate = if (testTasksTotal.get() > 0) {
            (testTasksSuccessful.get().toDouble() / testTasksTotal.get()) * 100.0
        } else {
            100.0
        }
        
        val avgPerformanceOverhead = if (performanceMetrics.isNotEmpty()) {
            performanceMetrics.values.average()
        } else {
            0.0
        }
        
        return CanaryMetrics(
            deploymentsTotal = deploymentsTotal.get(),
            deploymentsSuccessful = deploymentsSuccessful.get(),
            criticalErrors = criticalErrors.get(),
            performanceOverhead = avgPerformanceOverhead,
            testTasksTotal = testTasksTotal.get(),
            testTasksSuccessful = testTasksSuccessful.get(),
            testSuccessRate = testSuccessRate,
            healthChecksTotal = healthChecksTotal.get(),
            healthChecksSuccessful = healthChecksSuccessful.get(),
            rolledBack = rolledBack,
            rollbackReason = rollbackReason
        )
    }
    
    fun reset() {
        deploymentsTotal.set(0)
        deploymentsSuccessful.set(0)
        criticalErrors.set(0)
        testTasksTotal.set(0)
        testTasksSuccessful.set(0)
        healthChecksTotal.set(0)
        healthChecksSuccessful.set(0)
        performanceMetrics.clear()
        rolledBack = false
        rollbackReason = null
    }
}

// ========== Feature Flag Manager Interface ==========

/**
 * FeatureFlagManager interface for canary deployment
 */
interface FeatureFlagManager {
    suspend fun enableForNode(nodeId: String, flag: String): Boolean
    suspend fun disableForNode(nodeId: String, flag: String): Boolean
    suspend fun isEnabledForNode(nodeId: String, flag: String): Boolean
}
