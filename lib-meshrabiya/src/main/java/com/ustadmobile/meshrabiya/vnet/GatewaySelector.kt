
package com.ustadmobile.meshrabiya.vnet
import java.util.Locale
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.log.MNetLogger
import android.util.Log

/**
 * Intelligent gateway selector that chooses optimal gateways based on:
 * - Fitness score (hardware capability)
 * - Centrality score (topology position)
 * - Latency (ping time)
 * - User preferences (enabled roles)
 * 
 * ONLY selects gateway roles: TOR_GATEWAY, CLEARNET_GATEWAY, I2P_GATEWAY
 * (STORAGE/COMPUTE roles ignored for routing purposes)
 * 
 * @param originatingMessageManager Source of topology information
 * @param emergentRoleManager Source of user preferences and enabled roles
 * @param logger Logging function
 * @param localNodeAddress Virtual address of this node
 */
class GatewaySelector(
    private val originatingMessageManager: OriginatingMessageManager,
    private val emergentRoleManager: EmergentRoleManager,
    private val logger: MNetLogger,
    private val localNodeAddress: Int
) {
    
    private val logPrefix = "[GatewaySelector ${localNodeAddress.addressToDotNotation()}]"
    
    companion object {
        /**
         * Gateway roles that can be used for routing
         */
        val GATEWAY_ROLES = setOf(
            MeshRole.TOR_GATEWAY,
            MeshRole.CLEARNET_GATEWAY,
            MeshRole.I2P_GATEWAY
        )
    }
    
    /**
     * Select single best gateway for given type
     * @param gatewayType Must be TOR_GATEWAY, CLEARNET_GATEWAY, or I2P_GATEWAY
     * @return GatewaySelectionResult indicating success, failure, or user preference
     */
    fun selectGateway(gatewayType: MeshRole): GatewaySelectionResult {
        // Validate gateway type
        if (gatewayType !in GATEWAY_ROLES) {
            logger(
                Log.ERROR,
                "$logPrefix Invalid gateway type: $gatewayType (not a gateway role)"
            )
            return GatewaySelectionResult.NoGatewayAvailable
        }
        
        // Check if user has enabled this gateway type
        val currentRoles = emergentRoleManager.currentMeshRoles.value
        if (gatewayType !in currentRoles) {
            logger(
                Log.INFO,
                "$logPrefix Gateway type $gatewayType disabled by user"
            )
            return GatewaySelectionResult.GatewayDisabledByUser
        }
        
        // Get all nodes offering this gateway (from topology map)
        val availableGateways = originatingMessageManager.getNodesWithRole(gatewayType)
        if (availableGateways.isEmpty()) {
            logger(
                Log.WARN,
                "$logPrefix No gateways available for $gatewayType"
            )
            return GatewaySelectionResult.NoGatewayAvailable
        }
        
        // Calculate suitability and select best
        val rankedGateways = availableGateways
            .filter { !it.isStale() }  // Filter out stale nodes
            .map { node ->
                GatewayNode(
                    nodeAddress = node.nodeAddress,
                    suitability = node.calculateGatewaySuitability(gatewayType),
                    hopCount = getHopCount(node.nodeAddress)
                )
            }
            .sortedByDescending { it.suitability }
        
        if (rankedGateways.isEmpty()) {
            logger(
                Log.WARN,
                "$logPrefix All gateways for $gatewayType are stale"
            )
            return GatewaySelectionResult.NoGatewayAvailable
        }
        
        val best = rankedGateways.first()
        logger(
                Log.INFO,
                "$logPrefix Selected gateway ${best.nodeAddress.addressToDotNotation()} " +
                    "for $gatewayType (suitability=${String.format(Locale.US, "%.3f", best.suitability)})"
        )
        
        return GatewaySelectionResult.SingleGateway(
            nodeAddress = best.nodeAddress,
            suitability = best.suitability,
            hopCount = best.hopCount
        )
    }
    
    /**
     * Select multiple gateways for multiplexed routing
     * @param gatewayType Gateway role type (TOR/CLEARNET/I2P)
     * @param maxCount Maximum number of gateways to select (default 3)
     * @param strategy Distribution strategy (default WEIGHTED)
     * @return GatewaySelectionResult with selected gateways or error
     */
    fun selectMultipleGateways(
        gatewayType: MeshRole,
        maxCount: Int = 3,
        strategy: DistributionStrategy = DistributionStrategy.WEIGHTED
    ): GatewaySelectionResult {
        // Validate gateway type
        if (gatewayType !in GATEWAY_ROLES) {
            logger(
                Log.ERROR,
                "$logPrefix Invalid gateway type: $gatewayType (not a gateway role)"
            )
            return GatewaySelectionResult.NoGatewayAvailable
        }
        
        // Check user preferences
        val currentRoles = emergentRoleManager.currentMeshRoles.value
        if (gatewayType !in currentRoles) {
            return GatewaySelectionResult.GatewayDisabledByUser
        }
        
        // Get available gateways
        val availableGateways = originatingMessageManager.getNodesWithRole(gatewayType)
        if (availableGateways.isEmpty()) {
            return GatewaySelectionResult.NoGatewayAvailable
        }
        
        // Rank and select top N
        val rankedGateways = availableGateways
            .filter { !it.isStale() }  // Filter out stale nodes
            .map { node ->
                val suitability = node.calculateGatewaySuitability(gatewayType)
                GatewayNode(
                    nodeAddress = node.nodeAddress,
                    suitability = suitability,
                    hopCount = getHopCount(node.nodeAddress),
                    weight = calculateWeight(suitability, strategy)
                )
            }
            .sortedByDescending { it.suitability }
            .take(maxCount)
        
        if (rankedGateways.isEmpty()) {
            logger(
                Log.WARN,
                "$logPrefix All gateways for $gatewayType are stale"
            )
            return GatewaySelectionResult.NoGatewayAvailable
        }
        
        logger(
            Log.INFO,
            "$logPrefix Selected ${rankedGateways.size} gateways for $gatewayType: " +
                    rankedGateways.joinToString { 
                        "${it.nodeAddress.addressToDotNotation()}(${String.format(Locale.US, "%.3f", it.suitability)})" 
                    }
        )
        
        return GatewaySelectionResult.MultipleGateways(
            gateways = rankedGateways,
            distributionStrategy = strategy
        )
    }
    
    /**
     * Calculate distribution weight based on strategy
     * @param suitability Gateway suitability score
     * @param strategy Distribution strategy
     * @return Weight value for load distribution
     */
    private fun calculateWeight(suitability: Float, strategy: DistributionStrategy): Float {
        return when (strategy) {
            DistributionStrategy.ROUND_ROBIN -> 1f
            DistributionStrategy.WEIGHTED -> suitability
            DistributionStrategy.LATENCY_AWARE -> suitability  // Already includes latency
            DistributionStrategy.FAILOVER -> if (suitability > 0.8f) 1f else 0.1f
        }
    }
    
    /**
     * Get hop count to reach a node
     * @param nodeAddress Virtual address of the node
     * @return Hop count, or Int.MAX_VALUE if node not reachable
     */
    private fun getHopCount(nodeAddress: Int): Int {
        val originatorMsg = originatingMessageManager.findOriginatingMessageFor(nodeAddress)
        return originatorMsg?.hopCount?.toInt() ?: Int.MAX_VALUE
    }
}
