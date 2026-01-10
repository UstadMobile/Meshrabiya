package com.ustadmobile.meshrabiya.service.compute.runtime

import android.content.Context
import android.content.SharedPreferences
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File

/**
 * RuntimeRegistry
 * 
 * Phase 3: Runtime Management Layer - Runtime tracking and detection
 * 
 * Responsibilities:
 * 1. Track available runtimes (built-in + user-installed)
 * 2. Persist runtime information to SharedPreferences
 * 3. Provide runtime detection APIs
 * 4. Manage runtime lifecycle (install/uninstall)
 * 
 * Built-in Runtimes:
 * - JVM: Always available (Android Dalvik VM)
 * - Chaquopy: Detected via Class.forName
 * 
 * User-Installed Runtimes:
 * - J2V8: JavaScript engine
 * - TensorFlow Lite: ML inference
 * - Additional Python packages via Chaquopy
 */
class RuntimeRegistry private constructor(
    private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "runtime_registry"
        private const val KEY_RUNTIMES = "runtimes"
        
        @Volatile
        private var INSTANCE: RuntimeRegistry? = null
        
        fun getInstance(context: Context): RuntimeRegistry {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RuntimeRegistry(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
    
    @Serializable
    data class RuntimeInfo(
        val taskType: String, // TaskType.name
        val version: String,
        val isBuiltIn: Boolean,
        val installedAt: Long,
        val installPath: String? = null, // For user-installed runtimes
        val metadata: Map<String, String> = emptyMap() // Additional runtime-specific info
    )
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val runtimes = mutableMapOf<String, RuntimeInfo>()
    private val json = Json { ignoreUnknownKeys = true }
    
    init {
        loadFromPrefs()
        registerBuiltInRuntimes()
    }
    
    /**
     * Initialize registry - load persisted runtimes and register built-ins
     */
    fun initialize() {
        loadFromPrefs()
        registerBuiltInRuntimes()
    }
    
    /**
     * Register built-in runtimes that are always or conditionally available
     */
    private fun registerBuiltInRuntimes() {
        // JVM is always available on Android
        registerRuntime(RuntimeInfo(
            taskType = TaskType.JVM.name,
            version = System.getProperty("java.version") ?: "unknown",
            isBuiltIn = true,
            installedAt = System.currentTimeMillis(),
            metadata = mapOf(
                "vendor" to (System.getProperty("java.vendor") ?: "unknown"),
                "vm" to (System.getProperty("java.vm.name") ?: "unknown")
            )
        ))
        
        // Detect Chaquopy (Python runtime)
        if (isPythonAvailableInternal()) {
            val pythonVersion = getPythonVersionInternal()
            registerRuntime(RuntimeInfo(
                taskType = TaskType.PYTHON.name,
                version = pythonVersion,
                isBuiltIn = true,
                installedAt = System.currentTimeMillis(),
                metadata = mapOf("runtime" to "Chaquopy")
            ))
        }
    }
    
    /**
     * Check if Python runtime (Chaquopy) is available
     */
    fun isPythonAvailable(): Boolean {
        return runtimes.containsKey(TaskType.PYTHON.name)
    }
    
    /**
     * Get Python version if available
     */
    fun getPythonVersion(): String? {
        return runtimes[TaskType.PYTHON.name]?.version
    }
    
    /**
     * Check if a specific runtime is available
     */
    fun isRuntimeAvailable(taskType: TaskType): Boolean {
        return runtimes.containsKey(taskType.name)
    }
    
    /**
     * Get runtime information for a specific task type
     */
    fun getRuntimeInfo(taskType: TaskType): RuntimeInfo? {
        return runtimes[taskType.name]
    }
    
    /**
     * Get all available runtimes
     */
    fun getAvailableRuntimes(): List<RuntimeInfo> {
        return runtimes.values.toList()
    }
    
    /**
     * Register a new runtime (user-installed or built-in)
     */
    fun registerRuntime(info: RuntimeInfo) {
        runtimes[info.taskType] = info
        saveToPrefs()
    }
    
    /**
     * Uninstall a user-installed runtime
     * Built-in runtimes cannot be uninstalled
     */
    fun uninstallRuntime(taskType: TaskType): Boolean {
        val info = runtimes[taskType.name] ?: return false
        
        if (info.isBuiltIn) {
            return false // Cannot uninstall built-in runtimes
        }
        
        // Delete installation directory
        info.installPath?.let { path ->
            val installDir = File(path)
            if (installDir.exists()) {
                installDir.deleteRecursively()
            }
        }
        
        runtimes.remove(taskType.name)
        saveToPrefs()
        return true
    }
    
    /**
     * Get installation path for user-installed runtimes
     */
    fun getRuntimePath(taskType: TaskType): String? {
        return runtimes[taskType.name]?.installPath
    }
    
    // === Private Helper Methods ===
    
    private fun isPythonAvailableInternal(): Boolean {
        return try {
            Class.forName("com.chaquo.python.Python")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
    
    private fun getPythonVersionInternal(): String {
        return try {
            val pythonClass = Class.forName("com.chaquo.python.Python")
            val method = pythonClass.getMethod("getPlatform")
            val platform = method.invoke(null)
            val versionMethod = platform.javaClass.getMethod("getVersion")
            versionMethod.invoke(platform) as? String ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    private fun loadFromPrefs() {
        val json = prefs.getString(KEY_RUNTIMES, null) ?: return
        try {
            val list: List<RuntimeInfo> = this.json.decodeFromString(json)
            runtimes.clear()
            list.forEach { info ->
                runtimes[info.taskType] = info
            }
        } catch (e: Exception) {
            // Failed to load, start fresh
            runtimes.clear()
        }
    }
    
    private fun saveToPrefs() {
        val json = this.json.encodeToString(runtimes.values.toList())
        prefs.edit().putString(KEY_RUNTIMES, json).apply()
    }
}
