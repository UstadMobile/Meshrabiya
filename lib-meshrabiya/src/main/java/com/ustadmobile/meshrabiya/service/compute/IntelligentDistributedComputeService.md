package com.ustadmobile.meshrabiya.service.compute

// Intelligent Distributed Compute Service
// Integrates Python execution and LiteRT inference with mesh intelligence

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.ustadmobile.meshrabiya.service.storage.StorageDropFolderManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

data class ServiceSearchResult(
    val serviceId: String,
    val manifest: ServiceManifest,
    val executionProfile: ExecutionProfile,
    val capabilities: Set<ServiceCapability>,
    val nodeId: String
)

fun toSearchResult(entry: LibraryEntry, nodeId: String): ServiceSearchResult {
    return ServiceSearchResult(
        serviceId = entry.serviceId,
        manifest = entry.manifest,
        executionProfile = entry.executionProfile,
        capabilities = entry.capabilities,
        nodeId = nodeId
    )
}

sealed class LibraryEntry {
    abstract val serviceId: String
    abstract val manifest: ServiceManifest
    abstract val executionProfile: ExecutionProfile
    abstract val inputs: List<ServiceInput>
    abstract val outputs: List<ServiceOutput>
    abstract val capabilities: Set<ServiceCapability>

    data class PythonServiceEntry(
        override val serviceId: String,
        val scriptCode: String,
        val libraries: Set<PythonLibrary>,
        override val manifest: ServiceManifest,
        override val executionProfile: ExecutionProfile,
        override val inputs: List<ServiceInput>,
        override val outputs: List<ServiceOutput>,
        override val capabilities: Set<ServiceCapability>
    ) : LibraryEntry()

    data class LiteRTServiceEntry(
        override val serviceId: String,
        val modelId: String,
        val modelConfig: LiteRTConfig,
        override val manifest: ServiceManifest,
        override val executionProfile: ExecutionProfile,
        override val inputs: List<ServiceInput>,
        override val outputs: List<ServiceOutput>,
        override val capabilities: Set<ServiceCapability>
    ) : LibraryEntry()

    data class HybridServiceEntry(
        override val serviceId: String,
        val pythonPreprocessing: PythonServiceEntry?,
        val liteRTInference: LiteRTServiceEntry,
        val pythonPostprocessing: PythonServiceEntry?,
        override val manifest: ServiceManifest,
        override val executionProfile: ExecutionProfile,
        override val inputs: List<ServiceInput>,
        override val outputs: List<ServiceOutput>,
        override val capabilities: Set<ServiceCapability>
    ) : LibraryEntry()

    data class JavaServiceEntry(
        override val serviceId: String,
        val className: String,
        val jarPath: String,
        override val manifest: ServiceManifest,
        override val executionProfile: ExecutionProfile,
        override val inputs: List<ServiceInput>,
        override val outputs: List<ServiceOutput>,
        override val capabilities: Set<ServiceCapability>
    ) : LibraryEntry()

    data class NDKServiceEntry(
        override val serviceId: String,
        val soPath: String,
        val entryFunction: String,
        override val manifest: ServiceManifest,
        override val executionProfile: ExecutionProfile,
        override val inputs: List<ServiceInput>,
        override val outputs: List<ServiceOutput>,
        override val capabilities: Set<ServiceCapability>
    ) : LibraryEntry()

    data class WorkflowServiceEntry(
        override val serviceId: String,
        val steps: List<String>,
        override val manifest: ServiceManifest,
        override val executionProfile: ExecutionProfile,
        override val inputs: List<ServiceInput>,
        override val outputs: List<ServiceOutput>,
        override val capabilities: Set<ServiceCapability>
    ) : LibraryEntry()
}

data class ServiceManifest(
    val serviceType: ServiceType,
    val version: String,
    val author: String,
    val signature: String?,
    val runtimeRequired: List<String> = emptyList(),
    val runtimeOptional: List<String> = emptyList(),
    val deviceProfile: String? = null,
    val resourceRequirements: ResourceRequirements,
    val platformSupport: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    val builtin: Boolean = false
)

