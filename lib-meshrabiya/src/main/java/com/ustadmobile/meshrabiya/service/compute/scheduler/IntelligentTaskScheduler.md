package com.ustadmobile.meshrabiya.service.compute

import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import kotlin.math.*
import java.util.concurrent.ConcurrentHashMap

// Exact imports for all referenced types
import com.ustadmobile.meshrabiya.service.compute.mesh.ClusterState.NodeCapabilitySnapshot
import com.ustadmobile.meshrabiya.service.compute.scheduler.ComputeTask
import com.ustadmobile.meshrabiya.service.compute.model.ResourceRequirements
// DistributedJob: Not found in code, only in .md. Should be added to model if missing.
import com.ustadmobile.meshrabiya.service.compute.model.StorageOperation
import com.ustadmobile.meshrabiya.service.compute.mesh.ClusterState.MeshIntelligence
import com.ustadmobile.meshrabiya.service.compute.scheduler.DependencyGraph
import com.ustadmobile.meshrabiya.service.compute.model.LibraryEntry.PythonLibrary
import com.ustadmobile.meshrabiya.service.compute.model.ResourceRequirements.OutputSchema
import com.ustadmobile.meshrabiya.service.compute.model.ResourceRequirements.OutputFormat
// import com.ustadmobile.meshrabiya.service.compute.model.LibraryEntry.LiteRTConfig
import com.ustadmobile.meshrabiya.service.compute.model.LibraryEntry.InferenceConfig
import com.ustadmobile.meshrabiya.service.compute.model.LibraryEntry.Precision
import com.ustadmobile.meshrabiya.service.compute.model.JobTypes.JobType
import com.ustadmobile.meshrabiya.service.compute.model.JobTypes.SpecializedCapability
import com.ustadmobile.meshrabiya.service.compute.scheduler.ExecutionPlan
import com.ustadmobile.meshrabiya.service.compute.mesh.ClusterState.ResourceAllocation
import com.ustadmobile.meshrabiya.service.compute.model.JobTypes.AggregationStrategy

class IntelligentTaskScheduler {
	// Calculates resource score for a node and a task
	fun calculateResourceScore(node: NodeCapabilitySnapshot, task: ComputeTask): Float {
		val ramRatio = node.resourceCapabilities.availableRAMMB.toFloat() / task.resourceRequirements.preferredRAMMB
		val cpuScore = if (node.resourceCapabilities.availableCPU >= getCPURequirement(task.resourceRequirements.cpuIntensity)) 1.0f else 0.5f
		val batteryScore = (node.batteryInfo.level - task.resourceRequirements.minBatteryLevel).toFloat() / 100f
		return (ramRatio.coerceAtMost(2.0f) + cpuScore + batteryScore) / 3f
	}

	// Decomposes a distributed storage job into storage tasks
	fun decomposeDistributedStorage(job: DistributedJob, intelligence: MeshIntelligence): List<ComputeTask> {
		val tasks = mutableListOf<ComputeTask>()
		val fileId = job.inputData["fileId"] as? String ?: return emptyList()
		val operation = job.inputData["operation"] as? StorageOperation ?: StorageOperation.STORE
		val data = job.inputData["data"] as? ByteArray
	val replicationFactor = job.inputData["replicationFactor"] as? Int ?: MeshrabiyaConstants.getReplicaCount()
		val destinationPath = job.destinationPath ?: job.inputData["destinationPath"] as? String

		when (operation) {
			StorageOperation.STORE -> {
				repeat(replicationFactor) { index ->
					tasks.add(ComputeTask.DistributedStorageTask(
						taskId = "${job.jobId}_store_$index",
						operation = StorageOperation.STORE,
						fileId = fileId,
						data = data,
						replicationFactor = 1,
						estimatedExecutionMs = 1000L,
						resourceRequirements = ResourceRequirements(
							minRAMMB = 64,
							preferredRAMMB = 128,
							cpuIntensity = CPUIntensity.LIGHT,
							requiresStorage = true,
							minStorageGB = (data?.size ?: 0) / (1024f * 1024f * 1024f)
						),
						destinationPath = destinationPath
					))
				}
			}
			StorageOperation.RETRIEVE -> {
				tasks.add(ComputeTask.DistributedStorageTask(
					taskId = "${job.jobId}_retrieve",
					operation = StorageOperation.RETRIEVE,
					fileId = fileId,
					data = null,
					replicationFactor = 1,
					estimatedExecutionMs = 500L,
					resourceRequirements = ResourceRequirements(
						minRAMMB = 64,
						preferredRAMMB = 128,
						cpuIntensity = CPUIntensity.LIGHT,
						requiresStorage = true
					),
					destinationPath = destinationPath
				))
			}
			else -> {
				tasks.add(ComputeTask.DistributedStorageTask(
					taskId = "${job.jobId}_${operation.name.lowercase()}",
					operation = operation,
					fileId = fileId,
					data = data,
					replicationFactor = 1,
					estimatedExecutionMs = 300L,
					resourceRequirements = ResourceRequirements(
						minRAMMB = 32,
						preferredRAMMB = 64,
						cpuIntensity = CPUIntensity.LIGHT,
						requiresStorage = true
					),
					destinationPath = destinationPath
				))
			}
		}
		return tasks
	}

