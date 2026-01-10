package com.ustadmobile.meshrabiya.vnet

import android.content.Context
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class MeshRoleManagerTest {
    private lateinit var context: Context
    private lateinit var virtualNode: VirtualNode
    private lateinit var roleManager: MeshRoleManager

    @Before
    fun setup() {
        context = org.mockito.Mockito.mock(Context::class.java)
        virtualNode = object : VirtualNode(port = 12345) {
            override fun getContext(): Context? = null  // Return null in tests to avoid service initialization
            override val meshrabiyaWifiManager = object : com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager {
                override val state = emptyFlow<MeshrabiyaWifiState>()
                override val is5GhzSupported: Boolean = true
                override suspend fun requestHotspot(requestMessageId: Int, request: com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotRequest) =
                    com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse(requestMessageId, 0, null, 0)
                override suspend fun deactivateHotspot() {}
                override suspend fun connectToHotspot(config: com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig, timeout: Long) {}
            }
            override fun getCurrentFitnessScore(): Int = 100
            override fun getCurrentNodeRole(): Byte = 1
            override val originatingMessageManager = OriginatingMessageManager(
                localNodeInetAddr = address,
                logger = logger,
                scheduledExecutor = scheduledExecutor,
                nextMmcpMessageId = { nextMmcpMessageId() },
                getWifiState = { currentNodeState.wifiState },
                getFitnessScore = { getCurrentFitnessScore() },
                getNodeRole = { getCurrentNodeRole() }
            )
            override val currentNodeState: LocalNodeState
                get() = LocalNodeState(wifiState = MeshrabiyaWifiState())
        }
        roleManager = MeshRoleManager(virtualNode, context)
    }

    @Test
    fun testInitialRoleIsMeshNode() {
        assertEquals(NodeRole.MESH_NODE, roleManager.currentRole.value)
    }

    @Test
    fun testRoleUpdateWithHighFitness() {
        roleManager.userAllowsTorProxy = false
        roleManager.chokePointFlag = false
        roleManager.updateRole()
        assertEquals(NodeRole.MESH_NODE, roleManager.currentRole.value)
    }

    @Test
    fun testRoleUpdateWithLowFitness() {
        val lowFitnessNode = object : VirtualNode(port = 12345) {
            override fun getContext(): Context? = null  // Return null in tests
            override val meshrabiyaWifiManager = object : com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager {
                override val state = emptyFlow<MeshrabiyaWifiState>()
                override val is5GhzSupported: Boolean = true
                override suspend fun requestHotspot(requestMessageId: Int, request: com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotRequest) =
                    com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse(requestMessageId, 0, null, 0)
                override suspend fun deactivateHotspot() {}
                override suspend fun connectToHotspot(config: com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig, timeout: Long) {}
            }
            override fun getCurrentFitnessScore(): Int = 10
            override fun getCurrentNodeRole(): Byte = 0
            override val originatingMessageManager = OriginatingMessageManager(
                localNodeInetAddr = address,
                logger = logger,
                scheduledExecutor = scheduledExecutor,
                nextMmcpMessageId = { nextMmcpMessageId() },
                getWifiState = { currentNodeState.wifiState },
                getFitnessScore = { getCurrentFitnessScore() },
                getNodeRole = { getCurrentNodeRole() }
            )
            override val currentNodeState: LocalNodeState
                get() = LocalNodeState(wifiState = MeshrabiyaWifiState())
        }
        val lowFitnessManager = MeshRoleManager(lowFitnessNode, context)
        lowFitnessManager.updateRole()
        assertEquals(NodeRole.CLIENT, lowFitnessManager.currentRole.value)
    }

    @Test
    fun testRoleUpdateWithBridgeFitness() {
        val bridgeFitnessNode = object : VirtualNode(port = 12345) {
            override fun getContext(): Context? = null  // Return null in tests
            override val meshrabiyaWifiManager = object : com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager {
                override val state = emptyFlow<MeshrabiyaWifiState>()
                override val is5GhzSupported: Boolean = true
                override suspend fun requestHotspot(requestMessageId: Int, request: com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotRequest) =
                    com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse(requestMessageId, 0, null, 0)
                override suspend fun deactivateHotspot() {}
                override suspend fun connectToHotspot(config: com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig, timeout: Long) {}
            }
            override fun getCurrentFitnessScore(): Int = 60
            override fun getCurrentNodeRole(): Byte = 2
            override val originatingMessageManager = OriginatingMessageManager(
                localNodeInetAddr = address,
                logger = logger,
                scheduledExecutor = scheduledExecutor,
                nextMmcpMessageId = { nextMmcpMessageId() },
                getWifiState = { currentNodeState.wifiState },
                getFitnessScore = { getCurrentFitnessScore() },
                getNodeRole = { getCurrentNodeRole() }
            )
            override val currentNodeState: LocalNodeState
                get() = LocalNodeState(wifiState = MeshrabiyaWifiState())
        }
        val bridgeManager = MeshRoleManager(bridgeFitnessNode, context)
        bridgeManager.updateRole()
        assertEquals(NodeRole.BRIDGE, bridgeManager.currentRole.value)
    }

    @Test
    fun testRoleUpdateWithTorProxyAllowed() {
        roleManager.userAllowsTorProxy = true
        roleManager.updateRole()
        assertEquals(NodeRole.MESH_NODE, roleManager.currentRole.value)
    }

    @Test
    fun testFitnessScoreCalculation() {
        val score = roleManager.calculateFitnessScore()
        assertTrue(score.signalStrength >= 0)
        assertTrue(score.batteryLevel >= 0f)
        assertTrue(score.clientCount >= 0)
    }

    @Test
    fun testCentralityScoreCalculation() {
        val centrality = roleManager.calculateCentralityScore()
        assertTrue(centrality >= 0f)
    }
}
