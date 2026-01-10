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
 * BetaDeploymentManager
 * 
 * Manages Phase 2 of the rollout: Beta deployment to 25% of mesh nodes.
 * 
 * Features:
 * - Gradual rollout to early adopters and power users
 * - Real-time user feedback collection
 * - Performance comparison vs baseline (canary nodes)
 * - Automated progression from 5% to 25%
 * - Rollback on user-reported issues or performance degradation
 * 
 * Beta Strategy:
 * - Week 1: Deploy to 10% additional nodes (total 15%)
 * - Week 2: Deploy to 10% additional nodes (total 25%)
 * - Monitor each week for user feedback and performance
 * - Success criteria: <5 user issues, <3% overhead, >98% success rate
 * 
 * @property canaryNodes Nodes from successful canary deployment
 * @property nodeSelector Strategy for selecting beta nodes
 * @property feedbackCollector Collects user feedback
 * @property performanceComparator Compares performance vs baseline
 * @property metricsCollector Collects deployment metrics
 * @property featureFlagManager Controls feature flag state
 */
class BetaDeploymentManager(
    private val canaryNodes: List<String>,
    private val nodeSelector: NodeSelector,
    private val feedbackCollector: UserFeedbackCollector,
    private val performanceComparator: PerformanceComparator,
    private val metricsCollector: BetaMetricsCollector,
    private val featureFlagManager: FeatureFlagManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val _state = MutableStateFlow<BetaState>(BetaState.NotStarted)
    val state: StateFlow<BetaState> = _state.asStateFlow()
    
    private val betaNodes = ConcurrentHashMap<String, BetaNodeInfo>()
    private val isMonitoring = AtomicBoolean(false)
    private var monitoringJob: Job? = null
    
    private val rollbackTriggers = listOf(
        BetaRollbackTrigger.UserIssues(threshold = 10), // >10 user issues
        BetaRollbackTrigger.PerformanceDegradation(threshold = 5.0), // >5% degradation
        BetaRollbackTrigger.TaskFailureRate(threshold = 5.0) // >5% failure rate
    )
    
    /**
     * Start beta deployment
     * 
     * Week 1: 5% → 15% (10% additional)
     * Week 2: 15% → 25% (10% additional)
     * 
     * @param totalNodes Total nodes in mesh
     * @return Result indicating success or failure reason
     */
    suspend fun startBeta(totalNodes: Int): BetaResult {
        if (_state.value !is BetaState.NotStarted) {
            return BetaResult.Failure("Beta already started or completed")
        }
        
        // Announce beta to user community
        _state.value = BetaState.Announcing
        announceBetaProgram()
        
        // Week 1: Deploy to 10% additional nodes (total 15%)
        _state.value = BetaState.SelectingWeek1
        val week1Target = (totalNodes * 0.15).toInt() - canaryNodes.size
        val week1Nodes = nodeSelector.selectBetaNodes(
            count = week1Target,
            excludeNodes = canaryNodes
        )
        
        if (week1Nodes.isEmpty()) {
            return BetaResult.Failure("No nodes available for week 1 deployment")
        }
        
        // Deploy to week 1 nodes
        _state.value = BetaState.DeployingWeek1(week1Nodes)
        val week1Results = deployToNodes(week1Nodes, week = 1)
        val week1Failures = week1Results.filter { !it.value.success }
        
        if (week1Failures.isNotEmpty()) {
            rollback("Week 1 deployment failed on ${week1Failures.size} nodes")
            return BetaResult.Failure("Week 1 deployment failed")
        }
        
        // Monitor week 1
        _state.value = BetaState.MonitoringWeek1(canaryNodes + week1Nodes)
        val week1Health = monitorWeek(
            nodes = canaryNodes + week1Nodes,
            duration = 7.days,
            week = 1
        )
        
        if (!week1Health.healthy) {
            rollback("Week 1 monitoring failed: ${week1Health.reason}")
            return BetaResult.Failure("Week 1 failed: ${week1Health.reason}")
        }
        
        // Week 2: Deploy to 10% additional nodes (total 25%)
        _state.value = BetaState.SelectingWeek2
        val week2Target = (totalNodes * 0.25).toInt() - canaryNodes.size - week1Nodes.size
        val week2Nodes = nodeSelector.selectBetaNodes(
            count = week2Target,
            excludeNodes = canaryNodes + week1Nodes
        )
        
        if (week2Nodes.isEmpty()) {
            return BetaResult.Failure("No nodes available for week 2 deployment")
        }
        
        // Deploy to week 2 nodes
        _state.value = BetaState.DeployingWeek2(week2Nodes)
        val week2Results = deployToNodes(week2Nodes, week = 2)
        val week2Failures = week2Results.filter { !it.value.success }
        
        if (week2Failures.isNotEmpty()) {
            rollback("Week 2 deployment failed on ${week2Failures.size} nodes")
            return BetaResult.Failure("Week 2 deployment failed")
        }
        
        // Monitor week 2
        val allBetaNodes = canaryNodes + week1Nodes + week2Nodes
        _state.value = BetaState.MonitoringWeek2(allBetaNodes)
        val week2Health = monitorWeek(
            nodes = allBetaNodes,
            duration = 7.days,
            week = 2
        )
        
        if (!week2Health.healthy) {
            rollback("Week 2 monitoring failed: ${week2Health.reason}")
            return BetaResult.Failure("Week 2 failed: ${week2Health.reason}")
        }
        
        // Success!
        _state.value = BetaState.Success(allBetaNodes)
        return BetaResult.Success(
            nodes = allBetaNodes,
            metrics = metricsCollector.getBetaMetrics(),
            userFeedback = feedbackCollector.getSummary()
        )
    }
    
    /**
     * Deploy to multiple nodes in parallel
     */
    private suspend fun deployToNodes(
        nodeIds: List<String>,
        week: Int
    ): Map<String, DeploymentResult> = coroutineScope {
        nodeIds.associateWith { nodeId ->
            async {
                try {
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
                    betaNodes[nodeId] = BetaNodeInfo(
                        nodeId = nodeId,
                        deployedAt = Instant.now(),
                        week = week,
                        healthy = true,
                        userFeedback = mutableListOf()
                    )
                    
                    metricsCollector.recordDeployment(nodeId, week, success = true)
                    DeploymentResult(true)
                    
                } catch (e: Exception) {
                    metricsCollector.recordDeployment(nodeId, week, success = false)
                    DeploymentResult(false, "Exception: ${e.message}")
                }
            }
        }.mapValues { it.value.await() }
    }
    
    /**
     * Monitor beta deployment for one week
     * 
     * Checks:
     * - User feedback (collect and analyze)
     * - Performance vs baseline (<3% overhead)
     * - Task success rate (>98%)
     * - User-reported issues (<5 per week)
     */
    private suspend fun monitorWeek(
        nodes: List<String>,
        duration: Duration,
        week: Int
    ): HealthResult {
        val endTime = Instant.now().plusMillis(duration.inWholeMilliseconds)
        
        isMonitoring.set(true)
        monitoringJob = scope.launch {
            while (Instant.now().isBefore(endTime) && isActive) {
                // Collect user feedback
                val feedback = feedbackCollector.collectRecent(since = 1.days)
                for (fb in feedback) {
                    metricsCollector.recordUserFeedback(fb)
                    
                    // Track per-node feedback if available
                    fb.nodeId?.let { nodeId ->
                        betaNodes[nodeId]?.userFeedback?.add(fb)
                    }
                }
                
                // Compare performance vs baseline
                val performanceComparison = performanceComparator.compare(
                    betaNodes = nodes,
                    baselineNodes = canaryNodes
                )
                metricsCollector.recordPerformanceComparison(performanceComparison)
                
                // Check rollback triggers
                for (trigger in rollbackTriggers) {
                    if (trigger.shouldRollback(metricsCollector, feedbackCollector)) {
                        rollback("Rollback trigger activated: ${trigger.reason()}")
                        return@launch
                    }
                }
                
                delay(1.minutes.inWholeMilliseconds)
            }
        }
        
        monitoringJob?.join()
        isMonitoring.set(false)
        
        // Check final state
        val currentState = _state.value
        return when (currentState) {
            is BetaState.Failed, is BetaState.RolledBack -> {
                HealthResult(false, currentState.toString())
            }
            else -> {
                // Verify success criteria
                val metrics = metricsCollector.getBetaMetrics()
                val successCriteria = BetaSuccessCriteria(
                    maxUserIssues = 5,
                    maxPerformanceOverhead = 3.0,
                    minTaskSuccessRate = 98.0,
                    minPositiveFeedbackRate = 70.0
                )
                
                if (metrics.userIssuesReported > successCriteria.maxUserIssues) {
                    HealthResult(false, "User issues: ${metrics.userIssuesReported}")
                } else if (metrics.performanceOverhead > successCriteria.maxPerformanceOverhead) {
                    HealthResult(false, "Performance overhead: ${metrics.performanceOverhead}%")
                } else if (metrics.taskSuccessRate < successCriteria.minTaskSuccessRate) {
                    HealthResult(false, "Task success rate: ${metrics.taskSuccessRate}%")
                } else if (metrics.positiveFeedbackRate < successCriteria.minPositiveFeedbackRate) {
                    HealthResult(false, "Positive feedback: ${metrics.positiveFeedbackRate}%")
                } else {
                    HealthResult(true)
                }
            }
        }
    }
    
    /**
     * Rollback beta deployment
     */
    private suspend fun rollback(reason: String) {
        _state.value = BetaState.RollingBack(reason)
        
        // Disable feature flag on all beta nodes (excluding canary)
        for ((nodeId, info) in betaNodes) {
            if (nodeId !in canaryNodes) {
                featureFlagManager.disableForNode(nodeId, "TASK_KEYPAIR_ENABLED")
            }
        }
        
        // Wait for in-flight tasks
        delay(10.minutes.inWholeMilliseconds)
        
        _state.value = BetaState.RolledBack(reason)
        metricsCollector.recordRollback(reason)
    }
    
    /**
     * Get current beta status
     */
    fun getStatus(): BetaStatus {
        val currentState = _state.value
        val metrics = metricsCollector.getBetaMetrics()
        val feedbackSummary = feedbackCollector.getSummary()
        
        return BetaStatus(
            state = currentState,
            nodes = betaNodes.values.toList(),
            metrics = metrics,
            userFeedback = feedbackSummary,
            isMonitoring = isMonitoring.get()
        )
    }
    
    /**
     * Shutdown monitoring
     */
    fun shutdown() {
        monitoringJob?.cancel()
        isMonitoring.set(false)
        scope.cancel()
    }
    
    // ========== Helper Methods ==========
    
    private suspend fun announceBetaProgram() {
        // Announce beta program to users
        // In real implementation, send emails, in-app notifications, etc.
        delay(1000)
    }
    
    private suspend fun deployCode(nodeId: String): Boolean {
        // Deploy code to node
        delay(5000)
        return true
    }
}

