package org.torproject.meshrabiya.compute.rollout

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * StagedRolloutController
 * 
 * Manages Phase 3 of the rollout: Staged deployment from 25% to 100% of mesh nodes.
 * 
 * Features:
 * - Three-stage gradual rollout (50% → 75% → 100%)
 * - Per-stage validation with automatic progression
 * - Pause/resume capability for manual intervention
 * - Real-time monitoring with automated rollback
 * - Progressive confidence building (success at each stage required)
 * 
 * Staged Strategy:
 * - Week 4: Deploy to 50% of nodes (25% → 50%)
 * - Week 5: Deploy to 75% of nodes (50% → 75%)
 * - Week 6: Deploy to 100% of nodes (75% → 100%)
 * - Monitor each stage for 1 week before progression
 * - Success criteria: <10 issues/week, <2% overhead, >99% success rate
 * 
 * @property betaNodes Nodes from successful beta deployment
 * @property nodeSelector Strategy for selecting rollout nodes
 * @property stageValidator Validates each stage before progression
 * @property progressionEngine Controls automatic progression
 * @property metricsCollector Collects deployment metrics
 * @property featureFlagManager Controls feature flag state
 */
class StagedRolloutController(
    private val betaNodes: List<String>,
    private val nodeSelector: NodeSelector,
    private val stageValidator: StageValidator,
    private val progressionEngine: ProgressionEngine,
    private val metricsCollector: StagedMetricsCollector,
    private val featureFlagManager: FeatureFlagManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val _state = MutableStateFlow<StagedRolloutState>(StagedRolloutState.NotStarted)
    val state: StateFlow<StagedRolloutState> = _state.asStateFlow()
    
    private val rolloutNodes = ConcurrentHashMap<String, RolloutNodeInfo>()
    private val isPaused = AtomicBoolean(false)
    private val isMonitoring = AtomicBoolean(false)
    private var monitoringJob: Job? = null
    
    private val rollbackTriggers = listOf(
        StagedRollbackTrigger.UserIssues(threshold = 10), // >10 issues/week
        StagedRollbackTrigger.PerformanceDegradation(threshold = 2.0), // >2% degradation
        StagedRollbackTrigger.TaskFailureRate(threshold = 1.0), // >1% failure rate
        StagedRollbackTrigger.SecurityIncident
    )
    
    /**
     * Start staged rollout
     * 
     * Week 4: 25% → 50%
     * Week 5: 50% → 75%
     * Week 6: 75% → 100%
     * 
     * @param totalNodes Total nodes in mesh
     * @return Result indicating success or failure reason
     */
    suspend fun startRollout(totalNodes: Int): StagedRolloutResult {
        if (_state.value !is StagedRolloutState.NotStarted) {
            return StagedRolloutResult.Failure("Rollout already started or completed")
        }
        
        // Stage 1: 25% → 50%
        val stage1Result = executeStage(
            stage = 1,
            targetPercentage = 50.0,
            totalNodes = totalNodes,
            duration = 7.days
        )
        
        if (stage1Result is StageResult.Failure) {
            return StagedRolloutResult.Failure("Stage 1 failed: ${stage1Result.reason}")
        }
        
        // Stage 2: 50% → 75%
        val stage2Result = executeStage(
            stage = 2,
            targetPercentage = 75.0,
            totalNodes = totalNodes,
            duration = 7.days
        )
        
        if (stage2Result is StageResult.Failure) {
            return StagedRolloutResult.Failure("Stage 2 failed: ${stage2Result.reason}")
        }
        
        // Stage 3: 75% → 100%
        val stage3Result = executeStage(
            stage = 3,
            targetPercentage = 100.0,
            totalNodes = totalNodes,
            duration = 7.days
        )
        
        if (stage3Result is StageResult.Failure) {
            return StagedRolloutResult.Failure("Stage 3 failed: ${stage3Result.reason}")
        }
        
        // Success!
        _state.value = StagedRolloutState.Complete(rolloutNodes.keys.toList())
        return StagedRolloutResult.Success(
            nodes = rolloutNodes.keys.toList(),
            metrics = metricsCollector.getRolloutMetrics()
        )
    }
    
    /**
     * Execute a single rollout stage
     */
    private suspend fun executeStage(
        stage: Int,
        targetPercentage: Double,
        totalNodes: Int,
        duration: Duration
    ): StageResult {
        // Calculate nodes to deploy
        val currentDeployed = betaNodes.size + rolloutNodes.size
        val targetCount = (totalNodes * (targetPercentage / 100.0)).toInt()
        val nodesToDeploy = targetCount - currentDeployed
        
        if (nodesToDeploy <= 0) {
            return StageResult.Success(emptyList())
        }
        
        // Select nodes for this stage
        _state.value = StagedRolloutState.SelectingStage(stage, targetPercentage)
        val stageNodes = nodeSelector.selectCanaryNodes(nodesToDeploy)
            .filter { it !in betaNodes && it !in rolloutNodes.keys }
        
        if (stageNodes.isEmpty()) {
            return StageResult.Failure("No nodes available for stage $stage")
        }
        
        // Deploy to stage nodes
        _state.value = StagedRolloutState.DeployingStage(stage, targetPercentage, stageNodes)
        val deployResults = deployToNodes(stageNodes, stage)
        val failedDeployments = deployResults.filter { !it.value.success }
        
        if (failedDeployments.isNotEmpty()) {
            rollback("Stage $stage deployment failed on ${failedDeployments.size} nodes")
            return StageResult.Failure("Deployment failed on ${failedDeployments.size} nodes")
        }
        
        // Monitor stage
        _state.value = StagedRolloutState.MonitoringStage(
            stage = stage,
            percentage = targetPercentage,
            nodes = rolloutNodes.keys.toList()
        )
        
        val monitorResult = monitorStage(
            stage = stage,
            nodes = rolloutNodes.keys.toList(),
            duration = duration
        )
        
        if (!monitorResult.healthy) {
            rollback("Stage $stage monitoring failed: ${monitorResult.reason}")
            return StageResult.Failure(monitorResult.reason ?: "Monitoring failed")
        }
        
        // Validate stage before progression
        _state.value = StagedRolloutState.ValidatingStage(stage, targetPercentage)
        val validationResult = stageValidator.validate(
            stage = stage,
            nodes = rolloutNodes.keys.toList(),
            metrics = metricsCollector.getRolloutMetrics()
        )
        
        if (!validationResult.valid) {
            rollback("Stage $stage validation failed: ${validationResult.reason}")
            return StageResult.Failure(validationResult.reason)
        }
        
        // Progression approval (can be manual or automatic)
        _state.value = StagedRolloutState.AwaitingProgression(stage, targetPercentage)
        val approved = progressionEngine.awaitApproval(stage, validationResult)
        
        if (!approved) {
            return StageResult.Failure("Progression not approved for stage $stage")
        }
        
        return StageResult.Success(stageNodes)
    }
    
    /**
     * Deploy to multiple nodes in parallel
     */
    private suspend fun deployToNodes(
        nodeIds: List<String>,
        stage: Int
    ): Map<String, DeploymentResult> = coroutineScope {
        nodeIds.associateWith { nodeId ->
            async {
                try {
                    // Check if paused
                    while (isPaused.get()) {
                        delay(5000)
                    }
                    
                    // Deploy code (feature flag disabled)
                    val deployed = deployCode(nodeId)
                    if (!deployed) {
                        return@async DeploymentResult(false, "Code deployment failed")
                    }
                    
                    // Wait for stabilization
                    delay(10_000)
                    
                    // Enable feature flag
                    val flagEnabled = featureFlagManager.enableForNode(
                        nodeId = nodeId,
                        flag = "TASK_KEYPAIR_ENABLED"
                    )
                    if (!flagEnabled) {
                        return@async DeploymentResult(false, "Feature flag enable failed")
                    }
                    
                    // Register node
                    rolloutNodes[nodeId] = RolloutNodeInfo(
                        nodeId = nodeId,
                        deployedAt = Instant.now(),
                        stage = stage,
                        healthy = true
                    )
                    
                    metricsCollector.recordDeployment(nodeId, stage, success = true)
                    DeploymentResult(true)
                    
                } catch (e: Exception) {
                    metricsCollector.recordDeployment(nodeId, stage, success = false)
                    DeploymentResult(false, "Exception: ${e.message}")
                }
            }
        }.mapValues { it.value.await() }
    }
    
    /**
     * Monitor stage for specified duration
     */
    private suspend fun monitorStage(
        stage: Int,
        nodes: List<String>,
        duration: Duration
    ): HealthResult {
        val endTime = Instant.now().plusMillis(duration.inWholeMilliseconds)
        
        isMonitoring.set(true)
        monitoringJob = scope.launch {
            while (Instant.now().isBefore(endTime) && isActive) {
                // Check if paused
                if (isPaused.get()) {
                    delay(5000)
                    continue
                }
                
                // Collect metrics
                val metrics = metricsCollector.getRolloutMetrics()
                
                // Check rollback triggers
                for (trigger in rollbackTriggers) {
                    if (trigger.shouldRollback(stage, metrics)) {
                        rollback("Rollback trigger activated: ${trigger.reason()}")
                        return@launch
                    }
                }
                
                metricsCollector.recordMonitoringCheck(stage)
                delay(1.minutes.inWholeMilliseconds)
            }
        }
        
        monitoringJob?.join()
        isMonitoring.set(false)
        
        // Check final state
        val currentState = _state.value
        return when (currentState) {
            is StagedRolloutState.Failed, is StagedRolloutState.RolledBack -> {
                HealthResult(false, currentState.toString())
            }
            else -> {
                // Verify success criteria
                val metrics = metricsCollector.getRolloutMetrics()
                val successCriteria = StagedSuccessCriteria(
                    maxUserIssuesPerWeek = 10,
                    maxPerformanceOverhead = 2.0,
                    minTaskSuccessRate = 99.0,
                    maxSecurityIncidents = 0
                )
                
                if (metrics.userIssuesThisWeek > successCriteria.maxUserIssuesPerWeek) {
                    HealthResult(false, "User issues: ${metrics.userIssuesThisWeek}/week")
                } else if (metrics.performanceOverhead > successCriteria.maxPerformanceOverhead) {
                    HealthResult(false, "Performance overhead: ${metrics.performanceOverhead}%")
                } else if (metrics.taskSuccessRate < successCriteria.minTaskSuccessRate) {
                    HealthResult(false, "Task success rate: ${metrics.taskSuccessRate}%")
                } else if (metrics.securityIncidents > successCriteria.maxSecurityIncidents) {
                    HealthResult(false, "Security incidents: ${metrics.securityIncidents}")
                } else {
                    HealthResult(true)
                }
            }
        }
    }
    
    /**
     * Pause rollout
     * 
     * Useful for manual intervention or investigation
     */
    fun pause() {
        isPaused.set(true)
        _state.value = StagedRolloutState.Paused
    }
    
    /**
     * Resume rollout
     */
    fun resume() {
        isPaused.set(false)
        // Restore previous state (monitoring or deploying)
        // In real implementation, track previous state
    }
    
    /**
     * Rollback staged rollout
     */
    private suspend fun rollback(reason: String) {
        _state.value = StagedRolloutState.RollingBack(reason)
        
        // Disable feature flag on all rollout nodes (excluding beta)
        for ((nodeId, _) in rolloutNodes) {
            if (nodeId !in betaNodes) {
                featureFlagManager.disableForNode(nodeId, "TASK_KEYPAIR_ENABLED")
            }
        }
        
        // Wait for in-flight tasks
        delay(10.minutes.inWholeMilliseconds)
        
        _state.value = StagedRolloutState.RolledBack(reason)
        metricsCollector.recordRollback(reason)
    }
    
    /**
     * Get current rollout status
     */
    fun getStatus(): StagedRolloutStatus {
        val currentState = _state.value
        val metrics = metricsCollector.getRolloutMetrics()
        
        return StagedRolloutStatus(
            state = currentState,
            nodes = rolloutNodes.values.toList(),
            metrics = metrics,
            isPaused = isPaused.get(),
            isMonitoring = isMonitoring.get()
        )
    }
    
    /**
     * Shutdown controller
     */
    fun shutdown() {
        monitoringJob?.cancel()
        isMonitoring.set(false)
        isPaused.set(false)
        scope.cancel()
    }
    
    // ========== Helper Methods ==========
    
    private suspend fun deployCode(nodeId: String): Boolean {
        delay(5000)
        return true
    }
}

