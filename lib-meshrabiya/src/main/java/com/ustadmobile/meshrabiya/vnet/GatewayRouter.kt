package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.log.MNetLogger
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes packets through selected gateways with multiplexing support.
 * 
 * Two main behaviors:
 * 1. CLIENT NODE: Select gateway from topology, route packet to gateway node
 * 2. GATEWAY NODE: Route packet through configured proxy (Tor/etc)
 * 
 * @param gatewaySelector Component for selecting optimal gateways
 * @param virtualNode Reference to the VirtualNode for routing operations
 * @param logger Logging function
 * @param localNodeAddress Virtual address of this node
 */
class GatewayRouter(
    private val gatewaySelector: GatewaySelector,
    private val virtualNode: VirtualNode,
    private val logger: MNetLogger,
    private val localNodeAddress: Int
) {
    
    private val logPrefix = "[GatewayRouter ${localNodeAddress.addressToDotNotation()}]"
    
    // Round-robin counter for multiplexing
    private val roundRobinCounter = AtomicInteger(0)
    
    // Cache of active gateway pools (per gateway type)
    private val gatewayPools: MutableMap<MeshRole, CachedGatewayPool> = mutableMapOf()
    
    /**
     * Cached gateway pool with timestamp
     */
    private data class CachedGatewayPool(
        val gateways: List<GatewayNode>,
        val cachedAt: Long = System.currentTimeMillis()
    ) {
        fun isStale(thresholdMs: Long = 30_000): Boolean {
            return (System.currentTimeMillis() - cachedAt) > thresholdMs
        }
    }
    
    /**
     * Route packet through appropriate gateway based on destination.
     * CLIENT NODE behavior: Select gateway, route to gateway node
     * GATEWAY NODE behavior: Route through proxy
     * 
     * @param packet Virtual packet to route
     * @param gatewayType Type of gateway needed (TOR/CLEARNET/I2P)
     * @return true if routing succeeded
     */
    fun routeToGateway(
        packet: VirtualPacket,
        gatewayType: MeshRole
    ): Boolean {
        // Check if THIS node is a gateway (should route through proxy instead)
        if (virtualNode.isGatewayNode(gatewayType)) {
            return routeThroughProxyAsGateway(packet, gatewayType)
        }
        
        // CLIENT NODE: Select gateways (use cached pool or refresh)
        val gateways = getOrRefreshGatewayPool(gatewayType)
        
        return when {
            gateways.isEmpty() -> {
                logger(
                    Log.WARN,
                    "$logPrefix No gateways available for $gatewayType, falling back to direct routing"
                )
                virtualNode.route(packet)
                true  // route() returns Unit, so return true
            }
            
            gateways.size == 1 -> {
                // Single gateway - simple routing
                routeViaGatewayNode(packet, gateways.first())
            }
            
            else -> {
                // Multiple gateways - use multiplexing
                routeViaMultiplexedGateways(packet, gateways)
            }
        }
    }
    
    /**
     * GATEWAY NODE behavior: Route packet through configured proxy
     * Integrates with existing VirtualNode.routeViaProxy() method
     * 
     * @param packet Virtual packet to route
     * @param gatewayType Gateway type (for logging)
     * @return true if routing succeeded
     */
    private fun routeThroughProxyAsGateway(packet: VirtualPacket, gatewayType: MeshRole): Boolean {
        logger(
            Log.INFO,
            "$logPrefix This node is a $gatewayType, routing through proxy"
        )
        
        // Use existing proxy routing logic from VirtualNode
        return try {
            virtualNode.routeViaProxy(packet)
        } catch (e: Exception) {
            logger(
                Log.ERROR,
                "$logPrefix Failed to route via proxy: ${e.message}"
            )
            false
        }
    }
    
    /**
     * Get gateway pool, refresh if stale
     * @param gatewayType Gateway type to get pool for
     * @return List of available gateways (may be empty)
     */
    private fun getOrRefreshGatewayPool(gatewayType: MeshRole): List<GatewayNode> {
        // Check if pool exists and is fresh (< 30 seconds old)
        val cached = gatewayPools[gatewayType]
        if (cached != null && !cached.isStale()) {
            return cached.gateways
        }
        
        // Refresh gateway pool
        val result = gatewaySelector.selectMultipleGateways(
            gatewayType = gatewayType,
            maxCount = 3,  // Use up to 3 gateways
            strategy = DistributionStrategy.WEIGHTED
        )
        
        val newPool = when (result) {
            is GatewaySelectionResult.MultipleGateways -> result.gateways
            is GatewaySelectionResult.SingleGateway -> 
                listOf(GatewayNode(result.nodeAddress, result.suitability, result.hopCount))
            else -> emptyList()
        }
        
        gatewayPools[gatewayType] = CachedGatewayPool(newPool)
        return newPool
    }
    
    /**
     * CLIENT NODE: Route via single gateway node
     * @param packet Virtual packet to route
     * @param gateway Gateway node to route through
     * @return true if routing succeeded
     */
    private fun routeViaGatewayNode(packet: VirtualPacket, gateway: GatewayNode): Boolean {
        logger(
            Log.DEBUG,
            "$logPrefix CLIENT: Routing via gateway ${gateway.nodeAddress.addressToDotNotation()}"
        )
        
        // Modify packet header to route through gateway node
        val modifiedHeader = VirtualPacketHeader(
            toAddr = gateway.nodeAddress,  // Set gateway as next hop
            toPort = packet.header.toPort,
            fromAddr = packet.header.fromAddr,
            fromPort = packet.header.fromPort,
            lastHopAddr = packet.header.lastHopAddr,
            hopCount = packet.header.hopCount,
            maxHops = packet.header.maxHops,
            gatewayType = packet.header.gatewayType, //V3: Preserve gateway type
            payloadSize = packet.header.payloadSize
        )
        
        val modifiedPacket = VirtualPacket.fromHeaderAndPayloadData(
            header = modifiedHeader,
            data = packet.data,
            payloadOffset = packet.payloadOffset
        )
        
        virtualNode.route(modifiedPacket)
        return true  // route() returns Unit
    }
    
    /**
     * CLIENT NODE: Route via multiple gateways (multiplexing)
     * Uses round-robin to distribute packets across gateways
     * 
     * @param packet Virtual packet to route
     * @param gateways List of available gateways
     * @return true if routing succeeded
     */
    private fun routeViaMultiplexedGateways(packet: VirtualPacket, gateways: List<GatewayNode>): Boolean {
        // Select gateway using round-robin (can be enhanced with weighted selection)
        val index = roundRobinCounter.getAndIncrement() % gateways.size
        val selectedGateway = gateways[index]
        
        logger(
            Log.DEBUG,
            "$logPrefix Multiplexing: selected gateway ${selectedGateway.nodeAddress.addressToDotNotation()} " +
                    "(${index + 1}/${gateways.size})"
        )
        
        return routeViaGatewayNode(packet, selectedGateway)
    }
    
    /**
     * Clear gateway pool cache (call when topology changes significantly)
     */
    fun clearGatewayPools() {
        gatewayPools.clear()
        logger(
            Log.INFO,
            "$logPrefix Cleared gateway pool cache"
        )
    }
}