// ========== Data Classes ==========

/**
 * Beta deployment state
 */
sealed class BetaState {
    object NotStarted : BetaState()
    object Announcing : BetaState()
    object SelectingWeek1 : BetaState()
    data class DeployingWeek1(val nodes: List<String>) : BetaState()
    data class MonitoringWeek1(val nodes: List<String>) : BetaState()
    object SelectingWeek2 : BetaState()
    data class DeployingWeek2(val nodes: List<String>) : BetaState()
    data class MonitoringWeek2(val nodes: List<String>) : BetaState()
    data class RollingBack(val reason: String) : BetaState()
    data class RolledBack(val reason: String) : BetaState()
    data class Failed(val reason: String) : BetaState()
    data class Success(val nodes: List<String>) : BetaState()
}

/**
 * Beta node information
 */
data class BetaNodeInfo(
    val nodeId: String,
    val deployedAt: Instant,
    val week: Int,
    val healthy: Boolean,
    val userFeedback: MutableList<UserFeedback>
)

/**
 * Beta deployment result
 */
sealed class BetaResult {
    data class Success(
        val nodes: List<String>,
        val metrics: BetaMetrics,
        val userFeedback: FeedbackSummary
    ) : BetaResult()
    data class Failure(val reason: String) : BetaResult()
}

