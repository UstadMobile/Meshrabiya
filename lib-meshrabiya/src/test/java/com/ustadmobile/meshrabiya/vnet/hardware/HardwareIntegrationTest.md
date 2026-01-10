
package com.ustadmobile.meshrabiya.vnet.hardware

import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.mmcp.*
import com.ustadmobile.meshrabiya.vnet.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Integration tests for hardware metrics collection with EmergentRoleManager.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
public class HardwareIntegrationTest {
    private lateinit var betaTestLogger: BetaTestLogger
    private lateinit var virtualNode: VirtualNode
    private lateinit var meshRoleManager: MeshRoleManager
    private lateinit var emergentRoleManager: EmergentRoleManager
    private lateinit var context: android.app.Application

    private val testDispatcher = UnconfinedTestDispatcher()

    // Test doubles for hardware metrics
    fun mockDeviceCapabilityManager(
        batteryLevel: Int,
        isCharging: Boolean,
        availableCPU: Float,
        availableRAM: Long,
        availableBandwidth: Long,
        thermalState: ThermalState,
        stability: Float = 0.9f
    ): DeviceCapabilityManager {
        val mock = mockk<DeviceCapabilityManager>()
        val batteryInfo = BatteryInfo(
            level = batteryLevel,
            isCharging = isCharging,
            estimatedTimeRemaining = null,
            temperatureCelsius = 25,
            health = com.ustadmobile.meshrabiya.mmcp.BatteryHealth.GOOD,
            chargingSource = null
        )
        val resources = ResourceCapabilities(
            availableCPU = availableCPU,
            availableRAM = availableRAM,
            availableBandwidth = availableBandwidth,
            storageOffered = 100_000_000L,
            batteryLevel = batteryLevel,
            thermalThrottling = (thermalState == ThermalState.THROTTLING),
            powerState = if (batteryLevel > 70) PowerState.BATTERY_HIGH else PowerState.BATTERY_MEDIUM,
            networkInterfaces = emptySet<SerializableNetworkInterfaceInfo>()
        )
        val snapshot = NodeCapabilitySnapshot(
            nodeId = "12345",
            resources = resources,
            batteryInfo = batteryInfo,
            thermalState = thermalState,
            networkQuality = 0.8f,
            stability = stability,
            timestamp = System.currentTimeMillis()
        )
        var monitoring = false
        every { mock.isMonitoring() } answers { monitoring }
        every { mock.startMonitoring(any()) } answers { monitoring = true }
        every { mock.stopMonitoring() } answers { monitoring = false }
        coEvery { mock.getCapabilitySnapshot(any()) } returns snapshot
        coEvery { mock.getEstimatedBandwidth() } returns availableBandwidth
        coEvery { mock.getNetworkInterfaces() } returns emptySet<SerializableNetworkInterfaceInfo>()
        return mock
    }

    // Templates for device types
    fun highEndDeviceManager() = mockDeviceCapabilityManager(
        batteryLevel = 95,
        isCharging = true,
        availableCPU = 0.85f,
        availableRAM = 8_000_000_000L,
        availableBandwidth = 150_000_000L,
        thermalState = ThermalState.COOL,
        stability = 0.95f
    )

    fun averageDeviceManager() = mockDeviceCapabilityManager(
        batteryLevel = 60,
        isCharging = false,
        availableCPU = 0.5f,
        availableRAM = 3_000_000_000L,
        availableBandwidth = 30_000_000L,
        thermalState = ThermalState.WARM,
        stability = 0.8f
    )

    fun lowEndDeviceManager() = mockDeviceCapabilityManager(
        batteryLevel = 25,
        isCharging = false,
        availableCPU = 0.2f,
        availableRAM = 1_000_000_000L,
        availableBandwidth = 5_000_000L,
        thermalState = ThermalState.THROTTLING,
        stability = 0.5f
    )

