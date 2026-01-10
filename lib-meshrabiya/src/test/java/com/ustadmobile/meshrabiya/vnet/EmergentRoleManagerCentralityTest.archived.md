package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.vnet.hardware.DeviceCapabilityManager
import com.ustadmobile.meshrabiya.vnet.hardware.ResourceCapabilities
import com.ustadmobile.meshrabiya.vnet.hardware.BatteryInfo
import com.ustadmobile.meshrabiya.vnet.hardware.ThermalState
import com.ustadmobile.meshrabiya.vnet.topology.NodeTopologyInfo
import org.junit.Test
import org.junit.Assert.*

/**
 * Test 3: EmergentRoleManager Centrality with Topology
 * Verifies that EmergentRoleManager can calculate BFS centrality using topology callback
 * UPDATED: Now uses NodeTopologyInfo instead of Map<Int, Set<Int>>
 */
class EmergentRoleManagerCentralityTest {
    
    @Test
    fun testBFSCentralityWithTopologyCallback() {
        // Create mock topology representing a 4-node mesh
        // UPDATED: Using NodeTopologyInfo instead of Set<Int>
        val mockTopology = mapOf(
            192837464 to NodeTopologyInfo(
                nodeAddress = 192837464,
                neighbors = setOf(192837465, 192837466),
                meshRoles = setOf(MeshRole.MESH_ROUTER),
                centralityScore = 0.5f,
                fitnessScore = 0.6f,
                lastSeen = System.currentTimeMillis(),
                pingTime = 50
            ),
            192837465 to NodeTopologyInfo(
                nodeAddress = 192837465,
                neighbors = setOf(192837464, 192837467),
                meshRoles = setOf(MeshRole.MESH_ROUTER),
                centralityScore = 0.5f,
                fitnessScore = 0.6f,
                lastSeen = System.currentTimeMillis(),
                pingTime = 50
            ),
            192837466 to NodeTopologyInfo(
                nodeAddress = 192837466,
                neighbors = setOf(192837464, 192837467),
                meshRoles = setOf(MeshRole.MESH_ROUTER),
                centralityScore = 0.5f,
                fitnessScore = 0.6f,
                lastSeen = System.currentTimeMillis(),
                pingTime = 50
            ),
            192837467 to NodeTopologyInfo(
                nodeAddress = 192837467,
                neighbors = setOf(192837465, 192837466),
                meshRoles = setOf(MeshRole.MESH_ROUTER),
                centralityScore = 0.5f,
                fitnessScore = 0.6f,
                lastSeen = System.currentTimeMillis(),
                pingTime = 50
            )
        )
        
        // Create mock device capabilities
        val mockCapabilities = NodeCapabilitySnapshot(
            nodeId = "192837464",
            resources = ResourceCapabilities(
                availableMemoryMB = 2048,
                totalMemoryMB = 4096,
                cpuCores = 4,
                cpuUsagePercent = 30,
                batteryInfo = BatteryInfo(
                    level = 80,
                    isCharging = true,
                    temperature = 30
                ),
                thermalState = ThermalState.COOL,
                networkInterfaces = emptyList()
            ),
            batteryInfo = BatteryInfo(
                level = 80,
                isCharging = true,
                temperature = 30
            ),
            thermalState = ThermalState.COOL,
            networkQuality = 0.8f,
            stability = 0.7f
        )
        
        // Create mock VirtualNode with Context
        val mockContext = EnhancedMockContextProvider.createFullMockContext()
        val mockVirtualNode = object : VirtualNode(mockContext) {
            override val addressAsInt: Int = 192837464
        }
        
        // Create EmergentRoleManager with topology callback
        val manager = EmergentRoleManager(
            virtualNode = mockVirtualNode,
            context = mockContext,
            getTopologyMap = { mockTopology },
            getCurrentNodeCapabilities = { mockCapabilities }
        )
        
        // Calculate centrality score
        val centralityResult = manager.getCentralityResult()
        
        // Verify centrality was calculated
        assertTrue(
            "Centrality score should be greater than 0",
            centralityResult.centralityScore > 0f
        )
        assertTrue(
            "Should reach at least 3 nodes in the topology",
            centralityResult.reachableNodes >= 3
        )
    }
    