/**
 * Beta success criteria
 */
data class BetaSuccessCriteria(
    val maxUserIssues: Int,
    val maxPerformanceOverhead: Double,
    val minTaskSuccessRate: Double,
    val minPositiveFeedbackRate: Double
)

/**
 * Beta status snapshot
 */
data class BetaStatus(
    val state: BetaState,
    val nodes: List<BetaNodeInfo>,
    val metrics: BetaMetrics,
    val userFeedback: FeedbackSummary,
    val isMonitoring: Boolean
)

/**
 * Beta metrics
 */
data class BetaMetrics(
    val deploymentsTotal: Int,
    val deploymentsSuccessful: Int,
    val performanceOverhead: Double,
    val taskSuccessRate: Double,
    val userIssuesReported: Int,
    val positiveFeedbackRate: Double,
    val negativeFeedbackRate: Double,
    val rolledBack: Boolean,
    val rollbackReason: String? = null
)

// ========== Beta Rollback Triggers ==========

sealed class BetaRollbackTrigger {
    abstract fun shouldRollback(
        metrics: BetaMetricsCollector,
        feedback: UserFeedbackCollector
    ): Boolean
    abstract fun reason(): String
    
    /**
     * Rollback on too many user issues
     */
    data class UserIssues(val threshold: Int) : BetaRollbackTrigger() {
        override fun shouldRollback(
            metrics: BetaMetricsCollector,
            feedback: UserFeedbackCollector
        ): Boolean {
            val betaMetrics = metrics.getBetaMetrics()
            return betaMetrics.userIssuesReported > threshold
        }
        
