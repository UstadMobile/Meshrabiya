package org.torproject.meshrabiya.compute.monitoring

// ...existing code...

/**
 * Metrics collector for distributed compute layer
 * 
 * Collects and tracks:
 * - Performance metrics (latency, throughput)
 * - Reliability metrics (success rate, retry rate)
 * - Security metrics (encryption rate, keypair lifecycle)
 * - UX metrics (user satisfaction, error rate)
 * 
 * Thread Safety: All methods are thread-safe
 */
class MetricsCollector {
    
    private val logger = Logger.get(this::class)
    
    // Performance metrics
    private val taskSubmissionLatency = MetricHistogram()
    private val taskExecutionLatency = MetricHistogram()
    private val keypairGenerationLatency = MetricHistogram()
    private val fileReEncryptionLatency = MetricHistogram()
    
    // Reliability metrics
    private val taskSubmissionsTotal = AtomicLong(0)
    private val taskSuccessTotal = AtomicLong(0)
    private val taskFailureTotal = AtomicLong(0)
    private val taskRetryTotal = AtomicLong(0)
    
    // Security metrics
    private val keypairsGeneratedTotal = AtomicLong(0)
    private val keypairsExpiredTotal = AtomicLong(0)
    private val filesEncryptedTotal = AtomicLong(0)
    private val unauthorizedAccessAttempts = AtomicLong(0)
    
    // UX metrics
    private val errorRate = MutableStateFlow(0.0)
    private val averageExecutionTime = MutableStateFlow(0L)
    
    // Active keypairs registry
    private val activeKeypairs = ConcurrentHashMap<String, Long>()
    
    /**
     * Record task submission
     */
    fun recordTaskSubmission(latencyMs: Long) {
        taskSubmissionsTotal.incrementAndGet()
        taskSubmissionLatency.record(latencyMs)
    }
    
    /**
     * Record task completion
     */
    fun recordTaskCompletion(taskId: String, executionTimeMs: Long, success: Boolean) {
        if (success) {
            taskSuccessTotal.incrementAndGet()
            taskExecutionLatency.record(executionTimeMs)
        } else {
            taskFailureTotal.incrementAndGet()
        }
        
        updateDerivedMetrics()
    }
    
    /**
     * Record task retry
     */
    fun recordTaskRetry(taskId: String) {
        taskRetryTotal.incrementAndGet()
    }
    
    /**
     * Record keypair generation
     */
    fun recordKeypairGeneration(taskId: String, latencyMs: Long) {
        keypairsGeneratedTotal.incrementAndGet()
        keypairGenerationLatency.record(latencyMs)
        
        // Track active keypair
        activeKeypairs[taskId] = System.currentTimeMillis()
    }
    
    /**
     * Record keypair expiration
     */
    fun recordKeypairExpiration(taskId: String) {
        keypairsExpiredTotal.incrementAndGet()
        activeKeypairs.remove(taskId)
    }
    
    /**
     * Record file re-encryption
     */
    fun recordFileReEncryption(fileId: String, latencyMs: Long) {
        filesEncryptedTotal.incrementAndGet()
        fileReEncryptionLatency.record(latencyMs)
    }
    
    /**
     * Record unauthorized access attempt
     */
    fun recordUnauthorizedAccess(nodeId: String, fileId: String) {
        unauthorizedAccessAttempts.incrementAndGet()
        logger.warn("Unauthorized access attempt: node=$nodeId, file=$fileId")
    }
    
    /**
     * Get current metrics snapshot
     */
    fun getMetrics(): MetricsSnapshot {
        return MetricsSnapshot(
            // Performance
            taskSubmissionLatencyP50 = taskSubmissionLatency.percentile(0.5),
            taskSubmissionLatencyP95 = taskSubmissionLatency.percentile(0.95),
            taskExecutionLatencyP50 = taskExecutionLatency.percentile(0.5),
            taskExecutionLatencyP95 = taskExecutionLatency.percentile(0.95),
            keypairGenerationLatencyP50 = keypairGenerationLatency.percentile(0.5),
            keypairGenerationLatencyP95 = keypairGenerationLatency.percentile(0.95),
            fileReEncryptionLatencyP50 = fileReEncryptionLatency.percentile(0.5),
            fileReEncryptionLatencyP95 = fileReEncryptionLatency.percentile(0.95),
            
            // Reliability
            taskSubmissionsTotal = taskSubmissionsTotal.get(),
            taskSuccessTotal = taskSuccessTotal.get(),
            taskFailureTotal = taskFailureTotal.get(),
            taskRetryTotal = taskRetryTotal.get(),
            taskSuccessRate = calculateSuccessRate(),
            
            // Security
            keypairsGeneratedTotal = keypairsGeneratedTotal.get(),
            keypairsExpiredTotal = keypairsExpiredTotal.get(),
            activeKeypairsCount = activeKeypairs.size,
            filesEncryptedTotal = filesEncryptedTotal.get(),
            unauthorizedAccessAttempts = unauthorizedAccessAttempts.get(),
            
            // UX
            errorRate = errorRate.value,
            averageExecutionTime = averageExecutionTime.value
        )
    }
    
