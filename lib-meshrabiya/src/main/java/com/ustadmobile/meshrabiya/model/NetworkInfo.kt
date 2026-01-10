package com.ustadmobile.meshrabiya.model

/**
 * Represents information about the current mesh network state.
 * V3: Enhanced with gateway type breakdown (Tor, clearnet)
 */
data class NetworkInfo(
    val ssid: String = "",
    val bssid: String = "",
    val ipAddress: String = "",
    val connectedPeers: Int = 0,
    val isConnected: Boolean = false,
    
    // Phase 3B: Gateway statistics
    val torGateways: Int = 0,
    val clearnetGateways: Int = 0,
) {
    /**
     * Total gateway nodes (Tor + clearnet).
     * Note: Some nodes may advertise both roles, so this may not equal unique gateway count.
     */
    val totalGateways: Int
        get() = torGateways + clearnetGateways
}