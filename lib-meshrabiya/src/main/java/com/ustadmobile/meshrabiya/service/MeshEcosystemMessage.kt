package com.ustadmobile.meshrabiya.service

import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import com.ustadmobile.meshrabiya.service.AccessType
import com.ustadmobile.meshrabiya.service.compute.model.ComputeNodeResponse
import com.ustadmobile.meshrabiya.storage.RecipientEntry
import com.ustadmobile.meshrabiya.storage.RecipientType
import org.msgpack.core.MessagePacker
import org.msgpack.core.MessageUnpacker
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind

import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageBufferPacker
import com.ustadmobile.meshrabiya.service.compute.model.ResourceLimits


// --- AnySerializer for kotlinx.serialization ---
object AnySerializer : KSerializer<Any> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Any", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Any) {
        encoder.encodeString(value.toString())
    }
    override fun deserialize(decoder: Decoder): Any = decoder.decodeString()
}
// ===== DATA STRUCTURES FOR ECOSYSTEM MESSAGES =====

/**
 * Storage node request for chunk storage in distributed network.
 * Updated for canonical workflow compliance.
 */
data class StorageNodeRequest(
    val requestId: String,
    val chunkId: String,
    val chunkIndex: Int,
    val fileId: String,
    val chunkSizeBytes: Long,
    val replicaCount: Int = 0,
    // Legacy fields for backward compatibility
    val requiredSpace: Long = chunkSizeBytes,
    val fileName: String = "",
    val senderId: Int = 0
)

/**
 * Storage node response with capacity and service key.
 * Updated for canonical workflow compliance.
 */
data class StorageNodeResponse(
    val nodeId: Int,
    val availableSpace: Long,
    val totalStorageAllocated: Long = 0L,
    val systemState: String,
    val url: String,
    val latency: Int,
    val fitnessScore: Float,
    val fileId: String = "",
    val servicePublicKey: ByteArray = ByteArray(0)
)

/**
 * Query to retrieve chunk information from storage nodes.
 * Updated for canonical workflow retry support.
 */
data class ChunkRetrievalQuery(
    val fileId: String,
    val chunkIndexes: List<Int>? = null,
    // val recipients: List<RecipientEntry> = emptyList(),
    // val sessionKeys: Map<String, ByteArray> = emptyMap(),
    val owner: RecipientEntry,
    val senderId: Int,
    val requestId: String = ""
)

/**
 * Response containing chunk retrieval metadata from storage nodes.
 */
data class ChunkRetrievalResponse(
    val chunkId: String, // TODO should possibly be a list of chunkIds for all the chunks present
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val nodeId: Int,
    val fileName: String,
    val relativePath: String,
    val chunkSize: Long,
    val requestId: String ,
    
)

/**
 * Query to check replica status in distributed storage.
 */
data class ReplicaQuery(
    val fileId: String
)

/**
 * Response containing replica status information.
 */
data class ReplicaResponse(
    val fileId: String
)





// ===== MESSAGE TYPE REGISTRATION & ENUMERATION =====

/**
 * Enum of all canonical MeshEcosystemMessage types for registration and handler logic.
 * Used for message dispatch, handler registration, and type safety.
 */
enum class MessageType(val type: String) {
    STORAGE_NODE_REQUEST("StorageNodeRequest"),
    STORAGE_NODE_RESPONSE("StorageNodeResponse"),
    CHUNK_TRANSFER("ChunkTransfer"),
    CHUNK_RETRIEVAL_QUERY("ChunkRetrievalQuery"),
    CHUNK_RETRIEVAL_RESPONSE("ChunkRetrievalResponse"),
    REPLICA_QUERY("ReplicaQuery"),
    REPLICA_RESPONSE("ReplicaResponse"),
    FILE_PERMISSION_UPDATE("FilePermissionUpdateMessage"),
    FILE_PERMISSION_UPDATE_CONFIRMATION("FilePermissionUpdateConfirmation"),
    ECOSYSTEM_BROADCAST("EcosystemBroadcast"),
    COMPUTE_TASK_REQUEST("ComputeTaskRequest"),
    COMPUTE_NODE_RESPONSE("ComputeNodeResponse"),
    TASK_COMPLETED("TaskCompleted"),
    TASK_SCHEDULED("TaskScheduled"),
    TASK_ASSIGNMENT("TaskAssignment");

    companion object {
        private val map = values().associateBy(MessageType::type)
        // --- Helper methods for recipients/owner serialization ---
        fun packRecipientEntry(packer: MessagePacker, entry: RecipientEntry) {
            packer.packString(entry.publicKey)
            packer.packString(entry.recipientType.name)
            packer.packString(entry.recipientId)
            if (entry.expiresAt != null) {
                packer.packBoolean(true)
                packer.packLong(entry.expiresAt)
            } else {
                packer.packBoolean(false)
            }
        }

        fun unpackRecipientEntry(unpacker: MessageUnpacker): RecipientEntry {
            val publicKey = unpacker.unpackString()
            val recipientType = RecipientType.valueOf(unpacker.unpackString())
            val recipientId = unpacker.unpackString()
            val expiresAt = if (unpacker.unpackBoolean()) unpacker.unpackLong() else null
            return RecipientEntry(publicKey, recipientType, recipientId, expiresAt)
        }
        fun fromType(type: String): MessageType? = map[type]
    }
}

// ===== MESH ECOSYSTEM MESSAGE BASE CLASS =====

/**
 * Canonical Mesh Ecosystem Message class for all mesh-wide ecosystem events, including broadcast.
 * Provides serialization/deserialization and VirtualPacket conversion.
 */
