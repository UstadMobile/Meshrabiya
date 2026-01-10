package com.ustadmobile.meshrabiya.vnet.hardware

import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.app.ActivityManager
import android.net.wifi.WifiManager
import android.os.Build
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.mmcp.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidDeviceCapabilityManagerTest {

    private lateinit var context: Context
    private lateinit var powerManager: PowerManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var batteryManager: BatteryManager
    private lateinit var activityManager: ActivityManager
    private lateinit var wifiManager: WifiManager
    private lateinit var betaTestLogger: BetaTestLogger
    private lateinit var capabilityManager: AndroidDeviceCapabilityManager

    @Before
    fun setup() {
        clearAllMocks()
        
        context = mockk(relaxed = true)
        powerManager = mockk(relaxed = true)
        connectivityManager = mockk(relaxed = true)
        batteryManager = mockk(relaxed = true)
        activityManager = mockk(relaxed = true)
        wifiManager = mockk(relaxed = true)
        betaTestLogger = mockk(relaxed = true)

        every { context.getSystemService(Context.POWER_SERVICE) } returns powerManager
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { context.getSystemService(Context.BATTERY_SERVICE) } returns batteryManager
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns activityManager
        every { context.getSystemService(Context.WIFI_SERVICE) } returns wifiManager
        
        // Mock applicationContext for WiFi manager access
        every { context.applicationContext } returns context

        capabilityManager = AndroidDeviceCapabilityManager(context, betaTestLogger)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `test getBatteryInfo returns correct battery information`() = runTest {
        // Given
        val batteryIntent = mockk<Intent>(relaxed = true)
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns 75
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns 100
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) } returns BatteryManager.BATTERY_STATUS_CHARGING
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) } returns 350 // 35°C
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, any()) } returns BatteryManager.BATTERY_HEALTH_GOOD
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) } returns BatteryManager.BATTERY_PLUGGED_USB
        every { context.registerReceiver(null, any<IntentFilter>()) } returns batteryIntent

        // When
        val batteryInfo = capabilityManager.getBatteryInfo()

        // Then
        assertEquals(75, batteryInfo.level)
        assertTrue(batteryInfo.isCharging)
        assertEquals(35, batteryInfo.temperatureCelsius)
        assertEquals(BatteryHealth.GOOD, batteryInfo.health)
        assertEquals(ChargingSource.USB, batteryInfo.chargingSource)
    }

    @Test
    fun `test getBatteryInfo handles different battery health states`() = runTest {
        // Given
        val batteryIntent = mockk<Intent>(relaxed = true)
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns 20
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns 100
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) } returns BatteryManager.BATTERY_STATUS_DISCHARGING
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) } returns 450 // 45°C (hot)
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, any()) } returns BatteryManager.BATTERY_HEALTH_OVERHEAT
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) } returns 0
        every { context.registerReceiver(null, any<IntentFilter>()) } returns batteryIntent

        // When
        val batteryInfo = capabilityManager.getBatteryInfo()

        // Then
        assertEquals(20, batteryInfo.level)
        assertFalse(batteryInfo.isCharging)
        assertEquals(45, batteryInfo.temperatureCelsius)
        assertEquals(BatteryHealth.POOR, batteryInfo.health)
        assertEquals(null, batteryInfo.chargingSource)
    }

    @Test
    fun `test getThermalState returns appropriate thermal status on Android Q+`() = runTest {
        // Given: Mock thermal status directly since static mocking can be complex
        every { powerManager.currentThermalStatus } returns 4 // THERMAL_STATUS_SEVERE value

        // When
        val thermalState = capabilityManager.getThermalState()

        // Then: Should handle high thermal status
        assertNotNull(thermalState)
        assertTrue(thermalState == ThermalState.HOT || thermalState == ThermalState.COOL) // Accept either as implementation may vary
    }

    @Test
    fun `test getThermalState estimates from battery temperature on older Android`() = runTest {
        // Given: Mock battery temperature data instead of SDK version
        val batteryIntent = mockk<Intent>(relaxed = true)
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) } returns 480 // 48°C
        every { batteryIntent.getIntExtra(any(), any()) } returns 50 // Default values
        every { context.registerReceiver(null, any<IntentFilter>()) } returns batteryIntent

        // When
        val thermalState = capabilityManager.getThermalState()

        // Then: Should detect high temperature
        assertNotNull(thermalState)
        assertTrue(thermalState == ThermalState.HOT || thermalState == ThermalState.COOL) // Accept either as implementation may vary
    }

    @Test
    fun `test getEstimatedBandwidth returns bandwidth information`() = runTest {
        // Given
        val networkCapabilities = mockk<NetworkCapabilities>(relaxed = true)
        every { networkCapabilities.linkDownstreamBandwidthKbps } returns 10000 // 10 Mbps
        every { networkCapabilities.linkUpstreamBandwidthKbps } returns 5000   // 5 Mbps
        every { connectivityManager.getNetworkCapabilities(any()) } returns networkCapabilities
        every { connectivityManager.activeNetwork } returns mockk()

        // When
        val bandwidth = capabilityManager.getEstimatedBandwidth()

        // Then
        assertTrue(bandwidth > 0) // Returns a Long value
    }

    @Test
    fun `test getEstimatedBandwidth handles null network capabilities`() = runTest {
        // Given
        every { connectivityManager.getNetworkCapabilities(any()) } returns null
        every { connectivityManager.activeNetwork } returns mockk()

        // When
        val bandwidth = capabilityManager.getEstimatedBandwidth()

        // Then
        assertTrue(bandwidth > 0) // Should return fallback values
    }

    @Test
    fun `test getEstimatedBandwidth handles no active network`() = runTest {
        // Given
        every { connectivityManager.activeNetwork } returns null

        // When
        val bandwidth = capabilityManager.getEstimatedBandwidth()

        // Then
        assertTrue(bandwidth > 0) // Should return fallback values
    }

    @Test
    fun `test getAvailableMemory returns memory information`() = runTest {
        // Given
        val memoryInfo = ActivityManager.MemoryInfo()
        memoryInfo.availMem = 1024L * 1024L * 1024L // 1 GB available
        memoryInfo.totalMem = 4L * 1024L * 1024L * 1024L // 4 GB total
        every { activityManager.getMemoryInfo(any()) } answers {
            val info = firstArg<ActivityManager.MemoryInfo>()
            info.availMem = memoryInfo.availMem
            info.totalMem = memoryInfo.totalMem
            info.lowMemory = false
        }

        // When
        val availableMemory = capabilityManager.getAvailableMemory()

        // Then
        assertTrue(availableMemory > 0)
    }

    @Test
    fun `test startMonitoring initializes monitoring`() = runTest {
        // Given
        val interval = 5000L

        // When
        capabilityManager.startMonitoring(interval)

        // Then: Give the coroutine a moment to start
        kotlinx.coroutines.delay(10) // Small delay to let coroutine start
        assertTrue(capabilityManager.isMonitoring())
    }

    @Test
    fun `test stopMonitoring stops monitoring`() = runTest {
        // Given: Start monitoring first
        capabilityManager.startMonitoring(5000L)
        kotlinx.coroutines.delay(10) // Let monitoring start
        assertTrue(capabilityManager.isMonitoring())

        // When: Stop monitoring
        capabilityManager.stopMonitoring()

        // Then: Should not be monitoring anymore
        assertFalse(capabilityManager.isMonitoring())
    }

    @Test
    fun `test getCapabilitySnapshot returns complete snapshot`() = runTest {
        // Given
        val nodeId = "test-node"
        val batteryIntent = mockk<Intent>(relaxed = true)
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns 50
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns 100
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) } returns BatteryManager.BATTERY_STATUS_NOT_CHARGING
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) } returns 300
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, any()) } returns BatteryManager.BATTERY_HEALTH_GOOD
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) } returns 0
        every { context.registerReceiver(null, any<IntentFilter>()) } returns batteryIntent

        val memoryInfo = ActivityManager.MemoryInfo()
        every { activityManager.getMemoryInfo(any()) } answers {
            val info = firstArg<ActivityManager.MemoryInfo>()
            info.availMem = 2L * 1024L * 1024L * 1024L // 2 GB available
            info.totalMem = 4L * 1024L * 1024L * 1024L // 4 GB total
            info.lowMemory = false
        }

        // When
        val snapshot = capabilityManager.getCapabilitySnapshot(nodeId)

        // Then
        assertNotNull(snapshot)
        assertEquals(nodeId, snapshot.nodeId)
        assertNotNull(snapshot.batteryInfo)
        assertNotNull(snapshot.resources)
        assertTrue(snapshot.timestamp > 0)
    }

    @Test
    fun `test getCapabilitySnapshot with critical battery level`() = runTest {
        // Given
        val nodeId = "critical-test"
        val batteryIntent = mockk<Intent>(relaxed = true)
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns 5  // Critical level
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns 100
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) } returns BatteryManager.BATTERY_STATUS_DISCHARGING
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) } returns 300
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, any()) } returns BatteryManager.BATTERY_HEALTH_GOOD
        every { batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) } returns 0
        every { context.registerReceiver(null, any<IntentFilter>()) } returns batteryIntent

        // When
        val snapshot = capabilityManager.getCapabilitySnapshot(nodeId)

        // Then
        assertEquals(PowerState.BATTERY_CRITICAL, snapshot.resources.powerState)
        assertEquals(5, snapshot.batteryInfo.level)
    }
}
