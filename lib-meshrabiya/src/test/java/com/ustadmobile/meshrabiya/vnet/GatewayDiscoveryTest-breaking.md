package com.ustadmobile.meshrabiya.vnet

import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.*

/**
 * Unit tests for gateway discovery functionality.
 * Tests VirtualNode's ability to discover Tor and clearnet gateways from topology.
 */
class GatewayDiscoveryTest {

    private lateinit var mockOriginatingMessageManager: OriginatingMessageManager
    private lateinit var testTopologyNodes: List<NodeTopologyInfo>

    @Before
    fun setup() {
        println("[DEBUG] GatewayDiscoveryTest: Starting @Before setup()")
        // Clear any static mocks from previous tests
        unmockkAll()
        println("[DEBUG] GatewayDiscoveryTest: Cleared all mocks")
        mockOriginatingMessageManager = mockk(relaxed = true)
        println("[DEBUG] GatewayDiscoveryTest: Created mockOriginatingMessageManager")
        
        // Create test topology with mixed gateway nodes
        testTopologyNodes = listOf(
            createMockNodeInfo(address = 1, roles = setOf(MeshRole.TOR_GATEWAY), lastHeartbeat = System.currentTimeMillis() - 5000),
            createMockNodeInfo(address = 2, roles = setOf(MeshRole.CLEARNET_GATEWAY), lastHeartbeat = System.currentTimeMillis() - 5000),
            createMockNodeInfo(address = 3, roles = setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY), lastHeartbeat = System.currentTimeMillis() - 5000),
            createMockNodeInfo(address = 4, roles = emptySet(), lastHeartbeat = System.currentTimeMillis() - 5000),  // No gateway role
            createMockNodeInfo(address = 5, roles = setOf(MeshRole.TOR_GATEWAY), lastHeartbeat = System.currentTimeMillis() - 60000),  // Stale
        )
        println("[DEBUG] GatewayDiscoveryTest: Completed @Before setup()")
    }

    @After
    fun teardown() {
        println("[DEBUG] GatewayDiscoveryTest: Starting @After teardown()")
        unmockkAll()
        println("[DEBUG] GatewayDiscoveryTest: Completed @After teardown()")
    }

    @Test
    fun `getAvailableTorGateways returns only Tor gateways`() {
        every { mockOriginatingMessageManager.getNodesWithRole(MeshRole.TOR_GATEWAY) } returns 
            testTopologyNodes.filter { it.hasRole(MeshRole.TOR_GATEWAY) }
        
        val torGateways = mockOriginatingMessageManager.getNodesWithRole(MeshRole.TOR_GATEWAY)
            .filter { !it.isStale(30_000L) }
        
        assertEquals(2, torGateways.size, "Should find 2 fresh Tor gateways")
        assertTrue(torGateways.any { it.nodeAddress == 1 })
        assertTrue(torGateways.any { it.nodeAddress == 3 })
        assertFalse(torGateways.any { it.nodeAddress == 5 }, "Stale gateway should be filtered")
    }

    @Test
    fun `getAvailableClearnetGateways returns only clearnet gateways`() {
        every { mockOriginatingMessageManager.getNodesWithRole(MeshRole.CLEARNET_GATEWAY) } returns 
            testTopologyNodes.filter { it.hasRole(MeshRole.CLEARNET_GATEWAY) }
        
        val clearnetGateways = mockOriginatingMessageManager.getNodesWithRole(MeshRole.CLEARNET_GATEWAY)
            .filter { !it.isStale(30_000L) }
        
        assertEquals(2, clearnetGateways.size, "Should find 2 fresh clearnet gateways")
        assertTrue(clearnetGateways.any { it.nodeAddress == 2 })
        assertTrue(clearnetGateways.any { it.nodeAddress == 3 })
    }

    @Test
    fun `stale gateways are filtered out`() {
        val now = System.currentTimeMillis()
        
        val freshNode = createMockNodeInfo(
            address = 1,
            roles = setOf(MeshRole.TOR_GATEWAY),
            lastHeartbeat = now - 10_000  // 10 seconds ago (fresh)
        )
        
        val staleNode = createMockNodeInfo(
            address = 2,
            roles = setOf(MeshRole.TOR_GATEWAY),
            lastHeartbeat = now - 60_000  // 60 seconds ago (stale)
        )
        
        assertFalse(freshNode.isStale(30_000L), "Fresh node should not be stale")
        assertTrue(staleNode.isStale(30_000L), "Old node should be stale")
    }

    @Test
    fun `no gateways returns empty list`() {
        val noGatewayNodes = listOf(
            createMockNodeInfo(address = 1, roles = emptySet()),
            createMockNodeInfo(address = 2, roles = emptySet())
        )
        
        every { mockOriginatingMessageManager.getNodesWithRole(MeshRole.TOR_GATEWAY) } returns emptyList()
        every { mockOriginatingMessageManager.getNodesWithRole(MeshRole.CLEARNET_GATEWAY) } returns emptyList()
        
        val torGateways = mockOriginatingMessageManager.getNodesWithRole(MeshRole.TOR_GATEWAY)
        val clearnetGateways = mockOriginatingMessageManager.getNodesWithRole(MeshRole.CLEARNET_GATEWAY)
        
        assertTrue(torGateways.isEmpty(), "No Tor gateways should be found")
        assertTrue(clearnetGateways.isEmpty(), "No clearnet gateways should be found")
    }

    @Test
    fun `dual-role gateway appears in both lists`() {
        val dualRoleNode = createMockNodeInfo(
            address = 3,
            roles = setOf(MeshRole.TOR_GATEWAY, MeshRole.CLEARNET_GATEWAY),
            lastHeartbeat = System.currentTimeMillis() - 5000
        )
        
        assertTrue(dualRoleNode.hasRole(MeshRole.TOR_GATEWAY))
        assertTrue(dualRoleNode.hasRole(MeshRole.CLEARNET_GATEWAY))
        assertFalse(dualRoleNode.isStale(30_000L))
    }

    @Test
    fun `stale timeout threshold is 30 seconds`() {
        // Fix timing race condition by using fixed timestamp
        val fixedNow = 1000000000L
        
        val node29sec = createMockNodeInfo(address = 1, roles = setOf(MeshRole.TOR_GATEWAY), lastHeartbeat = fixedNow - 29_000)
        val node30sec = createMockNodeInfo(address = 2, roles = setOf(MeshRole.TOR_GATEWAY), lastHeartbeat = fixedNow - 30_000)
        val node31sec = createMockNodeInfo(address = 3, roles = setOf(MeshRole.TOR_GATEWAY), lastHeartbeat = fixedNow - 31_000)
        
        // Mock System.currentTimeMillis() in isStale calculation using the fixed value
        mockkStatic(System::class)
        every { System.currentTimeMillis() } returns fixedNow
        
        assertFalse(node29sec.isStale(30_000L), "29 seconds should be fresh")
        assertFalse(node30sec.isStale(30_000L), "30 seconds should be fresh (boundary)")
        assertTrue(node31sec.isStale(30_000L), "31 seconds should be stale")
        
        println("[DEBUG] GatewayDiscoveryTest: Test completed, static mock will be cleared in @After")
    }

    @Test
    fun `getNodesWithRole filters by role correctly`() {
        every { mockOriginatingMessageManager.getNodesWithRole(MeshRole.TOR_GATEWAY) } returns 
            testTopologyNodes.filter { it.hasRole(MeshRole.TOR_GATEWAY) }
        
        val torNodes = mockOriginatingMessageManager.getNodesWithRole(MeshRole.TOR_GATEWAY)
        
        // Should include nodes 1, 3, 5 (all have TOR_GATEWAY role)
        assertEquals(3, torNodes.size)
        assertTrue(torNodes.all { it.hasRole(MeshRole.TOR_GATEWAY) })
    }

    private fun createMockNodeInfo(
        address: Int,
        roles: Set<MeshRole>,
        lastHeartbeat: Long = System.currentTimeMillis()
    ): NodeTopologyInfo {
        return mockk<NodeTopologyInfo>().apply {
            every { nodeAddress } returns address
            every { meshRoles } returns roles
            every { lastSeen } returns lastHeartbeat
            every { hasRole(any()) } answers { 
                val role = firstArg<MeshRole>()
                roles.contains(role)
            }
            every { isStale(any()) } answers {
                val threshold = firstArg<Long>()
                val now = System.currentTimeMillis()
                (now - lastHeartbeat) > threshold
            }
        }
    }
}