sealed class MeshEcosystemMessage(
    val type: String
) {
    abstract fun toBytes(): ByteArray

    fun toVirtualPacket(
        toAddr: Int,
        fromAddr: Int,
        toPort: Int,
        fromPort: Int,
        lastHopAddr: Int = 0,
        hopCount: Byte = 0,
        payloadOffset: Int = VirtualPacketHeader.HEADER_SIZE
    ): VirtualPacket {
        val payload = toBytes()
        val packetData = ByteArray(payload.size + VirtualPacketHeader.HEADER_SIZE)
        System.arraycopy(payload, 0, packetData, VirtualPacketHeader.HEADER_SIZE, payload.size)
        return VirtualPacket.fromHeaderAndPayloadData(
            header = VirtualPacketHeader(
                toAddr = toAddr,
                toPort = toPort,
                fromAddr = fromAddr,
                fromPort = fromPort,
                lastHopAddr = lastHopAddr,
                hopCount = hopCount,
                maxHops = 0,
                gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE, //V3: Ecosystem messages are mesh-local
                payloadSize = payload.size
            ),
            data = packetData,
            payloadOffset = payloadOffset
        )
    }

    companion object {
        fun fromBytes(bytes: ByteArray): MeshEcosystemMessage {
            val unpacker: MessageUnpacker = MessagePack.newDefaultUnpacker(bytes)
            val type: String = unpacker.unpackString()
            val message: MeshEcosystemMessage = when (type) {
                "StorageNodeRequest" -> {
                    val requestId = unpacker.unpackString()
                    val chunkId = unpacker.unpackString()
                    val chunkIndex = unpacker.unpackInt()
                    val fileId = unpacker.unpackString()

                    val chunkSizeBytes = unpacker.unpackLong()
                    val replicaCount = unpacker.unpackInt()
                    val requiredSpace = unpacker.unpackLong()
                    val fileName = unpacker.unpackString()
                    val senderId = unpacker.unpackInt()
                    StorageNodeRequestMessage(
                        StorageNodeRequest(
                            requestId, chunkId, chunkIndex, fileId, 
                            chunkSizeBytes, replicaCount, requiredSpace, fileName, senderId
                        )
                    )
                }
                "StorageNodeResponse" -> {
                    val requestId = unpacker.unpackString().takeIf { it.isNotEmpty() }
                    val nodeId = unpacker.unpackInt()
                    val availableSpace = unpacker.unpackLong()
                    val totalStorageAllocated = unpacker.unpackLong()
                    val systemState = unpacker.unpackString()
                    val url = unpacker.unpackString()
                    val latency = unpacker.unpackInt()
                    val fitnessScore = unpacker.unpackFloat()
                    val fileId = unpacker.unpackString()
                    val serviceKeySize = unpacker.unpackBinaryHeader()
                    val servicePublicKey = ByteArray(serviceKeySize)
                    unpacker.readPayload(servicePublicKey)
                    StorageNodeResponseMessage(
                        response = StorageNodeResponse(
                            nodeId, availableSpace, totalStorageAllocated, systemState, 
                            url, latency, fitnessScore, fileId, servicePublicKey
                        ),
                        requestId = requestId
                    )
                }
                "ChunkTransfer" -> {
                    val chunkId = unpacker.unpackString()
                    val fileId = unpacker.unpackString()
                    val chunkIndex = unpacker.unpackInt()
                    val totalChunks = unpacker.unpackInt()
                    val fileName = unpacker.unpackString()
                    val relativePath = unpacker.unpackString()
                    val chunkSize = unpacker.unpackBinaryHeader()
                    val chunkBytes = ByteArray(chunkSize)
                    unpacker.readPayload(chunkBytes)
                    val hash = unpacker.unpackString()
                    val replicaCount = unpacker.unpackInt()

                    // Unpack recipients
                    val recipients = List(unpacker.unpackArrayHeader()) { MessageType.unpackRecipientEntry(unpacker) }

                    // Unpack sessionKeys
                    val sessionKeysSize = unpacker.unpackMapHeader()
                    val sessionKeys = mutableMapOf<String, ByteArray>()
                    repeat(sessionKeysSize) {
                        val keyId = unpacker.unpackString()
                        val keySize = unpacker.unpackBinaryHeader()
                        val key = ByteArray(keySize)
                        unpacker.readPayload(key)
                        sessionKeys[keyId] = key
                    }

                    // Unpack owner
                    val owner = MessageType.unpackRecipientEntry(unpacker)

                    
                    

                    ChunkTransferMessage(
                        chunkId, fileId, chunkIndex, totalChunks, fileName, relativePath, chunkBytes, hash, replicaCount, recipients, sessionKeys, 
                        owner
                    ) as MeshEcosystemMessage
                }
                "ChunkRetrievalQuery" -> {
                    val fileId = unpacker.unpackString()
                    val chunkIndexes = if (unpacker.unpackBoolean()) {
                        List(unpacker.unpackArrayHeader()) { unpacker.unpackInt() }
                    } else null
                    val owner = MessageType.unpackRecipientEntry(unpacker)
                    val senderId = unpacker.unpackInt()
                    val requestId = unpacker.unpackString()
                    ChunkRetrievalQueryMessage(
                        ChunkRetrievalQuery(fileId,  chunkIndexes, owner, senderId, requestId )
                    )
                }
                "ChunkRetrievalResponse" -> ChunkRetrievalResponseMessage(
                    response = ChunkRetrievalResponse(
                        requestId = unpacker.unpackString(),
                        chunkId = unpacker.unpackString(),
                        fileId = unpacker.unpackString(),
                        chunkIndex = unpacker.unpackInt(),
                        totalChunks = unpacker.unpackInt(),
                        nodeId = unpacker.unpackInt(),
                        fileName = unpacker.unpackString(),
                        relativePath = unpacker.unpackString(),
                        chunkSize = unpacker.unpackLong(),
                       
                    )
                )
                "ReplicaQuery" -> ReplicaQueryMessage(
                    ReplicaQuery(unpacker.unpackString())
                )
                "ReplicaResponse" -> ReplicaResponseMessage(
                    response = ReplicaResponse(unpacker.unpackString()),
                    requestId = unpacker.unpackString().takeIf { it.isNotEmpty() }
                )
                // DEPRECATED: Storage capabilities superseded by OriginatorMessage with MeshRole.STORAGE
                // "StorageCapabilities" -> StorageCapabilitiesMessage(
                //     StorageCapabilities(
                //         totalOffered = unpacker.unpackLong(),
                //         currentlyUsed = unpacker.unpackLong(),
                //         replicationFactor = unpacker.unpackInt(),
                //         compressionSupported = unpacker.unpackBoolean(),
                //         encryptionSupported = unpacker.unpackBoolean(),
                //         accessPatterns = List(unpacker.unpackArrayHeader()) { AccessPattern.valueOf(unpacker.unpackString()) }.toSet()
                //     )
                // )
                "FilePermissionUpdateMessage" -> {
                    val fileId = unpacker.unpackString()
                    val addedJson = unpacker.unpackString()
                    val removedJson = unpacker.unpackString()
                    val addedRecipients = Json.decodeFromString<List<RecipientEntry>>(addedJson)
                    val removedRecipients = Json.decodeFromString<List<RecipientEntry>>(removedJson)
                    val chunkIndexes = if (unpacker.unpackBoolean()) {
                        List(unpacker.unpackArrayHeader()) { unpacker.unpackInt() }
                    } else null
                    val senderNodeId = unpacker.unpackInt()
                    FilePermissionUpdateMessage(fileId, addedRecipients, removedRecipients, chunkIndexes, senderNodeId)
                }
                "FilePermissionUpdateConfirmation" -> {
                    val nodeId = unpacker.unpackInt()
                    val fileId = unpacker.unpackString()
                    val chunkIndexes = List(unpacker.unpackArrayHeader()) { unpacker.unpackInt() }
                    val status = unpacker.unpackBoolean()
                    FilePermissionUpdateConfirmationMessage(nodeId, fileId, chunkIndexes, status)
                }
                
                "EcosystemBroadcast" -> EcosystemBroadcastMessage(
                    broadcastId = unpacker.unpackString(),
                    senderId = unpacker.unpackInt(),
                    messageType = unpacker.unpackString(),
                    payload = unpacker.readPayload(unpacker.unpackBinaryHeader())
                )
                "ComputeTaskRequest" -> ComputeTaskRequestMessage.fromUnpacker(unpacker)
                "ComputeNodeResponse" -> ComputeNodeResponseMessage.fromUnpacker(unpacker)
                "TaskCompleted" -> TaskCompletedMessage.fromUnpacker(unpacker)
                "TaskScheduled" -> TaskScheduledMessage.fromUnpacker(unpacker)
                "TaskAssignment" -> TaskAssignmentMessage.fromUnpacker(unpacker)
                else -> throw IllegalArgumentException("Unknown MeshEcosystemMessage type: $type")
            }
            unpacker.close()
            return message
        }
    }
}