	// Helper for CPU requirement
	private fun getCPURequirement(intensity: CPUIntensity): Float {
		return when (intensity) {
			CPUIntensity.LIGHT -> 0.2f
			CPUIntensity.MODERATE -> 0.5f
			CPUIntensity.HEAVY -> 0.8f
			CPUIntensity.BURST -> 1.0f
		}
	}

	// Example usage for chunk size, max retries, no_chunking, connection pool size
	// val chunkSizeKb = MeshrabiyaConstants.getChunkSizeKb()
	// val maxRetries = MeshrabiyaConstants.getMaxRetries()
	// val noChunking = MeshrabiyaConstants.getNoChunking()
	// val connectionPoolSize = MeshrabiyaConstants.getConnectionPoolSize()
	// --- Restoration stubs for missing functions ---
	fun assignTasksIntelligently(
		tasks: List<ComputeTask>,
		intelligence: MeshIntelligence,
		dependencyGraph: DependencyGraph
	): Map<String, String> {
		val assignments = mutableMapOf<String, String>()
		val nodeWorkloads = mutableMapOf<String, Int>()
		val sortedTasks = tasks.sortedWith(compareBy<ComputeTask> {
			dependencyGraph.getDependencyDepth(it.taskId)
		}.thenByDescending {
			it.resourceRequirements.preferredRAMMB
		})
		for (task in sortedTasks) {
			val candidateNodes = findSuitableNodes(task, intelligence, assignments, dependencyGraph)
			if (candidateNodes.isEmpty()) {
				assignments[task.taskId] = "LOCAL"
				continue
			}
			val selectedNode = selectOptimalNode(task, candidateNodes, intelligence, nodeWorkloads)
			assignments[task.taskId] = selectedNode.nodeId
			nodeWorkloads[selectedNode.nodeId] = nodeWorkloads.getOrDefault(selectedNode.nodeId, 0) + 1
		}
		return assignments
	}

	fun buildDependencyGraph(tasks: List<ComputeTask>): DependencyGraph {
		val dependencies = tasks.associate { task ->
			task.taskId to task.dependencies
		}
		return DependencyGraph(dependencies)
	}

	fun calculateNetworkScore(node: NodeCapabilitySnapshot, task: ComputeTask, intelligence: MeshIntelligence): Float {
		val relevantNodes = intelligence.nodeStates.keys.take(5)
		val avgLatency = relevantNodes.map { otherNode ->
			intelligence.proximityMatrix.getLatency(node.nodeId, otherNode)
		}.average()
		return (1000f - avgLatency.toFloat()).coerceAtLeast(0f) / 1000f
	}

	fun calculateSpecializationScore(node: NodeCapabilitySnapshot, task: ComputeTask, intelligence: MeshIntelligence): Float {
		val specialization = intelligence.specializations[node.nodeId] ?: return 0.5f
		return when (task) {
			// is ComputeTask.LiteRTTask -> {
			// 	if (specialization.hasNPUAcceleration) 1.0f
			// 	else if (specialization.hasGPUAcceleration) 0.8f
			// 	else 0.6f
			// }
			is ComputeTask.PythonTask -> {
				if (specialization.hasPythonOptimizations) 0.9f
				else 0.7f
			}
			is ComputeTask.HybridTask -> 0.8f
			// is ComputeTask.DistributedStorageTask -> {
			// 	if (specialization.specializedCapabilities.contains(SpecializedCapability.DISTRIBUTED_STORAGE)) 1.0f
			// 	else 0.5f
			// }
		}
	}