enum class ServiceType {
    PYTHON, LITERT, HYBRID, JAVA, NDK, WORKFLOW, STORAGE
}

data class ExecutionProfile(
    val deterministic: Boolean = false,
    val zkpRequired: Boolean = false,
    val accessLevel: String = "user"
)

data class ServiceMeta(
    val serviceId: String,
    val manifest: ServiceManifest,
    val executionProfile: ExecutionProfile,
    val inputs: List<ServiceInput> = emptyList(),
    val outputs: List<ServiceOutput> = emptyList(),
    val capabilities: Set<ServiceCapability> = emptySet()
)

data class ServiceInput(val name: String, val type: String, val required: Boolean = true)
data class ServiceOutput(val name: String, val type: String)
enum class ServiceCapability {
    ML, CV, NLP, STORAGE, AUDIO, SIGNAL, CRYPTO, WORKFLOW, JAVA, NDK
}

// ATOMICITY & CONSISTENCY
// After file delivery, verify integrity (e.g., checksum). If failed, gray out item in UI and show retry button.
// On retry, attempt download again. If still failed, keep item grayed out.
// On failed download, clean up any partial file but keep grayed-out listing in Drop Folder widget.

// TODO: Notification/Confirmation to Recipient
// TODO: Implement notification/confirmation to recipient on successful file delivery. See KNOWLEDGE doc.

