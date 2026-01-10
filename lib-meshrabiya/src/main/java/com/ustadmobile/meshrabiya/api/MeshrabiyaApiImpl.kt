package com.ustadmobile.meshrabiya.api
// import com.ustadmobile.meshrabiya.model.toHash
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
import java.io.File
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import com.ustadmobile.meshrabiya.vnet.MeshFile
import com.ustadmobile.meshrabiya.storage.StorageDevice
import com.ustadmobile.meshrabiya.storage.StorageAllocation
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
// UNUSED SCHEDULER IMPORTS - Commented 2025-11-12
// Scheduler infrastructure not used in Phase 3-4 ML-capable compute implementation
// import com.ustadmobile.meshrabiya.service.compute.scheduler.ComputeTask
// import com.ustadmobile.meshrabiya.service.compute.scheduler.ExecutionPlan
import com.ustadmobile.meshrabiya.service.compute.model.JobType
// DEPRECATED: IntelligentDistributedComputeService replaced by canonical compute workflows (2025-12-04)
// import com.ustadmobile.meshrabiya.service.compute.IntelligentDistributedComputeService
import com.ustadmobile.meshrabiya.model.MeshState
import com.ustadmobile.meshrabiya.model.NetworkInfo
import com.ustadmobile.meshrabiya.model.NodeInfo
import com.ustadmobile.meshrabiya.model.ApiResult
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.EmergentRoleManager
import com.ustadmobile.meshrabiya.vnet.MeshRole
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import com.ustadmobile.meshrabiya.service.ComputeTaskRequestMessage
import com.ustadmobile.meshrabiya.service.TorStatusMonitor
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.storage.FileReference
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import com.ustadmobile.meshrabiya.storage.DropFolderItem
import com.ustadmobile.meshrabiya.storage.StoreFileTrigger
import com.ustadmobile.meshrabiya.storage.RecipientType
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.util.toHash
import com.ustadmobile.meshrabiya.storage.StorageDeviceType
import com.ustadmobile.meshrabiya.model.User

// import com.ustadmobile.meshrabiya.model.ServiceAnnouncement

/**
 * Production-ready implementation of MeshrabiyaApi.
 * Delegates all operations to internal Meshrabiya components.
 */
class MeshrabiyaApiImpl : MeshrabiyaApi {

    // Store the application context
    @Volatile
    private var appContext: Context? = null
    override fun provideAppContext(context: Context) {
        appContext = context.applicationContext
    }

    override fun getAppContext(): Context? {
        return appContext
    }

    companion object {
        @Volatile
        private var instance: MeshrabiyaApiImpl? = null

        fun getInstance(): MeshrabiyaApiImpl {
            return instance ?: synchronized(this) {
                instance ?: MeshrabiyaApiImpl().also { instance = it }
            }
        }
        
        /**
         * Timeout threshold for gateway staleness check.
         * Phase 3B: Used to filter stale gateways from statistics
         */
        private const val GATEWAY_STALE_TIMEOUT_MS = 30_000L  // 30 seconds
    }

    // Internal managers, initialized in initMesh
    private var myNode: AndroidVirtualNode? = null
    private var emergentRoleManager: EmergentRoleManager? = null
    private var distributedStorageManager: DistributedStorageManager? = null
    // distributedComputeClient accessed via myNode?.distributedComputeClient (protected property)
    // DEPRECATED: intelligentDistributedComputeService removed (2025-12-04)
    // Compute workflows now use DistributedComputeServer + TaskManager directly
    // private var intelligentDistributedComputeService: IntelligentDistributedComputeService? = null

    // Section 6: Event monitoring scope and jobs
    private val eventMonitoringScope = CoroutineScope(Dispatchers.Default)
    private var stateMonitorJob: Job? = null
    private var peerMonitorJob: Job? = null
    
    // V3: Gateway preference state
    @Volatile
    private var currentGatewayPreference: GatewayPreference = GatewayPreference.DEFAULT
    @Volatile
    private var isTorRunning: Boolean = false
    private val torStatusMonitor = TorStatusMonitor()

     // --- Proxy Controls ---
    override fun setProxy(host: String, port: Int) {
        myNode?.setProxy(host, port)
    }

    override fun setProxyActive(active: Boolean) {
        myNode?.setProxyActive(active)
    }

    // --- Mesh Initialization ---
    private val Context.dataStore by preferencesDataStore(name = "meshr_settings")
    
    override fun initMesh(context: Context) {
        val dataStore = context.dataStore

        myNode = AndroidVirtualNode(
            appContext = context.applicationContext,
            dataStore = dataStore
        )

        emergentRoleManager = myNode?.emergentRoleManager
        distributedStorageManager = myNode?.distributedStorageManager
        // distributedComputeClient accessed via myNode.getDistributedComputeClient() when needed
        // DEPRECATED: IntelligentDistributedComputeService removed (2025-12-04)
        // intelligentDistributedComputeService = myNode?.getIntelligentDistributedComputeService()
        
        // V3: Load gateway preference from storage
        runBlocking {
            loadGatewayPreference(context)
        }
        
        // V3: Register Tor status monitor
        torStatusMonitor.register(context)
        torStatusMonitor.requestStatusUpdate(context)  // Get initial status
        
        // Section 6: Start monitoring for state and peer count changes
        startEventMonitoring()
    }
    
