package com.ustadmobile.meshrabiya.storage

import android.content.Context
import com.ustadmobile.meshrabiya.service.MeshGossipService
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.MeshRoleManager
import com.ustadmobile.meshrabiya.vnet.StorageNodeRequest
import com.ustadmobile.meshrabiya.vnet.StorageNodeResponse
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * ReplicationManager - Handles distributed chunk/file replication according to STORAGE_LIFECYCLE.md.
 * Ensures chunk-level replication, replica count enforcement, error handling, and completion notification.
 */
class ReplicationManager(
    private val context: Context,
    private val virtualNode: VirtualNode,
    private val meshRoleManager: MeshRoleManager,
    private val dataStore: DataStore,
    private val scheduledExecutorService: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
) {

    private val meshGossipService: MeshGossipService = MeshGossipService.getInstance(
        virtualNode = virtualNode,
        meshRoleManager = meshRoleManager,
        context = context,
        dataStore = dataStore,
        scheduledExecutorService = scheduledExecutorService
    )

    /**
     * Replicate all chunks of a file to reach the desired replica count.
     * Conforms to STORAGE_LIFECYCLE.md section 8.
     */
    fun replicateFile(fileId: String, file: File) {
        CoroutineScope(Dispatchers.IO).launch {
            val replicaTarget = MeshrabiyaConstants.getReplicaCount()
            val chunks = dataStore.getChunksForFile(fileId)
            for (chunk in chunks) {
                meshGossipService.queryFileReplicas(chunk.chunkId, timeoutMs = 3000) { replicaNodes ->
                    val currentReplicas = replicaNodes.size
                    if (currentReplicas >= replicaTarget) return@queryFileReplicas

                    val request = StorageNodeRequest(chunk.chunkSize, chunk.fileName, chunk.fileId)
                    meshGossipService.broadcastStorageNodeRequest(request, timeoutMs = 5000) { candidates ->
                        val replicasNeeded = replicaTarget - currentReplicas
                        val selectedNodes = candidates
                            .filter { it.availableSpace >= chunk.chunkSize }
                            .sortedWith(compareBy({ -it.availableSpace }, { it.latency }))
                            .take(replicasNeeded)

                        selectedNodes.forEach { node ->
                            meshGossipService.sendChunk(
                                destinationUrl = node.url,
                                chunkId = chunk.chunkId,
                                fileId = chunk.fileId,
                                chunkIndex = chunk.chunkIndex,
                                totalChunks = chunk.totalChunks,
                                fileName = chunk.fileName,
                                relativePath = chunk.relativePath,
                                chunkBytes = readChunkBytes(chunk),
                                onComplete = { result ->
                                    if (result.success) {
                                        println("Replication to ${node.nodeId} succeeded for chunk ${chunk.chunkId}")
                                    } else {
                                        println("Replication to ${node.nodeId} failed for chunk ${chunk.chunkId}")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Reads the chunk bytes from storage.
     * Assumes chunk files are stored in shared_storage/{fileId}/{relativePath}/{chunkId}.chunk
     */
    private fun readChunkBytes(chunk: MeshChunk): ByteArray {
        val chunkFile = File(
            File(context.filesDir, "shared_storage/${chunk.fileId}/${chunk.relativePath}"),
            "${chunk.chunkId}.chunk"
        )
        return chunkFile.readBytes()
    }

    // No singleton instance; use DI or explicit construction
    companion object {}

    /**
     * Replicate a single chunk to a specific node.
     * Used for direct chunk replication if needed.
     */
    fun sendChunkToNode(chunk: MeshChunk, nodeUrl: String, onComplete: (Boolean) -> Unit) {
        meshGossipService.sendChunk(
            destinationUrl = nodeUrl,
            chunkId = chunk.chunkId,
            fileId = chunk.fileId,
            chunkIndex = chunk.chunkIndex,
            totalChunks = chunk.totalChunks,
            fileName = chunk.fileName,
            relativePath = chunk.relativePath,
            chunkBytes = readChunkBytes(chunk)
        ) { result ->
            onComplete(result.success)
        }
    }

    /**
     * Query the replica count for a given chunkId.
     * Conforms to STORAGE_LIFECYCLE.md section 9.
     */
    fun queryReplicaCount(chunkId: String): Int {
        var replicaCount = 0
        runBlocking {
            replicaCount = suspendCoroutine { cont ->
                meshGossipService.queryFileReplicas(chunkId, timeoutMs = 3000) { replicaNodes ->
                    cont.resume(replicaNodes.size)
                }
            }
        }
        return replicaCount
    }
}