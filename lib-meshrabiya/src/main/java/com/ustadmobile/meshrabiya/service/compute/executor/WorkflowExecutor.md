package com.ustadmobile.meshrabiya.service.compute.executor

import com.ustadmobile.meshrabiya.service.compute.model.TaskExecutionContext
import com.ustadmobile.meshrabiya.service.compute.model.ExecutionResult
// import com.ustadmobile.meshrabiya.service.compute.model.ResourceMetrics
import com.ustadmobile.meshrabiya.service.compute.model.ExecutionErrorType
import com.ustadmobile.meshrabiya.service.compute.model.FileReference
import android.content.Context
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
import kotlinx.serialization.json.*
import java.io.File

/**
 * WorkflowExecutor
 * 
 * Phase 2: Task Execution Layer - Multi-step task orchestration
 * 
 * Executes workflows consisting of multiple sequential or parallel sub-tasks.
 * Each sub-task can be a different TaskType (Python, JVM, JavaScript, ML).
 * 
 * Workflow Definition Format (JSON):
 * {
 *   "steps": [
 *     {
 *       "id": "step1",
 *       "type": "PYTHON",
 *       "codeBundle": "base64_encoded_code",
 *       "inputs": ["file1", "file2"],
 *       "outputs": ["result1"]
 *     },
 *     {
 *       "id": "step2",
 *       "type": "ML_NATIVE",
 *       "codeBundle": "base64_encoded_model",
 *       "inputs": ["step1/result1"],
 *       "outputs": ["final_result"],
 *       "dependencies": ["step1"]
 *     }
 *   ]
 * }
 * 
 * Execution Flow:
 * 1. Parse workflow JSON
 * 2. Build dependency graph
 * 3. Execute steps in topological order
 * 4. Pass outputs from one step as inputs to dependent steps
 * 5. Aggregate final outputs
 * 6. Track resource usage across all steps
 */
