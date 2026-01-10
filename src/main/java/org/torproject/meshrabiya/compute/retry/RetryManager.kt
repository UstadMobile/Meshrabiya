package org.torproject.meshrabiya.compute.retry

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow

/**
 * RetryManager implements exponential backoff retry logic for task operations.
 * 
 * Features:
 * - Configurable max retries per operation type
 * - Exponential backoff with jitter
 * - Per-task retry tracking
 * - Circuit breaker pattern for persistent failures
 * - Retry budget management
 * 
 * Architecture:
 * - Tracks retry attempts per task
 * - Calculates backoff delays using exponential formula
 * - Implements circuit breaker to prevent retry storms
 * - Thread-safe concurrent access
 * 
 * Retry Formula:
 * - Base delay: initialDelayMs * (2 ^ attemptNumber)
 * - With jitter: baseDelay * (0.8 + random(0.4))
 * - Max delay: min(calculated, maxDelayMs)
 * 
 * Circuit Breaker:
 * - Opens after consecutiveFailureThreshold failures
 * - Stays open for circuitBreakerResetMs
 * - Automatically closes after timeout
 * 
 * @property initialDelayMs Initial retry delay (default 1000ms)
 * @property maxDelayMs Maximum retry delay (default 60000ms)
 * @property maxRetries Maximum retry attempts per task (default 5)
 * @property jitterEnabled Whether to add randomness to delays (default true)
 */
