package com.ustadmobile.meshrabiya.vnet

/**
 * Result of gateway selection operation.
 * Sealed class representing different outcomes of gateway selection.
 */
sealed class GatewaySelectionResult {
    /**
     * Single gateway was selected successfully
     * @param nodeAddress Virtual address of the selected gateway
     * @param suitability Gateway suitability score (0.0-1.0)
     * @param hopCount Number of hops to reach this gateway
     */
    data class SingleGateway(
        val nodeAddress: Int,
        val suitability: Float,
        val hopCount: Int
    ) : GatewaySelectionResult()
    
    /**
     * Multiple gateways were selected for multiplexed routing
     * @param gateways List of selected gateways (ordered by suitability)
     * @param distributionStrategy Strategy for distributing traffic across gateways
     */
    data class MultipleGateways(
        val gateways: List<GatewayNode>,
        val distributionStrategy: DistributionStrategy
    ) : GatewaySelectionResult()
    
    /**
     * No gateway available for the requested type
     */
    object NoGatewayAvailable : GatewaySelectionResult()
    
    /**
     * User has disabled this gateway type in preferences
     */
    object GatewayDisabledByUser : GatewaySelectionResult()
}

/**
 * Represents a single gateway node in the mesh network
 * @param nodeAddress Virtual address of the gateway node
 * @param suitability Gateway suitability score (0.0-1.0)
 * @param hopCount Number of hops to reach this gateway
 * @param weight Distribution weight for weighted load balancing (default 1.0)
 */
data class GatewayNode(
    val nodeAddress: Int,
    val suitability: Float,
    val hopCount: Int,
    val weight: Float = 1f  // For weighted distribution
)

/**
 * Strategy for distributing traffic across multiple gateways
 */
enum class DistributionStrategy {
    /**
     * Round-robin distribution: each gateway gets equal share
     * Simple and fair, recommended for MVP
     */
    ROUND_ROBIN,
    
    /**
     * Weighted distribution: based on suitability scores
     * Higher suitability gateways receive more traffic
     */
    WEIGHTED,
    
    /**
     * Latency-aware distribution: prefer lower latency gateways
     * Useful when response time is critical
     */
    LATENCY_AWARE,
    
    /**
     * Failover distribution: use primary gateway with backups
     * Only switch to backup if primary fails
     */
    FAILOVER
}