    @Test
    fun testBFSCentralityWithLinearTopology() {
        // Linear topology: A -> B -> C -> D
        // UPDATED: Using NodeTopologyInfo
        val mockTopology = mapOf(
            192837464 to NodeTopologyInfo(
                nodeAddress = 192837464,
                neighbors = setOf(192837465),
                meshRoles = setOf(MeshRole.MESH_ROUTER),
                centralityScore = 0.3f,
                fitnessScore = 0.5f,
                lastSeen = System.currentTimeMillis(),
                pingTime = 50
            ),
            192837465 to NodeTopologyInfo(
                nodeAddress = 192837465,
                neighbors = setOf(192837464, 192837466),
                meshRoles = setOf(MeshRole.MESH_ROUTER),
                centralityScore = 0.6f,
                fitnessScore = 0.7f,
                lastSeen = System.currentTimeMillis(),
                pingTime = 50
            ),
            192837466 to NodeTopologyInfo(
                nodeAddress = 192837466,
                neighbors = setOf(192837465, 192837467),
                meshRoles = setOf(MeshRole.MESH_ROUTER),
                centralityScore = 0.6f,
                fitnessScore = 0.7f,
                lastSeen = System.currentTimeMillis(),
                pingTime = 50
            ),
            192837467 to NodeTopologyInfo(
                nodeAddress = 192837467,
                neighbors = setOf(192837466),
                meshRoles = setOf(MeshRole.MESH_ROUTER),
                centralityScore = 0.3f,
                fitnessScore = 0.5f,
                lastSeen = System.currentTimeMillis(),
                pingTime = 50
            )
        )
        
        val mockCapabilities = NodeCapabilitySnapshot(
            nodeId = "192837465",
            resources = ResourceCapabilities(
                availableMemoryMB = 2048,
                totalMemoryMB = 4096,
                cpuCores = 4,
                cpuUsagePercent = 30,
                batteryInfo = BatteryInfo(level = 80, isCharging = true, temperature = 30),
                thermalState = ThermalState.COOL,
                networkInterfaces = emptyList()
            ),
            batteryInfo = BatteryInfo(level = 80, isCharging = true, temperature = 30),
            thermalState = ThermalState.COOL,
            networkQuality = 0.8f,
            stability = 0.7f
        )
        
        val mockContext = EnhancedMockContextProvider.createFullMockContext()
        val mockVirtualNode = object : VirtualNode(mockContext) {
            override val addressAsInt: Int = 192837465
        }
        
        val manager = EmergentRoleManager(
            virtualNode = mockVirtualNode,
            context = mockContext,
            getTopologyMap = { mockTopology },
            getCurrentNodeCapabilities = { mockCapabilities }
        )
        
        // Node 192837465 (B) should have higher centrality than 192837464 (A)
        // because it's more central in the linear topology
        val centralityResult = manager.getCentralityResult()
        
        assertTrue("Should reach all 4 nodes", centralityResult.reachableNodes >= 3)
        assertTrue("Centrality should be positive", centralityResult.centralityScore > 0f)
    }
    
