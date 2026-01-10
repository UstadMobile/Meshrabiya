package com.ustadmobile.meshrabiya.vnet
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * MeshConnectionPool - Thread-safe connection pool for mesh network operations.
 * Provides robust acquire/release APIs and pool monitoring.
 *
 * Usage:
 *   val pool = MeshConnectionPool.getInstance()
 *   val conn = pool.acquireConnection()
 *   // ... use conn ...
 *   pool.releaseConnection(conn)
 */
class MeshConnectionPool constructor(private val virtualNode: VirtualNode) {

    companion object {
        @Volatile private var instance: MeshConnectionPool? = null
        fun init(virtualNode: VirtualNode) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = MeshConnectionPool(virtualNode)
                    }
                }
            }
        }
        fun getInstance(): MeshConnectionPool {
            return instance ?: throw IllegalStateException("MeshConnectionPool must be initialized with init(virtualNode) before use.")
        }
    }

    private val connectionQueue = ConcurrentLinkedQueue<Connection>()
    private val totalConnections = AtomicInteger(0)
    private val maxPoolSize = MeshrabiyaConstants.getConnectionPoolSize()

    init {
        repeat(maxPoolSize) {
            connectionQueue.add(Connection(virtualNode))
            totalConnections.incrementAndGet()
        }
    }

    /**
     * Acquire a connection from the pool.
     * Blocks if no connection is available, unless timeoutMs is specified.
     */
    @Throws(InterruptedException::class)
    fun acquireConnection(timeoutMs: Long = 0): Connection {
        val startTime = System.currentTimeMillis()
        while (true) {
            val conn = connectionQueue.poll()
            if (conn != null) {
                return conn
            }
            if (timeoutMs > 0 && System.currentTimeMillis() - startTime > timeoutMs) {
                throw RuntimeException("Timeout waiting for connection from pool")
            }
            Thread.sleep(10)
        }
    }

    /**
     * Release a connection back to the pool.
     */
    fun releaseConnection(connection: Connection) {
        connectionQueue.offer(connection)
    }

    /**
     * Returns the current number of available connections in the pool.
     */
    fun availableConnections(): Int = connectionQueue.size

    /**
     * Returns the total number of connections managed by the pool.
     */
    fun totalConnectionCount(): Int = totalConnections.get()

    /**
     * Returns the maximum pool size.
     */
    fun maxPoolSize(): Int = maxPoolSize

    /**
     * Connection abstraction for pooled communication.
     * Wraps VirtualNode for mesh operations.
     */
    class Connection(val virtualNode: VirtualNode) {
        // Add any connection-specific state or methods here if needed.
    }
}