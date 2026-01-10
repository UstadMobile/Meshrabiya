package com.ustadmobile.meshrabiya.api
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
    
import java.io.File
import android.content.Context
import kotlinx.coroutines.flow.Flow
import com.ustadmobile.meshrabiya.vnet.MeshFile
import com.ustadmobile.meshrabiya.storage.StorageDevice
import com.ustadmobile.meshrabiya.storage.StorageAllocation
import kotlinx.serialization.Serializable
// UNUSED SCHEDULER IMPORTS - Commented 2025-11-12
// Scheduler infrastructure not used in Phase 3-4 ML-capable compute implementation
// Phase 3-4 uses direct broadcast-response pattern (processTaskRequest → node selection)
// import com.ustadmobile.meshrabiya.service.compute.scheduler.ComputeTask
// import com.ustadmobile.meshrabiya.service.compute.scheduler.ExecutionPlan
import com.ustadmobile.meshrabiya.model.MeshState
import com.ustadmobile.meshrabiya.model.NetworkInfo
import com.ustadmobile.meshrabiya.model.NodeInfo
import com.ustadmobile.meshrabiya.model.ApiResult
import com.ustadmobile.meshrabiya.vnet.LocalNodeState
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import com.ustadmobile.meshrabiya.storage.DropFolderItem
import com.ustadmobile.meshrabiya.storage.StoreFileTrigger
// import com.ustadmobile.meshrabiya.model.ServiceAnnouncement

/**
 * Unified API interface for Meshrabiya module.
 * Exposes all key operations, state, and event registration for UI/control layers.
 */
interface MeshrabiyaApi {

    /**
     * Provide application context to the Meshrabiya core (for use by TaskManager, etc.)
     */
    fun provideAppContext(context: Context)

    /**
     * Retrieve the application context for internal use (TaskManager, etc.)
     */
    fun getAppContext(): Context?

    // --- Mesh Initialization ---
    fun initMesh(context: Context)

    // --- Mesh State & Network Info ---
    fun getNodeRole(): Byte
    fun getFitnessScore(): Float
    fun getConnectionUri(): String
    fun getLocalNodeState(): LocalNodeState
    fun getNeighbors(): List<Int>
    fun getHopCountToNode(nodeId: Int): Int?
    fun getConnectLink(): String?
    fun getConnectLinkFlow(): Flow<String?>

    // --- Mesh Network Controls ---
    fun startMesh(callback: (Result<Unit>) -> Unit)
    fun stopMesh(callback: (Result<Unit>) -> Unit)
    fun getMeshStatus(): MeshState
    fun getPeerCount(): Int
    fun getNetworkInfo(): NetworkInfo
    fun getNodeInfo(nodeId: String): NodeInfo
    fun getNodeId(): Int
    // --- Proxy Controls ---
    fun setProxy(host: String, port: Int)
    fun setProxyActive(active: Boolean)