// === Message Subclasses ===

data class StorageNodeResponseMessage(
    val response: StorageNodeResponse,
    val requestId: String? = null
) : MeshEcosystemMessage("StorageNodeResponse") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(requestId ?: "")
        packer.packInt(response.nodeId)
        packer.packLong(response.availableSpace)
        packer.packLong(response.totalStorageAllocated)
        packer.packString(response.systemState)
        packer.packString(response.url)
        packer.packInt(response.latency)
        packer.packFloat(response.fitnessScore)
        packer.packString(response.fileId)
        packer.packBinaryHeader(response.servicePublicKey.size)
        packer.writePayload(response.servicePublicKey)
        packer.close()
        return packer.toByteArray()
    }
}

data class StorageNodeRequestMessage(val request: StorageNodeRequest) : MeshEcosystemMessage("StorageNodeRequest") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(request.requestId)
        packer.packString(request.chunkId)
        packer.packInt(request.chunkIndex)
        packer.packString(request.fileId)
       
        packer.packLong(request.chunkSizeBytes)
        packer.packInt(request.replicaCount)
        packer.packLong(request.requiredSpace)
        packer.packString(request.fileName)
        packer.packInt(request.senderId)
        packer.close()
        return packer.toByteArray()
    }
    companion object {
        /**
            * Factory: Deserialize bytes to MeshEcosystemMessage, dispatching by MessageType.
            * All message types must be registered in MessageType and handled here.
            */
        fun fromBytes(bytes: ByteArray): MeshEcosystemMessage {
            val unpacker = MessagePack.newDefaultUnpacker(bytes)
            val type = unpacker.unpackString()
            val messageType = MessageType.fromType(type)
                ?: throw IllegalArgumentException("Unknown MeshEcosystemMessage type: $type")
            val message: MeshEcosystemMessage = when (messageType) {
                MessageType.STORAGE_NODE_REQUEST -> {
                    // ...existing code for StorageNodeRequest...
                    val requestId = unpacker.unpackString()
                    val chunkId = unpacker.unpackString()
                    val chunkIndex = unpacker.unpackInt()
                    val fileId = unpacker.unpackString()
                    
                    val chunkSizeBytes = unpacker.unpackLong()
                    val replicaCount = unpacker.unpackInt()
                    val requiredSpace = unpacker.unpackLong()
                    val fileName = unpacker.unpackString()
                    val senderId = unpacker.unpackInt()
                    StorageNodeRequestMessage(
                        StorageNodeRequest(
                            requestId, chunkId, chunkIndex, fileId, chunkSizeBytes, replicaCount, requiredSpace, fileName, senderId
                        )
                    )
                }
                MessageType.STORAGE_NODE_RESPONSE -> {
                    val requestId = unpacker.unpackString()
                    val nodeId = unpacker.unpackInt()
                    val availableSpace = unpacker.unpackLong()
                    val totalStorageAllocated = unpacker.unpackLong()
                    val systemState = unpacker.unpackString()
                    val url = unpacker.unpackString()
                    val latency = unpacker.unpackInt()
                    val fitnessScore = unpacker.unpackFloat()
                    val fileId = unpacker.unpackString()
                    val keyLen = unpacker.unpackBinaryHeader()
                    val servicePublicKey = ByteArray(keyLen)
                    unpacker.readPayload(servicePublicKey)
                    StorageNodeResponseMessage(
                        StorageNodeResponse(
                            nodeId, availableSpace, totalStorageAllocated, systemState, url, latency, fitnessScore, fileId, servicePublicKey
                        ),
                        requestId
                    )
                }
                MessageType.CHUNK_TRANSFER -> {
                    val chunkId = unpacker.unpackString()
                    val fileId = unpacker.unpackString()
                    val chunkIndex = unpacker.unpackInt()
                    val totalChunks = unpacker.unpackInt()
                    val fileName = unpacker.unpackString()
                    val relativePath = unpacker.unpackString()
                    val chunkBytesLen = unpacker.unpackBinaryHeader()
                    val chunkBytes = ByteArray(chunkBytesLen)
                    unpacker.readPayload(chunkBytes)
                    val hash = unpacker.unpackString()
                    val replicaCount = unpacker.unpackInt()
                    val recipients = List(unpacker.unpackArrayHeader()) { MessageType.unpackRecipientEntry(unpacker) }
                    val sessionKeysLen = unpacker.unpackMapHeader()
                    val sessionKeys = mutableMapOf<String, ByteArray>()
                    repeat(sessionKeysLen) {
                        val keyId = unpacker.unpackString()
                        val encKeyLen = unpacker.unpackBinaryHeader()
                        val encKey = ByteArray(encKeyLen)
                        unpacker.readPayload(encKey)
                        sessionKeys[keyId] = encKey
                    }
                    val owner = MessageType.unpackRecipientEntry(unpacker)
                
                    ChunkTransferMessage(
                        chunkId, fileId, chunkIndex, totalChunks, fileName, relativePath, chunkBytes, hash, replicaCount, recipients, sessionKeys, 
                        owner
                    )
                }
                MessageType.CHUNK_RETRIEVAL_QUERY -> {
                    val fileId = unpacker.unpackString()
                    val hasChunkIndexes = unpacker.unpackBoolean()
                    val chunkIndexes = if (hasChunkIndexes) {
                        val len = unpacker.unpackArrayHeader()
                        List(len) { unpacker.unpackInt() }
                    } else null
                    val owner = MessageType.unpackRecipientEntry(unpacker)
                    val senderId = unpacker.unpackInt()
                    val requestId = unpacker.unpackString()
                    ChunkRetrievalQueryMessage(ChunkRetrievalQuery(fileId, chunkIndexes,    owner, senderId, requestId))
                }
                MessageType.CHUNK_RETRIEVAL_RESPONSE -> {
                    val requestId = unpacker.unpackString()
                    val chunkId = unpacker.unpackString()
                    val fileId = unpacker.unpackString()
                    val chunkIndex = unpacker.unpackInt()
                    val totalChunks = unpacker.unpackInt()
                    val nodeId = unpacker.unpackInt()
                    val fileName = unpacker.unpackString()
                    val relativePath = unpacker.unpackString()
                    val chunkSize = unpacker.unpackLong()
                    
                    ChunkRetrievalResponseMessage(
                        ChunkRetrievalResponse(chunkId, fileId, chunkIndex, totalChunks, nodeId, fileName, relativePath, chunkSize,requestId,
                        )
                    )
                }
                MessageType.REPLICA_QUERY -> {
                    val fileId = unpacker.unpackString()
                    ReplicaQueryMessage(ReplicaQuery(fileId))
                }
                MessageType.REPLICA_RESPONSE -> {
                    val requestId = unpacker.unpackString()
                    val fileId = unpacker.unpackString()
                    ReplicaResponseMessage(ReplicaResponse(fileId), requestId)
                }
                MessageType.FILE_PERMISSION_UPDATE -> {
                    val fileId = unpacker.unpackString()
                    val addedJson = unpacker.unpackString()
                    val removedJson = unpacker.unpackString()
                    val hasChunkIndexes = unpacker.unpackBoolean()
                    val chunkIndexes = if (hasChunkIndexes) {
                        val len = unpacker.unpackArrayHeader()
                        List(len) { unpacker.unpackInt() }
                    } else null
                    val senderNodeId = unpacker.unpackInt()
                    val addedRecipients = Json.decodeFromString<List<RecipientEntry>>(addedJson)
                    val removedRecipients = Json.decodeFromString<List<RecipientEntry>>(removedJson)
                    FilePermissionUpdateMessage(fileId, addedRecipients, removedRecipients, chunkIndexes, senderNodeId)
                }
                MessageType.FILE_PERMISSION_UPDATE_CONFIRMATION -> {
                    val nodeId = unpacker.unpackInt()
                    val fileId = unpacker.unpackString()
                    val chunkIndexesLen = unpacker.unpackArrayHeader()
                    val chunkIndexes = List(chunkIndexesLen) { unpacker.unpackInt() }
                    val status = unpacker.unpackBoolean()
                    FilePermissionUpdateConfirmationMessage(nodeId, fileId, chunkIndexes, status)
                }
                MessageType.ECOSYSTEM_BROADCAST -> {
                    val broadcastId = unpacker.unpackString()
                    val senderId = unpacker.unpackInt()
                    val messageType = unpacker.unpackString()
                    val payloadLen = unpacker.unpackBinaryHeader()
                    val payload = ByteArray(payloadLen)
                    unpacker.readPayload(payload)
                    EcosystemBroadcastMessage(broadcastId, senderId, messageType, payload)
                }
                MessageType.COMPUTE_TASK_REQUEST -> ComputeTaskRequestMessage.fromUnpacker(unpacker)
                MessageType.COMPUTE_NODE_RESPONSE -> ComputeNodeResponseMessage.fromUnpacker(unpacker)
                MessageType.TASK_COMPLETED -> TaskCompletedMessage.fromUnpacker(unpacker)
                MessageType.TASK_SCHEDULED -> TaskScheduledMessage.fromUnpacker(unpacker)
                MessageType.TASK_ASSIGNMENT -> TaskAssignmentMessage.fromUnpacker(unpacker)
            }
            unpacker.close()
            return message
        }
    }
}


