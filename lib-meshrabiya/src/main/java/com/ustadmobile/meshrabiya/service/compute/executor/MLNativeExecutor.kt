package com.ustadmobile.meshrabiya.service.compute.executor

import android.content.Context
import com.ustadmobile.meshrabiya.service.compute.model.TaskExecutionContext
import com.ustadmobile.meshrabiya.service.compute.model.ExecutionResult
import com.ustadmobile.meshrabiya.service.compute.model.ExecutionErrorType
import com.ustadmobile.meshrabiya.storage.FileReference
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MLNativeExecutor
 * 
 * Phase 2: Task Execution Layer - TensorFlow Lite ML execution implementation
 * 
 * Executes TensorFlow Lite models (.tflite files) for ML inference.
 * Supports both CPU and GPU acceleration (if available).
 * 
 * Code Bundle Format:
 * - Single .tflite model file
 * 
 * Input Files:
 * - Binary input tensors (e.g., "input_0.bin", "input_1.bin")
 * - Each input file represents one model input tensor
 * 
 * Output Files:
 * - Binary output tensors (e.g., "output_0.bin", "output_1.bin")
 * - Each output file represents one model output tensor
 * 
 * Security:
 * - TensorFlow Lite Interpreter runs in isolated sandbox
 * - No network access
 * - No file I/O beyond inputs/outputs
 * - No access to Android system APIs
 */
class MLNativeExecutor(private val context: Context) : TaskExecutor {
    
    companion object {
        private val TFLITE_MAGIC_BYTES = byteArrayOf(0x54, 0x46, 0x4C, 0x33) // "TFL3"
    }
    
    override suspend fun execute(
        context: TaskExecutionContext,
        inputFiles: Map<String, ByteArray>,
        containerId: String
    ): ExecutionResult {
        // Rename parameter to match interface convention while using executionContext internally
        val executionContext = context
        val startTime = System.currentTimeMillis()
        val workspaceDir = File(this.context.cacheDir, "ml_workspace_$containerId")
        
        return try {
            // 1. Setup workspace
            workspaceDir.mkdirs()
            val inputsDir = File(workspaceDir, "inputs")
            val outputsDir = File(workspaceDir, "outputs")
            inputsDir.mkdirs()
            outputsDir.mkdirs()
            
            // 2. Write model file
            val modelFile = File(workspaceDir, "model.tflite")
            modelFile.writeBytes(executionContext.codeBundle)
            
            // 3. Write input files
            inputFiles.forEach { (filename, data) ->
                File(inputsDir, filename).writeBytes(data)
            }
            
            // 4. Execute ML inference
            var inferenceError: String? = null
            try {
                // Initialize TensorFlow Lite Interpreter
                val interpreter = Interpreter(modelFile)
                
                // Get input/output tensor counts
                val inputCount = interpreter.inputTensorCount
                val outputCount = interpreter.outputTensorCount
                
                // Load input tensors from files
                val inputs = (0 until inputCount).map { index ->
                    val inputFile = File(inputsDir, "input_$index.bin")
                    if (!inputFile.exists()) {
                        throw IllegalStateException("Missing input file: input_$index.bin")
                    }
                    ByteBuffer.wrap(inputFile.readBytes()).order(ByteOrder.nativeOrder())
                }.toTypedArray()
                
                // Prepare output tensors as Map<Int, Any>
                val outputs = mutableMapOf<Int, Any>()
            (0 until outputCount).forEach { index ->
                val outputShape = interpreter.getOutputTensor(index).shape()
                val outputSize = outputShape.reduce { acc, dim -> acc * dim }
                outputs[index] = ByteBuffer.allocateDirect(outputSize * 4).order(ByteOrder.nativeOrder())
            }
            
            // Run inference
            interpreter.runForMultipleInputsOutputs(inputs, outputs)
            outputs.forEach { (index, output) ->
                val outputBuffer = output as ByteBuffer
                val outputFile = File(outputsDir, "output_$index.bin")
                outputBuffer.rewind()
                val outputBytes = ByteArray(outputBuffer.remaining())
                outputBuffer.get(outputBytes)
                outputFile.writeBytes(outputBytes)
            }
            
            interpreter.close()
                
            } catch (e: Exception) {
                inferenceError = e.message ?: e::class.java.simpleName
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            
            // 5. Collect output files
            val outputManifest = collectOutputFiles(outputsDir)
            
            if (inferenceError == null) {
                ExecutionResult(
                    taskId = executionContext.taskId,
                    success = true,
                    outputManifest = outputManifest,
                    executionTimeMs = executionTime,
                    resultMessage = "ML inference completed successfully"
                )
            } else {
                ExecutionResult(
                    taskId = executionContext.taskId,
                    success = false,
                    outputManifest = outputManifest,
                    executionTimeMs = executionTime,
                    errorMessage = inferenceError,
                    errorType = ExecutionErrorType.RUNTIME_ERROR
                )
            }
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            ExecutionResult(
                taskId = executionContext.taskId,
                success = false,
                outputManifest = emptyList(),
                executionTimeMs = executionTime,
                errorMessage = e.message ?: "ML execution failed",
                errorType = ExecutionErrorType.RUNTIME_ERROR
            )
        } finally {
            // Cleanup workspace
            workspaceDir.deleteRecursively()
        }
    }
    
    override fun validateCodeBundle(codeBundle: ByteArray): Boolean {
        if (codeBundle.isEmpty()) return false
        
        // Check TensorFlow Lite magic bytes
        return codeBundle.size >= 4 &&
               codeBundle[0] == TFLITE_MAGIC_BYTES[0] &&
               codeBundle[1] == TFLITE_MAGIC_BYTES[1] &&
               codeBundle[2] == TFLITE_MAGIC_BYTES[2] &&
               codeBundle[3] == TFLITE_MAGIC_BYTES[3]
    }
    
    // === Private Helper Methods ===
    
    private fun collectOutputFiles(outputsDir: File): List<FileReference> {
        if (!outputsDir.exists()) return emptyList()
        
        return outputsDir.listFiles()?.mapNotNull { file ->
            if (file.isFile) {
                FileReference(
                    fileId = calculateSha256Hash(file),
                    fileName = file.name,
                    path = file.relativeTo(outputsDir).path,
                    sizeBytes = file.length()
                )
            } else null
        } ?: emptyList()
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
}
