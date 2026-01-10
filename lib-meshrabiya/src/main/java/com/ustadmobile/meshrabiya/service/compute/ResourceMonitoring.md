package com.ustadmobile.meshrabiya.service.compute

import kotlinx.coroutines.*
import com.ustadmobile.meshrabiya.service.compute.model.ResourceMetrics
import com.ustadmobile.meshrabiya.service.compute.model.ResourceLimits
import com.ustadmobile.meshrabiya.service.compute.model.ExecutionErrorType
import com.ustadmobile.meshrabiya.service.compute.model.ExecutionResult
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
import com.ustadmobile.meshrabiya.service.compute.model.JobType
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.log.betaLogger
import com.ustadmobile.meshrabiya.log.LogLevel
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized resource monitoring for all executing tasks and containers.
 * Tracks total node load, per-task metrics, peak usage, and detects resource limit violations.
 * Provides metrics for node capability calculations, enforcement, billing, and completion notifications.
 */
object ResourceMonitoring {

	private val activeExecutions: ConcurrentHashMap<String, ExecutionState> = ConcurrentHashMap()
	private var resourceMonitoringJob: Job? = null
	private var totalLoad: ResourceMetrics = ResourceMetrics()

	/**
	 * Start the resource monitoring loop.
	 */
	fun startMonitoring(scope: CoroutineScope) {
		if (resourceMonitoringJob?.isActive == true) return
		resourceMonitoringJob = scope.launch(Dispatchers.Default) {
			while (isActive) {
				updateResourceMetrics()
				delay(MeshrabiyaConstants.RESOURCE_MONITORING_INTERVAL_MS)
			}
		}
	}

	/**
	 * Stop the resource monitoring loop.
	 */
	fun stopMonitoring() {
		resourceMonitoringJob?.cancel()
		resourceMonitoringJob = null
	}

	/**
	 * Polls and updates metrics for all active tasks/containers.
	 */
	suspend fun updateResourceMetrics() {
		var totalRamActual = 0L
		var totalCpuActual = 0f
		var totalDiskActual = 0L
		var totalNetworkActual = 0L
		val now = System.currentTimeMillis()

		for ((taskId, execution) in activeExecutions) {
			// Simulate polling metrics from container/task engine
			val metrics = pollMetricsForTask(taskId)
			execution.currentMetrics = metrics.copy(timestamp = now)
			// Update peak metrics
			execution.peakMetrics = updatePeakMetrics(execution.peakMetrics, metrics)
			// Check for violations
			checkResourceLimitViolations(execution)
			totalRamActual += metrics.memoryUsedBytes
			totalCpuActual += metrics.cpuUsagePercent
			totalDiskActual += metrics.diskStorageUsedBytes
			totalNetworkActual += metrics.networkUsedBytes
		}
		totalLoad = ResourceMetrics(
			timestamp = now,
			ramActualBytes = totalRamActual,
			 cpuPercentage = totalCpuActual,
			 diskStorageUsedBytes = totalDiskActual,
			 networkUsedBytes = totalNetworkActual
		)
	}

	/**
	 * Simulate polling metrics for a given task/container.
	 * Replace with actual integration to container/task engine.
	 * Uses canonical ResourceMetrics property names.
	 */
	private fun pollMetricsForTask(taskId: String): ResourceMetrics {
		// TODO: Integrate with StrangersSafeComputeEngine, JVMExecutor, PythonExecutor, etc.
		// For now, return dummy metrics
		return ResourceMetrics(
			 ramActualBytes = (64 * 1024 * 1024), // 64MB
			 cpuPercentage = 10f,
			 diskStorageUsedBytes = (10 * 1024 * 1024), // 10MB
			 networkUsedBytes = (1 * 1024 * 1024) // 1MB
		)
	}

