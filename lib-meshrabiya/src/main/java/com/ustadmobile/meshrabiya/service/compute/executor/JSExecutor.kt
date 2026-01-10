package com.ustadmobile.meshrabiya.service.compute.executor

import com.ustadmobile.meshrabiya.service.compute.model.TaskExecutionContext
import com.ustadmobile.meshrabiya.service.compute.model.ExecutionResult
import com.ustadmobile.meshrabiya.service.compute.model.ExecutionErrorType
import com.ustadmobile.meshrabiya.storage.FileReference
import java.io.File
import java.util.zip.ZipInputStream
import java.io.ByteArrayInputStream

/**
 * JSExecutor
 * 
 * Phase 2: Task Execution Layer - JavaScript execution implementation
 * 
 * NOTE: J2V8 JavaScript execution is currently unavailable.
 * This executor validates code bundles but returns NOT_SUPPORTED errors until J2V8 dependency is added.
 * 
 * To enable JavaScript execution:
 * 1. Add J2V8 dependency to build.gradle: implementation 'com.eclipsesource.j2v8:j2v8:...'
 * 2. Uncomment V8 execution logic below
 * 3. Update TaskExecutionCoordinator to enable this executor
 * 
 * Code Bundle Formats:
 * 1. Single .js file: Direct JavaScript script execution
 * 2. ZIP archive with main.js entry point: Multi-file JavaScript projects
 */
class JSExecutor : TaskExecutor {
    
    companion object {
        private val ZIP_MAGIC_BYTES = byteArrayOf(0x50, 0x4B) // "PK"
        private const val MAIN_JS = "main.js"
    }
    
    override suspend fun execute(
        executionContext: TaskExecutionContext,
        inputFiles: Map<String, ByteArray>,
        containerId: String
    ): ExecutionResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            // J2V8 is not available - return NOT_SUPPORTED
            ExecutionResult(
                taskId = executionContext.taskId,
                success = false,
                outputManifest = emptyList(),
                executionTimeMs = System.currentTimeMillis() - startTime,
                errorMessage = "JavaScript execution not supported: J2V8 dependency not available. Add 'com.eclipsesource.j2v8:j2v8' to build.gradle to enable.",
                errorType = ExecutionErrorType.INVALID_CODE_BUNDLE
            )

            /*
            // FUTURE: When J2V8 is available, uncomment this implementation:
            
            val workspaceDir = File("/tmp/js_workspace_$containerId")
            
            try {
                // 1. Setup workspace
                workspaceDir.mkdirs()
                val inputsDir = File(workspaceDir, "inputs")
                val outputsDir = File(workspaceDir, "outputs")
                inputsDir.mkdirs()
                outputsDir.mkdirs()
                
                // 2. Extract code bundle
                val isZip = isZipArchive(executionContext.codeBundle)
                val scriptFile = if (isZip) {
                    extractCodeBundle(executionContext.codeBundle, workspaceDir)
                } else {
                    File(workspaceDir, "script.js").apply {
                        writeBytes(executionContext.codeBundle)
                    }
                }
                
                // 3. Write input files
                inputFiles.forEach { (filename, data) ->
                    File(inputsDir, filename).writeBytes(data)
                }
                
                // 4. Execute JavaScript using J2V8
                var jsError: String? = null
                try {
                    val v8 = V8.createV8Runtime(null, workspaceDir.absolutePath)
                    val script = scriptFile.readText()
                    v8.executeVoidScript(script)
                    v8.release()
                } catch (e: Exception) {
                    jsError = e.message
                }
                
                val executionTime = System.currentTimeMillis() - startTime
                
                // 5. Collect output files
                val outputManifest = collectOutputFiles(outputsDir)
                
                if (jsError == null) {
                    ExecutionResult(
                        taskId = executionContext.taskId,
                        success = true,
                        outputManifest = outputManifest,
                        executionTimeMs = executionTime,
                        resultMessage = "JavaScript execution completed successfully"
                    )
                } else {
                    ExecutionResult(
                        taskId = executionContext.taskId,
                        success = false,
                        outputManifest = outputManifest,
                        executionTimeMs = executionTime,
                        errorMessage = jsError,
                        errorType = ExecutionErrorType.RUNTIME_ERROR
                    )
                }
                
            } finally {
                // Cleanup workspace
                workspaceDir.deleteRecursively()
            }
            */
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            ExecutionResult(
                taskId = executionContext.taskId,
                success = false,
                outputManifest = emptyList(),
                executionTimeMs = executionTime,
                errorMessage = e.message ?: "JavaScript execution failed",
                errorType = ExecutionErrorType.RUNTIME_ERROR
            )
        }
    }
    
    override fun validateCodeBundle(codeBundle: ByteArray): Boolean {
        if (codeBundle.isEmpty()) return false
        
        // Check if it's a valid ZIP or contains JavaScript syntax
        if (isZipArchive(codeBundle)) {
            return hasMainJs(codeBundle)
        } else {
            // Check for JavaScript-like syntax (basic heuristic)
            val content = String(codeBundle)
            return content.contains("function ") || content.contains("const ") || 
                   content.contains("var ") || content.contains("let ") ||
                   content.contains("console.log") || content.trim().isNotEmpty()
        }
    }
    
    // === Private Helper Methods ===
    
    private fun isZipArchive(bytes: ByteArray): Boolean {
        return bytes.size >= 2 && 
               bytes[0] == ZIP_MAGIC_BYTES[0] && 
               bytes[1] == ZIP_MAGIC_BYTES[1]
    }
    
    private fun hasMainJs(zipBytes: ByteArray): Boolean {
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == MAIN_JS || entry.name.endsWith("/$MAIN_JS")) {
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
        var mainJsFile: File? = null
        
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
                    
                    if (file.name == MAIN_JS) {
                        mainJsFile = file
                    }
                }
                
                entry = zis.nextEntry
            }
        }
        
        return mainJsFile ?: throw IllegalStateException("No main.js found in ZIP archive")
    }
    
    private fun collectOutputFiles(outputsDir: File): List<FileReference> {
        if (!outputsDir.exists()) return emptyList()
        
        return outputsDir.listFiles()?.mapNotNull { file ->
            if (file.isFile) {
                FileReference(
                    fileId = calculateSha256Hash(file),
                    // TODO  verify the path should not be truncated and just use a filename in the process folder
                    path = file.relativeTo(outputsDir).path,
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