    /**
     * Section 6: Start coroutines to monitor mesh state and peer count changes
     * Invokes registered callbacks when changes are detected
     */
    private fun startEventMonitoring() {
        // Monitor mesh state changes
        stateMonitorJob = eventMonitoringScope.launch {
            var previousState = getMeshStatus()
            while (true) {
                delay(1000) // Check every second
                val currentState = getMeshStatus()
                if (currentState != previousState) {
                    previousState = currentState
                    onMeshStateChanged?.invoke(currentState)
                }
            }
        }
        
        // Monitor peer count changes
        peerMonitorJob = eventMonitoringScope.launch {
            var previousCount = getPeerCount()
            while (true) {
                delay(1000) // Check every second
                val currentCount = getPeerCount()
                if (currentCount != previousCount) {
                    previousCount = currentCount
                    onPeerCountChanged?.invoke(currentCount)
                }
            }
        }
    }
    
    /**
     * Section 6: Stop event monitoring (for cleanup)
     */
    private fun stopEventMonitoring() {
        stateMonitorJob?.cancel()
        peerMonitorJob?.cancel()
        stateMonitorJob = null
        peerMonitorJob = null
    }

    // --- Mesh State & Network Info ---
    override fun getNodeRole(): Byte = emergentRoleManager?.getCurrentMeshRoles()?.firstOrNull()?.ordinal?.toByte() ?: 0

    override fun getFitnessScore(): Float = emergentRoleManager?.getFitnessScore() ?: 0f
    
    override fun getConnectionUri(): String = myNode?.currentNodeState?.connectUri ?: ""
    override fun getLocalNodeState(): com.ustadmobile.meshrabiya.vnet.LocalNodeState = myNode?.currentNodeState ?: throw IllegalStateException("Mesh not initialized")
    override fun getNeighbors(): List<Int> = myNode?.neighbors()?.map { it.first } ?: emptyList()
    override fun getHopCountToNode(nodeId: Int): Int? = myNode?.originatingMessageManager?.findOriginatingMessageFor(nodeId)?.hopCount?.toInt()

    override fun getConnectLink(): String? = myNode?.currentNodeState?.connectUri
    override fun getConnectLinkFlow(): Flow<String?> = myNode?.state?.map { it.connectUri } ?: flowOf(null)