	/**
	 * Update peak metrics for a task using canonical ResourceMetrics property names.
	 */
	private fun updatePeakMetrics(oldPeak: ResourceMetrics?, current: ResourceMetrics): ResourceMetrics {
		if (oldPeak == null) return current
		return ResourceMetrics(
			timestamp = current.timestamp,
			 ramActualBytes = maxOf(oldPeak.ramActualBytes, current.ramActualBytes),
			 cpuPercentage = maxOf(oldPeak.cpuPercentage, current.cpuPercentage),
			 diskStorageUsedBytes = maxOf(oldPeak.diskStorageUsedBytes, current.diskStorageUsedBytes),
			 networkUsedBytes = maxOf(oldPeak.networkUsedBytes, current.networkUsedBytes)
		)
	}

	/**
	 * Checks for violations and triggers enforcement.
	 */
	private suspend fun checkResourceLimitViolations(execution: ExecutionState) {
		val limits = execution.context.resourceLimits
		val metrics = execution.currentMetrics
		if (limits == null || metrics == null) return

		var violation: ExecutionErrorType? = null
		if (metrics.memoryUsedBytes > limits.maxMemoryBytes) {
			violation = ExecutionErrorType.MEMORY_LIMIT_EXCEEDED
		} else if (metrics.cpuUsagePercent > limits.maxCpuPercent) {
			violation = ExecutionErrorType.CPU_LIMIT_EXCEEDED
		} else if (metrics.diskStorageUsedBytes > limits.maxDiskBytes) {
			violation = ExecutionErrorType.DISK_LIMIT_EXCEEDED
		} else if (metrics.networkUsedBytes > limits.maxNetworkBytes) {
			violation = ExecutionErrorType.NETWORK_LIMIT_EXCEEDED
		}
		if (violation != null) {
			terminateTask(execution.context.taskId, violation)
		}
	}

	/**
	 * Terminates a task and cleans up.
	 */
	private suspend fun terminateTask(taskId: String, errorType: ExecutionErrorType) {
		val execution = activeExecutions[taskId] ?: return
		// TODO: Integrate with TaskManager, container engine, etc.
		betaLogger.log(LogLevel.ERROR, "ResourceMonitoring", "Terminating task $taskId due to $errorType")
		execution.terminated = true
		execution.errorType = errorType
		cleanupExecution(taskId)
		// TODO: Send completion notification with errorType
	}

	/**
	 * Cleans up execution state for a task.
	 */
	private fun cleanupExecution(taskId: String) {
		activeExecutions.remove(taskId)
	}

	/**
	 * Returns aggregate node load.
	 */
	fun getTotalLoad(): ResourceMetrics {
		return totalLoad
	}

	/**
	 * Returns current metrics for a task using canonical ResourceMetrics property names.
	 */
	fun getTaskMetrics(taskId: String): ResourceMetrics? {
		return activeExecutions[taskId]?.currentMetrics
	}

	/**
	 * Returns peak metrics for a task using canonical ResourceMetrics property names.
	 */
	fun getPeakMetrics(taskId: String): ResourceMetrics? {
		return activeExecutions[taskId]?.peakMetrics
	}

	/**
	 * Registers a new execution for monitoring.
	 */
	fun registerExecution(context: TaskExecutionContext, limits: ResourceLimits?) {
		activeExecutions[context.taskId] = ExecutionState(context, limits)
	}

	/**
	 * Unregisters an execution (after completion or termination).
	 */
	fun unregisterExecution(taskId: String) {
		activeExecutions.remove(taskId)
	}

	/**
	 * Data class for tracking execution state.
	 */
	// Phase 2: Execution state tracking
    data class ExecutionState(
        val taskId: UUID,
        val containerId: String,
        val executorNodeAddress: String,
        val startTime: Long,
        val taskContext: TaskExecutionContext,
        val requesterNodeId: String,
        val callbackAddress: String,
        // Phase 2.2: Resource monitoring fields
        val resourceMetrics: ResourceMetrics = ResourceMetrics.zero(),
        val lastMetricUpdate: Long = 0L
    )
}