class RetryManager(
    private val initialDelayMs: Long = 1000,
    private val maxDelayMs: Long = 60000,
    private val maxRetries: Int = 5,
    private val jitterEnabled: Boolean = true,
    private val consecutiveFailureThreshold: Int = 10,
    private val circuitBreakerResetMs: Long = 300000 // 5 minutes
) {
    /**
     * Retry configuration for specific operation types.
     */
    data class RetryConfig(
        val maxRetries: Int,
        val initialDelayMs: Long,
        val maxDelayMs: Long,
        val jitterEnabled: Boolean = true,
        val retryableExceptions: Set<Class<out Exception>> = emptySet()
    )
    
    /**
     * Retry state for a task.
     */
    private data class RetryState(
        val taskId: String,
        val operationType: String,
        var attemptNumber: Int = 0,
        var lastAttemptTimeMs: Long = 0,
        var totalRetries: Int = 0,
        var consecutiveFailures: Int = 0,
        val errors: MutableList<RetryError> = mutableListOf()
    )
    
    /**
     * Information about a retry error.
     */
    data class RetryError(
        val attemptNumber: Int,
        val timestampMs: Long,
        val errorMessage: String,
        val errorType: String
    )
    
    /**
     * Circuit breaker state for an operation type.
     */
    private data class CircuitBreakerState(
        var isOpen: Boolean = false,
        var openedAtMs: Long = 0,
        var consecutiveFailures: Int = 0,
        var totalFailures: Long = 0
    )
    
    // Registry of retry states per task
    private val retryStates = ConcurrentHashMap<String, RetryState>()
    
    // Operation type specific retry configurations
    private val operationConfigs = ConcurrentHashMap<String, RetryConfig>()
    
    // Circuit breaker states per operation type
    private val circuitBreakers = ConcurrentHashMap<String, CircuitBreakerState>()
    
    // Statistics
    @Volatile private var totalRetryAttempts = 0L
    @Volatile private var totalSuccessfulRetries = 0L
    @Volatile private var totalFailedRetries = 0L
    @Volatile private var totalCircuitBreakerTrips = 0L
    
    /**
     * Configure retry behavior for a specific operation type.
     * 
     * @param operationType Operation type identifier (e.g., "file_re_encryption", "task_execution")
     * @param config Retry configuration
     */
    fun configureOperationType(operationType: String, config: RetryConfig) {
        operationConfigs[operationType] = config
    }
    
    /**
     * Execute an operation with retry logic.
     * 
     * @param taskId Task identifier
     * @param operationType Operation type
     * @param operation Suspending operation to execute
     * @return Result of operation
     * @throws RetryExhaustedException if max retries exceeded
     * @throws CircuitBreakerOpenException if circuit breaker is open
     */
    suspend fun <T> withRetry(
        taskId: String,
        operationType: String,
        operation: suspend (attemptNumber: Int) -> T
    ): T {
        val config = operationConfigs[operationType] ?: RetryConfig(
            maxRetries = maxRetries,
            initialDelayMs = initialDelayMs,
            maxDelayMs = maxDelayMs,
            jitterEnabled = jitterEnabled
        )
        
        // Check circuit breaker
        val circuitBreaker = circuitBreakers.getOrPut(operationType) { CircuitBreakerState() }
        if (circuitBreaker.isOpen) {
            val elapsedMs = System.currentTimeMillis() - circuitBreaker.openedAtMs
            if (elapsedMs < circuitBreakerResetMs) {
                throw CircuitBreakerOpenException(
                    operationType,
                    circuitBreakerResetMs - elapsedMs
                )
            } else {
                // Reset circuit breaker
                circuitBreaker.isOpen = false
                circuitBreaker.consecutiveFailures = 0
            }
        }
        
        val state = retryStates.getOrPut("${taskId}_${operationType}") {
            RetryState(taskId = taskId, operationType = operationType)
        }
        
        var lastException: Exception? = null
        
        for (attempt in 0..config.maxRetries) {
            state.attemptNumber = attempt
            state.lastAttemptTimeMs = System.currentTimeMillis()
            totalRetryAttempts++
            
            try {
                val result = operation(attempt)
                
                // Success - reset retry state
                if (attempt > 0) {
                    totalSuccessfulRetries++
                    state.totalRetries += attempt
                }
                
                // Reset circuit breaker on success
                circuitBreaker.consecutiveFailures = 0
                
                // Clean up retry state
                retryStates.remove("${taskId}_${operationType}")
                
                return result
                
            } catch (e: Exception) {
                lastException = e
                state.consecutiveFailures++
                
                // Record error
                state.errors.add(RetryError(
                    attemptNumber = attempt,
                    timestampMs = System.currentTimeMillis(),
                    errorMessage = e.message ?: "Unknown error",
                    errorType = e::class.simpleName ?: "Exception"
                ))
                
                // Check if exception is retryable
                if (config.retryableExceptions.isNotEmpty() &&
                    !config.retryableExceptions.any { it.isInstance(e) }) {
                    throw e
                }
                
                // Check if we've exhausted retries
                if (attempt >= config.maxRetries) {
                    totalFailedRetries++
                    
                    // Update circuit breaker
                    circuitBreaker.consecutiveFailures++
                    circuitBreaker.totalFailures++
                    
                    if (circuitBreaker.consecutiveFailures >= consecutiveFailureThreshold) {
                        circuitBreaker.isOpen = true
                        circuitBreaker.openedAtMs = System.currentTimeMillis()
                        totalCircuitBreakerTrips++
                    }
                    
                    throw RetryExhaustedException(
                        taskId = taskId,
                        operationType = operationType,
                        attempts = attempt + 1,
                        errors = state.errors.toList(),
                        cause = e
                    )
                }
                
                // Calculate backoff delay
                val delayMs = calculateBackoffDelay(
                    attempt = attempt,
                    config = config
                )
                
                // Wait before retry
                delay(delayMs)
            }
        }
        
        // Should never reach here, but throw last exception if we do
        throw lastException ?: RetryExhaustedException(
            taskId = taskId,
            operationType = operationType,
            attempts = config.maxRetries + 1,
            errors = state.errors.toList()
        )
    }
    
    /**
     * Calculate exponential backoff delay with optional jitter.
     * 
     * Formula: initialDelay * (2 ^ attempt) * jitterFactor
     * Where jitterFactor is random value between 0.8 and 1.2
     * 
     * @param attempt Current attempt number (0-indexed)
     * @param config Retry configuration
     * @return Delay in milliseconds
     */
    private fun calculateBackoffDelay(attempt: Int, config: RetryConfig): Long {
        // Calculate base delay using exponential backoff
        val exponentialDelay = config.initialDelayMs * (2.0.pow(attempt.toDouble())).toLong()
        
        // Cap at max delay
        val cappedDelay = min(exponentialDelay, config.maxDelayMs)
        
        // Add jitter if enabled
        return if (config.jitterEnabled) {
            val jitterFactor = 0.8 + (Math.random() * 0.4) // Random between 0.8 and 1.2
            (cappedDelay * jitterFactor).toLong()
        } else {
            cappedDelay
        }
    }
    
    /**
     * Get retry state for a task and operation type.
     * 
     * @param taskId Task identifier
     * @param operationType Operation type
     * @return Retry state or null if not found
     */
    fun getRetryState(taskId: String, operationType: String): RetryStateInfo? {
        val state = retryStates["${taskId}_${operationType}"] ?: return null
        return RetryStateInfo(
            taskId = state.taskId,
            operationType = state.operationType,
            attemptNumber = state.attemptNumber,
            lastAttemptTimeMs = state.lastAttemptTimeMs,
            totalRetries = state.totalRetries,
            consecutiveFailures = state.consecutiveFailures,
            errors = state.errors.toList()
        )
    }
    
    /**
     * Get circuit breaker state for an operation type.
     * 
     * @param operationType Operation type
     * @return Circuit breaker info or null if not found
     */
    fun getCircuitBreakerState(operationType: String): CircuitBreakerInfo? {
        val breaker = circuitBreakers[operationType] ?: return null
        return CircuitBreakerInfo(
            operationType = operationType,
            isOpen = breaker.isOpen,
            openedAtMs = breaker.openedAtMs,
            consecutiveFailures = breaker.consecutiveFailures,
            totalFailures = breaker.totalFailures,
            resetInMs = if (breaker.isOpen) {
                val elapsed = System.currentTimeMillis() - breaker.openedAtMs
                (circuitBreakerResetMs - elapsed).coerceAtLeast(0)
            } else 0
        )
    }
    
    /**
     * Manually reset circuit breaker for an operation type.
     * 
     * @param operationType Operation type
     * @return True if breaker was reset, false if not found
     */
    fun resetCircuitBreaker(operationType: String): Boolean {
        val breaker = circuitBreakers[operationType] ?: return false
        breaker.isOpen = false
        breaker.consecutiveFailures = 0
        return true
    }
    
    /**
     * Clear retry state for a task.
     * 
     * @param taskId Task identifier
     * @param operationType Optional operation type (clears all if not specified)
     */
    fun clearRetryState(taskId: String, operationType: String? = null) {
        if (operationType != null) {
            retryStates.remove("${taskId}_${operationType}")
        } else {
            retryStates.keys.removeIf { it.startsWith("${taskId}_") }
        }
    }
    
    /**
     * Get retry statistics.
     */
    fun getStatistics(): RetryStatistics {
        return RetryStatistics(
            activeRetryCount = retryStates.size,
            totalRetryAttempts = totalRetryAttempts,
            totalSuccessfulRetries = totalSuccessfulRetries,
            totalFailedRetries = totalFailedRetries,
            totalCircuitBreakerTrips = totalCircuitBreakerTrips,
            openCircuitBreakers = circuitBreakers.count { it.value.isOpen }
        )
    }
    
    /**
     * Public retry state information.
     */
    data class RetryStateInfo(
        val taskId: String,
        val operationType: String,
        val attemptNumber: Int,
        val lastAttemptTimeMs: Long,
        val totalRetries: Int,
        val consecutiveFailures: Int,
        val errors: List<RetryError>
    )
    
    /**
     * Public circuit breaker information.
     */
    data class CircuitBreakerInfo(
        val operationType: String,
        val isOpen: Boolean,
        val openedAtMs: Long,
        val consecutiveFailures: Int,
        val totalFailures: Long,
        val resetInMs: Long
    )
    
    /**
     * Retry statistics.
     */
    data class RetryStatistics(
        val activeRetryCount: Int,
        val totalRetryAttempts: Long,
        val totalSuccessfulRetries: Long,
        val totalFailedRetries: Long,
        val totalCircuitBreakerTrips: Long,
        val openCircuitBreakers: Int
    ) {
        val successRate: Double
            get() = if (totalRetryAttempts > 0) {
                totalSuccessfulRetries.toDouble() / totalRetryAttempts
            } else 0.0
    }
}

/**
 * Exception thrown when retry attempts are exhausted.
 */
class RetryExhaustedException(
    val taskId: String,
    val operationType: String,
    val attempts: Int,
    val errors: List<RetryManager.RetryError>,
    cause: Throwable? = null
) : Exception(
    "Retry exhausted for task $taskId operation $operationType after $attempts attempts",
    cause
)

/**
 * Exception thrown when circuit breaker is open.
 */
class CircuitBreakerOpenException(
    val operationType: String,
    val resetInMs: Long
) : Exception(
    "Circuit breaker open for operation $operationType, resets in ${resetInMs}ms"
)
