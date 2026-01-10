package com.ustadmobile.meshrabiya.vnet

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.util.Log
import com.ustadmobile.meshrabiya.api.GatewayPreference
import com.ustadmobile.meshrabiya.api.MeshrabiyaApiImpl

/**
 * V3: Resolves gateway type for packets based on precedence rules.
 * 
 * **Precedence (highest to lowest):**
 * 1. Packet header explicit gatewayType (if already set)
 * 2. Orbot VPN per-app rules (SharedPreferences "PrefTord")
 * 3. Global gateway preference (DataStore "gateway_preference")
 * 
 * **Implementation:**
 * - Reads Orbot VPN settings from SharedPreferences
 * - Maps Android package names to gateway types
 * - Applies global preference as fallback
 * 
 * **Thread Safety:** All public methods are thread-safe (uses volatile reads)
 * 
 * **Usage:**
 * ```kotlin
 * val resolver = GatewayTypeResolver(context)
 * 
 * // Resolve gateway type for a packet
 * val gatewayType = resolver.resolveGatewayType(
 *     packet = virtualPacket,
 *     sourcePackageName = "com.android.chrome"
 * )
 * 
 * // Update packet header with resolved type
 * packet.header.gatewayType = gatewayType
 * ```
 */
class GatewayTypeResolver(
    private val context: Context
) {
    companion object {
        private const val TAG = "GatewayTypeResolver"

        /**
         * SharedPreferences key for Orbot's torified apps list.
         * Format: pipe-delimited string "com.app1|com.app2|com.app3"
         */
        private const val PREFS_KEY_TORIFIED = "PrefTord"
    }

    /**
     * Cached torified apps list (updated on each resolution).
     * Avoids repeated SharedPreferences reads in high-throughput scenarios.
     */
    @Volatile
    private var cachedTorifiedApps: Set<String> = emptySet()

    /**
     * Last update timestamp for cache invalidation.
     */
    @Volatile
    private var lastCacheUpdate: Long = 0L

    /**
     * Cache TTL (5 seconds) - balance between freshness and performance.
     */
    private val CACHE_TTL_MS = 5000L

    /**
     * Resolve gateway type for a packet using precedence rules.
     * 
     * **Precedence:**
     * 1. If packet.header.gatewayType != GATEWAY_TYPE_NONE → return as-is (explicit request)
     * 2. If sourcePackageName in Orbot VPN settings → return GATEWAY_TYPE_TOR
     * 3. Apply global gateway preference:
     *    - TOR_ONLY → GATEWAY_TYPE_TOR
     *    - CLEARNET_ONLY → GATEWAY_TYPE_CLEARNET
     *    - EITHER → GATEWAY_TYPE_TOR (prefer Tor, fallback handled by routing layer)
     * 
     * @param packet VirtualPacket to resolve gateway type for
     * @param sourcePackageName Android package name of originating app (e.g., "com.android.chrome")
     * @return Resolved gateway type (GATEWAY_TYPE_NONE, GATEWAY_TYPE_TOR, or GATEWAY_TYPE_CLEARNET)
     */
    fun resolveGatewayType(
        packet: VirtualPacket,
        sourcePackageName: String?
    ): Byte {
        // Precedence 1: Packet header explicit gatewayType
        if (packet.header.gatewayType != VirtualPacketHeader.GATEWAY_TYPE_NONE) {
            Log.d(TAG, "Packet has explicit gatewayType=${packet.header.gatewayType}, using as-is")
            return packet.header.gatewayType
        }

        // Precedence 2: VPN per-app rules (supersede global preference)
        if (sourcePackageName != null) {
            val isAppTorified = isPackageTorified(sourcePackageName)
            if (isAppTorified) {
                Log.d(TAG, "Package $sourcePackageName is torified (VPN rule), using GATEWAY_TYPE_TOR")
                return VirtualPacketHeader.GATEWAY_TYPE_TOR
            } else {
                Log.d(TAG, "Package $sourcePackageName NOT torified (VPN rule), using GATEWAY_TYPE_CLEARNET")
                return VirtualPacketHeader.GATEWAY_TYPE_CLEARNET
            }
        }

        // Precedence 3: Global gateway preference (fallback)
        val preference = MeshrabiyaApiImpl.getInstance().getGatewayPreference()
        val gatewayType = applyGlobalPreference(preference)
        Log.d(TAG, "No VPN rule for package, using global preference $preference → gatewayType=$gatewayType")
        return gatewayType
    }

    /**
     * Check if a package is configured for Tor in Orbot VPN settings.
     * 
     * **Implementation:**
     * - Reads SharedPreferences "PrefTord" key
     * - Parses pipe-delimited string ("app1|app2|app3")
     * - Checks if packageName is in the list
     * - Caches results for 5 seconds to reduce I/O
     * 
     * @param packageName Android package name (e.g., "com.android.chrome")
     * @return true if package is torified, false otherwise
     */
    private fun isPackageTorified(packageName: String): Boolean {
        // Update cache if stale
        val now = System.currentTimeMillis()
        if (now - lastCacheUpdate > CACHE_TTL_MS) {
            updateTorifiedAppsCache()
        }

        return cachedTorifiedApps.contains(packageName)
    }

    /**
     * Update cached torified apps list from SharedPreferences.
     * 
     * **Thread Safety:** Called from resolveGatewayType (potentially multiple threads).
     * Uses synchronized block to prevent duplicate reads.
     */
    private fun updateTorifiedAppsCache() {
        synchronized(this) {
            // Double-check after acquiring lock
            val now = System.currentTimeMillis()
            if (now - lastCacheUpdate <= CACHE_TTL_MS) {
                return  // Another thread just updated
            }

            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val torifiedAppsString = prefs.getString(PREFS_KEY_TORIFIED, "") ?: ""
                
                cachedTorifiedApps = torifiedAppsString
                    .split("|")
                    .filter { it.isNotBlank() }
                    .toSet()
                
                lastCacheUpdate = now
                Log.d(TAG, "Updated torified apps cache: ${cachedTorifiedApps.size} apps")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read torified apps from SharedPreferences", e)
                cachedTorifiedApps = emptySet()
            }
        }
    }

    /**
     * Apply global gateway preference to determine gateway type.
     * 
     * **Mapping:**
     * - TOR_ONLY → GATEWAY_TYPE_TOR (strict, no fallback)
     * - CLEARNET_ONLY → GATEWAY_TYPE_CLEARNET (strict, no fallback)
     * - EITHER → GATEWAY_TYPE_TOR (prefer Tor, routing layer handles fallback)
     * 
     * **Note:** EITHER preference uses GATEWAY_TYPE_TOR as initial value.
     * The routing layer (VirtualNode.route) will attempt Tor gateway first,
     * then fallback to clearnet gateway if no Tor gateway is available.
     * 
     * @param preference Global gateway preference
     * @return Gateway type byte
     */
    private fun applyGlobalPreference(preference: GatewayPreference): Byte {
        return when (preference) {
            GatewayPreference.TOR_ONLY -> VirtualPacketHeader.GATEWAY_TYPE_TOR
            GatewayPreference.CLEARNET_ONLY -> VirtualPacketHeader.GATEWAY_TYPE_CLEARNET
            GatewayPreference.EITHER -> VirtualPacketHeader.GATEWAY_TYPE_TOR  // Prefer Tor
        }
    }

    /**
     * Get human-readable description of gateway type resolution.
     * 
     * **Use Case:** Debugging, logging, UI display
     * 
     * @param gatewayType Resolved gateway type
     * @return Description string
     */
    fun getGatewayTypeDescription(gatewayType: Byte): String {
        return when (gatewayType) {
            VirtualPacketHeader.GATEWAY_TYPE_NONE -> "None (mesh-local)"
            VirtualPacketHeader.GATEWAY_TYPE_TOR -> "Tor Gateway (privacy)"
            VirtualPacketHeader.GATEWAY_TYPE_CLEARNET -> "Clearnet Gateway (performance)"
            else -> "Unknown ($gatewayType)"
        }
    }

    /**
     * Force cache refresh (for testing or admin tools).
     * 
     * **Use Case:** After user modifies Orbot VPN settings, call this
     * to immediately reload the torified apps list.
     */
    fun refreshCache() {
        lastCacheUpdate = 0L  // Invalidate cache
        updateTorifiedAppsCache()
        Log.i(TAG, "Cache refreshed manually")
    }
}
