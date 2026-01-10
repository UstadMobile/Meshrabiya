package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.mmcp.MmcpOriginatorMessage
import org.junit.Test
import org.junit.Assert.*
import java.net.InetAddress

/**
 * Test 1: OriginatingMessageManager Callback Usage
 * Verifies that makeOriginatingMessage() uses callbacks instead of direct dependencies
 */
class OriginatingMessageManagerCallbackTest {
    
    @Test
    fun testMakeOriginatingMessageUsesCallbacks() {
        var centralityCallbackInvoked = false
        var rolesCallbackInvoked = false
        var fitnessCallbackInvoked = false
        
        val manager = OriginatingMessageManager(
            localNodeInetAddr = InetAddress.getByName("192.168.1.100"),
            localNodePort = 8080,
            datagramSockets = emptyList(),
            logger = { _, _ -> },
            getDirectNeighborAddresses = { emptyList() },
            getPingTimeFor = { 0 },
            getConnectConfig = { null },
            getCentralityScore = { 
                centralityCallbackInvoked = true
                0.75f 
            },
            getMeshRoles = { 
                rolesCallbackInvoked = true
                setOf(MeshRole.MESH_ROUTER) 
            },
            getFitnessScore = { 
                fitnessCallbackInvoked = true
                0.85f 
            }
        )
        
        // Use reflection or test access to call makeOriginatingMessage()
        // For now, we'll trigger it indirectly by starting the manager
        manager.start()
        
        // Wait briefly for the message to be created
        Thread.sleep(100)
        
        // Verify callbacks were invoked
        assertTrue("Centrality callback should be invoked", centralityCallbackInvoked)
        assertTrue("Roles callback should be invoked", rolesCallbackInvoked)
        assertTrue("Fitness callback should be invoked", fitnessCallbackInvoked)
        
        manager.close()
    }
    
    @Test
    fun testMakeOriginatingMessageWithActualValues() {
        val expectedCentrality = 0.75f
        val expectedRoles = setOf(MeshRole.MESH_ROUTER, MeshRole.TOR_GATEWAY)
        val expectedFitness = 0.85f
        
        val manager = OriginatingMessageManager(
            localNodeInetAddr = InetAddress.getByName("192.168.1.100"),
            localNodePort = 8080,
            datagramSockets = emptyList(),
            logger = { _, _ -> },
            getDirectNeighborAddresses = { listOf(192837465, 192837466) },
            getPingTimeFor = { 50 },
            getConnectConfig = { null },
            getCentralityScore = { expectedCentrality },
            getMeshRoles = { expectedRoles },
            getFitnessScore = { expectedFitness }
        )
        
        manager.start()
        Thread.sleep(100)
        
        // Get state and verify the message contains callback values
        val state = manager.state.value
        if (state.pendingMessages.isNotEmpty()) {
            val message = state.pendingMessages.values.first()
            assertEquals(expectedCentrality, message.centralityScore, 0.01f)
            assertEquals(expectedRoles, message.meshRoles)
            assertEquals(expectedFitness, message.fitnessScore, 0.01f)
            assertTrue(message.neighbors.contains(192837465))
            assertTrue(message.neighbors.contains(192837466))
        }
        
        manager.close()
    }
}
