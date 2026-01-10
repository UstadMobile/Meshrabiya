package com.ustadmobile.meshrabiya.vnet

import android.content.Context
import com.ustadmobile.meshrabiya.mmcp.*
import org.junit.Test
import org.junit.Before
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever

/**
 * Deep debug test to understand why MESH_PARTICIPANT is not being assigned
 */
class EmergentRoleManagerDeepDebugTest {

    private lateinit var mockVirtualNode: VirtualNode
    private lateinit var mockContext: Context
    private lateinit var mockMeshRoleManager: MeshRoleManager
    private lateinit var emergentRoleManager: EmergentRoleManager

    @Before
    fun setup() {
        mockVirtualNode = mock(VirtualNode::class.java)
        mockContext = mock(Context::class.java)
        mockMeshRoleManager = mock(MeshRoleManager::class.java)
        
        // Setup basic virtual node behavior
        whenever(mockVirtualNode.neighbors()).thenReturn(emptyList())
        whenever(mockMeshRoleManager.userAllowsTorProxy).thenReturn(true)
        
        emergentRoleManager = EmergentRoleManager(mockVirtualNode, mockContext, mockMeshRoleManager)
    }

    @Test
    fun debugTargetRolesDirectly() {
        // Create a basic node similar to the failing test
        val basicNode = NodeCapabilitySnapshot(
            nodeId = "debug-node",
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
        
        // Test with default mesh intelligence (all zeros)
        val defaultMesh = MeshIntelligence(
            totalNodes = 0,
            activeGateways = 0,
            activeStorageNodes = 0,
            activeComputeNodes = 0,
            networkLoad = 0.0f,
            storageUtilization = 0.0f,
            computeUtilization = 0.0f
        )
        
        // Try to get current roles first
        val currentRoles = emergentRoleManager.currentMeshRoles.value
        println("=== DEEP DEBUG OUTPUT ===")
        println("Current roles in EmergentRoleManager: $currentRoles")
        
        val plan = emergentRoleManager.determineOptimalRoles(basicNode, defaultMesh, emptySet())
        
        println("Basic node details:")
        println("  nodeId: ${basicNode.nodeId}")
        println("  batteryLevel: ${basicNode.resources.batteryLevel}")
        println("  powerState: ${basicNode.resources.powerState}")
        println("  thermalState: ${basicNode.thermalState}")
        println("  networkQuality: ${basicNode.networkQuality}")
        println("  stability: ${basicNode.stability}")
        
        println("Mesh intelligence:")
        println("  totalNodes: ${defaultMesh.totalNodes}")
        println("  needsMoreGateways: ${defaultMesh.needsMoreGateways}")
        println("  needsMoreStorage: ${defaultMesh.needsMoreStorage}")
        println("  needsMoreCompute: ${defaultMesh.needsMoreCompute}")
        
        println("Passed currentRoles: emptySet()")
        
        println("Result plan:")
        println("  addRoles: ${plan.addRoles}")
        println("  removeRoles: ${plan.removeRoles}")
        println("  transitionDeadline: ${plan.transitionDeadline}")
        println("  fallbackNodes: ${plan.fallbackNodes}")
        
        println("Contains MESH_PARTICIPANT: ${plan.addRoles.contains(MeshRole.MESH_PARTICIPANT)}")
        println("Plan addRoles size: ${plan.addRoles.size}")
        println("==================")
        
        // This will fail so we can see the actual output in test results
        assert(plan.addRoles.contains(MeshRole.MESH_PARTICIPANT)) { 
            "MESH_PARTICIPANT should always be assigned. Actual roles: ${plan.addRoles}" 
        }
    }
}