    // --- Mesh Network Controls ---
    override fun startMesh(callback: (Result<Unit>) -> Unit) {
        try {
            runBlocking {
                myNode?.setWifiHotspotEnabled(
                    enabled = true,
                    preferredBand = ConnectBand.BAND_5GHZ,
                    hotspotType = HotspotType.AUTO
                )
            }
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    override fun stopMesh(callback: (Result<Unit>) -> Unit) {
        try {
            runBlocking {
                myNode?.setWifiHotspotEnabled(
                    enabled = false,
                    preferredBand = ConnectBand.BAND_5GHZ,
                    hotspotType = HotspotType.AUTO
                )
            }
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    override fun getMeshStatus(): MeshState {
        val node = myNode ?: return MeshState.DISCONNECTED
        
        // Determine state based on neighbors and network connectivity
        val neighborCount = node.neighbors().size
        
        return when {
            neighborCount == 0 -> MeshState.DISCONNECTED
            neighborCount > 0 -> MeshState.CONNECTED
            else -> MeshState.UNKNOWN
        }
    }
    override fun getPeerCount(): Int = myNode?.neighbors()?.size ?: 0 // myNode?.getPeerCount() ?: 0
    
    /**
     * Phase 3B: Enhanced getNetworkInfo() with gateway statistics
     * Returns mesh network information including Tor and clearnet gateway counts
     */
    override fun getNetworkInfo(): NetworkInfo {
        val node = myNode
        if (node == null) {
            return NetworkInfo() // Mesh not initialized
        }
        
        val topology = node.originatingMessageManager.getTopologyMapInfo()
        val connectedNeighbors = node.neighbors().size
        
        // Phase 3B: Count gateways by type
        val torGateways = topology.values.count { nodeInfo ->
            nodeInfo.hasRole(com.ustadmobile.meshrabiya.vnet.MeshRole.TOR_GATEWAY) &&
            !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
        }
        
        val clearnetGateways = topology.values.count { nodeInfo ->
            nodeInfo.hasRole(com.ustadmobile.meshrabiya.vnet.MeshRole.CLEARNET_GATEWAY) &&
            !nodeInfo.isStale(GATEWAY_STALE_TIMEOUT_MS)
        }
        
        return NetworkInfo(
            connectedPeers = connectedNeighbors,
            torGateways = torGateways,
            clearnetGateways = clearnetGateways,
        )
    }
    override fun getNodeId(): Int {
        val node = myNode ?: return 0
        return node.addressAsInt
    }

    override fun getNodeInfo(nodeId: String): NodeInfo {
        val node = myNode ?: return NodeInfo()
        
        // Get topology information for the requested node
        val topology = node.originatingMessageManager.getTopologyMapInfo()
        
        // Try to parse nodeId as Int, return empty if invalid
        val nodeAddress = nodeId.toIntOrNull() ?: return NodeInfo()
        val nodeData = topology[nodeAddress] ?: return NodeInfo()
        
        // Extract capabilities from meshRoles
        val capabilities = nodeData.meshRoles.map { role -> role.name }
        
        return NodeInfo(
            nodeId = nodeId,
            displayName = nodeId.substring(0, minOf(8, nodeId.length)), // Use first 8 chars as display name
            isOnline = !nodeData.isStale(GATEWAY_STALE_TIMEOUT_MS),
            lastSeen = System.currentTimeMillis(), // Topology doesn't track lastSeen, use current time
            capabilities = capabilities
        )
    }

    // --- Gateway Controls ---
    // TODO: Reimplement using GatewaySelector from canonical workflows (2025-12-04)
    override fun setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        val roleManager = myNode?.emergentRoleManager
        if (roleManager == null) {
            callback(Result.failure(IllegalStateException("Role manager not initialized")))
            return
        }
        
        try {
            val currentRoles = roleManager.getCurrentMeshRoles().toMutableSet()
            
            if (enabled) {
                currentRoles.add(MeshRole.TOR_GATEWAY)
            } else {
                currentRoles.remove(MeshRole.TOR_GATEWAY)
            }
            
            roleManager.setPreferredRoles(currentRoles)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }
    override fun getTorGatewayStatus(): Boolean {
        val roleManager = myNode?.emergentRoleManager ?: return false
        return roleManager.getCurrentMeshRoles().contains(MeshRole.TOR_GATEWAY)
    }
    override fun setInternetGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        val roleManager = myNode?.emergentRoleManager
        if (roleManager == null) {
            callback(Result.failure(IllegalStateException("Role manager not initialized")))
            return
        }
        
        try {
            val currentRoles = roleManager.getCurrentMeshRoles().toMutableSet()
            
            if (enabled) {
                currentRoles.add(MeshRole.CLEARNET_GATEWAY)
            } else {
                currentRoles.remove(MeshRole.CLEARNET_GATEWAY)
            }
            
            roleManager.setPreferredRoles(currentRoles)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }
    override fun getInternetGatewayStatus(): Boolean {
        val roleManager = myNode?.emergentRoleManager ?: return false
        return roleManager.getCurrentMeshRoles().contains(MeshRole.CLEARNET_GATEWAY)
    }
    override fun getGatewayStatus(): Boolean {
        val roleManager = myNode?.emergentRoleManager ?: return false
        val roles = roleManager.getCurrentMeshRoles()
        return roles.contains(MeshRole.TOR_GATEWAY) || 
               roles.contains(MeshRole.CLEARNET_GATEWAY) ||
               roles.contains(MeshRole.I2P_GATEWAY)
    }

    // --- V3: Gateway Preference Implementation ---
    override fun setGatewayPreference(preference: GatewayPreference, callback: (Result<Unit>) -> Unit) {
        try {
            val context = appContext ?: throw IllegalStateException("App context not provided")
            runBlocking {
                context.dataStore.edit { prefs ->
                    prefs[stringPreferencesKey(GatewayPreference.KEY_GATEWAY_PREFERENCE)] = preference.name
                }
                currentGatewayPreference = preference
            }
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }

    override fun getGatewayPreference(): GatewayPreference {
        return currentGatewayPreference
    }

    override fun isTorActive(): Boolean {
        return isTorRunning
    }

    /**
     * V3: Internal method to update Tor status from TorStatusMonitor.
     * Called by TorStatusMonitor BroadcastReceiver when Orbot status changes.
     */
    internal fun updateTorStatus(isActive: Boolean) {
        isTorRunning = isActive
    }

    /**
     * V3: Load gateway preference from DataStore on initialization.
     */
    private suspend fun loadGatewayPreference(context: Context) {
        val prefs = context.dataStore.data.first()
        val prefString = prefs[stringPreferencesKey(GatewayPreference.KEY_GATEWAY_PREFERENCE)]
        currentGatewayPreference = GatewayPreference.fromString(prefString)
    }

    // --- Storage Participation ---
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun setStorageParticipationEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        val storageManager = myNode?.distributedStorageManager
        if (storageManager == null) {
            callback(Result.failure(IllegalStateException("Storage manager not initialized")))
            return
        }
        
        try {
            // Get current config and update participation flag
            val config = com.ustadmobile.meshrabiya.storage.DistributedStorageManager.StorageParticipationConfig(
                participationEnabled = enabled,
                totalQuota = storageManager.storageConfig.defaultQuota,
                allowedDirectories = emptyList(),  // Use defaults
                encryptionRequired = storageManager.storageConfig.encryptionEnabled
            )
            
            storageManager.configureStorageParticipation(config)
            callback(Result.success(Unit))
        } catch (e: Exception) {
            callback(Result.failure(e))
        }
    }
    override fun getStorageParticipationStatus(): Boolean {
        val storageManager = myNode?.distributedStorageManager ?: return false
        return storageManager.participationEnabled.value
    }
    override fun getAvailableStorageDevices(): List<StorageDeviceDto> {
        // Storage device enumeration not yet implemented in DistributedStorageManager
        // Return empty list until backend implementation available
        return emptyList()
    }
    
    override fun setStorageAllocation(deviceId: String,
    path: String, allocatedMB: Long, ) {
        try {
            val current = MeshrabiyaConstants.getStorageAllocations().toMutableList()
            val idx = current.indexOfFirst { it.path == path }
            if (idx >= 0) {
                current[idx] = StorageAllocation(path, allocatedMB,deviceId,)
            } else {
                current.add(StorageAllocation(path, allocatedMB,deviceId,))
            }
            MeshrabiyaConstants.setStorageAllocations(current)
            // callback(Result.success(Unit))
        } catch (e: Exception) {
            // callback(Result.failure(e))
        }
    }

    override fun getStorageAllocations(): List<StorageAllocation> {
        return MeshrabiyaConstants.getStorageAllocations()
    }
    
    override fun enableDistributedStorage() {
        val storageManager = myNode?.distributedStorageManager
        val listener = myNode?.obtainMeshEcosystemListener()
        
        if (storageManager != null && listener != null) {
            storageManager.registerWithEcosystemListener(listener)
        }
    }
    
    override fun disableDistributedStorage() {
        val storageManager = myNode?.distributedStorageManager
        val listener = myNode?.obtainMeshEcosystemListener()
        
        if (storageManager != null && listener != null) {
            storageManager.unregisterFromEcosystemListener(listener)
        }
    }

    private var onDropFolderUpdateHandler: ((List<DropFolderItemDto>) -> Unit)? = null

    override fun setOnDropFolderUpdate(handler: (List<DropFolderItemDto>) -> Unit) {
        onDropFolderUpdateHandler = handler
    }

    internal fun notifyDropFolderUpdate(changes: List<DropFolderItem>) {
        val dtos = changes.map { it.toDto() }
        onDropFolderUpdateHandler?.invoke(dtos)
    }

    override fun setDropFolderPath(path: String) {
        MeshrabiyaConstants.setDropFolderPath(path)
    }

    override fun getDropFolderPath(): String {
        return MeshrabiyaConstants.getDropFolderPath()
    }
    // TODO: Reimplement using TaskManager from canonical workflows (2025-12-04)
    override fun isComputeLayerParticipating(): Boolean {
        return MeshrabiyaConstants.isComputeLayerParticipating()
    }

    override fun setComputeLayerParticipatingEnabled(enabled: Boolean) {
        MeshrabiyaConstants.setComputeLayerParticipatingEnabled(enabled)
    }

    // --- Drop Folder Management ---
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun selectDropFolder(path: String, callback: (Result<Unit>) -> Unit) {
        // try {
        //     distributedStorageManager?.selectDropFolder(path)
            callback(Result.success(Unit))
        // } catch (e: Exception) {
        //     callback(Result.failure(e))
        // }
    }
    override fun getDropFolder(): File? = null // distributedStorageManager?.getDropFolder()
    override fun getDropFolderFiles(): List<File> = emptyList() // distributedStorageManager?.getDropFolderFiles() ?: emptyList()

    // =========================================================
    // Section 1: File Operations (Canonical Workflow Refactor)
    // =========================================================
    // All file operations below are refactored to match canonical workflow requirements.
    // Each method includes explicit TODOs, error handling, and implementation notes.
    // Remove TODOs and update implementation when DistributedStorageManager supports each operation.
    override fun storeFile(file: File, recipients:List<RecipientEntryDto>
    ) {
        val storageManager = myNode?.distributedStorageManager
        if (storageManager == null) {
            getOnOperationFailed()?.invoke("storeFile", IllegalStateException("Storage manager not initialized"))
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val fileBytes = file.readBytes()
                val senderId = myNode?.address?.hostAddress
                val recipientEntryList: List<RecipientEntry> = recipients.map { it.toInternal() }
                val fileRef = storageManager.storeFile(
                    path = file.absolutePath,
                    data = fileBytes,
                    recipients = recipientEntryList, // TODO: Specify recipients if needed
                )
                if (fileRef != null) {
                    // callback(Result.success(fileRef.id))
                    onFileStored?.invoke(fileRef.fileId, file, Result.success(fileRef.fileId))
                } else {
                    // callback(Result.failure(Exception("Failed to store file")))
                     getOnOperationFailed()?.invoke("storeFile",  Exception("Failed to store file"))
                }
            } catch (e: Exception) {
                // callback(Result.failure(e))
                getOnOperationFailed()?.invoke("storeFile", Exception("Failed to store file"))
            }
        }
    }

    override suspend fun retrieveFile(fileId: String): ByteArray? {
        if (fileId.isBlank()) {
            getOnOperationFailed()?.invoke("retrieveFile", IllegalArgumentException("File ID cannot be blank"))
            return null
        }
        val storageManager = myNode?.distributedStorageManager
        if (storageManager == null) {
            getOnOperationFailed()?.invoke("retrieveFile", IllegalStateException("Storage manager not initialized"))
            return null
        }
        val metadata = storageManager.getFileMetadata(fileId)
        if (metadata == null) {
            getOnOperationFailed()?.invoke("retrieveFile", java.io.FileNotFoundException("File not found: $fileId"))
            return null
        }
        return try {
            storageManager.retrieveFile(fileId)
        } catch (e: Exception) {
            getOnOperationFailed()?.invoke("retrieveFile", e)
            null
        }
    }

    override fun streamFile(fileId: String, callback: (Result<Unit>) -> Unit) {
        // Streaming not implemented in DistributedStorageManager; return error for now
        callback(Result.failure(NotImplementedError("streamFile not implemented in DistributedStorageManager")))
    }

    override fun deleteFile(fileId: String, callback: (Result<Unit>) -> Unit) {
        if (fileId.isBlank()) {
            callback(Result.failure(IllegalArgumentException("File ID cannot be blank")))
            return
        }
        val storageManager = myNode?.distributedStorageManager
        if (storageManager == null) {
            callback(Result.failure(IllegalStateException("Storage manager not initialized")))
            return
        }
        val metadata = storageManager.getFileMetadata(fileId)
        if (metadata == null) {
            callback(Result.failure(java.io.FileNotFoundException("File not found: $fileId")))
            return
        }
        // No deleteFile API in DistributedStorageManager; return error for now
        callback(Result.failure(NotImplementedError("deleteFile not implemented in DistributedStorageManager")))
    }
    override fun getAllMeshFiles(): List<MeshFile> {
        // Check storage manager availability
        val storageManager = myNode?.distributedStorageManager ?: return emptyList()
        
        try {
            // Get all file metadata from storage manager's file metadata map
            val fileMetadataMap = storageManager.fileMetadataStore
            
            if (fileMetadataMap.isEmpty()) {
                return emptyList()
            }
            
            // Convert FileMetadata to MeshFile
            return fileMetadataMap.values.map { metadata ->
                MeshFile(
                    fileId = metadata.fileId,
                    fileName = File(metadata.path).name,  // Extract filename from path
                    owner = metadata.owner,
                    recipients = metadata.recipients,
                    sizeBytes = metadata.sizeBytes,
                    createdAt = metadata.createdAt,
                    relativePath = metadata.relativePath,
                    path = metadata.path
                )
            }
        } catch (e: Exception) {
            onOperationFailed?.invoke("getAllMeshFiles", e)
            return emptyList()
        }
    }

    // --- Distributed Service Layer ---
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun setServiceParticipationEnabled(serviceId: String, enabled: Boolean, callback: (Result<Unit>) -> Unit) {
        // try {
        //     myNode?.setServiceParticipationEnabled(serviceId, enabled)
            callback(Result.success(Unit))
        // } catch (e: Exception) {
        //     callback(Result.failure(e))
        // }
    }
    override fun getAvailableServices(): List<String> = emptyList() // myNode?.getAvailableServices() ?: emptyList()
    override fun getServiceParticipationStatus(serviceId: String): Boolean = false // myNode?.getServiceParticipationStatus(serviceId) ?: false

    // --- Compute/Task Operations ---
    /**
     * Submit a compute task to the mesh network.
     * 
     * @param requestParams Map containing:
     *   - taskId (String, optional): Unique task identifier (auto-generated if not provided)
     *   - taskType (String, required): Execution engine (python, jvm, javascript, ml-native)
     *   
     * @return ApiResult.Success if task submitted, ApiResult.Failure on error
     * 
     * Note: JobType removed 2025-12-06 - no concept of "supported job types".
     * Use taskType to specify execution engine. Service discovery handles capability matching.
     */
    override fun addTask(requestParams: Map<String, Any>): ApiResult {
        return try {
            // Extract parameters
            val taskId = requestParams["taskId"] as? String ?: java.util.UUID.randomUUID().toString()
            val taskType = requestParams["taskType"] as? String 
                ?: return ApiResult.Failure(IllegalArgumentException("taskType required (python, jvm, javascript, ml-native)"))
            
            // Check compute client availability
            val computeClient = myNode?.obtainDistributedComputeClient() 
                ?: return ApiResult.Failure(IllegalStateException("Compute client not initialized"))
            
            // Create LocalComputeTaskRequest
            val request = com.ustadmobile.meshrabiya.service.compute.model.LocalComputeTaskRequest(
                requestId = java.util.UUID.randomUUID().toString(),
                taskId = taskId,
                taskType = taskType,  // Execution engine: python, jvm, javascript, ml-native
                timestamp = System.currentTimeMillis()
            )
            
            // Submit task asynchronously
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    computeClient.processTaskRequest(request)
                } catch (e: Exception) {
                    onOperationFailed?.invoke("addTask", e)
                }
            }
            
            ApiResult.Success  // Return immediately, status updates via callback
        } catch (e: Exception) {
            ApiResult.Failure(e)
        }
    }

    override fun startTask(taskId: String, callback: (Result<Unit>) -> Unit) {
        callback(Result.failure(NotImplementedError("startTask not yet implemented in canonical workflows")))
        // intelligentDistributedComputeService?.startTask(taskId, callback)
    }

    override fun cancelTask(taskId: String, callback: (Result<Unit>) -> Unit) {
        callback(Result.failure(NotImplementedError("cancelTask not yet implemented in canonical workflows")))
        // intelligentDistributedComputeService?.cancelTask(taskId, callback)
    }
    
    // DEPRECATED 2025-12-06: getJobTypes() removed
    // JobType is for ServiceLibraryEntry categorization only, not for API-level task validation
    // Use taskType (execution engine: python, jvm, js, ml-native) for task submission
    // override fun getJobTypes(): List<JobType> = emptyList()
    
    // UNUSED SCHEDULER API - Commented 2025-11-12
    // These implementations reference scheduler types that are unused in Phase 3-4
    // override fun getTaskStatus(taskId: String): ExecutionPlan? = intelligentDistributedComputeService?.getTaskStatus(taskId)
    // override fun getAllTasks(): List<ComputeTask> = intelligentDistributedComputeService?.getAllTasks() ?: emptyList()
    private var onFileRetrieved: ((fileId: String, file: File) -> Unit)? = null
    private var onFileStored: ((fileId: String, file: File, result: Result<String>) -> Unit)? = null
    private var onPermissionUpdated: ((fileId: String, success: Boolean) -> Unit)? = null
    private var onOperationFailed: ((operation: String, error: Throwable) -> Unit)? = null
    // UNUSED SCHEDULER API - Commented 2025-12-04
    // private var onTaskCompleted: ((taskId: String, result: ExecutionPlan) -> Unit)? = null
    private var onFileShared: ((fileId: String, recipientId: String) -> Unit)? = null
    private var onFileAddedToDropFolder: ((fileId: String, file: File) -> Unit)? = null

    override fun setOnFileRetrieved(handler: (fileId: String, file: File) -> Unit) {
        onFileRetrieved = handler
    }
    override fun setOnFileStored(handler: (fileId: String, file: File, result: Result<String>) -> Unit) {
        onFileStored = handler
    }
    fun getOnFileStored(): ((fileId: String, file: File, result: Result<String>) -> Unit)? {
        return onFileStored
    }
    override fun setOnPermissionUpdated(handler: (fileId: String, success: Boolean) -> Unit) {
        onPermissionUpdated = handler
    }
    override fun setOnOperationFailed(handler: (operation: String, error: Throwable) -> Unit) {
        onOperationFailed = handler
    }

    fun getOnOperationFailed(): ((operation: String, error: Throwable) -> Unit)? {
        return onOperationFailed
    }
    
    // UNUSED SCHEDULER API - Commented 2025-11-12
    // override fun setOnTaskCompleted(handler: (taskId: String, result: ExecutionPlan) -> Unit) {
    //     onTaskCompleted = handler
    // }
    
    
    override fun setOnFileShared(handler: (fileId: String, recipientId: String) -> Unit) {
        onFileShared = handler
    }
    override fun setOnFileAddedToDropFolder(handler: (fileId: String, file: File) -> Unit) {
        onFileAddedToDropFolder = handler
    }

    // --- Settings and State ---
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun getSettings(): Map<String, Any> {
        return mapOf(
            "dropFolderPath" to "",
            "availableServices" to emptyList<String>()
            // DEPRECATED 2025-12-06: jobTypes removed - use ServiceLibraryEntries for capability discovery
        )
    }
    override fun setSetting(key: String, value: Any, callback: (Result<Unit>) -> Unit) {
        // try {
        //     when (key) {
        //         "dropFolderPath" -> distributedStorageManager?.selectDropFolder(value as String)
        //         else -> throw IllegalArgumentException("Unknown setting key: $key")
        //     }
            callback(Result.success(Unit))
        // } catch (e: Exception) {
        //     callback(Result.failure(e))
        // }
    }

    // --- Service Bundle & Gateway Controls ---
    // override fun announceService(serviceAnnouncement: ServiceAnnouncement, signedBundle: ByteArray, callback: (Result<Unit>) -> Unit) {
    //     try {
    //         myNode?.announceService(serviceAnnouncement, signedBundle)
    //         callback(Result.success(Unit))
    //     } catch (e: Exception) {
    //         callback(Result.failure(e))
    //     }
    // }
    // override fun requestServiceBundle(serviceId: String, requesterOnionAddress: String, callback: (Result<ByteArray?>) -> Unit) {
    //     try {
    //         val result = myNode?.requestServiceBundle(serviceId, requesterOnionAddress)
    //         callback(Result.success(result))
    //     } catch (e: Exception) {
    //         callback(Result.failure(e))
    //     }
    // }
    private var onGatewayTraffic: ((packet: VirtualPacket) -> Boolean)? = null
    override fun setOnGatewayTraffic(handler: (packet: VirtualPacket) -> Boolean) {
        onGatewayTraffic = handler
    }
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun getMeshTrafficRouterStatus(): String = "Inactive" // {
        // val router = myNode?.getMeshTrafficRouter()
        // return if (router != null) "Active: ${router.javaClass.name}" else "Inactive"
    // }

    // --- Event/Callback Integration ---
    private var onMeshStateChanged: ((MeshState) -> Unit)? = null
    private var onPeerCountChanged: ((Int) -> Unit)? = null
    // private var onServiceBundleReceived: ((String, ByteArray) -> Unit)? = null
    // private var onServiceAnnounced: ((String, ServiceAnnouncement) -> Unit)? = null
    private var onGossipMessage: ((Int, ByteArray) -> Unit)? = null
    private var onTaskStatusUpdate: ((String, String) -> Unit)? = null  // Section 9

    override fun setOnMeshStateChanged(handler: (newState: MeshState) -> Unit) {
        onMeshStateChanged = handler
    }
    override fun setOnPeerCountChanged(handler: (newCount: Int) -> Unit) {
        onPeerCountChanged = handler
    }
    // override fun setOnServiceBundleReceived(handler: (serviceId: String, bundle: ByteArray) -> Unit) {
    //     onServiceBundleReceived = handler
    // }
    // override fun setOnServiceAnnounced(handler: (serviceId: String, announcement: ServiceAnnouncement) -> Unit) {
    //     onServiceAnnounced = handler
    // }
    // TODO: Reimplement using canonical workflows (2025-12-04)
    override fun setOnGossipMessage(handler: (senderId: Int, messageBytes: ByteArray) -> Unit) {
        onGossipMessage = handler
        // myNode?.addGossipListener(handler)
    }
    
    /**
     * Section 9: Set task status update callback
     * Wired through MeshEcosystemListener when TaskCompletedMessage received
     */
    override fun setOnTaskStatusUpdate(handler: (taskId: String, status: String) -> Unit) {
        onTaskStatusUpdate = handler
    }
    
    /**
     * Section 9: Internal method called by MeshEcosystemListener to trigger callback
     * This provides a public accessor for the listener to invoke the callback
     */
    fun triggerTaskStatusUpdate(taskId: String, status: String) {
        onTaskStatusUpdate?.invoke(taskId, status)
    }

    // --- TaskType enablement API ---
    override fun isTaskTypeEnabled(taskType: TaskType): Boolean {
        return MeshrabiyaConstants.isTaskTypeEnabled(taskType)
    }

    override fun setTaskTypeEnabled(taskType: TaskType, enabled: Boolean) {
        MeshrabiyaConstants.setTaskTypeEnabled(taskType, enabled)
    }

    override fun getAllTaskTypeEnabled(): Map<TaskType, Boolean> {
        return MeshrabiyaConstants.getAllTaskTypeEnabled()
    }


    // --- User Identity API Implementation ---
    // Allow provider to be injected for JVM testability; default to AndroidKeyStore
    private var keyProvider: String =
        if (System.getProperty("java.vendor")?.contains("Android") == true) "AndroidKeyStore" else "BC"

    fun setKeyProviderForTest(provider: String) {
        keyProvider = provider
    }

    override fun getUserInfo(): com.ustadmobile.meshrabiya.model.User {
        println("[DEBUG] MeshrabiyaApiImpl.getUserInfo: keyProvider='$keyProvider'")
        val keypair = com.ustadmobile.meshrabiya.model.UserKeyManager.getKeypair(provider = keyProvider)
        println("[DEBUG] MeshrabiyaApiImpl.getUserInfo: keypair=$keypair")
        if (keypair == null) throw IllegalStateException("User keypair not initialized (provider='$keyProvider')")
        val publicKey = keypair.public
        val userId = publicKey.toHash()
        val nickname = MeshrabiyaConstants.getNickname() ?: ""
        println("[DEBUG] MeshrabiyaApiImpl.getUserInfo: userId='$userId', nickname='$nickname'")
        val userEntry = RecipientEntry(
            publicKey = java.util.Base64.getEncoder().encodeToString(publicKey.encoded),
            recipientType = RecipientType.USER,
            recipientId = userId
        )
        return com.ustadmobile.meshrabiya.model.User(userId, publicKey, nickname, keypair, userEntry)
    }

    override fun setUserNickname(nickname: String) {
        println("[DEBUG] MeshrabiyaApiImpl.setUserNickname: nickname='$nickname'")
        MeshrabiyaConstants.setNickname(nickname)
    }

    override fun rotateUserKey(): com.ustadmobile.meshrabiya.model.User {
        val context = getAppContext() ?: throw IllegalStateException("App context not set")
        println("[DEBUG] MeshrabiyaApiImpl.rotateUserKey: keyProvider='$keyProvider'")
        val keypair = com.ustadmobile.meshrabiya.model.UserKeyManager.rotateKeypair(context, provider = keyProvider)
        println("[DEBUG] MeshrabiyaApiImpl.rotateUserKey: keypair=$keypair")
        val publicKey = keypair.public
        val userId = publicKey.toHash()
        println("[DEBUG] MeshrabiyaApiImpl.rotateUserKey: new userId='$userId'")
        MeshrabiyaConstants.setUserId(userId)
        MeshrabiyaConstants.setUserPublicKey(android.util.Base64.encodeToString(publicKey.encoded, android.util.Base64.DEFAULT))
         val userEntry = RecipientEntry(
            publicKey = java.util.Base64.getEncoder().encodeToString(publicKey.encoded),
            recipientType = RecipientType.USER,
            recipientId = userId
        )
        return User(userId, publicKey, MeshrabiyaConstants.getNickname() ?: "", keypair, userEntry)
    }

    /**
     * Initialize user info on first run: generate keypair, set nickname, store userId and nickname.
     * Should be called during app startup or mesh initialization.
     */
    fun initializeUser(context: Context, nicknameProvider: (() -> String)? = null) {
        // Check if userId is already set
        val userId = MeshrabiyaConstants.getUserId()
        println("[DEBUG] MeshrabiyaApiImpl.initializeUser: userId='$userId', keyProvider='$keyProvider'")
        if (userId.isNullOrEmpty()) {
            println("[DEBUG] MeshrabiyaApiImpl.initializeUser: Generating keypair")
            val keypair = com.ustadmobile.meshrabiya.model.UserKeyManager.generateKeypair(context, provider = keyProvider)
            println("[DEBUG] MeshrabiyaApiImpl.initializeUser: Generated keypair: $keypair")
            val publicKey = keypair.public
            val newUserId = publicKey.toHash()
            println("[DEBUG] MeshrabiyaApiImpl.initializeUser: newUserId='$newUserId'")
            MeshrabiyaConstants.setUserId(newUserId)
            // Prompt for nickname or set default
            val nickname = nicknameProvider?.invoke() ?: "MeshUser"
            println("[DEBUG] MeshrabiyaApiImpl.initializeUser: nickname='$nickname'")
            MeshrabiyaConstants.setNickname(nickname)
        }
    }


}

// In DropFolderItem.kt (internal model)
fun DropFolderItem.toDto(): DropFolderItemDto = DropFolderItemDto(
    itemRelativePath = itemRelativePath,
    isFolder = isFolder,
    trigger = trigger?.toDto()
)

fun DropFolderItemDto.toInternal(): DropFolderItem = DropFolderItem(
    itemRelativePath = itemRelativePath,
    isFolder = isFolder,
    trigger = trigger?.toInternal()
)

// In StoreFileTrigger.kt
fun StoreFileTrigger.toDto(): StoreFileTriggerDto = StoreFileTriggerDto(
    id=id,
    subPath = subPath,
    recipients = recipients.map { it.toDto() },

)

fun StoreFileTriggerDto.toInternal(): StoreFileTrigger = StoreFileTrigger(
    id=id,
    subPath = subPath,
    recipients = recipients.map { it.toInternal() },
    
)

// In RecipientEntry.kt
fun RecipientEntry.toDto(): RecipientEntryDto = RecipientEntryDto(
    publicKey = publicKey,
    recipientType = RecipientTypeDto.valueOf(recipientType.name),
    recipientId = recipientId,
    expiresAt = expiresAt
)

fun RecipientEntryDto.toInternal(): RecipientEntry = RecipientEntry(
    publicKey = publicKey,
    recipientType = RecipientType.valueOf(recipientType.name),
    recipientId = recipientId,
    expiresAt = expiresAt
)

fun StorageAllocation.toDto(): StorageAllocationDto = StorageAllocationDto(
    path = path,
    allocatedMB = allocatedMB,
    deviceId = deviceId
    // enabled = enabled
)

fun StorageAllocationDto.toInternal(): StorageAllocation = StorageAllocation(
    path = path,
    allocatedMB = allocatedMB,
    deviceId = deviceId
    // enabled = enabled
)

fun StorageDeviceTypeDto.toInternal(): StorageDeviceType =
    StorageDeviceType.valueOf(this.name)

fun StorageDeviceDto.toInternal(): StorageDevice =
StorageDevice(
    id = id,
    name = name,
    path = path,
    availableSpaceGB = availableSpaceGB,
    totalSpaceGB = totalSpaceGB,
    type = type.toInternal()
)

fun StorageDeviceType.toDto(): StorageDeviceTypeDto =
    StorageDeviceTypeDto.valueOf(this.name)

fun StorageDevice.toDto(): StorageDeviceDto =
StorageDeviceDto(
    id = id,
    name = name,
    path = path,
    availableSpaceGB = availableSpaceGB,
    totalSpaceGB = totalSpaceGB,
    type = type.toDto()
)