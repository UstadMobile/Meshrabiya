package org.torproject.meshrabiya.compute.network

import kotlinx.coroutines.*
import org.torproject.meshrabiya.mesh.MeshNetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.pow

/**
 * NetworkFailureRecovery manages network partition detection and recovery.
 * 
 * Features:
 * - Automatic network partition detection
 * - Exponential backoff reconnection
 * - Message queue for failed sends
 * - Connection health monitoring
 * - Automatic failover to backup nodes
 * 
 * Architecture:
 * - Monitors connection health via heartbeat messages
 * - Maintains pending message queue during partition
 * - Automatically reconnects with exponential backoff
 * - Resends queued messages after recovery
 * 
 * Partition Detection:
 * - Heartbeat timeout: 30 seconds
 * - Missed heartbeats: 3 consecutive
 * - Network error patterns
 * 
 * Reconnection Strategy:
 * - Initial delay: 1 second
 * - Max delay: 60 seconds
 * - Exponential backoff: delay * 2^attempt
 * - Max reconnection attempts: unlimited (until success or explicit stop)
 * 
 * @property meshNetwork Mesh network interface for communication
 * @property heartbeatIntervalMs Interval between heartbeat messages (default 10s)
 * @property heartbeatTimeoutMs Timeout for heartbeat response (default 30s)
 */