data class ChunkTransferMessage(
   val chunkId: String,
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val fileName: String,
    val relativePath: String,
    val chunkBytes: ByteArray,
    val hash: String,
    val replicaCount: Int = 0,
    val recipients: List<RecipientEntry> = emptyList(),
    val sessionKeys: Map<String, ByteArray> = emptyMap(),
    val owner: RecipientEntry
) : MeshEcosystemMessage("ChunkTransfer") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(chunkId)
        packer.packString(fileId)
        packer.packInt(chunkIndex)
        packer.packInt(totalChunks)
        packer.packString(fileName)
        packer.packString(relativePath)
        packer.packBinaryHeader(chunkBytes.size)
        packer.writePayload(chunkBytes)
        packer.packString(hash)
        packer.packInt(replicaCount)
        
        // Pack recipients
        packer.packArrayHeader(recipients.size)
        recipients.forEach { MessageType.packRecipientEntry(packer, it) }

        // Pack sessionKeys
        packer.packMapHeader(sessionKeys.size)
        sessionKeys.forEach { (keyId, encryptedKey) ->
            packer.packString(keyId)
            packer.packBinaryHeader(encryptedKey.size)
            packer.writePayload(encryptedKey)
        }

        // Pack owner
        MessageType.packRecipientEntry(packer, owner)
        
        
        
        packer.close()
        return packer.toByteArray()
    }
}

