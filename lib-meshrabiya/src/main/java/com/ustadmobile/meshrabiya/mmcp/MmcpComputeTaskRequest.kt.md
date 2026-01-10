package com.ustadmobile.meshrabiya.mmcp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MMCP message for compute task requests using the enhanced gossip protocol.
 */
class MmcpComputeTaskRequest(
    messageId: Int,
    val taskId: String,
    val taskType: ComputeTaskType,
    val requesterNodeId: String,
    val requirements: ComputeRequirements,
    val deadline: Long?,
    val priority: TaskPriority,
    val dependencies: List<String>,
    val targetRuntime: RuntimeEnvironment,
    val timestamp: Long = System.currentTimeMillis()
) : MmcpMessage(WHAT_COMPUTE_TASK_REQUEST, messageId) {

    override fun toBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        
        // Write task ID
        val taskIdBytes = taskId.toByteArray()
        dos.writeInt(taskIdBytes.size)
        dos.write(taskIdBytes)
        
        // Write task type
        dos.writeInt(taskType.ordinal)
        
        // Write requester node ID
        val requesterNodeIdBytes = requesterNodeId.toByteArray()
        dos.writeInt(requesterNodeIdBytes.size)
        dos.write(requesterNodeIdBytes)
        
        // Write requirements
        dos.writeFloat(requirements.minCPU)
        
        // Write deadline (nullable)
        dos.writeBoolean(deadline != null)
        if (deadline != null) {
            dos.writeLong(deadline)
        }
        
        // Write priority
        dos.writeInt(priority.ordinal)
        
        // Write dependencies
        dos.writeInt(dependencies.size)
        dependencies.forEach { dependency ->
            val dependencyBytes = dependency.toByteArray()
            dos.writeInt(dependencyBytes.size)
            dos.write(dependencyBytes)
        }
        
        // Write target runtime
        dos.writeInt(targetRuntime.ordinal)
        
        // Write timestamp
        dos.writeLong(timestamp)
        
        return baos.toByteArray()
    }

    companion object {
        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int = byteArray.size
        ): MmcpComputeTaskRequest {
            val buffer = ByteBuffer.wrap(byteArray, offset, len).order(ByteOrder.BIG_ENDIAN)
            buffer.position(offset + 1) // Skip what byte
            
            val messageId = buffer.int
            
            // Read task ID
            val taskIdSize = buffer.int
            val taskIdBytes = ByteArray(taskIdSize)
            buffer.get(taskIdBytes)
            val taskId = String(taskIdBytes)
            
            // Read task type
            val taskType = ComputeTaskType.values()[buffer.int]
            
            // Read requester node ID
            val requesterNodeIdSize = buffer.int
            val requesterNodeIdBytes = ByteArray(requesterNodeIdSize)
            buffer.get(requesterNodeIdBytes)
            val requesterNodeId = String(requesterNodeIdBytes)
            
            // Read requirements
            val minCPU = buffer.float
            val requirements = ComputeRequirements(minCPU = minCPU)
            
            // Read deadline
            val hasDeadline = buffer.get() != 0.toByte()
            val deadline = if (hasDeadline) buffer.long else null
            
            // Read priority
            val priority = TaskPriority.values()[buffer.int]
            
            // Read dependencies
            val dependenciesSize = buffer.int
            val dependencies = mutableListOf<String>()
            repeat(dependenciesSize) {
                val dependencySize = buffer.int
                val dependencyBytes = ByteArray(dependencySize)
                buffer.get(dependencyBytes)
                dependencies.add(String(dependencyBytes))
            }
            
            // Read target runtime
            val targetRuntime = RuntimeEnvironment.values()[buffer.int]
            
            // Read timestamp
            val timestamp = buffer.long
            
            return MmcpComputeTaskRequest(
                messageId = messageId,
                taskId = taskId,
                taskType = taskType,
                requesterNodeId = requesterNodeId,
                requirements = requirements,
                deadline = deadline,
                priority = priority,
                dependencies = dependencies,
                targetRuntime = targetRuntime,
                timestamp = timestamp
            )
        }
    }
}
