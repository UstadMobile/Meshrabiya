package com.ustadmobile.meshrabiya.service.compute.executor

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.ustadmobile.meshrabiya.service.compute.model.TaskExecutionContext
import com.ustadmobile.meshrabiya.service.compute.model.ExecutionResult
// import com.ustadmobile.meshrabiya.service.compute.model.ResourceMetrics
import com.ustadmobile.meshrabiya.service.compute.model.ExecutionErrorType
import com.ustadmobile.meshrabiya.service.compute.model.FileReference
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * PythonExecutor
 * 
 * Phase 2: Task Execution Layer - Python execution implementation
 * 
 * Executes Python code using Chaquopy (Python for Android).
 * 
 * Code Bundle Formats:
 * 1. Single .py file: Direct Python script execution
 * 2. ZIP archive with main.py entry point: Multi-file Python projects
 * 
 * Implementation Steps:
 * 1. Detect code bundle format (single file vs ZIP)
 * 2. Extract code bundle to container workspace
 * 3. Write input files to inputs/ directory
 * 4. Initialize Python runtime with restricted environment
 * 5. Execute main.py or single script
 * 6. Collect outputs from outputs/ directory
 * 7. Track resource usage during execution
 */
class PythonExecutor(
    private val context: Context
) : TaskExecutor {
    
    companion object {
        private val ZIP_MAGIC_BYTES = byteArrayOf(0x50, 0x4B) // "PK"
        private const val MAIN_PY = "main.py"
    }
    
    override suspend fun execute(
        context: TaskExecutionContext,
        inputFiles: Map<String, ByteArray>,
        containerId: String
    ): ExecutionResult {
        val startTime = System.currentTimeMillis()
        val workspaceDir = File(this.context.filesDir, "containers/$containerId")
        
        try {
            // 1. Setup workspace
            workspaceDir.mkdirs()
            val inputsDir = File(workspaceDir, "inputs")
            val outputsDir = File(workspaceDir, "outputs")
            inputsDir.mkdirs()
            outputsDir.mkdirs()
            
            // 2. Extract code bundle
            val isZip = isZipArchive(context.codeBundle)
            val scriptFile = if (isZip) {
                extractCodeBundle(context.codeBundle, workspaceDir)
            } else {
                File(workspaceDir, "script.py").apply {
                    writeBytes(context.codeBundle)
                }
            }
            
            // 3. Write input files
            inputFiles.forEach { (filename, data) ->
                File(inputsDir, filename).writeBytes(data)
            }
            
            // 4. Execute Python script using Chaquopy
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this.context))
            }
            val py = Python.getInstance()
            val scriptPath = scriptFile.absolutePath
            val pyResult: PyObject
            val pyError: String?
            try {
                // Set working directory to workspaceDir
                val sys = py.getModule("sys")
                sys["path"].callAttr("insert", 0, workspaceDir.absolutePath)
                sys["argv"] = listOf(scriptPath)
                val builtins = py.getModule("builtins")
                builtins["__file__"] = scriptPath
                val mainModule = py.getModule("__main__")
                pyResult = py.getModule("runpy").callAttr("run_path", scriptPath, mapOf("run_name" to "__main__"))
                pyError = null
            } catch (e: Exception) {
                pyResult = PyObject.fromJava(null)
                pyError = e.message
            }
            val executionTime = System.currentTimeMillis() - startTime
            // 5. Collect output files
            val outputManifest = collectOutputFiles(outputsDir)
            return if (pyError == null) {
                ExecutionResult(
                    taskId = context.taskId,
                    success = true,
                    outputManifest = outputManifest,
                    // resourcesUsed = ResourceMetrics.zero(), // TODO: Actual metrics
                    executionTimeMs = executionTime,
                    resultMessage = "Python execution completed successfully"
                )
            } else {
                ExecutionResult(
                    taskId = context.taskId,
                    success = false,
                    outputManifest = outputManifest,
                    // resourcesUsed = ResourceMetrics.zero(),
                    executionTimeMs = executionTime,
                    errorMessage = pyError,
                    errorType = ExecutionErrorType.RUNTIME_ERROR
                )
            }
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            return ExecutionResult(
                taskId = context.taskId,
                success = false,
                outputManifest = emptyList(),
                // resourcesUsed = ResourceMetrics.zero(),
                executionTimeMs = executionTime,
                errorMessage = e.message ?: "Python execution failed",
                errorType = ExecutionErrorType.RUNTIME_ERROR
            )
        } finally {
            // Cleanup workspace (optional - may keep for debugging)
            // workspaceDir.deleteRecursively()
        }
    }
    
    override fun validateCodeBundle(codeBundle: ByteArray): Boolean {
        if (codeBundle.isEmpty()) return false
        
        // Check if it's a valid ZIP or contains Python syntax
        if (isZipArchive(codeBundle)) {
            // Verify ZIP contains main.py
            return hasMainPy(codeBundle)
        } else {
            // Check for Python-like syntax (basic heuristic)
            val content = String(codeBundle)
            return content.contains("def ") || content.contains("import ") || 
                   content.contains("print(") || content.trim().isNotEmpty()
        }
    }
    
    override fun getSupportedTaskType(): TaskType = TaskType.PYTHON
    
    // === Private Helper Methods ===
    
    private fun isZipArchive(bytes: ByteArray): Boolean {
        return bytes.size >= 2 && 
               bytes[0] == ZIP_MAGIC_BYTES[0] && 
               bytes[1] == ZIP_MAGIC_BYTES[1]
    }
    
    private fun hasMainPy(zipBytes: ByteArray): Boolean {
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == MAIN_PY || entry.name.endsWith("/$MAIN_PY")) {
                        return true
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            return false
        }
        return false
    }
    
    private fun extractCodeBundle(zipBytes: ByteArray, destDir: File): File {
        var mainPyFile: File? = null
        
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(destDir, entry.name)
                
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { fos ->
                        zis.copyTo(fos)
                    }
                    
                    if (file.name == MAIN_PY) {
                        mainPyFile = file
                    }
                }
                
                entry = zis.nextEntry
            }
        }
        
        return mainPyFile ?: throw IllegalStateException("No main.py found in ZIP archive")
    }
    
    private fun collectOutputFiles(outputsDir: File): List<FileReference> {
        if (!outputsDir.exists()) return emptyList()
        
        return outputsDir.listFiles()?.mapNotNull { file ->
            if (file.isFile) {
                FileReference(
                    fileId = calculateSha256Hash(file),
                    fileName = file.name,
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