// ========== Data Classes ==========

/**
 * Staged rollout state
 */
sealed class StagedRolloutState {
    object NotStarted : StagedRolloutState()
    data class SelectingStage(val stage: Int, val targetPercentage: Double) : StagedRolloutState()
    data class DeployingStage(val stage: Int, val targetPercentage: Double, val nodes: List<String>) : StagedRolloutState()
    data class MonitoringStage(val stage: Int, val percentage: Double, val nodes: List<String>) : StagedRolloutState()
    data class ValidatingStage(val stage: Int, val percentage: Double) : StagedRolloutState()
    data class AwaitingProgression(val stage: Int, val percentage: Double) : StagedRolloutState()
    object Paused : StagedRolloutState()
    data class RollingBack(val reason: String) : StagedRolloutState()
    data class RolledBack(val reason: String) : StagedRolloutState()
    data class Failed(val reason: String) : StagedRolloutState()
    data class Complete(val nodes: List<String>) : StagedRolloutState()
}

/**
 * Rollout node information
 */
data class RolloutNodeInfo(
    val nodeId: String,
    val deployedAt: Instant,
    val stage: Int,
    val healthy: Boolean
)

/**
 * Stage execution result
 */
sealed class StageResult {
    data class Success(val nodes: List<String>) : StageResult()
    data class Failure(val reason: String) : StageResult()
}

