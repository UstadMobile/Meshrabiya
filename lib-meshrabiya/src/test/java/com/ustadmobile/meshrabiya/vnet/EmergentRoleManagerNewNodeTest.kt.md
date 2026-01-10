package com.ustadmobile.meshrabiya.vnet

import android.content.Context
import com.ustadmobile.meshrabiya.mmcp.*
import org.junit.Test
import org.junit.Before
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever
import kotlin.test.assertTrue

/**
 * Test to verify that MESH_PARTICIPANT is unconditionally assigned to new nodes
 */
class EmergentRoleManagerNewNodeTest {

    private lateinit var mockVirtualNode: VirtualNode
    private lateinit var mockContext: Context
    private lateinit var emergentRoleManager: EmergentRoleManager

    @Before
    fun setup() {
        mockVirtualNode = mock(VirtualNode::class.java)
        mockContext = mock(Context::class.java)
        
        // Setup basic virtual node behavior
        whenever(mockVirtualNode.neighbors()).thenReturn(emptyList())
        
        emergentRoleManager = EmergentRoleManager(mockVirtualNode, mockContext).apply {
            userAllowsTorProxy = true
        }
    }

    @Test
    fun testNewNodeAlwaysGetsMeshParticipant() {
        // Create a very basic, low-capability node
        val basicNode = NodeCapabilitySnapshot(
            nodeId = "basic-new-node",
            resources = ResourceCapabilities(
                availableCPU = 0.1f, // Low CPU
                availableRAM = 100_000L, // Low RAM
                availableBandwidth = 1000L, // Low bandwidth
                storageOffered = 0L, // No storage
                batteryLevel = 10, // Low battery
                thermalThrottling = true, // Thermal issues
                powerState = PowerState.BATTERY_LOW,
                networkInterfaces = emptySet()
            ),
            batteryInfo = BatteryInfo(
                level = 10, 
                isCharging = false, 
                estimatedTimeRemaining = null,
                temperatureCelsius = 80, // Hot
                health = BatteryHealth.POOR,
                chargingSource = null
            ),
            thermalState = ThermalState.CRITICAL, // Critical thermal state
            networkQuality = 0.1f, // Poor network
            stability = 0.2f // Poor stability
        )
        
        // Test with empty current roles (simulating a brand new node)
        val plan = emergentRoleManager.determineOptimalRoles(
            nodeCapabilities = basicNode,
            currentRoles = emptySet() // This is key - simulating a new node with no current roles
        )
        
        println("=== NEW NODE TEST ===")
        println("Node: ${basicNode.nodeId}")
        println("Roles to add: ${plan.addRoles}")
        println("Contains MESH_PARTICIPANT: ${plan.addRoles.contains(MeshRole.MESH_PARTICIPANT)}")
        println("=====================")
        
        // The node should get MESH_PARTICIPANT regardless of poor capabilities
        assertTrue(
            plan.addRoles.contains(MeshRole.MESH_PARTICIPANT), 
            "Even a low-capability node should get MESH_PARTICIPANT role. Actual roles: ${plan.addRoles}"
        )
    }

    @Test
    fun testHighCapabilityNodeGetsMultipleRoles() {
        // Create a high-capability node
        val highCapabilityNode = NodeCapabilitySnapshot(
            nodeId = "high-capability-node",
            resources = ResourceCapabilities(
                availableCPU = 0.9f,
                availableRAM = 2_000_000_000L,
                availableBandwidth = 100_000_000L,
                storageOffered = 10_000_000L,
                batteryLevel = 100,
                thermalThrottling = false,
                powerState = PowerState.BATTERY_HIGH,
                networkInterfaces = emptySet()
            ),
            batteryInfo = BatteryInfo(
                level = 100, 
                isCharging = true, 
                estimatedTimeRemaining = null,
                temperatureCelsius = 25,
                health = BatteryHealth.GOOD,
                chargingSource = ChargingSource.USB
            ),
            thermalState = ThermalState.COOL,
            networkQuality = 1.0f,
            stability = 1.0f
        )
        
        // Create a mesh that needs more of all role types
        val needyMesh = MeshIntelligence(
            totalNodes = 10,
            activeGateways = 1, // Needs more gateways
            activeStorageNodes = 1, // Needs more storage
            activeComputeNodes = 1, // Needs more compute
            networkLoad = 0.9f,
            storageUtilization = 0.9f,
            computeUtilization = 0.9f
        )
        
        // Test with empty current roles (simulating a brand new high-capability node)
        val plan = emergentRoleManager.determineOptimalRoles(
            nodeCapabilities = highCapabilityNode,
            meshIntelligence = needyMesh,
            currentRoles = emptySet()
        )
        
        println("=== HIGH CAPABILITY NODE TEST ===")
        println("Node: ${highCapabilityNode.nodeId}")
        println("Roles to add: ${plan.addRoles}")
        println("Total roles: ${plan.addRoles.size}")
        println("==================================")
        
        // Should get MESH_PARTICIPANT plus additional roles
        assertTrue(
            plan.addRoles.contains(MeshRole.MESH_PARTICIPANT), 
            "High-capability node should definitely get MESH_PARTICIPANT. Actual roles: ${plan.addRoles}"
        )
        
        assertTrue(
            plan.addRoles.size > 1, 
            "High-capability node should get multiple roles in a needy network. Actual roles: ${plan.addRoles}"
        )
    }
}
