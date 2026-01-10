package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.mmcp.MmcpOriginatorMessage
import com.ustadmobile.meshrabiya.ext.addressAsInt
import org.junit.Test
import org.junit.Assert.*
import java.net.InetAddress
import java.net.DatagramPacket

/**
 * Test 2: Topology Map Building
 * Verifies that onReceiveOriginatingMessage() builds topology map from neighbor lists
 */
class TopologyMapBuildingTest {
    
    @Test
    fun testTopologyMapBuildingFromReceivedMessages() {
        val localAddr = InetAddress.getByName("192.168.1.100")
        val manager = OriginatingMessageManager(
            localNodeInetAddr = localAddr,
            localNodePort = 8080,
            datagramSockets = emptyList(),
            logger = { _, _ -> },
            getDirectNeighborAddresses = { emptyList() },
            getPingTimeFor = { 0 },
            getConnectConfig = { null },
            getCentralityScore = { 0f },
            getMeshRoles = { emptySet() },
            getFitnessScore = { 0f }
        )
        
        manager.start()
        
        // Create originator message with neighbors
        val fromAddr = 192837464
        val neighbors = listOf(192837465, 192837466, 192837467)  // 3 neighbors
        
        val originatorMessage = MmcpOriginatorMessage(
            messageId = 1,
            sentTime = System.currentTimeMillis(),
            pingTimeSum = 0,
            connectConfig = null,
            neighbors = neighbors,
            centralityScore = 0.5f,
            fitnessScore = 0.6f,
            meshRoles = setOf(MeshRole.MESH_PARTICIPANT)
        )
        
        // Create virtual packet
        val virtualPacket = VirtualPacket.createWithMmcpPayload(
            toAddr = VirtualRouter.ADDR_BROADCAST,
            fromAddr = fromAddr,
            lastHopAddr = 192837463,
            hopCount = 2,
            mmcpMessage = originatorMessage
        )
        
        // Create mock datagram packet
        val datagramPacket = DatagramPacket(
            ByteArray(1024),
            1024,
            InetAddress.getByName("192.168.1.101"),
            8081
        )
        
        // Create mock socket
        val mockSocket = object : VirtualNodeDatagramSocket {
            override val virtualNode: VirtualNode
                get() = TODO("Not needed for this test")
            override val realInetSocketAddress: java.net.InetSocketAddress
                get() = java.net.InetSocketAddress(8080)
            override val virtualAddr: Int
                get() = localAddr.addressAsInt()
            override fun send(datagramPacket: DatagramPacket) {}
            override fun close() {}
        }
        
        // Process the message
        manager.onReceiveOriginatingMessage(
            mmcpMessage = originatorMessage,
            datagramPacket = datagramPacket,
            datagramSocket = mockSocket,
            virtualPacket = virtualPacket
        )
        
        // Verify topology map was updated
        val topologyMap = manager.getTopologyMap()
        assertTrue("Topology map should contain sender address", topologyMap.containsKey(fromAddr))
        
        // NEW: Access neighbors via NodeTopologyInfo
        val nodeInfo = topologyMap[fromAddr]
        assertNotNull("NodeInfo should exist for sender", nodeInfo)
        assertEquals(
            "Topology map should contain sender's neighbors",
            neighbors.toSet(),
            nodeInfo?.neighbors
        )
        
        manager.close()
    }
    
    @Test
    fun testTopologyMapWithMultipleNodes() {
        val localAddr = InetAddress.getByName("192.168.1.100")
        val manager = OriginatingMessageManager(
            localNodeInetAddr = localAddr,
            localNodePort = 8080,
            datagramSockets = emptyList(),
            logger = { _, _ -> },
            getDirectNeighborAddresses = { emptyList() },
            getPingTimeFor = { 0 },
            getConnectConfig = { null },
            getCentralityScore = { 0f },
            getMeshRoles = { emptySet() },
            getFitnessScore = { 0f }
        )
        
        manager.start()
        
        // Create messages from 3 different nodes
        val nodes = listOf(
            Triple(192837464, listOf(192837465, 192837466), setOf(MeshRole.MESH_PARTICIPANT)),
            Triple(192837465, listOf(192837464, 192837467), setOf(MeshRole.MESH_ROUTER)),
            Triple(192837467, listOf(192837465, 192837466), setOf(MeshRole.TOR_GATEWAY))
        )
        
        val mockSocket = object : VirtualNodeDatagramSocket {
            override val virtualNode: VirtualNode
                get() = TODO("Not needed for this test")
            override val realInetSocketAddress: java.net.InetSocketAddress
                get() = java.net.InetSocketAddress(8080)
            override val virtualAddr: Int
                get() = localAddr.addressAsInt()
            override fun send(datagramPacket: DatagramPacket) {}
            override fun close() {}
        }
        
        // Send messages from each node
        nodes.forEach { (fromAddr, neighbors, roles) ->
            val message = MmcpOriginatorMessage(
                messageId = fromAddr,
                sentTime = System.currentTimeMillis(),
                pingTimeSum = 0,
                connectConfig = null,
                neighbors = neighbors,
                centralityScore = 0.5f,
                fitnessScore = 0.6f,
                meshRoles = roles
            )
            
            val virtualPacket = VirtualPacket.createWithMmcpPayload(
                toAddr = VirtualRouter.ADDR_BROADCAST,
                fromAddr = fromAddr,
                lastHopAddr = fromAddr - 1,
                hopCount = 1,
                mmcpMessage = message
            )
            
            val datagramPacket = DatagramPacket(
                ByteArray(1024),
                1024,
                InetAddress.getByName("192.168.1.101"),
                8081
            )
            
            manager.onReceiveOriginatingMessage(
                mmcpMessage = message,
                datagramPacket = datagramPacket,
                datagramSocket = mockSocket,
                virtualPacket = virtualPacket
            )
        }
        
        // Verify all nodes are in topology map
        val topologyMap = manager.getTopologyMap()
        assertEquals("Topology map should contain all 3 nodes", 3, topologyMap.size)
        
        // Verify each node's neighbors (NEW: access via NodeTopologyInfo.neighbors)
        assertEquals(setOf(192837465, 192837466), topologyMap[192837464]?.neighbors)
        assertEquals(setOf(192837464, 192837467), topologyMap[192837465]?.neighbors)
        assertEquals(setOf(192837465, 192837466), topologyMap[192837467]?.neighbors)
        
        manager.close()
    }
}