	fun createFallbackTask(job: DistributedJob): ComputeTask {
		return ComputeTask.PythonTask(
			taskId = "${job.jobId}_fallback",
			scriptCode = "print('Fallback task execution')",
			inputData = job.inputData,
			libraries = setOf(PythonLibrary.JSON),
			estimatedExecutionMs = 1000L,
			resourceRequirements = ResourceRequirements(
				minRAMMB = 128,
				preferredRAMMB = 256,
				cpuIntensity = CPUIntensity.LIGHT
			),
			outputSchema = OutputSchema(OutputFormat.JSON, 1024, mapOf("status" to "string"))
		)
	}

	fun decomposeCollaborativeFiltering(job: DistributedJob, intelligence: MeshIntelligence): List<ComputeTask> {
		return listOf(createFallbackTask(job))
	}

	fun decomposeDataAnalysis(job: DistributedJob, intelligence: MeshIntelligence): List<ComputeTask> {
		return listOf(createFallbackTask(job))
	}

	fun decomposeImageProcessing(job: DistributedJob, intelligence: MeshIntelligence): List<ComputeTask> {
		val tasks = mutableListOf<ComputeTask>()
		val availableGPUNodes = intelligence.specializations
			.filter { it.value.hasGPUAcceleration }.keys.size

		if (availableGPUNodes >= 2) {
			tasks.add(ComputeTask.PythonTask(
				taskId = "${job.jobId}_preprocess",
				scriptCode = generateImagePreprocessingScript(),
				inputData = mapOf("images" to job.inputData),
				libraries = setOf(PythonLibrary.OPENCV, PythonLibrary.NUMPY),
				estimatedExecutionMs = 200L,
				resourceRequirements = ResourceRequirements(
					minRAMMB = 512,
					preferredRAMMB = 1024,
					cpuIntensity = CPUIntensity.MODERATE
				),
				outputSchema = OutputSchema(OutputFormat.TENSOR, 1024 * 1024, mapOf())
			))
			// repeat(minOf(4, availableGPUNodes)) { index ->
			// 	tasks.add(ComputeTask.LiteRTTask(
			// 		taskId = "${job.jobId}_inference_$index",
			// 		modelId = "mobilenet_v3_quantized",
			// 		inputTensors = listOf(),
			// 		modelConfig = LiteRTConfig(
			// 			useGPU = true,
			// 			useNNAPI = true,
			// 			numThreads = 2
			// 		),
			// 		estimatedExecutionMs = 150L,
			// 		resourceRequirements = ResourceRequirements(
			// 			minRAMMB = 256,
			// 			preferredRAMMB = 512,
			// 			cpuIntensity = CPUIntensity.LIGHT,
			// 			requiresGPU = true
			// 		),
			// 		dependencies = listOf("${job.jobId}_preprocess"),
			// 		inferenceConfig = InferenceConfig(
			// 			batchSize = 1,
			// 			precision = Precision.QUANTIZED
			// 		)
			// 	))
			// }
		}
		return tasks
	}

	fun decomposeJobIntelligently(job: DistributedJob, intelligence: MeshIntelligence): List<ComputeTask> {
		return when (job.jobType) {
			JobType.IMAGE_PROCESSING -> decomposeImageProcessing(job, intelligence)
			JobType.DATA_ANALYSIS -> decomposeDataAnalysis(job, intelligence)
			JobType.ML_PIPELINE -> decomposeMLPipeline(job, intelligence)
			JobType.SENSOR_FUSION -> decomposeSensorFusion(job, intelligence)
			JobType.COLLABORATIVE_FILTERING -> decomposeCollaborativeFiltering(job, intelligence)
			JobType.DISTRIBUTED_STORAGE -> decomposeDistributedStorage(job, intelligence)
			else -> listOf(createFallbackTask(job))
		}
	}

	fun decomposeMLPipeline(job: DistributedJob, intelligence: MeshIntelligence): List<ComputeTask> {
		return listOf(createFallbackTask(job))
	}

	fun decomposeSensorFusion(job: DistributedJob, intelligence: MeshIntelligence): List<ComputeTask> {
		return listOf(createFallbackTask(job))
	}

	suspend fun distributeJob(job: DistributedJob): ExecutionPlan {
		val meshIntelligence = gatherMeshIntelligence()
		val tasks = decomposeJobIntelligently(job, meshIntelligence)
		val dependencyGraph = buildDependencyGraph(tasks)
		val assignments = assignTasksIntelligently(tasks, meshIntelligence, dependencyGraph)
		val optimizedPlan = optimizeExecutionPlan(assignments, meshIntelligence)
		return optimizedPlan
	}