    /**
     * Observe error rate
     */
    fun observeErrorRate(): StateFlow<Double> = errorRate
    
    /**
     * Observe average execution time
     */
    fun observeAverageExecutionTime(): StateFlow<Long> = averageExecutionTime
    
    /**
     * Update derived metrics
     */
    private fun updateDerivedMetrics() {
        // Update error rate
        val total = taskSubmissionsTotal.get()
        val failures = taskFailureTotal.get()
        errorRate.value = if (total > 0) failures.toDouble() / total else 0.0
        
        // Update average execution time
        averageExecutionTime.value = taskExecutionLatency.average()
    }
    
    /**
     * Calculate task success rate
     */
    private fun calculateSuccessRate(): Double {
        val total = taskSuccessTotal.get() + taskFailureTotal.get()
        return if (total > 0) {
            taskSuccessTotal.get().toDouble() / total
        } else {
            0.0
        }
    }
    
    /**
     * Reset all metrics
     */
    fun reset() {
        taskSubmissionLatency.reset()
        taskExecutionLatency.reset()
        keypairGenerationLatency.reset()
        fileReEncryptionLatency.reset()
        
        taskSubmissionsTotal.set(0)
        taskSuccessTotal.set(0)
        taskFailureTotal.set(0)
        taskRetryTotal.set(0)
        
        keypairsGeneratedTotal.set(0)
        keypairsExpiredTotal.set(0)
        filesEncryptedTotal.set(0)
        unauthorizedAccessAttempts.set(0)
        
        activeKeypairs.clear()
        
        errorRate.value = 0.0
        averageExecutionTime.value = 0
        
        logger.info("Metrics reset")
    }
}

/**
 * Metrics snapshot
 */
data class MetricsSnapshot(
    // Performance metrics
    val taskSubmissionLatencyP50: Long,
    val taskSubmissionLatencyP95: Long,
    val taskExecutionLatencyP50: Long,
    val taskExecutionLatencyP95: Long,
    val keypairGenerationLatencyP50: Long,
    val keypairGenerationLatencyP95: Long,
    val fileReEncryptionLatencyP50: Long,
    val fileReEncryptionLatencyP95: Long,
    
    // Reliability metrics
    val taskSubmissionsTotal: Long,
    val taskSuccessTotal: Long,
    val taskFailureTotal: Long,
    val taskRetryTotal: Long,
    val taskSuccessRate: Double,
    
    // Security metrics
    val keypairsGeneratedTotal: Long,
    val keypairsExpiredTotal: Long,
    val activeKeypairsCount: Int,
    val filesEncryptedTotal: Long,
    val unauthorizedAccessAttempts: Long,
    
    // UX metrics
    val errorRate: Double,
    val averageExecutionTime: Long
)

/**
 * Metric histogram for latency tracking
 */
private class MetricHistogram {
    private val values = mutableListOf<Long>()
    private val lock = Any()
    
    fun record(value: Long) {
        synchronized(lock) {
            values.add(value)
            
            // Keep only last 1000 values to prevent memory growth
            if (values.size > 1000) {
                values.removeAt(0)
            }
        }
    }
    
    fun percentile(p: Double): Long {
        synchronized(lock) {
            if (values.isEmpty()) return 0
            
            val sorted = values.sorted()
            val index = (sorted.size * p).toInt().coerceIn(0, sorted.size - 1)
            return sorted[index]
        }
    }
    
    fun average(): Long {
        synchronized(lock) {
            if (values.isEmpty()) return 0
            return values.average().toLong()
        }
    }
    