        override fun reason(): String = "User issues >$threshold"
    }
    
    /**
     * Rollback on performance degradation
     */
    data class PerformanceDegradation(val threshold: Double) : BetaRollbackTrigger() {
        override fun shouldRollback(
            metrics: BetaMetricsCollector,
            feedback: UserFeedbackCollector
        ): Boolean {
            val betaMetrics = metrics.getBetaMetrics()
            return betaMetrics.performanceOverhead > threshold
        }
        
        override fun reason(): String = "Performance degradation >$threshold%"
    }
    
    /**
     * Rollback on high task failure rate
     */
    data class TaskFailureRate(val threshold: Double) : BetaRollbackTrigger() {
        override fun shouldRollback(
            metrics: BetaMetricsCollector,
            feedback: UserFeedbackCollector
        ): Boolean {
            val betaMetrics = metrics.getBetaMetrics()
            val failureRate = 100.0 - betaMetrics.taskSuccessRate
            return failureRate > threshold
        }
        
        override fun reason(): String = "Task failure rate >$threshold%"
    }
}

// ========== Node Selector Extensions ==========

/**
 * Select beta nodes (early adopters, power users)
 */
fun NodeSelector.selectBetaNodes(count: Int, excludeNodes: List<String>): List<String> {
    // In real implementation, filter for early adopters and power users
    // For now, delegate to canary selector
    return selectCanaryNodes(count).filter { it !in excludeNodes }
}

// ========== User Feedback Collector ==========

/**
 * UserFeedbackCollector
 * 
 * Collects and analyzes user feedback during beta
 */
interface UserFeedbackCollector {
    /**
     * Collect recent feedback
     */
    suspend fun collectRecent(since: Duration): List<UserFeedback>
    
