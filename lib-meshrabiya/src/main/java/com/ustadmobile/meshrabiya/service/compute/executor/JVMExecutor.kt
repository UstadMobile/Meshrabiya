package com.ustadmobile.meshrabiya.service.compute.executor

import com.ustadmobile.meshrabiya.service.compute.model.TaskExecutionContext
import com.ustadmobile.meshrabiya.service.compute.model.ExecutionResult
import com.ustadmobile.meshrabiya.service.compute.model.ExecutionErrorType
import com.ustadmobile.meshrabiya.storage.FileReference
import com.ustadmobile.meshrabiya.service.compute.security.RestrictedJVMPolicy
import java.io.File
import java.net.URLClassLoader
import java.security.Policy
import java.util.zip.ZipInputStream
import java.io.ByteArrayInputStream

/**
 * JVMExecutor
 * 
 * Phase 2: Task Execution Layer - JVM bytecode execution implementation
 * 
 * Executes JVM bytecode (.class/.jar files) in isolated URLClassLoader.
 * Supports both single-class executables and JAR archives.
 * 
 * Code Bundle Formats:
 * 1. Single .class file: Direct class file execution
 * 2. JAR archive: Multi-class Java/Kotlin projects with META-INF/MANIFEST.MF
 * 
 * Security:
 * - Isolated URLClassLoader with no parent class loader access
 * - Sandboxed file I/O (inputs/outputs directories only)
 * - No network access
 * - No access to Android system classes
 * 
 * Entry Point:
 * - Looks for class with `public static void main(String[] args)` method
 * - For JAR: uses Main-Class from MANIFEST.MF
 * - For .class: uses class name from file
 */
class JVMExecutor : TaskExecutor {
    
