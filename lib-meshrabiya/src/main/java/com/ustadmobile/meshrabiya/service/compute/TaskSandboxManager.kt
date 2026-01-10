package com.ustadmobile.meshrabiya.service.compute

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * TaskSandboxManager
 * 
 * Manages filesystem isolation for task execution.
 * 
 * Features:
 * - Creates isolated sandbox directories per task
 * - Caches sandbox paths for performance
 * - Provides input/output file operations
 * - Handles sandbox cleanup
 */
class TaskSandboxManager(
    private val context: Context
) {
    private val pathsCache = ConcurrentHashMap<String, SandboxPaths>()
    
    companion object {
        private const val SANDBOX_BASE_DIR = "task_sandboxes"
    }
    
    /**
     * Create sandbox directories for task.
     * Returns cached paths if already created.
     */
    fun createSandbox(taskId: String): SandboxPaths {
        return pathsCache.getOrPut(taskId) {
            val baseDir = File(context.filesDir, "$SANDBOX_BASE_DIR/$taskId")
            val inputsDir = File(baseDir, "inputs")
            val outputsDir = File(baseDir, "outputs")
            
            baseDir.mkdirs()
            inputsDir.mkdirs()
            outputsDir.mkdirs()
            
            SandboxPaths(
                base = baseDir.absolutePath,
                inputs = inputsDir.absolutePath,
                outputs = outputsDir.absolutePath
            )
        }
    }
    
    /**
     * Get sandbox paths (cached or computed).
     */
    fun getSandboxPaths(taskId: String): SandboxPaths {
        return pathsCache.getOrPut(taskId) {
            val baseDir = File(context.filesDir, "$SANDBOX_BASE_DIR/$taskId")
            SandboxPaths(
                base = baseDir.absolutePath,
                inputs = File(baseDir, "inputs").absolutePath,
                outputs = File(baseDir, "outputs").absolutePath
            )
        }
    }
    
    /**
     * Write input file to sandbox inputs directory.
     */
    fun writeInputFile(taskId: String, fileName: String, data: ByteArray) {
        val paths = getSandboxPaths(taskId)
        val inputFile = File(paths.inputs, fileName)
        inputFile.writeBytes(data)
    }
    
    /**
     * Read all output files from sandbox outputs directory.
     */
    fun readOutputFiles(taskId: String): Map<String, ByteArray> {
        val paths = getSandboxPaths(taskId)
        val outputsDir = File(paths.outputs)
        
        if (!outputsDir.exists()) return emptyMap()
        
        val outputFiles = mutableMapOf<String, ByteArray>()
        outputsDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                outputFiles[file.name] = file.readBytes()
            }
        }
        
        return outputFiles
    }
    
    /**
     * Cleanup sandbox directory and remove from cache.
     */
    fun cleanupSandbox(taskId: String) {
        pathsCache.remove(taskId)
        
        val sandboxDir = File(context.filesDir, "$SANDBOX_BASE_DIR/$taskId")
        if (sandboxDir.exists()) {
            sandboxDir.deleteRecursively()
        }
    }
}

/**
 * Sandbox paths data class.
 */
data class SandboxPaths(
    val base: String,
    val inputs: String,
    val outputs: String
)