data class ChunkRetrievalQueryMessage(val query: ChunkRetrievalQuery) : MeshEcosystemMessage("ChunkRetrievalQuery") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(query.fileId)
        
        // Pack chunkIndexes (nullable)
        if (query.chunkIndexes != null) {
            packer.packBoolean(true)
            packer.packArrayHeader(query.chunkIndexes.size)
            query.chunkIndexes.forEach { packer.packInt(it) }
        } else {
            packer.packBoolean(false)
        }
        MessageType.packRecipientEntry(packer, query.owner)
        packer.packInt(query.senderId)
        packer.packString(query.requestId)
        
        packer.close()
        return packer.toByteArray()
    }
}

data class ChunkRetrievalResponseMessage(
    val response: ChunkRetrievalResponse,
    val requestId: String? = null
) : MeshEcosystemMessage("ChunkRetrievalResponse") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(requestId ?: "")
        packer.packString(response.chunkId)
        packer.packString(response.fileId)
        packer.packInt(response.chunkIndex)
        packer.packInt(response.totalChunks)
        packer.packInt(response.nodeId)
        packer.packString(response.fileName)
        packer.packString(response.relativePath)
        packer.packLong(response.chunkSize)
        packer.close()
        return packer.toByteArray()
    }
}

data class ReplicaQueryMessage(val query: ReplicaQuery) : MeshEcosystemMessage("ReplicaQuery") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(query.fileId)
        packer.close()
        return packer.toByteArray()
    }
}

data class ReplicaResponseMessage(
    val response: ReplicaResponse,
    val requestId: String? = null
) : MeshEcosystemMessage("ReplicaResponse") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(requestId ?: "")
        packer.packString(response.fileId)
        packer.close()
        return packer.toByteArray()
    }
}

// DEPRECATED: Storage capabilities superseded by OriginatorMessage with MeshRole.STORAGE
// data class StorageCapabilitiesMessage(val capabilities: StorageCapabilities) : MeshEcosystemMessage("StorageCapabilities") {
//     override fun toBytes(): ByteArray {
//         val packer = MessagePack.newDefaultBufferPacker()
//         packer.packString(type)
//         packer.packLong(capabilities.totalOffered)
//         packer.packLong(capabilities.currentlyUsed)
//         packer.packInt(capabilities.replicationFactor)
//         packer.packBoolean(capabilities.compressionSupported)
//         packer.packBoolean(capabilities.encryptionSupported)
//         packer.packArrayHeader(capabilities.accessPatterns.size)
//         capabilities.accessPatterns.forEach { packer.packString(it.name) }
//         packer.close()
//         return packer.toByteArray()
//     }
// }

data class FilePermissionUpdateMessage(
    val fileId: String,
    val addedRecipients: List<RecipientEntry>,
    val removedRecipients: List<RecipientEntry>,
    val chunkIndexes: List<Int>? = null,
    val senderNodeId: Int
) : MeshEcosystemMessage("FilePermissionUpdateMessage") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(fileId)
        
        // Pack addedRecipients as JSON
        val addedJson = Json.encodeToString(addedRecipients)
        packer.packString(addedJson)
        
        // Pack removedRecipients as JSON
        val removedJson = Json.encodeToString(removedRecipients)
        packer.packString(removedJson)
        
        // Pack chunkIndexes (nullable)
        if (chunkIndexes != null) {
            packer.packBoolean(true)
            packer.packArrayHeader(chunkIndexes.size)
            chunkIndexes.forEach { packer.packInt(it) }
        } else {
            packer.packBoolean(false)
        }
        
        packer.packInt(senderNodeId)
        packer.close()
        return packer.toByteArray()
    }
}

data class FilePermissionUpdateConfirmationMessage(
    val nodeId: Int,
    val fileId: String,
    val chunkIndexes: List<Int>,
    val status: Boolean = true
) : MeshEcosystemMessage("FilePermissionUpdateConfirmation") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packInt(nodeId)
        packer.packString(fileId)
        packer.packArrayHeader(chunkIndexes.size)
        chunkIndexes.forEach { packer.packInt(it) }
        packer.packBoolean(status)
        packer.close()
        return packer.toByteArray()
    }
}