    @Test
    fun testBFSCentralityWithIsolatedNode() {
        // Isolated node topology - only self in map
        // UPDATED: Using NodeTopologyInfo
        val mockTopology = mapOf(
            192837464 to NodeTopologyInfo(
                nodeAddress = 192837464,
                neighbors = emptySet(),
                meshRoles = setOf(MeshRole.MESH_PARTICIPANT),
                centralityScore = 0f,
                fitnessScore = 0.5f,
                lastSeen = System.currentTimeMillis(),
                pingTime = 50
            )
        )
        
        val mockCapabilities = NodeCapabilitySnapshot(
            nodeId = "192837464",
            resources = ResourceCapabilities(
                availableMemoryMB = 2048,
                totalMemoryMB = 4096,
                cpuCores = 4,
                cpuUsagePercent = 30,
                batteryInfo = BatteryInfo(level = 80, isCharging = true, temperature = 30),
                thermalState = ThermalState.COOL,
                networkInterfaces = emptyList()
            ),
            batteryInfo = BatteryInfo(level = 80, isCharging = true, temperature = 30),
            thermalState = ThermalState.COOL,
            networkQuality = 0.8f,
            stability = 0.7f
        )
        
        val mockContext = EnhancedMockContextProvider.createFullMockContext()
        val mockVirtualNode = object : VirtualNode(mockContext) {
            override val addressAsInt: Int = 192837464
        }
        
        val manager = EmergentRoleManager(
            virtualNode = mockVirtualNode,
            context = mockContext,
            getTopologyMap = { mockTopology },
            getCurrentNodeCapabilities = { mockCapabilities }
        )
        
        val centralityResult = manager.getCentralityResult()
        
        // Isolated node should have low centrality
        assertTrue("Reachable nodes should be 0 (no other nodes)", centralityResult.reachableNodes == 0)
        assertTrue("Centrality should be low for isolated node", centralityResult.centralityScore < 0.3f)
    }
    
    @Test
    fun testBFSCentralityWithStarTopology() {
        // Star topology: central node connected to 3 leaf nodes
        val centralNode = 192837464
        // UPDATED: Using NodeTopologyInfo
        val mockTopology = mapOf(
            centralNode to NodeTopologyInfo(
                nodeAddress = centralNode,
                neighbors = setOf(192837465, 192837466, 192837467),
                meshRoles = setOf(MeshRole.MESH_ROUTER),
                centralityScore = 0.8f,
                fitnessScore = 0.9f,
                lastSeen = System.currentTimeMillis(),
                pingTime = 50
            ),
            192837465 to NodeTopologyInfo(
                nodeAddress = 192837465,
                neighbors = setOf(centralNode),
                meshRoles = setOf(MeshRole.MESH_PARTICIPANT),
                centralityScore = 0.3f,
                fitnessScore = 0.5f,
                lastSeen = System.currentTimeMillis(),
                pingTime = 50
            ),
            192837466 to NodeTopologyInfo(
                nodeAddress = 192837466,
                neighbors = setOf(centralNode),
                meshRoles = setOf(MeshRole.MESH_PARTICIPANT),
                centralityScore = 0.3f,
                fitnessScore = 0.5f,
                lastSeen = System.currentTimeMillis(),
                pingTime = 50
            ),
            192837467 to NodeTopologyInfo(
                nodeAddress = 192837467,
                neighbors = setOf(centralNode),
                meshRoles = setOf(MeshRole.MESH_PARTICIPANT),
                centralityScore = 0.3f,
                fitnessScore = 0.5f,
                lastSeen = System.currentTimeMillis(),
                pingTime = 50
            )
        )
        
        val mockCapabilities = NodeCapabilitySnapshot(
            nodeId = centralNode.toString(),
            resources = ResourceCapabilities(
                availableMemoryMB = 2048,
                totalMemoryMB = 4096,
                cpuCores = 4,
                cpuUsagePercent = 30,
                batteryInfo = BatteryInfo(level = 80, isCharging = true, temperature = 30),
                thermalState = ThermalState.COOL,
                networkInterfaces = emptyList()
            ),
            batteryInfo = BatteryInfo(level = 80, isCharging = true, temperature = 30),
            thermalState = ThermalState.COOL,
            networkQuality = 0.8f,
            stability = 0.7f
        )
        
        val mockContext = EnhancedMockContextProvider.createFullMockContext()
        val mockVirtualNode = object : VirtualNode(mockContext) {
            override val addressAsInt: Int = centralNode
        }
        
        val manager = EmergentRoleManager(
            virtualNode = mockVirtualNode,
            context = mockContext,
            getTopologyMap = { mockTopology },
            getCurrentNodeCapabilities = { mockCapabilities }
        )
        
        val centralityResult = manager.getCentralityResult()
        
        // Central node should reach all 3 leaf nodes
        assertTrue("Central node should reach all 3 leaf nodes", centralityResult.reachableNodes >= 3)
        assertTrue("Central node should have high centrality", centralityResult.centralityScore > 0.5f)
    }
}