class IntelligentDistributedComputeService(
    private val meshNetwork: MeshNetworkInterface,
    private val gossipProtocol: EnhancedGossipProtocol,
    private val quorumManager: QuorumManager,
    private val resourceManager: ResourceManager,
    private val pythonExecutor: PythonExecutor,
    private val liteRTEngine: LiteRTEngine,
    private val betaLogger: com.ustadmobile.meshrabiya.beta.BetaTestLogger? = null
) {
    // MeshEcosystemListener integration
    private var meshEcosystemListener: com.ustadmobile.meshrabiya.service.MeshEcosystemListener? = null

    fun registerWithEcosystemListener(listener: com.ustadmobile.meshrabiya.service.MeshEcosystemListener) {
        meshEcosystemListener = listener
        listener.registerComputeService(this)
    }

    fun handleTaskDataAccessUpdate(updateMsg: com.ustadmobile.meshrabiya.service.MeshGossipService.TaskDataAccessUpdateMessage) {
        // Implement logic for task data access update (e.g., update task permissions, trigger compute job)
    }

    val builtinLibraryEntries: List<LibraryEntry> = listOf(
        LibraryEntry.PythonServiceEntry(
            serviceId = "builtin_image_preprocessing",
            scriptCode = generateImagePreprocessingScript(),
            libraries = setOf(PythonLibrary.OPENCV, PythonLibrary.NUMPY, PythonLibrary.JSON, PythonLibrary.BASE64),
            manifest = ServiceManifest(
                serviceType = ServiceType.PYTHON,
                version = "1.0.0",
                author = "Orbot Team",
                signature = null,
                resourceRequirements = ResourceRequirements(
                    minRAMMB = 512,
                    preferredRAMMB = 1024,
                    cpuIntensity = CPUIntensity.MODERATE
                ),
                builtin = true
            ),
            executionProfile = ExecutionProfile(deterministic = true),
            inputs = listOf(ServiceInput("images", "List<Base64Image>", true)),
            outputs = listOf(ServiceOutput("processed_tensors", "List<Base64Tensor>")),
            capabilities = setOf(ServiceCapability.ML, ServiceCapability.CV)
        ),
        LibraryEntry.LiteRTServiceEntry(
            serviceId = "builtin_mobilenet_v3_inference",
            modelId = "mobilenet_v3_quantized",
            modelConfig = LiteRTConfig(useGPU = true, useNNAPI = true, numThreads = 2),
            manifest = ServiceManifest(
                serviceType = ServiceType.LITERT,
                version = "1.0.0",
                author = "Orbot Team",
                signature = null,
                resourceRequirements = ResourceRequirements(
                    minRAMMB = 256,
                    preferredRAMMB = 512,
                    cpuIntensity = CPUIntensity.LIGHT,
                    requiresGPU = true
                ),
                builtin = true
            ),
            executionProfile = ExecutionProfile(deterministic = true),
            inputs = listOf(ServiceInput("input_tensors", "List<Base64Tensor>", true)),
            outputs = listOf(ServiceOutput("inference_results", "List<FloatArray>")),
            capabilities = setOf(ServiceCapability.ML, ServiceCapability.CV)
        ),
        LibraryEntry.HybridServiceEntry(
            serviceId = "builtin_hybrid_image_pipeline",
            pythonPreprocessing = null,
            liteRTInference = LibraryEntry.LiteRTServiceEntry(
                serviceId = "builtin_mobilenet_v3_inference",
                modelId = "mobilenet_v3_quantized",
                modelConfig = LiteRTConfig(useGPU = true, useNNAPI = true, numThreads = 2),
                manifest = ServiceManifest(
                    serviceType = ServiceType.LITERT,
                    version = "1.0.0",
                    author = "Orbot Team",
                    signature = null,
                    resourceRequirements = ResourceRequirements(
                        minRAMMB = 256,
                        preferredRAMMB = 512,
                        cpuIntensity = CPUIntensity.LIGHT,
                        requiresGPU = true
                    ),
                    builtin = true
                ),
                executionProfile = ExecutionProfile(deterministic = true),
                inputs = listOf(ServiceInput("input_tensors", "List<Base64Tensor>", true)),
                outputs = listOf(ServiceOutput("inference_results", "List<FloatArray>")),
                capabilities = setOf(ServiceCapability.ML, ServiceCapability.CV)
            ),
            pythonPostprocessing = null,
            manifest = ServiceManifest(
                serviceType = ServiceType.HYBRID,
                version = "1.0.0",
                author = "Orbot Team",
                signature = null,
                resourceRequirements = ResourceRequirements(
                    minRAMMB = 512,
                    preferredRAMMB = 1024,
                    cpuIntensity = CPUIntensity.MODERATE
                ),
                builtin = true
            ),
            executionProfile = ExecutionProfile(deterministic = true),
            inputs = listOf(ServiceInput("images", "List<Base64Image>", true)),
            outputs = listOf(ServiceOutput("inference_results", "List<FloatArray>")),
            capabilities = setOf(ServiceCapability.ML, ServiceCapability.CV)
        )
    )

    fun showFolderPickerDialog(
        context: android.content.Context,
        initialPath: String = "/DropFolder",
        onFolderSelected: (String) -> Unit
    ) {
        val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(context)
        dialogBuilder.setTitle("Select Destination Folder")
        val folderList = getDropFolderSubfolders(initialPath)
        val adapter = object : android.widget.ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, folderList) {}
        dialogBuilder.setAdapter(adapter) { _, which ->
            val selectedFolder = folderList[which]
            onFolderSelected(selectedFolder)
        }
        dialogBuilder.setNegativeButton("Cancel", null)
        dialogBuilder.show()
    }

    private fun getDropFolderSubfolders(path: String): List<String> {
        return StorageDropFolderManager.getSubfolders(path)
    }

    fun pickDestinationFolder(
        context: android.content.Context,
        onPathSelected: (String) -> Unit
    ) {
        showFolderPickerDialog(context) { selectedPath ->
            onPathSelected(selectedPath)
        }
    }

    private val activeJobs = ConcurrentHashMap<String, DistributedJob>()
    private val nodeCapabilities = ConcurrentHashMap<String, NodeCapabilitySnapshot>()
    private val networkTopology = NetworkTopologyTracker()
    private val taskScheduler = IntelligentTaskScheduler()

    sealed class ComputeTask {
        abstract val taskId: String
        abstract val estimatedExecutionMs: Long
        abstract val resourceRequirements: ResourceRequirements
        abstract val dependencies: List<String>

        data class PythonTask(
            override val taskId: String,
            val scriptCode: String,
            val inputData: Map<String, Any>,
            val libraries: Set<PythonLibrary>,
            override val estimatedExecutionMs: Long,
            override val resourceRequirements: ResourceRequirements,
            override val dependencies: List<String> = emptyList(),
            val outputSchema: OutputSchema
        ) : ComputeTask()

        data class LiteRTTask(
            override val taskId: String,
            val modelId: String,
            val inputTensors: List<ByteArray>,
            val modelConfig: LiteRTConfig,
            override val estimatedExecutionMs: Long,
            override val resourceRequirements: ResourceRequirements,
            override val dependencies: List<String> = emptyList(),
            val inferenceConfig: InferenceConfig
        ) : ComputeTask()

        data class HybridTask(
            override val taskId: String,
            val pythonPreprocessing: PythonTask?,
            val liteRTInference: LiteRTTask,
            val pythonPostprocessing: PythonTask?,
            override val estimatedExecutionMs: Long,
            override val resourceRequirements: ResourceRequirements,
            override val dependencies: List<String> = emptyList()
        ) : ComputeTask()

        data class DistributedStorageTask(
            override val taskId: String,
            val operation: StorageOperation,
            val fileId: String,
            val data: ByteArray?,
            val replicationFactor: Int,
            override val estimatedExecutionMs: Long,
            override val resourceRequirements: ResourceRequirements,
            override val dependencies: List<String> = emptyList(),
            val destinationPath: String? = null
        ) : ComputeTask()
    }

    enum class StorageOperation {
        STORE, RETRIEVE, DELETE, REPLICATE, VERIFY
    }

    data class OutputSchema(
        val format: OutputFormat,
        val expectedSizeBytes: Long,
        val schema: Map<String, String>
    )

    enum class OutputFormat {
        JSON, BINARY, IMAGE, TENSOR, CSV
    }

    inner class IntelligentTaskScheduler {
        suspend fun distributeJob(job: DistributedJob): ExecutionPlan {
            val meshIntelligence = gatherMeshIntelligence()
            val tasks = decomposeJobIntelligently(job, meshIntelligence)
            val dependencyGraph = buildDependencyGraph(tasks)
            val assignments = assignTasksIntelligently(tasks, meshIntelligence, dependencyGraph)
            val optimizedPlan = optimizeExecutionPlan(assignments, meshIntelligence)
            return optimizedPlan
        }

        private suspend fun gatherMeshIntelligence(): MeshIntelligence {
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

        private fun calculateNetworkProximity(nodeStates: Map<String, NodeCapabilitySnapshot>): NetworkProximityMatrix {
            val matrix = mutableMapOf<Pair<String, String>, Int>()
            for ((nodeId1, _) in nodeStates) {
                for ((nodeId2, _) in nodeStates) {
                    if (nodeId1 != nodeId2) {
                        val latency = (50..200).random()
                        matrix[Pair(nodeId1, nodeId2)] = latency
                    }
                }
            }
            return NetworkProximityMatrix(matrix)
        }

        private fun assessNodeSpecializations(nodeStates: Map<String, NodeCapabilitySnapshot>): Map<String, NodeSpecialization> {
            return nodeStates.mapValues { (_, nodeState) ->
                NodeSpecialization(
                    hasGPUAcceleration = nodeState.resourceCapabilities.supportsGPU,
                    hasNPUAcceleration = nodeState.resourceCapabilities.supportsNPU,
                    hasPythonOptimizations = true,
                    supportedPythonLibraries = PythonLibrary.values().toSet(),
                    supportedLiteRTModels = setOf("mobilenet_v3_quantized", "yolo_nano"),
                    specializedCapabilities = determineSpecializedCapabilities(nodeState),
                    storageCapabilityGB = nodeState.resourceCapabilities.storageGB
                )
            }
        }

        private fun determineSpecializedCapabilities(nodeState: NodeCapabilitySnapshot): Set<SpecializedCapability> {
            val capabilities = mutableSetOf<SpecializedCapability>()
            if (nodeState.resourceCapabilities.supportsGPU) {
                capabilities.add(SpecializedCapability.IMAGE_PROCESSING)
                capabilities.add(SpecializedCapability.COMPUTER_VISION)
            }
            if (nodeState.resourceCapabilities.availableRAMMB > 1024) {
                capabilities.add(SpecializedCapability.NLP)
                capabilities.add(SpecializedCapability.SCIENTIFIC_COMPUTING)
            }
            capabilities.add(SpecializedCapability.DISTRIBUTED_STORAGE)
            return capabilities
        }

        private fun decomposeJobIntelligently(
            job: DistributedJob,
            intelligence: MeshIntelligence
        ): List<ComputeTask> {
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

        private fun decomposeDistributedStorage(
            job: DistributedJob,
            intelligence: MeshIntelligence
        ): List<ComputeTask> {
            val tasks = mutableListOf<ComputeTask>()
            val fileId = job.inputData["fileId"] as? String ?: return emptyList()
            val operation = job.inputData["operation"] as? StorageOperation ?: StorageOperation.STORE
            val data = job.inputData["data"] as? ByteArray
            val replicationFactor = job.inputData["replicationFactor"] as? Int ?: 3
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

        private fun decomposeImageProcessing(
            job: DistributedJob,
            intelligence: MeshIntelligence
        ): List<ComputeTask> {
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
                repeat(minOf(4, availableGPUNodes)) { index ->
                    tasks.add(ComputeTask.LiteRTTask(
                        taskId = "${job.jobId}_inference_$index",
                        modelId = "mobilenet_v3_quantized",
                        inputTensors = listOf(),
                        modelConfig = LiteRTConfig(
                            useGPU = true,
                            useNNAPI = true,
                            numThreads = 2
                        ),
                        estimatedExecutionMs = 150L,
                        resourceRequirements = ResourceRequirements(
                            minRAMMB = 256,
                            preferredRAMMB = 512,
                            cpuIntensity = CPUIntensity.LIGHT,
                            requiresGPU = true
                        ),
                        dependencies = listOf("${job.jobId}_preprocess"),
                        inferenceConfig = InferenceConfig(
                            batchSize = 1,
                            precision = Precision.QUANTIZED
                        )
                    ))
                }
            }
            return tasks
        }

        private fun decomposeDataAnalysis(job: DistributedJob, intelligence: MeshIntelligence): List<ComputeTask> {
            return listOf(createFallbackTask(job))
        }

        private fun decomposeMLPipeline(job: DistributedJob, intelligence: MeshIntelligence): List<ComputeTask> {
            return listOf(createFallbackTask(job))
        }

        private fun decomposeSensorFusion(job: DistributedJob, intelligence: MeshIntelligence): List<ComputeTask> {
            return listOf(createFallbackTask(job))
        }

        private fun decomposeCollaborativeFiltering(job: DistributedJob, intelligence: MeshIntelligence): List<ComputeTask> {
            return listOf(createFallbackTask(job))
        }

        private fun createFallbackTask(job: DistributedJob): ComputeTask {
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

        private fun buildDependencyGraph(tasks: List<ComputeTask>): DependencyGraph {
            val dependencies = tasks.associate { task ->
                task.taskId to task.dependencies
            }
            return DependencyGraph(dependencies)
        }

        private fun assignTasksIntelligently(
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

        private fun findSuitableNodes(
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
                (!task.resourceRequirements.requiresStorage ||
                 (intelligence.specializations[node.nodeId]?.storageCapabilityGB ?: 0f) >= task.resourceRequirements.minStorageGB) &&
                (dependencyNodes.isEmpty() ||
                 dependencyNodes.any { depNode ->
                     intelligence.proximityMatrix.getLatency(node.nodeId, depNode) <= task.resourceRequirements.maxNetworkLatencyMs
                 }) &&
                isNodeCompatibleWithTask(node, task, intelligence.specializations[node.nodeId])
            }
        }

        private fun selectOptimalNode(
            task: ComputeTask,
            candidates: List<NodeCapabilitySnapshot>,
            intelligence: MeshIntelligence,
            currentWorkloads: Map<String, Int>
        ): NodeCapabilitySnapshot {
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

        private fun calculateResourceScore(node: NodeCapabilitySnapshot, task: ComputeTask): Float {
            val ramRatio = node.resourceCapabilities.availableRAMMB.toFloat() / task.resourceRequirements.preferredRAMMB
            val cpuScore = if (node.resourceCapabilities.availableCPU >= getCPURequirement(task.resourceRequirements.cpuIntensity)) 1.0f else 0.5f
            val batteryScore = (node.batteryInfo.level - task.resourceRequirements.minBatteryLevel).toFloat() / 100f
            return (ramRatio.coerceAtMost(2.0f) + cpuScore + batteryScore) / 3f
        }

        private fun calculateNetworkScore(
            node: NodeCapabilitySnapshot,
            task: ComputeTask,
            intelligence: MeshIntelligence
        ): Float {
            val relevantNodes = intelligence.nodeStates.keys.take(5)
            val avgLatency = relevantNodes.map { otherNode ->
                intelligence.proximityMatrix.getLatency(node.nodeId, otherNode)
            }.average()
            return (1000f - avgLatency.toFloat()).coerceAtLeast(0f) / 1000f
        }

        private fun calculateSpecializationScore(
            node: NodeCapabilitySnapshot,
            task: ComputeTask,
            intelligence: MeshIntelligence
        ): Float {
            val specialization = intelligence.specializations[node.nodeId] ?: return 0.5f
            return when (task) {
                is ComputeTask.LiteRTTask -> {
                    if (specialization.hasNPUAcceleration) 1.0f
                    else if (specialization.hasGPUAcceleration) 0.8f
                    else 0.6f
                }
                is ComputeTask.PythonTask -> {
                    if (specialization.hasPythonOptimizations) 0.9f
                    else 0.7f
                }
                is ComputeTask.HybridTask -> 0.8f
                is ComputeTask.DistributedStorageTask -> {
                    if (specialization.specializedCapabilities.contains(SpecializedCapability.DISTRIBUTED_STORAGE)) 1.0f
                    else 0.5f
                }
            }
        }

        private fun getCPURequirement(intensity: CPUIntensity): Float {
            return when (intensity) {
                CPUIntensity.LIGHT -> 0.2f
                CPUIntensity.MODERATE -> 0.5f
                CPUIntensity.HEAVY -> 0.8f
                CPUIntensity.BURST -> 1.0f
            }
        }

        private fun isNodeCompatibleWithTask(
            node: NodeCapabilitySnapshot,
            task: ComputeTask,
            specialization: NodeSpecialization?
        ): Boolean {
            return when (task) {
                is ComputeTask.PythonTask -> {
                    task.libraries.all { lib ->
                        specialization?.supportedPythonLibraries?.contains(lib) == true
                    }
                }
                is ComputeTask.LiteRTTask -> {
                    specialization?.supportedLiteRTModels?.contains(task.modelId) == true
                }
                is ComputeTask.HybridTask -> {
                    isNodeCompatibleWithTask(node, task.liteRTInference, specialization) &&
                    (task.pythonPreprocessing?.let { isNodeCompatibleWithTask(node, it, specialization) } != false) &&
                    (task.pythonPostprocessing?.let { isNodeCompatibleWithTask(node, it, specialization) } != false)
                }
                is ComputeTask.DistributedStorageTask -> {
                    specialization?.specializedCapabilities?.contains(SpecializedCapability.DISTRIBUTED_STORAGE) == true &&
                    node.resourceCapabilities.storageGB >= task.resourceRequirements.minStorageGB
                }
            }
        }

        private fun optimizeExecutionPlan(
            assignments: Map<String, String>,
            intelligence: MeshIntelligence
        ): ExecutionPlan {
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
    }

    private fun generateImagePreprocessingScript(): String = """
import numpy as np
import cv2
import json
import base64

def preprocess_images(input_data):
    images = input_data.get('images', [])
    processed = []
    
    for img_b64 in images:
        img_data = base64.b64decode(img_b64)
        img_array = np.frombuffer(img_data, np.uint8)
        img = cv2.imdecode(img_array, cv2.IMREAD_COLOR)
        img_resized = cv2.resize(img, (224, 224))
        img_normalized = img_resized.astype(np.float32) / 255.0
        tensor_data = img_normalized.tobytes()
        processed.append(base64.b64encode(tensor_data).decode())
    return {
        'processed_tensors': processed,
        'tensor_shape': [224, 224, 3],
        'count': len(processed)
    }

result = preprocess_images(globals().get('input_data', {}))
print(json.dumps(result))
"""

    data class MeshIntelligence(
        val nodeStates: Map<String, NodeCapabilitySnapshot>,
        val networkMetrics: NetworkMetrics,
        val resourceAvailability: ClusterResourceState,
        val proximityMatrix: NetworkProximityMatrix,
        val specializations: Map<String, NodeSpecialization>,
        val activeQuorums: List<ActiveQuorum>,
        val timestamp: Long
    )

    data class NodeCapabilitySnapshot(
        val nodeId: String,
        val resourceCapabilities: ResourceCapabilities,
        val batteryInfo: BatteryInfo,
        val thermalState: ThermalState,
        val networkLatency: NetworkLatencyInfo,
        val reliabilityScore: Float,
        val currentLoad: Float,
        val availableForCompute: Boolean
    )

    data class ResourceCapabilities(
        val availableRAMMB: Int,
        val availableCPU: Float,
        val storageGB: Float,
        val networkBandwidthMbps: Float,
        val supportsGPU: Boolean,
        val supportsNPU: Boolean
    )

    data class NodeSpecialization(
        val hasGPUAcceleration: Boolean,
        val hasNPUAcceleration: Boolean,
        val hasPythonOptimizations: Boolean,
        val supportedPythonLibraries: Set<PythonLibrary>,
        val supportedLiteRTModels: Set<String>,
        val specializedCapabilities: Set<SpecializedCapability>,
        val storageCapabilityGB: Float
    )

    enum class SpecializedCapability {
        IMAGE_PROCESSING, AUDIO_PROCESSING, NLP, COMPUTER_VISION,
        SIGNAL_PROCESSING, CRYPTOGRAPHY, SCIENTIFIC_COMPUTING, DISTRIBUTED_STORAGE
    }

    data class NetworkProximityMatrix(private val matrix: Map<Pair<String, String>, Int>) {
        fun getLatency(node1: String, node2: String): Int {
            return matrix[Pair(node1, node2)] ?: matrix[Pair(node2, node1)] ?: Int.MAX_VALUE
        }
    }

    data class ClusterResourceState(
        val availableNodes: Int,
        val totalRAMMB: Long,
        val averageRAMMB: Int,
        val totalStorageGB: Long,
        val averageCPULoad: Float,
        val nodesWithGPU: Int,
        val nodesWithNPU: Int
    )

    data class ActiveQuorum(
        val quorumId: String,
        val quorumType: QuorumType,
        val memberNodes: Set<String>,
        val currentTask: String?,
        val resourcesAllocated: ResourceAllocation
    )

    data class ResourceAllocation(
        val allocatedRAMMB: Long,
        val allocatedCPU: Float,
        val allocatedStorage: Long
    )

    data class ExecutionPlan(
        val jobId: String,
        val tasks: List<ComputeTask>,
        val assignments: Map<String, String>,
        val dependencyGraph: DependencyGraph,
        val estimatedExecutionMs: Long,
        val resourceAllocation: ResourceAllocation,
        val aggregationStrategy: AggregationStrategy
    )

    class DependencyGraph(private val dependencies: Map<String, List<String>>) {
        fun getDependencyDepth(taskId: String): Int {
            val deps = dependencies[taskId] ?: return 0
            return if (deps.isEmpty()) 0 else deps.maxOf { getDependencyDepth(it) } + 1
        }

        fun getExecutionLevels(): List<List<String>> {
            val levels = mutableListOf<List<String>>()
            val processed = mutableSetOf<String>()
            val allTasks = dependencies.keys.toSet()
            while (processed.size < allTasks.size) {
                val currentLevel = allTasks.filter { taskId ->
                    taskId !in processed &&
                    (dependencies[taskId]?.all { it in processed } ?: true)
                }
                if (currentLevel.isEmpty()) break
                levels.add(currentLevel)
                processed.addAll(currentLevel)
            }
            return levels
        }
    }

    enum class JobType {
        IMAGE_PROCESSING, DATA_ANALYSIS, ML_PIPELINE,
        SENSOR_FUSION, COLLABORATIVE_FILTERING, DISTRIBUTED_STORAGE
    }

    enum class AggregationStrategy {
        SIMPLE_CONCAT, MAJORITY_VOTE, WEIGHTED_AVERAGE, ENSEMBLE_COMBINE
    }

    interface MeshNetworkInterface {
        suspend fun executeRemoteTask(nodeId: String, request: TaskExecutionRequest): TaskExecutionResponse
    }

    suspend fun MeshNetworkInterface.sendStorageRequest(targetNodeId: String, fileInfo: com.ustadmobile.meshrabiya.storage.DistributedFileInfo, operation: String): Boolean {
        throw NotImplementedError("sendStorageRequest not implemented by MeshNetworkInterface bridge")
    }

    suspend fun MeshNetworkInterface.requestFileFromNode(nodeId: String, fileIdOrPath: String): com.ustadmobile.meshrabiya.storage.FileReference? {
        throw NotImplementedError("requestFileFromNode not implemented by MeshNetworkInterface bridge")
    }

    suspend fun MeshNetworkInterface.getAvailableStorageNodes(): List<String> {
        throw NotImplementedError("getAvailableStorageNodes not implemented by MeshNetworkInterface bridge")
    }

    suspend fun MeshNetworkInterface.deleteFileOnNode(nodeId: String, fileId: String): Boolean {
        throw NotImplementedError("deleteFileOnNode not implemented by MeshNetworkInterface bridge")
    }

    interface EnhancedGossipProtocol {
        fun getCurrentNodeStates(): Map<String, NodeCapabilitySnapshot>
    }

    interface QuorumManager {
        fun getActiveQuorums(): List<ActiveQuorum>
    }

    interface ResourceManager {
        fun getClusterResourceState(): ClusterResourceState
    }

    interface PythonExecutor {
        suspend fun executeTask(task: ComputeTask.PythonTask): TaskExecutionResult
    }

    interface LiteRTEngine {
        suspend fun executeTask(task: ComputeTask.LiteRTTask): TaskExecutionResult
    }

    data class NetworkMetrics(val averageLatency: Long, val throughput: Long)
    data class NetworkLatencyInfo(val avgLatency: Long = 50L)
    data class BatteryInfo(val level: Int, val isCharging: Boolean = false)

    enum class QuorumType { COMPUTE, STORAGE, GATEWAY, HYBRID }

    data class DistributedJob(
        val jobId: String,
        val jobType: JobType,
        val inputData: Map<String, Any>,
        val priority: JobPriority,
        val maxExecutionTimeMs: Long,
        val aggregationStrategy: AggregationStrategy,
        val destinationPath: String? = null
    )

    enum class JobPriority { BACKGROUND, NORMAL, HIGH, CRITICAL }

    class NetworkTopologyTracker {
        fun getCurrentMetrics(): NetworkMetrics {
            return NetworkMetrics(averageLatency = 100L, throughput = 1000000L)
        }
    }

    data class TaskExecutionRequest(
        val taskId: String,
        val taskData: ByteArray,
        val timeoutMs: Long
    )

    sealed class TaskExecutionResponse {
        data class Success(
            val result: Map<String, Any>,
            val executionTimeMs: Long
        ) : TaskExecutionResponse()

        data class Failed(val error: String) : TaskExecutionResponse()
        object Timeout : TaskExecutionResponse()
    }

    sealed class TaskExecutionResult {
        abstract val taskId: String

        data class Success(
            override val taskId: String,
            val result: Map<String, Any>,
            val executionTimeMs: Long,
            val nodeId: String
        ) : TaskExecutionResult()

        data class Failed(
            override val taskId: String,
            val error: String,
            val isCritical: Boolean
        ) : TaskExecutionResult()
    }
}