/**
 * Staged rollout result
 */
sealed class StagedRolloutResult {
    data class Success(val nodes: List<String>, val metrics: StagedRolloutMetrics) : StagedRolloutResult()
    data class Failure(val reason: String) : StagedRolloutResult()
}

/**
 * Staged success criteria
 */
data class StagedSuccessCriteria(
    val maxUserIssuesPerWeek: Int,
    val maxPerformanceOverhead: Double,
    val minTaskSuccessRate: Double,
    val maxSecurityIncidents: Int
)

/**
 * Staged rollout status snapshot
 */
data class StagedRolloutStatus(
    val state: StagedRolloutState,
    val nodes: List<RolloutNodeInfo>,
    val metrics: StagedRolloutMetrics,
    val isPaused: Boolean,
    val isMonitoring: Boolean
)

/**
 * Staged rollout metrics
 */
data class StagedRolloutMetrics(
    val deploymentsTotal: Int,
    val deploymentsSuccessful: Int,
    val currentStage: Int,
    val currentPercentage: Double,
    val performanceOverhead: Double,
    val taskSuccessRate: Double,
    val userIssuesThisWeek: Int,
    val securityIncidents: Int,
    val rolledBack: Boolean,
    val rollbackReason: String? = null
)

// ========== Staged Rollback Triggers ==========

