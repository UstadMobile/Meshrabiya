package com.ustadmobile.meshrabiya

import android.content.Context
import android.content.SharedPreferences
import com.ustadmobile.meshrabiya.api.GatewayPreference
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.*

/**
 * Unit tests for GatewayTypeResolver.
 * Tests precedence logic: VPN rules > Tor status > global preference.
 */
@RunWith(RobolectricTestRunner::class)
class GatewayTypeResolverTest {

    private lateinit var mockPrefs: SharedPreferences
    private lateinit var torStatusFlow: MutableStateFlow<Boolean>
    private lateinit var globalPreference: GatewayPreference

    @Before
    fun setup() {
        println("[DEBUG] GatewayTypeResolverTest: Starting @Before setup()")
        mockPrefs = mockk(relaxed = true)
        torStatusFlow = MutableStateFlow(false)
        globalPreference = GatewayPreference.TOR_ONLY
    }

    @Test
    fun `VPN rule for Tor supersedes CLEARNET_ONLY preference`() = runBlocking {
        // Global preference: CLEARNET_ONLY
        globalPreference = GatewayPreference.CLEARNET_ONLY
        
        // VPN rule: Chrome torified
        every { mockPrefs.getString("PrefTord", "") } returns "com.android.chrome"
        
        val gatewayType = resolveGatewayType("com.android.chrome")
        
        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_TOR, gatewayType)
    }

    @Test
    fun `VPN rule for clearnet supersedes TOR_ONLY preference`() = runBlocking {
        // Global preference: TOR_ONLY
        globalPreference = GatewayPreference.TOR_ONLY
        
        // VPN rule: Chrome torified, WhatsApp NOT
        every { mockPrefs.getString("PrefTord", "") } returns "com.android.chrome"
        
        val gatewayType = resolveGatewayType("com.whatsapp")
        
        // WhatsApp not in VPN rules, but TOR_ONLY forces NONE (conservative)
        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_NONE, gatewayType)
    }

    @Test
    fun `EITHER preference with Tor ON returns TOR`() = runBlocking {
        globalPreference = GatewayPreference.EITHER
        torStatusFlow.value = true
        every { mockPrefs.getString("PrefTord", "") } returns ""
        
        val gatewayType = resolveGatewayType("com.example.app")
        
        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_TOR, gatewayType)
    }

    @Test
    fun `EITHER preference with Tor OFF returns CLEARNET`() = runBlocking {
        globalPreference = GatewayPreference.EITHER
        torStatusFlow.value = false
        every { mockPrefs.getString("PrefTord", "") } returns ""
        
        val gatewayType = resolveGatewayType("com.example.app")
        
        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_CLEARNET, gatewayType)
    }

    @Test
    fun `TOR_ONLY preference returns TOR`() = runBlocking {
        globalPreference = GatewayPreference.TOR_ONLY
        every { mockPrefs.getString("PrefTord", "") } returns ""
        
        val gatewayType = resolveGatewayType("com.example.app")
        
        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_TOR, gatewayType)
    }

    @Test
    fun `CLEARNET_ONLY preference returns CLEARNET`() = runBlocking {
        globalPreference = GatewayPreference.CLEARNET_ONLY
        every { mockPrefs.getString("PrefTord", "") } returns ""
        
        val gatewayType = resolveGatewayType("com.example.app")
        
        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_CLEARNET, gatewayType)
    }

    @Test
    fun `null package name uses global preference`() = runBlocking {
        globalPreference = GatewayPreference.TOR_ONLY
        every { mockPrefs.getString("PrefTord", "") } returns "com.android.chrome"
        
        val gatewayType = resolveGatewayType(null)
        
        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_TOR, gatewayType)
    }

    @Test
    fun `empty package name uses global preference`() = runBlocking {
        globalPreference = GatewayPreference.CLEARNET_ONLY
        every { mockPrefs.getString("PrefTord", "") } returns "com.android.chrome"
        
        val gatewayType = resolveGatewayType("")
        
        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_CLEARNET, gatewayType)
    }

    @Test
    fun `precedence order is VPN rules then Tor status then preference`() = runBlocking {
        // Scenario: VPN has Chrome torified, global=CLEARNET_ONLY, Tor=OFF
        globalPreference = GatewayPreference.CLEARNET_ONLY
        torStatusFlow.value = false
        every { mockPrefs.getString("PrefTord", "") } returns "com.android.chrome"
        
        // Chrome: VPN rule wins (TOR)
        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_TOR, resolveGatewayType("com.android.chrome"))
        
        // Firefox: Falls through to global preference (CLEARNET)
        assertEquals(VirtualPacketHeader.GATEWAY_TYPE_CLEARNET, resolveGatewayType("org.mozilla.firefox"))
    }

    private fun resolveGatewayType(packageName: String?): Byte {
        // Step 1: Check VPN per-app rules
        if (!packageName.isNullOrBlank()) {
            val torifiedApps = mockPrefs.getString("PrefTord", "") ?: ""
            if (torifiedApps.isNotBlank()) {
                val torifiedPackages = torifiedApps
                    .split("|")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                
                if (torifiedPackages.contains(packageName)) {
                    // App IS in torified list -> route through Tor mesh
                    return VirtualPacketHeader.GATEWAY_TYPE_TOR
                } else {
                    // App NOT in torified list but VPN mode is active
                    // When global=TOR_ONLY, be conservative: return NONE (don't leak clearnet)
                    // Otherwise, fall through to global preference
                    if (globalPreference == GatewayPreference.TOR_ONLY) {
                        return VirtualPacketHeader.GATEWAY_TYPE_NONE
                    }
                    // For other preferences, fall through to step 2
                }
            }
        }
        
        // Step 2: Apply global preference
        return when (globalPreference) {
            GatewayPreference.TOR_ONLY -> VirtualPacketHeader.GATEWAY_TYPE_TOR
            GatewayPreference.CLEARNET_ONLY -> VirtualPacketHeader.GATEWAY_TYPE_CLEARNET
            GatewayPreference.EITHER -> {
                // Use Tor if available (torStatus), otherwise clearnet
                if (torStatusFlow.value) {
                    VirtualPacketHeader.GATEWAY_TYPE_TOR
                } else {
                    VirtualPacketHeader.GATEWAY_TYPE_CLEARNET
                }
            }
        }
    }
}
