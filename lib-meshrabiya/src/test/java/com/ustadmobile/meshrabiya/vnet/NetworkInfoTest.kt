package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.model.NetworkInfo
import org.junit.Test
import kotlin.test.*

/**
 * Unit tests for NetworkInfo gateway statistics.
 * Tests gateway counting and totalGateways computed property.
 */
class NetworkInfoTest {

    @Test
    fun `totalGateways is sum of tor and clearnet gateways`() {
        val networkInfo = NetworkInfo(
            torGateways = 3,
            clearnetGateways = 2
        )
        
        assertEquals(5, networkInfo.totalGateways)
    }

    @Test
    fun `totalGateways is zero when no gateways`() {
        val networkInfo = NetworkInfo(
            torGateways = 0,
            clearnetGateways = 0
        )
        
        assertEquals(0, networkInfo.totalGateways)
    }

    @Test
    fun `totalGateways works with only tor gateways`() {
        val networkInfo = NetworkInfo(
            torGateways = 4,
            clearnetGateways = 0
        )
        
        assertEquals(4, networkInfo.totalGateways)
    }

    @Test
    fun `totalGateways works with only clearnet gateways`() {
        val networkInfo = NetworkInfo(
            torGateways = 0,
            clearnetGateways = 3
        )
        
        assertEquals(3, networkInfo.totalGateways)
    }

    @Test
    fun `default values are zero`() {
        val networkInfo = NetworkInfo()
        
        assertEquals(0, networkInfo.torGateways)
        assertEquals(0, networkInfo.clearnetGateways)
        assertEquals(0, networkInfo.totalGateways)
        assertEquals(0, networkInfo.connectedPeers)
        assertEquals("", networkInfo.ssid)
        assertEquals("", networkInfo.bssid)
        assertEquals("", networkInfo.ipAddress)
        assertFalse(networkInfo.isConnected)
    }

    @Test
    fun `all fields can be set`() {
        val networkInfo = NetworkInfo(
            ssid = "TestMesh",
            bssid = "00:11:22:33:44:55",
            ipAddress = "192.168.1.100",
            connectedPeers = 5,
            isConnected = true,
            torGateways = 2,
            clearnetGateways = 3
        )
        
        assertEquals("TestMesh", networkInfo.ssid)
        assertEquals("00:11:22:33:44:55", networkInfo.bssid)
        assertEquals("192.168.1.100", networkInfo.ipAddress)
        assertEquals(5, networkInfo.connectedPeers)
        assertTrue(networkInfo.isConnected)
        assertEquals(2, networkInfo.torGateways)
        assertEquals(3, networkInfo.clearnetGateways)
        assertEquals(5, networkInfo.totalGateways)
    }

    @Test
    fun `copy works correctly`() {
        val original = NetworkInfo(
            torGateways = 2,
            clearnetGateways = 3,
            connectedPeers = 5
        )
        
        val copy = original.copy(torGateways = 4)
        
        assertEquals(4, copy.torGateways)
        assertEquals(3, copy.clearnetGateways)
        assertEquals(5, copy.connectedPeers)
        assertEquals(7, copy.totalGateways)
    }

    @Test
    fun `equals works correctly`() {
        val info1 = NetworkInfo(torGateways = 2, clearnetGateways = 3)
        val info2 = NetworkInfo(torGateways = 2, clearnetGateways = 3)
        val info3 = NetworkInfo(torGateways = 3, clearnetGateways = 2)
        
        assertEquals(info1, info2)
        assertNotEquals(info1, info3)
    }
}
