package com.ustadmobile.meshrabiya.service

import com.ustadmobile.meshrabiya.service.MeshEcosystemMessage
import com.ustadmobile.meshrabiya.service.ChunkRetrievalQueryMessage
import com.ustadmobile.meshrabiya.service.ChunkRetrievalResponseMessage
import com.ustadmobile.meshrabiya.service.ReplicaQueryMessage
import com.ustadmobile.meshrabiya.service.ReplicaResponseMessage
import com.ustadmobile.meshrabiya.service.FilePermissionUpdateMessage
import com.ustadmobile.meshrabiya.service.FilePermissionUpdateConfirmationMessage
import com.ustadmobile.meshrabiya.service.EcosystemBroadcastMessage
import com.ustadmobile.meshrabiya.service.TaskCompletedMessage
import com.ustadmobile.meshrabiya.service.TaskScheduledMessage
import com.ustadmobile.meshrabiya.service.TaskAssignmentMessage
import com.ustadmobile.meshrabiya.service.TaskAcceptanceMessage
import com.ustadmobile.meshrabiya.service.TaskCompletionAckMessage
import com.ustadmobile.meshrabiya.service.FileAccessUpdateNotification
import com.ustadmobile.meshrabiya.service.ComputeTaskRequestMessage
import com.ustadmobile.meshrabiya.service.ComputeNodeResponseMessage
import com.ustadmobile.meshrabiya.service.StorageNodeResponseMessage
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
// DEPRECATED: IntelligentDistributedComputeService removed (2025-12-04)
// import com.ustadmobile.meshrabiya.service.compute.IntelligentDistributedComputeService
import com.ustadmobile.meshrabiya.service.compute.DistributedComputeClient
import com.ustadmobile.meshrabiya.service.compute.DistributedComputeServer
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.MeshRole
import com.ustadmobile.meshrabiya.service.compute.model.ComputeNodeResponse
import com.ustadmobile.meshrabiya.service.ChunkTransferMessage
import com.ustadmobile.meshrabiya.service.StorageNodeResponse
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import com.ustadmobile.meshrabiya.storage.RecipientType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.vnet.MeshConnectionPool
import com.ustadmobile.meshrabiya.storage.DistributedStorageClient

/**
 * MeshEcosystemListener: Global listener and router for ALL Distributed Storage & Compute messages.
 * 
 * This is the central entry point for ALL incoming MeshEcosystemMessages (both broadcast and node-to-node).
 * Called directly by VirtualNode.route() when a message arrives on the ecosystem port.
 * 
 * Architecture:
 * - VirtualNode.route() → MeshEcosystemListener.routeMessage() → Domain Managers
 * - Checks node roles (STORAGE_NODE, COMPUTE_NODE) before routing
 * - Extracts requestId and passes to domain managers for their own correlation
 * 
 * Key Responsibilities:
 * - Message type discrimination and routing (single entry point: routeMessage())
 * - Role-based message filtering (only route if appropriate role enabled)
 * - Connection pool management for transfers
 * 
 * Dependencies:
 * - VirtualNode (passed as self - provides access to all services)
 * - DistributedStorageManager (registered handler)
 * - IntelligentDistributedComputeService (registered handler)
 */