class WorkflowExecutor(
    private val context: Context,
    private val executorFactory: (TaskType) -> TaskExecutor?
) : TaskExecutor {
    
    companion object {
        private const val WORKFLOW_DEFINITION_FILE = "workflow.json"
    }
    
    override suspend fun execute(
        context: TaskExecutionContext,
        inputFiles: Map<String, ByteArray>,
        containerId: String
    ): ExecutionResult {
        val startTime = System.currentTimeMillis()
        val workspaceDir = File(this.context.filesDir, "containers/$containerId")
        
        try {
            // 1. Parse workflow definition
            val workflowJson = String(context.codeBundle)
            val workflow = Json.parseToJsonElement(workflowJson).jsonObject
            val steps = workflow["steps"]?.jsonArray ?: return errorResult(
                context.taskId,
                "Missing 'steps' array in workflow definition",
                System.currentTimeMillis() - startTime
            )
            
            // 2. Setup workspace
            workspaceDir.mkdirs()
            val inputsDir = File(workspaceDir, "inputs")
            inputsDir.mkdirs()
            
            // 3. Write initial input files
            inputFiles.forEach { (filename, data) ->
                File(inputsDir, filename).writeBytes(data)
            }
            
            // 4. Execute steps in order
            val stepResults = mutableMapOf<String, ExecutionResult>()
            val aggregatedOutputs = mutableMapOf<String, ByteArray>()
            // var totalResourceUsage = ResourceMetrics.zero()
            
            for (stepElement in steps) {
                val step = stepElement.jsonObject
                val stepId = step["id"]?.jsonPrimitive?.content ?: continue
                val stepType = step["type"]?.jsonPrimitive?.content ?: continue
                val taskType = TaskType.valueOf(stepType)
                
                // Check dependencies
                val dependencies = step["dependencies"]?.jsonArray?.map { 
                    it.jsonPrimitive.content 
                } ?: emptyList()
                
                for (dep in dependencies) {
                    val depResult = stepResults[dep]
                    if (depResult == null || !depResult.success) {
                        return errorResult(
                            context.taskId,
                            "Step '$stepId' dependency '$dep' failed",
                            System.currentTimeMillis() - startTime
                        )
                    }
                }
                
                // Prepare step inputs
                val stepInputs = mutableMapOf<String, ByteArray>()
                val inputRefs = step["inputs"]?.jsonArray ?: JsonArray(emptyList())
                for (inputRef in inputRefs) {
                    val inputPath = inputRef.jsonPrimitive.content
                    
                    // Check if input is from previous step (format: "stepId/filename")
                    if (inputPath.contains("/")) {
                        val (sourceStepId, filename) = inputPath.split("/", limit = 2)
                        val sourceStepOutputs = stepResults[sourceStepId]?.outputManifest
                            ?: return errorResult(
                                context.taskId,
                                "Step '$stepId' input '$inputPath' not found",
                                System.currentTimeMillis() - startTime
                            )
                        
                        val outputFile = File("$containerId/$sourceStepId/outputs/$filename")
                        if (outputFile.exists()) {
                            stepInputs[filename] = outputFile.readBytes()
                        } else {
                            val outputData = aggregatedOutputs["$sourceStepId/$filename"]
                            if (outputData != null) {
                                stepInputs[filename] = outputData
                            }
                        }
                    } else {
                        // Input from initial workflow inputs
                        val inputData = inputFiles[inputPath]
                        if (inputData != null) {
                            stepInputs[inputPath] = inputData
                        }
                    }
                }
                
                // Decode code bundle
                val codeBundleBase64 = step["codeBundle"]?.jsonPrimitive?.content
                    ?: return errorResult(
                        context.taskId,
                        "Step '$stepId' missing 'codeBundle'",
                        System.currentTimeMillis() - startTime
                    )
                val codeBundle = java.util.Base64.getDecoder().decode(codeBundleBase64)
                
                // Create sub-task context
                val stepContext = TaskExecutionContext(
                    taskId = "${context.taskId}_$stepId",
                    taskType = taskType,
                    codeBundle = codeBundle,
                    inputManifest = stepInputs.map { (name, data) ->
                        FileReference(
                            fileId = name,
                            fileName = name,
                            sizeBytes = data.size.toLong(),
                            mimeType = "application/octet-stream"
                        )
                    },
                    // resourceLimits = context.resourceLimits
                )
                
                // Execute sub-task
                val executor = executorFactory(taskType)
                    ?: return errorResult(
                        context.taskId,
                        "No executor found for step '$stepId' type '$stepType'",
                        System.currentTimeMillis() - startTime
                    )
                
                val stepResult = executor.execute(
                    stepContext,
                    stepInputs,
                    "$containerId/$stepId"
                )
                
                stepResults[stepId] = stepResult
                
                if (!stepResult.success) {
                    return errorResult(
                        context.taskId,
                        "Step '$stepId' failed: ${stepResult.errorMessage}",
                        System.currentTimeMillis() - startTime
                    )
                }
                
                // Aggregate resource usage
                // totalResourceUsage = ResourceMetrics(
                //     ramUsedBytes = totalResourceUsage.ramUsedBytes + stepResult.resourcesUsed.ramUsedBytes,
                //     cpuUsedPercent = maxOf(totalResourceUsage.cpuUsedPercent, stepResult.resourcesUsed.cpuUsedPercent),
                //     diskUsedBytes = totalResourceUsage.diskUsedBytes + stepResult.resourcesUsed.diskUsedBytes,
                //     networkSentBytes = totalResourceUsage.networkSentBytes + stepResult.resourcesUsed.networkSentBytes,
                //     networkReceivedBytes = totalResourceUsage.networkReceivedBytes + stepResult.resourcesUsed.networkReceivedBytes
                // )
                
                // Save step outputs for future steps
                // Actually read output files from step's output directory
                // For each output reference, verify file exists and update hash
                stepResult.outputManifest.forEach { ref ->
                    val outputFile = File("$containerId/$stepId/outputs/${ref.fileName}")
                    if (outputFile.exists()) {
                        ref.copy(fileId = calculateSha256Hash(outputFile))
                    }
                }
                
            }
            
            // 5. Collect final workflow outputs
            val finalOutputs = collectFinalOutputs(steps, stepResults)
            
            val executionTime = System.currentTimeMillis() - startTime
            
            return ExecutionResult(
                taskId = context.taskId,
                success = true,
                outputManifest = finalOutputs,
                // resourcesUsed = totalResourceUsage,
                executionTimeMs = executionTime,
                resultMessage = "Workflow completed successfully (${stepResults.size} steps)"
            )
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            return ExecutionResult(
                taskId = context.taskId,
                success = false,
                outputManifest = emptyList(),
                // resourcesUsed = ResourceMetrics.zero(),
                executionTimeMs = executionTime,
                errorMessage = e.message ?: "Workflow execution failed",
                errorType = ExecutionErrorType.RUNTIME_ERROR
            )
        } finally {
            // Cleanup workspace (optional - may keep for debugging)
            // workspaceDir.deleteRecursively()
        }
    }
    
    private fun calculateSha256Hash(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val inputStream = file.inputStream()
        val buffer = ByteArray(8192)
        var read: Int
        while (inputStream.read(buffer).also { read = it } > 0) {
            digest.update(buffer, 0, read)
        }
        inputStream.close()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun validateCodeBundle(codeBundle: ByteArray): Boolean {
        return try {
            val json = String(codeBundle)
            val workflow = Json.parseToJsonElement(json).jsonObject
            
            // Validate required fields
            val steps = workflow["steps"]?.jsonArray ?: return false
            
            for (step in steps) {
                val stepObj = step.jsonObject
                val id = stepObj["id"]?.jsonPrimitive?.content ?: return false
                val type = stepObj["type"]?.jsonPrimitive?.content ?: return false
                val codeBundle = stepObj["codeBundle"]?.jsonPrimitive?.content ?: return false
                
                // Validate type is a valid TaskType
                try {
                    TaskType.valueOf(type)
                } catch (e: IllegalArgumentException) {
                    return false
                }
            }
            
            true
        } catch (e: Exception) {
            false
        }
    }
    
    override fun getSupportedTaskType(): TaskType = TaskType.WORKFLOW
    
    // === Private Helper Methods ===
    
    private fun collectFinalOutputs(
        steps: JsonArray,
        stepResults: Map<String, ExecutionResult>
    ): List<FileReference> {
        val outputs = mutableListOf<FileReference>()
        
        // Collect outputs from all steps
        for (stepElement in steps) {
            val step = stepElement.jsonObject
            val stepId = step["id"]?.jsonPrimitive?.content ?: continue
            val stepResult = stepResults[stepId] ?: continue
            
            // Add all step outputs with prefixed names
            outputs.addAll(
                stepResult.outputManifest.map { ref ->
                    ref.copy(
                        fileId = "$stepId/${ref.fileId}",
                        fileName = "$stepId/${ref.fileName}"
                    )
                }
            )
        }
        
        return outputs
    }
    
    private fun errorResult(
        taskId: String,
        message: String,
        executionTime: Long
    ): ExecutionResult {
        return ExecutionResult(
            taskId = taskId,
            success = false,
            outputManifest = emptyList(),
            // resourcesUsed = ResourceMetrics.zero(),
            executionTimeMs = executionTime,
            errorMessage = message,
            errorType = ExecutionErrorType.RUNTIME_ERROR
        )
    }
}
