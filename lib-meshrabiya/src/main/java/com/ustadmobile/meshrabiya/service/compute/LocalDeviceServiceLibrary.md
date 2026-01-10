package com.ustadmobile.meshrabiya.service.compute

import android.content.Context
import android.content.SharedPreferences
import com.ustadmobile.meshrabiya.service.compute.ResourceRequirements
// import com.ustadmobile.meshrabiya.service.compute.model.ResourceMetrics
import com.ustadmobile.meshrabiya.service.compute.model.JobType
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
import com.ustadmobile.meshrabiya.service.compute.DistributedServiceLibrary.ServiceCategory
// import com.ustadmobile.meshrabiya.service.compute.model.ServiceEntry
import com.ustadmobile.meshrabiya.service.compute.runtime.RuntimeRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.min
import kotlin.jvm.Volatile
import com.ustadmobile.meshrabiya.service.compute.model.ServiceManifest
import com.ustadmobile.meshrabiya.service.compute.DistributedServiceLibrary.ServiceLibraryEntry


/**
 * LocalDeviceServiceLibrary
 *
 * Implements manifest-driven runtime selection, device profiling, and modular runtime management for distributed compute services.
 * Default: JVM (Kotlin/Java) and native (C/C++ via NDK) always available.
 * Optional: Python, Node.js, Rust, Go, WASM supported as modular downloads for capable devices.
 *
 * Phase 3.2: Enhanced with built-in compute service generation, persistence layer, and runtime integration.
 * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md Section 7
 *
 * Usage: Validate service manifest, select runtimes, manage user opt-in for advanced runtimes, and discover compute services.
 */
object LocalDeviceServiceLibrary {
    enum class DeviceProfile { FLAGSHIP, MID_RANGE, BUDGET }
    enum class Runtime { JVM, NATIVE, PYTHON, NODEJS, RUST, GO, WASM }

   

    private const val TAG = "LocalDeviceServiceLibrary"
    private const val PREFS_NAME = "local_device_service_library"
    private const val KEY_SERVICE_LIBRARY = "service_library"
    
    @Volatile
    private var instance: LocalDeviceServiceLibrary? = null
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var runtimeRegistry: RuntimeRegistry
    
    /**
     * In-memory service library storage.
     */
    private val serviceLibrary = mutableListOf<ServiceManifest>()
    
    /**
     * In-memory compute service entries (Phase 3.2)
     */
    private val computeServiceEntries = mutableListOf<ServiceLibraryEntry>()
    
    /**
     * Initialize the LocalDeviceServiceLibrary singleton.
     */
    fun getInstance(context: Context, runtimeRegistry: RuntimeRegistry): LocalDeviceServiceLibrary {
        if (!::context.isInitialized) {
            this.context = context.applicationContext
            this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            this.runtimeRegistry = runtimeRegistry
            loadServices()
        }
        return this
    }

    /**
     * Add a service manifest to the library.
     */
    fun addService(manifest: ServiceManifest) {
        serviceLibrary.add(manifest)
    }

    /**
     * Retrieve a service manifest by packageId.
     */
    fun getServiceByPackageId(packageId: String): ServiceManifest? =
        serviceLibrary.find { it.packageId == packageId }

    /**
     * Query services by serviceType.
     */
    fun findServicesByType(serviceType: String): List<ServiceManifest> =
        serviceLibrary.filter { it.serviceType.equals(serviceType, ignoreCase = true) }

    /**
     * Query services by required runtime.
     */
    fun findServicesByRuntime(runtime: Runtime): List<ServiceManifest> =
        serviceLibrary.filter { it.runtimeRequired.contains(runtime) || it.runtimeOptional.contains(runtime) }

    /**
     * Returns the list of available runtimes for the current device profile.
     */
    fun getAvailableRuntimes(profile: DeviceProfile): List<Runtime> = when (profile) {
        DeviceProfile.FLAGSHIP -> Runtime.values().toList()
        DeviceProfile.MID_RANGE -> listOf(Runtime.JVM, Runtime.NATIVE, Runtime.PYTHON, Runtime.NODEJS)
        DeviceProfile.BUDGET -> listOf(Runtime.JVM, Runtime.NATIVE)
    }

