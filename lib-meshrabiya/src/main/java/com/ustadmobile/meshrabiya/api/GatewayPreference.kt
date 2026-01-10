package com.ustadmobile.meshrabiya.api

/**
 * Gateway preference for internet-bound traffic routing.
 * 
 * This preference defines the user's global policy for how packets destined for
 * internet addresses (outside the mesh) should be routed through gateway nodes.
 * 
 * **Precedence:** Orbot VPN per-app rules (SharedPreferences "PrefTord") supersede
 * this global preference. If an app is explicitly marked as torified in Orbot's
 * VPN settings, it will always use Tor gateway regardless of this preference.
 * 
 * **V3 Implementation:** This enum works in conjunction with VirtualPacketHeader.gatewayType
 * to provide flexible gateway routing policies.
 * 
 * @see com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader.GATEWAY_TYPE_TOR
 * @see com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader.GATEWAY_TYPE_CLEARNET
 */
enum class GatewayPreference {
    /**
     * **TOR_ONLY**: Route all internet-bound traffic exclusively through Tor gateways.
     * 
     * **Behavior:**
     * - Only mesh nodes advertising Tor gateway capability will be used
     * - If no Tor gateway is available, internet traffic is dropped (no fallback)
     * - Provides maximum privacy at the cost of potential connectivity loss
     * - Typical latency: 500-2000ms (Tor network overhead)
     * 
     * **Use Case:** Maximum privacy requirement, user accepts potential connectivity issues
     * 
     * **Implementation:**
     * ```kotlin
     * when (preference) {
     *     TOR_ONLY -> {
     *         val torGateway = findAvailableTorGateway()
     *         if (torGateway != null) routeViaTorGateway(packet, torGateway)
     *         else dropPacket(packet) // No fallback
     *     }
     * }
     * ```
     */
    TOR_ONLY,

    /**
     * **CLEARNET_ONLY**: Route all internet-bound traffic exclusively through clearnet gateways.
     * 
     * **Behavior:**
     * - Only mesh nodes advertising clearnet gateway capability will be used
     * - No Tor routing, even if Tor gateways are available
     * - If no clearnet gateway is available, internet traffic is dropped (no fallback)
     * - Provides maximum performance at the cost of privacy
     * - Typical latency: 50-200ms (direct internet access)
     * 
     * **Use Case:** Performance-critical applications, privacy not required
     * 
     * **Implementation:**
     * ```kotlin
     * when (preference) {
     *     CLEARNET_ONLY -> {
     *         val clearnetGateway = findAvailableClearnetGateway()
     *         if (clearnetGateway != null) routeViaClearnetGateway(packet, clearnetGateway)
     *         else dropPacket(packet) // No fallback
     *     }
     * }
     * ```
     */
    CLEARNET_ONLY,

    /**
     * **EITHER**: Prefer Tor gateways, fallback to clearnet if unavailable.
     * 
     * **Behavior:**
     * - **Primary:** Attempt routing through Tor gateway for privacy
     * - **Fallback:** If no Tor gateway available, use clearnet gateway
     * - If neither gateway type is available, internet traffic is dropped
     * - Balances privacy and connectivity
     * - Latency: Variable (500-2000ms via Tor, 50-200ms via clearnet)
     * 
     * **Use Case:** Privacy-conscious but pragmatic, accepts clearnet fallback
     * 
     * **Privacy Note:** This is the recommended default for most users as it:
     * 1. Prioritizes privacy when possible (Tor first)
     * 2. Maintains connectivity when Tor is unavailable (clearnet fallback)
     * 3. Fails safe (no gateway = no internet, no leak)
     * 
     * **Implementation:**
     * ```kotlin
     * when (preference) {
     *     EITHER -> {
     *         val torGateway = findAvailableTorGateway()
     *         if (torGateway != null) {
     *             routeViaTorGateway(packet, torGateway)
     *         } else {
     *             val clearnetGateway = findAvailableClearnetGateway()
     *             if (clearnetGateway != null) routeViaClearnetGateway(packet, clearnetGateway)
     *             else dropPacket(packet) // No gateway available
     *         }
     *     }
     * }
     * ```
     */
    EITHER;