/**
 * Type alias for FilePermissionUpdateConfirmationMessage.
 * 
 * This message serves dual purposes:
 * 1. Confirms permission update to file owner ("confirmation" semantics)
 * 2. Notifies recipients (including tasks) they now have access ("notification" semantics)
 * 
 * Sent by: Storage nodes after re-encrypting chunk keys
 * Sent to: File owner, all recipients (users + tasks)
 * 
 * For task recipients, this triggers the compute node to retrieve the file
 * and make it available in the task sandbox.
 */
typealias FileAccessUpdateNotification = FilePermissionUpdateConfirmationMessage


/**
 * EcosystemBroadcastMessage: Canonical broadcast message for mesh-wide UDP gossip.
 */
data class EcosystemBroadcastMessage(
    val broadcastId: String,
    val senderId: Int,
    val messageType: String,
    val payload: ByteArray,
    val metadata: Map<String, Any>? = null,
    val requestId: String? = null
) : MeshEcosystemMessage("EcosystemBroadcast") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(broadcastId)
        packer.packInt(senderId)
        packer.packString(messageType)
        packer.packBinaryHeader(payload.size)
        packer.writePayload(payload)
        packer.close()
        return packer.toByteArray()
    }
}

data class ComputeTaskRequestMessage(
    val taskId: String,
    val serviceId: String,
    val inputParams: Map<String, Any>,
    val metadata: Map<String, Any>
) : MeshEcosystemMessage("ComputeTaskRequest") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(taskId)
        packer.packString(serviceId)
        // Serialize inputParams and metadata as JSON strings for simplicity
        packer.packString(Json.encodeToString(MapSerializer(String.serializer(), AnySerializer), inputParams))
        packer.packString(Json.encodeToString(MapSerializer(String.serializer(), AnySerializer), metadata))
        packer.close()
        return packer.toByteArray()
    }
    companion object {
        fun fromUnpacker(unpacker: MessageUnpacker): ComputeTaskRequestMessage {
            val taskId = unpacker.unpackString()
            val serviceId = unpacker.unpackString()
            val inputParamsJson = unpacker.unpackString()
            val metadataJson = unpacker.unpackString()
            val inputParams = Json.decodeFromString(MapSerializer(String.serializer(), AnySerializer), inputParamsJson)
            val metadata = Json.decodeFromString(MapSerializer(String.serializer(), AnySerializer), metadataJson)
            return ComputeTaskRequestMessage(taskId, serviceId, inputParams, metadata)
        }
    }
}

/**
 * ComputeNodeResponseMessage: Response from a node when it receives a compute task request.
 * Includes ML Kit capabilities and node performance metrics for task assignment decisions.
 */
data class ComputeNodeResponseMessage(
    val requestId: String,
    val response: ComputeNodeResponse
) : MeshEcosystemMessage("ComputeNodeResponse") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(requestId)
        // Pack response fields
        packer.packInt(response.nodeAddress)
        packer.packBoolean(response.available)
        packer.packLong(response.estimatedLatencyMs)
        packer.packFloat(response.currentLoad)
        // Pack ML Kit capabilities
        packer.packArrayHeader(response.mlKitFeatures.size)
        response.mlKitFeatures.forEach { packer.packString(it) }
        packer.packBoolean(response.mlKitCustomSupport)
        packer.packString(response.requestId)
        packer.packLong(response.timestamp)
        packer.close()
        return packer.toByteArray()
    }
    
    companion object {
        fun fromUnpacker(unpacker: MessageUnpacker): ComputeNodeResponseMessage {
            val requestId = unpacker.unpackString()
            val nodeAddress = unpacker.unpackInt()
            val available = unpacker.unpackBoolean()
            val estimatedLatencyMs = unpacker.unpackLong()
            val currentLoad = unpacker.unpackFloat()
            val mlKitFeatures = List(unpacker.unpackArrayHeader()) { unpacker.unpackString() }
            val mlKitCustomSupport = unpacker.unpackBoolean()
            val responseRequestId = unpacker.unpackString()
            val timestamp = unpacker.unpackLong()
            
            val computeResponse = ComputeNodeResponse(
                nodeAddress = nodeAddress,
                available = available,
                estimatedLatencyMs = estimatedLatencyMs,
                currentLoad = currentLoad,
                mlKitFeatures = mlKitFeatures,
                mlKitCustomSupport = mlKitCustomSupport,
                requestId = responseRequestId,
                timestamp = timestamp
            )
            return ComputeNodeResponseMessage(requestId, computeResponse)
        }
    }
}

/**
 * TaskCompletedMessage: Notification that a distributed task has completed execution.
 * Includes execution statistics, error information if failed, and result storage references.
 * 
 * Phase 1: Task Execution Layer - Task completion notification protocol
 */
