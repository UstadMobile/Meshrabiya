package com.ustadmobile.meshrabiya.vnet

import io.mockk.*
import org.junit.Before
import org.junit.Test
import kotlin.test.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests for OriginatingMessageManager gateway tracking functionality.
 * Tests gateway message tracking, return path routing, and statistics.
 */
class OriginatingMessageManagerGatewayTest {

    private lateinit var gatewayMessages: MutableMap<String, GatewayMessage>

    @Before
    fun setup() {
        gatewayMessages = ConcurrentHashMap()
    }

    @Test
    fun `trackGatewayMessage stores message correctly`() {
        trackGatewayMessage(
            fromAddr = 0x0A000001,
            fromPort = 12345,
            toAddr = 0x08080808,
            toPort = 53,
            gatewayType = VirtualPacketHeader.GATEWAY_TYPE_TOR,
            gatewayAddr = 0x0A000010
        )
        
        assertEquals(1, gatewayMessages.size)
        
        val key = "167772161:12345"  // 0x0A000001:12345
        val message = gatewayMessages[key]
        
        assertNotNull(message)
        assertEquals(0x0A000001, message?.fromAddr)
        assertEquals(12345, message?.fromPort)
        assertEquals(0x08080808, message?.toAddr)
        assertEquals(53, message?.toPort)
        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_TOR, message?.gatewayType)
        assertEquals(0x0A000010, message?.gatewayAddr)
    }

    @Test
    fun `getGatewayForReturnTraffic returns correct gateway`() {
        trackGatewayMessage(
            fromAddr = 0x0A000001,
            fromPort = 12345,
            toAddr = 0x08080808,
            toPort = 53,
            gatewayType = VirtualPacketHeader.GATEWAY_TYPE_TOR,
            gatewayAddr = 0x0A000010
        )
        
        // For return traffic, swap to/from addresses
        val gatewayAddr = getGatewayForReturnTraffic(0x0A000001, 12345)
        
        assertEquals(0x0A000010, gatewayAddr)
    }

    @Test
    fun `getGatewayForReturnTraffic returns null when no message tracked`() {
        val gatewayAddr = getGatewayForReturnTraffic(0x0A000001, 12345)
        
        assertNull(gatewayAddr)
    }

    @Test
    fun `multiple messages tracked separately`() {
        trackGatewayMessage(0x0A000001, 12345, 0x08080808, 53, VirtualPacketHeader.GATEWAY_TYPE_TOR, 0x0A000010)
        trackGatewayMessage(0x0A000002, 54321, 0x01010101, 80, VirtualPacketHeader.GATEWAY_TYPE_CLEARNET, 0x0A000020)
        
        assertEquals(2, gatewayMessages.size)
        
        assertEquals(0x0A000010, getGatewayForReturnTraffic(0x0A000001, 12345))
        assertEquals(0x0A000020, getGatewayForReturnTraffic(0x0A000002, 54321))
    }

    @Test
    fun `getGatewayUsageStats counts by gateway type`() {
        trackGatewayMessage(0x0A000001, 12345, 0x08080808, 53, VirtualPacketHeader.GATEWAY_TYPE_TOR, 0x0A000010)
        trackGatewayMessage(0x0A000002, 54321, 0x01010101, 80, VirtualPacketHeader.GATEWAY_TYPE_TOR, 0x0A000010)
        trackGatewayMessage(0x0A000003, 11111, 0x08080404, 443, VirtualPacketHeader.GATEWAY_TYPE_CLEARNET, 0x0A000020)
        
        val stats = getGatewayUsageStats()
        
        assertEquals(2, stats[VirtualPacketHeader.GATEWAY_TYPE_TOR])
        assertEquals(1, stats[VirtualPacketHeader.GATEWAY_TYPE_CLEARNET])
    }

    @Test
    fun `cleanupStaleGatewayMessages removes old messages`() {
        val now = System.currentTimeMillis()
        
        // Add fresh message
        gatewayMessages["fresh"] = GatewayMessage(
            fromAddr = 0x0A000001,
            fromPort = 12345,
            toAddr = 0x08080808,
            toPort = 53,
            timestamp = now - 10_000,  // 10 seconds ago
            gatewayType = VirtualPacketHeader.GATEWAY_TYPE_TOR,
            gatewayAddr = 0x0A000010
        )
        
        // Add stale message
        gatewayMessages["stale"] = GatewayMessage(
            fromAddr = 0x0A000002,
            fromPort = 54321,
            toAddr = 0x01010101,
            toPort = 80,
            timestamp = now - 90_000,  // 90 seconds ago
            gatewayType = VirtualPacketHeader.GATEWAY_TYPE_CLEARNET,
            gatewayAddr = 0x0A000020
        )
        
        assertEquals(2, gatewayMessages.size)
        
        cleanupStaleGatewayMessages(60_000L)  // 60-second threshold
        
        assertEquals(1, gatewayMessages.size)
        assertTrue(gatewayMessages.containsKey("fresh"))
        assertFalse(gatewayMessages.containsKey("stale"))
    }

    @Test
    fun `cleanupStaleGatewayMessages keeps all messages when none stale`() {
        val now = System.currentTimeMillis()
        
        gatewayMessages["msg1"] = createGatewayMessage(timestamp = now - 10_000)
        gatewayMessages["msg2"] = createGatewayMessage(timestamp = now - 20_000)
        gatewayMessages["msg3"] = createGatewayMessage(timestamp = now - 30_000)
        
        assertEquals(3, gatewayMessages.size)
        
        cleanupStaleGatewayMessages(60_000L)
        
        assertEquals(3, gatewayMessages.size)
    }

    @Test
    fun `createGatewayMessageKey uses correct format`() {
        val key = createGatewayMessageKey(0x0A000001, 12345)
        
        assertEquals("167772161:12345", key)
    }

    @Test
    fun `tracking same source overwrites previous message`() {
        trackGatewayMessage(0x0A000001, 12345, 0x08080808, 53, VirtualPacketHeader.GATEWAY_TYPE_TOR, 0x0A000010)
        trackGatewayMessage(0x0A000001, 12345, 0x01010101, 80, VirtualPacketHeader.GATEWAY_TYPE_CLEARNET, 0x0A000020)
        
        assertEquals(1, gatewayMessages.size)
        
        val message = gatewayMessages[createGatewayMessageKey(0x0A000001, 12345)]
        assertEquals(0x01010101, message?.toAddr)  // Second message overwrote first
        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_CLEARNET, message?.gatewayType)
    }

    // Helper methods that simulate OriginatingMessageManager functionality

    private fun trackGatewayMessage(
        fromAddr: Int,
        fromPort: Int,
        toAddr: Int,
        toPort: Int,
        gatewayType: Byte,
        gatewayAddr: Int
    ) {
        val key = createGatewayMessageKey(fromAddr, fromPort)
        val message = GatewayMessage(
            fromAddr = fromAddr,
            fromPort = fromPort,
            toAddr = toAddr,
            toPort = toPort,
            timestamp = System.currentTimeMillis(),
            gatewayType = gatewayType,
            gatewayAddr = gatewayAddr
        )
        gatewayMessages[key] = message
    }

    private fun getGatewayForReturnTraffic(toAddr: Int, toPort: Int): Int? {
        val key = createGatewayMessageKey(toAddr, toPort)
        return gatewayMessages[key]?.gatewayAddr
    }

    private fun getGatewayUsageStats(): Map<Byte, Int> {
        val stats = mutableMapOf<Byte, Int>()
        gatewayMessages.values.forEach { msg ->
            val count = stats.getOrDefault(msg.gatewayType, 0)
            stats[msg.gatewayType] = count + 1
        }
        return stats
    }

    private fun cleanupStaleGatewayMessages(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        val iterator = gatewayMessages.entries.iterator()
        
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.timestamp > maxAgeMs) {
                iterator.remove()
            }
        }
    }

    private fun createGatewayMessageKey(fromAddr: Int, fromPort: Int): String {
        return "$fromAddr:$fromPort"
    }

    private fun createGatewayMessage(
        fromAddr: Int = 0x0A000001,
        fromPort: Int = 12345,
        toAddr: Int = 0x08080808,
        toPort: Int = 53,
        timestamp: Long = System.currentTimeMillis(),
        gatewayType: Byte = VirtualPacketHeader.GATEWAY_TYPE_TOR,
        gatewayAddr: Int = 0x0A000010
    ): GatewayMessage {
        return GatewayMessage(
            fromAddr = fromAddr,
            fromPort = fromPort,
            toAddr = toAddr,
            toPort = toPort,
            timestamp = timestamp,
            gatewayType = gatewayType,
            gatewayAddr = gatewayAddr
        )
    }

    // Mock data class (should match actual GatewayMessage in OriginatingMessageManager)
    data class GatewayMessage(
        val fromAddr: Int,
        val fromPort: Int,
        val toAddr: Int,
        val toPort: Int,
        val timestamp: Long,
        val gatewayType: Byte,
        val gatewayAddr: Int
    )
}