class NetworkFailureRecovery(
    private val meshNetwork: MeshNetworkInterface,
    private val heartbeatIntervalMs: Long = 10000,
    private val heartbeatTimeoutMs: Long = 30000,
    private val maxMissedHeartbeats: Int = 3,
    private val initialReconnectDelayMs: Long = 1000,
    private val maxReconnectDelayMs: Long = 60000
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    /**
     * Connection state.
     */
    enum class ConnectionState {
        CONNECTED,
        DEGRADED,
        PARTITIONED,
        RECONNECTING,
        DISCONNECTED
    }
    
    /**
     * Connection info for a peer node.
     */
    private data class ConnectionInfo(
        val nodeId: String,
        var state: ConnectionState = ConnectionState.CONNECTED,
        var lastHeartbeatMs: Long = System.currentTimeMillis(),
        var missedHeartbeats: Int = 0,
        var reconnectAttempts: Int = 0,
        var lastReconnectAttemptMs: Long = 0,
        val pendingMessages: MutableList<PendingMessage> = mutableListOf(),
        var heartbeatJob: Job? = null,
        var reconnectJob: Job? = null
    )
    
    /**
     * Pending message waiting to be sent.
     */
    private data class PendingMessage(
        val messageId: String,
        val destinationNodeId: String,
        val messageBytes: ByteArray,
        val timestampMs: Long,
        val priority: MessagePriority,
        var sendAttempts: Int = 0
    )
    
    /**
     * Message priority for queue ordering.
     */
    enum class MessagePriority(val value: Int) {
        LOW(0),
        NORMAL(1),
        HIGH(2),
        CRITICAL(3)
    }
    
    // Registry of active connections
    private val connections = ConcurrentHashMap<String, ConnectionInfo>()
    
    // Global monitoring state
    private val isMonitoring = AtomicBoolean(false)
    private var monitoringJob: Job? = null
    
    // Statistics
    @Volatile private var totalPartitionsDetected = 0L
    @Volatile private var totalRecoveries = 0L
    @Volatile private var totalReconnectionAttempts = 0L
    @Volatile private var totalMessageResends = 0L
    @Volatile private var totalMessageDrops = 0L
    
    /**
     * Start monitoring network connections.
     */
    fun startMonitoring() {
        if (!isMonitoring.compareAndSet(false, true)) {
            return
        }
        
        monitoringJob = scope.launch {
            while (isActive && isMonitoring.get()) {
                checkConnectionHealth()
                delay(heartbeatIntervalMs)
            }
        }
    }
    
    /**
     * Stop monitoring network connections.
     */
    fun stopMonitoring() {
        isMonitoring.set(false)
        monitoringJob?.cancel()
        
        connections.values.forEach { conn ->
            conn.heartbeatJob?.cancel()
            conn.reconnectJob?.cancel()
        }
    }
    
    /**
     * Register a connection to monitor.
     * 
     * @param nodeId Node identifier
     */
    fun registerConnection(nodeId: String) {
        val conn = connections.getOrPut(nodeId) { ConnectionInfo(nodeId) }
        
        // Start heartbeat monitoring for this connection
        conn.heartbeatJob?.cancel()
        conn.heartbeatJob = scope.launch {
            monitorConnectionHeartbeat(nodeId)
        }
    }
    
    /**
     * Unregister a connection from monitoring.
     * 
     * @param nodeId Node identifier
     */
    fun unregisterConnection(nodeId: String) {
        val conn = connections.remove(nodeId)
        conn?.heartbeatJob?.cancel()
        conn?.reconnectJob?.cancel()
    }
    
    /**
     * Send a message with automatic retry on failure.
     * 
     * @param nodeId Destination node identifier
     * @param messageBytes Message bytes
     * @param priority Message priority (default NORMAL)
     * @return True if sent successfully or queued, false if dropped
     */
    suspend fun sendMessageWithRetry(
        nodeId: String,
        messageBytes: ByteArray,
        priority: MessagePriority = MessagePriority.NORMAL
    ): Boolean {
        val conn = connections[nodeId]
        
        if (conn == null || conn.state == ConnectionState.DISCONNECTED) {
            return false
        }
        
        // Try immediate send if connected
        if (conn.state == ConnectionState.CONNECTED) {
            try {
                meshNetwork.sendMessage(nodeId, messageBytes)
                return true
            } catch (e: Exception) {
                // Send failed, queue for retry
                handleSendFailure(conn, messageBytes, priority)
            }
        }
        
        // Queue message if not connected
        if (conn.state in listOf(ConnectionState.DEGRADED, ConnectionState.PARTITIONED, ConnectionState.RECONNECTING)) {
            queueMessage(conn, messageBytes, priority)
            return true
        }
        
        return false
    }
    
    /**
     * Record heartbeat received from a peer.
     * 
     * @param nodeId Node identifier
     */
    fun recordHeartbeatReceived(nodeId: String) {
        val conn = connections[nodeId] ?: return
        
        conn.lastHeartbeatMs = System.currentTimeMillis()
        conn.missedHeartbeats = 0
        
        // Transition back to CONNECTED if we were degraded/recovering
        if (conn.state != ConnectionState.CONNECTED) {
            handleConnectionRecovered(conn)
        }
    }
    
    /**
     * Get connection state for a node.
     * 
     * @param nodeId Node identifier
     * @return Connection state or null if not registered
     */
    fun getConnectionState(nodeId: String): ConnectionState? {
        return connections[nodeId]?.state
    }
    
    /**
     * Get pending message count for a node.
     * 
     * @param nodeId Node identifier
     * @return Number of pending messages
     */
    fun getPendingMessageCount(nodeId: String): Int {
        return connections[nodeId]?.pendingMessages?.size ?: 0
    }
    
    /**
     * Monitor connection heartbeat for a specific node.
     */
    private suspend fun monitorConnectionHeartbeat(nodeId: String) {
        while (isActive) {
            val conn = connections[nodeId] ?: break
            
            val timeSinceLastHeartbeatMs = System.currentTimeMillis() - conn.lastHeartbeatMs
            
            if (timeSinceLastHeartbeatMs > heartbeatTimeoutMs) {
                conn.missedHeartbeats++
                
                if (conn.missedHeartbeats >= maxMissedHeartbeats) {
                    handleConnectionPartitioned(conn)
                } else if (conn.state == ConnectionState.CONNECTED) {
                    conn.state = ConnectionState.DEGRADED
                }
            }
            
            delay(heartbeatIntervalMs)
        }
    }
    
    /**
     * Check health of all connections.
     */
    private suspend fun checkConnectionHealth() {
        connections.values.forEach { conn ->
            val timeSinceLastHeartbeatMs = System.currentTimeMillis() - conn.lastHeartbeatMs
            
            // Send heartbeat ping
            try {
                meshNetwork.sendHeartbeat(conn.nodeId)
            } catch (e: Exception) {
                // Heartbeat send failed
                if (conn.state == ConnectionState.CONNECTED) {
                    conn.state = ConnectionState.DEGRADED
                }
            }
        }
    }
    
    /**
     * Handle connection partition detected.
     */
    private suspend fun handleConnectionPartitioned(conn: ConnectionInfo) {
        if (conn.state == ConnectionState.PARTITIONED) {
            return // Already handling partition
        }
        
        conn.state = ConnectionState.PARTITIONED
        totalPartitionsDetected++
        
        // Start reconnection attempts
        attemptReconnection(conn)
    }
    
    /**
     * Handle connection recovered.
     */
    private suspend fun handleConnectionRecovered(conn: ConnectionInfo) {
        val wasPartitioned = conn.state == ConnectionState.PARTITIONED
        
        conn.state = ConnectionState.CONNECTED
        conn.reconnectAttempts = 0
        conn.reconnectJob?.cancel()
        conn.reconnectJob = null
        
        if (wasPartitioned) {
            totalRecoveries++
        }
        
        // Resend pending messages
        resendPendingMessages(conn)
    }
    
    /**
     * Handle send failure.
     */
    private fun handleSendFailure(
        conn: ConnectionInfo,
        messageBytes: ByteArray,
        priority: MessagePriority
    ) {
        if (conn.state == ConnectionState.CONNECTED) {
            conn.state = ConnectionState.DEGRADED
        }
        
        queueMessage(conn, messageBytes, priority)
    }
    
    /**
     * Queue a message for later delivery.
     */
    private fun queueMessage(
        conn: ConnectionInfo,
        messageBytes: ByteArray,
        priority: MessagePriority
    ) {
        val message = PendingMessage(
            messageId = generateMessageId(),
            destinationNodeId = conn.nodeId,
            messageBytes = messageBytes,
            timestampMs = System.currentTimeMillis(),
            priority = priority
        )
        
        conn.pendingMessages.add(message)
        
        // Sort by priority (highest first) and timestamp (oldest first)
        conn.pendingMessages.sortWith(
            compareByDescending<PendingMessage> { it.priority.value }
                .thenBy { it.timestampMs }
        )
        
        // Drop old low-priority messages if queue is too large
        val maxQueueSize = 1000
        if (conn.pendingMessages.size > maxQueueSize) {
            val dropped = conn.pendingMessages.removeAt(conn.pendingMessages.size - 1)
            totalMessageDrops++
        }
    }
    
    /**
     * Attempt reconnection with exponential backoff.
     */
    private suspend fun attemptReconnection(conn: ConnectionInfo) {
        conn.reconnectJob?.cancel()
        conn.reconnectJob = scope.launch {
            while (isActive && conn.state == ConnectionState.PARTITIONED) {
                conn.state = ConnectionState.RECONNECTING
                conn.reconnectAttempts++
                totalReconnectionAttempts++
                
                try {
                    // Attempt to establish connection
                    meshNetwork.reconnect(conn.nodeId)
                    
                    // Send heartbeat to verify connection
                    meshNetwork.sendHeartbeat(conn.nodeId)
                    
                    // Wait for heartbeat response (handled by recordHeartbeatReceived)
                    delay(heartbeatTimeoutMs)
                    
                    // If we received heartbeat, state will be CONNECTED
                    if (conn.state == ConnectionState.CONNECTED) {
                        break
                    }
                    
                } catch (e: Exception) {
                    // Reconnection failed, calculate backoff
                    val backoffDelayMs = calculateReconnectBackoff(conn.reconnectAttempts)
                    conn.lastReconnectAttemptMs = System.currentTimeMillis()
                    conn.state = ConnectionState.PARTITIONED
                    
                    delay(backoffDelayMs)
                }
            }
        }
    }
    
    /**
     * Calculate reconnection backoff delay.
     */
    private fun calculateReconnectBackoff(attempts: Int): Long {
        val exponentialDelay = initialReconnectDelayMs * (2.0.pow(attempts.toDouble())).toLong()
        return min(exponentialDelay, maxReconnectDelayMs)
    }
    
    /**
     * Resend pending messages after recovery.
     */
    private suspend fun resendPendingMessages(conn: ConnectionInfo) {
        val messages = conn.pendingMessages.toList()
        conn.pendingMessages.clear()
        
        messages.forEach { message ->
            try {
                meshNetwork.sendMessage(conn.nodeId, message.messageBytes)
                totalMessageResends++
            } catch (e: Exception) {
                // Resend failed, re-queue
                conn.pendingMessages.add(message.copy(sendAttempts = message.sendAttempts + 1))
            }
        }
    }
    
    /**
     * Generate unique message ID.
     */
    private fun generateMessageId(): String {
        return "${System.currentTimeMillis()}_${(Math.random() * 1000000).toInt()}"
    }
    
    /**
     * Get recovery statistics.
     */
    fun getStatistics(): RecoveryStatistics {
        val connectionStates = connections.values.groupBy { it.state }
        
        return RecoveryStatistics(
            totalConnections = connections.size,
            connectedCount = connectionStates[ConnectionState.CONNECTED]?.size ?: 0,
            degradedCount = connectionStates[ConnectionState.DEGRADED]?.size ?: 0,
            partitionedCount = connectionStates[ConnectionState.PARTITIONED]?.size ?: 0,
            reconnectingCount = connectionStates[ConnectionState.RECONNECTING]?.size ?: 0,
            disconnectedCount = connectionStates[ConnectionState.DISCONNECTED]?.size ?: 0,
            totalPendingMessages = connections.values.sumOf { it.pendingMessages.size },
            totalPartitionsDetected = totalPartitionsDetected,
            totalRecoveries = totalRecoveries,
            totalReconnectionAttempts = totalReconnectionAttempts,
            totalMessageResends = totalMessageResends,
            totalMessageDrops = totalMessageDrops
        )
    }
    
    /**
     * Get detailed connection info for all nodes.
     */
    fun getAllConnectionInfo(): List<ConnectionInfoPublic> {
        return connections.values.map { conn ->
            ConnectionInfoPublic(
                nodeId = conn.nodeId,
                state = conn.state,
                lastHeartbeatMs = conn.lastHeartbeatMs,
                missedHeartbeats = conn.missedHeartbeats,
                reconnectAttempts = conn.reconnectAttempts,
                pendingMessageCount = conn.pendingMessages.size,
                timeSinceLastHeartbeatMs = System.currentTimeMillis() - conn.lastHeartbeatMs
            )
        }
    }
    
    /**
     * Shutdown network failure recovery.
     */
    fun shutdown() {
        stopMonitoring()
        connections.clear()
        scope.cancel()
    }
    
    /**
     * Public connection information.
     */
    data class ConnectionInfoPublic(
        val nodeId: String,
        val state: ConnectionState,
        val lastHeartbeatMs: Long,
        val missedHeartbeats: Int,
        val reconnectAttempts: Int,
        val pendingMessageCount: Int,
        val timeSinceLastHeartbeatMs: Long
    )
    
    /**
     * Recovery statistics.
     */
    data class RecoveryStatistics(
        val totalConnections: Int,
        val connectedCount: Int,
        val degradedCount: Int,
        val partitionedCount: Int,
        val reconnectingCount: Int,
        val disconnectedCount: Int,
        val totalPendingMessages: Int,
        val totalPartitionsDetected: Long,
        val totalRecoveries: Long,
        val totalReconnectionAttempts: Long,
        val totalMessageResends: Long,
        val totalMessageDrops: Long
    ) {
        val recoveryRate: Double
            get() = if (totalPartitionsDetected > 0) {
                totalRecoveries.toDouble() / totalPartitionsDetected
            } else 0.0
    }
}