    /**
     * Get feedback summary
     */
    fun getSummary(): FeedbackSummary
    
    /**
     * Submit feedback
     */
    suspend fun submitFeedback(feedback: UserFeedback)
}

/**
 * User feedback
 */
data class UserFeedback(
    val userId: String,
    val nodeId: String?,
    val timestamp: Instant,
    val sentiment: FeedbackSentiment,
    val category: FeedbackCategory,
    val message: String,
    val isIssue: Boolean
)

/**
 * Feedback sentiment
 */
enum class FeedbackSentiment {
    POSITIVE,
    NEUTRAL,
    NEGATIVE
}

/**
 * Feedback category
 */
enum class FeedbackCategory {
    PERFORMANCE,
    SECURITY,
    USABILITY,
    RELIABILITY,
    OTHER
}

/**
 * Feedback summary
 */
data class FeedbackSummary(
    val totalFeedback: Int,
    val positiveCount: Int,
    val neutralCount: Int,
    val negativeCount: Int,
    val issuesReported: Int,
    val positiveFeedbackRate: Double,
    val negativeFeedbackRate: Double,
    val topIssues: List<String>
)

/**
 * Default feedback collector implementation
 */
class DefaultUserFeedbackCollector : UserFeedbackCollector {
    private val feedback = ConcurrentHashMap<String, MutableList<UserFeedback>>()
    
    override suspend fun collectRecent(since: Duration): List<UserFeedback> {
        val cutoff = Instant.now().minusMillis(since.inWholeMilliseconds)
        return feedback.values.flatten()
            .filter { it.timestamp.isAfter(cutoff) }
    }
    
    override fun getSummary(): FeedbackSummary {
        val allFeedback = feedback.values.flatten()
        val positiveCount = allFeedback.count { it.sentiment == FeedbackSentiment.POSITIVE }
        val neutralCount = allFeedback.count { it.sentiment == FeedbackSentiment.NEUTRAL }
        val negativeCount = allFeedback.count { it.sentiment == FeedbackSentiment.NEGATIVE }
        val issuesReported = allFeedback.count { it.isIssue }
        
        val positiveFeedbackRate = if (allFeedback.isNotEmpty()) {
            (positiveCount.toDouble() / allFeedback.size) * 100.0
        } else {
            0.0
        }
        
        val negativeFeedbackRate = if (allFeedback.isNotEmpty()) {
            (negativeCount.toDouble() / allFeedback.size) * 100.0
        } else {
            0.0
        }
        
        val topIssues = allFeedback
            .filter { it.isIssue }
            .groupBy { it.category }
            .mapValues { it.value.size }
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { "${it.key}: ${it.value}" }
        
        return FeedbackSummary(
            totalFeedback = allFeedback.size,
            positiveCount = positiveCount,
            neutralCount = neutralCount,
            negativeCount = negativeCount,
            issuesReported = issuesReported,
            positiveFeedbackRate = positiveFeedbackRate,
            negativeFeedbackRate = negativeFeedbackRate,
            topIssues = topIssues
        )
    }
    
    override suspend fun submitFeedback(feedback: UserFeedback) {
        this.feedback.getOrPut(feedback.userId) { mutableListOf() }.add(feedback)
    }
}

// ========== Performance Comparator ==========

/**
 * PerformanceComparator
 * 
 * Compares beta node performance vs baseline (canary nodes)
 */
interface PerformanceComparator {
    /**
     * Compare beta nodes vs baseline
     * 
     * Returns overhead percentage (positive = slower, negative = faster)
     */
    suspend fun compare(betaNodes: List<String>, baselineNodes: List<String>): PerformanceComparison
}

/**
 * Performance comparison result
 */
data class PerformanceComparison(
    val overheadPercentage: Double,
    val betaAvgLatency: Long,
    val baselineAvgLatency: Long,
    val betaP95Latency: Long,
    val baselineP95Latency: Long
)

/**
 * Default performance comparator implementation
 */