    // --- Gateway Controls ---
    fun setTorGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)
    fun getTorGatewayStatus(): Boolean
    fun setInternetGatewayEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)
    fun getInternetGatewayStatus(): Boolean
    fun getGatewayStatus(): Boolean

    // --- V3: Gateway Preference Controls ---
    /**
     * Set the global gateway preference for internet-bound traffic.
     * 
     * **Precedence:** Per-app VPN rules (Orbot SharedPreferences "PrefTord") supersede this preference.
     * 
     * @param preference Gateway routing policy (TOR_ONLY, CLEARNET_ONLY, EITHER)
     * @param callback Result callback (Success if saved, Failure on error)
     */
    fun setGatewayPreference(preference: GatewayPreference, callback: (Result<Unit>) -> Unit)

    /**
     * Get the current global gateway preference.
     * 
     * @return Current gateway preference (TOR_ONLY, CLEARNET_ONLY, or EITHER)
     */
    fun getGatewayPreference(): GatewayPreference

    /**
     * Query current Tor daemon status from Orbot.
     * 
     * **Implementation:** Reads last known status from TorStatusMonitor BroadcastReceiver.
     * 
     * @return true if Tor is running ("ON" status), false otherwise
     */
    fun isTorActive(): Boolean

    // --- Storage Participation ---
    fun setStorageParticipationEnabled(enabled: Boolean, callback: (Result<Unit>) -> Unit)
    fun getStorageParticipationStatus(): Boolean
    fun getAvailableStorageDevices(): List<StorageDeviceDto>
    fun setStorageAllocation(deviceId: String, 
    path: String,
    allocatedMB: Long, )
    fun getStorageAllocations(): List<StorageAllocation>
    fun enableDistributedStorage()
    fun disableDistributedStorage()
    fun isComputeLayerParticipating(): Boolean
    fun setDropFolderPath(path: String) 
    fun getDropFolderPath(): String

    /**
     * Enable or disable the entire compute service (persistent, global).
     */
    fun setComputeLayerParticipatingEnabled(enabled: Boolean)

    // --- Drop Folder Management ---
    fun selectDropFolder(path: String, callback: (Result<Unit>) -> Unit)
    fun getDropFolder(): File?
    fun getDropFolderFiles(): List<File>
    fun setOnDropFolderUpdate(handler: (List<DropFolderItemDto>) -> Unit)

    // --- File Operations ---
    fun storeFile(file: File, recipients:List<RecipientEntryDto>) 
    suspend fun retrieveFile(fileId: String): ByteArray? 
    fun streamFile(fileId: String, callback: (Result<Unit>) -> Unit)
    fun deleteFile(fileId: String, callback: (Result<Unit>) -> Unit)
    fun getAllMeshFiles(): List<MeshFile>

    // --- Distributed Service Layer ---
    fun setServiceParticipationEnabled(serviceId: String, enabled: Boolean, callback: (Result<Unit>) -> Unit)
    fun getAvailableServices(): List<String>
    fun getServiceParticipationStatus(serviceId: String): Boolean

    // --- Compute/Task Operations ---
    fun addTask(requestParams: Map<String, Any>): ApiResult
    fun startTask(taskId: String, callback: (Result<Unit>) -> Unit)
    fun cancelTask(taskId: String, callback: (Result<Unit>) -> Unit)
    
    // UNUSED SCHEDULER API - Commented 2025-11-12
    // These methods reference scheduler types (ExecutionPlan, ComputeTask) that are unused in Phase 3-4
    // Phase 3-4 uses IntelligentDistributedComputeService.processTaskRequest() with direct node selection
    // If scheduler needed in future, uncomment these and restore ExecutionPlan/ComputeTask imports
    // fun getTaskStatus(taskId: String): ExecutionPlan?
    // fun getAllTasks(): List<ComputeTask>
    
    // DEPRECATED 2025-12-06: getJobTypes() removed
    // JobType is for ServiceLibraryEntry categorization only, not for API-level task validation
    // Use taskType (execution engine: python, jvm, js, ml-native) for task submission

    // --- Event Registration ---
    fun setOnFileRetrieved(handler: (fileId: String, file: File) -> Unit)
    fun setOnFileStored(handler: (fileId: String, file: File, result: Result<String>) -> Unit)
    fun setOnPermissionUpdated(handler: (fileId: String, success: Boolean) -> Unit)
    fun setOnOperationFailed(handler: (operation: String, error: Throwable) -> Unit)
    
    // UNUSED SCHEDULER API - Commented 2025-11-12
    // This callback uses ExecutionPlan type from unused scheduler infrastructure
    // fun setOnTaskCompleted(handler: (taskId: String, result: ExecutionPlan) -> Unit)
    
    fun setOnFileShared(handler: (fileId: String, recipientId: String) -> Unit)
    fun setOnFileAddedToDropFolder(handler: (fileId: String, file: File) -> Unit)

    // --- Settings and State ---
    fun getSettings(): Map<String, Any>
    fun setSetting(key: String, value: Any, callback: (Result<Unit>) -> Unit)

    // --- Service Bundle & Gateway Controls ---
    // fun announceService(serviceAnnouncement: ServiceAnnouncement, signedBundle: ByteArray, callback: (Result<Unit>) -> Unit)
    // fun requestServiceBundle(serviceId: String, requesterOnionAddress: String, callback: (Result<ByteArray?>) -> Unit)
    fun setOnGatewayTraffic(handler: (packet: VirtualPacket) -> Boolean)
    fun getMeshTrafficRouterStatus(): String

    // --- Event/Callback Integration ---
    fun setOnMeshStateChanged(handler: (newState: MeshState) -> Unit)
    fun setOnPeerCountChanged(handler: (newCount: Int) -> Unit)
    // fun setOnServiceBundleReceived(handler: (serviceId: String, bundle: ByteArray) -> Unit)
    // fun setOnServiceAnnounced(handler: (serviceId: String, announcement: ServiceAnnouncement) -> Unit)
    fun setOnGossipMessage(handler: (senderId: Int, messageBytes: ByteArray) -> Unit)
    
    /**
     * Section 9: Task status update callback
     * Invoked when a distributed compute task changes status.
     * 
     * @param handler Callback function receiving taskId and status string
     */
    fun setOnTaskStatusUpdate(handler: (taskId: String, status: String) -> Unit)

    /**
     * Returns whether the given TaskType is enabled for compute participation.
     */
    fun isTaskTypeEnabled(taskType: TaskType): Boolean

    /**
     * Sets enabled status for a TaskType (persistently).
     */
    fun setTaskTypeEnabled(taskType: TaskType, enabled: Boolean)

    /**
     * Returns a map of all TaskTypes and their enabled status.
     */
    fun getAllTaskTypeEnabled(): Map<TaskType, Boolean>
    // --- User Identity API ---
    /**
     * Returns current user info (userId, publicKey, nickname).
     */
    fun getUserInfo(): com.ustadmobile.meshrabiya.model.User

    /**
     * Sets the user's nickname (persistent).
     */
    fun setUserNickname(nickname: String)

    /**
     * Rotates the user's keypair and updates userId/publicKey.
     */
    fun rotateUserKey(): com.ustadmobile.meshrabiya.model.User
}