	fun findSuitableNodes(
		task: ComputeTask,
		intelligence: MeshIntelligence,
		existingAssignments: Map<String, String>,
		dependencyGraph: DependencyGraph
	): List<NodeCapabilitySnapshot> {
		val dependencyNodes = task.dependencies.mapNotNull { depTaskId ->
			existingAssignments[depTaskId]
		}.toSet()
		return intelligence.nodeStates.values.filter { node ->
			node.resourceCapabilities.availableRAMMB >= task.resourceRequirements.minRAMMB &&
			node.batteryInfo.level >= task.resourceRequirements.minBatteryLevel &&
			node.thermalState in task.resourceRequirements.thermalConstraints &&
			(!task.resourceRequirements.requiresGPU || intelligence.specializations[node.nodeId]?.hasGPUAcceleration == true) &&
			(!task.resourceRequirements.requiresNPU || intelligence.specializations[node.nodeId]?.hasNPUAcceleration == true) &&
			(!task.resourceRequirements.requiresStorage || (intelligence.specializations[node.nodeId]?.storageCapabilityGB ?: 0f) >= task.resourceRequirements.minStorageGB) &&
			(dependencyNodes.isEmpty() || dependencyNodes.any { depNode ->
				intelligence.proximityMatrix.getLatency(node.nodeId, depNode) <= task.resourceRequirements.maxNetworkLatencyMs
			}) &&
			isNodeCompatibleWithTask(node, task, intelligence.specializations[node.nodeId])
		}
	}

	suspend fun gatherMeshIntelligence(): MeshIntelligence {
		val nodeStates = gossipProtocol.getCurrentNodeStates()
		val networkMetrics = networkTopology.getCurrentMetrics()
		val resourceAvailability = resourceManager.getClusterResourceState()
		val activeQuorums = quorumManager.getActiveQuorums()
		val proximityMatrix = calculateNetworkProximity(nodeStates)
		val specializations = assessNodeSpecializations(nodeStates)
		return MeshIntelligence(
			nodeStates = nodeStates,
			networkMetrics = networkMetrics,
			resourceAvailability = resourceAvailability,
			proximityMatrix = proximityMatrix,
			specializations = specializations,
			activeQuorums = activeQuorums,
			timestamp = System.currentTimeMillis()
		)
	}

	fun isNodeCompatibleWithTask(node: NodeCapabilitySnapshot, task: ComputeTask, specialization: NodeSpecialization?): Boolean {
		return when (task) {
			is ComputeTask.PythonTask -> {
				task.libraries.all { lib ->
					specialization?.supportedPythonLibraries?.contains(lib) == true
				}
			}
			// is ComputeTask.LiteRTTask -> {
			// 	specialization?.supportedLiteRTModels?.contains(task.modelId) == true
			// }
			is ComputeTask.HybridTask -> {
				// isNodeCompatibleWithTask(node, task.liteRTInference, specialization) &&
				(task.pythonPreprocessing?.let { isNodeCompatibleWithTask(node, it, specialization) } != false) &&
				(task.pythonPostprocessing?.let { isNodeCompatibleWithTask(node, it, specialization) } != false)
			}
			// is ComputeTask.DistributedStorageTask -> {
			// 	specialization?.specializedCapabilities?.contains(SpecializedCapability.DISTRIBUTED_STORAGE) == true &&
			// 	node.resourceCapabilities.storageGB >= task.resourceRequirements.minStorageGB
			// }
		}
	}

	fun optimizeExecutionPlan(assignments: Map<String, String>, intelligence: MeshIntelligence): ExecutionPlan {
		return ExecutionPlan(
			jobId = "optimized_plan",
			tasks = emptyList(),
			assignments = assignments,
			dependencyGraph = DependencyGraph(emptyMap()),
			estimatedExecutionMs = 0L,
			resourceAllocation = ResourceAllocation(0L, 0f, 0L),
			aggregationStrategy = AggregationStrategy.SIMPLE_CONCAT
		)
	}

	fun selectOptimalNode(task: ComputeTask, candidates: List<NodeCapabilitySnapshot>, intelligence: MeshIntelligence, currentWorkloads: Map<String, Int>): NodeCapabilitySnapshot {
		return candidates.maxByOrNull { node ->
			var score = 0f
			score += calculateResourceScore(node, task) * 0.3f
			score += calculateNetworkScore(node, task, intelligence) * 0.25f
			val currentLoad = currentWorkloads.getOrDefault(node.nodeId, 0)
			score += (1.0f / (currentLoad + 1)) * 0.2f
			score += calculateSpecializationScore(node, task, intelligence) * 0.15f
			score += node.reliabilityScore * 0.1f
			score
		} ?: candidates.first()
	}
}