    /**
     * Validates if the service can be installed on the current device profile.
     * Warns or rejects if required runtimes are not available.
     */
    fun validateService(manifest: ServiceManifest, profile: DeviceProfile): ValidationResult {
        val available = getAvailableRuntimes(profile)
        val missing = manifest.runtimeRequired.filter { it !in available }
        return when {
            missing.isEmpty() -> ValidationResult.Valid
            else -> ValidationResult.Rejected("Missing required runtimes: $missing for profile $profile")
        }
    }

    /**
     * Allows user to opt-in to install extra runtimes for advanced services.
     */
    fun userOptInRuntimes(profile: DeviceProfile, optIn: List<Runtime>): List<Runtime> {
        val available = getAvailableRuntimes(profile)
        return available + optIn.filter { it !in available }
    }

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Rejected(val reason: String) : ValidationResult()
    }
    
    // ========================================
    // Phase 3.2: Built-In Compute Services
    // ========================================
    
    /**
     * GET BUILT-IN COMPUTE SERVICES
     * 
     * Generate default compute services for each available runtime's task type.
     * Cross-product: taskType × jobType = service entries
     * 
     * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md Section 7.2
     */
    fun getBuiltInComputeServices(): List<ServiceLibraryEntry> {
        val services = mutableListOf<ServiceLibraryEntry>()
        
        // Get available runtimes from RuntimeRegistry
        val availableRuntimes = runtimeRegistry.getAvailableRuntimes()
        
        availableRuntimes.forEach { runtimeInfo ->
            val taskType = runtimeInfo.taskType
            
            // Get supported job types for this task type
            val supportedJobs = getJobTypesForTaskType(taskType)
            
            supportedJobs.forEach { jobType ->
                services.add(
                    ServiceLibraryEntry(
                        serviceName = "${taskType.name}_${jobType.name}_COMPUTE",
                        host = "localhost",
                        port = 0, // No port for compute services
                        category = ServiceCategory.COMPUTE,
                        supportsCompute = true,
                        taskTypes = listOf(taskType),
                        jobTypes = listOf(jobType),
                        maxConcurrentTasks = getMaxConcurrentTasks(),
                        estimatedCapacity = estimateNodeCapacity()
                    )
                )
            }
        }
        
        return services
    }
    
    /**
     * GET JOB TYPES FOR TASK TYPE
     * 
     * Map task types to compatible job types based on runtime capabilities.
     * 
     * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md Section 7.2
     */
    private fun getJobTypesForTaskType(taskType: TaskType): List<JobType> {
        return when (taskType) {
            TaskType.PYTHON -> listOf(
                JobType.IMAGE_PROCESSING,
                JobType.DATA_ANALYSIS,
                JobType.ML_PIPELINE,
                JobType.SENSOR_FUSION,
                JobType.COLLABORATIVE_FILTERING
            )
            
            TaskType.JVM, TaskType.JAVA -> listOf(
                JobType.DATA_ANALYSIS,
                JobType.COLLABORATIVE_FILTERING,
                // JobType.DISTRIBUTED_STORAGE
            )
            
            TaskType.JAVASCRIPT -> listOf(
                JobType.DATA_ANALYSIS,
                JobType.COLLABORATIVE_FILTERING
            )
            
            TaskType.ML_NATIVE -> listOf(
                JobType.IMAGE_PROCESSING,
                JobType.ML_PIPELINE,
                JobType.SENSOR_FUSION
            )
            
            TaskType.WORKFLOW -> listOf(
                JobType.ML_PIPELINE,
                JobType.COLLABORATIVE_FILTERING,
                // JobType.DISTRIBUTED_STORAGE
            )
        }
    }
    
    /**
     * GET MAX CONCURRENT TASKS
     * 
     * Calculate maximum concurrent tasks based on device CPU cores.
     * Limit to 4 tasks max to prevent resource exhaustion.
     * 
     * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md Section 7.2
     */
    private fun getMaxConcurrentTasks(): Int {
        val runtime = java.lang.Runtime.getRuntime()
        val availableProcessors = runtime.availableProcessors()
        
        // Allow 1 task per CPU core, up to 4 tasks max
        return min(availableProcessors, 4)
    }
    
    /**
     * ESTIMATE NODE CAPACITY
     * 
     * Calculate total available resources for compute (RAM, disk space).
     * 
     * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md Section 7.2
     */
    // private fun estimateNodeCapacity(): ResourceMetrics {
    //     val runtime = java.lang.Runtime.getRuntime()
    //     val dataDir = File("/data")
        
    //     return ResourceMetrics(
    //         ramActualBytes = 0,
    //         ramAverageBytes = 0,
    //         ramPeakBytes = runtime.maxMemory(),
    //         cpuTimeUsedMs = 0,
    //         cpuPercentage = 0f,
    //         diskIoOperations = 0,
    //         diskStorageUsedBytes = if (dataDir.exists()) dataDir.freeSpace else 0L
    //     )
    // }
    
    // ========================================
    // Phase 3.2: Persistence Layer
    // ========================================
    
    /**
     * SAVE SERVICES
     * 
     * Persist compute service entries to SharedPreferences as JSON.
     * 
     * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md Section 7.3
     */
    fun saveServices() {
        try {
            val json = Json.encodeToString(computeServiceEntries.toList())
            prefs.edit().putString(KEY_SERVICE_LIBRARY, json).apply()
            
            android.util.Log.d(TAG, "Saved ${computeServiceEntries.size} compute services")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to save services", e)
        }
    }
    
    /**
     * LOAD SERVICES
     * 
     * Restore compute service entries from SharedPreferences.
     * If none exist, generate built-in services.
     * 
     * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md Section 7.3
     */
    private fun loadServices() {
        if (!::prefs.isInitialized || !::runtimeRegistry.isInitialized) {
            return
        }
        
        val json = prefs.getString(KEY_SERVICE_LIBRARY, null)
        
        try {
            if (json != null) {
                val services = Json.decodeFromString<List<ServiceLibraryEntry>>(json)
                computeServiceEntries.clear()
                computeServiceEntries.addAll(services)
                
                android.util.Log.d(TAG, "Loaded ${services.size} compute services from storage")
            } else {
                // No saved services, generate built-in services
                refreshServices()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load services, regenerating built-in services", e)
            refreshServices()
        }
    }
    
    /**
     * REFRESH SERVICES
     * 
     * Rebuild compute service entries after runtime changes (install/uninstall).
     * 
     * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN_PART3.md Section 7.3
     */
    fun refreshServices() {
        if (!::runtimeRegistry.isInitialized) {
            android.util.Log.w(TAG, "Cannot refresh services: RuntimeRegistry not initialized")
            return
        }
        
        computeServiceEntries.clear()
        computeServiceEntries.addAll(getBuiltInComputeServices())
        saveServices()
        
        android.util.Log.d(TAG, "Refreshed ${computeServiceEntries.size} built-in compute services")
    }
    
    /**
     * GET COMPUTE SERVICES
     * 
     * Retrieve all available compute service entries.
     */
    fun getComputeServices(): List<ServiceLibraryEntry> {
        return computeServiceEntries.toList()
    }
    
    /**
     * FIND SERVICES BY TASK TYPE
     * 
     * Query compute services that support a specific task type.
     */
    fun findServicesByTaskType(taskType: TaskType): List<ServiceLibraryEntry> {
        return computeServiceEntries.filter { taskType in it.taskTypes }
    }
    
    /**
     * FIND SERVICES BY JOB TYPE
     * 
     * Query compute services that support a specific job type.
     */
    fun findServicesByJobType(jobType: JobType): List<ServiceLibraryEntry> {
        return computeServiceEntries.filter { jobType in it.jobTypes }
    }
}