    companion object {
        private val JAR_MAGIC_BYTES = byteArrayOf(0x50, 0x4B) // "PK" (ZIP header)
        private val CLASS_MAGIC_BYTES = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()) // 0xCAFEBABE
    }
    
    override suspend fun execute(
        executionContext: TaskExecutionContext,
        inputFiles: Map<String, ByteArray>,
        containerId: String
    ): ExecutionResult {
        val startTime = System.currentTimeMillis()
        val workspaceDir = File("/tmp/jvm_workspace_$containerId")
        
        return try {
            // 1. Setup workspace
            workspaceDir.mkdirs()
            val inputsDir = File(workspaceDir, "inputs")
            val outputsDir = File(workspaceDir, "outputs")
            val classesDir = File(workspaceDir, "classes")
            inputsDir.mkdirs()
            outputsDir.mkdirs()
            classesDir.mkdirs()
            
            // 2. Extract code bundle
            val isJar = isJarArchive(executionContext.codeBundle)
            if (isJar) {
                extractJar(executionContext.codeBundle, classesDir)
            } else {
                // Single .class file
                File(classesDir, "Main.class").writeBytes(executionContext.codeBundle)
            }
            
            // 3. Write input files
            inputFiles.forEach { (filename, data) ->
                File(inputsDir, filename).writeBytes(data)
            }
            
            // 4. Execute JVM bytecode with SecurityManager protection
            var executionError: String? = null
            var securityViolation: SecurityException? = null
            
            // Save current security state
            val originalSecurityManager = System.getSecurityManager()
            val originalPolicy = Policy.getPolicy()
            
            try {
                // Install thread-local security policy
                RestrictedJVMPolicy.setWorkspaceForCurrentThread(workspaceDir)
                Policy.setPolicy(RestrictedJVMPolicy)
                System.setSecurityManager(SecurityManager())
                
                // Create isolated URLClassLoader
                val classLoader = URLClassLoader(
                    arrayOf(classesDir.toURI().toURL()),
                    null // No parent classloader = isolated
                )
                
                // Find main class
                val mainClassName = if (isJar) {
                    findMainClassFromManifest(classesDir) ?: "Main"
                } else {
                    "Main"
                }
                
                // Load and execute
                val mainClass = classLoader.loadClass(mainClassName)
                val mainMethod = mainClass.getMethod("main", Array<String>::class.java)
                
                try {
                    mainMethod.invoke(null, arrayOf<String>())
                } catch (e: SecurityException) {
                    // Capture security violations separately
                    securityViolation = e
                    executionError = "Security violation: ${e.message}"
                }
                
            } catch (e: Exception) {
                if (e !is SecurityException) {
                    executionError = e.message ?: e::class.java.simpleName
                }
            } finally {
                // Restore original security state
                System.setSecurityManager(originalSecurityManager)
                Policy.setPolicy(originalPolicy)
                RestrictedJVMPolicy.clearWorkspaceForCurrentThread()
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            
            // 5. Collect output files
            val outputManifest = collectOutputFiles(outputsDir)
            
            // Determine error type for security violations
            val errorType = if (securityViolation != null) {
                ExecutionErrorType.SECURITY_VIOLATION
            } else {
                ExecutionErrorType.RUNTIME_ERROR
            }
            
            if (executionError == null) {
                ExecutionResult(
                    taskId = executionContext.taskId,
                    success = true,
                    outputManifest = outputManifest,
                    executionTimeMs = executionTime,
                    resultMessage = "JVM execution completed successfully"
                )
            } else {
                ExecutionResult(
                    taskId = executionContext.taskId,
                    success = false,
                    outputManifest = outputManifest,
                    executionTimeMs = executionTime,
                    errorMessage = executionError,
                    errorType = errorType
                )
            }
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            ExecutionResult(
                taskId = executionContext.taskId,
                success = false,
                outputManifest = emptyList(),
                executionTimeMs = executionTime,
                errorMessage = e.message ?: "JVM execution failed",
                errorType = ExecutionErrorType.RUNTIME_ERROR
            )
        } finally {
            // Cleanup workspace
            workspaceDir.deleteRecursively()
        }
    }
    
    override fun validateCodeBundle(codeBundle: ByteArray): Boolean {
        if (codeBundle.isEmpty()) return false
        
        // Check if it's a valid JAR or .class file
        if (isJarArchive(codeBundle)) {
            return true // Basic JAR validation
        } else if (isClassFile(codeBundle)) {
            return true // Basic .class validation
        }
        
        return false
    }
    
    // === Private Helper Methods ===
    
    private fun isJarArchive(bytes: ByteArray): Boolean {
        return bytes.size >= 2 && 
               bytes[0] == JAR_MAGIC_BYTES[0] && 
               bytes[1] == JAR_MAGIC_BYTES[1]
    }
    
    private fun isClassFile(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
               bytes[0] == CLASS_MAGIC_BYTES[0] &&
               bytes[1] == CLASS_MAGIC_BYTES[1] &&
               bytes[2] == CLASS_MAGIC_BYTES[2] &&
               bytes[3] == CLASS_MAGIC_BYTES[3]
    }
    
    private fun extractJar(jarBytes: ByteArray, destDir: File) {
        ZipInputStream(ByteArrayInputStream(jarBytes)).use { zis ->
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
                }
                
                entry = zis.nextEntry
            }
        }
    }
    
    private fun findMainClassFromManifest(classesDir: File): String? {
        val manifestFile = File(classesDir, "META-INF/MANIFEST.MF")
        if (!manifestFile.exists()) return null
        
        manifestFile.readLines().forEach { line ->
            if (line.startsWith("Main-Class:")) {
                return line.substringAfter("Main-Class:").trim()
            }
        }
        
        return null
    }
    
    private fun collectOutputFiles(outputsDir: File): List<FileReference> {
        if (!outputsDir.exists()) return emptyList()
        
        return outputsDir.listFiles()?.mapNotNull { file ->
            if (file.isFile) {
                FileReference(
                    fileId = calculateSha256Hash(file),
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
