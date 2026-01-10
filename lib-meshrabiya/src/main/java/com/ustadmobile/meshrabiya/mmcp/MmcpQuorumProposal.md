package com.ustadmobile.meshrabiya.mmcp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MMCP message for quorum proposal messages using the enhanced gossip protocol.
 */
class MmcpQuorumProposal(
    messageId: Int,
    val quorumId: String,
    val proposingNodeId: String,
    val memberNodes: Set<String>,
    val quorumType: QuorumType,
    val roleAssignments: Map<String, MeshRole>,
    val decisionDeadline: Long,
    val timestamp: Long = System.currentTimeMillis()
) : MmcpMessage(WHAT_QUORUM_PROPOSAL, messageId) {

    override fun toBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        
        // Write quorum ID
        val quorumIdBytes = quorumId.toByteArray()
        dos.writeInt(quorumIdBytes.size)
        dos.write(quorumIdBytes)
        
        // Write proposing node ID
        val proposingNodeIdBytes = proposingNodeId.toByteArray()
        dos.writeInt(proposingNodeIdBytes.size)
        dos.write(proposingNodeIdBytes)
        
        // Write member nodes
        dos.writeInt(memberNodes.size)
        memberNodes.forEach { memberNode ->
            val memberNodeBytes = memberNode.toByteArray()
            dos.writeInt(memberNodeBytes.size)
            dos.write(memberNodeBytes)
        }
        
        // Write quorum type
        dos.writeInt(quorumType.ordinal)
        
        // Write role assignments
        dos.writeInt(roleAssignments.size)
        roleAssignments.forEach { (nodeId, role) ->
            val nodeIdBytes = nodeId.toByteArray()
            dos.writeInt(nodeIdBytes.size)
            dos.write(nodeIdBytes)
            dos.writeInt(role.ordinal)
        }
        
        // Write decision deadline
        dos.writeLong(decisionDeadline)
        
        // Write timestamp
        dos.writeLong(timestamp)
        
        return baos.toByteArray()
    }

    companion object {
        fun fromBytes(
            byteArray: ByteArray,
            offset: Int = 0,
            len: Int = byteArray.size
        ): MmcpQuorumProposal {
            val buffer = ByteBuffer.wrap(byteArray, offset, len).order(ByteOrder.BIG_ENDIAN)
            buffer.position(offset + 1) // Skip what byte
            
            val messageId = buffer.int
            
            // Read quorum ID
            val quorumIdSize = buffer.int
            val quorumIdBytes = ByteArray(quorumIdSize)
            buffer.get(quorumIdBytes)
            val quorumId = String(quorumIdBytes)
            
            // Read proposing node ID
            val proposingNodeIdSize = buffer.int
            val proposingNodeIdBytes = ByteArray(proposingNodeIdSize)
            buffer.get(proposingNodeIdBytes)
            val proposingNodeId = String(proposingNodeIdBytes)
            
            // Read member nodes
            val memberNodesSize = buffer.int
            val memberNodes = mutableSetOf<String>()
            repeat(memberNodesSize) {
                val memberNodeSize = buffer.int
                val memberNodeBytes = ByteArray(memberNodeSize)
                buffer.get(memberNodeBytes)
                memberNodes.add(String(memberNodeBytes))
            }
            
            // Read quorum type
            val quorumType = QuorumType.values()[buffer.int]
            
            // Read role assignments
            val roleAssignmentsSize = buffer.int
            val roleAssignments = mutableMapOf<String, MeshRole>()
            repeat(roleAssignmentsSize) {
                val nodeIdSize = buffer.int
                val nodeIdBytes = ByteArray(nodeIdSize)
                buffer.get(nodeIdBytes)
                val nodeId = String(nodeIdBytes)
                
                val role = MeshRole.values()[buffer.int]
                roleAssignments[nodeId] = role
            }
            
            // Read decision deadline
            val decisionDeadline = buffer.long
            
            // Read timestamp
            val timestamp = buffer.long
            
            return MmcpQuorumProposal(
                messageId = messageId,
                quorumId = quorumId,
                proposingNodeId = proposingNodeId,
                memberNodes = memberNodes,
                quorumType = quorumType,
                roleAssignments = roleAssignments,
                decisionDeadline = decisionDeadline,
                timestamp = timestamp
            )
        }
    }
}