data class TaskCompletedMessage(
    val taskId: String,
    val executorNodeId: Int,
    val status: String,  // "SUCCESS", "FAILED", "TIMEOUT"
    val executionStats: ExecutionStats,
    val executionError: ExecutionError? = null,
    val resultStorageRefs: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) : MeshEcosystemMessage("TaskCompleted") {
    
    data class ExecutionStats(
        val executionTimeMs: Long,
        val cpuTimeMs: Long,
        val memoryPeakBytes: Long,
        val diskReadBytes: Long,
        val diskWriteBytes: Long,
        val networkSentBytes: Long = 0L,
        val networkRecvBytes: Long = 0L
    )
    
    data class ExecutionError(
        val errorType: String,  // Maps to ExecutionErrorType
        val errorMessage: String,
        val errorCode: Int = 0,
        val stackTrace: String? = null
    )
    
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(taskId)
        packer.packInt(executorNodeId)
        packer.packString(status)
        
        // Pack execution stats
        packer.packLong(executionStats.executionTimeMs)
        packer.packLong(executionStats.cpuTimeMs)
        packer.packLong(executionStats.memoryPeakBytes)
        packer.packLong(executionStats.diskReadBytes)
        packer.packLong(executionStats.diskWriteBytes)
        packer.packLong(executionStats.networkSentBytes)
        packer.packLong(executionStats.networkRecvBytes)
        
        // Pack execution error (nullable)
        if (executionError != null) {
            packer.packBoolean(true)
            packer.packString(executionError.errorType)
            packer.packString(executionError.errorMessage)
            packer.packInt(executionError.errorCode)
            packer.packString(executionError.stackTrace ?: "")
        } else {
            packer.packBoolean(false)
        }
        
        // Pack result storage references
        packer.packArrayHeader(resultStorageRefs.size)
        resultStorageRefs.forEach { packer.packString(it) }
        
        packer.packLong(timestamp)
        packer.close()
        return packer.toByteArray()
    }
    
    companion object {
        fun fromUnpacker(unpacker: MessageUnpacker): TaskCompletedMessage {
            val taskId = unpacker.unpackString()
            val executorNodeId = unpacker.unpackInt()
            val status = unpacker.unpackString()
            
            // Unpack execution stats
            val executionStats = ExecutionStats(
                executionTimeMs = unpacker.unpackLong(),
                cpuTimeMs = unpacker.unpackLong(),
                memoryPeakBytes = unpacker.unpackLong(),
                diskReadBytes = unpacker.unpackLong(),
                diskWriteBytes = unpacker.unpackLong(),
                networkSentBytes = unpacker.unpackLong(),
                networkRecvBytes = unpacker.unpackLong()
            )
            
            // Unpack execution error (nullable)
            val executionError = if (unpacker.unpackBoolean()) {
                ExecutionError(
                    errorType = unpacker.unpackString(),
                    errorMessage = unpacker.unpackString(),
                    errorCode = unpacker.unpackInt(),
                    stackTrace = unpacker.unpackString().takeIf { it.isNotEmpty() }
                )
            } else null
            
            // Unpack result storage references
            val resultStorageRefs = List(unpacker.unpackArrayHeader()) {
                unpacker.unpackString()
            }
            
            val timestamp = unpacker.unpackLong()
            
            return TaskCompletedMessage(
                taskId, executorNodeId, status, executionStats, 
                executionError, resultStorageRefs, timestamp
            )
        }
    }
}

/**
 * TaskScheduledMessage: Notification that a task has been scheduled for execution on a specific node.
 * Sent from orchestrator/scheduler to executor node and requester for task tracking.
 * 
 * Phase 1: Message Protocol Extensions - Task scheduling notification
 */
data class TaskScheduledMessage(
    val taskId: String,
    val executorNodeId: Int,
    val requesterNodeId: Int,
    val scheduledAt: Long = System.currentTimeMillis(),
    val estimatedStartTime: Long? = null,
    val resourceLimits: ResourceLimits = ResourceLimits.zero(),
    // val resourceAllocation: Map<String, Any> = emptyMap(),
    val metadata: Map<String, Any>? = null,
    val requestId: String? = null
) : MeshEcosystemMessage("TaskScheduled") {
    
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(taskId)
        packer.packInt(executorNodeId)
        packer.packInt(requesterNodeId)
        packer.packLong(scheduledAt)
        
        // Pack estimatedStartTime (nullable)
        if (estimatedStartTime != null) {
            packer.packBoolean(true)
            packer.packLong(estimatedStartTime)
        } else {
            packer.packBoolean(false)
        }
        
        // Pack resource allocation as JSON
        // val resourceJson = Json.encodeToString(MapSerializer(String.serializer(), AnySerializer), resourceAllocation)
        // packer.packString(resourceJson)
        
        packer.close()
        return packer.toByteArray()
    }
    
    companion object {
        fun fromUnpacker(unpacker: MessageUnpacker): TaskScheduledMessage {
            val taskId = unpacker.unpackString()
            val executorNodeId = unpacker.unpackInt()
            val requesterNodeId = unpacker.unpackInt()
            val scheduledAt = unpacker.unpackLong()
            
            // Unpack estimatedStartTime (nullable)
            val estimatedStartTime = if (unpacker.unpackBoolean()) {
                unpacker.unpackLong()
            } else null
            
            // Unpack resource allocation from JSON
            val resourceJson = unpacker.unpackString()
            val resourceLimits = Json.decodeFromString(ResourceLimits.serializer(), resourceJson)
            
            return TaskScheduledMessage(
                taskId = taskId,
                executorNodeId = executorNodeId,
                requesterNodeId = requesterNodeId,
                scheduledAt = scheduledAt,
                estimatedStartTime = estimatedStartTime,
                resourceLimits = resourceLimits,
                // resourceAllocation = resourceAllocation, // must be unpacked from JSON
                metadata = null, // or unpack if present
                requestId = null // or unpack if present
            )
        }
    }
}

/**
 * TaskAssignmentMessage: Complete task assignment sent from scheduler to executor node.
 * Includes all parameters needed for task execution: execution context, resource limits, input files.
 * 
 * Phase 1: Message Protocol Extensions - Comprehensive task assignment 
 * taskId, executorNodeId, requesterNodeId, callbackAddress, executorType, jobType,
                codeBundle, executionContext,  inputFiles, 
                assignedAt, owner,recipients
 */