data class DropFolderItemDto(
    val itemRelativePath: String,
    val isFolder: Boolean,
    // val children: List<DropFolderItemDto> = emptyList(),
    val trigger: StoreFileTriggerDto? = null
)





@Serializable
data class RecipientEntryDto(
    val publicKey: String,
    val recipientType: RecipientTypeDto, // Use String for enum in DTO
    val recipientId: String,
    val expiresAt: Long? = null
)

@Serializable
enum class RecipientTypeDto {
    /**
     * Long-lived user/node keypairs.
     * Used for persistent node identities and user accounts.
     * Never expires automatically.
     */
    USER,
    
    /**
     * Ephemeral task keypairs.
     * Generated per-task, expires after task completion or timeout.
     * Provides task-level data isolation from compute node operators.
     */
    TASK
}

@Serializable
data class StoreFileTriggerDto(
    val id: Int,
    val subPath: String, 
    val recipients: List<RecipientEntryDto>,
    
)

data class StorageAllocationDto(
    val deviceId: String,
    val path: String,
    val allocatedMB: Long,
    
    // val enabled: Boolean
)

enum class StorageDeviceTypeDto{
    INTERNAL,
    EXTERNAL,
    USB
}

data class StorageDeviceDto(
    val id: String,
    val name: String,
    val path: String,
    val availableSpaceGB: Float,
    val totalSpaceGB: Float,
    val type: StorageDeviceTypeDto
)
fun StorageDeviceDto.getFormattedAvailableSpace(): String {
    return when {
        availableSpaceGB >= 1024 -> "${(availableSpaceGB / 1024).toInt()} TB"
        availableSpaceGB >= 1 -> "${availableSpaceGB.toInt()} GB"
        else -> "${(availableSpaceGB * 1024).toInt()} MB"
    }
}