class MeshEcosystemListener(
    private val virtualNode: VirtualNode,
    connectionPoolSize: Int = MeshrabiyaConstants.getConnectionPoolSize(),
    
) {
    // Deduplication cache for broadcast messages
    private val seenBroadcasts = mutableSetOf<String>()
    private val broadcastTtlMs: Long = 60_000L
    private val broadcastTimestamps = mutableMapOf<String, Long>()

    private val connectionPool = MeshConnectionPool.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Registered handlers
    private var storageManager: DistributedStorageManager? = null
    // DEPRECATED: computeService removed (2025-12-04) - use computeClient/computeServer directly
    // private var computeService: IntelligentDistributedComputeService? = null
    private var computeClient: DistributedComputeClient? = null
    private var computeServer: DistributedComputeServer? = null

    private val isShutdown = AtomicBoolean(false)
    private var isStorageParticipationEnabled: Boolean = false

    // Controls whether storage node responsibilities are enabled
    var isStorageNodeEnabled: Boolean = true
        set(value) {
            field = value
        }

    fun registerStorageManager(manager: DistributedStorageManager) {
        storageManager = manager
    }

    // DEPRECATED: IntelligentDistributedComputeService removed (2025-12-04)
    // fun registerComputeService(service: IntelligentDistributedComputeService) {
    //     computeService = service
    // }
    
    fun registerComputeClient(client: DistributedComputeClient) {
        computeClient = client
    }
    
    fun registerComputeServer(server: DistributedComputeServer) {
        computeServer = server
    }

    fun setStorageParticipationEnabled(enabled: Boolean) {
        isStorageParticipationEnabled = enabled
    }

    /**
     * MAIN ENTRY POINT: Route incoming MeshEcosystemMessage to appropriate domain manager.
     * 
     * Called by VirtualNode.route() when a message arrives on the ecosystem port.
     * This method:
     * 1. Checks if node has appropriate role (STORAGE_NODE, COMPUTE_NODE) via emergentRoleManager
     * 2. Discriminates message type and extracts requestId when present
     * 3. Routes to appropriate domain manager (DistributedStorageManager or IntelligentDistributedComputeService)
     * 
     * Note: Does NOT call meshGossipService - domain managers handle their own request-response correlation.
     * 
     * @param senderId The virtual address of the node that sent the message
     * @param message The deserialized MeshEcosystemMessage
     */
    fun routeMessage(senderId: Int, message: MeshEcosystemMessage) {
        // Deduplication for broadcast messages (by unique broadcastId or taskId)
        val maybeBroadcastId = when (message) {
            is TaskScheduledMessage -> message.taskId
            is TaskAssignmentMessage -> message.taskId
            is EcosystemBroadcastMessage -> message.broadcastId
            else -> null
        }
        if (maybeBroadcastId != null) {
            val now = System.currentTimeMillis()
            // Clean up old entries
            broadcastTimestamps.entries.removeIf { now - it.value > broadcastTtlMs }
            if (seenBroadcasts.contains(maybeBroadcastId)) {
                // Already seen, skip routing
                return
            } else {
                seenBroadcasts.add(maybeBroadcastId)
                broadcastTimestamps[maybeBroadcastId] = now
            }
        }
        if (isShutdown.get()) {
            return
        }

        // Get current node roles to check if we should handle this message
        val currentRoles: Set<MeshRole> = virtualNode.emergentRoleManager.getCurrentMeshRoles()

        when (message) {
            // === STORAGE RESPONSES (responses to our storage queries) ===
            is StorageNodeResponseMessage -> {
                if (currentRoles.contains(MeshRole.STORAGE_NODE) && isStorageParticipationEnabled) {
                    message.requestId?.let { requestId ->
                        routeStorageNodeResponse(requestId, senderId, message.response)
                    }
                }
            }
            is ChunkRetrievalQueryMessage -> storageManager?.onChunkRetrievalQuery(senderId, message.query)
            is ChunkTransferMessage -> {
                if (message.chunkBytes.isEmpty()) {
                    storageManager?.onChunkDataRequest(senderId, message)
                } else {
                    storageManager?.handleIncomingChunkTransfer(message)
                }
            }
            // is ChunkTransferMessage -> {
            //     storageManager?.handleIncomingChunkTransfer(message)
            // }
            is ChunkRetrievalResponse -> {
                storageManager?.handleChunkRetrievalResponse(
                    requestId = message.requestId,
                    
                    senderId = message.nodeId,
                    response = message
                )
            }
            is ChunkRetrievalResponseMessage -> {
                if (currentRoles.contains(MeshRole.STORAGE_NODE) && isStorageParticipationEnabled) {
                    message.requestId?.let { requestId ->
                        routeChunkRetrievalResponse(requestId, senderId, message.response)
                    }
                }
            }
            

            is ReplicaResponseMessage -> {
                if (currentRoles.contains(MeshRole.STORAGE_NODE) && isStorageParticipationEnabled) {
                    message.requestId?.let { requestId ->
                        routeReplicaResponse(requestId, senderId, message.response)
                    }
                }
            }

            // === COMPUTE RESPONSES (responses to our compute task broadcasts) ===
            is ComputeNodeResponseMessage -> {
                if (currentRoles.contains(MeshRole.COMPUTE_NODE)) {
                    message.requestId?.let { requestId ->
                        // Route to CLIENT (client receives responses from potential compute nodes)
                        computeClient?.handleComputeNodeResponse(message)
                            ?: routeComputeNodeResponse(requestId, senderId, message.response)
                    }
                }
            }

            // === COMPUTE REQUESTS (other nodes broadcasting compute tasks) ===
            is ComputeTaskRequestMessage -> {
                if (currentRoles.contains(MeshRole.COMPUTE_NODE)) {
                    // Route to SERVER (server handles incoming task requests)
                    val requestId = message.taskId
                    scope.launch {
                        computeServer?.handleIncomingComputeTaskRequest(requestId, senderId, message)
                            ?: routeIncomingComputeTaskRequest(requestId, senderId, message)
                    }
                }
            }

            // === STORAGE METADATA/PERMISSIONS & COMPUTE METADATA/PERMISSIONS ===
            is FileAccessUpdateNotification -> {
                // Route to STORAGE for permission tracking
                if (currentRoles.contains(MeshRole.STORAGE_NODE) && isStorageParticipationEnabled) {
                    routePermissionUpdateConfirmation(message)
                }
                
                // Route to COMPUTE SERVER for task data access updates
                scope.launch {
                    computeServer?.handleTaskDataAccessUpdate(message)
                }
            }

            // === COMPUTE TASK LIFECYCLE ===
            is TaskAcceptanceMessage -> {
                // Route to CLIENT (client receives acceptance from compute node)
                computeClient?.handleTaskAcceptanceMessage(message)
            }
            
            is TaskCompletedMessage -> {
                // Route to CLIENT (client receives completion from compute node)
                scope.launch {
                    computeClient?.handleTaskCompletionMessage(message)
                }
                
                // Section 9: Trigger task status callback
                scope.launch {
                    val api = com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl.getInstance()
                    api.triggerTaskStatusUpdate(
                        taskId = message.taskId,
                        status = "COMPLETED"
                    )
                }
            }
            
            is TaskCompletionAckMessage -> {
                // Route to SERVER (server receives ACK from client)
                scope.launch {
                    computeServer?.handleTaskCompletionAckMessage(senderId, message)
                }
            }

            // === DATA TRANSFERS ===
            is ChunkTransferMessage -> {
                // Now expects recipients and owner fields in message
                if (currentRoles.contains(MeshRole.STORAGE_NODE) && isStorageParticipationEnabled) {
                    onChunkTransfer(senderId, message)
                }
            }

            // Handle other message types as they are added
            else -> {
                // Unknown message type - log if needed
            }
        }
    }

    // === Centralized routing methods with requestId correlation ===

    /**
     * Route StorageNodeResponse to DistributedStorageManager.
     * Passes requestId for correlation with pending requests.
     */
    private fun routeStorageNodeResponse(requestId: String, senderId: Int, response: StorageNodeResponse) {
        if (isStorageNodeEnabled) {
            storageManager?.handleStorageNodeResponse(requestId, senderId, response)
        }
    }

    /**
     * Route ChunkRetrievalResponse to DistributedStorageManager.
     * Passes requestId for correlation with pending requests.
     */
    private fun routeChunkRetrievalResponse(requestId: String, senderId: Int, response: ChunkRetrievalResponse) {
        if (isStorageNodeEnabled) {
            storageManager?.handleChunkRetrievalResponse(requestId, senderId, response)
        }
    }

    /**
     * Route ReplicaResponse to DistributedStorageManager.
     * Passes requestId for correlation with pending requests.
     */
    private fun routeReplicaResponse(requestId: String, senderId: Int, response: ReplicaResponse) {
        if (isStorageNodeEnabled) {
            storageManager?.handleReplicaResponse(requestId, senderId, response)
        }
    }

    /**
     * Route ComputeNodeResponse to DistributedComputeClient (DEPRECATED computeService removed).
     * Passes requestId for correlation with pending compute tasks.
     */
    private fun routeComputeNodeResponse(requestId: String, senderId: Int, response: ComputeNodeResponse) {
        // DEPRECATED: computeService removed - routing handled by computeClient directly in handleMessage
        // This fallback is kept for backward compatibility but should not be called
    }

    /**
     * Route incoming ComputeTaskRequest to DistributedComputeServer (DEPRECATED computeService removed).
     * This is called when another node broadcasts a compute task request to the mesh.
     * The local node evaluates whether it can handle the task and sends a response.
     */
    private suspend fun routeIncomingComputeTaskRequest(
        requestId: String, 
        requesterNodeAddress: Int, 
        request: ComputeTaskRequestMessage
    ) {
        // DEPRECATED: computeService removed - routing handled by computeServer directly in handleMessage
        // This fallback is kept for backward compatibility but should not be called
    }

    /**
     * Route permission update confirmation to DistributedStorageManager.
     */
    private fun routePermissionUpdateConfirmation(confirmation: FilePermissionUpdateConfirmationMessage) {
        if (isStorageNodeEnabled) {
            storageManager?.handlePermissionUpdateConfirmation(confirmation)
        }
    }

    /**
     * Handles inbound chunk/file transfer events.
     * Acquires connection from pool, processes transfer, releases connection.
     * Only processes if storage participation is enabled.
     */
    fun onChunkTransfer(senderId: Int, chunk: ChunkTransferMessage) {
        scope.launch {
            val connection = try {
                connectionPool.acquireConnection(timeoutMs = 5000)
            } catch (e: Exception) {
                null
            }
            if (connection != null) {
                try {
                    if (isStorageParticipationEnabled) {
                        // Now expects recipients and owner fields in chunk
                        storageManager?.handleIncomingChunkTransfer(senderId, chunk)
                    }
                } finally {
                    connectionPool.releaseConnection(connection)
                }
            }
        }
    }

    // === Connection Pool API for Sender-Side Transfers ===

    /**
     * Acquire a connection for sender-side chunk/file transfer.
     * Automatically releases connection after action completes.
     */
    suspend fun withConnectionForTransfer(action: suspend (MeshConnectionPool.Connection) -> Unit) {
        val connection = try {
            connectionPool.acquireConnection(timeoutMs = 5000)
        } catch (e: Exception) {
            null
        }
        if (connection != null) {
            try {
                action(connection)
            } finally {
                connectionPool.releaseConnection(connection)
            }
        } else {
            // Optionally log: "No available connection for sender-side transfer"
        }
    }

    // === Connection Pool Diagnostic Methods ===

    fun availableConnections(): Int = connectionPool.availableConnections()
    fun totalConnectionCount(): Int = connectionPool.totalConnectionCount()
    fun maxPoolSize(): Int = connectionPool.maxPoolSize()

    // === Shutdown ===

    fun shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            scope.cancel()
        }
    }
}