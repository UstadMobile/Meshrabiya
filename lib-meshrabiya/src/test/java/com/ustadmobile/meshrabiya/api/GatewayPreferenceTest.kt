package com.ustadmobile.meshrabiya.api

import org.junit.Test
import kotlin.test.*

/**
 * Unit tests for GatewayPreference enum.
 * Verifies enum values, names, and default preference.
 */
class GatewayPreferenceTest {

    @Test
    fun `default preference is TOR_ONLY`() {
        println("[DEBUG] GatewayPreferenceTest: Running test")
        assertEquals(GatewayPreference.TOR_ONLY, GatewayPreference.DEFAULT)
    }

    @Test
    fun `enum values have expected names`() {
        assertEquals("TOR_ONLY", GatewayPreference.TOR_ONLY.name)
        assertEquals("CLEARNET_ONLY", GatewayPreference.CLEARNET_ONLY.name)
        assertEquals("EITHER", GatewayPreference.EITHER.name)
    }

    @Test
    fun `valueOf parses from string`() {
        assertEquals(GatewayPreference.TOR_ONLY, GatewayPreference.valueOf("TOR_ONLY"))
        assertEquals(GatewayPreference.CLEARNET_ONLY, GatewayPreference.valueOf("CLEARNET_ONLY"))
        assertEquals(GatewayPreference.EITHER, GatewayPreference.valueOf("EITHER"))
    }

    @Test
    fun `all enum values are unique`() {
        val values = GatewayPreference.entries.toTypedArray()
        assertEquals(3, values.size)
        assertEquals(3, values.distinct().size)
    }

    @Test
    fun `values returns all three preferences`() {
        val values = GatewayPreference.entries.toTypedArray()
        assertTrue(values.contains(GatewayPreference.TOR_ONLY))
        assertTrue(values.contains(GatewayPreference.CLEARNET_ONLY))
        assertTrue(values.contains(GatewayPreference.EITHER))
    }

    @Test
    fun `enum ordinals are consistent`() {
        // Verify ordinals don't change (for serialization stability)
        assertEquals(0, GatewayPreference.TOR_ONLY.ordinal)
        assertEquals(1, GatewayPreference.CLEARNET_ONLY.ordinal)
        assertEquals(2, GatewayPreference.EITHER.ordinal)
    }

    @Test
    fun `toString returns name`() {
        assertEquals("TOR_ONLY", GatewayPreference.TOR_ONLY.toString())
        assertEquals("CLEARNET_ONLY", GatewayPreference.CLEARNET_ONLY.toString())
        assertEquals("EITHER", GatewayPreference.EITHER.toString())
    }
}