data class TaskAssignmentMessage(
    val taskId: String,
    val executorNodeId: Int,
    val requesterNodeId: Int,
    // val callbackAddress: String,
    val executorType: String,  // Executor class name: JSExecutor, JVMExecutor, MLNativeExecutor
    // val jobType: String,       // IMAGE_PROCESSING, VIDEO_PROCESSING, DATA_ANALYSIS, etc.
    val codeBundle: ByteArray? = null,
    val executionContext: Map<String, Any>,  // Includes working directory, environment vars, etc.
    // val resourceLimits: Map<String, Any>,    // Memory, CPU, disk, network, timeout
    val inputFiles: List<Map<String, String>>,  // List of {fileId, storageRef, accessScope}
    // val outputRequirements: Map<String, Any>,  // Output destination, permissions, etc.
    val assignedAt: Long = System.currentTimeMillis(),
    // val metadata: Map<String, Any>? = null,
    // val requestId: String? = null,
    val owner: RecipientEntry,
    val recipients: List<RecipientEntry> = emptyList()
) : MeshEcosystemMessage("TaskAssignment") {
    
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(taskId)
        packer.packInt(executorNodeId)
        packer.packInt(requesterNodeId)
        // packer.packString(callbackAddress)
        packer.packString(executorType)
        // packer.packString(jobType)
        
        // Pack code bundle
        if (codeBundle != null) {
            packer.packBoolean(true)
            packer.packBinaryHeader(codeBundle.size)
            packer.writePayload(codeBundle)
        } else {
            packer.packBoolean(false)
        }
        
        // Pack execution context as JSON
        val contextJson = Json.encodeToString(MapSerializer(String.serializer(), AnySerializer), executionContext)
        packer.packString(contextJson)
        
        // Pack resource limits as JSON
        // val limitsJson = Json.encodeToString(MapSerializer(String.serializer(), AnySerializer), resourceLimits)
        // packer.packString(limitsJson)
        
        // Pack input files array
        packer.packArrayHeader(inputFiles.size)
        inputFiles.forEach { fileMap ->
            val fileJson = Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), fileMap)
            packer.packString(fileJson)
        }
        
        // Pack output requirements as JSON
        // val outputJson = Json.encodeToString(MapSerializer(String.serializer(), AnySerializer), outputRequirements)
        // packer.packString(outputJson)
        
        packer.packLong(assignedAt)
        MessageType.packRecipientEntry(packer, owner)
        packer.packArrayHeader(recipients.size)
        recipients.forEach { recipient ->
            MessageType.packRecipientEntry(packer, recipient)
        }
        packer.close()
        return packer.toByteArray()
    }
    
    companion object {
        fun fromUnpacker(unpacker: MessageUnpacker): TaskAssignmentMessage {
            val taskId = unpacker.unpackString()
            val executorNodeId = unpacker.unpackInt()
            val requesterNodeId = unpacker.unpackInt()
            // val callbackAddress = unpacker.unpackString()
            val executorType = unpacker.unpackString()
            // val jobType = unpacker.unpackString()
            
            // Unpack code bundle
            val codeBundle = if (unpacker.unpackBoolean()) {
                val size = unpacker.unpackBinaryHeader()
                unpacker.readPayload(size)
            } else {
                null
            }
            
            // Unpack execution context from JSON
            val contextJson = unpacker.unpackString()
            val executionContext = Json.decodeFromString(
                MapSerializer(String.serializer(), AnySerializer),
                contextJson
            )
            
            // Unpack resource limits from JSON
            // val limitsJson = unpacker.unpackString()
            // val resourceLimits = Json.decodeFromString(
            //     MapSerializer(String.serializer(), AnySerializer),
            //     limitsJson
            // )
            
            // Unpack input files array
            val inputFiles = List(unpacker.unpackArrayHeader()) {
                val fileJson = unpacker.unpackString()
                Json.decodeFromString(
                    MapSerializer(String.serializer(), String.serializer()),
                    fileJson
                )
            }
            
            // Unpack output requirements from JSON
            // val outputJson = unpacker.unpackString()
            // val outputRequirements = Json.decodeFromString(
            //     MapSerializer(String.serializer(), AnySerializer),
            //     outputJson
            // )
            
            val assignedAt = unpacker.unpackLong()
            val owner = MessageType.unpackRecipientEntry(unpacker)
            val recipients = List(unpacker.unpackArrayHeader()) {
                MessageType.unpackRecipientEntry(unpacker)
            }
            return TaskAssignmentMessage(
                taskId, executorNodeId, requesterNodeId,  executorType, 
                codeBundle, executionContext,  inputFiles, 
                assignedAt, owner,recipients
            )
        }
    }
}

/**
 * Message sent by compute node to client when task is accepted for execution.
 *
 * @property taskId Task identifier
 * @property publicKey Public key for encrypting data shared with this task
 * @property computeNodeAddress Mesh network address of compute node executing the task
 */
data class TaskAcceptanceMessage(
    val taskId: String,
    val publicKey: String,
    val computeNodeAddress: String
) : MeshEcosystemMessage("TaskAcceptance") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(taskId)
        packer.packString(publicKey)
        packer.packString(computeNodeAddress)
        packer.close()
        return packer.toByteArray()
    }

    companion object {
        fun fromUnpacker(unpacker: MessageUnpacker): TaskAcceptanceMessage {
            val taskId = unpacker.unpackString()
            val publicKey = unpacker.unpackString()
            val computeNodeAddress = unpacker.unpackString()
            return TaskAcceptanceMessage(taskId, publicKey, computeNodeAddress)
        }
    }
}

/**
 * Message sent by client to compute node acknowledging task completion.
 *
 * @property taskId Task identifier
 * @property receivedAt Timestamp when client received completion notification
 */
data class TaskCompletionAckMessage(
    val taskId: String,
    val receivedAt: Long = System.currentTimeMillis()
) : MeshEcosystemMessage("TaskCompletionAck") {
    override fun toBytes(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packString(type)
        packer.packString(taskId)
        packer.packLong(receivedAt)
        packer.close()
        return packer.toByteArray()
    }

    companion object {
        fun fromUnpacker(unpacker: MessageUnpacker): TaskCompletionAckMessage {
            val taskId = unpacker.unpackString()
            val receivedAt = unpacker.unpackLong()
            return TaskCompletionAckMessage(taskId, receivedAt)
        }
    }
}