class DefaultPerformanceComparator : PerformanceComparator {
    override suspend fun compare(
        betaNodes: List<String>,
        baselineNodes: List<String>
    ): PerformanceComparison {
        // In real implementation, query metrics from monitoring system
        // For now, simulate performance comparison
        delay(1000)
        
        val baselineAvg = 500L // ms
        val betaAvg = 510L // ms (2% overhead)
        val overhead = ((betaAvg - baselineAvg).toDouble() / baselineAvg) * 100.0
        
        return PerformanceComparison(
            overheadPercentage = overhead,
            betaAvgLatency = betaAvg,
            baselineAvgLatency = baselineAvg,
            betaP95Latency = betaAvg * 2,
            baselineP95Latency = baselineAvg * 2
        )
    }
}

// ========== Beta Metrics Collector ==========

/**
 * BetaMetricsCollector
 * 
 * Collects metrics for beta deployment
 */
class BetaMetricsCollector {
    private val deploymentsTotal = AtomicInteger(0)
    private val deploymentsSuccessful = AtomicInteger(0)
    private val userIssuesReported = AtomicInteger(0)
    
    private val performanceComparisons = mutableListOf<PerformanceComparison>()
    private val userFeedbackList = mutableListOf<UserFeedback>()
    
    private var rolledBack = false
    private var rollbackReason: String? = null
    
    fun recordDeployment(nodeId: String, week: Int, success: Boolean) {
        deploymentsTotal.incrementAndGet()
        if (success) {
            deploymentsSuccessful.incrementAndGet()
        }
    }
    
    fun recordPerformanceComparison(comparison: PerformanceComparison) {
        synchronized(performanceComparisons) {
            performanceComparisons.add(comparison)
        }
    }
    
    fun recordUserFeedback(feedback: UserFeedback) {
        synchronized(userFeedbackList) {
            userFeedbackList.add(feedback)
        }
        if (feedback.isIssue) {
            userIssuesReported.incrementAndGet()
        }
    }
    
    fun recordRollback(reason: String) {
        rolledBack = true
        rollbackReason = reason
    }
    
    fun getBetaMetrics(): BetaMetrics {
        val avgOverhead = synchronized(performanceComparisons) {
            if (performanceComparisons.isNotEmpty()) {
                performanceComparisons.map { it.overheadPercentage }.average()
            } else {
                0.0
            }
        }
        
        val (positiveFeedback, negativeFeedback) = synchronized(userFeedbackList) {
            val positive = userFeedbackList.count { it.sentiment == FeedbackSentiment.POSITIVE }
            val negative = userFeedbackList.count { it.sentiment == FeedbackSentiment.NEGATIVE }
            positive to negative
        }
        
        val totalFeedback = userFeedbackList.size
        val positiveFeedbackRate = if (totalFeedback > 0) {
            (positiveFeedback.toDouble() / totalFeedback) * 100.0
        } else {
            0.0
        }
        
        val negativeFeedbackRate = if (totalFeedback > 0) {
            (negativeFeedback.toDouble() / totalFeedback) * 100.0
        } else {
            0.0
        }
        
        return BetaMetrics(
            deploymentsTotal = deploymentsTotal.get(),
            deploymentsSuccessful = deploymentsSuccessful.get(),
            performanceOverhead = avgOverhead,
            taskSuccessRate = 98.5, // Simulated
            userIssuesReported = userIssuesReported.get(),
            positiveFeedbackRate = positiveFeedbackRate,
            negativeFeedbackRate = negativeFeedbackRate,
            rolledBack = rolledBack,
            rollbackReason = rollbackReason
        )
    }
    
    fun reset() {
        deploymentsTotal.set(0)
        deploymentsSuccessful.set(0)
        userIssuesReported.set(0)
        synchronized(performanceComparisons) {
            performanceComparisons.clear()
        }
        synchronized(userFeedbackList) {
            userFeedbackList.clear()
        }
        rolledBack = false
        rollbackReason = null
    }
}
