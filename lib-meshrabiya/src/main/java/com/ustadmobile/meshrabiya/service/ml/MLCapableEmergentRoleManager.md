package com.ustadmobile.meshrabiya.service.ml

import android.content.Context
import android.util.Log
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.EmergentRoleManager
import com.ustadmobile.meshrabiya.vnet.MeshRoleManager
import com.ustadmobile.meshrabiya.mmcp.MeshRole
import com.ustadmobile.meshrabiya.model.DeviceCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap


// TODO Evalute if any of this functionality should be refactored into EmergentRoleManager

/**
 * Enhanced EmergentRoleManager with ML Server capabilities
 * Automatically assigns ML serving roles based on device capabilities and mesh topology
 */
class MLCapableEmergentRoleManager(
    private val meshNode: AndroidVirtualNode,
    private val context: Context,
    private val meshRoleManager: MeshRoleManager,
    private val emergentRoleManager: EmergentRoleManager
) {
    
    companion object {
        private const val TAG = "MLCapableEmergentRoleManager"
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    
    // Track ML capabilities across the mesh
    private val meshMLCapabilities = ConcurrentHashMap<Int, DeviceCapabilities>()
    
    // Current ML server assignments
    private val _mlServerRoles = MutableStateFlow<Map<String, MLServerAssignment>>(emptyMap())
    val mlServerRoles: StateFlow<Map<String, MLServerAssignment>> = _mlServerRoles
    
    data class MLServerAssignment(
        val serviceType: String, // "ml-kit-native", "ml-kit-custom", "litert"
        val primaryServerId: Int, // Node address
        val backupServerIds: List<Int> = emptyList(),
        val loadBalance: Boolean = true,
        val assignedAt: Long = System.currentTimeMillis()
    )
    
    /**
     * Update ML server role assignments based on current mesh topology
     */
    fun updateMLServerRoles() {
        scope.launch {
            try {
                val currentCapabilities = gatherMeshMLCapabilities()
                val newAssignments = calculateOptimalMLServerAssignments(currentCapabilities)
                
                _mlServerRoles.value = newAssignments
                
                // Announce role changes to mesh
                announceMLServerRoles(newAssignments)
                
                Log.i(TAG, "Updated ML server assignments: ${newAssignments.size} services assigned")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update ML server roles", e)
            }
        }
    }
    
    /**
     * Add new ML server role to existing EmergentRoleManager roles
     */
    fun getCurrentEnhancedMeshRoles(): Set<MeshRole> {
    val baseRoles: Set<MeshRole> = emergentRoleManager.getCurrentMeshRoles()
        val mlRoles = mutableSetOf<MeshRole>()

        _mlServerRoles.value.values.forEach { assignment ->
            if (assignment.primaryServerId == meshNode.getNodeId()) {
                mlRoles.add(MeshRole.ML_SERVER)
            }
        }

        return baseRoles.union(mlRoles)
    }
    
    /**
     * Determine if this device should serve a specific ML service type
     */
    fun shouldServeMLService(serviceType: String): Boolean {
        val assignment = _mlServerRoles.value[serviceType]
        val nodeId = meshNode.getNodeId()
        return assignment?.let {
            it.primaryServerId == nodeId || nodeId in it.backupServerIds
        } ?: false
    }
    
    /**
     * Get best ML server for a service type
     */
    fun getBestMLServer(serviceType: String): Int? {
        val assignment = _mlServerRoles.value[serviceType]
        return assignment?.primaryServerId
    }
    
    private fun gatherMeshMLCapabilities(): Map<Int, DeviceCapabilities> {
        // In real implementation, this would collect capabilities from mesh discovery
        val capabilities = mutableMapOf<Int, DeviceCapabilities>()
        
        // Add local device capabilities
    val localNodeId = meshNode.getNodeId()
        capabilities[localNodeId] = detectLocalMLCapabilities()
        
        // Add known mesh node capabilities (from service announcements)
        meshMLCapabilities.forEach { (nodeId, caps) ->
            capabilities[nodeId] = caps
        }
        
        return capabilities
    }
    
    private fun calculateOptimalMLServerAssignments(
        capabilities: Map<Int, DeviceCapabilities>
    ): Map<String, MLServerAssignment> {
        
        val assignments = mutableMapOf<String, MLServerAssignment>()
        
        // ML Kit Native Services - prefer devices with good ML Kit performance
        val mlKitCapableNodes = capabilities.toList().filter { (_, caps) ->
            caps.mlKitFeatures.isNotEmpty() && caps.memoryMB > 2000
        }.sortedByDescending { (_, caps) -> caps.memoryMB }
        
        if (mlKitCapableNodes.isNotEmpty()) {
            assignments["ml-kit-native"] = MLServerAssignment(
                serviceType = "ml-kit-native",
                primaryServerId = mlKitCapableNodes[0].first,
                backupServerIds = mlKitCapableNodes.drop(1).take(2).map { it.first },
                loadBalance = true
            )
        }
        
        // ML Kit Custom Services - need good memory + network for Firebase
        val customMLCapableNodes = capabilities.toList().filter { (_, caps) ->
            caps.mlKitCustomSupport && caps.memoryMB > 3000
        }.sortedByDescending { (_, caps) -> caps.memoryMB }
        
        if (customMLCapableNodes.isNotEmpty()) {
            assignments["ml-kit-custom"] = MLServerAssignment(
                serviceType = "ml-kit-custom",
                primaryServerId = customMLCapableNodes[0].first,
                backupServerIds = customMLCapableNodes.drop(1).take(2).map { it.first },
                loadBalance = true
            )
        }
        
        // LiteRT Services - need high memory + storage + preferably GPU
        // val literTCapableNodes = capabilities.toList().filter { (_, caps) ->
        //     caps.hasLiteRT && caps.memoryMB > 4000 && caps.storageMB > 1000
        // }.sortedWith(
        //     compareByDescending<Pair<Int, DeviceCapabilities>> { it.second.memoryMB }
        //         .thenByDescending { it.second.hasGPUAcceleration }
        //         .thenByDescending { it.second.storageMB }
        // )
        
        // if (literTCapableNodes.isNotEmpty()) {
        //     assignments["litert"] = MLServerAssignment(
        //         serviceType = "litert",
        //         primaryServerId = literTCapableNodes[0].first,
        //         backupServerIds = literTCapableNodes.drop(1).take(2).map { it.first },
        //         loadBalance = false // LiteRT models are larger, less suitable for load balancing
        //     )
        // }
        
        // Specialized assignments for specific model types
        val highEndNodes = capabilities.toList().filter { (_, caps) ->
            caps.deviceClass == DeviceCapabilities.DeviceClass.ML_POWERHOUSE
        }.sortedByDescending { (_, caps) -> caps.memoryMB }
        
        if (highEndNodes.isNotEmpty()) {
            assignments["large-language-models"] = MLServerAssignment(
                serviceType = "large-language-models",
                primaryServerId = highEndNodes[0].first,
                backupServerIds = highEndNodes.drop(1).take(1).map { it.first }, // Only 1 backup for heavy models
                loadBalance = false
            )
        }
        
        return assignments
    }
    
    private fun announceMLServerRoles(assignments: Map<String, MLServerAssignment>) {
        // Create enhanced service originator message with ML server role announcements
    val localNodeId = meshNode.getNodeId()
        val myAssignments = assignments.filter { (_, assignment) ->
            assignment.primaryServerId == localNodeId || localNodeId in assignment.backupServerIds
        }
        
        if (myAssignments.isNotEmpty()) {
            Log.i(TAG, "Announcing ML server roles: ${myAssignments.keys}")
            // In real implementation, this would extend the originator message
            // meshNode.announceMLServerRoles(myAssignments)
        }
    }
    
    private fun detectLocalMLCapabilities(): DeviceCapabilities {
        // Detect ML capabilities of local device
        return DeviceCapabilities(
            memoryMB = getDeviceMemoryMB(),
            storageMB = getAvailableStorageMB(),
            batteryLevel = getBatteryLevel(),
            isCharging = isCharging(),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            meshHops = 0, // Local device
            deviceClass = determineDeviceClass(),
            mlKitFeatures = detectMLKitFeatures(),
            mlKitCustomSupport = hasMLKitCustomSupport(),
            hasLiteRT = hasLiteRTSupport(),
            hasGPUAcceleration = hasGPUAcceleration(),
            hasNNAPI = hasNNAPISupport()
        )
    }
    
    // Helper methods for capability detection
    private fun getDeviceMemoryMB(): Int {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            (memInfo.totalMem / (1024 * 1024)).toInt()
        } catch (e: Exception) {
            2048 // Default to 2GB if detection fails
        }
    }
    
    private fun getAvailableStorageMB(): Int {
        return try {
            val internal = context.filesDir
            (internal.usableSpace / (1024 * 1024)).toInt()
        } catch (e: Exception) {
            1024 // Default to 1GB
        }
    }
    
    private fun getBatteryLevel(): Float {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) / 100f
        } catch (e: Exception) {
            0.5f
        }
    }
    
    private fun isCharging(): Boolean {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            batteryManager.isCharging
        } catch (e: Exception) {
            false
        }
    }
    
    private fun determineDeviceClass(): DeviceCapabilities.DeviceClass {
        val memoryMB = getDeviceMemoryMB()
        val hasMLKit = detectMLKitFeatures().isNotEmpty()
        val hasLiteRT = hasLiteRTSupport()
        
        return when {
            memoryMB > 6000 && hasMLKit && hasLiteRT -> DeviceCapabilities.DeviceClass.ML_POWERHOUSE
            memoryMB > 3000 && hasMLKit -> DeviceCapabilities.DeviceClass.ML_CAPABLE
            memoryMB > 1500 && hasMLKit -> DeviceCapabilities.DeviceClass.ML_BASIC
            else -> DeviceCapabilities.DeviceClass.CONSUMER
        }
    }
    
    private fun detectMLKitFeatures(): List<String> {
        // Check which ML Kit features are available
        val features = mutableListOf<String>()
        
        try {
            // These would be real ML Kit availability checks
            features.add("text-recognition")
            
            if (getDeviceMemoryMB() > 2000) {
                features.add("face-detection")
            }
            
            if (getDeviceMemoryMB() > 4000) {
                features.add("object-detection")
                features.add("translation")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect ML Kit features", e)
        }
        
        return features
    }
    
    private fun hasMLKitCustomSupport(): Boolean {
        return getDeviceMemoryMB() > 3000 // Simplified check
    }
    
    private fun hasLiteRTSupport(): Boolean {
        return try {
            Class.forName("com.google.ai.edge.litert.CompiledModel") != null
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    private fun hasGPUAcceleration(): Boolean {
        return try {
            // Check if GPU accelerator is available by trying to create options
            com.google.ai.edge.litert.CompiledModel.Options(com.google.ai.edge.litert.Accelerator.GPU)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun hasNNAPISupport(): Boolean {
        return try {
            // NNAPI is handled internally by LiteRT when using CPU accelerator
            // Check for general LiteRT support and API level
            Class.forName("com.google.ai.edge.litert.CompiledModel") != null &&
            android.os.Build.VERSION.SDK_INT >= 27
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}

// Extension to MeshRole enum (would be added to existing enum)