    companion object {
        /**
         * DataStore preference key for persisting gateway preference.
         * 
         * **Storage:** AndroidX DataStore (Preferences)
         * **Type:** String (enum name: "TOR_ONLY", "CLEARNET_ONLY", "EITHER")
         * **Default:** "TOR_ONLY" (privacy-first approach)
         * 
         * **Usage:**
         * ```kotlin
         * // Save preference
         * dataStore.edit { preferences ->
         *     preferences[stringPreferencesKey(KEY_GATEWAY_PREFERENCE)] = GatewayPreference.EITHER.name
         * }
         * 
         * // Load preference
         * val prefString = dataStore.data.first()[stringPreferencesKey(KEY_GATEWAY_PREFERENCE)]
         * val preference = GatewayPreference.valueOf(prefString ?: "TOR_ONLY")
         * ```
         */
        const val KEY_GATEWAY_PREFERENCE = "gateway_preference"

        /**
         * Default gateway preference (privacy-first).
         * 
         * **Rationale:** TOR_ONLY is the default to ensure privacy by default.
         * Users must explicitly opt-in to clearnet or fallback behavior.
         * This aligns with Tor Browser's privacy-first philosophy.
         */
        val DEFAULT = TOR_ONLY

        /**
         * Parse gateway preference from string (e.g., DataStore value).
         * 
         * @param value String value to parse (enum name or ordinal)
         * @return GatewayPreference enum value, or DEFAULT if parsing fails
         * 
         * **Example:**
         * ```kotlin
         * val pref1 = fromString("TOR_ONLY")      // TOR_ONLY
         * val pref2 = fromString("EITHER")        // EITHER
         * val pref3 = fromString("invalid")       // TOR_ONLY (default)
         * val pref4 = fromString(null)            // TOR_ONLY (default)
         * ```
         */
        fun fromString(value: String?): GatewayPreference {
            if (value.isNullOrBlank()) return DEFAULT
            return try {
                valueOf(value.uppercase())
            } catch (e: IllegalArgumentException) {
                DEFAULT
            }
        }

        /**
         * Convert gateway preference to user-friendly display string.
         * 
         * @param preference GatewayPreference enum value
         * @return Human-readable string for UI display
         * 
         * **Example:**
         * ```kotlin
         * toDisplayString(TOR_ONLY)      // "Tor Only (Maximum Privacy)"
         * toDisplayString(CLEARNET_ONLY) // "Direct (Maximum Performance)"
         * toDisplayString(EITHER)        // "Prefer Tor, Fallback to Direct"
         * ```
         */
        fun toDisplayString(preference: GatewayPreference): String {
            return when (preference) {
                TOR_ONLY -> "Tor Only (Maximum Privacy)"
                CLEARNET_ONLY -> "Direct (Maximum Performance)"
                EITHER -> "Prefer Tor, Fallback to Direct"
            }
        }

        /**
         * Get detailed description for user education.
         * 
         * @param preference GatewayPreference enum value
         * @return Detailed explanation for settings screen
         */
        fun toDescription(preference: GatewayPreference): String {
            return when (preference) {
                TOR_ONLY -> 
                    "All internet traffic will be routed through Tor for maximum privacy. " +
                    "If no Tor gateway is available, internet access will be blocked. " +
                    "Slower but more private."
                    
                CLEARNET_ONLY -> 
                    "All internet traffic will be routed directly (no Tor). " +
                    "If no direct gateway is available, internet access will be blocked. " +
                    "Faster but less private."
                    
                EITHER -> 
                    "Internet traffic will prefer Tor gateways for privacy, but will fallback " +
                    "to direct gateways if no Tor gateway is available. " +
                    "Recommended for most users."
            }
        }
    }
}
