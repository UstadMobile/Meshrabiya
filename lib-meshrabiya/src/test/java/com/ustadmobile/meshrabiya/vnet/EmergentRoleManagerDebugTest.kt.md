package com.ustadmobile.meshrabiya.vnet

import android.content.Context
import com.ustadmobile.meshrabiya.mmcp.*
import org.junit.Test
import org.junit.Before
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever
import kotlin.test.assertTrue

/**
 * Simple debug test to understand what EmergentRoleManager actually returns
 */
class EmergentRoleManagerDebugTest {

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
    fun debugBasicNodeRoles() {
        // Create a basic node similar to the failing test
        val basicNode = NodeCapabilitySnapshot(
            nodeId = "basic-node",
            resources = ResourceCapabilities(
                availableCPU = 0.2f,
                availableRAM = 200_000_000L,
                availableBandwidth = 1_000_000L,
                storageOffered = 100_000L,
                batteryLevel = 50,
                thermalThrottling = false,
                powerState = PowerState.BATTERY_MEDIUM,
                networkInterfaces = emptySet()
            ),
            batteryInfo = BatteryInfo(
                level = 50, 
                isCharging = false, 
                estimatedTimeRemaining = null,
                temperatureCelsius = 35,
                health = BatteryHealth.GOOD,
                chargingSource = null
            ),
            thermalState = ThermalState.WARM,
            networkQuality = 0.5f,
            stability = 0.6f
        )
        
        val plan = emergentRoleManager.determineOptimalRoles(
            nodeCapabilities = basicNode,
            currentRoles = emptySet() // Simulate a new node joining
        )
        
        println("=== DEBUG OUTPUT ===")
        println("Node ID: ${basicNode.nodeId}")
        println("Plan addRoles: ${plan.addRoles}")
        println("Plan removeRoles: ${plan.removeRoles}")
        println("Contains MESH_PARTICIPANT: ${plan.addRoles.contains(MeshRole.MESH_PARTICIPANT)}")
        println("All roles: ${plan.addRoles.joinToString(", ")}")
        println("==================")
        
        // Verify that the new node gets MESH_PARTICIPANT role
        assertTrue(plan.addRoles.contains(MeshRole.MESH_PARTICIPANT), "New node should get MESH_PARTICIPANT role")
    }
}