sealed class StagedRollbackTrigger {
    abstract fun shouldRollback(stage: Int, metrics: StagedRolloutMetrics): Boolean
    abstract fun reason(): String
    
    data class UserIssues(val threshold: Int) : StagedRollbackTrigger() {
        override fun shouldRollback(stage: Int, metrics: StagedRolloutMetrics): Boolean {
            return metrics.userIssuesThisWeek > threshold
        }
        
        override fun reason(): String = "User issues >$threshold/week"
    }
    
    data class PerformanceDegradation(val threshold: Double) : StagedRollbackTrigger() {
        override fun shouldRollback(stage: Int, metrics: StagedRolloutMetrics): Boolean {
            return metrics.performanceOverhead > threshold
        }
        
        override fun reason(): String = "Performance degradation >$threshold%"
    }
    
    data class TaskFailureRate(val threshold: Double) : StagedRollbackTrigger() {
        override fun shouldRollback(stage: Int, metrics: StagedRolloutMetrics): Boolean {
            val failureRate = 100.0 - metrics.taskSuccessRate
            return failureRate > threshold
        }
        
        override fun reason(): String = "Task failure rate >$threshold%"
    }
    
    object SecurityIncident : StagedRollbackTrigger() {
        override fun shouldRollback(stage: Int, metrics: StagedRolloutMetrics): Boolean {
            return metrics.securityIncidents > 0
        }
        
        override fun reason(): String = "Security incident detected"
    }
}

// ========== Stage Validator ==========

/**
 * StageValidator
 * 
 * Validates each stage before progression
 */
interface StageValidator {
    /**
     * Validate stage completion
     * 
     * Checks:
     * - Success criteria met
     * - No critical issues
     * - Performance acceptable
     * - User feedback positive
     */
    suspend fun validate(
        stage: Int,
        nodes: List<String>,
        metrics: StagedRolloutMetrics
    ): ValidationResult
}

/**
 * Validation result
 */
data class ValidationResult(
    val valid: Boolean,
    val reason: String,
    val details: Map<String, Any> = emptyMap()
)

/**
 * Default stage validator implementation
 */