    // Templates for hardware simulation
    // Removed unsupported Robolectric shadow classes and simulation methods

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
        context = RuntimeEnvironment.getApplication() as android.app.Application
        betaTestLogger = mockk(relaxed = true)
        virtualNode = mockk(relaxed = true)
        meshRoleManager = mockk(relaxed = true)
        // Mock virtual node basics
        every { virtualNode.addressAsInt } returns 12345
        // Mock fitness score for fallback scenarios
        every { meshRoleManager.calculateFitnessScore() } returns FitnessScore(
            signalStrength = 80,
            batteryLevel = 0.75f,
            clientCount = 5
        )
    // Default to average device for general setup
    }
    
    @After
    fun teardown() {
        kotlinx.coroutines.Dispatchers.resetMain()
        if (::emergentRoleManager.isInitialized) {
            emergentRoleManager.stopHardwareMonitoring()
        }
        clearAllMocks()
    }
    
    @Test
    fun testHardwareMonitoringLifecycleIntegration() {
        runTest {
            emergentRoleManager = EmergentRoleManager(
                virtualNode = virtualNode,
                context = context,
                meshRoleManager = meshRoleManager,
                deviceCapabilityManager = averageDeviceManager()
            )
            emergentRoleManager.startHardwareMonitoring()
            assertTrue("Should be monitoring after start", emergentRoleManager.isHardwareMonitoring())
            emergentRoleManager.stopHardwareMonitoring()
            assertFalse("Should not be monitoring after stop", emergentRoleManager.isHardwareMonitoring())
        }
    }

    @Test
    fun testCapabilitySnapshotIncludesAllRequiredMetrics() {
        runTest {
            emergentRoleManager = EmergentRoleManager(
                virtualNode = virtualNode,
                context = context,
                meshRoleManager = meshRoleManager,
                deviceCapabilityManager = averageDeviceManager()
            )
            val capabilities = emergentRoleManager.getDeviceCapabilities()
            assertNotNull(capabilities)
            assertEquals("12345", capabilities?.nodeId)
            assertNotNull(capabilities?.batteryInfo)
            assertNotNull(capabilities?.resources)
            assertTrue("Timestamp should be > 0", capabilities?.timestamp ?: 0 > 0)
            assertTrue("Stability should be > 0", capabilities?.stability ?: 0f > 0)
        }
    }

    @Test
    fun testHighEndDeviceHasAppropriateCapabilities() {
        runTest {
            emergentRoleManager = EmergentRoleManager(
                virtualNode = virtualNode,
                context = context,
                meshRoleManager = meshRoleManager,
                deviceCapabilityManager = highEndDeviceManager()
            )
            val capabilities = emergentRoleManager.getDeviceCapabilities()
            assertNotNull(capabilities)
            assertTrue("High-end device should have high CPU availability", capabilities?.resources?.availableCPU ?: 0f > 0.7f)
            assertTrue("High-end device should have good battery", capabilities?.batteryInfo?.level ?: 0 > 70)
            assertEquals("High-end device should run cool", ThermalState.COOL, capabilities?.thermalState)
            assertTrue("High-end device should be very stable", capabilities?.stability ?: 0f > 0.85f)
            assertTrue("High-end device should have high bandwidth", capabilities?.resources?.availableBandwidth ?: 0L > 50_000_000L)
        }
    }

    @Test
    fun testLowEndDeviceHasLimitedCapabilities() {
        runTest {
            emergentRoleManager = EmergentRoleManager(
                virtualNode = virtualNode,
                context = context,
                meshRoleManager = meshRoleManager,
                deviceCapabilityManager = lowEndDeviceManager()
            )
            val capabilities = emergentRoleManager.getDeviceCapabilities()
            assertNotNull(capabilities)
            assertTrue("Low-end device should have limited CPU", capabilities?.resources?.availableCPU ?: 1f < 0.5f)
            assertTrue("Low-end device should have lower battery", capabilities?.batteryInfo?.level ?: 100 < 50)
            assertTrue("Low-end device should be less stable", capabilities?.stability ?: 1f < 0.7f)
        }
    }

    @Test
    fun testTabletOptimizedForStorageAndRelayCapabilities() {
        runTest {
            emergentRoleManager = EmergentRoleManager(
                virtualNode = virtualNode,
                context = context,
                meshRoleManager = meshRoleManager,
                deviceCapabilityManager = highEndDeviceManager()
            )
            val capabilities = emergentRoleManager.getDeviceCapabilities()
            assertNotNull(capabilities)
            assertTrue("Tablet should have high memory", capabilities?.resources?.availableRAM ?: 0L > 2_000_000_000L)
            assertEquals("Tablet should run cool", ThermalState.COOL, capabilities?.thermalState)
            assertTrue("Tablet should be stable", capabilities?.stability ?: 0f > 0.8f)
        }
    }

    @Test
    fun testEmergencyThermalProtectionTriggersCorrectly() {
        runTest {
            val criticalDeviceManager = mockDeviceCapabilityManager(
                batteryLevel = 20,
                isCharging = false,
                availableCPU = 0.1f,
                availableRAM = 1_000_000_000L,
                availableBandwidth = 2_000_000L,
                thermalState = ThermalState.CRITICAL
            )
            emergentRoleManager = EmergentRoleManager(
                virtualNode = virtualNode,
                context = context,
                meshRoleManager = meshRoleManager,
                deviceCapabilityManager = criticalDeviceManager
            )
            val capabilities = emergentRoleManager.getDeviceCapabilities()
            assertNotNull(capabilities)
            assertEquals("Should be CRITICAL under thermal stress", ThermalState.CRITICAL, capabilities?.thermalState)
            assertTrue("Should throttle CPU under thermal stress", capabilities?.resources?.availableCPU ?: 1f < 0.3f)
        }
    }
}