    fun reset() {
        synchronized(lock) {
            values.clear()
        }
    }
}

/**
 * Alerting manager for compute layer
 * 
 * Monitors metrics and sends alerts when thresholds are exceeded.
 */
class AlertingManager(
    private val metricsCollector: MetricsCollector,
    private val alertChannels: List<AlertChannel>
) {
    
    private val logger = Logger.get(this::class)
    private var monitoringJob: Job? = null
    
    /**
     * Start monitoring and alerting
     */
    fun start() {
        logger.info("Starting alerting manager")
        
        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                checkAlerts()
                delay(60_000)  // Check every minute
            }
        }
    }
    
    /**
     * Stop monitoring
     */
    fun stop() {
        monitoringJob?.cancel()
        logger.info("Alerting manager stopped")
    }
    
    /**
     * Check all alert conditions
     */
    private suspend fun checkAlerts() {
        val metrics = metricsCollector.getMetrics()
        
        // Check error rate (threshold: >5%)
        if (metrics.errorRate > 0.05) {
            sendAlert(
                severity = AlertSeverity.CRITICAL,
                title = "High Error Rate",
                message = "Task error rate is ${(metrics.errorRate * 100).toInt()}% (threshold: 5%)",
                metrics = metrics
            )
        }
        
        // Check performance degradation (threshold: >20% increase)
        val baselineLatency = 500L  // 500ms baseline
        if (metrics.taskExecutionLatencyP95 > baselineLatency * 1.2) {
            sendAlert(
                severity = AlertSeverity.WARNING,
                title = "Performance Degradation",
                message = "Task execution latency P95 is ${metrics.taskExecutionLatencyP95}ms (baseline: ${baselineLatency}ms)",
                metrics = metrics
            )
        }
        
        // Check security violations
        if (metrics.unauthorizedAccessAttempts > 0) {
            sendAlert(
                severity = AlertSeverity.CRITICAL,
                title = "Security Violation",
                message = "${metrics.unauthorizedAccessAttempts} unauthorized access attempts detected",
                metrics = metrics
            )
        }
        
        // Check task success rate (threshold: <99%)
        if (metrics.taskSuccessRate < 0.99 && metrics.taskSubmissionsTotal > 10) {
            sendAlert(
                severity = AlertSeverity.WARNING,
                title = "Low Success Rate",
                message = "Task success rate is ${(metrics.taskSuccessRate * 100).toInt()}% (threshold: 99%)",
                metrics = metrics
            )
        }
    }
    
    /**
     * Send alert to all configured channels
     */
    private suspend fun sendAlert(
        severity: AlertSeverity,
        title: String,
        message: String,
        metrics: MetricsSnapshot
    ) {
        logger.warn("Alert: [$severity] $title - $message")
        
        val alert = Alert(
            severity = severity,
            title = title,
            message = message,
            timestamp = System.currentTimeMillis(),
            metrics = metrics
        )
        
        for (channel in alertChannels) {
            try {
                channel.send(alert)
            } catch (e: Exception) {
                logger.error("Failed to send alert via ${channel.name}", e)
            }
        }
    }
}

/**
 * Alert severity levels
 */
enum class AlertSeverity {
    INFO,
    WARNING,
    CRITICAL
}

/**
 * Alert data class
 */
data class Alert(
    val severity: AlertSeverity,
    val title: String,
    val message: String,
    val timestamp: Long,
    val metrics: MetricsSnapshot
)

/**
 * Alert channel interface
 * 
 * Implementations can send alerts via email, Slack, PagerDuty, etc.
 */
interface AlertChannel {
    val name: String
    
    /**
     * Send alert via this channel
     * 
     * @param alert Alert to send
     * @throws AlertException if send fails
     */
    suspend fun send(alert: Alert)
}

/**
 * Console alert channel (for development/testing)
 */
class ConsoleAlertChannel : AlertChannel {
    override val name = "Console"
    
    override suspend fun send(alert: Alert) {
        println("""
            ╔═══════════════════════════════════════════════════════════════╗
            ║ ALERT: ${alert.severity}
            ║ ${alert.title}
            ║ ${alert.message}
            ║ Time: ${alert.timestamp}
            ╚═══════════════════════════════════════════════════════════════╝
        """.trimIndent())
    }
}

/**
 * Alert exception
 */
class AlertException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