class DefaultStageValidator : StageValidator {
    override suspend fun validate(
        stage: Int,
        nodes: List<String>,
        metrics: StagedRolloutMetrics
    ): ValidationResult {
        // Check success criteria
        val criteria = StagedSuccessCriteria(
            maxUserIssuesPerWeek = 10,
            maxPerformanceOverhead = 2.0,
            minTaskSuccessRate = 99.0,
            maxSecurityIncidents = 0
        )
        
        if (metrics.userIssuesThisWeek > criteria.maxUserIssuesPerWeek) {
            return ValidationResult(
                valid = false,
                reason = "Too many user issues: ${metrics.userIssuesThisWeek}",
                details = mapOf("userIssues" to metrics.userIssuesThisWeek)
            )
        }
        
        if (metrics.performanceOverhead > criteria.maxPerformanceOverhead) {
            return ValidationResult(
                valid = false,
                reason = "Performance overhead too high: ${metrics.performanceOverhead}%",
                details = mapOf("overhead" to metrics.performanceOverhead)
            )
        }
        
        if (metrics.taskSuccessRate < criteria.minTaskSuccessRate) {
            return ValidationResult(
                valid = false,
                reason = "Task success rate too low: ${metrics.taskSuccessRate}%",
                details = mapOf("successRate" to metrics.taskSuccessRate)
            )
        }
        
        if (metrics.securityIncidents > criteria.maxSecurityIncidents) {
            return ValidationResult(
                valid = false,
                reason = "Security incidents detected: ${metrics.securityIncidents}",
                details = mapOf("securityIncidents" to metrics.securityIncidents)
            )
        }
        
        return ValidationResult(
            valid = true,
            reason = "Stage $stage validated successfully",
            details = mapOf(
                "stage" to stage,
                "nodes" to nodes.size,
                "metrics" to metrics
            )
        )
    }
}

// ========== Progression Engine ==========

/**
 * ProgressionEngine
 * 
 * Controls automatic progression between stages
 */
interface ProgressionEngine {
    /**
     * Await approval to progress to next stage
     * 
     * Can be automatic (based on validation result) or manual
     */
    suspend fun awaitApproval(stage: Int, validation: ValidationResult): Boolean
}

/**
 * Automatic progression engine
 * 
 * Automatically approves progression if validation passes
 */
class AutomaticProgressionEngine : ProgressionEngine {
    override suspend fun awaitApproval(stage: Int, validation: ValidationResult): Boolean {
        return validation.valid
    }
}

/**
 * Manual progression engine
 * 
 * Requires manual approval before progression
 */
class ManualProgressionEngine : ProgressionEngine {
    private val approvals = ConcurrentHashMap<Int, Boolean>()
    
    override suspend fun awaitApproval(stage: Int, validation: ValidationResult): Boolean {
        // Wait for manual approval
        while (stage !in approvals) {
            delay(5000)
        }
        return approvals[stage] ?: false
    }
    
    fun approve(stage: Int) {
        approvals[stage] = true
    }
    
    fun reject(stage: Int) {
        approvals[stage] = false
    }
}

// ========== Staged Metrics Collector ==========

/**
 * StagedMetricsCollector
 * 
 * Collects metrics for staged rollout
 */
class StagedMetricsCollector {
    private val deploymentsTotal = AtomicInteger(0)
    private val deploymentsSuccessful = AtomicInteger(0)
    private val currentStage = AtomicInteger(0)
    private val userIssuesThisWeek = AtomicInteger(0)
    private val securityIncidents = AtomicInteger(0)
    
    private var currentPercentage = 0.0
    private var performanceOverhead = 0.0
    private var taskSuccessRate = 99.5
    
    private var rolledBack = false
    private var rollbackReason: String? = null
    
    fun recordDeployment(nodeId: String, stage: Int, success: Boolean) {
        deploymentsTotal.incrementAndGet()
        if (success) {
            deploymentsSuccessful.incrementAndGet()
        }
        currentStage.set(stage)
    }
    
    fun recordMonitoringCheck(stage: Int) {
        // Update metrics from monitoring system
        // In real implementation, query actual metrics
    }
    
    fun recordUserIssue() {
        userIssuesThisWeek.incrementAndGet()
    }
    
    fun recordSecurityIncident() {
        securityIncidents.incrementAndGet()
    }
    
    fun recordRollback(reason: String) {
        rolledBack = true
        rollbackReason = reason
    }
    
    fun updatePercentage(percentage: Double) {
        currentPercentage = percentage
    }
    
    fun getRolloutMetrics(): StagedRolloutMetrics {
        return StagedRolloutMetrics(
            deploymentsTotal = deploymentsTotal.get(),
            deploymentsSuccessful = deploymentsSuccessful.get(),
            currentStage = currentStage.get(),
            currentPercentage = currentPercentage,
            performanceOverhead = performanceOverhead,
            taskSuccessRate = taskSuccessRate,
            userIssuesThisWeek = userIssuesThisWeek.get(),
            securityIncidents = securityIncidents.get(),
            rolledBack = rolledBack,
            rollbackReason = rollbackReason
        )
    }
    
    fun reset() {
        deploymentsTotal.set(0)
        deploymentsSuccessful.set(0)
        currentStage.set(0)
        userIssuesThisWeek.set(0)
        securityIncidents.set(0)
        currentPercentage = 0.0
        performanceOverhead = 0.0
        taskSuccessRate = 99.5
        rolledBack = false
        rollbackReason = null
    }
}
